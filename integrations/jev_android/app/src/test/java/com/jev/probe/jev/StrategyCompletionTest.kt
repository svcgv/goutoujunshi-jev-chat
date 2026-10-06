package com.jev.probe.jev

import com.jev.probe.core.StrategyRoute
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StrategyCompletionTest {
    private val evidence = """{"strategy":"降压","intent":"可能暂时忙","confidence":null,"facts":["对方说这周忙"],"unknowns":["下周是否有空"]}"""

    private fun response(text: String, finish: String = "stop"): JSONObject = JSONObject().put("choices",
        JSONArray().put(JSONObject().put("finish_reason", finish)
            .put("message", JSONObject().put("role", "assistant").put("content", text))))

    @Test fun proxyReceivesChatPayloadAndBearerKeyNotJevDecisions() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 5000
        val worker = Executors.newSingleThreadExecutor()
        val task = worker.submit {
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                assertEquals("POST /v1/chat/completions HTTP/1.1", input.readLine())
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = input.readLine() ?: error("missing HTTP headers")
                    if (line.isEmpty()) break
                    headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                }
                assertEquals("Bearer fixture-proxy-key", headers["authorization"])
                val chars = CharArray(headers.getValue("content-length").toInt())
                var offset = 0
                while (offset < chars.size) {
                    val count = input.read(chars, offset, chars.size - offset)
                    check(count > 0)
                    offset += count
                }
                val body = JSONObject(String(chars))
                assertEquals(setOf("model", "messages", "stream"), body.keySet())
                assertEquals("gpt-proxy-alias", body.getString("model"))
                val bytes = response(evidence).toString().toByteArray(Charsets.UTF_8)
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                socket.getOutputStream().write(bytes)
                socket.getOutputStream().flush()
            }
        }
        try {
            val endpoint = StrategyRoute.endpoint("http://127.0.0.1:${server.localPort}/v1")
            val result = StrategyCompletion.request(endpoint, "fixture-proxy-key",
                StrategyRequest.body("gpt-proxy-alias", "system", "user", false, json = true))
            assertEquals(evidence, result)
            assertEquals("降压", StrategyEvidence.parse(result)?.getString("strategy"))
            task.get(10, TimeUnit.SECONDS)
        } finally {
            server.close()
            worker.shutdownNow()
        }
    }

    @Test fun truncatedOrEmptyOutputCannotBecomeJudgment() {
        for (data in listOf(response(evidence, "length"), response(""), response(" "), response(evidence, "content_filter"))) {
            try {
                StrategyCompletion.content(data)
                fail("accepted invalid completion")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun missingLogprobsIsFineForCompatibleRoute() {
        assertEquals(evidence, StrategyCompletion.content(response(evidence)))
    }
}
