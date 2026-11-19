package vn.nghenho

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import vn.nghenho.ai.Memory
import vn.nghenho.audio.AdtsAacWriter
import vn.nghenho.audio.AudioConvert
import vn.nghenho.audio.AudioFiles
import vn.nghenho.data.Chunk
import vn.nghenho.data.ConvStatus
import vn.nghenho.data.Conversation
import vn.nghenho.data.SecretBox
import vn.nghenho.data.ActionItem
import vn.nghenho.data.CalEvent
import vn.nghenho.data.EventState
import vn.nghenho.util.Fmt
import vn.nghenho.work.Cleanup
import vn.nghenho.work.Pipeline
import vn.nghenho.work.ProcLog
import vn.nghenho.work.Work
import org.junit.Assert.assertFalse
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Chạy trên thiết bị/máy ảo. Bài pipeline cần máy chủ AI giả lập (chuẩn OpenAI) ở http://10.0.2.2:8765/v1.
 */
@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun writeTone(file: File, seconds: Int) {
        val w = AdtsAacWriter(file, AudioFiles.SAMPLE_RATE)
        val frame = ShortArray(320)
        var n = 0L
        repeat(seconds * 50) {
            for (i in frame.indices) {
                frame[i] = (8000 * sin(2 * PI * 440 * (n + i) / AudioFiles.SAMPLE_RATE)).toInt().toShort()
            }
            n += frame.size
            w.write(frame, frame.size)
        }
        w.finish()
    }

    private fun durationUs(f: File): Pair<String, Long> {
        val ex = MediaExtractor()
        ex.setDataSource(f.absolutePath)
        val fmt = ex.getTrackFormat(0)
        ex.selectTrack(0)
        var last = 0L
        val buf = java.nio.ByteBuffer.allocate(64 * 1024)
        while (ex.readSampleData(buf, 0) >= 0) { last = ex.sampleTime; ex.advance() }
        ex.release()
        return (fmt.getString(MediaFormat.KEY_MIME) ?: "") to last
    }

    @Test
    fun aacEncoderAndM4aRemux() {
        val aac = File(ctx.cacheDir, "t_tone.aac")
        writeTone(aac, 3)
        assertTrue("aac size=${aac.length()}", aac.length() in 8_000..20_000) // ~32 kbps × 3 s
        val (mime, last) = durationUs(aac)
        assertEquals("audio/mp4a-latm", mime)
        assertTrue("aac last pts=$last", last in 2_700_000..3_100_000)

        val m4a = File(ctx.cacheDir, "t_tone.m4a")
        AudioConvert.adtsToM4a(aac, m4a)
        val head = m4a.readBytes().copyOfRange(4, 8).toString(Charsets.US_ASCII)
        assertEquals("ftyp", head)
        val (mime2, last2) = durationUs(m4a)
        assertEquals("audio/mp4a-latm", mime2)
        assertTrue("m4a last pts=$last2", last2 in 2_700_000..3_100_000)

        // Trình phát của máy đọc được file .aac (nút ▶ trong tab Bản ghi)
        val mp = MediaPlayer()
        mp.setDataSource(aac.absolutePath)
        mp.prepare()
        assertTrue("duration=${mp.duration}", mp.duration in 2_500..3_500)
        mp.release()
    }

    @Test
    fun apiKeysAreEncryptedAtRest() {
        val box = SecretBox()
        val enc = box.encrypt("sk-test-123")!!
        assertTrue(!enc.contains("sk-test"))
        assertEquals("sk-test-123", box.decrypt(enc))
        App.settings.setKey("unit_test", "abc")
        assertEquals("abc", App.settings.key("unit_test"))
        App.settings.setKey("unit_test", "")
        assertEquals("", App.settings.key("unit_test"))
    }

    private fun configureMock() {
        val s = App.settings
        s.sttProvider = "custom"; s.sttModel = "whisper-1"
        s.llmProvider = "custom"; s.llmModel = "mock-llm"
        s.customBaseUrl = "http://10.0.2.2:8765/v1"
        s.language = "vi_en"; s.summaryLang = "vi"; s.userName = "Thanh"; s.keepAudioDays = 7; s.wifiOnly = false
        s.convGapMin = 1; s.latencyMode = 0; s.lastError = ""
    }

    /** Cấu hình máy chủ giả lập cho kịch bản E2E chạy tay (adb … -e class vn.nghenho.DeviceTest#setupE2E). */
    @Test
    fun setupE2E() = configureMock()

    @Test
    fun widgetViewsInflate() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val views = vn.nghenho.service.RecordWidget.buildViews(ctx)
            val v = views.apply(ctx, android.widget.FrameLayout(ctx))
            assertTrue(v != null)
        }
    }

    @Test
    fun fullPipelineWithMockServer() {
        configureMock()

        val dao = App.dao
        val start = System.currentTimeMillis() - 60_000
        val f = File(AudioFiles.dir(ctx), "c_$start.aac")
        writeTone(f, 5)
        val convId = dao.insertConv(Conversation(startedAt = start, endedAt = start + 5_000, status = ConvStatus.CLOSED))
        dao.insertChunk(Chunk(conversationId = convId, path = f.absolutePath, startedAt = start, durationMs = 5_000, speechMs = 4_000))
        Work.kick(ctx, force = true)

        var conv = dao.conv(convId)
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline && conv?.status != ConvStatus.DONE && conv?.status != ConvStatus.FAILED) {
            Thread.sleep(1_000)
            conv = dao.conv(convId)
        }
        val chunks = dao.chunksOf(convId)
        assertEquals("conv=$conv chunks=$chunks", ConvStatus.DONE, conv?.status)
        assertEquals("Họp ngân sách Q4", conv!!.title)
        assertEquals("Công việc", conv.category)
        assertTrue(conv.mindmap.contains("Ngân sách"))
        val segs = dao.segmentsOf(convId)
        assertEquals(2, segs.size)
        assertTrue(segs[0].text.startsWith("Chào anh Minh"))
        assertEquals(start + 2_500, segs[1].startAt)
        // Whisper không tách người nói => AI gán người nói khi tóm tắt
        assertEquals("Bạn", segs[0].speaker)
        assertEquals("Người A", segs[1].speaker)
        assertEquals(1, dao.actionsOf(convId).size)
        val evs = dao.eventsOf(convId)
        assertEquals(1, evs.size)
        assertEquals(evs[0].startAt + 3_600_000L, evs[0].endAt)

        // Hỏi trí nhớ dùng cùng máy chủ giả lập
        val ans = kotlinx.coroutines.runBlocking { Memory.answer("Tuần này tôi hẹn họp gì?") }
        assertTrue(ans, ans.contains("[mock-chat]"))
        assertTrue("Hỏi đáp phải nhận được ký ức của cuộc họp: $ans", ans.contains("context_ok=True"))
    }

    /** Xử lý trực tiếp trong app (không qua WorkManager) + nút "Gửi ngay" bỏ qua thời gian chờ của lỗi 429. */
    @Test
    fun directProcessingAndRetryAfter() {
        configureMock()
        val dao = App.dao
        val start = System.currentTimeMillis() - 30_000
        val f = File(AudioFiles.dir(ctx), "c_$start.aac")
        writeTone(f, 3)
        val convId = dao.insertConv(Conversation(startedAt = start, endedAt = start + 3_000, status = ConvStatus.CLOSED))
        dao.insertChunk(Chunk(conversationId = convId, path = f.absolutePath, startedAt = start, durationMs = 3_000, speechMs = 2_500))
        try {
            // Lần 1: chép lời được nhưng tóm tắt bị 429 "Please try again in 2s"
            mockMode("llm=429&n=1")
            val r1 = processNowWhenFree(userInitiated = true)
            assertTrue("r1=$r1", r1.retry && r1.blockedBy == null)
            assertTrue(Pipeline.lastTransientError.orEmpty(), Pipeline.lastTransientError.orEmpty().contains("Rate limit"))
            assertTrue(Pipeline.notBefore > 0)
            if (System.currentTimeMillis() < Pipeline.notBefore) {
                assertEquals("đang trong thời gian chờ => lượt tự động bỏ qua", null, runBlocking { Pipeline.processNow(ctx) })
            }
            // "Gửi ngay": bỏ qua thời gian chờ
            processNowWhenFree(userInitiated = true)
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline && dao.conv(convId)?.status != ConvStatus.DONE) Thread.sleep(500)
            assertEquals(ConvStatus.DONE, dao.conv(convId)?.status)
            assertEquals(listOf("Bạn", "Người A"), dao.segmentsOf(convId).map { it.speaker })
            val log = ProcLog.recent(80)
            assertTrue(log.joinToString("\n"), log.any { it.contains("Tóm tắt") } && log.any { it.contains("429") || it.contains("Rate limit") })
        } finally {
            mockMode("reset=1")
            Pipeline.deleteConversation(convId, keepOpenActions = false)
        }
    }

    private fun mockMode(q: String) {
        okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url("http://10.0.2.2:8765/mode?$q").build()).execute().close()
    }

    /** processNow trả null khi một lượt khác (WorkManager / lúc mở app) đang chạy: chờ tới lượt. */
    private fun processNowWhenFree(userInitiated: Boolean): Pipeline.DrainResult {
        val deadline = System.currentTimeMillis() + 60_000
        while (true) {
            runBlocking { Pipeline.processNow(ctx, userInitiated) }?.let { return it }
            if (System.currentTimeMillis() > deadline) error("Pipeline luôn bận")
            Thread.sleep(300)
        }
    }

    @Test
    fun dailyCleanupOnlyOffersFinishedOldConversations() {
        val dao = App.dao
        val s = App.settings
        val now = System.currentTimeMillis()
        val yesterday = Fmt.startOfDay(now) - 6 * 3_600_000L
        s.askCleanupDaily = true; s.cleanupPromptDay = ""; s.cleanupKeptBefore = 0L
        val old = dao.insertConv(Conversation(startedAt = yesterday, endedAt = yesterday + 60_000, status = ConvStatus.DONE, title = "test-old"))
        val openTask = dao.insertAction(ActionItem(conversationId = old, text = "test-việc chưa xong"))
        dao.insertAction(ActionItem(conversationId = old, text = "test-việc đã xong", done = true))
        dao.insertEvents(listOf(CalEvent(conversationId = old, title = "đã thêm", startAt = now + 3_600_000, endAt = now + 7_200_000, state = EventState.ADDED)))
        val waiting = dao.insertConv(Conversation(startedAt = yesterday + 120_000, endedAt = yesterday + 180_000, status = ConvStatus.DONE, title = "test-waiting"))
        dao.insertEvents(listOf(CalEvent(conversationId = waiting, title = "chưa thêm", startAt = now + 86_400_000, endAt = now + 90_000_000)))
        val today = dao.insertConv(Conversation(startedAt = now - 60_000, endedAt = now, status = ConvStatus.DONE, title = "test-today"))
        try {
            assertTrue(Cleanup.shouldAsk(now))
            val plan = Cleanup.dailyPlan(now)
            assertTrue(plan.ids.contains(old))
            assertFalse("còn lịch hẹn chưa thêm vào Lịch => giữ", plan.ids.contains(waiting))
            assertFalse("hôm nay => không hỏi", plan.ids.contains(today))
            assertTrue(plan.waitingCalendar >= 1)
            assertTrue(plan.openTasks >= 1)

            assertEquals(1, Cleanup.delete(listOf(old)))
            Cleanup.snooze(now)
            assertEquals(null, dao.conv(old))
            assertTrue(dao.segmentsOf(old).isEmpty() && dao.eventsOf(old).isEmpty())
            assertEquals("chỉ giữ việc chưa xong", listOf(openTask), dao.actionsOf(old).map { it.id })
            assertFalse("đã trả lời hôm nay => không hỏi lại", Cleanup.shouldAsk(now))
            assertTrue(Cleanup.olderThan(0, now).contains(waiting))
            assertFalse(Cleanup.olderThan(0, now).contains(today))
        } finally {
            listOf(old, waiting, today).forEach { Pipeline.deleteConversation(it, keepOpenActions = false) }
            s.cleanupPromptDay = ""
        }
    }

    /** Lượt 8:00 sáng (CleanupWorker) gọi Cleanup.maybeNotify: thông báo có nút "Xoá N cuộc" / "Giữ lại". */
    @Test
    fun morningCleanupNotification() {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        val now = System.currentTimeMillis()
        val y = Fmt.startOfDay(now) - 4 * 3_600_000L
        val id = App.dao.insertConv(Conversation(startedAt = y, endedAt = y + 60_000, status = ConvStatus.DONE, title = "test-notif"))
        App.settings.askCleanupDaily = true
        App.settings.cleanupPromptDay = ""
        try {
            Cleanup.maybeNotify(ctx)
            Thread.sleep(500)
            val n = nm.activeNotifications.firstOrNull { it.id == vn.nghenho.service.Notifs.CLEANUP_ID }
            assertTrue("chưa thấy thông báo dọn dẹp", n != null)
            val labels = n!!.notification.actions.orEmpty().map { it.title.toString() }
            assertTrue(labels.toString(), labels.any { it.startsWith("Xoá") } && labels.contains("Giữ lại"))
            // Bấm "Giữ lại" ngay trên thông báo
            n.notification.actions.first { it.title.toString() == "Giữ lại" }.actionIntent.send()
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && App.settings.cleanupKeptBefore != Fmt.startOfDay(now)) Thread.sleep(100)
            assertEquals(Fmt.startOfDay(now), App.settings.cleanupKeptBefore)
            assertFalse(Cleanup.shouldAsk())
            Thread.sleep(300)
            assertTrue("bấm xong phải gỡ thông báo", nm.activeNotifications.none { it.id == vn.nghenho.service.Notifs.CLEANUP_ID })
            App.settings.cleanupKeptBefore = 0L
            App.settings.cleanupPromptDay = ""
            // Tắt tuỳ chọn => không hỏi nữa
            App.settings.askCleanupDaily = false
            Cleanup.maybeNotify(ctx)
            Thread.sleep(300)
            assertTrue(nm.activeNotifications.none { it.id == vn.nghenho.service.Notifs.CLEANUP_ID })
        } finally {
            App.settings.askCleanupDaily = true
            Pipeline.deleteConversation(id, keepOpenActions = false)
        }
    }

    /** Flowme: tên hiển thị mới nhưng GIỮ mã gói cũ (cài đè lên NgheNhớ không mất dữ liệu), icon dạng adaptive. */
    @Test
    fun flowmeBrandingKeepsPackageId() {
        val pm = ctx.packageManager
        assertEquals("vn.nghenho", ctx.packageName)
        assertEquals("Flowme", ctx.applicationInfo.loadLabel(pm).toString())
        assertTrue(BuildConfig.VERSION_NAME, BuildConfig.VERSION_NAME.startsWith("2."))
        val icon = pm.getApplicationIcon(ctx.packageName)
        assertTrue("icon=${icon.javaClass}", icon is android.graphics.drawable.AdaptiveIconDrawable)
        val adaptive = icon as android.graphics.drawable.AdaptiveIconDrawable
        assertTrue(adaptive.background != null && adaptive.foreground != null)
        if (android.os.Build.VERSION.SDK_INT >= 33) assertTrue("thiếu lớp monochrome cho icon theo chủ đề", adaptive.monochrome != null)
        // Các drawable thương hiệu dựng được (vector + gradient)
        for (id in listOf(R.drawable.ic_brand_mark, R.drawable.ic_stat_flowme, R.drawable.ic_launcher_background)) {
            assertTrue(androidx.core.content.ContextCompat.getDrawable(ctx, id) != null)
        }
    }

    /** Câu hỏi đang gõ dở được ghi thật xuống đĩa (không chỉ trong bộ nhớ) => thoát/ép dừng app vẫn còn. */
    @Test
    fun chatDraftIsWrittenToDisk() {
        val text = "Mai 9h30 tôi họp với ai? ${System.currentTimeMillis()}"
        App.settings.setDraft("chat", text)
        assertEquals(text, vn.nghenho.data.Settings(ctx).draft("chat"))
        val file = File(ctx.applicationInfo.dataDir, "shared_prefs/nghenho.xml")
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !(file.exists() && file.readText().contains(text))) Thread.sleep(100)
        assertTrue("bản nháp chưa được ghi xuống ${file.path}", file.readText().contains(text))
        App.settings.setDraft("chat", "")
    }

    /** App bị đóng đúng lúc AI đang trả lời: "Hỏi lại" trả lời câu đã lưu, không lưu trùng câu hỏi. */
    @Test
    fun chatRetryAfterInterruption() {
        configureMock()
        val dao = App.dao
        dao.clearChat()
        dao.insertChat(vn.nghenho.data.ChatMsg(role = "user", text = "Tuần này tôi hẹn họp gì?"))
        val pending = vn.nghenho.util.ChatState.unanswered(dao.lastChat(10).reversed())
        assertTrue(pending != null)
        vn.nghenho.ui.ChatRunner.ask(pending!!.text, saveQuestion = false)
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline && dao.lastChat(10).firstOrNull()?.role != "assistant") Thread.sleep(200)
        val msgs = dao.lastChat(10).reversed()
        assertEquals("chỉ 1 câu hỏi + 1 câu trả lời: $msgs", listOf("user", "assistant"), msgs.map { it.role })
        assertTrue(msgs.last().text, msgs.last().text.contains("[mock-chat]"))
        assertEquals(null, vn.nghenho.util.ChatState.unanswered(msgs))
        dao.clearChat()
    }

    /** Dữ liệu mẫu để xem thẻ "Dọn trí nhớ" trên màn hình chính (chạy tay). */
    @Test
    fun setupCleanupDemo() {
        val now = System.currentTimeMillis()
        val y = Fmt.startOfDay(now) - 5 * 3_600_000L
        val id = App.dao.insertConv(
            Conversation(startedAt = y, endedAt = y + 600_000, status = ConvStatus.DONE, title = "Hẹn họp 9h30 sáng mai", emoji = "📅", category = "Công việc", overview = "Bạn hẹn họp lúc 9:30."),
        )
        App.dao.insertEvents(listOf(CalEvent(conversationId = id, title = "Họp", startAt = now + 3_600_000, endAt = now + 7_200_000, state = EventState.ADDED)))
        App.settings.askCleanupDaily = true
        App.settings.cleanupPromptDay = ""
        App.settings.cleanupKeptBefore = 0L
    }
}
