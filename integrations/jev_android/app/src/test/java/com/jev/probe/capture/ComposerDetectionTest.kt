package com.jev.probe.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat composer must be found even when the platform does not report it as
 * editable. WeChat's input field is a self-drawn EditText whose `isEditable`
 * flag is not always truthful, which made "填入" silently fall back to the
 * clipboard.
 */
class ComposerDetectionTest {

    @Test fun aRealEditTextWidgetIsAccepted() {
        assertTrue(ComposerShape.isComposer(
            className = "android.widget.EditText", viewId = null,
            enabled = true, visible = true, clickable = true, focusable = true, editable = false,
            screenHeight = 2640))
    }

    @Test fun wechatComposerIdIsAcceptedEvenWhenNotFlaggedEditable() {
        // Observed on device: id=bkk, class=EditText, editable not reported.
        assertTrue(ComposerShape.isComposer(
            className = "android.widget.EditText", viewId = "com.tencent.mm:id/bkk",
            enabled = true, visible = true, clickable = true, focusable = true, editable = false,
            screenHeight = 2640))
    }

    @Test fun anEditableFieldIsAlwaysAccepted() {
        assertTrue(ComposerShape.isComposer(
            className = "android.widget.FrameLayout", viewId = null,
            enabled = true, visible = true, clickable = false, focusable = false, editable = true,
            screenHeight = 2640))
    }

    @Test fun anUnrelatedTextViewIsNotAComposer() {
        assertFalse(ComposerShape.isComposer(
            className = "android.widget.TextView", viewId = "com.tencent.mm:id/title",
            enabled = true, visible = true, clickable = true, focusable = true, editable = false,
            screenHeight = 2640))
    }

    @Test fun aDisabledOrInvisibleFieldIsRejected() {
        assertFalse(ComposerShape.isComposer(
            className = "android.widget.EditText", viewId = null,
            enabled = false, visible = true, clickable = true, focusable = true, editable = true,
            screenHeight = 2640))
        assertFalse(ComposerShape.isComposer(
            className = "android.widget.EditText", viewId = null,
            enabled = true, visible = false, clickable = true, focusable = true, editable = true,
            screenHeight = 2640))
    }

    @Test fun aSearchBoxNearTheTopIsNotTheChatComposer() {
        // The composer lives in the lower region of the screen.
        assertFalse(ComposerShape.isInComposerRegion(centerY = 300, screenHeight = 2640))
        assertTrue(ComposerShape.isInComposerRegion(centerY = 2560, screenHeight = 2640))
    }
}
