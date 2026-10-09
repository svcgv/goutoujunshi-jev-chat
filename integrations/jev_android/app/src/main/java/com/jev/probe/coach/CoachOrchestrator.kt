package com.jev.probe.coach

import android.content.Context
import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Choice
import com.jev.probe.core.GoutouGuidance
import com.jev.probe.core.Prefs
import com.jev.probe.core.kb.ChatContext
import com.jev.probe.core.kb.KbStore
import com.jev.probe.core.WorkScope
import com.jev.probe.core.WorkToken
import com.jev.probe.jev.JevClient

/**
 * The single application-level coach entry point. It composes the existing
 * independent strategy and reply routes, then applies only the memory updates
 * the user has explicitly allowed. Analysis and old reply APIs remain intact.
 */
class CoachOrchestrator(
    context: Context,
    private val prefs: Prefs = Prefs(context)
) {
    private val appContext = context.applicationContext
    private val kb = KbStore.get(appContext)
    private val memory = CoachMemoryStore.get(appContext)
    private val transcripts = ConsultationStore.get(appContext)

    fun prepare(request: CoachRequest): CoachRequest {
        val contactId = request.contactId
        val contact = contactId?.let { kb.contact(it) }
        val history = if (contact != null && prefs.contextEnabled) {
            val binding = kb.bindings().firstOrNull { it.contactId == contact.id }
            if (binding?.remember == true) kb.recentLog(contact.id, prefs.contextHistoryCount)
            else emptyList()
        } else emptyList()
        val ctx = ChatContext(contact, history, emptyList())
        val background = listOf(request.background, contact?.let { ctx.background(request.relationship) }.orEmpty())
            .filter { it.isNotBlank() }.joinToString("\n")
        return request.copy(
            relationship = request.relationship.ifBlank { contact?.relationship.orEmpty() },
            background = background,
            memoryContext = memory.contextFor(contactId)
        )
    }

    fun decide(request: CoachRequest): CoachDecision {
        val prepared = prepare(request)
        val snapshot = prepared.snapshot ?: ChatSnapshot(prepared.relationship, emptyList())
        val boundary = CoachSafety.standingBoundary(snapshot, prepared.memoryContext)
        val safety = CoachSafety.safetySignal(snapshot, prepared.userGoal, prepared.memoryContext)
        if (boundary) return CoachDecision(
            strategy = if (prepared.task == CoachTask.CONSULT) "收线" else "降压",
            boundary = true, safety = safety,
            facts = listOf("聊天或记忆中已有明确停止联系的要求"),
            unknowns = listOf("对方是否愿意在安全边界内继续沟通"))
        if (!prefs.usesChatStrategy()) {
            return CoachDecision(
                strategy = "由回复模型统一判断",
                facts = prepared.snapshot?.messages?.takeLast(3)?.map { it.text }.orEmpty(),
                unknowns = listOf("旧 Jev 路线不提供本轮结构化判断；回复模型只依据已核对资料作暂定分析"),
                safety = safety)
        }
        return try {
            val analysis = JevClient(prefs, appContext).judge(snapshot, prepared.relationship,
                contextFor(prepared), prepared.task, prepared.userGoal, prepared.endMode.wire,
                prepared.memoryContext)
            CoachDecision(
                strategy = analysis.strategy ?: analysis.bestAction?.choice ?: "澄清",
                facts = analysis.facts.take(6),
                unknowns = analysis.unknowns.take(6),
                safety = safety || (analysis.dangerLevel?.score ?: 0.0) >= 6.0,
                error = analysis.error
            )
        } catch (e: Exception) {
            CoachDecision("澄清", safety = safety, error = e.message ?: "策略判断暂不可用")
        }
    }

    fun submit(request: CoachRequest, token: WorkToken = WorkToken()): CoachResponse {
        token.checkActive()
        val prepared = prepare(request)
        val decision = WorkScope.run(token) { decide(prepared) }
        if (decision.boundary && prepared.task in listOf(CoachTask.OPEN, CoachTask.REPLY)) {
            return CoachResponse(
                consultation = if (decision.safety)
                    "这里先以安全为先：已有明确停止联系或危险信号。不要发送开场、邀约或“最后一句”。"
                else "对方已经明确要求停止联系。不要发送开场、邀约或“最后一句”。",
                timing = "停止推进；必要时只处理自己的退出与支持安排。",
                noReply = "尊重停止联系的要求，不回复比补一句更安全。",
                rejection = GoutouGuidance.stopCondition
            )
        }
        val response = try {
            WorkScope.run(token) {
                JevClient(prefs, appContext).coach(prepared, decision, contextFor(prepared))
            }
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            CoachResponse("咨询暂不可用：${e.message ?: e.javaClass.simpleName}", error = e.message)
        }
        token.checkActive()
        val adjusted = enforceSubject(prepared, response)
        val ranked = rankCandidates(prepared, decision, adjusted, token)
        token.checkActive()
        val persisted = persist(prepared, ranked)
        return persisted
    }

    /**
     * The reply model drafts; the strategy model independently ranks when
     * available. A ranking outage preserves the original candidate order.
     */
    private fun rankCandidates(request: CoachRequest, decision: CoachDecision,
                               response: CoachResponse, token: WorkToken): CoachResponse {
        if (response.candidates.size < 2 || response.error != null) return response
        if (!prefs.usesChatStrategy()) return response.copy(
            rankingNotice = "旧 Jev 路线不调用独立候选排序；顺序由回复模型给出。")
        val judgment = Analysis(
            trueIntent = null, dangerLevel = null, sheNeeds = null, shouldReplyNow = null,
            bestAction = Choice(decision.strategy, Double.NaN, emptyMap()), tensionResolved = null,
            literalQuestion = null, rankedReplies = emptyList(), latencyMs = 0,
            strategy = decision.strategy)
        val rankedReplies = WorkScope.run(token) {
            JevClient(prefs, appContext).rerank(request.snapshot ?: ChatSnapshot(request.relationship, emptyList()),
                request.relationship, judgment, response.candidates.map { it.text }, contextFor(request))
        }
        if (rankedReplies.all { it.prob == 0.0 }) return response.copy(
            rankingNotice = "独立排序暂不可用，保留回复模型给出的顺序。")
        val byText = response.candidates.associateBy { it.text }
        val ordered = rankedReplies.mapNotNull { byText[it.text] }.distinct()
        val leftovers = response.candidates.filter { candidate -> ordered.none { it.text == candidate.text } }
        val candidates = (ordered + leftovers).mapIndexed { index, candidate ->
            candidate.copy(label = if (index == 0) "首选" else "备选 ${index + 1}")
        }
        return response.copy(candidates = candidates)
    }

    private fun persist(request: CoachRequest, response: CoachResponse): CoachResponse {
        if (response.error != null) return response
        val updates = response.memoryUpdates.map { row ->
            when (row.scope) {
                MemoryScope.USER -> row.copy(subjectId = "user")
                else -> row.copy(subjectId = request.contactId ?: row.subjectId)
            }
        }.filter { it.subjectId.isNotBlank() }
        var notice: String? = null
        if (updates.isNotEmpty()) {
            val result = memory.apply(updates)
            val labels = updates.take(4).joinToString("、") { "${it.scope.name.lowercase()}.${it.field}" }
            notice = if (result.saved > 0)
                "记忆已更新：$labels" + (if (updates.size > 4) " 等 ${updates.size} 项" else "") + "；可撤销"
            else if (result.reason.isNotBlank()) "记忆未保存：${result.reason}"
            else null
        }
        if (transcripts.enabled() && request.task != CoachTask.REPLY) {
            val sessionId = request.contactId?.let { "contact:$it" } ?: "general"
            val saved = transcripts.saveTurn(sessionId, request.contactId,
                request.snapshot?.title ?: request.userGoal.take(40).ifBlank { "军师咨询" },
                StoredTurn(request.userGoal, response.consultation, request.task, System.currentTimeMillis()))
            if (!saved) notice = listOfNotNull(notice, "咨询原文未保存").joinToString("；")
        }
        return response.copy(memoryNotice = notice)
    }

    private fun enforceSubject(request: CoachRequest, response: CoachResponse): CoachResponse {
        if (request.contactId != null) return response.copy(memoryUpdates = response.memoryUpdates.map {
            if (it.scope == MemoryScope.USER) it.copy(subjectId = "user")
            else it.copy(subjectId = request.contactId)
        })
        return response.copy(memoryUpdates = response.memoryUpdates.filter { it.scope == MemoryScope.USER })
    }

    private fun contextFor(request: CoachRequest): ChatContext? {
        val contact = request.contactId?.let { kb.contact(it) } ?: return null
        val history = if (prefs.contextEnabled) kb.recentLog(contact.id, prefs.contextHistoryCount) else emptyList()
        return ChatContext(contact, history, emptyList())
    }

}
