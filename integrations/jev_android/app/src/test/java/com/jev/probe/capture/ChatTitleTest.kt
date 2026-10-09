package com.jev.probe.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The conversation title can only be read from pixels (WeChat draws its own bar),
 * so OCR output is filtered before it can become a contact identity.
 */
class ChatTitleTest {

    @Test fun readsTheTitleFromTheBand() {
        assertEquals("兰花独秀", ChatTitle.pick(listOf("兰花独秀" to 150)))
    }

    @Test fun ignoresTheMoreButtonAndOtherChrome() {
        assertEquals("兰花独秀", ChatTitle.pick(listOf("更多信息" to 150, "兰花独秀" to 152)))
    }

    @Test fun stripsLeadingSymbolsThePlatformAdds() {
        assertEquals("兰花独秀", ChatTitle.pick(listOf("… 兰花独秀" to 150)))
        assertEquals("兰花独秀", ChatTitle.pick(listOf("·兰花独秀·" to 150)))
    }

    @Test fun aGroupTitleKeepsItsMemberCount() {
        assertEquals("项目组(12)", ChatTitle.pick(listOf("项目组(12)" to 150)))
    }

    @Test fun timestampsAndBareNumbersAreNotNames() {
        assertNull(ChatTitle.pick(listOf("22:55" to 150)))
        assertNull(ChatTitle.pick(listOf("19:25" to 150)))
        assertNull(ChatTitle.pick(listOf("2026" to 150)))
    }

    @Test fun veryLongTextIsRejected() {
        assertNull(ChatTitle.pick(listOf("这是一个特别特别特别特别特别长的名字不应该被当作标题" to 150)))
    }

    @Test fun nothingReadableYieldsNull() {
        assertNull(ChatTitle.pick(emptyList()))
        assertNull(ChatTitle.pick(listOf("   " to 150)))
    }

    @Test fun theFirstPlausibleLineWinsInBandOrder() {
        // Lines are supplied top-to-bottom; the title is the topmost real one.
        assertEquals("张三", ChatTitle.pick(listOf("更多信息" to 140, "张三" to 150, "李四" to 160)))
    }

    @Test fun perAppRegionsAreStoredIndependently() {
        val wechat = TitleRegion.fromPixels(100, 120, 700, 260, 1216, 2640)!!
        val qq = TitleRegion.fromPixels(80, 140, 800, 300, 1216, 2640)!!
        val regions = mapOf("com.tencent.mm" to wechat, "com.tencent.mobileqq" to qq)
        val decoded = TitleRegions.decode(TitleRegions.encode(regions))
        assertEquals(wechat, decoded["com.tencent.mm"])
        assertEquals(qq, decoded["com.tencent.mobileqq"])
    }

    @Test fun settingOneAppDoesNotDisturbAnother() {
        val wechat = TitleRegion.fromPixels(100, 120, 700, 260, 1216, 2640)!!
        val qq = TitleRegion.fromPixels(80, 140, 800, 300, 1216, 2640)!!
        val regions = TitleRegions.with(emptyMap(), "com.tencent.mm", wechat)
        val both = TitleRegions.with(regions, "com.tencent.mobileqq", qq)
        assertEquals(wechat, both["com.tencent.mm"])
        assertEquals(qq, both["com.tencent.mobileqq"])
    }

    @Test fun clearingOneAppKeepsTheRest() {
        val wechat = TitleRegion.fromPixels(100, 120, 700, 260, 1216, 2640)!!
        val regions = mapOf("com.tencent.mm" to wechat)
        val cleared = TitleRegions.with(regions, "com.tencent.mm", null)
        assertEquals(emptyMap<String, TitleRegion>(), cleared)
    }

    @Test fun malformedStoredMapsAreIgnored() {
        assertEquals(emptyMap<String, TitleRegion>(), TitleRegions.decode(null))
        assertEquals(emptyMap<String, TitleRegion>(), TitleRegions.decode("garbage"))
        assertEquals(emptyMap<String, TitleRegion>(), TitleRegions.decode("com.tencent.mm=nope"))
        assertEquals(emptyMap<String, TitleRegion>(), TitleRegions.decode("=1,2,3,4"))
    }
}
