package com.jev.probe.core.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillLibraryTest {

    private val replyDoc = """
        # 实战话术编排器

        ## 一句话的生成流程
        先定本轮目标，再决定怎么回复；一句话只做一个主动作。
        为每句话留后续分支，对方拒绝时也能体面退出。这里的正文要够长才能成为一个片段。
    """.trimIndent()

    private val inviteDoc = """
        # 主动表达与第一次见面

        ## 从泛聊转邀约
        邀约要给出具体时间和地点，并让对方可以轻松拒绝。
        不要用虚假的时间限制来施压。正文需要足够长才会被当作一个片段保留下来。
    """.trimIndent()

    private val safetyDoc = """
        # 法律安全与危机

        ## 危险信号
        出现威胁、跟踪、强迫或人身危险时，先确认当下安全并联系可信支持或当地紧急服务。
        不要协助任何形式的胁迫或报复行为。这段正文足够长可以形成一个片段。
    """.trimIndent()

    private fun library() = SkillLibrary(listOf(
        "reply_craft.md" to replyDoc,
        "first_meeting.md" to inviteDoc,
        "safety_crisis.md" to safetyDoc
    ))

    @Test fun splitsDocumentsIntoSections() {
        val sections = library().splitSections("reply_craft.md", replyDoc)
        assertTrue(sections.isNotEmpty())
        assertTrue(sections.all { it.body.length >= 40 })
    }

    @Test fun retrievalPicksTheTopicMatchingTheConversation() {
        val hits = library().search("对方问我这周有没有空，我想约她出来见个面")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.first().contains("邀约"))
    }

    @Test fun unrelatedConversationStillMatchesOnlyRelevantTopics() {
        val hits = library().search("她说最近工作有点累")
        // No bundled topic is triggered here; returning nothing is correct.
        assertTrue(hits.isEmpty())
    }

    @Test fun emptyOrBlankQueryReturnsNothing() {
        assertTrue(library().search("").isEmpty())
        assertTrue(library().search("   ").isEmpty())
    }

    @Test fun dangerousConversationSurfacesSafetyMaterial() {
        val hits = library().search("对方一直威胁我，还跟踪我回家")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.first().contains("安全") || hits.first().contains("危险"))
    }

    @Test fun resultsRespectLimitAndBudget() {
        val hits = library().search("她说有空见面，但我不确定她是不是只想找个伴", limit = 1, budget = 200)
        assertTrue(hits.size <= 1)
        assertTrue(hits.sumOf { it.length } <= 200 + 8)
    }

    @Test fun atMostOneExcerptPerDocument() {
        val hits = library().search("回复 邀约 安全 危险 威胁 有空 见面", limit = 3)
        val files = hits.map { it.substringAfter("[军师知识·").substringBefore("]") }
        assertEquals(files.size, files.toSet().size)
    }

    @Test fun aLibraryWithNoDocumentsIsEmptyAndSafe() {
        val empty = SkillLibrary(emptyList())
        assertTrue(empty.isEmpty())
        assertTrue(empty.search("随便说点什么").isEmpty())
    }

    @Test fun eachDocumentCarriesItsOwnTopicKeywords() {
        val lib = library()
        // Keywords are fixed per document, so topics never leak between files.
        assertTrue(lib.keywordsFor("first_meeting.md").any { it == "邀约" || it == "见面" })
    }

    @Test fun missingTopicIsNotFabricated() {
        assertFalse(library().search("我们今天讨论的是股票和基金").isNotEmpty())
    }

    @Test fun requiredTaskDocumentWinsEvenWhenTheQueryIsSparse() {
        val hits = library().search("想开始", limit = 1, requiredFiles = setOf("reply_craft.md"))
        assertTrue(hits.single().contains("生成流程"))
    }

    @Test fun safetyCanBeForcedEvenWhenKeywordMatchingWouldMissIt() {
        val hits = library().search("今天不知道怎么处理", limit = 2, forceSafety = true)
        assertTrue(hits.any { it.contains("危险") || it.contains("安全") })
    }

    @Test fun headingTermsRaiseTheSpecificSubsection() {
        val doc = """
            # 测试
            ## 从泛聊转邀约
            邀约要给出具体时间和地点，并让对方可以轻松拒绝。这段正文足够长可以形成片段。
            ## 冲突修复
            冲突后只为已确认的问题道歉，观察对方是否愿意继续谈。这段正文也足够长可以形成片段。
        """.trimIndent()
        val hits = SkillLibrary(listOf("first_meeting.md" to doc))
            .search("我想修复一次冲突并道歉", limit = 3)
        assertTrue(hits.first().contains("冲突修复"))
    }
}
