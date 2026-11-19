package vn.nghenho.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import vn.nghenho.App
import vn.nghenho.audio.AudioConvert
import vn.nghenho.audio.AudioFiles
import java.io.File
import java.util.Base64

/** Giọng nói → chữ, có phân biệt người nói (tuỳ nhà cung cấp). */
object Transcriber {
    private const val GEMINI = "https://generativelanguage.googleapis.com/v1beta/models"

    suspend fun transcribe(ctx: Context, file: File): List<TSeg> = withContext(Dispatchers.IO) {
        val s = App.settings
        val p = Providers.stt(s.sttProvider)
        val key = s.key(p.keyId)
        if (key.isBlank() && p.id != "custom") throw MissingKeyException("Chưa có API key cho ${p.label}")
        val model = Providers.sttModel(s)
        val segs = when (p.id) {
            "gemini" -> gemini(file, model, key, s.language, s.userName)
            "deepgram" -> deepgram(file, model, key, s.language)
            else -> openAi(ctx, file, model, Providers.openAiBase(p.id, s), key, s.language)
        }
        Parse.cleanSegments(segs)
    }

    private fun gemini(file: File, model: String, key: String, lang: String, userName: String): List<TSeg> {
        if (file.length() > 14L * 1024 * 1024) throw ApiException(413, "Đoạn âm thanh quá lớn cho Gemini", false)
        val b64 = Base64.getEncoder().encodeToString(file.readBytes())
        fun body(withSchema: Boolean) = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject { put("text", Prompts.transcribe(lang, userName)) }
                        addJsonObject {
                            putJsonObject("inlineData") {
                                put("mimeType", AudioFiles.mimeOf(file))
                                put("data", b64)
                            }
                        }
                    }
                }
            }
            // Không đặt temperature: Gemini 3 khuyên giữ mặc định (đặt thấp dễ bị lặp câu)
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                if (withSchema) put("responseSchema", Prompts.GEMINI_TRANSCRIPT_SCHEMA)
            }
        }
        fun req(withSchema: Boolean) = Request.Builder()
            .url("$GEMINI/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body(withSchema).toString().toRequestBody(Http.JSON))
            .build()
        val raw = try {
            Http.call(req(true), "Gemini chép lời")
        } catch (e: ApiException) {
            // Model không nhận responseSchema => vẫn yêu cầu JSON qua prompt
            if (e.code == 400) Http.call(req(false), "Gemini chép lời") else throw e
        }
        return Parse.geminiTranscript(raw)
    }

    private fun deepgram(file: File, model: String, key: String, lang: String): List<TSeg> {
        val url = "https://api.deepgram.com/v1/listen".toHttpUrl().newBuilder()
            .addQueryParameter("model", model)
            .addQueryParameter("diarize", "true")
            .addQueryParameter("punctuate", "true")
            .addQueryParameter("smart_format", "true")
            .addQueryParameter("utterances", "true")
            .apply { if (lang == "vi" || lang == "en") addQueryParameter("language", lang) else addQueryParameter("detect_language", "true") }
            .build()
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Token $key")
            .post(file.asRequestBody(AudioFiles.mimeOf(file).toMediaType()))
            .build()
        return Parse.deepgramTranscript(Http.call(req, "Deepgram"))
    }

    /** Chuẩn OpenAI /audio/transcriptions: OpenAI, Groq, faster-whisper-server, LocalAI… */
    private fun openAi(ctx: Context, file: File, model: String, base: String, key: String, lang: String): List<TSeg> {
        if (base.isBlank()) throw MissingKeyException("Chưa nhập địa chỉ máy chủ riêng")
        val upload = if (file.extension.equals("aac", true)) {
            val out = File(ctx.cacheDir, file.nameWithoutExtension + ".m4a")
            runCatching { AudioConvert.adtsToM4a(file, out); out }.getOrElse { file }
        } else file
        try {
            val diarize = model.contains("diarize")
            val plainJson = !diarize && model.startsWith("gpt-4o")
            val format = when {
                diarize -> "diarized_json"
                plainJson -> "json"
                else -> "verbose_json"
            }
            val b = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", upload.name, upload.asRequestBody(AudioFiles.mimeOf(upload).toMediaType()))
                .addFormDataPart("model", model)
                .addFormDataPart("response_format", format)
            // Việt + English: không ép 1 ngôn ngữ để Whisper giữ nguyên câu tiếng Anh
            if (lang == "vi" || lang == "en") b.addFormDataPart("language", lang)
            if (diarize) b.addFormDataPart("chunking_strategy", "auto")
            else if (!plainJson && lang == "vi") b.addFormDataPart("prompt", "Cuộc trò chuyện bằng tiếng Việt, viết có dấu đầy đủ.")
            else if (!plainJson && lang == "vi_en") b.addFormDataPart("prompt", "Cuộc trò chuyện bằng tiếng Việt có dấu, có xen English.")
            val req = Request.Builder()
                .url("$base/audio/transcriptions")
                .apply { if (key.isNotBlank()) header("Authorization", "Bearer $key") }
                .post(b.build())
                .build()
            return Parse.openAiTranscript(Http.call(req, "Chép lời"))
        } finally {
            if (upload != file) upload.delete()
        }
    }
}
