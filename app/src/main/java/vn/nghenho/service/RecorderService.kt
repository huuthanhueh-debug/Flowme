package vn.nghenho.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.nghenho.App
import vn.nghenho.BuildConfig
import vn.nghenho.R
import vn.nghenho.audio.AudioFiles
import vn.nghenho.audio.PcmWriter
import vn.nghenho.audio.Segmenter
import vn.nghenho.audio.SegmenterConfig
import vn.nghenho.data.CalEvent
import vn.nghenho.data.Chunk
import vn.nghenho.data.ConvStatus
import vn.nghenho.data.Conversation
import vn.nghenho.data.MemDao
import vn.nghenho.data.Settings
import vn.nghenho.ui.MainActivity
import vn.nghenho.util.Fmt
import vn.nghenho.work.Pipeline
import vn.nghenho.work.Work
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class RecState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val sessionStart: Long = 0L,
    val level: Float = 0f,
    val inSpeech: Boolean = false,
    val silenced: Boolean = false,
    val activeConvId: Long = 0L,
    val error: String? = null,
)

object RecorderState {
    private val _state = MutableStateFlow(RecState())
    val state: StateFlow<RecState> = _state.asStateFlow()
    fun update(f: (RecState) -> RecState) = _state.update(f)
}

/** Gom các đoạn có tiếng nói thành "cuộc hội thoại"; im lặng quá N phút => kết thúc và gửi đi tóm tắt. */
class ConversationTracker(private val dao: MemDao, private val settings: Settings, private val onClosed: () -> Unit) {
    @Volatile var activeId = 0L
        private set
    private var lastActivity = 0L
    private val gapMs: Long get() = settings.convGapMin.coerceIn(1, 30) * 60_000L

    @Synchronized
    fun assign(chunkStart: Long, lastSpeechAt: Long): Long {
        if (activeId != 0L && chunkStart - lastActivity <= gapMs) {
            if (dao.touchConv(activeId, lastSpeechAt) > 0) {
                lastActivity = maxOf(lastActivity, lastSpeechAt)
                return activeId
            }
            activeId = 0L // cuộc hội thoại vừa bị xoá ở nơi khác => mở cuộc mới
        }
        closeActive()
        activeId = dao.insertConv(Conversation(startedAt = chunkStart, endedAt = lastSpeechAt, status = ConvStatus.ACTIVE))
        lastActivity = lastSpeechAt
        val id = activeId
        RecorderState.update { it.copy(activeConvId = id) }
        return id
    }

    @Synchronized
    fun tick(now: Long, chunkOpen: Boolean) {
        if (activeId != 0L && !chunkOpen && now - lastActivity > gapMs) closeActive()
    }

    @Synchronized
    fun closeActive() {
        if (activeId == 0L) return
        Log.i("NgheNho", "Khép cuộc hội thoại $activeId")
        dao.setConvStatus(activeId, ConvStatus.CLOSED)
        activeId = 0L
        RecorderState.update { it.copy(activeConvId = 0L) }
        onClosed()
    }
}

/**
 * Dịch vụ chạy nền (foreground, loại "microphone") – nghe liên tục cả ngày.
 * Có thông báo cố định với nút Tạm dừng / Chốt đoạn / Tắt.
 */
