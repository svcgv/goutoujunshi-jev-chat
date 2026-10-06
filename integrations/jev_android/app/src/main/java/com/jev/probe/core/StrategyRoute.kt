package com.jev.probe.core

import java.net.URI

/** A base URL includes /v1 if needed; also accepts a full completion URL. */
internal object StrategyRoute {
    fun endpoint(base: String): String {
        val value = base.trim().trimEnd('/')
        val uri = URI(value)
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "策略地址必须是无用户名、查询参数和片段的 HTTP(S) API 地址"
        }
        return if (value.endsWith("/chat/completions")) value else "$value/chat/completions"
    }
}
