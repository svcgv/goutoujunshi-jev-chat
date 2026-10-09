package com.jev.probe.coach

import com.jev.probe.core.ChatSnapshot

/** The four user-visible jobs the coach can perform. */
enum class CoachTask(val wire: String) {
    REPLY("reply"),
    OPEN("open"),
    END("end"),
    CONSULT("consult");

    companion object {
        fun fromWire(value: String?): CoachTask =
            entries.firstOrNull { it.wire == value } ?: CONSULT
    }
}

/** End-chat is deliberately split so a daily sign-off never becomes a relationship exit. */
enum class EndChatMode(val wire: String, val label: String) {
    END_TURN("end_turn", "结束本轮"),
    REDUCE_INVESTMENT("reduce_investment", "减少投入 / 交还主动权"),
    END_RELATIONSHIP("end_relationship", "明确结束关系");

    companion object {
        fun fromWire(value: String?): EndChatMode =
            entries.firstOrNull { it.wire == value } ?: END_TURN
    }
}

/** Everything the coach needs for one turn. The transcript is already user-reviewed. */
data class CoachRequest(
    val task: CoachTask,
    val contactId: String? = null,
    val relationship: String = "",
    val snapshot: ChatSnapshot? = null,
    val userGoal: String = "",
    val turn: Int = 1,
    val endMode: EndChatMode = EndChatMode.END_TURN,
    val background: String = "",
    val memoryContext: String = "",
    val conversation: List<CoachMessage> = emptyList()
)

data class CoachMessage(val role: String, val text: String)

/** Extracted, auditable decision state. It never claims to read the other person's mind. */
data class CoachDecision(
    val strategy: String,
    val facts: List<String> = emptyList(),
    val unknowns: List<String> = emptyList(),
    val clarification: String? = null,
    val boundary: Boolean = false,
    val safety: Boolean = false,
    val error: String? = null
)

/** Consultation copy and sendable copy stay in separate fields. */
data class CoachResponse(
    val consultation: String,
    val candidates: List<CoachCandidate> = emptyList(),
    val timing: String = "",
    val positive: String = "",
    val ambiguous: String = "",
    val noReply: String = "",
    val rejection: String = "",
    val rankingNotice: String? = null,
    val memoryUpdates: List<MemoryUpdate> = emptyList(),
    val memoryNotice: String? = null,
    val error: String? = null
) {
    val hasSendableCopy: Boolean get() = candidates.any { it.text.isNotBlank() }
}

data class CoachCandidate(
    val text: String,
    val label: String = "首选",
    val reason: String = "",
    val tradeoff: String = ""
)

/** A proposed update is applied only when the user explicitly enabled distilled memory. */
data class MemoryUpdate(
    val scope: MemoryScope,
    val subjectId: String,
    val field: String,
    val value: String,
    val sourceType: String = "user_report",
    val sourceRef: String = "",
    val occurredAt: String = "",
    val confidence: String = "high"
)

enum class MemoryScope { USER, OBJECT, RELATIONSHIP, EVENT, HYPOTHESIS;
    companion object {
        fun fromWire(value: String?): MemoryScope = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true) || it.name.lowercase() == value
        } ?: USER
    }
}

data class CoachTurnRecord(
    val task: CoachTask,
    val request: String,
    val response: CoachResponse,
    val at: Long = System.currentTimeMillis()
)