class RecorderService : Service() {
    companion object {
        private const val TAG = "NgheNho"
        const val ACTION_START = "vn.nghenho.START"
        const val ACTION_STOP = "vn.nghenho.STOP"
        const val ACTION_PAUSE = "vn.nghenho.PAUSE"
        const val ACTION_RESUME = "vn.nghenho.RESUME"
        const val ACTION_SPLIT = "vn.nghenho.SPLIT"
        const val NOTIF_ID = 42

        fun intent(ctx: Context, action: String): Intent = Intent(ctx, RecorderService::class.java).setAction(action)

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, intent(ctx, ACTION_START))
        }

        /** Gửi lệnh cho dịch vụ đang chạy (dừng, tạm dừng, chốt đoạn…). */
        fun send(ctx: Context, action: String) {
            if (RecorderState.state.value.running) ctx.startService(intent(ctx, action))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var tickJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var paused = false
    @Volatile private var splitRequested = false
    @Volatile private var stopping = false
    private lateinit var tracker: ConversationTracker
    private var debugFeed: ShortArray? = null
    private var feedPos = 0

    /** Chỉ bản debug: phát file WAV 16 kHz mono thay cho micro, để kiểm thử trọn vòng ghi âm trên máy ảo. */
    private fun loadDebugFeed(): ShortArray? {
        if (!BuildConfig.DEBUG) return null
        val f = File(getExternalFilesDir(null), "debug_input.wav")
        if (!f.exists() || f.length() < 1_000) return null
        val bytes = f.readBytes()
        val sb = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val pcm = ShortArray(sb.remaining())
        sb.get(pcm)
        Log.i(TAG, "DEBUG: dùng âm thanh thử ${pcm.size / AudioFiles.SAMPLE_RATE} giây thay cho micro")
        return pcm
    }

    override fun onCreate() {
        super.onCreate()
        tracker = ConversationTracker(App.dao, App.settings) { Pipeline.processSoon(this) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val active = loopJob?.isActive == true
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                if (active) stopAll() else stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> if (active) setPaused(true) else stopSelf()
            ACTION_RESUME -> if (active) setPaused(false) else stopSelf()
            ACTION_SPLIT -> if (active) splitRequested = true else stopSelf()
            else -> startRecording(restartedBySystem = intent == null)
        }
        return START_STICKY
    }

    private fun startRecording(restartedBySystem: Boolean) {
        if (loopJob?.isActive == true) {
            // Đã chạy: vẫn gọi lại startForeground cho chắc (mỗi startForegroundService cần một lần startForeground)
            runCatching {
                ServiceCompat.startForeground(
                    this, NOTIF_ID, Notifs.recording(this, RecorderState.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            }
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Notifs.showResumePrompt(this, "Cần cấp quyền micro. Mở Flowme để cấp quyền rồi bật lại.")
            stopSelf()
            return
        }
        RecorderState.update { RecState(running = true, sessionStart = System.currentTimeMillis()) }
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, Notifs.recording(this, RecorderState.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            // Android không cho bật micro khi app đang ở nền (vd. hệ thống tự khởi động lại dịch vụ)
            Log.w(TAG, "startForeground thất bại", e)
            RecorderState.update { RecState() }
            Notifs.showResumePrompt(
                this,
                if (restartedBySystem) "Hệ thống đã tạm dừng Flowme. Chạm “Bật lại” để tiếp tục nghe."
                else "Không bật được ghi âm nền. Chạm “Bật lại” để thử lại.",
            )
            stopSelf()
            return
        }
        Notifs.cancel(this, Notifs.RESUME_ID)
        App.settings.wasRecording = true
        stopping = false
        paused = false
        splitRequested = false
        acquireWakeLock(force = true)
        // Cuộc hội thoại "đang diễn ra" sót lại từ phiên trước (dịch vụ bị huỷ đột ngột) => khép để được tóm tắt
        val startedAt = System.currentTimeMillis()
        App.instance.io.launch { runCatching { if (App.dao.closeStaleActive(startedAt) > 0) Pipeline.processSoon(this@RecorderService) } }
        loopJob = scope.launch(Dispatchers.IO) { recordLoop() }
        // Trong lúc nghe: mỗi phút thử gửi các đoạn còn chờ (không phụ thuộc WorkManager)
        tickJob?.cancel()
        tickJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(60_000)
                runCatching {
                    if (App.dao.pendingCount() > 0 || App.dao.closedCount() > 0) Pipeline.processSoon(this@RecorderService)
                }
            }
        }
        StateSync.refresh(this)
    }

    private suspend fun recordLoop() {
        val s = App.settings
        val cfg = SegmenterConfig.of(s.sensitivity, s.latencyMode)
        val seg = Segmenter(AudioFiles.SAMPLE_RATE, cfg, ChunkSink())
        debugFeed = loadDebugFeed()
        feedPos = 0
        var failures = 0
        try {
            while (currentCoroutineContext().isActive && failures < 6) {
                val ok = runSession(s, seg)
                if (!currentCoroutineContext().isActive) break
                if (ok) failures = 0 else { failures++; delay(1_500L * failures) }
            }
        } finally {
            runCatching { seg.closeIfOpen() }
        }
        if (failures >= 6) {
            RecorderState.update { it.copy(error = "Micro không phản hồi") }
            withContext(Dispatchers.Main) { stopAll() }
        }
    }

    /** Một phiên AudioRecord. true = kết thúc bình thường, false = lỗi micro (sẽ mở lại). */
    @SuppressLint("MissingPermission")
    private suspend fun runSession(s: Settings, seg: Segmenter): Boolean {
        val sr = AudioFiles.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(s.audioSource, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 4, sr * 4))
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord", e); return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        val ns = if (s.noiseSuppress && NoiseSuppressor.isAvailable())
            runCatching { NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val agc = if (s.autoGain && AutomaticGainControl.isAvailable())
            runCatching { AutomaticGainControl.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val frame = ShortArray(sr / 50) // 20 ms
        var recording = false
        var lastUi = 0L
        var lastCheck = 0L
        var lastWake = System.currentTimeMillis()
        try {
            while (currentCoroutineContext().isActive) {
                val now = System.currentTimeMillis()
                if (paused) {
                    if (recording) {
                        rec.stop(); recording = false
                        seg.reset()
                        RecorderState.update { it.copy(level = 0f, inSpeech = false) }
                    }
                    if (splitRequested) { splitRequested = false; tracker.closeActive() }
                    tracker.tick(now, false)
                    delay(250)
                    continue
                }
                if (!recording) {
                    rec.startRecording()
                    recording = true
                    if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) return false
                }
                val n = rec.read(frame, 0, frame.size)
                if (n < 0) { Log.w(TAG, "AudioRecord.read=$n"); return false }
                if (n == 0) { delay(10); continue }
                debugFeed?.let { feed ->
                    for (i in 0 until n) { frame[i] = feed[feedPos]; feedPos = (feedPos + 1) % feed.size }
                }
                if (splitRequested) {
                    splitRequested = false
                    seg.closeIfOpen()
                    tracker.closeActive()
                }
                seg.onFrame(frame, n, now)
                tracker.tick(now, seg.isOpen)
                if (now - lastUi > 120) {
                    lastUi = now
                    val lv = seg.level
                    val open = seg.isOpen
                    RecorderState.update { it.copy(level = lv, inSpeech = open) }
                }
                if (now - lastCheck > 3_000) {
                    lastCheck = now
                    // Đang gọi điện / app khác giữ micro => Android cho app này nhận im lặng
                    val silenced = rec.activeRecordingConfiguration?.isClientSilenced == true
                    if (silenced != RecorderState.state.value.silenced) {
                        RecorderState.update { it.copy(silenced = silenced) }
                        refreshNotification()
                    }
                }
                if (now - lastWake > 3_600_000L) { lastWake = now; acquireWakeLock(force = true) }
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "vòng ghi âm", e)
            return false
        } finally {
            runCatching { seg.closeIfOpen() }
            runCatching { if (recording) rec.stop() }
            rec.release()
            ns?.release()
            agc?.release()
        }
    }

    /** Nhận các đoạn đã cắt: lưu file + đưa vào hàng chờ chép lời. */
    private inner class ChunkSink : Segmenter.Sink {
        private var file: File? = null

        override fun open(startMs: Long): PcmWriter {
            val (f, w) = AudioFiles.create(this@RecorderService, startMs)
            file = f
            return w
        }

        override fun close(info: Segmenter.ChunkInfo) {
            val f = file ?: return
            file = null
            Log.i(TAG, "Đoạn ${Fmt.hms(info.startMs)} dài ${info.durationMs} ms, có tiếng ${info.voicedMs} ms, giữ=${info.keep}")
            if (!info.keep || !f.exists() || f.length() < 512) { f.delete(); return }
            try {
                val convId = tracker.assign(info.startMs, info.lastSpeechAt)
                App.dao.insertChunk(
                    Chunk(conversationId = convId, path = f.absolutePath, startedAt = info.startMs, durationMs = info.durationMs, speechMs = info.voicedMs)
                )
                Pipeline.processSoon(this@RecorderService)
            } catch (e: Exception) {
                Log.e(TAG, "lưu đoạn", e) // file vẫn còn, sẽ được khôi phục ở lần mở app sau
            }
        }
    }

    private fun setPaused(p: Boolean) {
        paused = p
        RecorderState.update { it.copy(paused = p, level = 0f, inSpeech = false) }
        refreshNotification()
        StateSync.refresh(this)
    }

    private fun refreshNotification() {
        if (RecorderState.state.value.running) Notifs.notify(this, NOTIF_ID, Notifs.recording(this, RecorderState.state.value))
    }

    private fun stopAll() {
        if (stopping) return
        stopping = true
        App.settings.wasRecording = false
        tickJob?.cancel()
        val job = loopJob
        scope.launch {
            job?.cancelAndJoin()
            withContext(Dispatchers.IO) { runCatching { tracker.closeActive() } }
            RecorderState.update { RecState() }
            releaseWakeLock()
            withContext(Dispatchers.Main) {
                ServiceCompat.stopForeground(this@RecorderService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                StateSync.refresh(this@RecorderService)
            }
        }
    }

    override fun onDestroy() {
        if (!stopping && loopJob?.isActive == true) {
            val job = loopJob
            // Chờ vòng ghi âm lưu đoạn cuối (finally) rồi mới khép cuộc hội thoại
            App.instance.io.launch {
                runCatching { job?.cancelAndJoin() }
                runCatching { tracker.closeActive() }
            }
        }
        scope.cancel()
        releaseWakeLock()
        if (RecorderState.state.value.running) RecorderState.update { RecState() }
        StateSync.refresh(this)
        super.onDestroy()
    }

    private fun acquireWakeLock(force: Boolean) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NgheNho:recording").also {
            it.setReferenceCounted(false)
            wakeLock = it
        }
        if (force || !wl.isHeld) wl.acquire(16 * 3_600_000L)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}

