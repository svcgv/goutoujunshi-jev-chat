package com.jev.probe.coach

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class MemoryItem(
    val id: String,
    val scope: MemoryScope,
    val subjectId: String,
    val field: String,
    val value: String,
    val sourceType: String,
    val sourceRef: String,
    val occurredAt: String,
    val confidence: String,
    val updatedAt: Long
)

data class MemoryStatus(
    val enabled: Boolean,
    val paused: Boolean,
    val items: List<MemoryItem>,
    val undoCount: Int
)

data class MemoryApplyResult(
    val saved: Int,
    val rejected: Int,
    val reason: String = ""
)

/**
 * A deliberately small, auditable memory archive. It stores fields and short
 * event summaries, never a full transcript. All operations are synchronous and
 * atomic-file backed so failures can be surfaced to the user.
 */
class CoachMemoryStore internal constructor(
    private val root: File,
    private val report: (String) -> Unit = {}
) {
    private val lock = Any()
    private val file: File get() = File(root, "coach_memory.json")

    private data class State(
        var enabled: Boolean = false,
        var paused: Boolean = false,
        val items: MutableList<MemoryItem> = mutableListOf(),
        val undo: MutableList<List<MemoryItem>> = mutableListOf()
    )

    fun status(): MemoryStatus = synchronized(lock) {
        val s = load()
        MemoryStatus(s.enabled, s.paused, s.items.toList(), s.undo.size)
    }

    fun list(subjectId: String? = null): List<MemoryItem> = synchronized(lock) {
        load().items.filter { subjectId == null || it.subjectId == subjectId }
            .sortedWith(compareByDescending<MemoryItem> { it.scope == MemoryScope.EVENT }
                .thenByDescending { it.updatedAt })
    }

    fun enable(): Boolean = mutate { it.enabled = true; it.paused = false }

    fun pause(): Boolean = mutate { it.paused = true }

    fun resume(): Boolean = mutate { it.paused = false }

    /** Keeps existing data. [deleteExisting] is supplied by the explicit revoke UI. */
    fun revoke(deleteExisting: Boolean): Boolean = mutate {
        it.enabled = false
        it.paused = false
        if (deleteExisting) {
            it.items.clear()
            it.undo.clear()
        }
    }

    fun clear(): Boolean = mutate { it.items.clear(); it.undo.clear() }

    fun deleteSubject(subjectId: String): Boolean = mutate {
        it.items.removeAll { row -> row.subjectId == subjectId }
    }

    fun correct(id: String, value: String): Boolean = synchronized(lock) {
        val v = clean(value) ?: return@synchronized false
        val s = load()
        val index = s.items.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized false
        pushUndo(s)
        val old = s.items[index]
        s.items[index] = old.copy(value = v, updatedAt = System.currentTimeMillis(),
            sourceType = "user_correction", sourceRef = "settings")
        save(s)
    }

    /**
     * Applies only when consent is enabled and auto-update is not paused.
     * Invalid rows are rejected as a batch and never partially written.
     */
    fun apply(updates: List<MemoryUpdate>): MemoryApplyResult = synchronized(lock) {
        if (updates.isEmpty()) return@synchronized MemoryApplyResult(0, 0)
        val s = load()
        if (!s.enabled) return@synchronized MemoryApplyResult(0, updates.size, "尚未同意精简记忆")
        if (s.paused) return@synchronized MemoryApplyResult(0, updates.size, "精简记忆已暂停")
        val checked = updates.mapNotNull { normalize(it) }
        if (checked.size != updates.size) return@synchronized MemoryApplyResult(0, updates.size, "记忆字段无效")
        pushUndo(s)
        var saved = 0
        checked.forEach { update ->
            val existingIndex = if (update.scope == MemoryScope.EVENT) {
                s.items.indexOfFirst { it.scope == update.scope && it.subjectId == update.subjectId &&
                    it.field == update.field && it.occurredAt == update.occurredAt }
            } else {
                s.items.indexOfFirst { it.scope == update.scope && it.subjectId == update.subjectId &&
                    it.field == update.field }
            }
            val row = MemoryItem(
                id = if (existingIndex >= 0) s.items[existingIndex].id else newId(),
                scope = update.scope,
                subjectId = update.subjectId.trim(),
                field = update.field.trim().take(80),
                value = update.value.trim(),
                sourceType = update.sourceType.trim().ifBlank { "user_report" },
                sourceRef = update.sourceRef.trim().take(200),
                occurredAt = update.occurredAt.trim().take(32),
                confidence = update.confidence.trim().ifBlank { "medium" },
                updatedAt = System.currentTimeMillis()
            )
            if (existingIndex >= 0) s.items[existingIndex] = row else s.items.add(row)
            saved++
        }
        enforceCaps(s)
        if (!save(s)) return@synchronized MemoryApplyResult(0, updates.size, "写入失败，未保存")
        report("coachMemory saved=$saved total=${s.items.size}")
        MemoryApplyResult(saved, updates.size - saved)
    }

    fun undo(): Boolean = synchronized(lock) {
        val s = load()
        val previous = s.undo.removeLastOrNull() ?: return@synchronized false
        s.items.clear(); s.items.addAll(previous)
        save(s)
    }

    fun contextFor(subjectId: String?, maxChars: Int = 4000): String = synchronized(lock) {
        val s = load()
        if (!s.enabled) return@synchronized ""
        val relevant = s.items.filter {
            it.scope == MemoryScope.USER || (subjectId != null && it.subjectId == subjectId)
        }
        val ordered = relevant.sortedWith(compareBy<MemoryItem> {
            when (it.scope) { MemoryScope.USER -> 0; MemoryScope.OBJECT -> 1; MemoryScope.RELATIONSHIP -> 2;
                MemoryScope.EVENT -> 3; MemoryScope.HYPOTHESIS -> 4 }
        }.thenByDescending { it.updatedAt })
        val sb = StringBuilder("【精简记忆（经用户授权；事实与推断分开）】\n")
        for (row in ordered) {
            val line = "${scopeLabel(row.scope)} ${row.field}：${row.value}" +
                if (row.scope == MemoryScope.HYPOTHESIS) "（暂定，置信度 ${row.confidence}）" else ""
            if (sb.length + line.length + 1 > maxChars) break
            sb.append('•').append(line).append('\n')
        }
        if (sb.length < 40) "" else sb.toString()
    }

    private fun normalize(update: MemoryUpdate): MemoryUpdate? {
        val value = clean(update.value) ?: return null
        if (update.subjectId.isBlank() || update.field.isBlank()) return null
        return update.copy(subjectId = update.subjectId.trim().take(100), field = update.field.trim().take(80),
            value = value, sourceRef = update.sourceRef.trim().take(200), occurredAt = update.occurredAt.trim().take(32),
            confidence = update.confidence.trim().lowercase().take(12))
    }

    private fun clean(value: String): String? = value.replace('\u0000', ' ').trim().take(200)
        .takeIf { it.isNotBlank() }

    private fun enforceCaps(s: State) {
        val subjects = s.items.map { it.subjectId }.toSet()
        subjects.forEach { subject ->
            trimScope(s, subject, MemoryScope.EVENT, 20)
            trimScope(s, subject, MemoryScope.HYPOTHESIS, 5)
        }
        while (s.items.size > 200) {
            val index = s.items.indexOfFirst { it.scope == MemoryScope.HYPOTHESIS }
                .let { if (it >= 0) it else s.items.indices.minByOrNull { i -> s.items[i].updatedAt } ?: return }
            s.items.removeAt(index)
        }
    }

    private fun trimScope(s: State, subject: String, scope: MemoryScope, cap: Int) {
        while (s.items.count { it.subjectId == subject && it.scope == scope } > cap) {
            val oldest = s.items.filter { it.subjectId == subject && it.scope == scope }
                .minByOrNull { it.updatedAt } ?: return
            s.items.remove(oldest)
        }
    }

    private fun pushUndo(s: State) {
        s.undo.add(s.items.toList())
        while (s.undo.size > 20) s.undo.removeAt(0)
    }

    private fun mutate(block: (State) -> Unit): Boolean = synchronized(lock) {
        val s = load(); block(s); save(s)
    }

    private fun load(): State {
        if (!file.isFile) return State()
        return try {
            val o = JSONObject(file.readText())
            val items = o.optJSONArray("items")?.let { decodeItems(it) } ?: emptyList()
            val undo = o.optJSONArray("undo")?.let { stack ->
                (0 until stack.length()).mapNotNull { i -> stack.optJSONArray(i)?.let(::decodeItems) }
            } ?: emptyList()
            State(o.optBoolean("enabled", false), o.optBoolean("paused", false),
                items.toMutableList(), undo.toMutableList())
        } catch (e: Exception) {
            report("coachMemory load failed: ${e.javaClass.simpleName}")
            State()
        }
    }

    private fun save(s: State): Boolean = try {
        root.mkdirs()
        val o = JSONObject().put("version", 1).put("enabled", s.enabled).put("paused", s.paused)
            .put("items", encodeItems(s.items)).put("undo", JSONArray().apply {
                s.undo.forEach { put(encodeItems(it)) }
            })
        val tmp = File(root, "coach_memory.json.tmp")
        tmp.writeText(o.toString())
        if (file.exists() && !file.delete()) throw IllegalStateException("cannot replace memory")
        if (!tmp.renameTo(file)) throw IllegalStateException("cannot commit memory")
        true
    } catch (e: Exception) {
        report("coachMemory save failed: ${e.javaClass.simpleName}")
        false
    }

    private fun encodeItems(rows: List<MemoryItem>): JSONArray = JSONArray().apply {
        rows.forEach { r -> put(JSONObject().put("id", r.id).put("scope", r.scope.name)
            .put("subjectId", r.subjectId).put("field", r.field).put("value", r.value)
            .put("sourceType", r.sourceType).put("sourceRef", r.sourceRef)
            .put("occurredAt", r.occurredAt).put("confidence", r.confidence).put("updatedAt", r.updatedAt)) }
    }

    private fun decodeItems(a: JSONArray): List<MemoryItem> = (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        MemoryItem(o.optString("id"), MemoryScope.fromWire(o.optString("scope")),
            o.optString("subjectId"), o.optString("field"), o.optString("value"),
            o.optString("sourceType"), o.optString("sourceRef"), o.optString("occurredAt"),
            o.optString("confidence"), o.optLong("updatedAt"))
    }

    private fun scopeLabel(scope: MemoryScope): String = when (scope) {
        MemoryScope.USER -> "用户"
        MemoryScope.OBJECT -> "对象"
        MemoryScope.RELATIONSHIP -> "关系"
        MemoryScope.EVENT -> "事件"
        MemoryScope.HYPOTHESIS -> "暂定"
    }

    companion object {
        @Volatile private var instance: CoachMemoryStore? = null
        fun get(context: Context): CoachMemoryStore =
            instance ?: synchronized(this) {
                instance ?: CoachMemoryStore(File(context.applicationContext.filesDir, "coach"),
                    { message -> android.util.Log.i("JEVASSIST", message) }).also { instance = it }
            }
        internal fun newId(): String = "m" + System.currentTimeMillis().toString(36) + "-" +
            java.util.UUID.randomUUID().toString().take(6)
    }
}
