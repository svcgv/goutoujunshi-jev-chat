package com.jev.probe.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jev.probe.core.kb.Contact
import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.ReviewedTranscript
import com.jev.probe.core.GoutouGuidance
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.core.Msg
import com.jev.probe.core.MessageEditing
import com.jev.probe.capture.BackfillResult
import com.jev.probe.capture.BackfillState
import com.jev.probe.capture.MessageCompleteness
import com.jev.probe.SettingsActivity
import com.jev.probe.KlineActivity
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Floating overlay: a small draggable bubble that expands into a translucent
 * panel showing Jev's read of the chat plus 3 ranked candidate replies. All
 * actions are copy / fill — never send.
 *
 * Design goals: let the chat show through (adjustable opacity), keep the signal
 * scannable (danger badge + intent headline + reply cards), and stay out of the
 * way (freely draggable bubble that stays where dropped and remembers its position).
 */
class OverlayController(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = Prefs(ctx)
    /** Bubble window: a tiny 52dp view. Dragging it re-lays out nothing else. */
    private var root: FrameLayout? = null
    private var bubble: TextView? = null
    private var dangerDot: View? = null
    private var lp: WindowManager.LayoutParams? = null

    /** Panel window: separate, so the bubble drag never touches its layout. */
    private var panelRoot: FrameLayout? = null
    private var panel: LinearLayout? = null
    private var contentBox: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    /** Header back control, shown only while a sub-page is open. */
    private var backButton: TextView? = null
    private var expanded = false

    var onManualAnalyze: (() -> Unit)? = null
    var onDetails: (() -> Unit)? = null
    var onExplain: ((String) -> Unit)? = null
    var onRewrite: (() -> Unit)? = null
    /** Quick paths to the standalone coach. */
    var onOpenChat: (() -> Unit)? = null
    var onEndChat: ((String) -> Unit)? = null
    var onConsult: (() -> Unit)? = null

    /** Bubble menu → file the open conversation as a knowledge-base contact. */
    var onSaveContact: (() -> Unit)? = null

    /** Bubble menu → one manual screenshot + OCR of whatever app is open. */
    var onProjectionCapture: (() -> Unit)? = null
    /** Bubble menu → automatic history backfill (scroll toward older messages). */
    var onBackfill: (() -> Unit)? = null
    var onBackfillStop: (() -> Unit)? = null
    /** Bubble tap; return true to consume the tap (e.g. stop a running backfill). */
    var onBubbleTap: (() -> Boolean)? = null
    var onImportScreenshot: (() -> Unit)? = null
    var onShowHistory: (() -> Unit)? = null

    /** Bubble menu → calibrate the conversation-title region for the current app. */
    var onCalibrateTitleRegion: (() -> Unit)? = null
    private var bindingSummary = "未绑定对象 · 不加载历史"

    fun setBindingSummary(value: String) { bindingSummary = value }

    var onOcrCapture: (() -> Unit)? = null

    /** How much knowledge context the last analysis actually used. */
    private var ctxNotes = 0
    private var ctxHistory = 0

    /** A caveat about how the current snapshot was captured (OCR mode). */
    private var noteText: String? = null
    private var evidenceSnapshot: ChatSnapshot? = null

    /** Whether the overlay window is currently on screen. */
    fun isShowing(): Boolean = root != null

    private var lastJudgment: Analysis? = null
    private var lastFill: ((String) -> Unit)? = null

    /** Set when [showReplies] was handed a draftAndRank failure, so the panel
     *  can say so instead of silently showing "（未生成候选回复）". */
    private var replyError: String? = null
    private var reviewCancel: (() -> Unit)? = null
    var onHidden: (() -> Unit)? = null

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).roundToInt()

    private fun canOverlay(): Boolean = Settings.canDrawOverlays(ctx)

    private val screenW get() = ctx.resources.displayMetrics.widthPixels
    private val screenH get() = ctx.resources.displayMetrics.heightPixels

    /** Panel background: white with the user's opacity so the chat shows through. */
    private fun panelBg(): Int {
        val a = (prefs.overlayOpacity / 100f * 255).roundToInt().coerceIn(150, 255)
        return Color.argb(a, 255, 255, 255)
    }

    private fun card(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
        if (stroke) setStroke(dp(1), Color.parseColor("#22000000"))
    }

    // ---------------------------------------------------------------- window

    private fun ensureRoot() {
        if (root != null) return
        if (!canOverlay()) { android.util.Log.w("JEVASSIST", "overlay: canDrawOverlays=false"); return }
        // Exact 52dp window: dragging it moves a view with no children to lay out.
        val size = dp(BUBBLE_DP)
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val position = BubblePosition.clamp(prefs.bubbleX.takeIf { it >= 0 } ?: dp(8),
                prefs.bubbleY.takeIf { it >= 0 } ?: dp(150), screenW, screenH, size, dp(8))
            x = position.first; y = position.second
        }
        lp = params

        val r = FrameLayout(ctx)
        r.addView(buildBubble(params))
        root = r
        try { wm.addView(r, params) } catch (e: Exception) {
            android.util.Log.e("JEVASSIST", "overlay addView failed: ${e.message}"); root = null
        }
    }

    /** The panel lives in its own window so expanding never resizes the bubble. */
    private fun ensurePanel() {
        if (panelRoot != null) return
        if (!canOverlay()) return
        val params = WindowManager.LayoutParams(
            dp(PANEL_WIDTH_DP),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        panelLp = params
        val p = buildPanel()
        val r = FrameLayout(ctx)
        r.addView(p)
        // The window is created up-front (so content can be built into it) but
        // stays invisible until the user taps the bubble. Switching chats must
        // never pop the panel open on its own.
        r.visibility = View.GONE
        panelRoot = r
        try { wm.addView(r, params) } catch (e: Exception) {
            android.util.Log.e("JEVASSIST", "panel addView failed: ${e.message}"); panelRoot = null
        }
    }

    private fun buildBubble(params: WindowManager.LayoutParams): View {
        val wrap = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(dp(BUBBLE_DP), dp(BUBBLE_DP))
        }
        val b = TextView(ctx).apply {
            text = "军师"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(235, 43, 82, 69))
            }
            layoutParams = FrameLayout.LayoutParams(dp(BUBBLE_DP), dp(BUBBLE_DP))
        }
        val dot = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) }
            layoutParams = FrameLayout.LayoutParams(dp(12), dp(12)).apply {
                gravity = Gravity.TOP or Gravity.END
            }
        }
        wrap.addView(b)
        wrap.addView(dot)
        attachBubbleTouch(wrap, params)
        bubble = b; dangerDot = dot
        return wrap
    }

    private fun buildPanel(): LinearLayout {
        val p = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = card(18, panelBg(), stroke = true)
            elevation = dp(8).toFloat()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = FrameLayout.LayoutParams(dp(PANEL_WIDTH_DP), FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        // Header
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        // Top-left way out of a sub-page (history / details), kept out of the way
        // the rest of the time. It sits in the header rather than at the bottom
        // of the page so it is reachable without scrolling a long history.
        header.addView(TextView(ctx).apply {
            text = "‹ 返回"
            setTextColor(Color.parseColor("#2B5245"))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(2), dp(10), dp(2))
            visibility = View.GONE
            isClickable = true
            backButton = this
        })
        header.addView(TextView(ctx).apply {
            text = "狗头军师"; setTextColor(Color.parseColor("#24382d")); textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(iconBtn("⚙") { openSettings() })
        header.addView(iconBtn("✕") { toggle() })
        p.addView(header)

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            // Cap the height so the panel stays in the upper area and does not
            // cover the WeChat input box / keyboard. Scroll inside if taller.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (screenH * 0.40f).roundToInt()).apply { topMargin = dp(6) }
        }
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        p.addView(scroll)
        contentBox = content
        panel = p
        return p
    }

    private fun iconBtn(glyph: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = glyph; setTextColor(Color.parseColor("#6B7280")); textSize = 16f
        setPadding(dp(10), dp(2), dp(6), dp(2))
        setOnClickListener { onClick() }
    }

    // --------------------------------------------------------------- gestures

    private fun attachBubbleTouch(v: View, params: WindowManager.LayoutParams) {
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        var moved = false; var longFired = false
        val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
        val longPress = Runnable {
            if (!moved) { longFired = true; showBubbleMenu() }
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY
                    moved = false; longFired = false
                    v.postDelayed(longPress, LONG_PRESS_MS); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX; val dy = e.rawY - touchY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        moved = true
                        v.removeCallbacks(longPress)
                        // The panel is a separate window; drop it while dragging.
                        if (expanded) collapsePanel()
                    }
                    if (moved) {
                        val pos = BubblePosition.clamp(
                            (startX + dx).toInt(), (startY + dy).toInt(),
                            screenW, screenH, dp(BUBBLE_DP), dp(8))
                        params.x = pos.first; params.y = pos.second
                        // One layout call per move event; the window holds a single
                        // childless-size view, so this stays cheap and follows the finger.
                        root?.let { runCatching { wm.updateViewLayout(it, params) } }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (moved) saveBubblePosition(params)
                    else if (!longFired && onBubbleTap?.invoke() != true) toggle()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress)
                    if (moved) saveBubblePosition(params)
                    true
                }
                else -> false
            }
        }
    }

    private fun saveBubblePosition(params: WindowManager.LayoutParams) {
        prefs.bubbleX = params.x; prefs.bubbleY = params.y
    }

    fun reposition() {
        val params = lp ?: return
        if (expanded) toggle()
        val pos = BubblePosition.clamp(prefs.bubbleX, prefs.bubbleY, screenW, screenH, dp(BUBBLE_DP), dp(8))
        params.x = pos.first; params.y = pos.second
        saveBubblePosition(params)
        root?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    private fun showBubbleMenu() {
        ensureRoot(); ensurePanel()
        val cancel = reviewCancel
        reviewCancel = null
        cancel?.invoke()
        releaseFocus()
        setContent(listOf(hint(bindingSummary),
            actionGroup(listOf(
                "截屏识别一次" to { onOcrCapture?.invoke() },
                "绑定对象 / 记忆设置" to { onSaveContact?.invoke() },
                "自动补录会话历史" to { onBackfill?.invoke() },
                "导入聊天截图" to { onImportScreenshot?.invoke() },
                "查看对象历史" to { onShowHistory?.invoke() },
                "设置标题识别区域" to { onCalibrateTitleRegion?.invoke() },
                "打开设置" to { openSettings() },
                "隐藏助手（本次）" to { hide() }))))
        if (!expanded) toggle()
    }

    private fun openSettings() {
        runCatching {
            ctx.startActivity(Intent(ctx, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (expanded) toggle()
    }

    private fun toggle() {
        if (expanded) collapsePanel() else expandPanel()
    }

    /**
     * Show the panel window next to the bubble. The bubble keeps its position:
     * expanding must never drag the user's bubble somewhere else.
     */
    private fun expandPanel() {
        val bubbleParams = lp ?: return
        ensurePanel()
        val params = panelLp ?: return
        placePanel(bubbleParams, params)
        expanded = true
        panel?.visibility = View.VISIBLE
        panelRoot?.visibility = if (OverlayVisibility.panelVisible(true, false)) View.VISIBLE else View.GONE
        bubble?.alpha = 1f
        // The first placement uses the estimated height; once the panel has
        // measured itself, re-place it so a short panel still fits fully.
        panelRoot?.post { if (expanded) placePanel(bubbleParams, params) }
    }

    private fun placePanel(bubbleParams: WindowManager.LayoutParams,
                           params: WindowManager.LayoutParams) {
        val measured = panelRoot?.height?.takeIf { it > 0 }
            ?: (screenH * 0.40f).roundToInt()
        val pos = PanelPlacement.place(bubbleParams.x, bubbleParams.y, dp(BUBBLE_DP),
            dp(PANEL_WIDTH_DP), measured, screenW, screenH, dp(8))
        params.x = pos.first; params.y = pos.second
        panelRoot?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    /** Focusable while a text field is showing, so the keyboard can open. */
    private fun setPanelFocusable(focusable: Boolean) {
        val params = panelLp ?: return
        params.flags = if (focusable) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                       else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        panelRoot?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    /**
     * Hide the panel without deciding anything.
     *
     * This used to invoke [reviewCancel], so merely tapping the bubble to fold
     * the panel cancelled the pending review — and the backfill review's cancel
     * path tears the tracked conversation down, leaving the next open claiming
     * the chat was unbound. Hiding a panel is not a decision; explicit cancel
     * buttons and conversation switches own that.
     */
    private fun collapsePanel() {
        setPanelFocusable(false)
        panel?.visibility = View.GONE
        panelRoot?.visibility = View.GONE
        expanded = false
    }

    // ------------------------------------------------------------ public API

    fun showIdle(title: String?) {
        ensureRoot(); ensurePanel(); bubble?.alpha = 0.55f
        // Either there is genuinely nothing to show yet, or the panel is empty
        // for some other reason (root got rebuilt after hide(), leaving
        // contentBox with zero children while lastJudgment still points at a
        // stale conversation) — either way an empty panel must never stay
        // literally blank.
        if (lastJudgment == null || contentBox?.childCount == 0) {
            setContent(listOf(
                hint(title?.let { "当前窗口：$it" } ?: "请进入聊天窗口"), hint(bindingSummary),
                bigButton("分析当前对话") { onManualAnalyze?.invoke() },
                actionGroup(listOf(
                    "帮我回复" to { onManualAnalyze?.invoke() },
                    "发起聊天" to { onOpenChat?.invoke() },
                    "暂时离开会话" to { onEndChat?.invoke("temporary_leave") },
                    "问军师（连续咨询）" to { onConsult?.invoke() },
                    "绑定对象 / 记忆设置" to { onSaveContact?.invoke() },
                    "自动补录会话历史" to { onBackfill?.invoke() },
                    "导入聊天截图" to { onImportScreenshot?.invoke() },
                    "查看对象历史" to { onShowHistory?.invoke() },
                    "设置标题识别区域" to { onCalibrateTitleRegion?.invoke() }))))
        }
    }

    fun showBinding(title: String, contacts: List<Contact>, selectedId: String?, remember: Boolean,
                    onSave: (String?, String, Boolean) -> Unit, onUnbind: () -> Unit,
                    onClear: () -> Unit, onCancel: () -> Unit) {
        ensureRoot(); ensurePanel()
        val picker = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                listOf("新建对象") + contacts.map { it.name })
            setSelection(contacts.indexOfFirst { it.id == selectedId }.let { if (it < 0) 0 else it + 1 })
        }
        val name = EditText(ctx).apply { hint = "新对象称呼／无法识别时的本轮会话称呼"; setText(title) }
        val consent = CheckBox(ctx).apply { text = "保存核对后的消息并在分析时使用历史（本机存储）"; isChecked = remember }
        val identity = CheckBox(ctx).apply { text = "确认这是同一对象的一对一会话；同名会话请先在聊天软件设唯一备注" }
        reviewCancel = onCancel
        setContent(listOf(line("绑定：$title", "#24382d", 16f, true), picker, name, consent, identity,
            hint("仅采集已核对的可见消息；不读取完整微信记录。跨应用只有手动选同一对象才共享档案。"),
            bigButton("确认绑定") {
                if (!identity.isChecked || (picker.selectedItemPosition == 0 && name.text.isBlank())) {
                    toast("请填写称呼并确认会话身份")
                } else {
                    finishReview(); releaseFocus()
                    val selected = contacts.getOrNull(picker.selectedItemPosition - 1)
                    onSave(selected?.id, name.text.toString().trim().ifBlank { selected?.name.orEmpty() }, consent.isChecked)
                }
            },
            actionGroup(listOf(
                "解除当前绑定" to { finishReview(); releaseFocus(); onUnbind() },
                "清空此对象历史（需确认）" to {
                    setContent(listOf(hint("删除此对象已保存的聊天历史，不能恢复；绑定与档案保留。"),
                        bigButton("确认清空") { finishReview(); releaseFocus(); onClear() },
                        actionGroup(listOf("取消" to { finishReview(); releaseFocus(); onCancel() }))))
                },
                "取消" to { finishReview(); releaseFocus(); onCancel() }))))
        if (!expanded) toggle()
        setPanelFocusable(true)
    }

    private fun releaseFocus() {
        setPanelFocusable(false)
    }

    /** OCR text is editable because both wording and speaker attribution can be wrong. */
    fun showReview(snapshot: ChatSnapshot, onConfirm: (ChatSnapshot) -> Unit,
                   onCancel: () -> Unit) {
        ensureRoot(); ensurePanel()
        reviewCancel = onCancel
        val editor = EditText(ctx).apply {
            setText(snapshot.messages.joinToString("\n") {
                (when (it.side) { "me" -> "我："; "other" -> "对方："; else -> "待确认：" }) + it.text
            })
            setTextColor(Color.parseColor("#24382d"))
            textSize = 14f
            minLines = 7
            maxLines = 12
            gravity = Gravity.TOP
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = card(10, Color.WHITE, stroke = true)
        }
        val confirm = bigButton("确认原文并分析") {
            val messages = runCatching { ReviewedTranscript.parse(editor.text.toString()) }.getOrNull()
            if (messages == null) {
                toast("每行请以“我：”或“对方：”开头，并核对内容")
            } else {
                // Keep cancellation while focus is returning to the chat window.
                releaseFocus()
                onConfirm(snapshot.copy(messages = messages,
                    note = (snapshot.note ?: "") + " · 原文与说话人已人工核对"))
            }
        }
        setContent(listOf(line("核对本轮对话", "#24382d", 16f, true),
            hint(bindingSummary),
            hint("只支持一对一聊天。请核对每行的我／对方和正文，确认后按记忆设置保存，再调用模型。"),
            editor, confirm))
        if (!expanded) toggle()
        // Re-place with the real measured height, then take focus for the editor.
        panelRoot?.post {
            if (expanded) expandPanel()
            setPanelFocusable(true)
        }
    }

    // ------------------------------------------------------------ backfill

    /** Confirm the run before any scrolling happens. */
    fun showBackfillPrompt(title: String?, defaultTarget: Int,
                           onStart: (Int) -> Unit, onCancel: () -> Unit) {
        ensureRoot(); ensurePanel()
        reviewCancel = onCancel
        val count = EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(defaultTarget.coerceIn(10, 100).toString())
            textSize = 15f
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = card(10, Color.WHITE, stroke = true)
        }
        setContent(listOf(
            line("自动补录会话历史", "#24382d", 16f, true),
            hint(title?.let { "当前会话：$it" } ?: "当前会话身份待确认"),
            hint("将从会话底部开始，自动向上翻页读取最近的消息，默认 50 条（10–100）。" +
                "只读取屏幕上的文字，不发送、不上传截图，结束后由你核对原文。"),
            hint("补录条数（10–100）"),
            count,
            bigButton("开始补录") {
                val n = count.text.toString().trim().toIntOrNull()?.coerceIn(10, 100) ?: defaultTarget
                releaseFocus()
                onStart(n)
            },
            actionGroup(listOf("取消" to { finishReview(); releaseFocus(); onCancel() }))))
        if (!expanded) toggle()
        panelRoot?.post { if (expanded) expandPanel(); setPanelFocusable(true) }
    }

    /** Live progress while the run scrolls the chat. */
    internal fun showBackfillProgress() {
        ensureRoot(); ensurePanel(); bubble?.alpha = 1f
        setContent(listOf(
            line("自动补录中…", "#24382d", 16f, true),
            hint("正在回到会话底部并向上读取；请勿手动滑动聊天窗口。点一下悬浮球可停止。")))
        if (!expanded) toggle()
        setPanelFocusable(false)
    }

    internal fun updateBackfillProgress(state: BackfillState) {
        if (panelRoot == null) return
        setContent(listOf(
            line("自动补录中…", "#24382d", 16f, true),
            hint(state.progressText().ifBlank { "准备中…" }),
            hint("请勿手动滑动聊天窗口；点一下悬浮球可停止。")))
    }

    /**
     * Per-message review. Each message keeps its own editor, so a long bubble
     * with internal newlines round-trips intact. Split / merge-with-next / delete
     * let the user repair OCR and stitching mistakes before anything is saved.
     */
    internal fun showMessageReview(title: String?, result: BackfillResult,
                          onConfirm: (List<Msg>) -> Unit, onCancel: () -> Unit) {
        ensureRoot(); ensurePanel()
        reviewCancel = onCancel
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val model = ArrayList(result.messages.map {
            ReviewItem(it.side, it.text, it.completeness)
        })
        val rows = ArrayList<Row>()
        val usedOcr = result.messages.any { it.side == "unknown" }

        /** Push every editor's current text back into the model. */
        fun capture() {
            rows.forEach { it.item.text = it.editor.text.toString() }
        }

        /** Rebuild the list after a structural edit. */
        fun render() {
            container.removeAllViews()
            rows.clear()
            model.forEachIndexed { index, item ->
                val header = TextView(ctx).apply {
                    textSize = 12f
                    setPadding(0, dp(8), 0, dp(2))
                    setTextColor(Color.parseColor("#2B5245"))
                    setTypeface(typeface, Typeface.BOLD)
                    text = reviewHeader(index, item.side, item.completeness)
                    setOnClickListener {
                        item.side = nextSide(item.side)
                        text = reviewHeader(index, item.side, item.completeness)
                    }
                }
                val editor = EditText(ctx).apply {
                    setText(item.text)
                    setTextColor(Color.parseColor("#24382d"))
                    textSize = 13f
                    gravity = Gravity.TOP
                    maxLines = 8
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    background = card(8, Color.WHITE, stroke = true)
                }
                // The panel stays non-focusable so the WeChat list behind it
                // keeps scrolling for cross-checking. Only a tap on a message
                // body pulls window focus (and the IME) to that editor.
                editor.setOnTouchListener { v, e ->
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                        setPanelFocusable(true)
                        v.post {
                            v.requestFocus()
                            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                                ?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                        }
                    }
                    false
                }
                val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                actions.addView(smallAction("拆分") {
                    capture()
                    val split = MessageEditing.split(item.text, editor.selectionStart)
                    if (split == null) {
                        toast("把光标放在正文中间再拆分")
                    } else {
                        model[index] = item.copy(text = split.first)
                        model.add(index + 1, item.copy(text = split.second))
                        render()
                    }
                })
                actions.addView(smallAction("合并下一条") {
                    if (index + 1 >= model.size) {
                        toast("后面没有可合并的消息")
                    } else {
                        capture()
                        val next = model[index + 1]
                        model[index] = item.copy(text = MessageEditing.merge(item.text, next.text))
                        model.removeAt(index + 1)
                        render()
                    }
                })
                actions.addView(smallAction("删除") {
                    capture()
                    model.removeAt(index)
                    render()
                })
                rows.add(Row(item, editor))
                container.addView(header)
                container.addView(editor)
                container.addView(actions)
            }
        }

        fun collect(): List<Msg> = model.mapNotNull { item ->
            val t = item.text.trim()
            if (t.isEmpty()) null else Msg(item.side, t)
        }

        render()

        val confirm = bigButton("确认原文并分析") {
            capture()
            val msgs = collect()
            runCatching {
                com.jev.probe.CrashLogger.diag(ctx, "review confirm tapped msgs=${msgs.size} " +
                    "unknown=${msgs.count { it.side == "unknown" }}")
            }
            when {
                msgs.isEmpty() -> toast("至少保留一条消息")
                msgs.any { it.side == "unknown" } -> toast("请点击每一行把“待确认”改成我或对方")
                else -> { releaseFocus(); onConfirm(msgs) }
            }
        }
        setContent(listOf(
            TextView(ctx).apply {
                text = "核对本轮对话"
                setTextColor(Color.parseColor("#24382d"))
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(2), 0, dp(2))
                setOnClickListener {
                    // Hand focus back so the chat list is scrollable again.
                    releaseFocus()
                    toast("已退出编辑，可滑动微信对照原文")
                }
            },
            hint(title?.let { "当前会话：$it" } ?: "当前会话身份待确认"),
            hint(result.summary()),
            hint("核对时可直接滑动微信对照原文；点某条正文才开始编辑，点标题「核对本轮对话」退出编辑。"),
            hint("点击每条标题切换 我／对方；正文下方可拆分、与下一条合并或删除。"),
            if (usedOcr)
                hint("本次包含本地 OCR 结果：说话人无法自动判断，带「待确认」的每一条都必须手动指定我／对方。")
            else hint(""),
            container,
            confirm,
            actionGroup(listOf("放弃本轮" to { finishReview(); releaseFocus(); onCancel() }))))
        if (!expanded) toggle()
        // Deliberately NOT focusable: FLAG_NOT_FOCUSABLE also makes the window
        // non-modal for touch, so swipes outside the panel reach the chat app.
        panelRoot?.post { if (expanded) expandPanel(); setPanelFocusable(false) }
    }

    private data class ReviewItem(
        var side: String,
        var text: String,
        val completeness: MessageCompleteness
    )

    private class Row(val item: ReviewItem, val editor: EditText)

    /** Small inline button used by the split / merge / delete strip. */
    private fun smallAction(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#2B5245"))
        setPadding(dp(10), dp(4), dp(10), dp(4))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(6); topMargin = dp(2) }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun nextSide(side: String): String = when (side) {
        "unknown" -> "other"
        "other" -> "me"
        else -> "unknown"
    }

    private fun sideWord(side: String): String = when (side) {
        "me" -> "我"
        "other" -> "对方"
        else -> "待确认"
    }

    private fun reviewHeader(index: Int, side: String, completeness: MessageCompleteness): String {
        val flag = when (completeness) {
            MessageCompleteness.COMPLETE -> ""
            MessageCompleteness.STITCHED -> "  [跨屏已拼接]"
            MessageCompleteness.PARTIAL -> "  [可能缺失]"
            MessageCompleteness.CONFLICT -> "  [存在冲突]"
            MessageCompleteness.UNKNOWN -> "  [非文本]"
        }
        return "${index + 1}. ${sideWord(side)}$flag"
    }

    /**
     * Drop whatever judgment/candidates/note belonged to the previous
     * conversation. Call this before showing anything for a different chat
     * window (a different app, or new content in the same one) — otherwise a
     * leftover [lastJudgment] from a prior conversation can keep [showIdle]
     * from putting the "分析当前对话" button back, and a leftover [lastFill]
     * could fill the wrong chat's input box.
     */
    fun resetForNewConversation() {
        runCatching { com.jev.probe.CrashLogger.diag(ctx, "overlay resetForNewConversation") }
        reviewCancel = null
        bindingSummary = "未绑定对象 · 不加载历史"
        lastJudgment = null
        lastFill = null
        noteText = null
        evidenceSnapshot = null
        replyError = null
        contentBox?.removeAllViews()
    }

    /** Collapse the panel if open. Used when moving to a different conversation. */
    fun collapse() {
        if (expanded) collapsePanel()
    }

    fun finishReview() { reviewCancel = null }

    /** Primary call to action: one filled, rounded, full-width button. */
    private fun bigButton(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        background = card(12, Color.parseColor("#2B5245"))
        setPadding(dp(12), dp(11), dp(12), dp(11))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    /**
     * Secondary actions as ONE rounded card with hairline dividers. Stacking
     * independent rounded buttons produced a scalloped left/right edge and a
     * tall column of separate pills; a single clipped container keeps the
     * corners clean and the panel compact.
     */
    private fun actionGroup(items: List<Pair<String, () -> Unit>>): View {
        val group = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(12, Color.parseColor("#2B5245"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            clipToOutline = true
        }
        items.forEachIndexed { index, (label, action) ->
            if (index > 0) group.addView(View(ctx).apply {
                setBackgroundColor(Color.parseColor("#406353"))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            })
            group.addView(TextView(ctx).apply {
                text = label; textSize = 14f; gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                isClickable = true
                setOnClickListener { action() }
            })
        }
        return group
    }

    fun showLoading() {
        ensureRoot(); ensurePanel(); bubble?.alpha = 1f
        ctxNotes = 0; ctxHistory = 0   // counts for the round that is starting
        replyError = null              // this round has not failed (yet)
        setContent(listOf(hint("分析中…")))
        if (!expanded) toggle()
    }

    /** How many knowledge notes / history lines went into the pending analysis. */
    fun setContextInfo(notes: Int, history: Int) {
        ctxNotes = notes; ctxHistory = history
    }

    /** A caveat line for the panel (OCR mode); null clears it. */
    fun setNote(note: String?) {
        noteText = note
    }

    fun setSnapshot(snapshot: ChatSnapshot) {
        evidenceSnapshot = snapshot
    }

    /**
     * Take the overlay out of the picture for one screenshot. INVISIBLE, not
     * removed: the window (and everything on it) must survive the round trip.
     */
    /**
     * While a backfill is driving the chat, the progress panel must not swallow
     * the synthesized scroll gestures. NOT_TOUCHABLE lets them reach the chat
     * window underneath; the bubble still works as the stop affordance.
     */
    fun setPanelTouchPassthrough(enabled: Boolean) {
        val root = panelRoot ?: return
        val lp = panelLp ?: return
        lp.flags = if (enabled)
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { wm.updateViewLayout(root, lp) }
    }

    fun setHiddenForShot(hidden: Boolean) {
        root?.visibility = if (OverlayVisibility.bubbleVisible(hidden)) View.VISIBLE else View.INVISIBLE
        panelRoot?.visibility = if (OverlayVisibility.panelVisible(expanded, hidden))
            View.VISIBLE else if (hidden) View.INVISIBLE else View.GONE
    }

    /**
     * Record an error for the panel and surface it as a toast, but never force
     * the panel open: errors can be raised by merely switching chats (e.g.
     * landing on a group), and that must not pop the panel over the chat.
     */
    fun showError(msg: String) {
        // Record the reason so it can be read from Settings even when logcat is
        // restricted on the device.
        runCatching { com.jev.probe.CrashLogger.note(ctx, "showError: $msg") }
        ensureRoot(); ensurePanel(); bubble?.alpha = 1f
        setContent(listOf(
            line("出错了", "#DC2626", 14f, true),
            hint(msg),
            actionGroup(listOf(
                "重新识别" to { onOcrCapture?.invoke() },
                "自动补录会话历史" to { onBackfill?.invoke() },
                "导入聊天截图" to { onImportScreenshot?.invoke() }))))
        bubble?.alpha = 0.55f
        toast(msg)
    }

    fun showJudgment(a: Analysis) {
        lastJudgment = a
        render(a, generating = true)
    }

    fun showReplies(ranked: List<RankedReply>, error: String? = null, onFill: (String) -> Unit) {
        lastFill = onFill
        replyError = error
        val a = lastJudgment?.copy(rankedReplies = ranked) ?: return
        lastJudgment = a
        render(a, generating = false)
    }

    /**
     * A read-only page: detailed analysis, reply rationale, or stored history.
     *
     * [onBack] is the way out when there is nothing to return to. The history page
     * can be opened before any analysis exists, and a button that only re-rendered
     * an existing judgment was then a dead end.
     */
    fun showDetails(
        text: String,
        heading: String = "详细分析",
        subtitle: String = "分析来自当前已核对的原文；推测与事实分开看。",
        onBack: (() -> Unit)? = null
    ) {
        ensureRoot(); ensurePanel()
        val judgment = lastJudgment
        val views = ArrayList<View>()
        views.add(line(heading, "#24382d", 17f, true))
        if (subtitle.isNotBlank()) views.add(hint(subtitle))
        views.add(line(text, "#374151", 13f))
        setContent(views)
        when (DetailBackAction.forPage(judgment != null, onBack != null)) {
            DetailBackAction.Kind.BACK_TO_REPLIES ->
                showBackControl { judgment?.let { render(it, generating = false) } }
            DetailBackAction.Kind.CALLBACK -> showBackControl { onBack?.invoke() }
            DetailBackAction.Kind.NONE -> Unit
        }
        if (!expanded) toggle()
    }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    fun hide() {
        // Do not invoke the collapse callback here: it would recreate the idle overlay.
        reviewCancel = null
        resetForNewConversation()
        panelRoot?.let { r -> runCatching { wm.removeView(r) } }
        root?.let { r -> runCatching { wm.removeView(r) } }
        root = null; bubble = null; dangerDot = null
        panelRoot = null; panel = null; contentBox = null; panelLp = null; expanded = false
        onHidden?.invoke()
    }

    // --------------------------------------------------------------- rendering

    private fun setContent(views: List<View>) {
        val c = contentBox ?: return
        c.removeAllViews(); views.forEach { c.addView(it) }
        // Every page starts without a back control; showDetails opts back in.
        backButton?.visibility = View.GONE
        backButton?.setOnClickListener(null)
    }

    /** Put the way out of a sub-page in the panel's top-left corner. */
    private fun showBackControl(onBack: () -> Unit) {
        backButton?.apply {
            visibility = View.VISIBLE
            setOnClickListener { onBack() }
        }
    }

    private fun render(a: Analysis, generating: Boolean) {
        ensureRoot(); ensurePanel(); bubble?.alpha = 1f
        panel?.background = card(18, panelBg(), stroke = true) // re-apply in case opacity changed
        val views = ArrayList<View>()

        // What context this read was based on (knowledge base / remembered history).
        views.add(hint(bindingSummary))
        views.add(hint(
            if (ctxNotes == 0 && ctxHistory == 0) "未用知识库"
            else "知识库 $ctxNotes 条 · 历史 $ctxHistory 条"))

        // How this snapshot was captured, when it changes how to read it.
        noteText?.let { if (it.isNotBlank()) views.add(hint(it)) }

        // Danger badge — the alarm signal, up top and color-coded.
        a.dangerLevel?.let {
            val lvl = it.score.roundToInt()
            views.add(dangerBadge(lvl, it.maxLevel))
            tintBubbleDanger(it.score)
        }
        // Intent headline.
        a.trueIntent?.let {
            views.add(line("对方可能的意图：${INTENT[it.choice] ?: it.choice}", "#24382d", 15f, true))
            if (it.confidence.isFinite() && it.confidence in 0.0..1.0 &&
                evidenceSnapshot?.messages?.isNotEmpty() == true) {
                views.add(hint("判断把握 ${(it.confidence * 100).roundToInt()}% · 模型估计"))
            }
        }
        a.strategy?.let { strategy ->
            val weight = a.strategyWeights[strategy]
            val detail = if (weight != null) "策略选择权重 ${(weight * 100).roundToInt()}%"
                         else "策略 token 权重暂不可用"
            views.add(hint("聊天模型主策略 · $strategy · $detail；不是回复成功率"))
        }
        // Compact secondary line: needs · action · reply-now.
        val bits = ArrayList<String>()
        a.sheNeeds?.let { bits.add("要${(NEEDS[it.choice] ?: it.choice)}") }
        a.bestAction?.let { bits.add(ACTION[it.choice] ?: it.choice) }
        a.shouldReplyNow?.let { bits.add(if (it >= 0.5) "可给实质" else "先别给实质") }
        if (bits.isNotEmpty()) views.add(line(bits.joinToString("  ·  "), "#374151", 13f))
        a.tensionResolved?.let { if (it >= 0.7) views.add(line("✓ 紧张已缓解", "#16A34A", 12f)) }

        evidenceSnapshot?.let { snap ->
            val quoted = snap.messages.takeLast(3).joinToString("\n") {
                (if (it.side == "me") "我" else "对方") + "：「" + it.text.take(80) + "」"
            }
            val facts = a.facts.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: quoted
            if (facts.isNotBlank()) views.add(hint("已核对原文\n$facts"))
            views.add(hint("仍未知 · " + (a.unknowns.joinToString("；").ifBlank {
                "仅凭屏幕片段无法确认对方内心、完整上下文和线下情况。" })))
            val boundary = GoutouGuidance.explicitBoundary(snap)
            views.add(line("军师建议 · " + (if (boundary) "尊重停止联系要求" else
                GoutouGuidance.actionLabel(a.bestAction?.choice)), "#2B5245", 13f, true))
            views.add(hint("下一步 · " + (if (boundary) "先停止联系，等对方主动重启对话。" else
                GoutouGuidance.nextStep(a.bestAction?.choice))))
            views.add(hint("停止条件 · ${GoutouGuidance.stopCondition}"))
            views.add(hint("OCR 原文和说话人需核对；判断把握不是对方真实意图概率。"))
        }

        views.add(divider())
        views.add(line("候选回复排序（${if (a.strategy != null) "聊天模型" else "Jev"}）", "#68776F", 12f))
        if (generating) {
            views.add(hint("生成中…"))
        } else {
            val fill = lastFill ?: {}
            a.rankedReplies.forEachIndexed { i, r ->
                views.add(replyCard(i + 1, r.text, (r.prob * 100).roundToInt(), fill))
            }
            if (a.rankedReplies.isNotEmpty() && a.rankedReplies.all { it.prob == 0.0 })
                views.add(hint("排序暂不可用，候选保留生成顺序。"))
            if (a.rankedReplies.isEmpty()) {
                val msg = replyError?.let { "回复接口出错：$it" }
                    ?: if (evidenceSnapshot?.let { GoutouGuidance.explicitBoundary(it) } == true)
                        "对方明确要求停止联系，这轮建议不回复。" else "（未生成候选回复）"
                views.add(hint(msg))
            }
        }
        views.add(reAnalyzeBtn())
        if (!generating) {
            views.add(pill("详细分析", false) { onDetails?.invoke() })
            if (a.rankedReplies.isNotEmpty())
                views.add(pill("更像我一点", false) { onRewrite?.invoke() })
        }
        views.add(actionGroup(listOf(
            "发起聊天" to { onOpenChat?.invoke() },
            "暂时离开会话" to { onEndChat?.invoke("temporary_leave") },
            "问军师（连续咨询）" to { onConsult?.invoke() })))

        setContent(views)
        if (!expanded) toggle()
    }

    private fun dangerBadge(lvl: Int, max: Int): View {
        val color = dangerColor(lvl)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        row.addView(TextView(ctx).apply {
            text = "危险 $lvl/$max"
            setTextColor(Color.WHITE); textSize = 13f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = card(20, color)
        })
        row.addView(TextView(ctx).apply {
            text = "  " + dangerWord(lvl); setTextColor(color); textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        })
        return row
    }

    private fun replyCard(rank: Int, text: String, pct: Int, onFill: (String) -> Unit): View {
        val top = rank == 1
        val cardBg = if (top) Color.parseColor("#EAF4EF") else Color.parseColor("#F3F4F2")
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(12, cardBg)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        c.addView(TextView(ctx).apply {
            this.text = if (pct > 0) "#$rank · 相对权重 ${pct}%" else "#$rank · 排序待定"
            setTextColor(Color.parseColor("#2B5245")); textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
        })
        c.addView(TextView(ctx).apply {
            this.text = text; setTextColor(Color.parseColor("#111827")); textSize = 14f
            setPadding(0, dp(3), 0, dp(7)); setLineSpacing(dp(2).toFloat(), 1f)
        })
        val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(pill("复制", false) { copy(text) })
        // Fill, then collapse so the input box + keyboard are visible to review/send.
        btns.addView(pill("填入", true) { android.util.Log.d("JEVASSIST", "overlay: fill tapped"); onFill(text); if (expanded) toggle() })
        c.addView(btns)
        c.addView(pill("为什么这样回", false) { onExplain?.invoke(text) })
        return c
    }

    private fun pill(label: String, primary: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) Color.WHITE else Color.parseColor("#2B5245"))
        background = card(18, if (primary) Color.parseColor("#2B5245") else Color.parseColor("#FFFFFF"), stroke = !primary)
        setPadding(dp(18), dp(6), dp(18), dp(6))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun reAnalyzeBtn() = TextView(ctx).apply {
        text = "重新分析"; textSize = 13f; gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#6B7280"))
        setPadding(dp(10), dp(10), dp(10), dp(4))
        setOnClickListener { onManualAnalyze?.invoke() }
    }

    private fun tintBubbleDanger(score: Double) {
        val color = dangerColor(score.roundToInt())
        dangerDot?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(2), Color.WHITE)
        }
    }

    // --------------------------------------------------------------- helpers

    private fun line(text: String, color: String, size: Float, bold: Boolean = false) =
        TextView(ctx).apply {
            this.text = text; setTextColor(Color.parseColor(color)); textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(2))
        }

    private fun hint(text: String) = line(text, "#9CA3AF", 12f)

    private fun divider() = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#1F000000"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(8); bottomMargin = dp(4)
        }
    }

    private fun copy(text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("jev_reply", text))
        toast("已复制")
    }

    private fun dangerColor(lvl: Int): Int = when {
        lvl >= 6 -> Color.parseColor("#DC2626")
        lvl >= 3 -> Color.parseColor("#D97706")
        else -> Color.parseColor("#16A34A")
    }

    private fun dangerWord(lvl: Int): String = when {
        lvl >= 8 -> "很危险"
        lvl >= 6 -> "偏危险"
        lvl >= 3 -> "留神"
        else -> "安全"
    }

    companion object {
        private const val BUBBLE_DP = 52
        private const val PANEL_WIDTH_DP = 316
        private const val LONG_PRESS_MS = 500L

        private val INTENT = mapOf(
            "confirm_you_care" to "确认你在不在乎", "vent_anger" to "在发泄情绪",
            "request_action" to "要你办事", "seek_explanation" to "要个解释",
            "casual_chat" to "随便聊聊", "close_topic" to "事情过去了")
        private val NEEDS = mapOf(
            "apology" to "道歉", "action" to "具体行动", "explanation" to "解释",
            "care" to "你的在乎", "nothing" to "（不用做什么）")
        private val ACTION = mapOf(
            "check_history" to "翻聊天记录", "apologize" to "先道歉", "give_commitment" to "给承诺",
            "explain" to "解释清楚", "acknowledge" to "接住情绪", "say_less" to "少说两句",
            "make_plan" to "定个安排")
    }
}
