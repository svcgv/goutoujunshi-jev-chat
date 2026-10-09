package com.jev.probe.capture

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import com.jev.probe.CapturePermissionActivity
import com.jev.probe.capture.ocr.CaptureHandoff
import com.jev.probe.core.kb.Contact
import com.jev.probe.core.kb.ConversationBinding
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.CrashLogger
import com.jev.probe.capture.ocr.MlKitOcr
import com.jev.probe.capture.ocr.OcrLine
import com.jev.probe.capture.ocr.ScreenCapture
import com.jev.probe.core.BubbleRect
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.GoutouGuidance
import com.jev.probe.core.WorkScope
import com.jev.probe.core.WorkToken
import com.jev.probe.core.kb.ChatContext
import com.jev.probe.core.kb.ContextBuilder
import com.jev.probe.core.kb.ConversationFingerprints
import com.jev.probe.core.kb.KbStore
import com.jev.probe.jev.JevClient
import com.jev.probe.jev.VisionClient
import com.jev.probe.overlay.OverlayController
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.CancellationException
import java.util.concurrent.FutureTask

internal fun shouldIgnoreAccessibilityEvents(reviewPending: Boolean): Boolean = reviewPending

internal fun shouldReviewOcr(manual: Boolean, autoAnalyze: Boolean, latestFrom: String?): Boolean =
    manual || (autoAnalyze && latestFrom == "other")

internal enum class ReviewConfirmationState {
    WAIT_FOR_CHAT_WINDOW,
    CHAT_CURRENT,
    CHAT_CHANGED
}

internal fun reviewConfirmationState(
    livePackage: String?,
    ownPackage: String,
    expectedChatPackage: String
): ReviewConfirmationState = when (livePackage) {
    null, ownPackage -> ReviewConfirmationState.WAIT_FOR_CHAT_WINDOW
    expectedChatPackage -> ReviewConfirmationState.CHAT_CURRENT
    else -> ReviewConfirmationState.CHAT_CHANGED
}

/**
 * The live capture service (registered under a disguised class name so WeChat
 * exposes its node tree — see the disguised subclass). It reads whichever
 * adapted chat app is in the foreground, detects a new incoming message from the
 * other person, runs Jev analysis off the main thread, and drives the floating
 * overlay.
 *
 * Per-app node rules live in [ChatAppAdapter] implementations; everything here
 * is app-agnostic.
 *
 * It never sends a message. The only write action is ACTION_SET_TEXT (or a
 * clipboard PASTE fallback) to fill the chat input box when the user taps
 * "填入"; the user still presses send.
 */
