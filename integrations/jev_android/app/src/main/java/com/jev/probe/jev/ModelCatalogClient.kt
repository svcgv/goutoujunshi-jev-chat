package com.jev.probe.jev

import com.jev.probe.core.ServiceConfig
import org.json.JSONArray
import org.json.JSONObject

/** Standard OpenAI-compatible model discovery; no credentials cross origins. */
object ModelCatalogClient {

    data class Entry(val id: String, val vision: Boolean? = null)

    fun fetch(service: ServiceConfig): List<Entry> {
        val endpoint = service.modelsEndpoint()
            ?: throw IllegalArgumentException("${service.kind.label} 不支持拉取模型列表，请手动添加")
        require(service.key.isNotBlank()) { "请先填写服务密钥" }
        val response = HttpJson.get(endpoint, service.key, "模型列表", HttpJson.headersFor(endpoint))
        return parseEntries(response)
    }

    fun parseEntries(root: JSONObject): List<Entry> {
        val rows = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
        val seen = HashSet<String>()
        val result = ArrayList<Entry>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optString("id").trim().ifBlank { row.optString("name").trim() }
            if (id.isBlank() || !seen.add(id)) continue
            result.add(Entry(id, visionCapability(row)))
        }
        return result
    }

    /** Only explicit metadata is treated as evidence; unknown stays null. */
    private fun visionCapability(row: JSONObject): Boolean? {
        row.optJSONObject("capabilities")?.let { caps ->
            if (caps.has("vision")) return caps.optBoolean("vision")
        }
        val modalities = row.optJSONArray("input_modalities")
            ?: row.optJSONArray("modalities")
            ?: row.optJSONObject("architecture")?.optJSONArray("input_modalities")
        if (modalities != null) {
            val values = (0 until modalities.length()).mapNotNull { index ->
                modalities.optString(index).trim().lowercase().takeIf { it.isNotBlank() }
            }
            if (values.isNotEmpty()) return values.any { it == "image" || it == "vision" }
        }
        return null
    }
}
