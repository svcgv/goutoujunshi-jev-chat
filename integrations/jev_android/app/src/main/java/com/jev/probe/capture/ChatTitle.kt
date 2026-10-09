package com.jev.probe.capture

/**
 * Picks the conversation title out of everything OCR finds in the title band.
 *
 * The band also contains the back arrow and the "more" button, and WeChat's own
 * bar may contain a member count for group chats. Only line text is considered,
 * and obvious non-names are discarded so a button label can never become the
 * contact's name.
 */
internal object ChatTitle {

    /** Labels that appear in the bar but are never the conversation name. */
    private val REJECT = setOf(
        "更多信息", "返回", "聊天信息", "微信", "通讯录", "发现", "我"
    )

    /** Leading dots/symbols the platform puts in front of the title. */
    private val LEADING = "…·•.、,，:：;；-—_ "

    /**
     * @param lines OCR lines whose bounds already intersect the title band.
     * @return the best title candidate, or null when nothing plausible was read.
     */
    fun pick(lines: List<Pair<String, Int>>): String? =
        lines.asSequence()
            .map { (raw, _) -> clean(raw) }
            .filter { it != null }
            .map { it!! }
            .filter { it !in REJECT }
            .firstOrNull()

    private fun clean(raw: String): String? {
        val t = raw.trim().trimStart(*LEADING.toCharArray()).trimEnd(*LEADING.toCharArray()).trim()
        if (t.isEmpty() || t.length > 24) return null
        // A pure timestamp or a member count on its own is not a name.
        if (LooksLikeTime.matches(t)) return null
        if (Regex("""^\d+$""").matches(t)) return null
        return t
    }

    private val LooksLikeTime = Regex("""^\d{1,2}[:：]\d{2}$""")
}
