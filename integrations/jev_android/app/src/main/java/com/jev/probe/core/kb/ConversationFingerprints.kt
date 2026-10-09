package com.jev.probe.core.kb

/**
 * Message-based identification for conversations whose title cannot be read.
 *
 * A title-less window (WeChat) is recognised by the messages it shows: the same
 * conversation repeats most of the same recent lines, so overlapping text is a
 * reliable signal. Ambiguity is refused rather than guessed — binding the wrong
 * person would put one contact's history in front of another.
 */
object ConversationFingerprints {

    fun of(messages: List<String>): List<String> = ConversationBindings.fingerprintOf(messages)

    fun match(rows: List<ConversationBinding>, app: String, messages: List<String>): ConversationBinding? =
        ConversationBindings.resolveByFingerprint(rows, app, messages)
}
