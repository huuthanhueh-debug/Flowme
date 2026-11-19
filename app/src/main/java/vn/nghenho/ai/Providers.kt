package vn.nghenho.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import vn.nghenho.data.Settings
import java.io.IOException
import java.util.concurrent.TimeUnit

/** auth = key sai/hết hạn/không có quyền: dừng hàng chờ (không đánh lỗi từng đoạn) và báo người dùng. */
class ApiException(
    val code: Int, message: String, val retryable: Boolean, val auth: Boolean = false, val retryAfterMs: Long? = null,
) : Exception(message)
class MissingKeyException(message: String) : Exception(message)

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS)
        .build()

    val JSON = "application/json; charset=utf-8".toMediaType()
    private val AUTH_HINT = Regex("api[ _-]?key|API_KEY_INVALID|unauthori[sz]ed|authentication|invalid[ _-]?token", RegexOption.IGNORE_CASE)

    /** Gọi HTTP đồng bộ (chạy trên luồng IO). Lỗi mạng/429/5xx => retryable để WorkManager thử lại. */
    fun call(req: Request, name: String): String {
        try {
            client.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val c = r.code
                    val msg = Parse.errorMessage(body)
                    val auth = c == 401 || c == 403 ||
                        (c == 400 && AUTH_HINT.containsMatchIn(msg + " " + body.take(600)))
                    throw ApiException(
                        c, "$name lỗi HTTP $c: $msg",
                        retryable = c == 408 || c == 409 || c == 425 || c == 429 || c >= 500,
                        auth = auth,
                        retryAfterMs = Parse.retryAfterMs(r.header("Retry-After"), body),
                    )
                }
                return body
            }
        } catch (e: IOException) {
            throw ApiException(-1, "$name: lỗi mạng (${e.message ?: e.javaClass.simpleName})", true)
        }
    }
}

/** Danh mục nhà cung cấp AI. Người dùng tự mang API key (giống chế độ "dùng API riêng" của Omi). */
object Providers {
    data class Stt(
        val id: String, val label: String, val defaultModel: String, val diarize: Boolean, val keyId: String, val note: String,
        val models: List<String> = emptyList(),
    )
    data class Llm(val id: String, val label: String, val defaultModel: String, val models: List<String>, val keyId: String, val note: String)

    val STT = listOf(
        Stt("gemini", "Google Gemini", "gemini-flash-latest", true, "gemini",
            "Tiếng Việt rất tốt, có tách người nói. Có gói miễn phí (giới hạn lượt/ngày) – báo 429 thì đổi model khác.",
            models = listOf("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-2.5-flash")),
        Stt("groq", "Groq Whisper (miễn phí)", "whisper-large-v3-turbo", false, "groq",
            "Miễn phí vài giờ âm thanh mỗi ngày, rất nhanh. Tự nó không tách người nói – AI sẽ gán người nói khi tóm tắt.",
            models = listOf("whisper-large-v3-turbo", "whisper-large-v3")),
        Stt("deepgram", "Deepgram", "nova-2", true, "deepgram",
            "Có tách người nói, nhanh. Có tín dụng dùng thử khi đăng ký.", models = listOf("nova-2", "nova-3")),
        Stt("openai", "OpenAI", "gpt-4o-transcribe-diarize", true, "openai",
            "Chất lượng cao, có tách người nói, tính phí theo phút.",
            models = listOf("gpt-4o-transcribe-diarize", "gpt-4o-transcribe", "whisper-1")),
        Stt("custom", "Máy chủ riêng (chuẩn OpenAI)", "whisper-1", false, "custom",
            "Tự host Whisper (faster-whisper-server, LocalAI…): âm thanh không rời khỏi máy chủ của bạn."),
    )

    val LLM = listOf(
        Llm("gemini", "Google Gemini", "gemini-flash-latest",
            listOf("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-2.5-pro"), "gemini",
            "Dùng chung key với chép lời Gemini."),
        Llm("anthropic", "Claude (Anthropic)", "claude-opus-5-5",
            listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"), "anthropic",
            "Opus 5.5: tóm tắt tốt nhất. Haiku 5.5: rẻ nhất. Claude không chép lời được – mục 1 hãy chọn Groq hoặc Gemini."),
        Llm("deepseek", "DeepSeek", "deepseek-chat", listOf("deepseek-chat", "deepseek-reasoner"), "deepseek",
            "Rẻ, hiểu tiếng Việt & English. DeepSeek không chép lời được – mục 1 hãy chọn Groq hoặc Gemini."),
        Llm("openai", "OpenAI", "gpt-5-mini", listOf("gpt-5-mini", "gpt-5", "gpt-4.1-mini"), "openai", ""),
        Llm("groq", "Groq (miễn phí)", "openai/gpt-oss-120b",
            listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile"), "groq", "Gói miễn phí có giới hạn theo ngày."),
        Llm("custom", "Máy chủ riêng (chuẩn OpenAI)", "llama3.1", emptyList(), "custom", "Ollama / LM Studio / vLLM trên máy của bạn."),
    )

    fun stt(id: String) = STT.firstOrNull { it.id == id } ?: STT[0]
    fun llm(id: String) = LLM.firstOrNull { it.id == id } ?: LLM[0]
    fun sttModel(s: Settings) = s.sttModel.ifBlank { stt(s.sttProvider).defaultModel }
    fun llmModel(s: Settings) = s.llmModel.ifBlank { llm(s.llmProvider).defaultModel }

    fun openAiBase(id: String, s: Settings): String = when (id) {
        "openai" -> "https://api.openai.com/v1"
        "groq" -> "https://api.groq.com/openai/v1"
        "deepseek" -> "https://api.deepseek.com/v1"
        else -> s.customBaseUrl.trim().trimEnd('/')
    }

    fun sttReady(s: Settings): Boolean {
        val p = stt(s.sttProvider)
        return if (p.id == "custom") s.customBaseUrl.isNotBlank() else s.key(p.keyId).isNotBlank()
    }

    fun llmReady(s: Settings): Boolean {
        val p = llm(s.llmProvider)
        return if (p.id == "custom") s.customBaseUrl.isNotBlank() else s.key(p.keyId).isNotBlank()
    }

    fun keyLabel(keyId: String): String = when (keyId) {
        "gemini" -> "Gemini API key"
        "groq" -> "Groq API key"
        "deepgram" -> "Deepgram API key"
        "openai" -> "OpenAI API key"
        "anthropic" -> "Claude (Anthropic) API key"
        "deepseek" -> "DeepSeek API key"
        "custom" -> "API key máy chủ riêng (có thể để trống)"
        else -> "API key"
    }

    fun keyHelp(keyId: String): String = when (keyId) {
        "gemini" -> "Lấy key tại aistudio.google.com → Get API key."
        "groq" -> "Lấy key miễn phí tại console.groq.com → API Keys."
        "deepgram" -> "Lấy key tại console.deepgram.com."
        "openai" -> "Lấy key tại platform.openai.com/api-keys."
        "anthropic" -> "Lấy key tại platform.claude.com → API Keys (tính phí theo lượt dùng, tách biệt gói Claude Pro)."
        "deepseek" -> "Lấy key tại platform.deepseek.com → API keys."
        "custom" -> "Máy chủ cần có /v1/audio/transcriptions và/hoặc /v1/chat/completions."
        else -> ""
    }
}