open class ChatCaptureService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)

    /** Adapted chat apps, keyed by package name. */
    private val adapters = listOf(WeChatAdapter(), QQAdapter(), XAdapter(), FeishuAdapter()).associateBy { it.pkg }

    /** Submit to the worker, ignoring rejection after the service is torn down
     *  (a stale overlay callback must never crash the process). */
    private fun submit(token: WorkToken = session.current, task: () -> Unit) {
        if (!session.isCurrent(token)) return
        val future = FutureTask<Unit> {
            try { WorkScope.run(token, task) }
            catch (_: CancellationException) { }
        }
        val detach = token.onCancel { future.cancel(true) }
        try { worker.execute { try { future.run() } finally { detach() } } }
        catch (_: RejectedExecutionException) { detach(); future.cancel(true) }
    }
    private lateinit var prefs: Prefs
    private var overlay: OverlayController? = null
    private val session = CaptureSession { ::prefs.isInitialized && prefs.enabled }
    private var detachPrefs: (() -> Unit)? = null

    private var lastSignature: String = ""
    private var activePkg: String? = null

    /** Manual identity only survives the current capture round, never an app switch. */
    private var externalCapturePending = false
    private var waitingExternalBitmap: Bitmap? = null
    private var manualWindowTitle: String? = null
    private val debounce = Runnable {
        val snapshot = pendingSnapshot
        if (snapshot != null) reviewSnapshot(snapshot, activePkg ?: foregroundPkg ?: "")
    }
    private var pendingSnapshot: ChatSnapshot? = null
    @Volatile private var currentSnapshot: ChatSnapshot? = null
    private var foregroundPkg: String? = null

    // ---- OCR path (B stage). Everything here runs on the main thread: the
    // screenshot callback and the ML Kit callback are both posted back to it.
    private val screenCapture by lazy {
        ScreenCapture(this,
            hideOverlay = { overlay?.setHiddenForShot(true) },
            restoreOverlay = { overlay?.setHiddenForShot(false) })
    }
    private val ocr = MlKitOcr()
    private var ocrBusy = false
    private var lastAnalysis: com.jev.probe.core.Analysis? = null
    private var lastAnalyzedSnapshot: ChatSnapshot? = null
    private var lastContext: ChatContext? = null

    /** What the screen looked like the last time we fired an automatic shot.
     *  See [ocrSignature]: this is the brake on the OCR path. */
    private var lastOcrSignature: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        session.setEnabled(prefs.enabled)
        overlay = OverlayController(this)
        overlay?.onHidden = { cancelWork() }
        detachPrefs = prefs.observeEnabled { enabled ->
            session.setEnabled(enabled)
            cancelWork()
            if (!enabled) overlay?.hide() else maybeCapture()
        }
        overlay?.onManualAnalyze = {
          guarded("analyze") {
            if (!session.reviewPending) {
                // Re-read the screen: a cached snapshot may belong to the chat the
                // user was in before returning to the list and opening another one.
                val root = rootInActiveWindow
                val pkg = root?.packageName?.toString().orEmpty()
                val adapter = adapters[pkg]
                val live = root?.let { r -> adapter?.extract(r, resources) }
                if (live != null && live.messages.isNotEmpty()) {
                    maybeCapture()
                    val fresh = currentSnapshot
                    if (fresh != null && fresh.messages.isNotEmpty()) {
                        reviewSnapshot(fresh, pkg)
                    }
                } else {
                    ocrCaptureManual()
                }
            }
          }
        }
        overlay?.onDetails = {
            val snapshot = lastAnalyzedSnapshot
            val analysis = lastAnalysis
            val ctx = lastContext
            val token = session.current
            val pkg = activePkg ?: foregroundPkg ?: ""
            if (snapshot == null || analysis == null || !snapshotIsCurrent(snapshot, pkg)) {
                overlay?.toast("请先核对并分析当前会话")
            } else submit(token) {
                val result = try { JevClient(prefs, applicationContext).details(snapshot, prefs.relationship, analysis, ctx) }
                             catch (_: Exception) { "详细分析暂不可用，请检查回复模型后重试。" }
                main.post { if (session.isCurrent(token) && snapshotIsCurrent(snapshot, pkg)) overlay?.showDetails(result) }
            }
        }
        overlay?.onExplain = { candidate ->
            val snapshot = lastAnalyzedSnapshot
            val analysis = lastAnalysis
            val ctx = lastContext
            val token = session.current
            val pkg = activePkg ?: foregroundPkg ?: ""
            if (snapshot == null || analysis == null ||
                analysis.rankedReplies.none { it.text == candidate } || !snapshotIsCurrent(snapshot, pkg)) {
                overlay?.toast("请先分析当前会话")
            } else submit(token) {
                val result = try { JevClient(prefs, applicationContext).explain(snapshot, prefs.relationship, analysis, candidate, ctx) }
                             catch (_: Exception) { "理由与代价暂不可用，请检查回复模型后重试。" }
                main.post { if (session.isCurrent(token) && snapshotIsCurrent(snapshot, pkg)) overlay?.showDetails(result, "回复理由与代价") }
            }
        }
        overlay?.onRewrite = {
            val snapshot = lastAnalyzedSnapshot
            val analysis = lastAnalysis
            val ctx = lastContext
            val token = session.current
            val pkg = activePkg ?: foregroundPkg ?: ""
            if (snapshot == null || analysis == null || analysis.rankedReplies.isEmpty() ||
                !snapshotIsCurrent(snapshot, pkg)) {
                overlay?.toast("请先生成当前会话的候选")
            } else {
                overlay?.toast("正在按你的原话调整口吻…")
                submit(token) {
                    val client = JevClient(prefs, applicationContext)
                    try {
                        val candidates = client.rewrite(snapshot, analysis,
                            analysis.rankedReplies.map { it.text })
                        val ranked = client.rerank(snapshot, prefs.relationship, analysis, candidates, ctx)
                        main.post {
                            if (session.isCurrent(token) && snapshotIsCurrent(snapshot, pkg)) {
                                lastAnalysis = analysis.copy(rankedReplies = ranked)
                                overlay?.showReplies(ranked) { text -> fillInput(text, snapshot, pkg) }
                            }
                        }
                    } catch (e: Exception) {
                        main.post { if (session.isCurrent(token)) overlay?.toast(e.message ?: "口吻调整失败；原候选已保留") }
                    }
                }
            }
        }
        overlay?.onSaveContact = { guarded("bind") { showBinding() } }
        overlay?.onProjectionCapture = { guarded("projection") { startExternalCapture(false) } }
        overlay?.onImportScreenshot = { guarded("import") { startExternalCapture(true) } }
        overlay?.onShowHistory = onShowHistory@{
            val snapshot = currentSnapshot
            if (snapshot == null || !snapshotIsCurrent(snapshot, activePkg.orEmpty())) {
                overlay?.showError("请先确认当前会话身份，再查看历史")
                return@onShowHistory
            }
            val store = KbStore.get(this)
            val binding = boundContact(snapshot.title, activePkg.orEmpty())
            val lines = binding?.let { store.recentLog(it.contactId, 100) }.orEmpty()
            overlay?.showDetails(if (binding == null) "请先绑定当前会话" else lines.joinToString("\n") {
                (if (it.side == "me") "我：" else "对方：") + it.text
            }.ifBlank { "没有保存过核对后的消息" }, "本机对象历史")
        }
        overlay?.onCalibrateTitleRegion = { guarded("calibrate") { startTitleCalibration() } }
        overlay?.onOcrCapture = { guarded("ocr") { ocrCaptureManual() } }
        // Keep the process at foreground importance so MIUI does not freeze us.
        runCatching { KeepAliveService.start(this) }
        // Load the bundled OCR model now, off the main thread: the first
        // recognize() otherwise pays for it inside the screenshot callback.
        submit { MlKitOcr.warmUp() }
        // HyperOS may kill and restart us. On (re)connect, proactively re-show the
        // bubble for whatever chat is already open, so it comes back on its own
        // instead of waiting for the user to scroll.
        main.postDelayed({ if (prefs.enabled) runCatching { maybeCapture() } }, 900)
        Log.i(TAG, "capture service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!prefs.enabled) { session.setEnabled(false); overlay?.hide(); return }
        session.setEnabled(true)

        val type = event.eventType
        // showReview() makes the overlay focusable so its EditText can be
        // corrected. That focus transition emits accessibility window events,
        // while rootInActiveWindow may report either our overlay or the chat app
        // underneath. Neither is a real navigation, so review/cancel must remain
        // the sole owners of this panel until the user chooses one.
        if (externalCapturePending || shouldIgnoreAccessibilityEvents(session.reviewPending)) return
        if (manualWindowTitle != null && type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Our own overlay taking focus for the binding/review editor, and IME
            // windows, are not navigation. Only a real departure from the chat
            // window invalidates a binding the user already confirmed.
            val liveRoot = rootInActiveWindow
            val livePkg = liveRoot?.packageName?.toString()
            val stillInChat = liveRoot?.let { adapters[activePkg]?.extract(it, resources) } != null
            if (ConversationSwitch.invalidatesOpaqueBinding(
                    event.packageName?.toString(), activePkg, packageName, livePkg, stillInChat)) {
                cancelWork()
            }
        }

        // Decide "did we leave the chat app" from the REAL active window, not the
        // event's package. The event package can be an IME (e.g. com.tencent.wetype)
        // or the status bar while the chat app is still foreground — keying off it
        // made the bubble flicker (hide → re-show → hide…). rootInActiveWindow stays
        // on the chat app while the keyboard is up, so this is stable.
        //
        // An app with no adapter is NOT a reason to take the bubble away: the only
        // way into DingTalk / Telegram / anything else is the bubble menu's
        // "截屏识别一次", and a bubble that is gone cannot be tapped. So we park
        // the idle bubble there instead — still no automatic capture, no analysis.
        // The bubble does come off for places where it would only be in the way:
        // our own settings screens, the launcher, and the system UI.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val fg = rootInActiveWindow?.packageName?.toString()
            when (ForegroundApp.classify(fg, packageName, adapters.keys)) {
                ForegroundApp.Kind.ChatApp -> Unit // handled below by maybeCapture()
                ForegroundApp.Kind.OwnApp -> {
                    // Our overlay window took/released focus. This is not a
                    // navigation: keep the round (and an in-flight fill) alive and
                    // leave the panel exactly as the user left it.
                    foregroundPkg = packageName
                    return
                }
                ForegroundApp.Kind.Foreign -> {
                    if (ForegroundApp.shouldCancelWork(ForegroundApp.Kind.Foreign)) cancelWork()
                    foregroundPkg = fg
                    val home = fg?.contains("launcher", ignoreCase = true) == true ||
                        fg == "com.miui.home" || fg == "com.android.systemui"
                    if (home) overlay?.hide() else overlay?.showIdle(null)
                    return
                }
            }
        }

        when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> maybeCapture()
        }
    }

    private fun maybeCapture() {
        if (!session.current.isActive() || session.reviewPending) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString()
        // NOTE: there used to be an early return here whenever the live title was
        // unreadable (always true for WeChat) and a confirmed name existed. It was
        // meant to keep our own overlay from looking like a chat switch, but it
        // also skipped the switch detection entirely: opening a different chat
        // kept the previous conversation's snapshot, so the assistant analysed the
        // wrong person. Focus changes are already handled in onAccessibilityEvent,
        // so the check is not needed here.
        // Apps with no adapter are never handled automatically (v1.3 revision):
        // the only way in for them is the bubble menu's "截屏识别一次".
        val adapter = adapters[pkg] ?: return
        // Only act inside a chat window (the adapter returns null elsewhere).
        val rawSnapshot = adapter.extract(root, resources)
        if (rawSnapshot == null) {
            if (activePkg != null) cancelWork()
            if (pkg == WECHAT_PACKAGE) {
                foregroundPkg = pkg
                overlay?.setBindingSummary("窗口未识别 · 请进入一对一聊天后截屏或导入")
                overlay?.showIdle(null)
            }
            return
        }
        if (rawSnapshot.isGroup) { cancelWork(); overlay?.showError("当前版本只支持一对一聊天，不分析群聊"); return }
        // Stabilize the title BEFORE anything below reads it: some apps (X) show
        // a transient "连接中…" title for a moment right after opening a thread.
        val snapshot = stabilizeTitle(rawSnapshot)
        if (!prefs.isAllowed(snapshot.title)) { overlay?.hide(); return }
        val trackedKey = currentSnapshot?.let { conversationKey(it.title) }
        val incomingKey = conversationKey(snapshot.title)
        // Title-only comparison is blind on WeChat, where the title is never
        // exposed: switching chats then kept the previous conversation. Fall back
        // to the messages themselves when the keys cannot tell them apart.
        val sameKeyOrUnknown = !ConversationSwitch.isNewConversation(
            pkg != activePkg, trackedKey, incomingKey)
        val switchedByMessages = sameKeyOrUnknown && currentSnapshot != null &&
            ConversationSwitch.isDifferentByMessages(
                currentSnapshot!!.messages.map { it.text }, snapshot.messages.map { it.text })
        if (switchedByMessages || ConversationSwitch.isNewConversation(
                pkg != activePkg, trackedKey, incomingKey)) {
            cancelWork()
            activePkg = pkg
            currentSnapshot = snapshot
            // Moved to a different conversation: put the panel away. It comes
            // back only when the user taps the bubble.
            overlay?.collapse()
            overlay?.resetForNewConversation()
        } else if (currentSnapshot == null) {
            activePkg = pkg
            currentSnapshot = snapshot
        }
        updateBindingSummary(snapshot, pkg.orEmpty())
        if (pkg == WECHAT_PACKAGE && snapshot.messages.isEmpty()) {
            overlay?.showIdle(snapshot.title)
            return // Screenshot permission is user initiated, never prompted by background events.
        }
        // In a chat window but the tree holds no text (Feishu draws its bodies,
        // WeChat hides them when the disguise fails) → screenshot + OCR, subject
        // to ScreenCapture's own >=1s throttle and failure backoff.
        if (snapshot.messages.isEmpty()) {
            if (prefs.ocrFallback) {
                // Gate BEFORE the shot, not after the OCR. Feishu's tree is empty
                // on every content-changed event, and a successful shot resets the
                // failure backoff — so without this the caret blinking or an
                // "online" badge flipping keeps a screenshot going out every
                // second forever. The picture can only differ if the bubbles moved
                // or the conversation changed, and that is exactly what the
                // signature measures.
                val sig = ocrSignature(pkg ?: "", snapshot.title, snapshot.bubbleRects)
                if (sig == lastOcrSignature && overlay?.isShowing() == true) return
                lastOcrSignature = sig
                ocrCapture(snapshot, snapshot.bubbleRects, pkg ?: "", manual = false)
            }
            return
        }

        // Switching to another adapted app resets the dedupe signature, so two apps
        // whose last few messages happen to match cannot swallow each other.
        if (pkg != activePkg) { activePkg = pkg; lastSignature = "" }

        val sig = snapshot.signature()
        val showing = overlay?.isShowing() == true
        // Same content and the bubble is already up → nothing to do.
        if (sig == lastSignature && showing) return
        // Same content but the bubble is gone (killed by MIUI, or we left and came
        // back) → just put the bubble back, do NOT re-analyze (saves tokens/time).
        if (sig == lastSignature && !showing) { overlay?.showIdle(snapshot.title); return }
        cancelWork()
        activePkg = pkg
        currentSnapshot = snapshot
        // Anything else reaching here is a genuinely different conversation (new
        // app, or new content in this one) — a leftover judgment/candidates from
        // whatever was shown before must not leak into it.
        overlay?.resetForNewConversation()
        lastSignature = sig
        updateBindingSummary(snapshot, pkg.orEmpty())
        Log.d(TAG, "snapshot[$pkg] title=${snapshot.title} n=${snapshot.messages.size} " +
            snapshot.messages.takeLast(6).joinToString(" | ") { "${it.side}:${it.text.length}" }) // sides + lengths only, never content

        // Trigger only when the newest message is from the other person, and only
        // if auto-analyze is on. Otherwise show the idle bubble (tap to analyze).
        if (snapshot.latestFrom != "other" || !prefs.autoAnalyze) {
            overlay?.showIdle(snapshot.title); return
        }

        pendingSnapshot = snapshot
        main.removeCallbacks(debounce)
        main.postDelayed(debounce, 800) // debounce bursts of content-changed events
    }

    /** A placeholder title an app shows only for a moment (e.g. X's "连接中…"
     *  right after opening a DM thread) — never a real conversation title.
     *  Blank/null counts too, so a caller can always fall back the same way. */
    private fun isTransientTitle(t: String?): Boolean {
        val trimmed = t?.trim()?.removeSuffix("…")?.removeSuffix("...")?.trim()
        if (trimmed.isNullOrEmpty()) return true
        val lower = trimmed.lowercase()
        return TRANSIENT_TITLE_WORDS.any { lower.contains(it.lowercase()) }
    }

    /** Missing identity must not inherit another person's title. */
    private fun stabilizeTitle(snapshot: ChatSnapshot): ChatSnapshot =
        if (isTransientTitle(snapshot.title)) snapshot.copy(title = null) else snapshot

    /** Invalidate every callback before clearing UI state or starting another round. */
    private fun cancelWork() {
        session.reset()
        externalCapturePending = false
        waitingExternalBitmap?.recycle(); waitingExternalBitmap = null
        manualWindowTitle = null
        CaptureHandoff.cancel()
        overlay?.setHiddenForShot(false)
        stopService(Intent(this, com.jev.probe.capture.ocr.ProjectionCaptureService::class.java))
        main.removeCallbacksAndMessages(null)
        pendingSnapshot = null
        currentSnapshot = null
        activePkg = null
        lastSignature = ""
        lastOcrSignature = ""
        ocrBusy = false
        lastAnalysis = null
        lastAnalyzedSnapshot = null
        lastContext = null
        overlay?.resetForNewConversation()
    }

    /**
     * The key that identifies the on-screen conversation. A readable title wins;
     * an unreadable one (WeChat and other self-drawn windows) falls back to the
     * name the user confirmed, so a bound window stays bound instead of asking
     * the user to bind again on every capture.
     */
    private fun conversationKey(title: String?): String? =
        ConversationBindingKey.resolve(title, manualWindowTitle)

    /**
     * The contact bound to this conversation, or null when it is not bound yet.
     *
     * A readable title identifies the conversation by itself. When the title is
     * unreadable (WeChat and other self-drawn windows) the confirmed name stands
     * in for it — but that name only lived in memory, so after the service
     * restarted the key could not be rebuilt and the user was asked to bind the
     * same chat again forever. In that situation, if this app has exactly ONE
     * binding, it is unambiguous and is reused. With two or more opaque bindings
     * in the same app we cannot tell them apart and the user must confirm.
     */
    private fun boundContact(title: String?, pkg: String): ConversationBinding? {
        val store = KbStore.get(this)
        val key = conversationKey(title)
        if (key != null) {
            store.binding(key, pkg)?.let {
                if (manualWindowTitle.isNullOrBlank()) manualWindowTitle = it.title
                Log.i(TAG, "bind lookup pkg=$pkg keyLen=${key.length} hit=byKey")
                return it
            }
        }
        // Title unreadable: identify the conversation by the messages on screen.
        val onScreen = currentSnapshot?.messages?.map { it.text }.orEmpty()
        ConversationFingerprints.match(store.bindingsForApp(pkg), pkg, onScreen)?.let {
            manualWindowTitle = it.title
            Log.i(TAG, "bind lookup pkg=$pkg hit=byFingerprint")
            return it
        }
        // Last resort: a single binding in this app is unambiguous.
        val rows = store.bindings().filter { it.app == pkg }
        val only = rows.singleOrNull()
        if (only != null) manualWindowTitle = only.title
        Log.i(TAG, "bind lookup pkg=$pkg titleLen=${title?.length ?: -1} " +
            "keyLen=${key?.length ?: -1} appRows=${rows.size} fallbackHit=${only != null}")
        return only
    }

    /**
     * Runs an overlay callback defensively.
     *
     * The overlay lives in this same process, so an exception raised while
     * handling a tap would otherwise crash the app and silently drop the
     * accessibility service. Anything unexpected is recorded and shown, so the
     * UI stays alive and the failure is diagnosable.
     */
    private fun guarded(where: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "overlay callback failed: $where", t)
            CrashLogger.recordCaught(this, "overlay:$where", t)
            runCatching { overlay?.showError("操作失败：${t.javaClass.simpleName}（已记录，可重试）") }
        }
    }

    private fun updateBindingSummary(snapshot: ChatSnapshot, pkg: String) {
        val store = KbStore.get(this)
        val binding = boundContact(snapshot.title, pkg)
        val contact = binding?.let { store.contact(it.contactId) }
        overlay?.setBindingSummary(if (contact == null) "未绑定对象 · 不加载历史"
            else "对象：${contact.name} · 已存 ${store.logSize(contact.id)} 条 · " +
                if (binding.remember && prefs.contextEnabled) "记忆已启用" else "记忆暂停")
    }

    private fun showBinding(afterSave: (() -> Unit)? = null) {
        val pkg = activePkg ?: foregroundPkg ?: rootInActiveWindow?.packageName?.toString().orEmpty()
        if (pkg.isBlank() || pkg == packageName) { overlay?.toast("请先进入聊天窗口"); return }
        val snapshot = currentSnapshot ?: ChatSnapshot(null, emptyList())
        if (!prefs.isAllowed(snapshot.title)) { overlay?.showError("此会话不在白名单内"); return }
        val title = snapshot.title?.takeUnless { isTransientTitle(it) }.orEmpty()
        val store = KbStore.get(this)
        val visible = rootInActiveWindow
        val liveBefore = visible?.let { adapters[pkg]?.extract(it, resources) }
        if (visible?.packageName?.toString() != pkg || liveBefore?.isGroup == true ||
            (title.isNotBlank() && !isTransientTitle(liveBefore?.title) && liveBefore?.title != title)) {
            cancelWork(); overlay?.showError("当前会话无法确认，请重新识别"); return
        }
        val bound = boundContact(title, pkg)
        // Adopt the bound name as this window's key for the current session, so
        // repeated reads of a title-less window keep resolving to the same object.
        if (title.isBlank()) {
            bound?.let { manualWindowTitle = it.title }
        }
        val token = session.beginReview() ?: return
        /**
         * Run [action] after the editor has released focus.
         *
         * This deliberately does NOT require the chat app to become the active
         * window again: the binding panel is focusable and the IME may hold focus,
         * which previously timed out and was misread as "conversation changed",
         * so the binding was never written and the user was asked to bind again.
         * The chat window is verified only as a safety check when it IS visible.
         */
        fun done(action: () -> Unit) {
            fun waitForChat(attempt: Int) {
                if (!session.isCurrent(token)) return
                val root = rootInActiveWindow
                val actualPkg = root?.packageName?.toString()
                if (actualPkg == packageName && attempt < 20) {
                    // Our overlay still owns focus; give it a moment to hand back.
                    main.postDelayed({ waitForChat(attempt + 1) }, 50); return
                }
                val live = root?.takeIf { actualPkg == pkg }?.let { adapters[pkg]?.extract(it, resources) }
                if (actualPkg == pkg && (live?.isGroup == true ||
                        (!live?.title.isNullOrBlank() && title.isNotBlank() && live?.title != title))) {
                    cancelWork(); overlay?.showError("会话已切换，请重新选择对象"); return
                }
                session.confirmReview(token)
                action()
            }
            waitForChat(0)
        }
        fun idle() { updateBindingSummary(currentSnapshot ?: snapshot, pkg); overlay?.showIdle(currentSnapshot?.title) }
        overlay?.showBinding(title, store.contacts(), bound?.contactId, bound?.remember == true && prefs.contextEnabled,
            onSave = { id, name, remember -> done {
                val identityTitle = title.ifBlank { name }
                if (identityTitle.isBlank()) { overlay?.showError("请填写当前会话称呼"); return@done }
                val contactId = id ?: KbStore.newId()
                if (id == null && !store.saveContact(Contact(contactId, name, apps = listOf(pkg)))) {
                    overlay?.showError("对象档案保存失败"); return@done
                }
                val fingerprint = ConversationFingerprints.of(snapshot.messages.map { it.text })
                if (!store.bind(ConversationBinding(pkg, identityTitle, contactId, remember, fingerprint))) {
                    overlay?.showError("绑定保存失败，未启用记忆"); return@done
                }
                if (remember) prefs.contextEnabled = true
                if (title.isBlank()) manualWindowTitle = identityTitle
                // The tracked snapshot carries the confirmed name; for an opaque
                // window that name also becomes the stable lookup key, so the next
                // capture matches instead of looking like a new conversation.
                currentSnapshot = snapshot.copy(title = identityTitle)
                activePkg = pkg
                lastAnalysis = null; lastContext = null; lastAnalyzedSnapshot = null
                overlay?.resetForNewConversation()
                idle()
                afterSave?.invoke()
            } },
            onUnbind = { done {
                if (!store.unbind(title, pkg)) overlay?.toast("解除绑定失败")
                lastAnalysis = null; lastContext = null; lastAnalyzedSnapshot = null
                overlay?.resetForNewConversation(); idle()
            } },
            onClear = { done {
                if (bound == null) overlay?.toast("当前没有已绑定对象")
                else overlay?.toast(if (store.clearHistory(bound.contactId)) "历史已清空" else "清空失败")
                lastContext = null; lastAnalysis = null; lastAnalyzedSnapshot = null
                overlay?.resetForNewConversation(); idle()
            } },
            onCancel = { done { overlay?.resetForNewConversation(); idle() } })
    }

    /**
     * Captures the current chat window and opens the title-region picker.
     *
     * The region is per chat app, so the screenshot is tagged with the package it
     * came from; the picker stores it only for that package.
     */
    private fun startTitleCalibration() {
        if (!session.current.isActive() || session.reviewPending) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg.isBlank() || pkg == packageName) {
            overlay?.toast("请先进入微信或 QQ 的聊天窗口"); return
        }
        val token = session.current
        val wasExpanded = true
        overlay?.setHiddenForShot(true)
        screenCapture.capture { res ->
            if (!session.isCurrent(token)) {
                if (res is ScreenCapture.Result.Ok) runCatching { res.bitmap.recycle() }
                overlay?.setHiddenForShot(false)
                return@capture
            }
            overlay?.setHiddenForShot(false)
            when (res) {
                is ScreenCapture.Result.Failed -> overlay?.showError(res.humanMessage)
                is ScreenCapture.Result.Ok -> {
                    com.jev.probe.calibrate.TitleCalibrationHandoff.put(res.bitmap, pkg)
                    try {
                        startActivity(Intent(this, com.jev.probe.calibrate.TitleCalibrationActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                        com.jev.probe.calibrate.TitleCalibrationHandoff.clear()
                        overlay?.showError("无法打开标题区域设置")
                    }
                }
            }
        }
    }

    private fun startExternalCapture(import: Boolean) {
        if (!session.current.isActive() || session.reviewPending || externalCapturePending) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg != WECHAT_PACKAGE && pkg != "com.tencent.mobileqq") {
            overlay?.toast("请先进入微信或 QQ 的一对一聊天"); return
        }
        val live = adapters[pkg]?.extract(root, resources)
        if (live?.isGroup == true) { overlay?.showError("暂不支持群聊"); return }
        val title = live?.title?.takeUnless { isTransientTitle(it) }
        if (!prefs.isAllowed(title)) { overlay?.showError("此会话不在白名单内"); return }
        cancelWork()
        activePkg = pkg
        currentSnapshot = ChatSnapshot(title, emptyList())
        val token = session.current
        externalCapturePending = true
        overlay?.setHiddenForShot(true)
        val requestId = CaptureHandoff.begin(allowFrame = {
            val active = rootInActiveWindow
            val visible = active?.let { adapters[pkg]?.extract(it, resources) }
            session.isCurrent(token) && active?.packageName?.toString() == pkg &&
                visible?.isGroup != true && (title == null || visible?.title == title)
        }) { bitmap, error ->
            waitingExternalBitmap = bitmap
            fun resume(attempt: Int) {
                if (!session.isCurrent(token)) { bitmap?.recycle(); return }
                if (bitmap == null) {
                    externalCapturePending = false
                    overlay?.setHiddenForShot(false)
                    overlay?.showError(error ?: "没有获得截图"); return
                }
                val active = rootInActiveWindow
                val actual = active?.packageName?.toString()
                if (actual != pkg && attempt < 40) {
                    main.postDelayed({ resume(attempt + 1) }, 100); return
                }
                val fresh = active?.let { adapters[pkg]?.extract(it, resources) }
                if (actual != pkg || fresh?.isGroup == true ||
                    (title != null && fresh?.title != title)) {
                    bitmap.recycle(); cancelWork(); overlay?.setHiddenForShot(false)
                    overlay?.showError("截屏前后会话无法确认，请回到原聊天窗口重试"); return
                }
                // Keep the external-flow guard until OCR has finished. Always confirm the
                // target for imported images (which may depict a different conversation).
                waitingExternalBitmap = null // OCR callback now owns this bitmap.
                ocr.scaleX = 1f; ocr.scaleY = 1f; ocr.originX = 0; ocr.originY = 0
                val region = screenToBitmapRegion(fresh, bitmap)
                ocr.recognize(bitmap, region) { lines ->
                    bitmap.recycle()
                    if (!session.isCurrent(token)) return@recognize
                    externalCapturePending = false
                    overlay?.setHiddenForShot(false)
                    val now = rootInActiveWindow
                    val nowSnapshot = now?.let { adapters[pkg]?.extract(it, resources) }
                    if (now?.packageName?.toString() != pkg || nowSnapshot?.isGroup == true ||
                        (title != null && nowSnapshot?.title != title)) {
                        cancelWork(); overlay?.showError("识别时会话发生变化，请重试"); return@recognize
                    }
                    val snapshot = ChatSnapshot(title, groupOcrLines(lines), note =
                        "${if (import) "导入截图" else "系统授权截屏"} · 只支持一对一，请核对截图对象、原文和双方身份")
                    currentSnapshot = snapshot
                    activePkg = pkg
                    if (snapshot.messages.isEmpty()) { overlay?.showError("没有识别到文字；受保护画面无法读取"); return@recognize }
                    updateBindingSummary(snapshot, pkg)
                    showBinding { reviewSnapshot(currentSnapshot ?: snapshot, pkg) }
                }
            }
            resume(0)
        }
        try {
            startActivity(Intent(this, CapturePermissionActivity::class.java)
                .putExtra("requestId", requestId).putExtra("import", import).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            CaptureHandoff.finish(requestId, null, "无法打开系统截屏或导入入口")
        }
    }

    private fun runAnalysis(token: WorkToken = session.current) {
        if (!session.isCurrent(token) || session.analyzing) return
        val snapshot = pendingSnapshot ?: return
        pendingSnapshot = null
        val pkg = activePkg ?: foregroundPkg ?: ""
        if (!snapshotIsCurrent(snapshot, pkg)) return
        if (!prefs.hasKey() && !GoutouGuidance.explicitBoundary(snapshot)) {
            overlay?.showError("请先在设置里配置策略接口地址、模型 ID 和密钥"); return
        }
        if (!session.beginAnalysis(token)) return
        overlay?.showLoading(); overlay?.setNote(snapshot.note); overlay?.setSnapshot(snapshot)
        val client = JevClient(prefs, applicationContext)
        val rel = prefs.relationship
        submit(token) {
            val ctx = try {
                ContextBuilder.build(this, snapshot, pkg, prefs)
            } catch (e: Exception) {
                Log.w(TAG, "context build failed: ${e.javaClass.simpleName}"); null
            }
            main.post {
                if (session.isCurrent(token) && snapshotIsCurrent(snapshot, pkg))
                    overlay?.setContextInfo(ctx?.notes?.size ?: 0, ctx?.history?.size ?: 0)
            }

            // Judgment, draft and rank share one cancellation scope.
            token.checkActive()
            val judgment = client.judge(snapshot, rel, ctx)
            if (judgment.error != null) {
                finishAnalysis(token, snapshot, pkg) { overlay?.showError(judgment.error) }
            } else {
                main.post { if (session.isCurrent(token) && snapshotIsCurrent(snapshot, pkg)) overlay?.showJudgment(judgment) }
                token.checkActive()
                var replyError: String? = null
                val ranked = try { client.draftAndRank(snapshot, rel, ctx, judgment) } catch (e: Exception) {
                    replyError = e.message ?: e.javaClass.simpleName
                    emptyList()
                }
                finishAnalysis(token, snapshot, pkg) {
                    lastAnalysis = judgment.copy(rankedReplies = ranked)
                    lastAnalyzedSnapshot = snapshot
                    lastContext = ctx
                    overlay?.showReplies(ranked, replyError) { text -> fillInput(text, snapshot, pkg) }
                }
            }
        }
    }

    private fun snapshotIsCurrent(snapshot: ChatSnapshot, pkg: String): Boolean {
        if (!session.current.isActive()) return false
        val current = currentSnapshot ?: return false
        val root = rootInActiveWindow ?: return false
        // While our own focusable panel (binding/review editor) owns focus,
        // rootInActiveWindow is us, not the chat app. That is not a switch, so
        // fall back to the package this round was started for.
        val livePackage = root.packageName?.toString()
        if (livePackage == packageName) {
            return pkg.isNotBlank() && current.title == snapshot.title &&
                current.signature() == snapshot.signature()
        }
        if (pkg.isBlank() || livePackage != pkg ||
            current.title != snapshot.title || current.signature() != snapshot.signature()) return false
        // A new chat can be visible before OCR finishes and updates currentSnapshot.
        // Check the live title too, so the previous chat's result cannot flash over it.
        val adapter = adapters[pkg] ?: return true // manual OCR in an unadapted app
        val live = adapter.extract(root, resources)
        return ConversationIdentity.matches(pkg, snapshot.title, root.packageName?.toString(),
            live?.title?.takeUnless { isTransientTitle(it) }, live?.isGroup == true, manualWindowTitle)
    }

    private fun finishAnalysis(token: WorkToken, snapshot: ChatSnapshot, pkg: String, display: () -> Unit) {
        main.post {
            if (!session.finishAnalysis(token)) return@post
            if (snapshotIsCurrent(snapshot, pkg)) display()
        }
    }

    // ------------------------------------------------------------------ OCR

    /**
     * Bubble menu → "截屏识别一次". Works on ANY app, adapted or not: one whole
     * screen shot, every line OCR'd, lines grouped into pseudo-bubbles by line
     * spacing. Nobody can tell who said what this way, so everything is filed as
     * the other person and the panel says so.
     */
    private fun ocrCaptureManual() {
        if (!session.current.isActive()) return
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: foregroundPkg ?: activePkg ?: ""
        // Top bar text, if this app has one we can read; else the first OCR line.
        val extracted = root?.let { adapters[pkg]?.extract(it, resources) }
        if (extracted?.isGroup == true) { overlay?.showError("暂不支持群聊"); return }
        val title = extracted?.title ?: root?.let {
            findTitleInActionBar(it, Int.MAX_VALUE, resources.displayMetrics.widthPixels, resources, 0.15, 0.85)
        }
        // Remember a usable name BEFORE cancelWork(), which clears session state
        // (including the confirmed name) and would otherwise make the identity
        // check fail for a window whose own title is unreadable.
        val remembered = manualWindowTitle
            ?: title?.takeUnless { isTransientTitle(it) }
            ?: boundContact(title, pkg)?.title
        cancelWork()
        activePkg = pkg
        manualWindowTitle = remembered
        ocrCapture(currentSnapshot ?: extracted, emptyList(), pkg, manual = true)
    }

    /**
     * What the screen would look like to a camera, as far as the tree can tell.
     *
     * Feishu: the conversation title plus every bubble rectangle and its side —
     * the bubbles move whenever the list scrolls or a message arrives, and stay
     * put when only chrome (caret, presence dot, timestamp) redraws. Apps that
     * give us no rectangles fall back to package + title, which at least stops a
     * burst of events on one screen from becoming a burst of screenshots.
     */
    private fun ocrSignature(pkg: String, title: String?, rects: List<BubbleRect>): String {
        if (rects.isEmpty()) return pkg + "|" + (title ?: "")
        return (title ?: "") + "|" + rects.joinToString(";") { br ->
            val r = br.rect
            "${r.left},${r.top},${r.right},${r.bottom},${br.side}"
        }
    }

    /**
     * Screenshot, then either OCR each known bubble rect (Feishu: the tree knows
     * where the bubbles are and who sent them, just not what they say) or OCR
     * the whole screen (everything else).
     */
    private fun ocrCapture(snapshot: ChatSnapshot?, rects: List<BubbleRect>, pkg: String, manual: Boolean) {
        val treeTitle = snapshot?.title
        val token = session.current
        if (ocrBusy || !token.isActive()) return
        ocrBusy = true
        screenCapture.capture { res ->
            if (!session.isCurrent(token)) {
                if (res is ScreenCapture.Result.Ok) runCatching { res.bitmap.recycle() }
                return@capture
            }
            when (res) {
                is ScreenCapture.Result.Failed -> {
                    ocrBusy = false
                    Log.i(TAG, "ocr: screenshot failed code=${res.code}")
                    // Nothing was read, so the signature must not claim this screen
                    // is done — the next event may retry, still held back by
                    // ScreenCapture's own throttle and failure backoff.
                    if (!manual) lastOcrSignature = ""
                    // Throttle/interval codes are transient timing, not something
                    // the user can act on — nagging about them would be constant.
                    val transient = res.code == ScreenCapture.CODE_THROTTLED || res.code == 3
                    if (manual || !transient) overlay?.showError(res.humanMessage)
                }
                is ScreenCapture.Result.Ok -> {
                    if (prefs.ocrEngine == Prefs.OCR_VISION) {
                        ocrCloud(res.bitmap, snapshot, pkg, manual, token)
                        return@capture
                    }
                    ocr.scaleX = res.scaleX; ocr.scaleY = res.scaleY
                    ocr.originX = res.originX; ocr.originY = res.originY
                    if (rects.isNotEmpty() && !manual) {
                        // Re-measure inside the callback. The rects handed in were
                        // read before the 120ms overlay-hide wait and the shot
                        // itself; one scroll tick in between and we would crop the
                        // rows next to the ones in the picture. Fall back to the
                        // old rects only if the tree gives us nothing now.
                        val fresh = rootInActiveWindow?.let { collectFeishuBubbleRects(it, resources) }
                        ocrByRects(res.bitmap, if (fresh.isNullOrEmpty()) rects else fresh, treeTitle, pkg, token)
                    } else ocrWholeScreen(res.bitmap, snapshot, pkg, manual, token)
                }
            }
        }
    }

    /** One cropped image per round, rather than a paid request for every bubble. */
    private fun ocrCloud(bmp: Bitmap, snapshot: ChatSnapshot?, pkg: String, manual: Boolean, token: WorkToken) {
        val title = snapshot?.title
        val bounds = screenToBitmapRegion(snapshot, bmp)
        val top = bounds.top
        val bottom = bounds.bottom
        val crop = Bitmap.createBitmap(bmp, 0, top, bmp.width, bottom - top)
        runCatching { bmp.recycle() }
        val detachCrop = token.onCancel { runCatching { crop.recycle() } }
        if (!VisionClient.supportsVision(prefs.visionBaseUrl) || prefs.effectiveVisionKey().isBlank()) {
            runCatching { crop.recycle() }
            detachCrop()
            ocrBusy = false
            overlay?.showError("请配置支持图片输入的视觉接口与密钥")
            return
        }
        submit(token) {
            val result = try {
                val encoded = VisionClient.encodeJpeg(crop)
                VisionClient(prefs).extractDialog(encoded)
            } catch (_: Exception) { "" }
            finally { detachCrop(); runCatching { crop.recycle() } }
            main.post {
                if (!session.isCurrent(token)) return@post
                if (result.isBlank()) {
                    ocrBusy = false
                    overlay?.showError("视觉模型未识别出聊天文字，请检查模型或改用本地 OCR")
                    return@post
                }
                val messages = result.lineSequence().mapNotNull { line ->
                    val clean = line.trim().trimStart('-', '•', ' ')
                    when {
                        clean.startsWith("我：") -> Msg("me", clean.removePrefix("我：").trim())
                        clean.startsWith("对方：") -> Msg("other", clean.removePrefix("对方：").trim())
                        else -> null
                    }
                }.filter { it.text.isNotBlank() }.take(60).toList()
                finishOcrSnapshot(ChatSnapshot(title, messages,
                    note = "视觉模型识图；原文和双方身份必须核对，截图已发送至所选服务"), pkg, manual, token)
            }
        }
    }

    /** One OCR pass per bubble rectangle; each rect becomes exactly one message. */
    private fun ocrByRects(bmp: Bitmap, rects: List<BubbleRect>, title: String?, pkg: String, token: WorkToken) {
        val sx = ocr.scaleX; val sy = ocr.scaleY
        // Screen -> bitmap: drop the window origin first. A window shot does not
        // start at (0,0) in split screen or when it excludes the status bar.
        val ox = ocr.originX; val oy = ocr.originY
        val out = arrayOfNulls<Msg>(rects.size)
        var remaining = rects.size
        rects.forEachIndexed { i, br ->
            val region = Rect(
                ((br.rect.left - ox) * sx).toInt(), ((br.rect.top - oy) * sy).toInt(),
                ((br.rect.right - ox) * sx).toInt(), ((br.rect.bottom - oy) * sy).toInt())
            ocr.recognize(bmp, region) { lines ->
                val text = cleanBubbleText(lines.joinToString(" ") { it.text })
                if (text.isNotEmpty()) out[i] = Msg(br.side, text)
                remaining--
                if (remaining == 0) {
                    runCatching { bmp.recycle() }
                    finishOcrSnapshot(ChatSnapshot(title, out.filterNotNull()), pkg, manual = false, token = token)
                }
            }
        }
    }

    /**
     * The message area, grouped into pseudo-bubbles by line spacing.
     *
     * The crop comes from the adapter's reported viewport (first message to the
     * input box) via [screenToBitmapRegion]; an unreadable edge stays uncropped.
     * The conversation title is not in that region, so it is read separately from
     * the title strip, which WeChat draws itself and never exposes to the tree.
     */
    private fun ocrWholeScreen(bmp: Bitmap, snapshot: ChatSnapshot?, pkg: String, manual: Boolean, token: WorkToken) {
        ocrTitle(bmp, snapshot?.title, pkg) { title ->
            val region = screenToBitmapRegion(snapshot, bmp)
            ocr.recognize(bmp, region) { lines ->
                runCatching { bmp.recycle() }
                val msgs = groupOcrLines(lines)
                finishOcrSnapshot(ChatSnapshot(title, msgs, note = OCR_NOTE), pkg, manual, token)
            }
        }
    }

    /**
     * Reads the conversation name from the title strip.
     *
     * A title already read from the node tree always wins. Otherwise the strip is
     * OCR'd on-device; when that yields nothing usable the tree title (possibly
     * null) is kept, so an unreadable name is never invented.
     */
    private fun ocrTitle(bmp: Bitmap, treeTitle: String?, pkg: String, done: (String?) -> Unit) {
        val tree = treeTitle?.takeIf { it.isNotBlank() }
        if (tree != null) { done(tree); return }
        // The title area is whatever the user selected for THIS app during
        // calibration (WeChat and QQ differ). With no saved region we do not
        // guess at a band: an invented crop could clip the name or read a message
        // instead, which is exactly what went wrong before. Reading nothing is
        // the honest outcome.
        val region = TitleRegions.decode(prefs.titleRegions)[pkg]
        val px = region?.pixelsFor(bmp.width, bmp.height)
        if (px == null) { done(null); return }
        val band = Rect(px[0], px[1], px[2], px[3])
        ocr.recognize(bmp, band) { lines ->
            val picked = ChatTitle.pick(lines.map { it.text to it.bounds.top })
            Log.i(TAG, "ocr title region lines=${lines.size} picked=${picked != null}")
            done(picked)
        }
    }

    /**
     * OCR lines → "bubbles": a gap larger than 1.2x the previous line's height
     * starts a new one. Side is unknowable from a flat screen read, so every
     * group is filed as the other person (and [OCR_NOTE] says so on the panel).
     */
    private fun groupOcrLines(lines: List<OcrLine>): List<Msg> {
        val usable = lines
            .filter { it.text.isNotBlank() && !PURE_TIME.matches(it.text.trim()) }
            .sortedBy { it.bounds.top }
        val out = ArrayList<Msg>()
        val buf = StringBuilder()
        var prev: OcrLine? = null
        for (l in usable) {
            val p = prev
            if (p != null) {
                val gap = l.bounds.top - p.bounds.bottom
                val lineHeight = maxOf(p.bounds.height(), 1)
                if (gap > lineHeight * 1.2f) {
                    if (buf.isNotEmpty()) { out.add(Msg("unknown", buf.toString())); buf.setLength(0) }
                }
            }
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(l.text.trim())
            prev = l
        }
        if (buf.isNotEmpty()) out.add(Msg("unknown", buf.toString()))
        return out
    }

    /** Strip the read receipt and the timestamp Feishu glues onto a bubble. */
    private fun cleanBubbleText(raw: String): String {
        var t = raw.trim()
        var changed = true
        while (changed && t.isNotEmpty()) {
            changed = false
            for (tail in arrayOf("已读", "未读")) {
                if (t.endsWith(tail)) { t = t.removeSuffix(tail).trim(); changed = true }
            }
            TAIL_TIME.find(t)?.let { t = t.substring(0, it.range.first).trim(); changed = true }
        }
        return t
    }

    /** Shared tail of both OCR paths: dedupe, then analyze or park the bubble. */
    private fun finishOcrSnapshot(snapshot: ChatSnapshot, pkg: String, manual: Boolean, token: WorkToken) {
        if (!session.isCurrent(token) || rootInActiveWindow?.packageName?.toString() != pkg) return
        ocrBusy = false
        // Counts only — OCR'd chat text never goes to logcat.
        Log.i(TAG, "ocr[$pkg] msgs=${snapshot.messages.size} manual=$manual")
        if (snapshot.messages.isEmpty()) {
            if (manual) overlay?.showError("这一屏没认出文字")
            return
        }
        if (!prefs.isAllowed(snapshot.title)) { overlay?.hide(); return }

        if (pkg.isNotEmpty() && pkg != activePkg) { activePkg = pkg; lastSignature = "" }
        currentSnapshot = snapshot
        updateBindingSummary(snapshot, pkg)
        val sig = snapshot.signature()
        // Manual taps always re-run; the automatic path dedupes like the tree path.
        if (!manual && sig == lastSignature) {
            if (overlay?.isShowing() != true) overlay?.showIdle(snapshot.title)
            return
        }
        // Same rule as the tree path: past this point the conversation is either
        // new or being force-refreshed, so drop whatever was shown before.
        overlay?.resetForNewConversation()
        updateBindingSummary(snapshot, pkg)
        lastSignature = sig

        if (shouldReviewOcr(manual, prefs.ocrAutoAnalyze, snapshot.latestFrom)) reviewSnapshot(snapshot, pkg)
        else overlay?.showIdle(snapshot.title)
    }

    /** Both tree extraction and OCR require a reviewed transcript before model calls. */
    private fun reviewSnapshot(snapshot: ChatSnapshot, pkg: String) {
        if (snapshot.isGroup) { overlay?.showError("暂不支持群聊"); return }
        if (boundContact(snapshot.title, pkg) == null) {
            // Bind once; the retry re-reads the live snapshot so it can never
            // re-enter with a stale, already-cleared conversation.
            showBinding { currentSnapshot?.let { if (session.current.isActive()) reviewSnapshot(it, pkg) } }
            return
        }
        if (!snapshotIsCurrent(snapshot, pkg)) {
            val liveTitle = rootInActiveWindow?.packageName?.toString()
            Log.w(TAG, "identity check failed pkg=$pkg livePkg=$liveTitle " +
                "snapTitleLen=${snapshot.title?.length ?: -1} " +
                "manualLen=${manualWindowTitle?.length ?: -1} " +
                "bound=${boundContact(snapshot.title, pkg) != null}")
            overlay?.showError("无法确认当前聊天身份，请重新截屏并绑定对象"); return
        }
        updateBindingSummary(snapshot, pkg)
        val token = session.beginReview() ?: return
        overlay?.showReview(snapshot, onConfirm = { confirmed ->
            if (!session.isCurrent(token) || !session.reviewPending) return@showReview
            overlay?.showLoading()
            completeReviewConfirmation(confirmed, pkg, token)
        }, onCancel = {
            if (session.isCurrent(token)) {
                cancelWork()
                currentSnapshot = snapshot
                activePkg = pkg
                overlay?.showIdle(snapshot.title)
            }
        })
        if (overlay?.isShowing() != true) cancelWork()
    }

    /**
     * Dropping focus from the editable review overlay updates
     * rootInActiveWindow asynchronously. Wait for the original chat window
     * before validating it and starting analysis.
     */
    private fun completeReviewConfirmation(
        confirmed: ChatSnapshot,
        pkg: String,
        token: WorkToken,
        attempt: Int = 0
    ) {
        if (!session.isCurrent(token) || !session.reviewPending) return
        val livePkg = rootInActiveWindow?.packageName?.toString()
        when (reviewConfirmationState(livePkg, packageName, pkg)) {
            ReviewConfirmationState.WAIT_FOR_CHAT_WINDOW -> {
                if (attempt < REVIEW_FOCUS_RETRIES) {
                    main.postDelayed({
                        completeReviewConfirmation(confirmed, pkg, token, attempt + 1)
                    }, REVIEW_FOCUS_RETRY_MS)
                } else {
                    cancelWork()
                    overlay?.showError("无法重新确认聊天窗口，请关闭核对页后重试")
                }
            }
            ReviewConfirmationState.CHAT_CHANGED -> {
                cancelWork()
                overlay?.showError("聊天应用已切换，请重新识别后再分析")
            }
            ReviewConfirmationState.CHAT_CURRENT -> {
                if (!snapshotIsCurrent(currentSnapshot ?: confirmed, pkg)) {
                    cancelWork(); overlay?.showError("会话身份已变化，请重新绑定或识别"); return
                }
                if (!session.confirmReview(token)) return
                overlay?.finishReview()
                currentSnapshot = confirmed
                pendingSnapshot = confirmed
                main.removeCallbacks(debounce)
                // Persist before checking model credentials or starting any paid request.
                if (prefs.contextEnabled && !KbStore.get(this).rememberReviewed(confirmed.title, pkg, confirmed.messages)) {
                    overlay?.showError("历史保存失败，本轮未调用模型，请重试"); return
                }
                updateBindingSummary(confirmed, pkg)
                runAnalysis(token)
            }
        }
    }

    /** Fill only a verified, still-current chat input box (never sends). */
    private fun fillInput(text: String, snapshot: ChatSnapshot, pkg: String) {
        val token = session.current
        submit(token) {
            // The panel collapses as part of the tap, and focus returns to the
            // chat app asynchronously. Reading the tree too early finds our own
            // overlay (or the IME) and reports "input box not confirmed", so the
            // draft is never written. Wait briefly for the chat window to return.
            waitForChatWindow(pkg, token)
            val edit = verifiedInput(snapshot, pkg)
            val before = edit?.text?.toString()
            // Existing drafts belong to the user. Never clear or overwrite them.
            if (edit == null || before == null || before.isNotEmpty()) {
                token.checkActive()
                copyToClipboard(text)
                main.post { if (session.isCurrent(token)) overlay?.toast("会话或输入框未确认，或已有草稿；已复制，请手动粘贴") }
                return@submit
            }
            token.checkActive()
            var ok = setTextRaw(edit, text)
            Thread.sleep(150)
            var after = verifiedInput(snapshot, pkg)?.text?.toString()
            ok = ok && after == text
            // A failed SET_TEXT may still have landed. Paste only when a fresh read
            // proves the box is empty; never clear a box to make the fallback work.
            if (!ok && after == "") {
                val focused = verifiedInput(snapshot, pkg)
                if (focused != null && focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Thread.sleep(150)
                    if (verifiedInput(snapshot, pkg)?.text?.toString() == "") {
                        token.checkActive()
                        copyToClipboard(text)
                        focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                        Thread.sleep(150)
                        after = verifiedInput(snapshot, pkg)?.text?.toString()
                        ok = after == text
                    }
                }
            }
            Log.i(TAG, "fill: verified=$ok readback=${after?.length ?: -1}")
            main.post {
                if (!session.isCurrent(token)) return@post
                if (ok) overlay?.toast("已填入，确认后自己发送")
                else {
                    copyToClipboard(text)
                    overlay?.toast("无法确认是否填入；请检查输入框，勿重复粘贴")
                }
            }
        }
    }

    /** Blocks (on the worker) until the chat app is the active window, or times out. */
    private fun waitForChatWindow(pkg: String, token: WorkToken) {
        repeat(20) { attempt ->
            token.checkActive()
            val root = rootInActiveWindow
            val live = root?.packageName?.toString()
            if (live == pkg) {
                // Let the window settle so the input box is attached.
                Thread.sleep(80)
                return
            }
            if (attempt < 19) Thread.sleep(50)
        }
    }

    /**
     * The screenshot region holding the messages, in bitmap coordinates.
     *
     * The adapter reports screen-space edges (first bubble, input box). A window
     * screenshot is scaled and offset from the screen, so both are converted with
     * the same scale/origin used when mapping OCR boxes back. Unknown edges stay
     * uncropped: guessing with a percentage of the image is what previously cut
     * real messages off on tall screens.
     */
    private fun screenToBitmapRegion(snapshot: ChatSnapshot?, bmp: Bitmap): Rect {
        val sx = if (ocr.scaleX > 0f) ocr.scaleX else 1f
        val sy = if (ocr.scaleY > 0f) ocr.scaleY else 1f
        val topScreen = snapshot?.viewportTop
        val bottomScreen = snapshot?.viewportBottom
        val region = CaptureRegionCalculator.compute(
            imageHeight = bmp.height,
            firstBubbleTop = topScreen?.let { ((it - ocr.originY) * sy).toInt() },
            composerTop = bottomScreen?.let { ((it - ocr.originY) * sy).toInt() })
        return Rect(0, region.top.coerceIn(0, bmp.height - 1),
            bmp.width, region.bottom.coerceIn(region.top + 1, bmp.height))
    }

    private fun setTextRaw(edit: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Never choose a search box or an editable field in another app. */
    /**
     * The chat input box, but only when the SAME conversation is still on screen.
     *
     * Identity is decided by the conversation key, not by an exact message
     * signature: tapping 填入 collapses the panel and may coincide with a new
     * message or a scroll, which legitimately changes the visible messages. An
     * exact signature match therefore failed constantly and the draft was never
     * written. The key (app + readable title, or the confirmed name for an
     * unreadable window) is what actually proves we are in the right chat.
     */
    private fun verifiedInput(snapshot: ChatSnapshot, pkg: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != pkg) return null
        val fresh = adapters[pkg]?.extract(root, resources) ?: return null
        if (fresh.isGroup) return null
        val freshKey = conversationKey(fresh.title)
        val wantKey = conversationKey(snapshot.title)
        if (freshKey.isNullOrBlank() || freshKey != wantKey) return null
        return findEditable(root)
    }

    /**
     * The message input box, chosen as the lowest plausible editor on screen.
     *
     * A node counts when the platform marks it editable, or when it looks like a
     * chat composer: an EditText / known WeChat input id that is visible,
     * clickable or focusable, and sits in the lower part of the screen. The
     * structural fallback matters because WeChat's composer is a self-drawn
     * EditText whose `isEditable` is not always reported truthfully.
     */
    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        var best: AccessibilityNodeInfo? = null
        var bestBottom = -1
        val bounds = Rect()
        val minY = resources.displayMetrics.heightPixels * 0.35f
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            node.getBoundsInScreen(bounds)
            val isComposer = ComposerShape.isComposer(
                className = node.className?.toString(),
                viewId = node.viewIdResourceName,
                enabled = node.isEnabled,
                visible = node.isVisibleToUser,
                clickable = node.isClickable,
                focusable = node.isFocusable,
                editable = node.isEditable,
                screenHeight = resources.displayMetrics.heightPixels,
                centerY = bounds.centerY())
            if (isComposer) {
                if (bounds.width() >= 80 && bounds.height() >= 24 &&
                    bounds.centerY() >= minY && bounds.bottom > bestBottom) {
                    best = node
                    bestBottom = bounds.bottom
                }
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        return best
    }


    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("jev_reply", text))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        cancelWork()
        overlay?.reposition()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        detachPrefs?.invoke()
        detachPrefs = null
        session.destroy()
        cancelWork()
        // Tear the overlay down and cut its callback so a stale button tap can
        // never call back into this dead instance.
        overlay?.onManualAnalyze = null
        overlay?.onSaveContact = null
        overlay?.onOcrCapture = null
        overlay?.onProjectionCapture = null
        overlay?.onImportScreenshot = null
        overlay?.onShowHistory = null
        overlay?.onCalibrateTitleRegion = null
        overlay?.onHidden = null
        overlay?.hide()
        overlay = null
        worker.shutdownNow()
    }

    companion object {
        private const val TAG = "JEVASSIST"
        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val REVIEW_FOCUS_RETRIES = 20
        private const val REVIEW_FOCUS_RETRY_MS = 50L

        /** Said on the panel whenever a snapshot came from flat-screen OCR. */
        private const val OCR_NOTE = "OCR 未分边，把全部消息当作对方所说"

        private val PURE_TIME = Regex("""\d{1,2}[:：]\d{2}""")
        private val TAIL_TIME = Regex("""\d{1,2}[:：]\d{2}$""")

        /** Transient placeholder titles apps show while a chat page is still
         *  connecting/loading — see [isTransientTitle]. Matched as a substring,
         *  case-insensitive, after trimming a trailing ellipsis. */
        private val TRANSIENT_TITLE_WORDS = listOf(
            "连接中", "正在连接", "未连接", "Connecting",
            "加载中", "Loading", "同步中", "Syncing"
        )
    }
}
