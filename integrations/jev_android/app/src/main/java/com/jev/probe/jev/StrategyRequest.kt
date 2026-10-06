package com.jev.probe.jev

import org.json.JSONArray
import org.json.JSONObject

/** Minimal proxy payload: no provider-only flags or assumed GPT capabilities. */
internal object StrategyRequest {
    fun body(model: String, system: String, user: String, officialDeepSeek: Boolean,
             json: Boolean = false, choice: Boolean = false): JSONObject {
        require(model.isNotBlank()) { "请填写代理实际提供的策略模型 ID" }
        val body = JSONObject().put("model", model.trim())
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
            .put("stream", false)
        // Preserve the existing official route; do not send these to a proxy.
        if (officialDeepSeek) {
            body.put("temperature", if (choice) 1 else 0.6)
                .put("max_tokens", if (choice) 8 else 900)
                .put("thinking", JSONObject().put("type", "disabled"))
            if (json) body.put("response_format", JSONObject().put("type", "json_object"))
            if (choice) body.put("logprobs", true).put("top_logprobs", 20)
        }
        return body
    }
}
