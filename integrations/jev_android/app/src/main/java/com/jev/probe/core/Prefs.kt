package com.jev.probe.core

import android.content.Context
import com.jev.probe.coach.CoachTask

/**
 * App-private config store. Model routes live in one versioned snapshot managed
 * by [ModelConfigStore]; the old route keys are read only once for migration.
 * Chat text, keys and other private data are never logged.
 */
class Prefs(context: Context, prefsName: String = PREFS_MAIN) {

    private val sp = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val modelStore = ModelConfigStore(sp)

    init { if (prefsName == PREFS_MAIN) migrateIfNeeded() }

    /** v1.2 -> v1.3 and the single model snapshot migration. */
    private fun migrateIfNeeded() {
        if (!sp.getBoolean(K_MIGRATED_V13, false)) {
            val legacy = sp.getString(K_LEGACY_KEY, "").orEmpty()
            val current = sp.getString(K_JUDGE_KEY, "").orEmpty()
            val edit = sp.edit().putBoolean(K_MIGRATED_V13, true)
            if (current.isBlank() && legacy.isNotBlank()) edit.putString(K_JUDGE_KEY, legacy)
            edit.commit()
        }
        if (sp.getBoolean(K_MIGRATED_CONFIG_V1, false)) return
        val stored = modelStore.load()
        if (!modelStore.hasStored() || (stored.services.isEmpty() && stored.models.isEmpty())) {
            val legacy = readLegacy()
            if (legacy.configured) {
                val snapshot = ConfigMigration.migrate(legacy)
                if (!modelStore.save(snapshot)) return
            }
        }
        sp.edit().putBoolean(K_MIGRATED_CONFIG_V1, true).commit()
    }

    // ------------------------------------------------------------ model config

    fun modelSnapshot(): ModelConfigSnapshot = modelStore.load()

    fun saveModelSnapshot(snapshot: ModelConfigSnapshot): Boolean {
        val saved = modelStore.save(snapshot)
        if (saved) sp.edit().putBoolean(K_MIGRATED_CONFIG_V1, true).apply()
        return saved
    }

    fun judgeRoute(): ResolvedRoute? = modelSnapshot().judgeRoute()

    fun replyRoute(): ResolvedRoute? = modelSnapshot().replyRoute()

    fun visionRoute(): ResolvedRoute? = modelSnapshot().visionRoute()

    fun featureRoute(task: CoachTask): ResolvedRoute? = modelSnapshot().featureRoute(task)

    /** Readiness gate: a complete judge route is required before analysis. */
    fun hasKey(): Boolean = judgeRoute()?.ready == true

    fun hasVision(): Boolean = visionRoute()?.ready == true

    // -------------------------------------------------------- context (D)

    var contextEnabled: Boolean
        get() = sp.getBoolean(K_CTX_ENABLED, false)
        set(v) = sp.edit().putBoolean(K_CTX_ENABLED, v).apply()

    /** Title areas selected by dragging over a screenshot, keyed by package. */
    var titleRegions: String
        get() = sp.getString(K_TITLE_REGION, "") ?: ""
        set(v) = sp.edit().putString(K_TITLE_REGION, v.trim()).apply()

    var skillKnowledgeEnabled: Boolean
        get() = sp.getBoolean(K_SKILL_KB, true)
        set(v) = sp.edit().putBoolean(K_SKILL_KB, v).apply()

    var contextHistoryCount: Int
        get() = sp.getInt(K_CTX_COUNT, 30)
        set(v) = sp.edit().putInt(K_CTX_COUNT, v).apply()

    var modelContextWindow: Int
        get() = sp.getInt(K_CTX_WINDOW, 8192)
        set(v) = sp.edit().putInt(K_CTX_WINDOW, v.coerceIn(1024, 1_000_000)).apply()

    internal fun tokenBudget(): com.jev.probe.jev.TokenBudget =
        com.jev.probe.jev.TokenBudget(contextWindow = modelContextWindow.coerceIn(1024, 1_000_000))

    var autoSummary: Boolean
        get() = sp.getBoolean(K_AUTO_SUMMARY, true)
        set(v) = sp.edit().putBoolean(K_AUTO_SUMMARY, v).apply()

    // ------------------------------------------------------------ OCR (B)

    /** "mlkit" | "vision". */
    var ocrEngine: String
        get() = sp.getString(K_OCR_ENGINE, OCR_MLKIT) ?: OCR_MLKIT
        set(v) = sp.edit().putString(K_OCR_ENGINE, v.trim()).apply()

