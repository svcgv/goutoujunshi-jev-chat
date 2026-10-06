package com.jev.probe.jev

import org.json.JSONObject

internal object StrategyCompletion {
    fun request(endpoint: String, key: String, body: JSONObject, choice: Boolean = false): String {
        val response = HttpJson.post(endpoint, key, body, Route.JUDGE, HttpJson.headersFor(endpoint))
        return content(response, choice)
    }

    fun content(response: JSONObject, choice: Boolean = false): String {
        val first = response.getJSONArray("choices").getJSONObject(0)
        require(first.optString("finish_reason", "stop") == "stop") { "策略模型输出不完整" }
        val content = first.getJSONObject("message").getString("content")
        require(content.isNotBlank()) { "策略模型返回空内容" }
        return if (choice) JSONObject().put("text", content)
            .put("logprobs", first.optJSONObject("logprobs")).toString() else content
    }
}
