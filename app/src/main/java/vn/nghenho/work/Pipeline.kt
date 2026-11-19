package vn.nghenho.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import vn.nghenho.App
import vn.nghenho.ai.ApiException
import vn.nghenho.ai.Llm
import vn.nghenho.ai.MissingKeyException
import vn.nghenho.ai.Parse
import vn.nghenho.ai.Prompts
import vn.nghenho.ai.Providers
import vn.nghenho.ai.Transcriber
import vn.nghenho.data.ActionItem
import vn.nghenho.data.CalEvent
import vn.nghenho.data.Chunk
import vn.nghenho.data.ChunkStatus
import vn.nghenho.data.ConvStatus
import vn.nghenho.data.Conversation
import vn.nghenho.data.EventState
import vn.nghenho.data.Segment
import vn.nghenho.integrations.Integrations
import vn.nghenho.util.Fmt
import vn.nghenho.util.Mindmap
import vn.nghenho.util.Times
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Lượt xử lý dự phòng bằng WorkManager (đường chính là Pipeline.processSoon ngay trong app).
 * KHÔNG đặt điều kiện mạng: WorkManager đòi mạng "đã xác thực" nên có máy/mạng (DNS riêng, VPN, Wi-Fi công ty…)
 * bị kẹt mãi ở "chờ mạng" dù 5G/Wi-Fi vẫn dùng được. Việc kiểm tra mạng do Net.blockReason() làm bên trong drain().
 */
object Work {
    private const val UNIQUE = "nghenho-process"
    private const val PERIODIC = "nghenho-periodic"

    private fun constraints(): Constraints = Constraints.NONE

