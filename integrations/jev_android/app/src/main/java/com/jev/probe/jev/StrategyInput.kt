package com.jev.probe.jev

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.kb.ChatContext
import org.json.JSONArray
import org.json.JSONObject

internal object StrategyInput {

    /**
     * @param prepared when present, its budgeted transcript text replaces the
     *        legacy per-message array and its history field, so a long message
     *        is never clipped to a fixed character count. When null the legacy
     *        array shape is used (kept for callers and tests that predate the
     *        budget work).
     */
    fun build(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext?, historyLimit: Int,
              prepared: ContextPreparer.Prepared? = null): JSONObject {
        val effectiveRelationship = ctx?.contact?.relationship?.takeIf { it.isNotBlank() } ?: relationship
        val result = JSONObject().put("relationship", effectiveRelationship)
        if (prepared != null) {
            result.put("transcript", prepared.text)
            prepared.notice()?.let { result.put("transcript_notice", it) }
            result.put("compressed", prepared.compressed)
        } else {
            result.put("transcript", JSONArray().also { array ->
                snapshot.messages.takeLast(30).forEach { message ->
                    array.put(JSONObject().put("speaker", message.side).put("text", message.text))
                }
            })
        }
        ctx?.background(effectiveRelationship)?.takeIf { it.isNotBlank() }?.let { result.put("background", it) }
        if (prepared == null) {
            val history = ctx?.history?.takeLast(historyLimit.coerceIn(0, 100)).orEmpty()
            if (history.isNotEmpty()) result.put("history", JSONArray().also { array ->
                history.forEach { message ->
                    array.put(JSONObject().put("speaker", message.side).put("text", message.text))
                }
            })
        }
        return result
    }
}
