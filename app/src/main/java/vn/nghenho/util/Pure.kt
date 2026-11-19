package vn.nghenho.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import vn.nghenho.data.Segment
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Các hàm thuần (không phụ thuộc Android) — có unit test. */

fun JsonObject?.str(k: String): String = ((this?.get(k)) as? JsonPrimitive)?.contentOrNull?.trim() ?: ""

object Fmt {
    private val DOW = arrayOf("Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy", "Chủ Nhật")
    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private val HMS = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val DM = DateTimeFormatter.ofPattern("dd/MM")
    private val DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    fun zdt(ms: Long, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(zone)
    fun dow(d: LocalDate): String = DOW[d.dayOfWeek.value - 1]
    fun hm(ms: Long): String = zdt(ms).format(HM)
    fun hms(ms: Long): String = zdt(ms).format(HMS)
    fun dm(ms: Long): String = zdt(ms).format(DM)

    fun dayHeader(ms: Long, now: Long = System.currentTimeMillis()): String {
        val d = zdt(ms).toLocalDate()
        val today = zdt(now).toLocalDate()
        return when (d) {
            today -> "Hôm nay"
            today.minusDays(1) -> "Hôm qua"
            else -> "${dow(d)}, ${d.format(DMY)}"
        }
    }

    fun dateTime(ms: Long): String {
        val z = zdt(ms)
        return "${dow(z.toLocalDate())} ${z.format(DM)} ${z.format(HM)}"
    }

    fun dateTimeFull(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val z = zdt(ms, zone)
        return "${dow(z.toLocalDate())}, ${z.format(DMY)} ${z.format(HM)}"
    }

    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        return when {
            h > 0 -> "$h giờ $m phút"
            m > 0 -> "$m phút"
            else -> "$s giây"
        }
    }

    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    fun startOfDay(ms: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        zdt(ms, zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /** Bản chép lời dạng "[HH:mm:ss] Người nói: lời…" (gộp lượt nói liền nhau). Quá dài thì lược phần giữa. */
    fun transcript(segs: List<Segment>, maxChars: Int = Int.MAX_VALUE, zone: ZoneId = ZoneId.systemDefault()): String {
        val sb = StringBuilder()
        var lastSpeaker: String? = null
        var lastAt = 0L
        for (s in segs) {
            val t = s.text.trim()
            if (t.isEmpty()) continue
            if (s.speaker == lastSpeaker && s.startAt - lastAt < 60_000 && sb.isNotEmpty()) {
                sb.append(' ').append(t)
            } else {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append('[').append(zdt(s.startAt, zone).format(HMS)).append("] ")
                if (s.speaker.isNotBlank()) sb.append(s.speaker).append(": ")
                sb.append(t)
            }
            lastSpeaker = s.speaker
            lastAt = s.startAt
        }
        if (sb.length <= maxChars) return sb.toString()
        return cut(sb, maxChars)
    }

    /** Bản chép lời đánh số từng câu (#1, #2…) để AI gán người nói khi nhà cung cấp không tự tách. */
    fun numberedTranscript(segs: List<Segment>, zone: ZoneId = ZoneId.systemDefault()): String =
        segs.mapIndexed { i, s -> "#${i + 1} [${zdt(s.startAt, zone).format(HMS)}] ${s.text.trim()}" }.joinToString("\n")

    private fun cut(sb: StringBuilder, maxChars: Int): String {
        val head = (maxChars * 0.6).toInt()
        val tail = maxChars - head
        return sb.substring(0, head) + "\n…(lược bớt phần giữa vì quá dài)…\n" + sb.substring(sb.length - tail)
    }
}

object Times {
    /** Đọc thời điểm AI trả về ("2026-11-06T15:00", có/không múi giờ) => epoch ms. */
    fun parseLocal(s: String?, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val t = s?.trim().orEmpty()
        if (t.isEmpty() || t.equals("null", ignoreCase = true)) return null
        runCatching { return OffsetDateTime.parse(t).toInstant().toEpochMilli() }
        runCatching { return ZonedDateTime.parse(t).toInstant().toEpochMilli() }
        val n = t.replace(' ', 'T')
        runCatching { return LocalDateTime.parse(n).atZone(zone).toInstant().toEpochMilli() }
        for (p in listOf("yyyy-MM-dd'T'H:mm", "yyyy-MM-dd'T'H:mm:ss")) {
            runCatching { return LocalDateTime.parse(n, DateTimeFormatter.ofPattern(p)).atZone(zone).toInstant().toEpochMilli() }
        }
        runCatching { return LocalDate.parse(t.take(10)).atTime(9, 0).atZone(zone).toInstant().toEpochMilli() }
        return null
    }

    fun isoLocal(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Fmt.zdt(ms, zone).toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
}

/** Nhận diện khoảng thời gian trong câu hỏi ("hôm qua", "tuần này"...). Trả về [from, to). */
object DateRange {
    fun detect(q: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long>? {
        val t = q.lowercase()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun start(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
        fun day(d: LocalDate) = start(d) to start(d.plusDays(1))
        val monday = today.with(DayOfWeek.MONDAY)
        val firstOfMonth = today.withDayOfMonth(1)
        return when {
            "hôm kia" in t -> day(today.minusDays(2))
            "hôm qua" in t || "tối qua" in t || "sáng qua" in t || "chiều qua" in t || "yesterday" in t -> day(today.minusDays(1))
            "hôm nay" in t || "sáng nay" in t || "chiều nay" in t || "tối nay" in t || "today" in t -> day(today)
            "tuần trước" in t || "tuần qua" in t || "last week" in t -> start(monday.minusWeeks(1)) to start(monday)
            "tuần này" in t || "this week" in t -> start(monday) to now + 1
            "tháng trước" in t -> start(firstOfMonth.minusMonths(1)) to start(firstOfMonth)
            "tháng này" in t -> start(firstOfMonth) to now + 1
            else -> Regex("(\\d+)\\s*ngày\\s*(qua|trước|gần đây)").find(t)?.let {
                val n = it.groupValues[1].toLong().coerceIn(1, 90)
                start(today.minusDays(n)) to now + 1
            }
        }
    }
}

/** Tách từ khoá tìm kiếm từ câu hỏi tiếng Việt (ưu tiên cụm 2 tiếng). */
object Keywords {
    private val STOP = setOf(
        "tôi", "mình", "tao", "em", "anh", "chị", "bạn", "của", "là", "có", "không", "gì", "nào", "đã", "đang", "sẽ",
        "với", "cho", "và", "này", "đó", "kia", "những", "các", "một", "được", "thì", "mà", "ở", "về", "khi", "nói",
        "hôm", "nay", "qua", "bao", "giờ", "ai", "sao", "thế", "nhé", "ạ", "à", "ơi", "nhỉ", "hả", "chưa", "rồi", "lúc",
        "ra", "vào", "lại", "cũng", "như", "để", "nên", "phải", "làm", "hay", "hoặc", "từ", "đến", "tới", "trong",
        "ngoài", "trên", "dưới", "nhiều", "ít", "rất", "quá", "tuần", "tháng", "ngày", "năm", "sáng", "chiều", "tối",
        "đâu", "bị", "hãy", "giúp", "tóm", "tắt", "liệt", "kê", "hỏi", "nhắc", "biết", "xem", "nhớ", "điều", "chuyện",
        "the", "a", "an", "is", "are", "what", "did", "do", "i", "me", "my", "about", "of", "to", "in", "on", "and",
    )

    fun extract(q: String): List<String> {
        val tokens = q.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        val bigrams = tokens.zipWithNext().filter { (a, b) -> a !in STOP && b !in STOP }.map { "${it.first} ${it.second}" }
        val words = tokens.filter { it.length >= 2 && it !in STOP }
        return (bigrams + words).distinct().take(8)
    }
}

data class MindNode(val label: String, val children: List<MindNode>)

object Mindmap {
    fun fromJson(e: JsonElement?, depth: Int = 0): MindNode? {
        val o = e as? JsonObject ?: return null
        val label = o.str("label").ifBlank { o.str("t") }.ifBlank { o.str("name") }
        if (label.isBlank()) return null
        val arr = (o["children"] as? JsonArray) ?: (o["c"] as? JsonArray)
        val kids = if (depth >= 4) emptyList() else arr?.mapNotNull { fromJson(it, depth + 1) }.orEmpty()
        return MindNode(label, kids)
    }

    fun toJson(n: MindNode): JsonObject = buildJsonObject {
        put("label", n.label)
        putJsonArray("children") { n.children.forEach { add(toJson(it)) } }
    }

    fun outline(n: MindNode, depth: Int = 0, sb: StringBuilder = StringBuilder()): String {
        sb.append("  ".repeat(depth)).append("- ").append(n.label).append('\n')
        n.children.forEach { outline(it, depth + 1, sb) }
        return sb.toString()
    }
}