    /**
     * force/replace: chạy ngay, huỷ cả lượt đang hoãn chờ thử lại (người dùng bấm Thử lại, đổi key…).
     * chain: nối tiếp sau lượt đang chạy (lượt hiện tại hết giờ nhưng còn việc).
     */
    fun kick(ctx: Context, force: Boolean = false, replace: Boolean = false, chain: Boolean = false) {
        val app = ctx.applicationContext
        App.instance.io.launch {
            runCatching {
                val wm = WorkManager.getInstance(app)
                var policy = if (force || replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE
                if (!force && !replace && !chain) {
                    val waiting = wm.getWorkInfosForUniqueWork(UNIQUE).get()
                        .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
                    if (waiting.any { it.constraints.requiredNetworkType == NetworkType.NOT_REQUIRED }) return@launch
                    // Lượt cũ (bản 1.1.0) còn chờ "mạng đã xác thực" => thay hẳn, đừng xếp sau nó
                    if (waiting.isNotEmpty()) policy = ExistingWorkPolicy.REPLACE
                }
                val req = OneTimeWorkRequestBuilder<ProcessWorker>()
                    .setConstraints(constraints())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                wm.enqueueUniqueWork(UNIQUE, policy, req)
            }.onFailure { Log.w("NgheNho", "kick", it) }
        }
    }

    /** Lượt định kỳ KHÔNG đòi điều kiện mạng của WorkManager – tự kiểm tra mạng bên trong (xem Net). */
    fun schedulePeriodic(ctx: Context, update: Boolean = false) {
        val req = PeriodicWorkRequestBuilder<ProcessWorker>(30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            PERIODIC, if (update) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP, req,
        )
    }

    /** Mỗi sáng ~8:00 hỏi dọn hội thoại hôm trước. */
    fun scheduleCleanup(ctx: Context) {
        val now = ZonedDateTime.now()
        var next = now.toLocalDate().atTime(8, 0).atZone(now.zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val req = PeriodicWorkRequestBuilder<CleanupWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(CLEANUP, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    private const val CLEANUP = "nghenho-cleanup"
}

class ProcessWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        // Nhà cung cấp vừa yêu cầu chờ (429 + Retry-After): để WorkManager hẹn lại sau, không gọi dồn
        if (System.currentTimeMillis() < Pipeline.notBefore) return Result.retry()
        val r = Pipeline.lock.withLock { Pipeline.drain(applicationContext, 8 * 60_000L) { isStopped } }
        if (r.budgetHit) Work.kick(applicationContext, chain = true)
        return if (r.retry) Result.retry() else Result.success()
    }
}

object Pipeline {
    val lock = Mutex()
    private const val MAX_CHUNK_ATTEMPTS = 20
    private const val MAX_CONV_ATTEMPTS = 6

    /** Bị giới hạn tốc độ (429 + Retry-After): không tự gọi lại trước thời điểm này. */
    @Volatile var notBefore = 0L
    @Volatile var lastTransientError: String? = null

    enum class Outcome { OK, RETRY, BLOCKED }

    data class DrainResult(
        val retry: Boolean = false,
        val budgetHit: Boolean = false,
        val transcribed: Int = 0,
        val summarized: Int = 0,
        val blockedBy: String? = null,
    )

    /** Một lượt xử lý hàng chờ: chép lời các đoạn (cũ nhất trước) rồi tóm tắt các cuộc đã xong. */
    suspend fun drain(ctx: Context, budgetMs: Long, stopped: () -> Boolean): DrainResult {
        Net.blockReason()?.let { return DrainResult(blockedBy = it) }
        val started = System.currentTimeMillis()
        fun overBudget() = System.currentTimeMillis() - started > budgetMs
        val dao = App.dao
        val s = App.settings
        var retry = false
        var budgetHit = false
        var transcribed = 0
        var summarized = 0
        if (Providers.sttReady(s)) {
            while (!stopped()) {
                if (overBudget()) { budgetHit = true; break }
                val c = dao.nextPending() ?: break
                when (transcribeChunk(ctx, c)) {
                    Outcome.RETRY -> { retry = true; break }
                    Outcome.BLOCKED -> break
                    Outcome.OK -> transcribed++
                }
            }
        }
        if (!budgetHit && Providers.llmReady(s)) {
            for (conv in dao.closedConvs()) {
                if (stopped()) break
                if (overBudget()) { budgetHit = true; break }
                if (dao.pendingChunksOf(conv.id) > 0) continue
                when (summarize(ctx, conv)) {
                    Outcome.RETRY -> { retry = true; break }
                    Outcome.BLOCKED -> break
                    Outcome.OK -> summarized++
                }
            }
        }
        runCatching { cleanupAudio() }
        return DrainResult(retry, budgetHit, transcribed, summarized)
    }

    /**
     * Xử lý NGAY trong app (không chờ WorkManager). Bỏ qua (null) nếu đang có lượt khác chạy
     * hoặc đang bị giới hạn tốc độ (trừ khi người dùng tự bấm "Gửi ngay").
     */
    suspend fun processNow(ctx: Context, userInitiated: Boolean = false): DrainResult? {
        if (userInitiated) notBefore = 0L else if (System.currentTimeMillis() < notBefore) return null
        if (!lock.tryLock()) return null
        return try {
            drain(ctx.applicationContext, 5 * 60_000L) { false }
        } finally {
            lock.unlock()
        }
    }

    /** Gọi khi có đoạn mới / cuộc hội thoại vừa khép / vừa có mạng. WorkManager chỉ là dự phòng. */
    fun processSoon(ctx: Context) {
        val app = ctx.applicationContext
        App.instance.io.launch {
            val r = runCatching { processNow(app) }.onFailure { Log.w("NgheNho", "processNow", it) }.getOrNull()
            if (r == null || r.retry || r.budgetHit || r.blockedBy != null) Work.kick(app)
        }
    }

    fun describe(r: DrainResult?): String = when {
        r == null -> "Đang có lượt xử lý khác chạy – chờ vài giây rồi xem lại."
        r.blockedBy != null -> "Chưa gửi được: ${r.blockedBy}."
        r.transcribed + r.summarized > 0 ->
            "Đã xử lý ${r.transcribed} đoạn, tóm tắt ${r.summarized} cuộc." + if (r.retry) " Còn đoạn lỗi tạm thời – sẽ tự thử lại." else ""
        r.retry -> "Nhà cung cấp AI đang báo lỗi tạm thời: ${lastTransientError ?: "?"}. Sẽ tự thử lại."
        else -> "Không có gì đang chờ. Nếu vẫn kẹt, xem Cài đặt → Nhật ký xử lý."
    }

    /** Nút "Kiểm tra chép lời": gửi đoạn ghi gần nhất tới nhà cung cấp và báo kết quả/lỗi thật. */
    suspend fun testStt(ctx: Context): String = withContext(Dispatchers.IO) {
        val c = App.dao.latestChunkWithFile()
            ?: return@withContext "Chưa có đoạn ghi âm nào để thử. Bật Flowme, nói vài câu rồi bấm lại."
        val result = try {
            val segs = Transcriber.transcribe(ctx, File(c.path))
            when {
                segs.isEmpty() -> "✅ Kết nối chép lời được, nhưng đoạn ${Fmt.hm(c.startedAt)} không có lời nói rõ."
                else -> "✅ Chép lời được ${segs.size} câu" +
                    (if (segs.any { it.speaker.isNotBlank() }) ", có tách người nói" else " (nhà cung cấp này không tách người nói – AI sẽ gán khi tóm tắt)") +
                    ": “${segs.joinToString(" ") { it.text }.take(140)}”"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "❌ ${e.message ?: e.javaClass.simpleName}"
        }
        ProcLog.add("🔎 Kiểm tra chép lời: $result")
        result
    }

    suspend fun transcribeChunk(ctx: Context, c: Chunk): Outcome {
        val dao = App.dao
        val f = File(c.path)
        if (c.path.isBlank() || !f.exists() || f.length() < 512) {
            dao.updateChunk(c.copy(status = ChunkStatus.EMPTY, error = "Không còn file âm thanh"))
            return Outcome.OK
        }
        return try {
            val segs = Transcriber.transcribe(ctx, f)
            val limit = if (c.durationMs > 0) c.durationMs else 3_600_000L
            var prev = 0L
            val rows = segs.map { s ->
                val st = maxOf((s.startSec * 1000).toLong().coerceIn(0, limit), prev)
                val en = if (s.endSec > s.startSec) (s.endSec * 1000).toLong().coerceIn(st, maxOf(limit, st)) else st
                prev = st
                Segment(conversationId = c.conversationId, chunkId = c.id, startAt = c.startedAt + st, endAt = c.startedAt + en, speaker = s.speaker, text = s.text)
            }
            if (dao.conv(c.conversationId) == null) {
                // Cuộc hội thoại bị xoá trong lúc đang chép lời => bỏ đoạn này
                f.delete()
                dao.deleteChunk(c.id)
                return Outcome.OK
            }
            val done = c.copy(status = if (rows.isEmpty()) ChunkStatus.EMPTY else ChunkStatus.DONE, error = null, provider = App.settings.sttProvider)
            dao.replaceTranscript(done, rows)
            clearError("STT")
            lastTransientError = null
            ProcLog.add(
                "✓ Chép lời ${Fmt.hm(c.startedAt)} (${Fmt.duration(c.durationMs)}): ${rows.size} câu" +
                    if (rows.any { it.speaker.isNotBlank() }) ", có tách người nói" else ""
            )
            if (rows.isEmpty() || App.settings.keepAudioDays == 0) {
                f.delete()
                dao.clearChunkPath(c.id)
            }
            Outcome.OK
        } catch (e: MissingKeyException) {
            Outcome.BLOCKED
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.auth) {
                // Key sai/hết hạn: dừng cả hàng chờ, giữ nguyên các đoạn để xử lý lại khi sửa key
                setError("STT", e.message)
                dao.updateChunk(c.copy(error = e.message))
                ProcLog.add("⛔ ${e.message}")
                Outcome.BLOCKED
            } else failChunk(c, e.message ?: "Lỗi", e.retryable, e.retryAfterMs)
        } catch (e: Exception) {
            failChunk(c, e.message ?: e.javaClass.simpleName, false)
        }
    }

    /** Lỗi cần người dùng sửa, hiện trên màn hình chính. kind = STT | AI. */
    private fun setError(kind: String, msg: String?) {
        App.settings.lastError = "$kind|${msg.orEmpty()}"
    }

    private fun clearError(kind: String) {
        if (App.settings.lastError.startsWith("$kind|")) App.settings.lastError = ""
    }

    private fun failChunk(c: Chunk, msg: String, retryable: Boolean, retryAfterMs: Long? = null): Outcome {
        val attempts = c.attempts + 1
        lastTransientError = msg
        retryAfterMs?.let { notBefore = maxOf(notBefore, System.currentTimeMillis() + it) }
        ProcLog.add("✗ Chép lời ${Fmt.hm(c.startedAt)} lần $attempts: $msg")
        return if (retryable && attempts < MAX_CHUNK_ATTEMPTS) {
            App.dao.updateChunk(c.copy(attempts = attempts, error = msg))
            Outcome.RETRY
        } else {
            App.dao.updateChunk(c.copy(status = ChunkStatus.FAILED, attempts = attempts, error = msg))
            Outcome.OK
        }
    }

    suspend fun summarize(ctx: Context, conv0: Conversation): Outcome {
        val dao = App.dao
        val conv = dao.conv(conv0.id) ?: return Outcome.OK
        val segs = dao.segmentsOf(conv.id)
        val now = System.currentTimeMillis()
        if (segs.isEmpty()) {
            val chunks = dao.chunksOf(conv.id)
            if (chunks.any { it.status == ChunkStatus.FAILED }) {
                dao.updateConv(conv.copy(status = ConvStatus.FAILED, error = "Chép lời thất bại – xem tab Bản ghi để thử lại"))
            } else {
                deleteConversation(conv.id) // chỉ có tạp âm, không có lời nói
            }
            return Outcome.OK
        }
        val words = segs.sumOf { s -> s.text.split(' ').count { it.isNotBlank() } }
        if (words < 6) {
            dao.updateConv(
                conv.copy(
                    status = ConvStatus.DONE, title = segs.joinToString(" ") { it.text }.take(60), emoji = "💬",
                    category = "Khác", trivial = true, error = null, processedAt = now,
                )
            )
            return Outcome.OK
        }
        return try {
            val s = App.settings
            // Nhà cung cấp chép lời không tách người nói (Groq/Whisper…) => đánh số từng câu để AI gán người nói
            val needLabels = segs.size in 1..400 && segs.all { it.speaker.isBlank() }
            val transcript = if (needLabels) Fmt.numberedTranscript(segs) else Fmt.transcript(segs, maxChars = 400_000)
            val raw = Llm.complete(
                Prompts.summarySystem(s.summaryLang),
                Prompts.summaryUser(conv.startedAt, conv.endedAt, transcript, s.userName, numberedLines = if (needLabels) segs.size else 0),
                Prompts.SUMMARY_SCHEMA,
            )
            val ins = Parse.insight(raw)
            val actions = ins.actions.map { ActionItem(conversationId = conv.id, text = it.text, owner = it.owner, due = it.due) }
            val parsedEvents = ins.events.mapNotNull { e ->
                val st = Times.parseLocal(e.start) ?: return@mapNotNull null
                var en = Times.parseLocal(e.end) ?: (st + 3_600_000L)
                if (en <= st) en = st + 3_600_000L
                CalEvent(conversationId = conv.id, title = e.title, startAt = st, endAt = en, location = e.location, notes = e.notes)
            }
            // "Tóm tắt lại": không thêm trùng lịch hẹn đã thêm vào Lịch hoặc đã bỏ qua
            val kept = dao.eventsOf(conv.id).filter { it.state != EventState.SUGGESTED }
            val events = parsedEvents.filter { e -> kept.none { k -> k.startAt == e.startAt && k.title.equals(e.title, ignoreCase = true) } }
            val mind = ins.mindmap?.let { Mindmap.fromJson(it) }?.let { Mindmap.toJson(it).toString() } ?: ""
            val updated = conv.copy(
                status = ConvStatus.DONE,
                title = ins.title.ifBlank { "Cuộc trò chuyện" }.take(120),
                emoji = ins.emoji.take(16).ifBlank { "💬" },
                category = ins.category.ifBlank { "Khác" },
                overview = ins.overview,
                keyPoints = JsonArray(ins.keyPoints.map { JsonPrimitive(it) }).toString(),
                mindmap = mind,
                trivial = !ins.meaningful,
                error = null,
                attempts = 0,
                processedAt = now,
            )
            if (dao.conv(conv.id) == null) return Outcome.OK // bị xoá trong lúc đang tóm tắt
            val ids = dao.saveInsight(updated, actions, events)
            clearError("AI")
            val labels = ins.speakerLabels
            if (needLabels && labels.size == segs.size && labels.any { it.isNotBlank() }) {
                dao.setSpeakers(segs.map { it.id }, labels.map { Parse.speakerLabel(it).ifBlank { "?" }.take(40) })
            } else if (needLabels) {
                ProcLog.add("⚠ AI trả ${labels.size}/${segs.size} nhãn người nói – giữ bản ghi không tên (bấm “Tóm tắt lại” để thử lại)")
            }
            ProcLog.add("✓ Tóm tắt: ${updated.emoji} ${updated.title}" + if (needLabels && labels.size == segs.size) " (AI đã tách người nói)" else "")
            val saved = events.mapIndexed { i, e -> e.copy(id = ids.getOrElse(i) { 0L }) }.filter { it.id > 0 }
            runCatching { Integrations.afterSummary(ctx, updated, saved) }.onFailure { Log.w("NgheNho", "integrations", it) }
            Outcome.OK
        } catch (e: MissingKeyException) {
            Outcome.BLOCKED
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.auth) {
                setError("AI", e.message)
                ProcLog.add("⛔ ${e.message}")
                Outcome.BLOCKED
            } else {
                e.retryAfterMs?.let { notBefore = maxOf(notBefore, System.currentTimeMillis() + it) }
                failConv(conv, e.message ?: "Lỗi", e.retryable)
            }
        } catch (e: Exception) {
            failConv(conv, e.message ?: e.javaClass.simpleName, false)
        }
    }

    private fun failConv(c: Conversation, msg: String, retryable: Boolean): Outcome {
        val attempts = c.attempts + 1
        lastTransientError = msg
        ProcLog.add("✗ Tóm tắt lần $attempts: $msg")
        return if (retryable && attempts < MAX_CONV_ATTEMPTS) {
            App.dao.updateConv(c.copy(attempts = attempts, error = msg))
            Outcome.RETRY
        } else {
            App.dao.updateConv(c.copy(status = ConvStatus.FAILED, attempts = attempts, error = msg))
            Outcome.OK
        }
    }

    /** keepOpenActions: giữ lại việc chưa xong (vẫn hiện trong tab Việc). */
    fun deleteConversation(id: Long, keepOpenActions: Boolean = true) {
        val dao = App.dao
        dao.chunksOf(id).forEach { if (it.path.isNotBlank()) File(it.path).delete() }
        dao.deleteConversationRows(id, keepOpenActions)
    }

    /** Giữ file âm thanh N ngày (0 = xoá ngay sau khi chép lời, -1 = giữ mãi). */
    fun cleanupAudio() {
        val days = App.settings.keepAudioDays
        if (days <= 0) return
        val before = System.currentTimeMillis() - days * 86_400_000L
        for (c in App.dao.oldProcessedChunks(before)) {
            File(c.path).delete()
            App.dao.clearChunkPath(c.id)
        }
    }
}
