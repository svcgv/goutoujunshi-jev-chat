package com.jev.probe.core.skill

import android.content.Context

/**
 * The bundled Goutoujunshi knowledge base: a curated subset of the skill's
 * reference documents, shipped in `assets/skill` and searched on-device.
 *
 * Only decision-relevant topics are bundled. Retrieval is deliberately simple
 * (no embeddings, no network): score sections by keyword hits and return a few
 * short excerpts so the injected text stays small.
 */
class SkillLibrary internal constructor(
    private val documents: List<Pair<String, String>>
) {

    internal data class Section(
        val file: String,
        val title: String,
        val body: String,
        val keywords: Set<String>
    )

    private val sections: List<Section> by lazy {
        documents.flatMap { (name, raw) -> splitSections(name, raw) }
    }

    fun isEmpty(): Boolean = sections.isEmpty()

    /**
     * The most relevant excerpts for this conversation, best first.
     *
     * @param query free text: relationship, transcript, user notes.
     * @param limit maximum number of excerpts to return.
     * @param budget maximum total characters across all returned excerpts.
     */
    fun search(query: String, limit: Int = 2, budget: Int = 1200): List<String> {
        if (sections.isEmpty()) return emptyList()
        val haystack = query.lowercase()
        if (haystack.isBlank()) return emptyList()
        val scored = sections.mapNotNull { section ->
            var score = 0
            for (kw in section.keywords) if (haystack.contains(kw)) score += kw.length
            if (score > 0) section to score else null
        }.sortedByDescending { it.second }
        if (scored.isEmpty()) return emptyList()

        val out = ArrayList<String>()
        var used = 0
        val seenFiles = HashSet<String>()
        for ((section, _) in scored) {
            if (out.size >= limit) break
            // At most one excerpt per document keeps the injected advice varied.
            if (!seenFiles.add(section.file)) continue
            val text = format(section)
            if (used + text.length > budget && out.isNotEmpty()) continue
            out.add(text)
            used += text.length
        }
        return out
    }

    private fun format(section: Section): String =
        "[军师知识·${section.title}]\n" + section.body.take(600).trim()

    /** Split a Markdown document on `##` headings; drop the front-matter. */
    internal fun splitSections(file: String, raw: String): List<Section> {
        val chunks = ArrayList<Section>()
        var title = file.removeSuffix(".md")
        val body = StringBuilder()
        fun flush() {
            val text = body.toString().trim()
            if (text.length >= 40) chunks.add(Section(file, title, text, KEYWORDS_BY_FILE[file].orEmpty()))
            body.setLength(0)
        }
        raw.lineSequence().forEach { line ->
            if (line.startsWith("## ")) {
                flush()
                title = line.removePrefix("## ").trim()
            } else if (!line.startsWith("# ")) {
                body.append(line).append('\n')
            }
        }
        flush()
        return chunks
    }

    /**
     * A section matches when the conversation mentions a term belonging to the
     * section's own topic. Keywords are fixed per document (not guessed from
     * prose) so a passing mention of, say, a threat inside a social-skills
     * document cannot make it answer a safety question.
     */
    internal fun keywordsFor(file: String): Set<String> = KEYWORDS_BY_FILE[file].orEmpty()

    companion object {
        /** Files bundled into the APK, in `assets/skill`. */
        val FILES = listOf(
            "reply_craft.md",
            "vibe_calibration.md",
            "online_dating.md",
            "investment_imbalance.md",
            "attachment_emotion.md",
            "conflict_repair.md",
            "consent_boundary.md",
            "safety_crisis.md",
            "manipulation_boundary.md",
            "first_meeting.md",
            "emotion_support.md",
            "social_systems.md"
        )

        /** Topic keywords per bundled document. */
        private val KEYWORDS_BY_FILE: Map<String, Set<String>> = mapOf(
            "reply_craft.md" to setOf("怎么回", "回复", "一句话", "开场", "话术", "接话", "怎么说", "措辞"),
            "vibe_calibration.md" to setOf("松弛", "轻松", "调侃", "玩笑", "调情", "气氛", "尴尬", "冷场", "接话"),
            "online_dating.md" to setOf("网聊", "微信", "截图", "朋友圈", "已读", "在线", "诈骗", "资料页", "聊天记录"),
            "investment_imbalance.md" to setOf("冷淡", "敷衍", "投入", "付出", "回应", "主动", "退出", "放弃", "失衡", "单向", "备胎"),
            "attachment_emotion.md" to setOf("焦虑", "不安", "粘", "回避", "情绪", "难过", "在意", "依恋", "纠结"),
            "conflict_repair.md" to setOf("吵架", "冲突", "矛盾", "生气", "道歉", "修复", "误会", "冷战", "翻旧账"),
            "consent_boundary.md" to setOf("边界", "拒绝", "同意", "不舒服", "亲密", "肢体", "停止联系", "尊重"),
            "safety_crisis.md" to setOf("威胁", "跟踪", "家暴", "强迫", "勒索", "危险", "报警", "法律", "安全", "自杀", "暴力", "骚扰"),
            "manipulation_boundary.md" to setOf("PUA", "操控", "冷读", "推拉", "服从", "煤气灯", "贬低", "打压", "服从性测试"),
            "first_meeting.md" to setOf("邀约", "约见", "见面", "出来", "有空", "约会", "第一次", "线下", "自然接触"),
            "emotion_support.md" to setOf("情绪价值", "安慰", "共情", "倾诉", "难受", "委屈"),
            "social_systems.md" to setOf("自然流", "结构化", "内在状态", "体系", "冷读", "Blueprint", "Mystery")
        )

        @Volatile private var instance: SkillLibrary? = null

        fun get(context: Context): SkillLibrary =
            instance ?: synchronized(this) {
                instance ?: SkillLibrary(loadBundled(context)).also { instance = it }
            }

        /** Read the bundled documents from the APK's assets. Missing files are skipped. */
        fun loadBundled(context: Context): List<Pair<String, String>> {
            val assets = context.applicationContext.assets
            return FILES.mapNotNull { name ->
                val text = try {
                    assets.open("skill/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
                } catch (_: Exception) {
                    return@mapNotNull null
                }
                name to text
            }
        }
    }
}
