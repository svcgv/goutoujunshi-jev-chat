package com.jev.probe.core.kb

import org.json.JSONArray
import org.json.JSONObject

/**
 * A conversation the user explicitly bound to a contact.
 *
 * [title] is the readable conversation title when the app exposes one. WeChat and
 * other self-drawn UIs expose **no** title at all, so [fingerprint] records a few
 * recent normalized messages instead: they are what actually re-identifies that
 * conversation on a later visit, including after the service restarts.
 *
 * [titleAliases] are other titles the SAME conversation has been seen under.
 * Chat apps let the user rename a contact or change a remark, and the bound
 * title then stops matching even though it is the same person. Once such a
 * window is re-identified (by the messages on screen, or by the name already
 * confirmed this session), its new title is recorded here so later visits
 * resolve by exact title again instead of depending on the message fingerprint.
 */
data class ConversationBinding(
    val app: String,
    val title: String,
    val contactId: String,
    val remember: Boolean = false,
    val fingerprint: List<String> = emptyList(),
    val titleAliases: List<String> = emptyList()
) {
    /** Every title this conversation is known by, primary title first. */
    fun titles(): List<String> = listOf(title) + titleAliases

    fun matchesTitle(candidate: String?): Boolean {
        val want = candidate?.trim().orEmpty()
        if (want.isEmpty()) return false
        return titles().any { it.trim() == want }
    }
}

internal object ConversationBindings {

    /** How many recent messages are kept as the conversation's fingerprint. */
    const val FINGERPRINT_SIZE = 8

    /** Minimum overlapping messages required to accept a fingerprint match. */
    const val MIN_OVERLAP = 2

    /** Lines shorter than this are too generic to identify a conversation. */
    private const val FINGERPRINT_MIN_LENGTH = 6

    /** Upper bound on how many lines one binding may remember. */
    private const val FINGERPRINT_CAP = 24

    fun resolve(rows: List<ConversationBinding>, app: String, title: String?): ConversationBinding? {
        if (app.isBlank() || title.isNullOrBlank()) return null
        return rows.filter { it.app == app && it.matchesTitle(title) }.singleOrNull()
    }

    /**
     * Add [alias] to the binding identified by [title] in [app].
     *
     * Returns the updated list, or null when there is no such binding or the
     * alias is already known. Pure: the caller persists the result.
     */
    fun withTitleAlias(
        rows: List<ConversationBinding>,
        app: String,
        title: String,
        alias: String
    ): List<ConversationBinding>? {
        val want = alias.trim()
        if (app.isBlank() || title.isBlank() || want.isEmpty()) return null
        val index = rows.indexOfFirst { it.app == app && it.title.trim() == title.trim() }
        if (index < 0) return null
        val row = rows[index]
        if (row.matchesTitle(want)) return null
        val updated = row.copy(titleAliases = (row.titleAliases + want).distinct())
        return rows.toMutableList().also { it[index] = updated }
    }

    /**
     * The binding whose recorded messages best match what is on screen now.
     *
     * Returns null when nothing matches well enough, or when two bindings tie —
     * guessing between two people is worse than asking the user to confirm.
     */
    fun resolveByFingerprint(
        rows: List<ConversationBinding>,
        app: String,
        messages: List<String>
    ): ConversationBinding? {
        if (app.isBlank() || messages.size < MIN_OVERLAP) return null
        val live = normalizeAll(messages)
        if (live.isEmpty()) return null
        val scored = rows.filter { it.app == app && it.fingerprint.isNotEmpty() }.map { row ->
            row to row.fingerprint.count { it in live }
        }.filter { (_, score) -> score >= MIN_OVERLAP }
        if (scored.isEmpty()) return null
        val best = scored.maxOf { it.second }
        val winners = scored.filter { it.second == best }
        return winners.singleOrNull()?.first
    }

    /**
     * Match against a larger corpus per binding than the fingerprint holds.
     *
     * The fingerprint only covers the handful of lines visible when the user
     * bound the conversation, so it stops matching as soon as the chat moves on
     * or the user scrolls. The contact's stored history (up to 300 reviewed
     * lines) is a far better corpus for the same question, and is what recovers
     * a window whose title the app does not expose.
     *
     * Ties are refused: guessing between two people is worse than nothing.
     */
    fun resolveByCorpus(
        entries: List<Pair<ConversationBinding, List<String>>>,
        app: String,
        messages: List<String>,
        minOverlap: Int = MIN_OVERLAP
    ): ConversationBinding? {
        if (app.isBlank()) return null
        val live = normalizeAll(messages)
        if (live.isEmpty()) return null
        val scored = entries
            .filter { (binding, _) -> binding.app == app }
            .map { (binding, corpus) -> binding to corpus.count { normalize(it) in live } }
            .filter { (_, score) -> score >= minOverlap }
        if (scored.isEmpty()) return null
        val best = scored.maxOf { it.second }
        return scored.filter { it.second == best }.singleOrNull()?.first
    }

    /**
     * Grow [existing] with distinctive messages seen on the current screen.
     *
     * The fingerprint is what re-identifies a window whose title cannot be read
     * (WeChat). The few lines captured when the user bound it stop matching as
     * soon as the conversation moves on or the user scrolls, so every successful
     * identification also feeds new lines back in. Only reasonably long lines are
     * taken: "嗯" / "好的" / "哈哈" are shared by every chat and would make two
     * conversations look alike.
     *
     * @return the merged list, or null when there is nothing new to record.
     */
    fun expandedFingerprint(
        existing: List<String>,
        live: List<String>,
        minLength: Int = FINGERPRINT_MIN_LENGTH,
        cap: Int = FINGERPRINT_CAP
    ): List<String>? {
        val known = existing.toSet()
        val fresh = live.mapNotNull { normalize(it) }
            .filter { it.length >= minLength && it !in known }
            .distinct()
        if (fresh.isEmpty()) return null
        // Keep the newest lines, and never lose what was already recorded.
        return (existing + fresh).distinct().takeLast(cap)
    }

    /** Normalized, deduplicated, order-stable fingerprint for a screen of messages. */
    fun fingerprintOf(messages: List<String>): List<String> =
        messages.mapNotNull { normalize(it) }.distinct().takeLast(FINGERPRINT_SIZE)

    private fun normalizeAll(messages: List<String>): Set<String> =
        messages.mapNotNull { normalize(it) }.toSet()

    private fun normalize(raw: String): String? =
        raw.trim().lowercase().takeIf { it.isNotEmpty() }

    fun decode(raw: String): List<ConversationBinding> {
        val rows = JSONArray(raw)
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            ConversationBinding(
                app = row.getString("app"),
                title = row.getString("title"),
                contactId = row.getString("contactId"),
                remember = row.optBoolean("remember", false),
                fingerprint = row.optJSONArray("fingerprint")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                }.orEmpty(),
                titleAliases = row.optJSONArray("titleAliases")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                }.orEmpty()
            )
        }
    }

    fun encode(rows: List<ConversationBinding>): JSONArray = JSONArray().apply {
        rows.forEach { row ->
            put(JSONObject()
                .put("app", row.app)
                .put("title", row.title)
                .put("contactId", row.contactId)
                .put("remember", row.remember)
                .put("fingerprint", JSONArray(row.fingerprint))
                .put("titleAliases", JSONArray(row.titleAliases)))
        }
    }
}
