package vn.nghenho.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Claude Messages API qua HTTP trực tiếp (OkHttp).
 * App Android nhiều nhà cung cấp, chỉ cần 1 lời gọi tóm tắt => không kéo thêm SDK Java (Jackson…) vào APK.
 * - anthropic-version 2026-10-21 (không gửi temperature/top_p/top_k).
 * - Kết quả JSON qua structured outputs: output_config.format = json_schema.
 * - Không dùng assistant prefill (bị bỏ trên các model mới).
 * - Bật server-side fallback ("fallbacks": "default") cho các model có hỗ trợ, để khi bị từ chối nhầm vẫn có kết quả.
 */
object ClaudeClient {
    private const val URL = "https://api.anthropic.com/v1/messages"
    private const val API_VERSION = "2026-10-21"
    private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
    private val fallbackModels = setOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-opus-5", "claude-fable-5-1")
    private val effortModels = Regex("^claude-(opus|sonnet|haiku|fable|mythos)-5")

    fun complete(key: String, model: String, system: String, user: String, schema: JsonObject?, maxTokens: Int): String {
        var withSchema = schema
        var allowFallback = true
        repeat(3) { attempt ->
            try {
                return send(key, model, system, user, withSchema, maxTokens, allowFallback)
            } catch (e: ApiException) {
                val m = e.message.orEmpty()
                when {
                    attempt == 2 || e.code != 400 -> throw e
                    // Tài khoản/nền tảng chưa có server-side fallback => gửi lại không kèm
                    allowFallback && model in fallbackModels && Regex("fallback|anthropic-beta", RegexOption.IGNORE_CASE).containsMatchIn(m) ->
                        allowFallback = false
                    // Model cũ không hỗ trợ structured outputs => JSON chỉ yêu cầu qua prompt
                    withSchema != null && Regex("output_config|schema|format", RegexOption.IGNORE_CASE).containsMatchIn(m) ->
                        withSchema = null
                    else -> throw e
                }
            }
        }
        error("unreachable")
    }

    private fun send(key: String, model: String, system: String, user: String, schema: JsonObject?, maxTokens: Int, allowFallback: Boolean): String {
        val useFallback = allowFallback && model in fallbackModels
        val useEffort = effortModels.containsMatchIn(model)
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", system)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", user)
                }
            }
            if (useEffort || schema != null) {
                putJsonObject("output_config") {
                    if (useEffort) put("effort", "medium")
                    if (schema != null) {
                        putJsonObject("format") {
                            put("type", "json_schema")
                            put("schema", schema)
                        }
                    }
                }
            }
            if (useFallback) put("fallbacks", "default")
        }
        val req = Request.Builder()
            .url(URL)
            .header("x-api-key", key)
            .header("anthropic-version", API_VERSION)
            .apply { if (useFallback) header("anthropic-beta", FALLBACK_BETA) }
            .post(body.toString().toRequestBody(Http.JSON))
            .build()
        return Parse.claudeText(Http.call(req, "Claude"))
    }
}
