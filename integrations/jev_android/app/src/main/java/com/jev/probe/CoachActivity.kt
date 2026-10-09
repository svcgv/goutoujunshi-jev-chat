package com.jev.probe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.coach.CoachMessage
import com.jev.probe.coach.CoachOrchestrator
import com.jev.probe.coach.CoachRequest
import com.jev.probe.coach.CoachResponse
import com.jev.probe.coach.CoachTask
import com.jev.probe.coach.ConsultationStore
import com.jev.probe.coach.EndChatMode
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.WorkToken
import com.jev.probe.core.kb.Contact
import com.jev.probe.core.kb.KbStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Standalone coach consultation. It does not need accessibility, overlay or a
 * chat app to work. Consultation output is copy-only; this screen never fills
 * or sends a message into another app.
 */
class CoachActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var store: KbStore
    private lateinit var coach: CoachOrchestrator
    private lateinit var transcripts: ConsultationStore
    private lateinit var container: LinearLayout
    private lateinit var goalEdit: EditText
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    private var running = false
    private var activeToken: WorkToken? = null

    private var task = CoachTask.CONSULT
    private var endMode = EndChatMode.END_TURN
    private var contactId: String? = null
    private var title = "军师咨询"
    private var relationship = ""
    private var snapshot: ChatSnapshot? = null
    private var turns = mutableListOf<CoachMessage>()
    private var lastResponse: CoachResponse? = null

    private val accent = Color.parseColor("#2B5245")
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")
    private val green = Color.parseColor("#16A34A")

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = KbStore.get(this)
        coach = CoachOrchestrator(this, prefs)
        transcripts = ConsultationStore.get(this)
        readIntent()
        buildShell()
        val restored = savedInstanceState?.getString(STATE_RESPONSE)?.let(::decodeResponse)
        if (restored != null) {
            lastResponse = restored
            turns = savedInstanceState.getStringArrayList(STATE_TURNS)?.mapNotNull { row ->
                val parts = row.split('', limit = 2)
                if (parts.size == 2) CoachMessage(parts[0], parts[1]) else null
            }?.toMutableList() ?: mutableListOf()
            renderResponse(restored)
            savedInstanceState.getString(STATE_GOAL)?.let { goalEdit.setText(it) }
        } else {
            renderIdle()
            savedInstanceState?.getString(STATE_GOAL)?.let { goalEdit.setText(it) }
        }
        if (restored == null && intent.getBooleanExtra(EXTRA_AUTO_START, false)) {
            goalEdit.setText(defaultGoal())
            generate()
        } else if (!intent.getBooleanExtra(EXTRA_FROM_OVERLAY, false)) {
            maybeInviteProfile()
        }
    }

    private fun readIntent() {
        task = CoachTask.fromWire(intent.getStringExtra(EXTRA_TASK))
        endMode = EndChatMode.fromWire(intent.getStringExtra(EXTRA_END_MODE))
        contactId = intent.getStringExtra(EXTRA_CONTACT_ID)
        title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "军师咨询"
        relationship = intent.getStringExtra(EXTRA_RELATIONSHIP).orEmpty()
        val lines = intent.getStringArrayListExtra(EXTRA_MESSAGES).orEmpty()
        val messages = lines.mapNotNull { row ->
            val split = row.split('\u0001', limit = 2)
            if (split.size == 2 && split[1].isNotBlank()) Msg(split[0], split[1]) else null
        }
        if (messages.isNotEmpty()) snapshot = ChatSnapshot(title, messages,
            note = "来自悬浮窗当前已核对的可见消息")
        val contact = contactId?.let { store.contact(it) }
        if (contact != null) {
            relationship = relationship.ifBlank { contact.relationship }
            title = contact.name
        }
    }

    private fun buildShell() {
        window.decorView.setBackgroundColor(Color.parseColor("#F2F3F5"))
        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(30))
        }
        container.padForSystemBars()
        scroll.addView(container)
        setContentView(scroll)
    }

    private fun renderIdle() {
        container.removeAllViews()
        container.addView(line("军师咨询", 24f, ink, true))
        container.addView(line(taskLabel(), 14f, accent, true).apply { setPadding(0, dp(4), 0, 0) })
        container.addView(hint("完整咨询可与军师连续追问。手机上的聊天原文只在你核对后才会发送给已配置的模型；话术不会自动发送。")
            .apply { setPadding(0, dp(4), 0, dp(10)) })

        if (contactId == null) {
            container.addView(card("尚未建档，也可以直接咨询。", "补充姓名、MBTI、主观评分和关系背景能减少重复解释；不知道可留空。",
                "补充档案（可跳过）") { profileDialog() })
        } else {
            container.addView(hint("对象：$title" + if (relationship.isBlank()) "" else " · $relationship"))
        }
        snapshot?.let { container.addView(hint("已带入 ${it.messages.size} 条已核对消息")) }

        container.addView(label("这一轮你最想解决什么"))
        goalEdit = EditText(this).apply {
            hint = defaultGoal()
            setText(defaultGoal())
            minLines = 3; maxLines = 7
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(ink); textSize = 14f
            background = round(dp(12), Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        container.addView(goalEdit)

        container.addView(primary("开始咨询") { generate() })
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "补充 / 修改档案" to { profileDialog() }
        if (transcripts.enabled()) actions += "历次咨询" to { showHistory() }
        actions += "精简记忆" to { startActivity(android.content.Intent(this, MemoryActivity::class.java)) }
        container.addView(actionGroup(actions))
        container.addView(divider())
        container.addView(hint("紧急回复、强烈情绪或危险场景：先处理眼前安全，不必先填档案。"))
    }

    private fun defaultGoal(): String = when (task) {
        CoachTask.OPEN -> "帮我主动发起一次自然、低压力的聊天。"
        CoachTask.REPLY -> "帮我回复当前对话。"
        CoachTask.END -> when (endMode) {
            EndChatMode.END_TURN -> "帮我把本轮聊天自然收尾，不制造借口。"
            EndChatMode.REDUCE_INVESTMENT -> "帮我减少单边投入、交还主动权，但不羞辱或操控对方。"
            EndChatMode.END_RELATIONSHIP -> "帮我清楚、尊重地表达结束关系，并给出安全边界。"
        }
        CoachTask.CONSULT -> "帮我看清当前局面，并给我一个现在能做的小动作。"
    }

    private fun taskLabel(): String = when (task) {
        CoachTask.OPEN -> "发起聊天"
        CoachTask.REPLY -> "帮我回复"
        CoachTask.END -> "结束聊天 · ${endMode.label}"
        CoachTask.CONSULT -> "完整咨询"
    }

    private fun generate() {
        if (running) return
        val goal = goalEdit.text.toString().trim().ifBlank { defaultGoal() }
        running = true
        renderLoading()
        val before = turns.toList()
        val request = CoachRequest(task = task, contactId = contactId, relationship = relationship,
            snapshot = snapshot, userGoal = goal, turn = before.size / 2 + 1,
            endMode = endMode, conversation = before)
        val token = WorkToken { !cancelled }
        activeToken?.cancel()
        activeToken = token
        worker.execute {
            val response = try { coach.submit(request, token) } catch (_: java.util.concurrent.CancellationException) {
                main.post { if (!cancelled) { running = false; renderIdle() } }
                return@execute
            }
            main.post {
                if (cancelled || token !== activeToken) return@post
                activeToken = null
                running = false
                lastResponse = response
                turns += CoachMessage("user", goal)
                turns += CoachMessage("assistant", response.consultation)
                renderResponse(response)
            }
        }
    }

    private fun renderLoading() {
        container.removeAllViews()
        container.addView(line(taskLabel(), 22f, ink, true))
        container.addView(hint("军师正在区分事实、推测、情绪与下一步…"))
        container.addView(primary("取消", accent = false) { finish() })
    }

    private fun renderResponse(response: CoachResponse) {
        container.removeAllViews()
        container.addView(line(taskLabel(), 23f, ink, true))
        if (response.error != null) {
            container.addView(line("未能完成：${response.error}", 14f, Color.parseColor("#DC2626"), true))
        }
        if (response.consultation.isNotBlank()) {
            container.addView(section("军师建议"))
            container.addView(cardText(response.consultation))
        }
        response.candidates.forEachIndexed { i, candidate ->
            val body = candidate.text + if (candidate.reason.isBlank() && candidate.tradeoff.isBlank()) ""
                else "\n\n为什么：" + candidate.reason.ifBlank { "按当前事实和目标更稳妥" } +
                    if (candidate.tradeoff.isBlank()) "" else "\n代价：" + candidate.tradeoff
            container.addView(section(if (i == 0) "首选话术（可复制）" else "备选 ${i + 1}"))
            container.addView(card(body, if (i == 0) "复制首选" else "复制", null,
                if (i == 0) green else accent) { copy(candidate.text) })
        }
        if (response.candidates.isEmpty() && response.error == null) {
            container.addView(hint("这轮不建议硬发消息。先处理眼前事实、安全或情绪，比凑一句更重要。"))
        }
        val notes = listOf("发送时机" to response.timing, "积极回应后" to response.positive,
            "含糊回应后" to response.ambiguous, "不回应时" to response.noReply,
            "拒绝或不适后" to response.rejection, "候选排序" to response.rankingNotice.orEmpty(),
            "记忆" to response.memoryNotice.orEmpty())
            .filter { it.second.isNotBlank() }
        if (notes.isNotEmpty()) {
            container.addView(section("接下来"))
            notes.forEach { container.addView(cardText("${it.first}\n${it.second}")) }
        }

        container.addView(label("继续问"))
        goalEdit = EditText(this).apply {
            hint = "例如：如果对方说没空，我下一步怎么办？"
            minLines = 2; maxLines = 5; gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = round(dp(12), Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        container.addView(goalEdit)
        container.addView(primary("继续咨询") {
            task = CoachTask.CONSULT
            generate()
        })
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (transcripts.enabled()) actions += "历次咨询" to { showHistory() }
        actions += "精简记忆" to { startActivity(android.content.Intent(this, MemoryActivity::class.java)) }
        actions += "返回任务选择" to { renderIdle() }
        container.addView(actionGroup(actions))
    }

    private fun profileDialog() {
        val existing = contactId?.let { store.contact(it) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        val name = field(existing?.name ?: title.takeIf { it != "军师咨询" }.orEmpty(), "对象称呼 / 代号")
        val mbti = field("", "对象 MBTI（不知道留空）")
        val score = field("", "你对 TA 的主观综合评分 0–100（不知道留空）")
        val rel = field(existing?.relationship ?: relationship, "当前关系")
        val stage = field(existing?.stage ?: "未填写", "关系阶段")
        val notes = field(existing?.notes.orEmpty(), "关键事件、优势、顾虑（最多 200 字）").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        }
        box.addView(label("对象档案")); box.addView(name); box.addView(mbti); box.addView(score)
        box.addView(rel); box.addView(stage); box.addView(notes)
        box.addView(hint("评分只代表你当下的主观评价。未知项留空，军师不会替你填写。"))
        val dialog = AlertDialog.Builder(this).setTitle("补充档案（可跳过）")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("保存", null).setNegativeButton("跳过", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nm = name.text.toString().trim()
                val sc = score.text.toString().trim().toIntOrNull()
                if (sc != null && sc !in 0..100) { toast("评分必须是 0–100，或留空"); return@setOnClickListener }
                val c = Contact(
                    id = existing?.id ?: KbStore.newId(),
                    name = nm.ifBlank { existing?.name ?: "待命名对象" },
                    relationship = rel.text.toString().trim(),
                    notes = notes.text.toString().trim().take(200),
                    stage = stage.text.toString().trim().ifBlank { "未填写" },
                    goal = existing?.goal ?: "自然接话"
                )
                if (!store.saveContact(c)) { toast("档案写入失败，未保存"); return@setOnClickListener }
                contactId = c.id; title = c.name; relationship = c.relationship
                val updates = mutableListOf<com.jev.probe.coach.MemoryUpdate>()
                mbti.text.toString().trim().takeIf { it.isNotBlank() }?.let { updates +=
                    com.jev.probe.coach.MemoryUpdate(com.jev.probe.coach.MemoryScope.OBJECT, c.id, "mbti", it) }
                if (sc != null) updates += com.jev.probe.coach.MemoryUpdate(
                    com.jev.probe.coach.MemoryScope.OBJECT, c.id, "subjective_score", sc.toString())
                updates += com.jev.probe.coach.MemoryUpdate(com.jev.probe.coach.MemoryScope.RELATIONSHIP,
                    c.id, "stage", c.stage)
                com.jev.probe.coach.CoachMemoryStore.get(this).apply(updates)
                dialog.dismiss(); renderIdle()
            }
        }
        dialog.show()
    }

    private fun maybeInviteProfile() {
        if (contactId != null) return
        AlertDialog.Builder(this).setTitle("要补充关系档案吗？")
            .setMessage("可以跳过直接咨询；是否允许精简记忆之后可在设置里单独控制。")
            .setPositiveButton("补充（可跳过）") { _, _ -> profileDialog() }
            .setNegativeButton("直接咨询", null).show()
    }

    private fun showHistory() {
        val sessions = transcripts.list()
        if (sessions.isEmpty()) { toast("还没有保存的咨询记录"); return }
        val labels = sessions.map { s -> s.title + " · " + s.turns.size + " 轮" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("历次咨询")
            .setItems(labels) { _, which ->
                val s = sessions[which]
                turns = s.turns.flatMap { listOf(CoachMessage("user", it.user), CoachMessage("assistant", it.assistant)) }.toMutableList()
                contactId = s.contactId
                title = s.title
                val last = s.turns.lastOrNull()
                lastResponse = last?.let { CoachResponse(it.assistant) }
                lastResponse?.let { renderResponse(it) } ?: renderIdle()
            }
            .setNeutralButton("清空记录") { _, _ -> transcripts.clear(); toast("咨询原文已清空") }
            .setNegativeButton("关闭", null).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::goalEdit.isInitialized) outState.putString(STATE_GOAL, goalEdit.text.toString())
        outState.putStringArrayList(STATE_TURNS, ArrayList(turns.map { it.role + "\u0001" + it.text }))
        lastResponse?.let { outState.putString(STATE_RESPONSE, encodeResponse(it)) }
    }

    private fun encodeResponse(r: CoachResponse): String = JSONObject()
        .put("consultation", r.consultation).put("timing", r.timing).put("positive", r.positive)
        .put("ambiguous", r.ambiguous).put("no_reply", r.noReply).put("rejection", r.rejection)
        .put("memory_notice", r.memoryNotice ?: "").put("ranking_notice", r.rankingNotice ?: "")
        .put("error", r.error ?: "")
        .put("candidates", JSONArray().apply { r.candidates.forEach { c -> put(JSONObject()
            .put("text", c.text).put("label", c.label).put("reason", c.reason).put("tradeoff", c.tradeoff)) } })
        .toString()

    private fun decodeResponse(raw: String): CoachResponse? = runCatching {
        val o = JSONObject(raw)
        CoachResponse(
            consultation = o.optString("consultation"), timing = o.optString("timing"),
            positive = o.optString("positive"), ambiguous = o.optString("ambiguous"),
            noReply = o.optString("no_reply"), rejection = o.optString("rejection"),
            memoryNotice = o.optString("memory_notice").takeIf { it.isNotBlank() },
            rankingNotice = o.optString("ranking_notice").takeIf { it.isNotBlank() },
            error = o.optString("error").takeIf { it.isNotBlank() },
            candidates = o.optJSONArray("candidates")?.let { a -> (0 until a.length()).map { i ->
                val c = a.getJSONObject(i); com.jev.probe.coach.CoachCandidate(c.optString("text"),
                    c.optString("label"), c.optString("reason"), c.optString("tradeoff")) } } ?: emptyList())
    }.getOrNull()

    override fun onDestroy() {
        cancelled = true
        activeToken?.cancel()
        activeToken = null
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun copy(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("jev_coach", text))
        toast("已复制；不会自动发送")
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun section(value: String) = line(value, 13f, sub, true).apply { setPadding(0, dp(16), 0, dp(4)) }
    private fun label(value: String) = line(value, 13f, ink, true).apply { setPadding(0, dp(12), 0, dp(4)) }
    private fun hint(value: String) = line(value, 12f, sub)

    private fun line(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun cardText(value: String) = TextView(this).apply {
        text = value; textSize = 14f; setTextColor(ink)
        background = round(dp(12), Color.WHITE); setPadding(dp(12), dp(11), dp(12), dp(11))
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun card(value: String, subtitle: String, action: String? = "复制", color: Int = accent,
                     onClick: (() -> Unit)? = null): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = round(dp(12), Color.WHITE)
            setPadding(dp(12), dp(11), dp(12), dp(11))
        }
        box.addView(cardText(value))
        if (subtitle.isNotBlank()) box.addView(hint(subtitle).apply { setPadding(0, dp(6), 0, 0) })
        if (action != null) box.addView(primary(action, color = color) { onClick?.invoke() })
        return box
    }

    private fun primary(labelText: String, accent: Boolean = true,
                        color: Int = if (accent) Color.parseColor("#2B5245") else sub,
                        onClick: () -> Unit) = TextView(this).apply {
        text = labelText; textSize = 14f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
        background = round(dp(11), color); setPadding(dp(14), dp(11), dp(14), dp(11))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
    }

    private fun actionGroup(items: List<Pair<String, () -> Unit>>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        items.forEach { (labelText, action) ->
            addView(primary(labelText, accent = false, color = Color.WHITE) { action() }.apply {
                setTextColor(accent); background = round(dp(10), Color.WHITE, stroke = true)
            })
        }
    }

    private fun field(value: String, hintText: String) = EditText(this).apply {
        setText(value); hint = hintText; textSize = 14f; setTextColor(ink)
        background = round(dp(10), Color.WHITE, stroke = true)
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    private fun divider() = View(this).apply { setBackgroundColor(Color.parseColor("#1F000000")) }
    private fun round(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color)
        if (stroke) setStroke(dp(1), Color.parseColor("#D1D5DB"))
    }

    companion object {
        const val EXTRA_TASK = "coach_task"
        const val EXTRA_END_MODE = "coach_end_mode"
        const val EXTRA_CONTACT_ID = "coach_contact_id"
        const val EXTRA_TITLE = "coach_title"
        const val EXTRA_RELATIONSHIP = "coach_relationship"
        const val EXTRA_MESSAGES = "coach_messages"
        const val EXTRA_AUTO_START = "coach_auto_start"
        const val EXTRA_FROM_OVERLAY = "coach_from_overlay"
        private const val STATE_GOAL = "coach_state_goal"
        private const val STATE_TURNS = "coach_state_turns"
        private const val STATE_RESPONSE = "coach_state_response"
    }
}
