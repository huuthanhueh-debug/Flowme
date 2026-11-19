package vn.nghenho

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghenho.ai.ApiException
import vn.nghenho.ai.Parse
import vn.nghenho.ai.Prompts
import vn.nghenho.ai.TSeg
import vn.nghenho.audio.Adts
import vn.nghenho.audio.PcmWriter
import vn.nghenho.audio.Segmenter
import vn.nghenho.audio.SegmenterConfig
import vn.nghenho.data.Segment
import vn.nghenho.integrations.Notion
import vn.nghenho.util.DateRange
import vn.nghenho.util.Fmt
import vn.nghenho.util.Keywords
import vn.nghenho.util.Mindmap
import vn.nghenho.util.Times
import vn.nghenho.util.BrandPalette
import vn.nghenho.util.ChatState
import vn.nghenho.util.Contrast
import vn.nghenho.util.Tone
import vn.nghenho.util.ToneRules
import vn.nghenho.data.ChatMsg
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class LogicTest {
    private val sr = 16000
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    // ───────── Segmenter (phát hiện tiếng nói + cắt đoạn) ─────────

    private class FakeWriter : PcmWriter {
        var samples = 0L
        var finished = false
        override fun write(pcm: ShortArray, n: Int) { samples += n }
        override fun finish() { finished = true }
    }

    private class Rec : Segmenter.Sink {
        val opened = mutableListOf<Long>()
        val closed = mutableListOf<Segmenter.ChunkInfo>()
        val writers = mutableListOf<FakeWriter>()
        override fun open(startMs: Long): PcmWriter = FakeWriter().also { writers += it; opened += startMs }
        override fun close(info: Segmenter.ChunkInfo) { closed += info }
    }

    private fun tone(db: Double, rnd: Random): ShortArray {
        val amp = 32768.0 * 10.0.pow(db / 20.0) * sqrt(2.0)
        val f = 150 + rnd.nextInt(200)
        return ShortArray(320) { i -> (amp * sin(2 * PI * f * i / sr)).toInt().coerceIn(-32768, 32767).toShort() }
    }

    private fun noise(db: Double, rnd: Random): ShortArray {
        val a = 32768.0 * 10.0.pow(db / 20.0) * sqrt(3.0)
        return ShortArray(320) { ((rnd.nextDouble() * 2 - 1) * a).toInt().toShort() }
    }

    /** Nền ồn -65 dBFS; "đang nói" = âm tiết 240 ms ở -30 dBFS xen nghỉ 80 ms. */
    private fun feed(seg: Segmenter, startMs: Long, durMs: Long, speaking: Boolean, rnd: Random, loudDb: Double = -30.0): Long {
        var t = startMs
        val end = startMs + durMs
        while (t < end) {
            val inSyllable = speaking && ((t - startMs) % 320) < 240
            seg.onFrame(if (inSyllable) tone(loudDb, rnd) else noise(-65.0, rnd), 320, t)
            t += 20
        }
        return t
    }

    @Test
    fun silenceProducesNoChunk() {
        val rec = Rec()
        val seg = Segmenter(sr, SegmenterConfig(), rec)
        feed(seg, 0, 60_000, false, Random(1))
        assertTrue(rec.opened.isEmpty())
        assertFalse(seg.isOpen)
    }

    @Test
    fun speechBurstMakesOneKeptChunk() {
        val rec = Rec()
        val seg = Segmenter(sr, SegmenterConfig(), rec)
        val rnd = Random(2)
        var t = feed(seg, 0, 5_000, false, rnd)
        t = feed(seg, t, 20_000, true, rnd)
        feed(seg, t, 10_000, false, rnd)
        assertEquals(1, rec.opened.size)
        assertEquals(1, rec.closed.size)
        val c = rec.closed[0]
        assertTrue("phải giữ đoạn", c.keep)
        assertTrue("voiced=${c.voicedMs}", c.voicedMs in 12_000..20_000)
        assertTrue("start=${c.startMs}", c.startMs in 4_000..5_100)          // có pre-roll ~0,8 s
        assertTrue("dur=${c.durationMs}", c.durationMs in 25_000..28_500)    // 20 s nói + 6 s im lặng để đóng
        assertTrue(rec.writers[0].finished)
        assertEquals(c.durationMs, rec.writers[0].samples * 1000 / sr)
    }

    @Test
    fun shortClickIsDiscarded() {
        val rec = Rec()
        val seg = Segmenter(sr, SegmenterConfig(), rec)
        val rnd = Random(3)
        var t = feed(seg, 0, 3_000, false, rnd)
        repeat(5) { seg.onFrame(tone(-20.0, rnd), 320, t); t += 20 } // tiếng động 100 ms
        feed(seg, t, 10_000, false, rnd)
        assertEquals(1, rec.closed.size)
        assertFalse(rec.closed[0].keep)
    }

    @Test
    fun longTalkIsCutAtNaturalPauseAfterMinLength() {
        val rec = Rec()
        val seg = Segmenter(sr, SegmenterConfig(), rec)
        val rnd = Random(4)
        var t = feed(seg, 0, 65_000, true, rnd)
        t = feed(seg, t, 1_500, false, rnd)   // ngắt nghỉ 1,5 s sau khi đã > 60 s
        t = feed(seg, t, 10_000, true, rnd)
        feed(seg, t, 10_000, false, rnd)
        assertEquals(2, rec.closed.size)
        assertTrue("dur0=${rec.closed[0].durationMs}", rec.closed[0].durationMs in 60_000..68_000)
        assertTrue(rec.closed.all { it.keep })
    }

    @Test
    fun continuousTalkIsCutAtMaxLength() {
        val rec = Rec()
        val seg = Segmenter(sr, SegmenterConfig(maxChunkMs = 30_000, minChunkMs = 200_000), rec)
        feed(seg, 0, 70_000, true, Random(5))
        seg.closeIfOpen()
        assertTrue(rec.closed.size >= 3)
        assertTrue(rec.closed.all { it.durationMs <= 30_020 })
    }

    @Test
    fun adtsHeaderBits() {
        val h = Adts.header(100, 16000)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x60, 0x40, 0x0D, 0x7F, 0xFC.toByte()), h)
    }

    // ───────── Đọc phản hồi nhà cung cấp ─────────

    @Test
    fun geminiTranscriptParsed() {
        val inner = """{"segments":[{"speaker":"Bạn","start":0.5,"end":2.1,"text":"Chào anh Minh, mai họp lúc 3 giờ nhé"},{"speaker":"Người A","start":"00:03","text":"Ok em"}]}"""
        val raw = buildJsonObject {
            putJsonArray("candidates") {
                addJsonObject {
                    putJsonObject("content") {
                        putJsonArray("parts") {
                            addJsonObject { put("text", "nghĩ…"); put("thought", true) }
                            addJsonObject { put("text", "```json\n$inner\n```") }
                        }
                    }
                }
            }
        }.toString()
        val segs = Parse.geminiTranscript(raw)
        assertEquals(2, segs.size)
        assertEquals("Bạn", segs[0].speaker)
        assertEquals("Chào anh Minh, mai họp lúc 3 giờ nhé", segs[0].text)
        assertEquals(3.0, segs[1].startSec, 0.001)
        assertEquals("Người A", segs[1].speaker)
    }

    @Test
    fun geminiBlockedThrows() {
        val raw = """{"promptFeedback":{"blockReason":"SAFETY"}}"""
        val e = assertThrows(ApiException::class.java) { Parse.geminiText(raw) }
        assertTrue(e.message!!.contains("SAFETY"))
    }

    @Test
    fun openAiDiarizedParsed() {
        val raw = """{"text":"Xin chào Chào bạn","segments":[{"type":"transcript.text.segment","id":"seg_1","speaker":"A","start":0.0,"end":1.5,"text":"Xin chào"},{"speaker":"B","start":1.6,"end":3.0,"text":"Chào bạn"}]}"""
        val segs = Parse.openAiTranscript(raw)
        assertEquals(listOf("Người A", "Người B"), segs.map { it.speaker })
        assertEquals(1.6, segs[1].startSec, 0.001)
    }

    @Test
    fun openAiPlainTextParsed() {
        val segs = Parse.openAiTranscript("""{"text":"Một câu duy nhất"}""")
        assertEquals(1, segs.size)
        assertEquals("", segs[0].speaker)
    }

    @Test
    fun deepgramUtterancesParsed() {
        val raw = """{"results":{"utterances":[{"start":0.1,"end":1.2,"transcript":"alo","speaker":0},{"start":1.3,"end":2.0,"transcript":"dạ","speaker":1}]}}"""
        val segs = Parse.deepgramTranscript(raw)
        assertEquals(listOf("Người A", "Người B"), segs.map { it.speaker })
    }

    @Test
    fun claudeTextAndRefusal() {
        val ok = """{"content":[{"type":"thinking","thinking":""},{"type":"text","text":"{\"title\":\"x\"}"}],"stop_reason":"end_turn"}"""
        assertEquals("{\"title\":\"x\"}", Parse.claudeText(ok))
        val refused = """{"content":[],"stop_reason":"refusal","stop_details":{"type":"refusal","category":"cyber"}}"""
        assertThrows(ApiException::class.java) { Parse.claudeText(refused) }
    }

    @Test
    fun openAiChatText() {
        val raw = """{"choices":[{"message":{"role":"assistant","content":"Xin chào"}}]}"""
        assertEquals("Xin chào", Parse.openAiChatText(raw))
    }

    @Test
    fun errorMessagesFromProviders() {
        assertEquals("Quota exceeded", Parse.errorMessage("""{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED"}}"""))
        assertEquals("bad key", Parse.errorMessage("""{"err_code":"INVALID_AUTH","err_msg":"bad key"}"""))
        assertEquals("plain text", Parse.errorMessage("plain text"))
    }

    @Test
    fun whisperHallucinationsAndNoiseFiltered() {
        val out = Parse.cleanSegments(
            listOf(
                TSeg("", 0.0, 1.0, "Hãy subscribe cho kênh Ghiền Mì Gõ để không bỏ lỡ video"),
                TSeg("", 1.0, 2.0, "Hôm nay mình bàn về   ngân sách"),
                TSeg("", 2.0, 3.0, "Hôm nay mình bàn về ngân sách"),
                TSeg("", 3.0, 4.0, "[Âm nhạc]"),
                TSeg("", 4.0, 5.0, "..."),
            )
        )
        assertEquals(1, out.size)
        assertEquals("Hôm nay mình bàn về ngân sách", out[0].text)
    }

    @Test
    fun speakerLabels() {
        assertEquals("Người A", Parse.speakerLabel("0"))
        assertEquals("Người B", Parse.speakerLabel("speaker_1"))
        assertEquals("Người C", Parse.speakerLabel("C"))
        assertEquals("Bạn", Parse.speakerLabel("Bạn"))
        assertEquals("", Parse.speakerLabel(null))
    }

    @Test
    fun insightParsed() {
        val raw = "Đây là kết quả:\n```json\n" +
            """{"title":"Họp ngân sách Q4","emoji":"💰","category":"Công việc","overview":"Bàn ngân sách.","key_points":["Tăng 10%"],""" +
            """"action_items":[{"text":"Gửi báo cáo","owner":"Bạn","due":"2026-11-06T17:00"}],""" +
            """"events":[{"title":"Họp với anh Minh","start":"2026-11-06T15:00","end":"","location":"Phòng 2","notes":""}],""" +
            """"mindmap":{"label":"Ngân sách","children":[{"label":"Doanh thu","children":[{"label":"Tăng 10%"}]}]},"is_meaningful":true}""" +
            "\n```"
        val ins = Parse.insight(raw)
        assertEquals("Họp ngân sách Q4", ins.title)
        assertEquals("Công việc", ins.category)
        assertEquals(listOf("Tăng 10%"), ins.keyPoints)
        assertEquals("Bạn", ins.actions[0].owner)
        assertEquals(1, ins.events.size)
        assertEquals(ms(2026, 11, 6, 15, 0), Times.parseLocal(ins.events[0].start, zone))
        val mm = Mindmap.fromJson(ins.mindmap)!!
        assertEquals("Ngân sách", mm.label)
        assertEquals("Tăng 10%", mm.children[0].children[0].label)
        assertTrue(Mindmap.outline(mm).contains("    - Tăng 10%"))
        assertTrue(ins.meaningful)
    }

    @Test
    fun insightToleratesLooseShapes() {
        val ins = Parse.insight("""{"title":"Ý tưởng","action_items":["Mua quà"],"events":[{"title":"Không giờ"}],"is_meaningful":false}""")
        assertEquals("Mua quà", ins.actions[0].text)
        assertTrue(ins.events.isEmpty())
        assertFalse(ins.meaningful)
    }

    @Test
    fun timeParsingVariants() {
        assertEquals(ms(2026, 11, 6, 15, 0), Times.parseLocal("2026-11-06T15:00:00+07:00", zone))
        assertEquals(ms(2026, 11, 6, 15, 0), Times.parseLocal("2026-11-06 15:00", zone))
        assertEquals(ms(2026, 11, 6, 9, 5), Times.parseLocal("2026-11-06T9:05", zone))
        assertEquals(ms(2026, 11, 6, 9, 0), Times.parseLocal("2026-11-06", zone))
        assertEquals(null, Times.parseLocal("", zone))
        assertEquals(null, Times.parseLocal("chiều mai", zone))
    }

    @Test
    fun dateRanges() {
        val now = ms(2026, 11, 5, 14, 0) // Thứ Năm
        assertEquals(ms(2026, 11, 4, 0, 0) to ms(2026, 11, 5, 0, 0), DateRange.detect("Hôm qua tôi nói gì?", now, zone))
        assertEquals(ms(2026, 11, 5, 0, 0) to ms(2026, 11, 6, 0, 0), DateRange.detect("tóm tắt hôm nay", now, zone))
        assertEquals(ms(2026, 11, 2, 0, 0), DateRange.detect("Tuần này có lịch hẹn nào?", now, zone)!!.first)
        assertEquals(ms(2026, 10, 26, 0, 0) to ms(2026, 11, 2, 0, 0), DateRange.detect("tuần trước họp gì", now, zone))
        assertEquals(ms(2026, 11, 2, 0, 0), DateRange.detect("3 ngày qua", now, zone)!!.first)
        assertEquals(null, DateRange.detect("anh Minh nói gì về ngân sách", now, zone))
    }

    @Test
    fun keywordsExtraction() {
        val k = Keywords.extract("Tuần này anh Minh nói gì về ngân sách?")
        assertTrue(k.toString(), "ngân sách" in k)
        assertTrue(k.toString(), "minh" in k)
        assertFalse(k.toString(), "về" in k)
    }

    @Test
    fun schemasAreValid() {
        assertEquals("object", Prompts.SUMMARY_SCHEMA["type"]!!.jsonPrimitive.content)
        val cats = Prompts.SUMMARY_SCHEMA["properties"]!!.jsonObject["category"]!!.jsonObject["enum"] as JsonArray
        assertEquals(Prompts.CATEGORIES.size, cats.size)
        assertEquals("OBJECT", Prompts.GEMINI_TRANSCRIPT_SCHEMA["type"]!!.jsonPrimitive.content)
        assertTrue(Prompts.summarySystem("vi").contains("tiếng Việt"))
    }

    @Test
    fun summaryPromptCarriesTimezone() {
        val p = Prompts.summaryUser(ms(2026, 11, 5, 14, 32), ms(2026, 11, 5, 14, 55), "[14:32:05] Bạn: xin chào", "Thanh", zone)
        assertTrue(p, p.contains("Thứ Năm, 05/11/2026 14:32"))
        assertTrue(p, p.contains("UTC+07:00"))
        assertTrue(p, p.contains("23 phút"))
    }

    @Test
    fun transcriptMergesConsecutiveTurns() {
        val base = ms(2026, 11, 5, 10, 0)
        val segs = listOf(
            Segment(1, 1, 1, base, base, "Bạn", "Chào"),
            Segment(2, 1, 1, base + 5_000, base + 5_000, "Bạn", "mọi người"),
            Segment(3, 1, 1, base + 10_000, base + 10_000, "Người A", "Chào anh"),
        )
        assertEquals("[10:00:00] Bạn: Chào mọi người\n[10:00:10] Người A: Chào anh", Fmt.transcript(segs, zone = zone))
        val long = Fmt.transcript(segs, maxChars = 30, zone = zone)
        assertTrue(long.contains("lược bớt"))
    }

    @Test
    fun retryAfterParsing() {
        assertEquals(30_000L, Parse.retryAfterMs("30", ""))
        assertEquals(37_500L, Parse.retryAfterMs(null, """{"error":{"code":429,"details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"37.5s"}]}}"""))
        assertEquals(72_500L, Parse.retryAfterMs(null, """{"error":{"message":"Rate limit reached for model whisper-large-v3-turbo. Please try again in 1m12.5s."}}"""))
        assertEquals(2_000L, Parse.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT", "Rate limit reached. Please try again in 2s."))
        assertEquals(6 * 3_600_000L, Parse.retryAfterMs("999999", ""))
        assertEquals(null, Parse.retryAfterMs(null, """{"error":{"message":"Invalid API key"}}"""))
    }

    @Test
    fun speakerLabelsFromSummary() {
        val ins = Parse.insight(
            """{"title":"Họp","emoji":"📅","category":"Công việc","overview":"x","key_points":[],"action_items":[],"events":[],""" +
                """"mindmap":{},"is_meaningful":true,"speaker_labels":["Bạn"," Người A ",""]}"""
        )
        assertEquals(listOf("Bạn", "Người A", ""), ins.speakerLabels)
        assertTrue(Parse.insight("""{"title":"Cũ"}""").speakerLabels.isEmpty()) // phản hồi kiểu cũ vẫn đọc được
        val required = Prompts.SUMMARY_SCHEMA["required"] as JsonArray
        assertTrue(required.any { it.jsonPrimitive.content == "speaker_labels" })
        assertTrue(Prompts.summarySystem("vi").contains("speaker_labels"))
        assertEquals("Người B", Parse.speakerLabel("speaker_1"))
    }

    @Test
    fun numberedTranscriptForSpeakerLabeling() {
        val base = ms(2026, 11, 5, 10, 0)
        val segs = listOf(
            Segment(1, 1, 1, base, base, "", " Mai 9 giờ 30 mình họp nhé "),
            Segment(2, 1, 1, base + 4_000, base + 4_000, "", "OK, see you tomorrow"),
        )
        val t = Fmt.numberedTranscript(segs, zone)
        assertEquals("#1 [10:00:00] Mai 9 giờ 30 mình họp nhé\n#2 [10:00:04] OK, see you tomorrow", t)
        val p = Prompts.summaryUser(base, base + 4_000, t, "Thanh", zone, numberedLines = 2)
        assertTrue(p, p.contains("speaker_labels đúng 2 phần tử"))
        assertFalse(Prompts.summaryUser(base, base + 4_000, "[10:00:00] Bạn: hi", "Thanh", zone).contains("speaker_labels"))
    }

    @Test
    fun brandPaletteMeetsWcagContrast() {
        for ((name, p) in listOf("light" to BrandPalette.Light, "dark" to BrandPalette.Dark)) {
            fun check(label: String, fg: Long, bg: Long, min: Double) {
                val r = Contrast.ratio(fg, bg)
                assertTrue("$name/$label: ${"%.2f".format(r)} < $min", r >= min)
            }
            // Chữ thường: ≥ 4.5 (AA)
            check("onPrimary", p.onPrimary, p.primary, 4.5)
            check("onSurface", p.onSurface, p.surface, 7.0)
            check("onSurfaceVariant/low", p.onSurfaceVariant, p.surfaceLow, 4.5)
            check("onSurfaceVariant/high", p.onSurfaceVariant, p.surfaceHigh, 4.5)
            check("onPrimaryContainer", p.onPrimaryContainer, p.primaryContainer, 4.5)
            check("onTertiaryContainer", p.onTertiaryContainer, p.tertiaryContainer, 4.5)
            check("onSecondaryContainer", p.onSecondaryContainer, p.secondaryContainer, 4.5)
            check("onSecondary", p.onSecondary, p.secondary, 4.5)
            check("onErrorContainer", p.onErrorContainer, p.errorContainer, 4.5)
            check("onWarningContainer", p.onWarningContainer, p.warningContainer, 4.5)
            check("onSuccessContainer", p.onSuccessContainer, p.successContainer, 4.5)
            // Icon / thành phần giao diện: ≥ 3 (AA non-text)
            check("primary/bg", p.primary, p.background, 3.0)
            check("primary/primaryContainer", p.primary, p.primaryContainer, 3.0)
            check("tertiary/container", p.tertiary, p.tertiaryContainer, 3.0)
            check("error/container", p.error, p.errorContainer, 3.0)
            check("warning/container", p.warning, p.warningContainer, 3.0)
            check("success/container", p.success, p.successContainer, 3.0)
            check("outline/surface", p.outline, p.surfaceLowest, 3.0)
        }
        // Chữ trắng "BẬT"/"TẮT" trên nút ghi: đọc được ở mọi điểm dừng của gradient
        for (stop in BrandPalette.BRAND_GRADIENT + BrandPalette.LIVE_GRADIENT + BrandPalette.PAUSE_GRADIENT) {
            assertTrue("gradient ${stop.toString(16)}", Contrast.ratio(0xFFFFFFFF, stop) >= 4.5)
        }
        assertEquals(21.0, Contrast.ratio(0xFF000000, 0xFFFFFFFF), 0.01)
    }

    @Test
    fun infoCardToneFromLeadingSymbol() {
        assertEquals(Tone.WARNING, ToneRules.of("⚠️ Key bị từ chối"))
        assertEquals(Tone.WARNING, ToneRules.of("⚠ không có variation selector"))
        assertEquals(Tone.ERROR, ToneRules.of("❗ 2 đoạn chép lời bị lỗi."))
        assertEquals(Tone.PENDING, ToneRules.of("  ⏳ 3 đoạn đang chờ"))
        assertEquals(Tone.SUCCESS, ToneRules.of("✅ Kết nối được"))
        assertEquals(Tone.LIVE, ToneRules.of("🔴 Cuộc trò chuyện đang diễn ra"))
        assertEquals(Tone.INFO, ToneRules.of("Chưa có API key cho tóm tắt AI."))
        assertEquals("Key bị từ chối", ToneRules.clean("⚠️ Key bị từ chối"))
        assertEquals("3 đoạn đang chờ", ToneRules.clean("  ⏳ 3 đoạn đang chờ"))
        assertEquals("Chưa có API key.", ToneRules.clean("Chưa có API key."))
    }

    @Test
    fun chatDetectsUnansweredQuestion() {
        val q1 = ChatMsg(1, "user", "Hôm nay tôi hứa gì?")
        val a1 = ChatMsg(2, "assistant", "Bạn hứa gửi báo cáo.")
        val q2 = ChatMsg(3, "user", "Mai mấy giờ họp?")
        assertEquals(null, ChatState.unanswered(emptyList()))
        assertEquals(q1, ChatState.unanswered(listOf(q1)))
        assertEquals(null, ChatState.unanswered(listOf(q1, a1)))
        assertEquals(q2, ChatState.unanswered(listOf(q1, a1, q2)))
    }

    @Test
    fun notionIdNormalization() {
        val id = "1a2b3c4d5e6f708192a3b4c5d6e7f809"
        assertEquals(id, Notion.normalizeId("https://www.notion.so/team/Deadbeef-Meetings-$id?v=ffffffffffffffffffffffffffffffff"))
        assertEquals(id, Notion.normalizeId("1a2b3c4d-5e6f-7081-92a3-b4c5d6e7f809"))
    }
}
