package com.jev.probe.core.kb

import com.jev.probe.core.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What the UI reads straight after a backfill must be what was just written.
 *
 * rememberBackfill merges into a COPY of the cached list, so writing to disk
 * alone left the in-memory cache showing the pre-save rows — the panel kept
 * saying "已存 N 条" with the old count and 查看对象历史 listed only those.
 */
class KbStoreBackfillCacheTest {

    @get:Rule val temp = TemporaryFolder()

    private fun store() = KbStore(temp.root)

    @Test fun historyReadBackReflectsTheBackfillThatWasJustSaved() {
        val store = store()
        assertTrue(store.saveContact(Contact("c1", "对象", apps = listOf("wechat"))))
        // Sides must match the batch below: m4 is mine, m5 is theirs.
        assertTrue(store.appendLog("c1", listOf(
            LogEntry("me", "m4", 0L, "wechat"),
            LogEntry("other", "m5", 0L, "wechat")
        ), screenBatch = false))
        // Read the size first, the way the binding summary does, so the cache is warm.
        assertEquals(2, store.logSize("c1"))

        val backfilled = (1..6).map { Msg(if (it % 2 == 0) "me" else "other", "m$it") }
        assertTrue(store.rememberBackfill("c1", "wechat", backfilled))

        assertEquals(6, store.logSize("c1"))
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5", "m6"),
            store.recentLog("c1", 100).map { it.text })
    }

    @Test fun aSecondBackfillIsIdempotentAndStillVisible() {
        val store = store()
        assertTrue(store.saveContact(Contact("c1", "对象", apps = listOf("wechat"))))
        val batch = (1..4).map { Msg("other", "m$it") }
        assertTrue(store.rememberBackfill("c1", "wechat", batch))
        assertEquals(4, store.logSize("c1"))
        assertTrue(store.rememberBackfill("c1", "wechat", batch))
        assertEquals(4, store.logSize("c1"))
        assertEquals(listOf("m1", "m2", "m3", "m4"), store.recentLog("c1", 100).map { it.text })
    }
}
