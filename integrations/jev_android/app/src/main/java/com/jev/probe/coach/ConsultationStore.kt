package com.jev.probe.coach

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ConsultationSession(
    val id: String,
    val contactId: String?,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val turns: List<StoredTurn>
)

data class StoredTurn(
    val user: String,
    val assistant: String,
    val task: CoachTask,
    val at: Long
)

/** Opt-in transcript storage. Defaults OFF and is independent from distilled memory. */
class ConsultationStore internal constructor(
    private val root: File,
    private val report: (String) -> Unit = {}
) {
    private val lock = Any()
    private val file: File get() = File(root, "consultations.json")

    fun enabled(): Boolean = synchronized(lock) { load().first }
    fun setEnabled(value: Boolean): Boolean = synchronized(lock) {
        val (_, sessions) = load()
        save(value, sessions)
    }

    fun list(): List<ConsultationSession> = synchronized(lock) { load().second }
        .sortedByDescending { it.updatedAt }

    fun get(id: String): ConsultationSession? = synchronized(lock) {
        load().second.firstOrNull { it.id == id }
    }

    fun saveTurn(sessionId: String, contactId: String?, title: String, turn: StoredTurn): Boolean =
        synchronized(lock) {
            val (enabled, sessions) = load()
            if (!enabled) return@synchronized true
            val existing = sessions.firstOrNull { it.id == sessionId }
            val next = if (existing != null) {
                existing.copy(updatedAt = turn.at, contactId = contactId ?: existing.contactId,
                    title = title.ifBlank { existing.title }, turns = (existing.turns + turn).takeLast(100))
            } else ConsultationSession(sessionId, contactId, title, turn.at, turn.at, listOf(turn))
            val rows = (sessions.filterNot { it.id == sessionId } + next).takeLast(50)
            save(true, rows)
        }

    fun create(): ConsultationSession = ConsultationSession(
        id = "c" + System.currentTimeMillis().toString(36) + "-" + java.util.UUID.randomUUID().toString().take(6),
        contactId = null, title = "未命名咨询", createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(), turns = emptyList())

    fun delete(id: String): Boolean = synchronized(lock) {
        val (enabled, sessions) = load()
        save(enabled, sessions.filterNot { it.id == id })
    }

    fun clear(): Boolean = synchronized(lock) { save(load().first, emptyList()) }

    private fun load(): Pair<Boolean, List<ConsultationSession>> {
        if (!file.isFile) return false to emptyList()
        return try {
            val o = JSONObject(file.readText())
            val rows = o.optJSONArray("sessions")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::decode) }
            } ?: emptyList()
            o.optBoolean("enabled", false) to rows
        } catch (e: Exception) {
            report("consultations load failed: ${e.javaClass.simpleName}")
            false to emptyList()
        }
    }

    private fun save(enabled: Boolean, sessions: List<ConsultationSession>): Boolean = try {
        root.mkdirs()
        val o = JSONObject().put("version", 1).put("enabled", enabled).put("sessions", JSONArray().apply {
            sessions.forEach { put(encode(it)) }
        })
        val tmp = File(root, "consultations.json.tmp")
        tmp.writeText(o.toString())
        if (file.exists() && !file.delete()) throw IllegalStateException("cannot replace transcript")
        if (!tmp.renameTo(file)) throw IllegalStateException("cannot commit transcript")
        true
    } catch (e: Exception) {
        report("consultations save failed: ${e.javaClass.simpleName}")
        false
    }

    private fun encode(s: ConsultationSession) = JSONObject().put("id", s.id)
        .put("contactId", s.contactId ?: JSONObject.NULL).put("title", s.title)
        .put("createdAt", s.createdAt).put("updatedAt", s.updatedAt).put("turns", JSONArray().apply {
            s.turns.forEach { t -> put(JSONObject().put("user", t.user).put("assistant", t.assistant)
                .put("task", t.task.wire).put("at", t.at)) }
        })

    private fun decode(o: JSONObject): ConsultationSession = ConsultationSession(
        id = o.optString("id"),
        contactId = if (o.isNull("contactId")) null else o.optString("contactId"),
        title = o.optString("title"),
        createdAt = o.optLong("createdAt"),
        updatedAt = o.optLong("updatedAt"),
        turns = o.optJSONArray("turns")?.let { a ->
            (0 until a.length()).map { i -> val t = a.optJSONObject(i)
                StoredTurn(t.optString("user"), t.optString("assistant"),
                    CoachTask.fromWire(t.optString("task")), t.optLong("at")) }
        } ?: emptyList())

    companion object {
        @Volatile private var instance: ConsultationStore? = null
        fun get(context: Context): ConsultationStore = instance ?: synchronized(this) {
            instance ?: ConsultationStore(File(context.applicationContext.filesDir, "coach"),
                { message -> android.util.Log.i("JEVASSIST", message) }).also { instance = it }
        }
    }
}