    var ocrForUnknownApps: Boolean
        get() = sp.getBoolean(K_OCR_UNKNOWN, true)
        set(v) = sp.edit().putBoolean(K_OCR_UNKNOWN, v).apply()

    var ocrFallback: Boolean
        get() = sp.getBoolean(K_OCR_FALLBACK, true)
        set(v) = sp.edit().putBoolean(K_OCR_FALLBACK, v).apply()

    var ocrAutoAnalyze: Boolean
        get() = sp.getBoolean(K_OCR_AUTO, false)
        set(v) = sp.edit().putBoolean(K_OCR_AUTO, v).apply()

    // ------------------------------------------------------------- existing

    var relationship: String
        get() = sp.getString(K_REL, DEFAULT_REL) ?: DEFAULT_REL
        set(v) = sp.edit().putString(K_REL, v).apply()

    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, false)
        set(v) = sp.edit().putBoolean(K_ENABLED, v).apply()

    fun observeEnabled(onChanged: (Boolean) -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == K_ENABLED) onChanged(enabled)
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        return { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    var whitelist: Set<String>
        get() = sp.getStringSet(K_WHITELIST, emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet(K_WHITELIST, v.filter { it.isNotBlank() }.toSet()).apply()

    var overlayOpacity: Int
        get() = sp.getInt(K_OPACITY, 92)
        set(v) = sp.edit().putInt(K_OPACITY, v.coerceIn(60, 100)).apply()

    var bubbleY: Int
        get() = sp.getInt(K_BUBBLE_Y, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_Y, v).apply()

    var bubbleX: Int
        get() = sp.getInt(K_BUBBLE_X, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_X, v).apply()

    var autoAnalyze: Boolean
        get() = sp.getBoolean(K_AUTO, false)
        set(v) = sp.edit().putBoolean(K_AUTO, v).apply()

    fun isAllowed(title: String?): Boolean {
        val list = whitelist
        if (list.isEmpty()) return true
        if (title == null) return false
        return list.any { title.contains(it) }
    }

    private fun readLegacy(): LegacyConfig {
        fun has(vararg keys: String) = keys.any { sp.contains(it) }
        val strategyDefault = if (sp.contains(K_JUDGE_PROVIDER) || sp.contains(K_JUDGE_KEY)) "jev"
            else STRATEGY_COMPATIBLE
        val strategyProvider = sp.getString(K_STRATEGY_PROVIDER, strategyDefault) ?: strategyDefault
        return LegacyConfig(
            hasJudge = has(K_LEGACY_KEY, K_JUDGE_PROVIDER, K_JUDGE_BASE, K_JUDGE_KEY, K_JUDGE_MODEL),
            judgeProvider = sp.getString(K_JUDGE_PROVIDER, PROVIDER_OPENROUTER) ?: PROVIDER_OPENROUTER,
            judgeBaseUrl = sp.getString(K_JUDGE_BASE, DEFAULT_JUDGE_BASE_OPENROUTER)
                ?: DEFAULT_JUDGE_BASE_OPENROUTER,
            judgeKey = sp.getString(K_JUDGE_KEY, "") ?: "",
            judgeModel = sp.getString(K_JUDGE_MODEL, DEFAULT_JUDGE_MODEL_OPENROUTER)
                ?: DEFAULT_JUDGE_MODEL_OPENROUTER,
            hasStrategy = has(K_STRATEGY_PROVIDER, K_STRATEGY_BASE, K_STRATEGY_KEY, K_STRATEGY_MODEL),
            strategyProvider = strategyProvider,
            strategyBaseUrl = sp.getString(K_STRATEGY_BASE, DEFAULT_PROXY_BASE) ?: DEFAULT_PROXY_BASE,
            strategyKey = sp.getString(K_STRATEGY_KEY, "") ?: "",
            strategyModel = sp.getString(K_STRATEGY_MODEL,
                if (strategyProvider == STRATEGY_COMPATIBLE) "" else DEEPSEEK_MODEL) ?: "",
            hasReply = has(K_REPLY_BASE, K_REPLY_KEY, K_REPLY_MODEL),
            replyBaseUrl = sp.getString(K_REPLY_BASE, DEFAULT_REPLY_BASE) ?: DEFAULT_REPLY_BASE,
            replyKey = sp.getString(K_REPLY_KEY, "") ?: "",
            replyModel = sp.getString(K_REPLY_MODEL, DEFAULT_REPLY_MODEL) ?: DEFAULT_REPLY_MODEL,
            hasVision = has(K_VISION_BASE, K_VISION_KEY, K_VISION_MODEL),
            visionBaseUrl = sp.getString(K_VISION_BASE, DEFAULT_VISION_BASE) ?: DEFAULT_VISION_BASE,
            visionKey = sp.getString(K_VISION_KEY, "") ?: "",
            visionModel = sp.getString(K_VISION_MODEL, DEFAULT_VISION_MODEL) ?: DEFAULT_VISION_MODEL
        )
    }

    companion object {
        const val PREFS_MAIN = "jev_assistant"

        private const val K_LEGACY_KEY = "openrouter_key"
        private const val K_MIGRATED_V13 = "prefs_migrated_v13"
        private const val K_MIGRATED_CONFIG_V1 = "prefs_migrated_model_config_v1"
        private const val K_JUDGE_PROVIDER = "judge_provider"
        private const val K_JUDGE_BASE = "judge_base_url"
        private const val K_JUDGE_KEY = "judge_key"
        private const val K_JUDGE_MODEL = "judge_model"
        private const val K_STRATEGY_PROVIDER = "strategy_provider"
        private const val K_STRATEGY_BASE = "strategy_base_url"
        private const val K_STRATEGY_MODEL = "strategy_model"
        private const val K_STRATEGY_KEY = "strategy_key"
        private const val K_REPLY_BASE = "reply_base_url"
        private const val K_REPLY_KEY = "reply_key"
        private const val K_REPLY_MODEL = "reply_model"
        private const val K_VISION_BASE = "vision_base_url"
        private const val K_VISION_KEY = "vision_key"
        private const val K_VISION_MODEL = "vision_model"
        private const val K_CTX_ENABLED = "context_enabled"
        private const val K_SKILL_KB = "skill_knowledge_enabled"
        private const val K_TITLE_REGION = "title_region"
        private const val K_CTX_COUNT = "context_history_count"
        private const val K_CTX_WINDOW = "model_context_window"
        private const val K_AUTO_SUMMARY = "auto_summary"
        private const val K_OCR_ENGINE = "ocr_engine"
        private const val K_OCR_UNKNOWN = "ocr_unknown_apps"
        private const val K_OCR_FALLBACK = "ocr_fallback"
        private const val K_OCR_AUTO = "ocr_auto_analyze"
        private const val K_REL = "relationship"
        private const val K_ENABLED = "enabled"
        private const val K_WHITELIST = "whitelist"
        private const val K_OPACITY = "overlay_opacity"
        private const val K_BUBBLE_Y = "bubble_y"
        private const val K_BUBBLE_X = "bubble_x"
        private const val K_AUTO = "auto_analyze"

        const val STRATEGY_COMPATIBLE = "compatible"
        const val DEFAULT_PROXY_BASE = "http://127.0.0.1:8317/v1"

        const val PROVIDER_OPENROUTER = "openrouter"
        const val PROVIDER_TYPESAFE = "typesafe"
        const val PROVIDER_CUSTOM = "custom"

        const val OCR_MLKIT = "mlkit"
        const val OCR_VISION = "vision"

        const val DEFAULT_JUDGE_BASE_OPENROUTER = "https://openrouter.ai/api"
        const val DEFAULT_JUDGE_MODEL_OPENROUTER = "typesafe/jev-1.13"
        const val DEFAULT_JUDGE_BASE_TYPESAFE = "https://api.typesafe.ai"
        const val DEFAULT_JUDGE_MODEL_TYPESAFE = "jev-latest"

        const val DEFAULT_REPLY_BASE = "https://openrouter.ai/api/v1"
        const val DEFAULT_REPLY_MODEL = "deepseek/deepseek-chat-v3.1"
        const val DEEPSEEK_BASE = "https://api.deepseek.com/v1"
        const val DEEPSEEK_MODEL = "deepseek-flash"
        const val DASHSCOPE_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        const val DASHSCOPE_MODEL = "qwen-plus"

        const val DEFAULT_VISION_BASE = "https://openrouter.ai/api/v1"
        const val DEFAULT_VISION_MODEL = "qwen/qwen2.5-vl-72b-instruct"
        const val DASHSCOPE_VISION_MODEL = "qwen-vl-max"

        const val DEFAULT_REL = "对方是我的伴侣；from=me 的是我发的，from=other 的是对方发的"
    }
}
