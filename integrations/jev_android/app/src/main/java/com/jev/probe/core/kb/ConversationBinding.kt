package com.jev.probe.core.kb

import org.json.JSONArray
import org.json.JSONObject

/** Explicitly confirmed window identity. Titles are exact, not fuzzy aliases. */
data class ConversationBinding(val app: String, val title: String, val contactId: String,
                               val remember: Boolean = false)

internal object ConversationBindings {
    fun resolve(rows: List<ConversationBinding>, app: String, title: String?): ConversationBinding? {
        if (app.isBlank() || title.isNullOrBlank()) return null
        return rows.filter { it.app == app && it.title == title.trim() }.singleOrNull()
    }

    fun decode(raw: String): List<ConversationBinding> {
        val rows = JSONArray(raw)
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            ConversationBinding(row.getString("app"), row.getString("title"),
                row.getString("contactId"), row.optBoolean("remember", false))
        }
    }

    fun encode(rows: List<ConversationBinding>): JSONArray = JSONArray().apply {
        rows.forEach { put(JSONObject().put("app", it.app).put("title", it.title)
            .put("contactId", it.contactId).put("remember", it.remember)) }
    }
}
