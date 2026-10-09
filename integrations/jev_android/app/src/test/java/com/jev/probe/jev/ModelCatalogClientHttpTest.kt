package com.jev.probe.jev

import com.jev.probe.core.ServiceConfig
import com.jev.probe.core.ServiceKind
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogClientHttpTest {

    @Test fun fetchSendsBearerAndParsesStandardList() {
        val server = responseServer(200, """{"data":[{"id":"one"},{"id":"two"}]}""")
        try {
            val service = service(server.first.localPort)
            assertEquals(listOf("one", "two"), ModelCatalogClient.fetch(service).map { it.id })
            val request = server.second.get(5, TimeUnit.SECONDS)
            assertEquals("GET /v1/models HTTP/1.1", request.firstOrNull())
            assertTrue(request.any { it == "Authorization: Bearer fixture-key" })
        } finally {
            server.first.close()
        }
    }

    @Test fun fetchAcceptsEmptyListWithoutInventingModels() {
        val server = responseServer(200, """{"data":[]}""")
        try {
            assertTrue(ModelCatalogClient.fetch(service(server.first.localPort)).isEmpty())
        } finally {
            server.first.close()
        }
    }

    @Test fun unauthorizedIsReportedWithoutRetry() {
        val server = responseServer(401, """{"error":"bad key"}""")
        try {
            val error = runCatching { ModelCatalogClient.fetch(service(server.first.localPort)) }.exceptionOrNull()
            assertTrue(error is ApiException)
            assertEquals(401, (error as ApiException).status)
        } finally {
            server.first.close()
        }
    }

    @Test fun notFoundIsReportedAsHttpStatus() {
        val server = responseServer(404, "not found")
        try {
            val error = runCatching { ModelCatalogClient.fetch(service(server.first.localPort)) }.exceptionOrNull()
            assertTrue(error is ApiException)
            assertEquals(404, (error as ApiException).status)
        } finally {
            server.first.close()
        }
    }

    private fun service(port: Int) = ServiceConfig(
        id = "svc", name = "Fixture", kind = ServiceKind.COMPATIBLE,
        baseUrl = "http://127.0.0.1:$port/v1", key = "fixture-key")

    private fun responseServer(status: Int, body: String): Pair<ServerSocket, Future<List<String>>> {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 5000
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit<List<String>> {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().buffered()
                    val lines = ArrayList<String>()
                    while (true) {
                        val line = readLine(input)
                        if (line.isEmpty()) break
                        lines.add(line)
                    }
                    val reason = if (status == 200) "OK" else "Error"
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().use { output ->
                        output.write(("HTTP/1.1 $status $reason\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                            .toByteArray(Charsets.US_ASCII))
                        output.write(bytes)
                    }
                    lines
                }
            } finally {
                executor.shutdown()
            }
        }
        return server to future
    }

    private fun readLine(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < 8192) {
            val next = input.read()
            check(next != -1) { "Incomplete HTTP request" }
            if (next == '\n'.code) return bytes.toString("US-ASCII").trimEnd('\r')
            bytes.write(next)
        }
        error("HTTP header line too long")
    }
}
