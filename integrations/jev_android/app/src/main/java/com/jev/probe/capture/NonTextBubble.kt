package com.jev.probe.capture

/**
 * Recognizes chat bubbles that are NOT dialogue — a red packet or a transfer —
 * and names them for the transcript.
 *
 * They are kept rather than dropped: "对方发了个红包" is a real signal for the
 * judgement, and removing the row would leave a hole in the conversation the
 * model has to guess about. What the model must not do is read the card's own
 * wording ("微信红包", "恭喜发财，大吉大利") as something the person typed.
 *
 * Matching is deliberately conservative. A message that merely mentions 红包 is
 * still a message, so the text must BE the marker (or the bubble must say so in
 * its accessibility description) — never just contain the word.
 */
internal object NonTextBubble {

    /** Placeholder for a non-dialogue bubble, or null when this is ordinary text. */
    fun placeholderFor(text: String?, description: String? = null): String? {
        val body = text?.trim().orEmpty()
        val desc = description?.trim().orEmpty()

        if (desc.contains("红包") || isRedPacketText(body)) return redPacket(body)
        if (desc.contains("转账") || isTransferText(body)) return transfer(body)
        return null
    }

    private fun redPacket(body: String): String {
        val amount = amountIn(body) ?: return "[红包]"
        return "[红包 $amount]"
    }

    private fun transfer(body: String): String {
        val amount = amountIn(body) ?: return "[转账]"
        return "[转账 $amount]"
    }

    /** "微信红包" / "QQ红包" — the card's own title, never a sentence about one. */
    private fun isRedPacketText(body: String): Boolean =
        body.startsWith("微信红包") || body.startsWith("QQ红包") || body.startsWith("qq红包")

    /**
     * WeChat/QQ label a transfer bubble "转账", optionally followed by the
     * amount, and its state changes to one of the phrases below once the money
     * moves.
     *
     * A sentence that merely starts with the word — "转账给他了，还没到" — is a
     * message someone typed, so the marker must be the WHOLE bubble: either the
     * bare label, the label plus an amount, or one of the fixed state phrases.
     */
    private fun isTransferText(body: String): Boolean {
        if (body.isEmpty()) return false
        if (body == "转账") return true
        if (TRANSFER_CARD.matches(body)) return true
        return body in TRANSFER_STATES
    }

    /** The amount the card shows, when it shows one; kept in the placeholder. */
    private fun amountIn(body: String): String? {
        val match = AMOUNT.find(body) ?: return null
        val value = match.groupValues.getOrNull(1).orEmpty()
        return value.takeIf { it.isNotEmpty() }?.let { "¥$it" }
    }

    private val AMOUNT = Regex("""[¥￥]\s?(\d+(?:\.\d{1,2})?)""")

    /** "转账" + an amount, and nothing else. */
    private val TRANSFER_CARD = Regex("""^转账\s*[¥￥]?\s*\d+(?:\.\d{1,2})?\s*元?$""")

    /** The states a transfer card flips through once the money moves. */
    private val TRANSFER_STATES = setOf("请收款", "已收款", "待收款", "已退还", "已收钱", "已被退还")
}
