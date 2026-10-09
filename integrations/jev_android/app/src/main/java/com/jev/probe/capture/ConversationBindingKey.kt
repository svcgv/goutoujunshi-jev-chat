package com.jev.probe.capture

/**
 * Which key identifies the conversation currently on screen.
 *
 * A readable title always wins, so a different conversation can never inherit
 * the previous one's name. When the title is unreadable (WeChat and other apps
 * that draw their own text) the user-confirmed name is used instead, which keeps
 * the binding stable across capture cycles instead of asking to bind again.
 */
internal object ConversationBindingKey {
    fun resolve(readableTitle: String?, confirmedName: String?): String? {
        val title = readableTitle?.trim().orEmpty()
        if (title.isNotEmpty() && !isTransient(title)) return title
        return confirmedName?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun isTransient(title: String): Boolean {
        val trimmed = title.removeSuffix("…").removeSuffix("...").trim()
        if (trimmed.isEmpty()) return true
        val lower = trimmed.lowercase()
        return TRANSIENT.any { lower.contains(it.lowercase()) }
    }

    private val TRANSIENT = listOf(
        "连接中", "正在连接", "未连接", "Connecting",
        "加载中", "Loading", "同步中", "Syncing"
    )
}
