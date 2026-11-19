package vn.nghenho.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import vn.nghenho.data.ActionItem
import vn.nghenho.data.CalEvent
import vn.nghenho.data.ChatMsg
import vn.nghenho.data.Conversation
import vn.nghenho.data.Segment
import vn.nghenho.util.Fmt
import vn.nghenho.util.str
import java.time.ZoneId

data class TSeg(val speaker: String, val startSec: Double, val endSec: Double, val text: String)
data class ActionDraft(val text: String, val owner: String, val due: String)
data class EventDraft(val title: String, val start: String, val end: String, val location: String, val notes: String)
data class Insight(
    val title: String,
    val emoji: String,
    val category: String,
    val overview: String,
    val keyPoints: List<String>,
    val actions: List<ActionDraft>,
    val events: List<EventDraft>,
    val mindmap: JsonObject?,
    val meaningful: Boolean,
    /** Người nói cho từng dòng (khi nhà cung cấp chép lời không tự tách) */
    val speakerLabels: List<String> = emptyList(),
)

/** Đọc phản hồi của các nhà cung cấp (thuần Kotlin, có unit test). */
object Parse {
    val JSON = Json { isLenient = true; ignoreUnknownKeys = true }

    private fun raw(o: JsonObject?, k: String): String? = (o?.get(k) as? JsonPrimitive)?.contentOrNull

    private fun num(o: JsonObject, k: String): Double {
        val p = o[k] as? JsonPrimitive ?: return 0.0
        p.doubleOrNull?.let { return it }
        // "01:05" hoặc "00:01:05"
        val parts = p.content.trim().split(':').mapNotNull { it.trim().toDoubleOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0.0
        }
    }

    fun errorMessage(body: String): String = runCatching {
        val root = JSON.parseToJsonElement(body) as? JsonObject ?: return@runCatching body
        when (val err = root["error"]) {
            is JsonObject -> err.str("message").ifBlank { err.toString() }
            is JsonPrimitive -> err.content
            else -> root.str("message").ifBlank { root.str("err_msg") }.ifBlank { body }
        }
    }.getOrDefault(body).take(300)

    /** Lấy phần JSON trong câu trả lời (bỏ ```json … ``` nếu có). */
    fun extractJson(text: String): String {
        val t = text.trim().removePrefix(Char(0xFEFF).toString())
        val objStart = t.indexOf('{')
        val arrStart = t.indexOf('[')
        val useArray = arrStart >= 0 && (objStart < 0 || arrStart < objStart)
        val s = if (useArray) arrStart else objStart
        val e = if (useArray) t.lastIndexOf(']') else t.lastIndexOf('}')
        return if (s >= 0 && e > s) t.substring(s, e + 1) else t
    }

    fun speakerLabel(rawLabel: String?): String {
        val s = rawLabel?.trim().orEmpty()
        if (s.isEmpty()) return ""
        Regex("^(?:speaker[_ ]?)?(\\d+)$", RegexOption.IGNORE_CASE).matchEntire(s)?.let {
            val n = it.groupValues[1].toInt()
            return "Người " + if (n in 0..25) ('A' + n).toString() else (n + 1).toString()
        }
        Regex("^(?:speaker[_ ]?)?([A-Za-z])$", RegexOption.IGNORE_CASE).matchEntire(s)?.let {
            return "Người " + it.groupValues[1].uppercase()
        }
        return s
    }

