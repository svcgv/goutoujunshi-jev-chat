package com.jev.probe.coach

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.GoutouGuidance

/** Pure safety checks shared by the coach and covered by unit tests. */
object CoachSafety {
    private val BOUNDARY_PHRASES = setOf(
        "不要再联系我", "别再联系我", "不要再给我发消息", "别再给我发消息",
        "不要再找我", "别再找我", "请不要联系我", "要求停止联系", "明确拒绝并停止"
    )
    private val SAFETY_TERMS = setOf(
        "威胁", "跟踪", "家暴", "强迫", "勒索", "人身危险", "报警", "自杀", "暴力", "骚扰", "下药", "偷拍",
        "堵你", "堵截", "掐脖", "武器", "伤害自己", "伤害你", "去死", "逼我复合"
    )

    fun standingBoundary(snapshot: ChatSnapshot, memoryContext: String = ""): Boolean {
        if (GoutouGuidance.explicitBoundary(snapshot)) return true
        val text = snapshot.messages.takeLast(30).joinToString("\n") { it.text } + "\n" + memoryContext
        return BOUNDARY_PHRASES.any { text.contains(it) }
    }

    fun safetySignal(snapshot: ChatSnapshot, goal: String = "", memoryContext: String = ""): Boolean {
        val text = snapshot.messages.joinToString("\n") { it.text } + "\n" + goal + "\n" + memoryContext
        return SAFETY_TERMS.any { text.contains(it) }
    }
}
