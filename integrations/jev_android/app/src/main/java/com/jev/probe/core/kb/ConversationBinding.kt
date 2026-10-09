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
 */
data class ConversationBinding(
    val app: String,
    val title: String,
    val contactId: String,
    val remember: Boolean = false,
    val fingerprint: List<String> = emptyList()
)

internal object ConversationBindings {

    /** How many recent messages are kept as the conversation's fingerprint. */
    const val FINGERPRINT_SIZE = 8

    /** Minimum overlapping messages required to accept a fingerprint match. */
    const val MIN_OVERLAP = 2

    fun resolve(rows: List<ConversationBinding>, app: String, title: String?): ConversationBinding? {
        if (app.isBlank() || title.isNullOrBlank()) return null
        return rows.filter { it.app == app && it.title == title.trim() }.singleOrNull()
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
                .put("fingerprint", JSONArray(row.fingerprint)))
        }
    }
}
