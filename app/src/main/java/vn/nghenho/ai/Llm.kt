package vn.nghenho.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import vn.nghenho.App

/** Gọi mô hình ngôn ngữ để tóm tắt / hỏi đáp. schema != null => yêu cầu trả JSON. */
object Llm {
    private const val GEMINI = "https://generativelanguage.googleapis.com/v1beta/models"

    suspend fun complete(system: String, user: String, schema: JsonObject?, maxTokens: Int = 16_000): String = withContext(Dispatchers.IO) {
        val s = App.settings
        val p = Providers.llm(s.llmProvider)
        val key = s.key(p.keyId)
        if (key.isBlank() && p.id != "custom") throw MissingKeyException("Chưa có API key cho ${p.label}")
        val model = Providers.llmModel(s)
        when (p.id) {
            "anthropic" -> ClaudeClient.complete(key, model, system, user, schema, maxTokens)
            "gemini" -> gemini(key, model, system, user, json = schema != null)
            else -> openAi(Providers.openAiBase(p.id, s), key, model, system, user, json = schema != null)
        }
    }

    private fun gemini(key: String, model: String, system: String, user: String, json: Boolean): String {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", system) } } }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", user) } }
                }
            }
            if (json) putJsonObject("generationConfig") { put("responseMimeType", "application/json") }
        }
        val req = Request.Builder()
            .url("$GEMINI/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody(Http.JSON))
            .build()
        return Parse.geminiText(Http.call(req, "Gemini"))
    }

    private fun openAi(base: String, key: String, model: String, system: String, user: String, json: Boolean): String {
        if (base.isBlank()) throw MissingKeyException("Chưa nhập địa chỉ máy chủ riêng")
        fun body(withFormat: Boolean) = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", system) }
                addJsonObject { put("role", "user"); put("content", user) }
            }
            if (withFormat) putJsonObject("response_format") { put("type", "json_object") }
        }
        fun req(withFormat: Boolean) = Request.Builder()
            .url("$base/chat/completions")
            .apply { if (key.isNotBlank()) header("Authorization", "Bearer $key") }
            .post(body(withFormat).toString().toRequestBody(Http.JSON))
            .build()
        val txt = try {
            Http.call(req(json), "AI")
        } catch (e: ApiException) {
            if (json && e.code == 400) Http.call(req(false), "AI") else throw e
        }
        return Parse.openAiChatText(txt)
    }
}
