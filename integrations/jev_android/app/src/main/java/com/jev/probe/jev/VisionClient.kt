package com.jev.probe.jev

import android.graphics.Bitmap
import android.util.Base64
import com.jev.probe.core.Prefs
import com.jev.probe.core.ModelProtocol
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * The vision route: an OpenAI-compatible `/chat/completions` endpoint that
 * accepts `image_url` content parts. The screenshot pipeline uses it when cloud OCR is selected.
 *
 * Resolves the explicitly selected vision model from [Prefs]. The service and
 * key do not inherit from the reply route; the user chooses the host that
 * receives the screenshot.
 *
 * Wire format notes that cost real debugging time:
 * - JPEG, not PNG: a screenshot as PNG base64 is several times larger.
 * - `Base64.NO_WRAP`: Android's default inserts newlines, which corrupts the
 *   data URL.
 * - The image part goes BEFORE the text part — DashScope's compatible-mode
 *   rejects the other order.
 */
class VisionClient(private val prefs: Prefs) {

    /**
     * Send a screenshot and get the transcribed dialog back as plain text.
     * The caller parses this into bubbles and asks the user to review them.
     *
     * @param imageBase64Jpeg base64 of a JPEG, without the `data:` prefix.
     */
    fun extractDialog(imageBase64Jpeg: String): String = ask(
        imageBase64Jpeg,
        "你是聊天截图转写助手。把图中聊天气泡按从上到下的顺序转写成文本，" +
            "每行一条，格式 `我：正文` 或 `对方：正文`。只输出转写结果，不要解释。"
    )

    /** Generic single-question call against the image (used by the settings test). */
    fun ask(imageBase64Jpeg: String, prompt: String): String {
        val route = prefs.visionRoute()
            ?: throw IllegalStateException("请先选择视觉模型")
        require(route.protocol == ModelProtocol.CHAT) { "视觉模型必须使用聊天协议" }
        require(route.vision) { "所选模型未标记为支持图片" }
        require(route.key.isNotBlank()) { "请先配置视觉模型所在服务的密钥" }
        val url = route.endpoint
        val image = JSONObject().put("type", "image_url")
            .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageBase64Jpeg"))
        val text = JSONObject().put("type", "text").put("text", prompt)
        // DashScope requires image first; DeepSeek's documented format puts text first.
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
        val content = if (host == "api.deepseek.com")
            JSONArray().put(text).put(image) else JSONArray().put(image).put(text)
        val messages = JSONArray().put(
            JSONObject().put("role", "user").put("content", content))
        val body = JSONObject()
            .put("model", route.modelId)
            .put("messages", messages)
            .put("temperature", 0.0)
        val resp = HttpJson.post(url, route.key, body, Route.VISION, HttpJson.headersFor(url))
        return resp.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content") ?: ""
    }

    companion object {
        /** Bitmap -> JPEG base64 in the exact form [ask] expects. */
        fun encodeJpeg(bitmap: Bitmap, quality: Int = 80): String {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }

        /** DeepSeek Flash accepts image_url; custom hosts are checked by the request itself. */
        fun supportsVision(baseUrl: String): Boolean = baseUrl.trim().isNotEmpty()
    }
}
