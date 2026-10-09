package com.jev.probe.core

import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** Versioned JSON snapshot. The old route keys are migration input only. */
class ModelConfigStore(private val sp: SharedPreferences) {

    fun hasStored(): Boolean = sp.contains(KEY_CONFIG) && sp.getString(KEY_CONFIG, "").orEmpty().isNotBlank()

    fun load(): ModelConfigSnapshot {
        val raw = sp.getString(KEY_CONFIG, "").orEmpty()
        if (raw.isBlank()) return ModelConfigSnapshot()
        return try {
            decode(JSONObject(raw)).also { it.validate() }
        } catch (_: Exception) {
            Log.w(TAG, "model config ignored")
            ModelConfigSnapshot()
        }
    }

    fun save(snapshot: ModelConfigSnapshot): Boolean = try {
        snapshot.validate()
        sp.edit().putString(KEY_CONFIG, encode(snapshot).toString()).commit()
    } catch (_: Exception) {
        Log.w(TAG, "model config save rejected")
        false
    }

    companion object {
        const val KEY_CONFIG = "model_config_v1"
        private const val TAG = "JEVASSIST"

        fun encode(snapshot: ModelConfigSnapshot): JSONObject {
            val services = JSONArray().apply {
                snapshot.services.forEach { service ->
                    put(JSONObject()
                        .put("id", service.id)
                        .put("name", service.name)
                        .put("kind", service.kind.wire)
                        .put("base_url", service.baseUrl)
                        .put("key", service.key))
                }
            }
            val models = JSONArray().apply {
                snapshot.models.forEach { model ->
                    put(JSONObject()
                        .put("id", model.id)
                        .put("service_id", model.serviceId)
                        .put("model_id", model.modelId)
                        .put("display_name", model.displayName)
                        .put("protocol", model.protocol.wire)
                        .put("vision", model.vision))
                }
            }
            return JSONObject()
                .put("version", snapshot.version)
                .put("services", services)
                .put("models", models)
                .put("bindings", JSONObject()
                    .put("judge", snapshot.bindings.judgeModelId)
                    .put("reply", snapshot.bindings.replyModelId)
                    .put("vision", snapshot.bindings.visionModelId))
                .put("overrides", JSONObject()
                    .put("open", snapshot.overrides.openModelId)
                    .put("end", snapshot.overrides.endModelId)
                    .put("consult", snapshot.overrides.consultModelId))
        }

        fun decode(root: JSONObject): ModelConfigSnapshot {
            val services = root.optJSONArray("services")?.let { rows ->
                (0 until rows.length()).map { index ->
                    val row = rows.getJSONObject(index)
                    ServiceConfig(
                        id = row.getString("id"),
                        name = row.optString("name").trim(),
                        kind = ServiceKind.fromWire(row.optString("kind"))
                            ?: throw IllegalArgumentException("未知服务类型"),
                        baseUrl = row.optString("base_url"),
                        key = row.optString("key")
                    )
                }
            } ?: emptyList()
            val models = root.optJSONArray("models")?.let { rows ->
                (0 until rows.length()).map { index ->
                    val row = rows.getJSONObject(index)
                    ModelConfig(
                        id = row.getString("id"),
                        serviceId = row.getString("service_id"),
                        modelId = row.optString("model_id").trim(),
                        displayName = row.optString("display_name").trim(),
                        protocol = ModelProtocol.fromWire(row.optString("protocol"))
                            ?: throw IllegalArgumentException("未知模型协议"),
                        vision = row.optBoolean("vision", false)
                    )
                }
            } ?: emptyList()
            val bindings = root.optJSONObject("bindings") ?: JSONObject()
            val overrides = root.optJSONObject("overrides") ?: JSONObject()
            return ModelConfigSnapshot(
                version = root.optInt("version", ModelConfigSnapshot.VERSION),
                services = services,
                models = models,
                bindings = InterfaceBindings(
                    judgeModelId = bindings.optString("judge"),
                    replyModelId = bindings.optString("reply"),
                    visionModelId = bindings.optString("vision")
                ),
                overrides = FeatureModelOverrides(
                    openModelId = overrides.optString("open"),
                    endModelId = overrides.optString("end"),
                    consultModelId = overrides.optString("consult")
                )
            )
        }
    }
}