object Notifs {
    /** Màu thương hiệu Flowme (chàm tím) cho icon & nút trên thông báo. */
    private val BRAND = 0xFF5B47F5.toInt()
    const val CH_REC = "recording"
    const val CH_INSIGHT = "insights"
    const val CH_ALERT = "alerts"
    const val RESUME_ID = 7

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_REC, "Đang nghe", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Thông báo cố định khi Flowme đang nghe"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_INSIGHT, "Tóm tắt & lịch hẹn", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Cần bạn thao tác", NotificationManager.IMPORTANCE_HIGH))
    }

    fun notify(ctx: Context, id: Int, n: Notification) {
        try { ctx.getSystemService(NotificationManager::class.java).notify(id, n) } catch (_: SecurityException) { }
    }

    fun cancel(ctx: Context, id: Int) {
        ctx.getSystemService(NotificationManager::class.java).cancel(id)
    }

    private fun openApp(ctx: Context, convId: Long = 0L): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (convId > 0) i.putExtra(MainActivity.EXTRA_CONV, convId)
        return PendingIntent.getActivity(ctx, 100 + (convId % 100_000).toInt(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun svc(ctx: Context, action: String, req: Int): PendingIntent =
        PendingIntent.getService(ctx, req, RecorderService.intent(ctx, action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun recording(ctx: Context, st: RecState): Notification {
        val title = when {
            st.paused -> "Flowme đang tạm dừng"
            st.silenced -> "Micro đang bị cuộc gọi/ứng dụng khác dùng"
            else -> "Flowme đang nghe"
        }
        val text = if (st.paused) "Bấm Tiếp tục để nghe lại" else "Tự ghi, chép lời và tóm tắt các cuộc trò chuyện"
        return NotificationCompat.Builder(ctx, CH_REC)
            .setSmallIcon(R.drawable.ic_stat_flowme).setColor(BRAND)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(true)
            .setUsesChronometer(!st.paused)
            .setWhen(if (st.sessionStart > 0) st.sessionStart else System.currentTimeMillis())
            .setContentIntent(openApp(ctx))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                if (st.paused) R.drawable.ic_play else R.drawable.ic_pause,
                if (st.paused) "Tiếp tục" else "Tạm dừng",
                svc(ctx, if (st.paused) RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE, 2),
            )
            .addAction(R.drawable.ic_split, "Chốt đoạn", svc(ctx, RecorderService.ACTION_SPLIT, 3))
            .addAction(R.drawable.ic_stop, "Tắt", svc(ctx, RecorderService.ACTION_STOP, 4))
            .build()
    }

    /** Android chỉ cho bật micro khi người dùng chạm => thông báo có nút "Bật lại". */
    fun showResumePrompt(ctx: Context, text: String) {
        // Đi qua Activity trong suốt: kiểm tra quyền micro rồi mới bật dịch vụ
        // (gọi thẳng startForegroundService mà không kịp startForeground sẽ làm app crash)
        val i = Intent(ctx, ToggleActivity::class.java)
            .putExtra(ToggleActivity.EXTRA_START_ONLY, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val start = PendingIntent.getActivity(ctx, 9, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_flowme).setColor(BRAND)
            .setContentTitle("Flowme đã ngừng nghe")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .addAction(R.drawable.ic_mic, "Bật lại", start)
            .build()
        notify(ctx, RESUME_ID, n)
    }

    fun showSummary(ctx: Context, c: Conversation) {
        val n = NotificationCompat.Builder(ctx, CH_INSIGHT)
            .setSmallIcon(R.drawable.ic_stat_flowme).setColor(BRAND)
            .setContentTitle("${c.emoji} ${c.title}".trim())
            .setContentText(c.overview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(c.overview))
            .setContentIntent(openApp(ctx, c.id))
            .setAutoCancel(true)
            .setGroup("summaries")
            .build()
        notify(ctx, 1000 + (c.id % 100_000).toInt(), n)
    }

    fun eventNotifId(id: Long): Int = 200_000 + (id % 100_000).toInt()

    const val CLEANUP_ID = 300

    /** Sáng hôm sau: hỏi xoá các cuộc hội thoại cũ đã lưu lịch xong (nút Xoá / Giữ lại). */
    fun showCleanup(ctx: Context, count: Int, openTasks: Int) {
        val text = "$count cuộc hội thoại của những ngày trước đã lưu lịch hẹn xong. Xoá cho gọn?" +
            if (openTasks > 0) " ($openTasks việc chưa xong vẫn được giữ trong tab Việc.)" else ""
        fun pi(action: String, req: Int) = PendingIntent.getBroadcast(
            ctx, req, Intent(ctx, ActionReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CH_INSIGHT)
            .setSmallIcon(R.drawable.ic_stat_flowme).setColor(BRAND)
            .setContentTitle("🧹 Dọn trí nhớ hôm qua?")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .addAction(0, "Xoá $count cuộc", pi(ActionReceiver.CLEANUP_DELETE, 301))
            .addAction(0, "Giữ lại", pi(ActionReceiver.CLEANUP_KEEP, 302))
            .build()
        notify(ctx, CLEANUP_ID, n)
    }

    private fun eventAction(ctx: Context, action: String, id: Long): PendingIntent {
        val req = eventNotifId(id) * 2 + if (action == ActionReceiver.ADD_EVENT) 0 else 1
        val i = Intent(ctx, ActionReceiver::class.java).setAction(action).putExtra("id", id)
        return PendingIntent.getBroadcast(ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun showEvent(ctx: Context, ev: CalEvent, added: Boolean, canAdd: Boolean) {
        val whenTxt = Fmt.dateTime(ev.startAt) + if (ev.location.isNotBlank()) " · ${ev.location}" else ""
        val b = NotificationCompat.Builder(ctx, CH_INSIGHT)
            .setSmallIcon(R.drawable.ic_event).setColor(BRAND)
            .setContentTitle(if (added) "Đã thêm vào Lịch: ${ev.title}" else "📅 ${ev.title}")
            .setContentText(whenTxt)
            .setContentIntent(openApp(ctx, ev.conversationId))
            .setAutoCancel(true)
        if (!added) {
            if (canAdd) b.addAction(R.drawable.ic_event, "Thêm vào lịch", eventAction(ctx, ActionReceiver.ADD_EVENT, ev.id))
            b.addAction(0, "Bỏ qua", eventAction(ctx, ActionReceiver.DISMISS_EVENT, ev.id))
        }
        notify(ctx, eventNotifId(ev.id), b.build())
    }
}
