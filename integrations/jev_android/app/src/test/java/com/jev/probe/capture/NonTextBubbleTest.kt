package com.jev.probe.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Red packets and transfers stay in the transcript as a named placeholder, so
 * the model knows something happened without reading the card as dialogue.
 */
class NonTextBubbleTest {

    @Test fun aRedPacketCardBecomesAPlaceholder() {
        assertEquals("[红包]", NonTextBubble.placeholderFor("微信红包"))
        assertEquals("[红包]", NonTextBubble.placeholderFor("恭喜发财，大吉大利", "微信红包"))
        assertEquals("[红包]", NonTextBubble.placeholderFor("QQ红包"))
    }

    @Test fun aTransferBecomesAPlaceholderAndKeepsTheAmount() {
        assertEquals("[转账]", NonTextBubble.placeholderFor("转账"))
        assertEquals("[转账 ¥100.00]", NonTextBubble.placeholderFor("转账 ¥100.00"))
        assertEquals("[转账 ¥52.5]", NonTextBubble.placeholderFor("转账￥52.5"))
        assertEquals("[转账]", NonTextBubble.placeholderFor("请收款"))
        assertEquals("[转账]", NonTextBubble.placeholderFor("已收款"))
    }

    @Test fun anAccessibilityDescriptionAloneIsEnough() {
        assertEquals("[转账 ¥100.00]", NonTextBubble.placeholderFor("¥100.00", "转账"))
        assertEquals("[红包]", NonTextBubble.placeholderFor(null, "微信红包，点击领取"))
    }

    @Test fun anOrdinaryMessageThatMentionsRedPacketsIsLeftAlone() {
        // The whole point of being conservative: these are things people typed.
        assertNull(NonTextBubble.placeholderFor("你发的红包我收到了"))
        assertNull(NonTextBubble.placeholderFor("发个红包呗"))
        assertNull(NonTextBubble.placeholderFor("红包"))
        assertNull(NonTextBubble.placeholderFor("转账给他了，还没到"))
        assertNull(NonTextBubble.placeholderFor("我今天转账了"))
    }

    @Test fun ordinaryChatIsNeverTouched() {
        assertNull(NonTextBubble.placeholderFor("在吗"))
        assertNull(NonTextBubble.placeholderFor("¥100"))
        assertNull(NonTextBubble.placeholderFor(""))
        assertNull(NonTextBubble.placeholderFor(null))
    }
}
