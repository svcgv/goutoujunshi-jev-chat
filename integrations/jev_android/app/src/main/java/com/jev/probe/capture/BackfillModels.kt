package com.jev.probe.capture

import com.jev.probe.core.Msg

/** Where a bubble's text came from. */
internal enum class BubbleSource { NODE, OCR }

/**
 * Whether a captured bubble is known to be complete.
 *
 * The review page treats anything other than [COMPLETE] as needing attention;
 * [CONFLICT] additionally means automatic stitching gave up and the user must
 * decide what the real text is.
 */
internal enum class MessageCompleteness {
    /** Fully visible on one screen, or a trustworthy node returned the whole text. */
    COMPLETE,
    /** Assembled from several screens while scrolling. */
    STITCHED,
    /** Clipped at a viewport edge and not fully recovered. */
    PARTIAL,
    /** Overlapping captures disagreed; the user must resolve the text. */
    CONFLICT,
    /** A non-text bubble (image/voice/…) we can count but not read. */
    UNKNOWN;

    val needsAttention: Boolean get() = this != COMPLETE
}

/** Why a backfill run stopped. */
internal enum class BackfillStopReason {
    TARGET_REACHED,
    HISTORY_TOP,
    CANCELED,
    IDENTITY_CHANGED,
    UNREADABLE,
    TIMEOUT,
    PERMISSION_REVOKED,
    SCROLL_LIMIT,
    BOTTOM_UNCONFIRMED
}

/** Which stage of the run is active, for progress text. */
internal enum class BackfillPhase { IDLE, TO_BOTTOM, COLLECTING, RETURNING, DONE }

/** One bubble as seen on a single screen, in screen coordinates. */
internal data class CapturedBubble(
    val text: String,
    val side: String,
    val top: Int,
    val bottom: Int,
    val source: BubbleSource,
    val clippedTop: Boolean = false,
    val clippedBottom: Boolean = false,
    val nodeId: String? = null,
    /** Non-text bubble kind ("图片"/"语音"/…); null for a normal text bubble. */
    val placeholder: String? = null
) {
    val isPlaceholder: Boolean get() = placeholder != null
}

/**
 * One merged message in the collected history, oldest-first.
 *
 * [text] is what is shown and edited in the review page; [fragments] retains how
 * it was assembled so completeness can be re-derived after edits.
 */
internal data class CollectedMessage(
    val id: String,
    val side: String,
    val text: String,
    val completeness: MessageCompleteness,
    val fragments: List<CapturedBubble> = emptyList()
) {
    fun toMsg(): Msg = Msg(side, text)
}

/** Options for one run. [target] is clamped by [BackfillPlan]. */
internal data class BackfillOptions(val target: Int = BackfillPlan.TARGET_MESSAGES) {
    val clampedTarget: Int get() = BackfillPlan.clampTarget(target)
}

/** Snapshot of progress for the overlay. */
internal data class BackfillState(
    val phase: BackfillPhase,
    val complete: Int,
    val pending: Int,
    val swipes: Int,
    val target: Int
) {
    val collected: Int get() = complete + pending

    fun progressText(): String = when (phase) {
        BackfillPhase.IDLE -> ""
        BackfillPhase.TO_BOTTOM -> "正在回到会话底部…"
        BackfillPhase.COLLECTING -> "补录中 $complete/$target" +
            if (pending > 0) "（$pending 条待补齐）" else ""
        BackfillPhase.RETURNING -> "已收集 $complete/$target · 回到最新消息…"
        BackfillPhase.DONE -> "已收集 $complete/$target"
    }
}

/** Final result of a run. [messages] is oldest-first. */
internal data class BackfillResult(
    val messages: List<CollectedMessage>,
    val stopReason: BackfillStopReason,
    val target: Int
) {
    val reachedTarget: Boolean get() = stopReason == BackfillStopReason.TARGET_REACHED
    val hasGap: Boolean get() = messages.any { it.completeness == MessageCompleteness.CONFLICT }
    val incompleteCount: Int get() = messages.count { it.completeness.needsAttention }

    /** One-line summary shown above the review list. */
    fun summary(): String {
        val got = messages.size
        val base = when (stopReason) {
            BackfillStopReason.TARGET_REACHED -> "已补录 $got/$target 条"
            BackfillStopReason.HISTORY_TOP -> "已到达会话顶部，共 $got 条"
            BackfillStopReason.SCROLL_LIMIT -> "已到采集上限，共收集 $got 条"
            BackfillStopReason.BOTTOM_UNCONFIRMED -> "无法确认会话底部，仅收集到 $got 条"
            BackfillStopReason.TIMEOUT -> "采集超时，共收集 $got 条"
            BackfillStopReason.IDENTITY_CHANGED -> "会话已切换，已停止补录"
            BackfillStopReason.PERMISSION_REVOKED -> "屏幕捕获已停止，已停止补录"
            BackfillStopReason.UNREADABLE -> "无法读取更多消息，已停止补录"
            BackfillStopReason.CANCELED -> "已取消补录"
        }
        return if (incompleteCount > 0) "$base · $incompleteCount 条待核对" else base
    }
}