    // ── Gemini ──
    fun geminiText(rawBody: String): String {
        val root = JSON.parseToJsonElement(rawBody) as? JsonObject ?: throw ApiException(200, "Gemini trả về dữ liệu lạ", false)
        val cands = root["candidates"] as? JsonArray
        if (cands.isNullOrEmpty()) {
            val block = (root["promptFeedback"] as? JsonObject).str("blockReason")
            throw ApiException(200, "Gemini không trả lời" + if (block.isNotBlank()) " (bị chặn: $block)" else "", false)
        }
        val c0 = cands[0] as? JsonObject
        val parts = ((c0?.get("content") as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
        val text = parts.mapNotNull { p ->
            val po = p as? JsonObject ?: return@mapNotNull null
            if ((po["thought"] as? JsonPrimitive)?.booleanOrNull == true) null else raw(po, "text")
        }.joinToString("")
        if (text.isBlank()) throw ApiException(200, "Gemini trả về rỗng (finishReason=${c0.str("finishReason")})", false)
        return text
    }

    fun geminiTranscript(rawBody: String): List<TSeg> = transcriptJson(geminiText(rawBody))

    fun transcriptJson(text: String): List<TSeg> {
        val el: JsonElement = runCatching { JSON.parseToJsonElement(extractJson(text)) }.getOrNull()
            ?: return if (text.isBlank()) emptyList() else listOf(TSeg("", 0.0, 0.0, text.trim()))
        val arr: List<JsonElement> = when (el) {
            is JsonArray -> el
            is JsonObject -> (el["segments"] as? JsonArray) ?: (el["utterances"] as? JsonArray) ?: emptyList()
            else -> emptyList()
        }
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val t = o.str("text")
            if (t.isBlank()) null else TSeg(speakerLabel(o.str("speaker")), num(o, "start"), num(o, "end"), t)
        }
    }

    // ── OpenAI / Groq / máy chủ riêng ──
    fun openAiTranscript(rawBody: String): List<TSeg> {
        val o = JSON.parseToJsonElement(rawBody) as? JsonObject ?: return emptyList()
        val segs = o["segments"] as? JsonArray
        if (!segs.isNullOrEmpty()) {
            return segs.mapNotNull { e ->
                val s = e as? JsonObject ?: return@mapNotNull null
                val t = s.str("text")
                if (t.isBlank()) null else TSeg(speakerLabel(s.str("speaker")), num(s, "start"), num(s, "end"), t)
            }
        }
        val text = o.str("text")
        return if (text.isBlank()) emptyList() else listOf(TSeg("", 0.0, 0.0, text))
    }

    fun openAiChatText(rawBody: String): String {
        val o = JSON.parseToJsonElement(rawBody) as? JsonObject ?: throw ApiException(200, "AI trả về dữ liệu lạ", false)
        val msg = ((o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
        val text = when (val content = msg?.get("content")) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.joinToString("") { raw(it as? JsonObject, "text").orEmpty() }
            else -> ""
        }
        if (text.isBlank()) throw ApiException(200, "AI trả về rỗng", false)
        return text
    }

    // ── Deepgram ──
    fun deepgramTranscript(rawBody: String): List<TSeg> {
        val root = JSON.parseToJsonElement(rawBody) as? JsonObject ?: return emptyList()
        val results = root["results"] as? JsonObject ?: return emptyList()
        val utt = results["utterances"] as? JsonArray
        if (!utt.isNullOrEmpty()) {
            return utt.mapNotNull { u ->
                val o = u as? JsonObject ?: return@mapNotNull null
                val t = o.str("transcript")
                if (t.isBlank()) null else TSeg(speakerLabel(o.str("speaker")), num(o, "start"), num(o, "end"), t)
            }
        }
        val alt = (((results["channels"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("alternatives") as? JsonArray)
            ?.firstOrNull() as? JsonObject
        val t = alt.str("transcript")
        return if (t.isBlank()) emptyList() else listOf(TSeg("", 0.0, 0.0, t))
    }

    // ── Claude ──
    fun claudeText(rawBody: String): String {
        val o = JSON.parseToJsonElement(rawBody) as? JsonObject ?: throw ApiException(200, "Claude trả về dữ liệu lạ", false)
        val stop = o.str("stop_reason")
        if (stop == "refusal") {
            val cat = (o["stop_details"] as? JsonObject).str("category")
            throw ApiException(200, "Claude từ chối xử lý nội dung này" + if (cat.isNotBlank()) " ($cat)" else "", false)
        }
        val text = (o["content"] as? JsonArray).orEmpty().mapNotNull { b ->
            val bo = b as? JsonObject
            if (bo.str("type") == "text") raw(bo, "text") else null
        }.joinToString("")
        if (text.isBlank()) throw ApiException(200, "Claude trả về rỗng (stop_reason=$stop)", false)
        return text
    }

    // ── Làm sạch bản chép lời ──
    private val HALLUCINATIONS = listOf(
        "hãy subscribe", "đăng ký kênh", "đăng kí kênh", "subscribe cho kênh", "ghiền mì gõ", "cảm ơn các bạn đã theo dõi",
        "cảm ơn các bạn đã xem", "hẹn gặp lại các bạn trong những video", "like và share", "like, share", "các bạn hãy đăng ký",
        "thanks for watching", "thank you for watching", "please subscribe", "la la school",
    )

    fun cleanSegments(list: List<TSeg>): List<TSeg> {
        val out = ArrayList<TSeg>()
        val ws = Regex("\\s+")
        val noise = Regex("^[\\[(（].{0,40}[\\])）]$")
        val punct = Regex("^[\\p{P}\\s…]+$")
        for (s in list) {
            val t = s.text.trim().replace(ws, " ")
            if (t.isEmpty() || punct.matches(t) || noise.matches(t)) continue
            val low = t.lowercase()
            if (t.length < 160 && HALLUCINATIONS.any { it in low }) continue
            if (out.isNotEmpty() && out.last().text.equals(t, ignoreCase = true)) continue
            out += s.copy(text = t)
        }
        return out
    }

    // ── Tóm tắt ──
    fun insight(rawText: String): Insight {
        val o = JSON.parseToJsonElement(extractJson(rawText)) as? JsonObject
            ?: throw IllegalArgumentException("AI không trả về JSON hợp lệ")
        fun strList(k: String) = (o[k] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull?.trim() ?: (it as? JsonObject)?.str("text")
        }.filter { it.isNotBlank() }
        val actions = (o["action_items"] as? JsonArray).orEmpty().mapNotNull { e ->
            when (e) {
                is JsonObject -> e.str("text").takeIf { it.isNotBlank() }?.let { ActionDraft(it, e.str("owner"), e.str("due")) }
                is JsonPrimitive -> e.contentOrNull?.trim()?.takeIf { it.isNotBlank() }?.let { ActionDraft(it, "", "") }
                else -> null
            }
        }
        val events = (o["events"] as? JsonArray).orEmpty().mapNotNull { e ->
            val eo = e as? JsonObject ?: return@mapNotNull null
            val title = eo.str("title")
            val start = eo.str("start")
            if (title.isBlank() || start.isBlank()) null else EventDraft(title, start, eo.str("end"), eo.str("location"), eo.str("notes"))
        }
        val meaningful = (o["is_meaningful"] as? JsonPrimitive)?.booleanOrNull ?: true
        return Insight(
            o.str("title"), o.str("emoji"), o.str("category"), o.str("overview"),
            strList("key_points"), actions, events, o["mindmap"] as? JsonObject, meaningful,
            (o["speaker_labels"] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.contentOrNull?.trim().orEmpty() },
        )
    }

    /** Thời gian chờ khi bị giới hạn tốc độ: header Retry-After (giây) hoặc Gemini "retryDelay": "37s" / "retry in 37.5s". */
    fun retryAfterMs(header: String?, body: String): Long? {
        val max = 6 * 3_600_000L // không bao giờ đứng chờ quá 6 giờ
        header?.trim()?.toLongOrNull()?.let { return (it * 1000).coerceIn(0, max) }
        Regex("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"").find(body)?.let {
            return (it.groupValues[1].toDouble() * 1000).toLong().coerceIn(0, max)
        }
        // Groq/OpenAI: "Please try again in 1m12.5s" · Gemini: "Please retry in 37.5s"
        Regex("(?:retry|try again) in (?:(\\d+)m)?(\\d+(?:\\.\\d+)?)\\s*s", RegexOption.IGNORE_CASE).find(body)?.let {
            val min = it.groupValues[1].toLongOrNull() ?: 0L
            return (min * 60_000 + (it.groupValues[2].toDouble() * 1000).toLong()).coerceIn(0, max)
        }
        return null
    }
}

/** Lời nhắc (prompt) cho AI. */
object Prompts {
    val CATEGORIES = listOf("Công việc", "Học tập", "Ý tưởng", "Cá nhân", "Gia đình", "Sức khỏe", "Tài chính", "Mua sắm", "Giải trí", "Khác")

    fun transcribe(lang: String, userName: String): String = buildString {
        appendLine("Transcribe this audio clip verbatim.")
        appendLine("Rules:")
        appendLine("- Keep the original spoken language. Vietnamese must be written with complete and correct diacritics (tiếng Việt có dấu đầy đủ). Keep English words and names as spoken.")
        appendLine("- Split into speaker turns and label speakers consistently within this clip: the person closest to the microphone (loudest and clearest, usually the phone owner) is \"Bạn\"; other people are \"Người A\", \"Người B\", … in order of first appearance. If only one person speaks, use \"Bạn\".")
        if (userName.isNotBlank()) appendLine("- The phone owner's name is \"$userName\".")
        appendLine("- \"start\" and \"end\" are seconds from the beginning of the clip.")
        appendLine("- Do not summarize, translate or add anything. Skip music, TV, background noise and unintelligible sounds.")
        appendLine("- If there is no intelligible speech, return an empty \"segments\" array.")
        when (lang) {
            "vi" -> appendLine("- The main language is Vietnamese.")
            "en" -> appendLine("- The main language is English.")
            else -> appendLine("- Speech may be Vietnamese, English, or a mix of both in one sentence. Write every word in the language it was spoken; never translate.")
        }
    }

    val GEMINI_TRANSCRIPT_SCHEMA: JsonObject = Parse.JSON.parseToJsonElement(
        """
        {"type":"OBJECT","properties":{"segments":{"type":"ARRAY","items":{"type":"OBJECT","properties":{
          "speaker":{"type":"STRING"},"start":{"type":"NUMBER"},"end":{"type":"NUMBER"},"text":{"type":"STRING"}},
          "required":["speaker","start","text"]}}},"required":["segments"]}
        """.trimIndent()
    ) as JsonObject

    /** JSON Schema cho structured outputs của Claude (output_config.format). */
    val SUMMARY_SCHEMA: JsonObject = Parse.JSON.parseToJsonElement(
        """
        {"type":"object","additionalProperties":false,
         "required":["title","emoji","category","overview","key_points","action_items","events","mindmap","is_meaningful","speaker_labels"],
         "properties":{
          "title":{"type":"string"},
          "emoji":{"type":"string"},
          "category":{"type":"string","enum":[__CATS__]},
          "overview":{"type":"string"},
          "key_points":{"type":"array","items":{"type":"string"}},
          "action_items":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["text","owner","due"],
             "properties":{"text":{"type":"string"},"owner":{"type":"string"},"due":{"type":"string"}}}},
          "events":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["title","start","end","location","notes"],
             "properties":{"title":{"type":"string"},"start":{"type":"string"},"end":{"type":"string"},"location":{"type":"string"},"notes":{"type":"string"}}}},
          "mindmap":{"type":"object","additionalProperties":false,"required":["label","children"],"properties":{
             "label":{"type":"string"},
             "children":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["label","children"],"properties":{
                "label":{"type":"string"},
                "children":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["label"],"properties":{"label":{"type":"string"}}}}}}}}},
          "is_meaningful":{"type":"boolean"},
          "speaker_labels":{"type":"array","items":{"type":"string"}}
         }}
        """.trimIndent().replace("__CATS__", CATEGORIES.joinToString(",") { "\"$it\"" })
    ) as JsonObject

    fun summarySystem(lang: String): String {
        val outLang = when (lang) {
            "en" -> "English"
            "same" -> "đúng ngôn ngữ chính của cuộc trò chuyện (tiếng Việt hoặc English)"
            else -> "tiếng Việt"
        }
        val cats = CATEGORIES.joinToString(", ") { "\"$it\"" }
        return """
Bạn là Flowme – trợ lý trí nhớ cá nhân. Điện thoại của người dùng ghi âm suốt ngày; bạn nhận bản chép lời của MỘT cuộc hội thoại (có thể là cuộc họp, bài giảng, trò chuyện, hoặc người dùng tự nói ra ý tưởng). Bản chép lời do máy tạo nên có thể sai chính tả hoặc nhầm người nói – hãy hiểu theo ngữ cảnh.

Trả về DUY NHẤT một đối tượng JSON (không markdown, không giải thích) gồm:
- "title": tiêu đề ngắn (tối đa 8 từ) nêu đúng chủ đề.
- "emoji": một emoji phù hợp.
- "category": một trong $cats.
- "overview": tóm tắt 2–5 câu: ai nói gì, kết luận/quyết định chính.
- "key_points": 3–8 ý chính, mỗi ý một câu. Bài giảng: các kiến thức cần ôn. Ý tưởng: diễn đạt lại rõ ràng, giữ nguyên ý người nói.
- "action_items": các việc cụ thể cần làm được nhắc tới, mỗi việc {"text", "owner", "due"}; owner = "Bạn" nếu là việc của người dùng, hoặc tên người phụ trách, hoặc ""; due = "YYYY-MM-DDTHH:mm" nếu suy ra được, ngược lại "".
- "events": lịch hẹn/cuộc gặp/sự kiện CỤ THỂ sắp tới có ngày giờ suy ra được, mỗi mục {"title", "start", "end", "location", "notes"}. Quy đổi thời gian tương đối ("3 giờ chiều mai", "thứ Sáu tuần sau") thành thời điểm tuyệt đối dựa trên thời điểm bắt đầu hội thoại. start/end dạng "YYYY-MM-DDTHH:mm" (end = "" nếu không rõ). Không bịa sự kiện; không có thì [].
- "mindmap": sơ đồ tư duy {"label": chủ đề trung tâm, "children": [3–7 nhánh {"label", "children": [0–5 ý {"label"}]}]}.
- "is_meaningful": false nếu bản ghi chỉ là tạp âm, tiếng TV/nhạc, câu rời rạc vô nghĩa hoặc quá ít nội dung; ngược lại true.
- "speaker_labels": CHỈ khi bản chép lời có các dòng đánh số #1, #2… và chưa có tên người nói: dựa vào nội dung (xưng hô, hỏi–đáp, đổi lượt lời) gán người nói cho TỪNG dòng theo đúng thứ tự – "Bạn" (người dùng), "Người A", "Người B"… hoặc tên riêng nếu biết; mảng phải có đúng bằng số dòng. Nếu không có dòng đánh số thì trả [].

Viết bằng $outLang (giữ nguyên tên riêng và thuật ngữ tiếng Anh). Nhãn "Bạn" trong bản ghi là người dùng.
""".trim()
    }

    fun summaryUser(
        startedAt: Long, endedAt: Long, transcript: String, userName: String,
        zone: ZoneId = ZoneId.systemDefault(), numberedLines: Int = 0,
    ): String {
        val z = Fmt.zdt(startedAt, zone)
        val offset = z.offset.id.let { if (it == "Z") "+00:00" else it }
        return buildString {
            appendLine("Thời điểm bắt đầu: ${Fmt.dateTimeFull(startedAt, zone)} (múi giờ ${zone.id}, UTC$offset)")
            appendLine("Thời lượng: ${Fmt.duration(endedAt - startedAt)}")
            appendLine("Người dùng: ${userName.ifBlank { "(chưa đặt tên)" }} — nhãn \"Bạn\" trong bản ghi là người dùng.")
            if (numberedLines > 0) {
                appendLine("Bản chép lời có $numberedLines dòng đánh số #1…#$numberedLines và CHƯA tách người nói – hãy điền speaker_labels đúng $numberedLines phần tử.")
            }
            appendLine()
            appendLine("BẢN CHÉP LỜI:")
            append(transcript)
        }
    }

    fun chatSystem(): String = """
Bạn là Flowme – trợ lý trí nhớ cá nhân của người dùng. Bạn được xem KÝ ỨC: tóm tắt và trích đoạn các cuộc hội thoại mà điện thoại của người dùng đã ghi lại.
- Trả lời bằng đúng ngôn ngữ của câu hỏi (tiếng Việt hoặc English), ngắn gọn, đi thẳng vào ý; dùng gạch đầu dòng khi liệt kê.
- Chỉ dựa trên KÝ ỨC được cung cấp. Nếu không có thông tin, nói rõ là không thấy trong các bản ghi.
- Khi dẫn chứng, ghi kèm thời điểm dạng [dd/MM HH:mm].
- "Bạn" trong bản ghi chính là người dùng.
""".trim()

    fun chatUser(
        question: String, now: Long, memory: String, open: List<ActionItem>, upcoming: List<CalEvent>, history: List<ChatMsg>,
        pendingNote: String = "",
    ): String = buildString {
        appendLine("Bây giờ là: ${Fmt.dateTimeFull(now)}")
        if (pendingNote.isNotBlank()) appendLine(pendingNote)
        if (open.isNotEmpty()) {
            appendLine(); appendLine("VIỆC CHƯA XONG:")
            open.forEach { a ->
                appendLine("- ${a.text}" + (if (a.owner.isNotBlank()) " (${a.owner})" else "") + (if (a.due.isNotBlank()) " – hạn ${a.due}" else ""))
            }
        }
        if (upcoming.isNotEmpty()) {
            appendLine(); appendLine("LỊCH HẸN SẮP TỚI:")
            upcoming.forEach { e -> appendLine("- ${Fmt.dateTime(e.startAt)}: ${e.title}" + if (e.location.isNotBlank()) " @ ${e.location}" else "") }
        }
        appendLine(); appendLine("KÝ ỨC:")
        appendLine(memory.ifBlank { "(chưa có cuộc hội thoại nào được tóm tắt)" })
        if (history.isNotEmpty()) {
            appendLine(); appendLine("TRAO ĐỔI GẦN ĐÂY:")
            history.forEach { m -> appendLine((if (m.role == "user") "Người dùng: " else "Flowme: ") + m.text.take(600)) }
        }
        appendLine(); append("CÂU HỎI: "); append(question)
    }

    fun memoryBlock(c: Conversation, actions: List<ActionItem>, hits: List<Segment>): String = buildString {
        append("### [${Fmt.dm(c.startedAt)} ${Fmt.hm(c.startedAt)}–${Fmt.hm(c.endedAt)}] ${c.emoji} ${c.title}")
        if (c.category.isNotBlank()) append(" (${c.category})")
        appendLine()
        if (c.overview.isNotBlank()) appendLine("Tóm tắt: ${c.overview}")
        val kp = keyPoints(c.keyPoints)
        if (kp.isNotEmpty()) appendLine("Ý chính: " + kp.joinToString("; "))
        if (actions.isNotEmpty()) {
            appendLine("Việc: " + actions.joinToString("; ") { a -> a.text + (if (a.owner.isNotBlank()) " (${a.owner})" else "") + (if (a.done) " [xong]" else "") })
        }
        if (hits.isNotEmpty()) {
            appendLine("Trích đoạn:")
            hits.sortedBy { it.startAt }.take(12).forEach { s ->
                appendLine("[${Fmt.hm(s.startAt)}] " + (if (s.speaker.isNotBlank()) "${s.speaker}: " else "") + s.text.take(400))
            }
        }
        appendLine()
    }

    fun keyPoints(json: String): List<String> = runCatching {
        (Parse.JSON.parseToJsonElement(json) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }.getOrDefault(emptyList())
}
