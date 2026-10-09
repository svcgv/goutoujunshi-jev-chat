package com.jev.probe.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailBackActionTest {

    @Test fun anExistingJudgmentReturnsToTheReplies() {
        assertEquals(DetailBackAction.Kind.BACK_TO_REPLIES,
            DetailBackAction.forPage(hasJudgment = true, hasCallback = true))
        assertEquals(DetailBackAction.Kind.BACK_TO_REPLIES,
            DetailBackAction.forPage(hasJudgment = true, hasCallback = false))
    }

    @Test fun historyOpenedBeforeAnyAnalysisUsesTheCallersWayOut() {
        // Regression: this used to render "返回候选回复" with nothing to return
        // to, so the button did nothing.
        assertEquals(DetailBackAction.Kind.CALLBACK,
            DetailBackAction.forPage(hasJudgment = false, hasCallback = true))
    }

    @Test fun noWayOutMeansNoButton() {
        assertEquals(DetailBackAction.Kind.NONE,
            DetailBackAction.forPage(hasJudgment = false, hasCallback = false))
    }
}
