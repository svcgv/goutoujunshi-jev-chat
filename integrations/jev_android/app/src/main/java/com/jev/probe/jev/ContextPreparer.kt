package com.jev.probe.jev

import com.jev.probe.core.Msg

/**
 * Conservative token estimate used when no route-specific tokenizer is known.
 *
 * Chinese is roughly one token per character and UTF-8 spends three bytes per
 * character, so `bytes / 3` lands close to one token per CJK character and stays
 * a little pessimistic for ASCII. Never used for billing — only for budgeting.
 */
internal object TokenEstimator {
    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        val bytes = text.toByteArray(Charsets.UTF_8).size
        return (bytes + 2) / 3 + 1
    }
}

/** How much of a route's context window one request may spend. */
internal data class TokenBudget(
    val contextWindow: Int = DEFAULT_CONTEXT_WINDOW,
    val reservedOutput: Int = DEFAULT_RESERVED_OUTPUT,
    val safetyFraction: Double = DEFAULT_SAFETY
) {
    /** Input tokens left after reserving output and a safety margin. */
    val usableInput: Int
        get() = ((contextWindow - reservedOutput).coerceAtLeast(0) *
            (1.0 - safetyFraction.coerceIn(0.0, 0.5))).toInt().coerceAtLeast(0)

    companion object {
        const val DEFAULT_CONTEXT_WINDOW = 8192
        const val DEFAULT_RESERVED_OUTPUT = 2048
        const val DEFAULT_SAFETY = 0.10
    }
}

/** Splits an over-long conversation at message / paragraph / character boundaries. */
internal object ContextChunker {

    /** Groups messages so each group's rendered form stays under [maxTokens]. */
    fun chunkMessages(messages: List<Msg>, maxTokens: Int, renderOne: (Msg) -> String): List<List<Msg>> {
        val out = ArrayList<List<Msg>>()
        var current = ArrayList<Msg>()
        var used = 0
        for (m in messages) {
            val t = TokenEstimator.estimate(renderOne(m))
            if (t > maxTokens) {
                if (current.isNotEmpty()) { out.add(current); current = ArrayList(); used = 0 }
                splitMessage(m, maxTokens, renderOne).forEach { out.add(listOf(it)) }
                continue
            }
            if (used + t > maxTokens && current.isNotEmpty()) {
                out.add(current); current = ArrayList(); used = 0
            }
            current.add(m); used += t
        }
        if (current.isNotEmpty()) out.add(current)
        return out
    }

    /** Greedily splits one over-long message into fragments that each fit. */
    fun splitMessage(m: Msg, maxTokens: Int, renderOne: (Msg) -> String): List<Msg> {
        val text = m.text
        if (text.isEmpty()) return listOf(m)
        val out = ArrayList<Msg>()
        var start = 0
        while (start < text.length) {
            var end = start + 1
            while (end <= text.length &&
                TokenEstimator.estimate(renderOne(Msg(m.side, text.substring(start, end)))) <= maxTokens) {
                end++
            }
            val cut = (end - 1).coerceAtLeast(start + 1)
            out.add(Msg(m.side, text.substring(start, cut)))
            start = cut
        }
        return out
    }
}

/**
 * Turns a reviewed conversation into the text one model request may send.
 *
 * Within budget the full transcript goes out verbatim. Over budget, the newest
 * messages are kept verbatim and older ones are compressed through a caller
 * supplied extractor; the returned [Prepared.notice] tells the UI how many
 * messages were compressed. The reviewed原文 is never modified — only the
 * request payload is.
 */
internal class ContextPreparer(
    private val budget: TokenBudget,
    private val maxCalls: Int = MAX_CALLS
) {

    class Prepared(
        val text: String,
        val verbatimMessages: Int,
        val compressedMessages: Int,
        val calls: Int
    ) {
        val totalMessages: Int get() = verbatimMessages + compressedMessages
        val compressed: Boolean get() = compressedMessages > 0

        fun notice(): String? = if (compressed)
            "本轮共 $totalMessages 条，其中 $compressedMessages 条使用压缩信息；核对原文未改变。"
        else null
    }

    /**
     * @param primary reviewed messages that matter most (oldest-first).
     * @param history extra stored history, droppable first (oldest-first).
     * @param overheadTokens tokens the fixed prompt/background already spend.
     * @param render renders messages into the on-wire transcript form.
     * @param summarize extracts durable facts from one chunk's rendered text.
     * @throws IllegalArgumentException when the fixed overhead alone is too large.
     * @throws IllegalStateException when compression cannot fit or exceeds the cap.
     */
    fun prepare(
        primary: List<Msg>,
        history: List<Msg>,
        overheadTokens: Int,
        render: (List<Msg>) -> String,
        summarize: (String) -> String
    ): Prepared {
        val available = budget.usableInput - overheadTokens
        require(available >= MIN_CHUNK_TOKENS) {
            "固定提示与背景已超出模型预算，请减小背景或提高上下文窗口"
        }

        // Oldest-first: extra stored history sits BEFORE the reviewed window,
        // so keeping the newest messages verbatim protects the reviewed window.
        val all = history + primary
        if (all.isEmpty()) return Prepared("", 0, 0, 0)

        val full = render(all)
        if (TokenEstimator.estimate(full) <= available) {
            return Prepared(full, all.size, 0, 0)
        }

        // Keep the newest messages verbatim; the rest is compressed.
        val verbatimBudget = (available * VERBATIM_FRACTION).toInt().coerceAtLeast(MIN_CHUNK_TOKENS)
        var verbatimFrom = all.size
        var used = 0
        for (i in all.indices.reversed()) {
            val t = TokenEstimator.estimate(render(listOf(all[i])))
            if (used + t > verbatimBudget) break
            used += t
            verbatimFrom = i
        }
        val verbatim = all.subList(verbatimFrom, all.size)
        val toCompress = all.subList(0, verbatimFrom)
        val verbatimText = render(verbatim)

        val chunkBudget = (available - TokenEstimator.estimate(verbatimText) - SUMMARY_HEADER_TOKENS)
            .coerceAtLeast(MIN_CHUNK_TOKENS)
        val chunks = ContextChunker.chunkMessages(toCompress, chunkBudget) { m -> render(listOf(m)) }

        var calls = 0
        val summaries = ArrayList<String>()
        for (chunk in chunks) {
            if (calls >= maxCalls) {
                throw IllegalStateException("压缩调用超过上限（$maxCalls），请减少条数或提高上下文窗口")
            }
            calls++
            val summary = summarize(render(chunk)).trim()
            if (summary.isNotEmpty()) summaries.add(summary)
        }

        val text = buildString {
            if (summaries.isNotEmpty()) {
                append(SUMMARY_HEADER).append('\n')
                summaries.forEach { append(it).append('\n') }
                append('\n')
            }
            if (verbatim.isNotEmpty()) {
                append(VERBATIM_HEADER).append('\n').append(verbatimText)
            }
        }.trim()

        check(TokenEstimator.estimate(text) <= available) { "压缩后仍超出模型预算，请减少条数或提高上下文窗口" }
        return Prepared(text, verbatim.size, toCompress.size, calls)
    }

    companion object {
        const val MAX_CALLS = 40
        private const val MIN_CHUNK_TOKENS = 32
        private const val VERBATIM_FRACTION = 0.6
        private const val SUMMARY_HEADER_TOKENS = 24
        const val SUMMARY_HEADER = "[较早内容摘要]"
        const val VERBATIM_HEADER = "[最近原文]"
    }
}
