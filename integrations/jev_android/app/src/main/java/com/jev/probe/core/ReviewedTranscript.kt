package com.jev.probe.core

/**
 * Decodes the human-reviewed transcript back into messages.
 *
 * Two formats are supported:
 *
 * - **Line format** ([parse]): one message per line, `我：` / `对方：` prefix.
 *   This is what the copy/history views show. It cannot represent a message that
 *   itself contains a newline.
 * - **Block format** ([parseBlocks] / [encode]): messages separated by [SEP], each
 *   starting with a `我：` / `对方：` marker but free to span multiple lines. This
 *   is lossless for long, multi-paragraph bubbles and round-trips exactly.
 *
 * Unknown OCR sides must be corrected explicitly, never defaulted to the other
 * person.
 */
internal object ReviewedTranscript {

    /** Separator between messages in the multi-line-safe block format. */
    const val SEP = "\n===\n"

    private const val ME = "我："
    private const val OTHER = "对方："
    private const val UNKNOWN = "待确认："

    private fun prefixOf(side: String): String = when (side) {
        "me" -> ME
        "other" -> OTHER
        else -> UNKNOWN
    }

    /** One message per line. Cannot carry a newline inside a message body. */
    fun parse(text: String): List<Msg> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        require(lines.isNotEmpty())
        return lines.map {
            val side = when {
                it.startsWith(ME) -> "me"
                it.startsWith(OTHER) -> "other"
                else -> throw IllegalArgumentException("请核对每条消息的我／对方身份")
            }
            val body = it.substringAfter('：').trim()
            require(body.isNotEmpty())
            Msg(side, body)
        }
    }

    /**
     * Lossless encoding: each message is `prefix + body` and messages are joined
     * by [SEP]. Bodies keep their internal newlines and colons.
     */
    fun encode(messages: List<Msg>): String =
        messages.joinToString(SEP) { prefixOf(it.side) + it.text }

    /**
     * Inverse of [encode]. Every block must declare its side; a body may span
     * multiple lines. Blank blocks are ignored; an unmarked block is rejected.
     */
    fun parseBlocks(text: String): List<Msg> {
        val out = ArrayList<Msg>()
        for (raw in text.split(SEP)) {
            val block = raw.trim('\n')
            if (block.isEmpty()) continue
            val side = when {
                block.startsWith(ME) -> "me"
                block.startsWith(OTHER) -> "other"
                else -> throw IllegalArgumentException("请核对每条消息的我／对方身份")
            }
            val body = block.removePrefix(prefixOf(side))
            require(body.isNotBlank())
            out.add(Msg(side, body))
        }
        require(out.isNotEmpty())
        return out
    }
}
