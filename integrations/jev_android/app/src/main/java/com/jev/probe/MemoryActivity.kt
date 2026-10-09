package com.jev.probe

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.coach.CoachMemoryStore
import com.jev.probe.coach.ConsultationStore
import com.jev.probe.coach.MemoryItem
import kotlin.math.roundToInt

/** User-facing controls for the three independent storage permissions. */
class MemoryActivity : AppCompatActivity() {
    private lateinit var memory: CoachMemoryStore
    private lateinit var transcripts: ConsultationStore
    private lateinit var container: LinearLayout

    private val accent = Color.parseColor("#2B5245")
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")
    private val red = Color.parseColor("#DC2626")
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
        v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        memory = CoachMemoryStore.get(this)
        transcripts = ConsultationStore.get(this)
        window.decorView.setBackgroundColor(Color.parseColor("#F2F3F5"))
        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(30))
        }
        container.padForSystemBars()
        scroll.addView(container)
        setContentView(scroll)
        render()
    }

    private fun render() {
        container.removeAllViews()
        val status = memory.status()
        container.addView(line("记忆与隐私", 24f, ink, true))
        container.addView(hint("三类存储互不代替：聊天历史、精简长期记忆、军师咨询原文。这里只控制后两类。"))
        container.addView(section("精简长期记忆"))
        container.addView(card(if (status.enabled) "已同意" else "未启用",
            "只保存会影响未来建议的短字段和关键事件；单条不超过 200 字，可查看、纠正、暂停、撤销和删除。"))
        if (!status.enabled) {
            container.addView(primary("同意并在本机启用") {
                confirm("启用精简记忆", "启用后每轮会自动更新稳定信息和关键事件，并提示变更。你可以随时暂停或撤销。") {
                    if (memory.enable()) render() else toast("写入失败，未启用")
                }
            })
            if (status.items.isNotEmpty()) container.addView(primary("删除现有精简记忆", danger = true) {
                confirm("删除现有精简记忆", "这会硬删除全部字段、事件和撤销历史，不能恢复。") {
                    if (memory.clear()) render() else toast("删除失败")
                }
            })
        } else {
            container.addView(primary(if (status.paused) "恢复自动更新" else "暂停自动更新") {
                val ok = if (status.paused) memory.resume() else memory.pause()
                if (ok) render() else toast("状态写入失败")
            })
            if (status.undoCount > 0) container.addView(primary("撤销最近一次更新") {
                if (memory.undo()) render() else toast("没有可撤销的更新")
            })
            container.addView(primary("撤回同意并保留现有资料") {
                confirm("撤回同意", "停止新记忆读写，但保留现有资料供你以后选择删除。") {
                    if (memory.revoke(false)) render() else toast("状态写入失败")
                }
            })
            container.addView(primary("撤回并删除全部精简记忆", danger = true) {
                confirm("删除全部精简记忆", "这会硬删除全部字段、事件和撤销历史，不能恢复。") {
                    if (memory.revoke(true)) render() else toast("删除失败")
                }
            })
        }

        if (status.items.isNotEmpty()) {
            container.addView(section("已保存 ${status.items.size} 项"))
            val grouped = status.items.sortedWith(compareBy<MemoryItem> { it.subjectId }.thenByDescending { it.updatedAt })
            grouped.forEach { container.addView(memoryCard(it)) }
        }

        container.addView(section("军师咨询原文"))
        container.addView(card(if (transcripts.enabled()) "已允许保存" else "默认不保存",
            "关闭时只保留当前页面会话。开启后咨询原文会在本机保存，可在这里清空；它与精简记忆无关。"))
        container.addView(primary(if (transcripts.enabled()) "关闭并保留已有咨询原文" else "允许保存咨询原文") {
            if (transcripts.setEnabled(!transcripts.enabled())) render() else toast("设置写入失败")
        })
        container.addView(primary("清空全部咨询原文", danger = true) {
            confirm("清空咨询原文", "只删除保存的咨询记录，不影响聊天历史和精简记忆。") {
                if (transcripts.clear()) render() else toast("清空失败")
            }
        })
        container.addView(section("当前聊天历史"))
        container.addView(card("由聊天记录开关控制", "现有联系人的核对历史仍由“记录聊天历史”开关管理；本次升级不会自动扩大旧授权。"))
    }

    private fun memoryCard(item: MemoryItem): TextView = TextView(this).apply {
        val scope = when (item.scope.name) {
            "USER" -> "用户"; "OBJECT" -> "对象"; "RELATIONSHIP" -> "关系"
            "EVENT" -> "事件"; else -> "暂定"
        }
        text = "$scope · ${item.field}\n${item.value}\n来源 ${item.sourceType} · 更新可纠正"
        textSize = 13f; setTextColor(ink); background = round(dp(12), Color.WHITE, false)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) }
        layoutParams = params
        setOnLongClickListener {
            AlertDialog.Builder(this@MemoryActivity).setTitle("管理这条记忆")
                .setItems(arrayOf("纠正内容", "删除这个对象的全部记忆")) { _, which ->
                    if (which == 0) {
                        val edit = android.widget.EditText(this@MemoryActivity).apply { setText(item.value) }
                        AlertDialog.Builder(this@MemoryActivity).setTitle("纠正这条记忆").setView(edit)
                            .setPositiveButton("保存") { _, _ ->
                                if (memory.correct(item.id, edit.text.toString())) render() else toast("纠正失败")
                            }.setNegativeButton("取消", null).show()
                    } else {
                        confirm("删除对象记忆", "删除 ${item.subjectId} 的全部精简记忆，不能恢复。") {
                            if (memory.deleteSubject(item.subjectId)) render() else toast("删除失败")
                        }
                    }
                }.setNegativeButton("取消", null).show()
            true
        }
    }

    private fun confirm(title: String, message: String, block: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("确认") { _, _ -> block() }.setNegativeButton("取消", null).show()
    }
    private fun toast(v: String) = Toast.makeText(this, v, Toast.LENGTH_SHORT).show()
    private fun section(v: String) = line(v, 13f, sub, true).apply { setPadding(0, dp(18), 0, dp(3)) }
    private fun hint(v: String) = line(v, 12f, sub)
    private fun line(v: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = v; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun card(title: String, body: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = round(dp(12), Color.WHITE)
        setPadding(dp(12), dp(11), dp(12), dp(11)); addView(line(title, 15f, ink, true)); addView(hint(body))
    }
    private fun primary(textValue: String, danger: Boolean = false, block: () -> Unit) = TextView(this).apply {
        text = textValue; textSize = 14f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE); background = round(dp(11), if (danger) red else accent); setPadding(dp(12), dp(11), dp(12), dp(11))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        setOnClickListener { block() }
    }
    private fun round(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color)
        if (stroke) setStroke(dp(1), accent)
    }
}
