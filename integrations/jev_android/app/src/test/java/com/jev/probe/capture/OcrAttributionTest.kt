package com.jev.probe.capture

import android.graphics.Rect
import com.jev.probe.capture.ocr.OcrLine
import com.jev.probe.core.BubbleRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The OCR fallback must keep the speaker each bubble already implies from its
 * position, instead of making the user label every row by hand.
 */
class OcrAttributionTest {

    private fun line(text: String, top: Int, bottom: Int) =
        OcrLine(text, rect(top, bottom))

    private fun bubble(side: String, top: Int, bottom: Int) =
        BubbleRect(rect(top, bottom), side)

    /** Local JVM tests receive an Android stub jar, so set Rect fields directly. */
    private fun rect(top: Int, bottom: Int) = Rect().apply {
        left = 100
        this.top = top
        right = 500
        this.bottom = bottom
    }

    @Test fun linesAreAssignedToTheBubbleThatContainsThem() {
        val bubbles = listOf(bubble("other", 100, 200), bubble("me", 260, 360))
        val msgs = OcrAttribution.assign(
            listOf(line("你好", 110, 150), line("在的", 270, 310)), bubbles)!!
        assertEquals(listOf("other" to "你好", "me" to "在的"), msgs.map { it.side to it.text })
    }

    @Test fun severalLinesInOneBubbleAreJoinedInReadingOrder() {
        val bubbles = listOf(bubble("other", 100, 260))
        val msgs = OcrAttribution.assign(
            listOf(line("第二行", 200, 240), line("第一行", 110, 150)), bubbles)!!
        assertEquals(listOf("other"), msgs.map { it.side })
        assertEquals("第一行 第二行", msgs[0].text)
    }

    @Test fun bubblesComeBackTopToBottom() {
        val bubbles = listOf(bubble("me", 400, 500), bubble("other", 100, 200))
        val msgs = OcrAttribution.assign(
            listOf(line("上面", 110, 150), line("下面", 410, 450)), bubbles)!!
        assertEquals(listOf("上面", "下面"), msgs.map { it.text })
    }

    @Test fun anOverlappingNeighbourDoesNotStealALine() {
        // The big bubble overlaps the small one; the line belongs to the small one.
        val bubbles = listOf(bubble("other", 100, 400), bubble("me", 200, 240))
        val msgs = OcrAttribution.assign(listOf(line("短句", 205, 235)), bubbles)!!
        assertEquals(listOf("me"), msgs.map { it.side })
    }

    @Test fun linesOutsideEveryBubbleAreDropped() {
        val bubbles = listOf(bubble("other", 100, 200))
        assertEquals(listOf("other" to "里面"), OcrAttribution.assign(
            listOf(line("外面", 600, 640), line("里面", 110, 150)), bubbles)!!
            .map { it.side to it.text })
    }

    @Test fun nothingToAssignFallsBackToTheCaller() {
        assertNull(OcrAttribution.assign(emptyList(), listOf(bubble("other", 0, 10))))
        assertNull(OcrAttribution.assign(listOf(line("x", 0, 10)), emptyList()))
        assertNull(OcrAttribution.assign(listOf(line("x", 900, 940)), listOf(bubble("other", 0, 10))))
    }
}
