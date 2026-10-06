package com.jev.probe.core

/** Unknown OCR sides must be corrected explicitly, never defaulted to the other person. */
internal object ReviewedTranscript {
    fun parse(text: String): List<Msg> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        require(lines.isNotEmpty())
        return lines.map {
            val side = when {
                it.startsWith("我：") -> "me"
                it.startsWith("对方：") -> "other"
                else -> throw IllegalArgumentException("请核对每条消息的我／对方身份")
            }
            val body = it.substringAfter('：').trim()
            require(body.isNotEmpty())
            Msg(side, body)
        }
    }
}
