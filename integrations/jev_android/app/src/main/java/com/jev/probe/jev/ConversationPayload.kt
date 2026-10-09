package com.jev.probe.jev

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.kb.ChatContext

/**
 * Builds the budgeted transcript every chat model call shares.
 *
 * Within budget the reviewed messages go out verbatim. Over budget the newest
 * messages stay verbatim and the older ones are compressed through the caller's
 * own route, so no long message is silently clipped to a fixed character count.
 */
internal object ConversationPayload {

    /** "我：…" / "对方：…", one message per paragraph; long bodies keep newlines. */
    fun render(messages: List<Msg>): String = messages.joinToString("\n") {
        (if (it.side == "me") "我" else "对方") + "：" + it.text
    }

    fun historyMessages(ctx: ChatContext?): List<Msg> =
        ctx?.history?.map { Msg(it.side, it.text) }.orEmpty()

    /**
     * @param overheadTokens prompt/background cost already committed.
     * @param summarizeExtract extracts durable facts from one chunk; must call
     *        the same route the caller will use.
     */
    fun prepare(prefs: Prefs, snapshot: ChatSnapshot, ctx: ChatContext?,
                overheadTokens: Int, summarizeExtract: (String) -> String): ContextPreparer.Prepared =
        ContextPreparer(prefs.tokenBudget()).prepare(
            primary = snapshot.messages,
            history = historyMessages(ctx),
            overheadTokens = overheadTokens,
            render = ::render,
            summarize = summarizeExtract)
}
