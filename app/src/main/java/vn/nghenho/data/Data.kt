package vn.nghenho.data

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaRecorder
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import vn.nghenho.audio.AudioFiles
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

// ───────────────────────── Trạng thái ─────────────────────────

object ChunkStatus {
    const val PENDING = 1   // đã ghi xong, chờ chép lời (kể cả khi offline)
    const val DONE = 2
    const val FAILED = 3
    const val EMPTY = 4     // không có lời nói
}

object ConvStatus {
    const val ACTIVE = 0    // đang diễn ra
    const val CLOSED = 1    // đã kết thúc, chờ tóm tắt
    const val DONE = 2
    const val FAILED = 3
}

object EventState {
    const val SUGGESTED = 0
    const val ADDED = 1
    const val DISMISSED = 2
}

// ───────────────────────── Bảng dữ liệu ─────────────────────────

@Entity(tableName = "conversations", indices = [Index("startedAt"), Index("status")])
data class Conversation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long,
    val status: Int = ConvStatus.ACTIVE,
    val title: String = "",
    val emoji: String = "",
    val category: String = "",
    val overview: String = "",
    val keyPoints: String = "[]",
    val mindmap: String = "",
    val trivial: Boolean = false,
    val error: String? = null,
    val attempts: Int = 0,
    val notionUrl: String? = null,
    val processedAt: Long = 0,
)

@Entity(tableName = "chunks", indices = [Index("conversationId"), Index("status")])
data class Chunk(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val path: String,
    val startedAt: Long,
    val durationMs: Long = 0,
    val speechMs: Long = 0,
    val status: Int = ChunkStatus.PENDING,
    val attempts: Int = 0,
    val error: String? = null,
    val provider: String = "",
)

@Entity(tableName = "segments", indices = [Index("conversationId"), Index("chunkId"), Index("startAt")])
data class Segment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val chunkId: Long,
    val startAt: Long,
    val endAt: Long,
    val speaker: String,
    val text: String,
)

@Entity(tableName = "action_items", indices = [Index("conversationId")])
data class ActionItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val text: String,
    val owner: String = "",
    val due: String = "",
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "events", indices = [Index("conversationId"), Index("startAt")])
data class CalEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val title: String,
    val startAt: Long,
    val endAt: Long,
    val location: String = "",
    val notes: String = "",
    val state: Int = EventState.SUGGESTED,
    val calendarEventId: Long = 0,
)

@Entity(tableName = "chat")
data class ChatMsg(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
)

// ───────────────────────── Truy vấn ─────────────────────────

@Dao
abstract class MemDao {
    // Cuộc hội thoại
    @Insert abstract fun insertConv(c: Conversation): Long
    @Update abstract fun updateConv(c: Conversation)
    @Query("SELECT * FROM conversations WHERE id = :id") abstract fun conv(id: Long): Conversation?
    @Query("SELECT * FROM conversations WHERE id = :id") abstract fun convFlow(id: Long): Flow<Conversation?>
    @Query("SELECT * FROM conversations ORDER BY startedAt DESC LIMIT 1500") abstract fun convsFlow(): Flow<List<Conversation>>
    /** Trả về 0 nếu cuộc hội thoại không còn (đã bị xoá). */
    @Query("UPDATE conversations SET endedAt = MAX(endedAt, :t) WHERE id = :id") abstract fun touchConv(id: Long, t: Long): Int
    @Query("UPDATE conversations SET status = :status WHERE id = :id") abstract fun setConvStatus(id: Long, status: Int)
    @Query("UPDATE conversations SET status = 1, attempts = 0, error = NULL WHERE id = :id") abstract fun reopenConv(id: Long)
    @Query("UPDATE conversations SET notionUrl = :url WHERE id = :id") abstract fun setNotionUrl(id: Long, url: String)
    @Query("SELECT * FROM conversations WHERE status = 1 ORDER BY startedAt") abstract fun closedConvs(): List<Conversation>
    @Query("UPDATE conversations SET status = 1 WHERE status = 0 AND startedAt < :before") abstract fun closeStaleActive(before: Long): Int
    @Query("UPDATE conversations SET status = 1, attempts = 0, error = NULL WHERE status = 3") abstract fun retryFailedConvs(): Int
    @Query("DELETE FROM conversations WHERE id = :id") abstract fun deleteConv(id: Long)
    @Query("SELECT * FROM conversations WHERE startedAt >= :from AND startedAt < :to ORDER BY startedAt") abstract fun convsBetween(from: Long, to: Long): List<Conversation>
    @Query("SELECT * FROM conversations WHERE status = 2 AND (title LIKE :q OR overview LIKE :q OR keyPoints LIKE :q OR category LIKE :q) ORDER BY startedAt DESC LIMIT :limit")
    abstract fun searchConvs(q: String, limit: Int): List<Conversation>
    @Query("SELECT * FROM conversations WHERE status = 2 AND trivial = 0 ORDER BY startedAt DESC LIMIT :limit") abstract fun recentDone(limit: Int): List<Conversation>
    @Query("SELECT * FROM conversations WHERE id IN (:ids)") abstract fun convsByIds(ids: List<Long>): List<Conversation>
    @Query("SELECT COUNT(*) FROM conversations WHERE startedAt >= :from AND trivial = 0") abstract fun convCountSinceFlow(from: Long): Flow<Int>
    @Query("SELECT * FROM conversations ORDER BY startedAt") abstract fun allConvs(): List<Conversation>

    // Đoạn âm thanh
    @Insert abstract fun insertChunk(c: Chunk): Long
    @Update abstract fun updateChunk(c: Chunk)
    @Query("SELECT * FROM chunks WHERE status = 1 ORDER BY startedAt LIMIT 1") abstract fun nextPending(): Chunk?
    @Query("SELECT * FROM chunks WHERE conversationId = :cid ORDER BY startedAt") abstract fun chunksOf(cid: Long): List<Chunk>
    @Query("SELECT * FROM chunks WHERE conversationId = :cid ORDER BY startedAt") abstract fun chunksFlow(cid: Long): Flow<List<Chunk>>
    @Query("SELECT COUNT(*) FROM chunks WHERE conversationId = :cid AND status = 1") abstract fun pendingChunksOf(cid: Long): Int
    @Query("SELECT COUNT(*) FROM chunks WHERE status = 1") abstract fun pendingCountFlow(): Flow<Int>
    @Query("SELECT COUNT(*) FROM chunks WHERE status = 3") abstract fun failedCountFlow(): Flow<Int>
    @Query("UPDATE chunks SET status = 1, attempts = 0, error = NULL WHERE status = 3 AND path != ''") abstract fun retryFailedChunks(): Int
    @Query("UPDATE chunks SET status = 1, attempts = 0, error = NULL WHERE conversationId = :cid AND path != ''") abstract fun resetChunksOf(cid: Long): Int
    @Query("SELECT * FROM chunks WHERE status IN (2, 4) AND startedAt < :before AND path != ''") abstract fun oldProcessedChunks(before: Long): List<Chunk>
    @Query("UPDATE chunks SET path = '' WHERE id = :id") abstract fun clearChunkPath(id: Long)
    @Query("SELECT path FROM chunks WHERE path != ''") abstract fun allChunkPaths(): List<String>
    @Query("SELECT COALESCE(SUM(speechMs), 0) FROM chunks WHERE startedAt >= :from") abstract fun speechSinceFlow(from: Long): Flow<Long>
    @Query("DELETE FROM chunks WHERE conversationId = :cid") abstract fun deleteChunksOfConv(cid: Long)
    @Query("DELETE FROM chunks WHERE id = :id") abstract fun deleteChunk(id: Long)
    @Query("SELECT COUNT(*) FROM chunks WHERE status = 1") abstract fun pendingCount(): Int
    @Query("SELECT COUNT(*) FROM conversations WHERE status = 1") abstract fun closedCount(): Int
    @Query("SELECT COUNT(*) FROM conversations") abstract fun convCountFlow(): Flow<Int>
    @Query("SELECT error FROM chunks WHERE status = 1 AND error IS NOT NULL AND error != '' ORDER BY startedAt DESC LIMIT 1")
    abstract fun latestPendingErrorFlow(): Flow<String?>
    @Query("SELECT * FROM chunks WHERE path != '' ORDER BY startedAt DESC LIMIT 1") abstract fun latestChunkWithFile(): Chunk?
    @Query("SELECT * FROM conversations WHERE startedAt >= :from AND startedAt < :to AND status IN (2, 3) ORDER BY startedAt")
    abstract fun finishedBetween(from: Long, to: Long): List<Conversation>
    @Query("UPDATE segments SET speaker = :speaker WHERE id = :id") abstract fun setSegmentSpeaker(id: Long, speaker: String)
    @Query("DELETE FROM action_items WHERE conversationId = :cid AND done = 1") abstract fun deleteDoneActionsOf(cid: Long)

    @Transaction
    open fun setSpeakers(ids: List<Long>, labels: List<String>) {
        ids.forEachIndexed { i, id -> setSegmentSpeaker(id, labels[i]) }
    }

    // Lời thoại
    @Insert abstract fun insertSegments(list: List<Segment>)
    @Query("DELETE FROM segments WHERE chunkId = :chunkId") abstract fun deleteSegmentsOfChunk(chunkId: Long)
    @Query("DELETE FROM segments WHERE conversationId = :cid") abstract fun deleteSegmentsOfConv(cid: Long)
    @Query("SELECT * FROM segments WHERE conversationId = :cid ORDER BY startAt, id") abstract fun segmentsOf(cid: Long): List<Segment>
    @Query("SELECT * FROM segments WHERE conversationId = :cid ORDER BY startAt, id") abstract fun segmentsFlow(cid: Long): Flow<List<Segment>>
    @Query("SELECT * FROM segments ORDER BY startAt DESC, id DESC LIMIT :n") abstract fun latestSegmentsFlow(n: Int): Flow<List<Segment>>
    @Query("UPDATE segments SET speaker = :newName WHERE conversationId = :cid AND speaker = :old") abstract fun renameSpeaker(cid: Long, old: String, newName: String)
    @Query("SELECT * FROM segments WHERE text LIKE :q ORDER BY startAt DESC LIMIT :limit") abstract fun searchSegments(q: String, limit: Int): List<Segment>
    @Query("SELECT DISTINCT conversationId FROM segments WHERE text LIKE :q LIMIT 300") abstract fun convIdsWithText(q: String): List<Long>

    // Việc cần làm
    @Insert abstract fun insertActions(list: List<ActionItem>)
    @Insert abstract fun insertAction(a: ActionItem): Long
    @Query("DELETE FROM action_items WHERE conversationId = :cid") abstract fun deleteActionsOf(cid: Long)
    @Query("SELECT * FROM action_items WHERE conversationId = :cid ORDER BY id") abstract fun actionsFlow(cid: Long): Flow<List<ActionItem>>
    @Query("SELECT * FROM action_items WHERE conversationId = :cid ORDER BY id") abstract fun actionsOf(cid: Long): List<ActionItem>
    @Query("SELECT * FROM action_items ORDER BY done, createdAt DESC LIMIT 800") abstract fun allActionsFlow(): Flow<List<ActionItem>>
    @Query("SELECT * FROM action_items WHERE done = 0 ORDER BY createdAt DESC LIMIT :n") abstract fun openActions(n: Int): List<ActionItem>
    @Query("UPDATE action_items SET done = :done WHERE id = :id") abstract fun setActionDone(id: Long, done: Boolean)
    @Query("DELETE FROM action_items WHERE id = :id") abstract fun deleteAction(id: Long)

    // Lịch hẹn
    @Insert abstract fun insertEvents(list: List<CalEvent>): List<Long>
    @Query("DELETE FROM events WHERE conversationId = :cid AND state = 0") abstract fun deleteSuggestedEventsOf(cid: Long)
    @Query("DELETE FROM events WHERE conversationId = :cid") abstract fun deleteEventsOf(cid: Long)
    @Query("SELECT * FROM events WHERE conversationId = :cid ORDER BY startAt") abstract fun eventsFlow(cid: Long): Flow<List<CalEvent>>
    @Query("SELECT * FROM events WHERE conversationId = :cid ORDER BY startAt") abstract fun eventsOf(cid: Long): List<CalEvent>
    @Query("SELECT * FROM events WHERE id = :id") abstract fun event(id: Long): CalEvent?
    @Query("UPDATE events SET state = :state, calendarEventId = :calId WHERE id = :id") abstract fun setEventState(id: Long, state: Int, calId: Long)
    @Query("SELECT * FROM events WHERE startAt >= :from AND state != 2 ORDER BY startAt LIMIT :n") abstract fun upcomingEvents(from: Long, n: Int): List<CalEvent>
    @Query("SELECT * FROM events WHERE startAt >= :from AND state != 2 ORDER BY startAt LIMIT 60") abstract fun upcomingFlow(from: Long): Flow<List<CalEvent>>

    // Hỏi đáp
    @Insert abstract fun insertChat(m: ChatMsg): Long
    @Query("SELECT * FROM chat ORDER BY id") abstract fun chatFlow(): Flow<List<ChatMsg>>
    @Query("SELECT * FROM chat ORDER BY id DESC LIMIT :n") abstract fun lastChat(n: Int): List<ChatMsg>
    @Query("DELETE FROM chat") abstract fun clearChat()

    @Transaction
    open fun replaceTranscript(chunk: Chunk, segs: List<Segment>) {
        deleteSegmentsOfChunk(chunk.id)
        if (segs.isNotEmpty()) insertSegments(segs)
        updateChunk(chunk)
    }

    @Transaction
    open fun saveInsight(conv: Conversation, actions: List<ActionItem>, events: List<CalEvent>): List<Long> {
        updateConv(conv)
        deleteActionsOf(conv.id)
        if (actions.isNotEmpty()) insertActions(actions)
        deleteSuggestedEventsOf(conv.id)
        return if (events.isEmpty()) emptyList() else insertEvents(events)
    }

    @Transaction
    open fun deleteConversationRows(cid: Long, keepOpenActions: Boolean) {
        deleteSegmentsOfConv(cid)
        deleteChunksOfConv(cid)
        if (keepOpenActions) deleteDoneActionsOf(cid) else deleteActionsOf(cid)
        deleteEventsOf(cid)
        deleteConv(cid)
    }
}

@Database(
    entities = [Conversation::class, Chunk::class, Segment::class, ActionItem::class, CalEvent::class, ChatMsg::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): MemDao

    companion object {
        fun create(ctx: Context): AppDb = Room.databaseBuilder(ctx, AppDb::class.java, "nghenho.db").build()
    }
}

// ───────────────────────── Cài đặt ─────────────────────────

/** Cài đặt lưu trong SharedPreferences; API key được mã hoá bằng khoá trong Android Keystore. */
class Settings(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("nghenho", Context.MODE_PRIVATE)
    private val box = SecretBox()

    /** Tăng mỗi khi có thay đổi, để giao diện vẽ lại. */
    val changes = MutableStateFlow(0)
    private fun bump() = changes.update { it + 1 }

    private inner class Str(private val k: String, private val d: String) : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String = p.getString(k, d) ?: d
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) { p.edit().putString(k, value).apply(); bump() }
    }
    private inner class IntP(private val k: String, private val d: Int) : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int = p.getInt(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { p.edit().putInt(k, value).apply(); bump() }
    }
    private inner class LongP(private val k: String, private val d: Long) : ReadWriteProperty<Any?, Long> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Long = p.getLong(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) { p.edit().putLong(k, value).apply(); bump() }
    }
    private inner class BoolP(private val k: String, private val d: Boolean) : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = p.getBoolean(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) { p.edit().putBoolean(k, value).apply(); bump() }
    }

    /**
     * Bản nháp (câu hỏi đang gõ dở…): ghi ngay vào máy nên thoát/mở lại app vẫn còn.
     * Cố ý KHÔNG gọi bump() – tránh vẽ lại giao diện mỗi lần gõ phím.
     */
    fun draft(key: String): String = p.getString("draft_$key", "") ?: ""
    fun setDraft(key: String, text: String) { p.edit().putString("draft_$key", text).apply() }

    var userName by Str("user_name", "")
    /** vi_en = Việt + English (giữ nguyên ngôn ngữ gốc khi nói xen kẽ), vi, en, auto */
    var language by Str("language", "vi_en")
    /** Ngôn ngữ bản tóm tắt: vi, en, same (theo cuộc trò chuyện) */
    var summaryLang by Str("summary_lang", "vi")
    var sttProvider by Str("stt_provider", "gemini")
    var sttModel by Str("stt_model", "")
    var llmProvider by Str("llm_provider", "gemini")
    var llmModel by Str("llm_model", "")
    var customBaseUrl by Str("custom_base_url", "")

    var sensitivity by IntP("sensitivity", 1)
    var latencyMode by IntP("latency", 1)
    var convGapMin by IntP("conv_gap_min", 3)
    var audioSource by IntP("audio_source", MediaRecorder.AudioSource.MIC)
    var noiseSuppress by BoolP("noise_suppress", true)
    var autoGain by BoolP("auto_gain", true)

    var wifiOnly by BoolP("wifi_only", false)
    var keepAudioDays by IntP("keep_audio_days", 7)

    var autoCalendar by BoolP("auto_calendar", false)
    var calendarId by LongP("calendar_id", -1L)
    var notifySummaries by BoolP("notify_summaries", true)
    var notionDbId by Str("notion_db", "")
    var notionAuto by BoolP("notion_auto", false)
    var webhookUrl by Str("webhook_url", "")
    var webhookAuto by BoolP("webhook_auto", false)
    var emailTo by Str("email_to", "")

    var wasRecording by BoolP("was_recording", false)
    /** Lỗi xử lý cần người dùng sửa (vd. API key bị từ chối); rỗng = không có lỗi. */
    var lastError by Str("last_error", "")

    /** Dọn trí nhớ mỗi sáng */
    var askCleanupDaily by BoolP("ask_cleanup_daily", true)
    var cleanupKeptBefore by LongP("cleanup_kept_before", 0L)
    var cleanupPromptDay by Str("cleanup_prompt_day", "")

    fun key(id: String): String = p.getString("key_$id", null)?.let { box.decrypt(it) } ?: ""

    fun setKey(id: String, value: String) {
        val v = value.trim()
        if (v.isEmpty()) p.edit().remove("key_$id").apply()
        else box.encrypt(v)?.let { p.edit().putString("key_$id", it).apply() }
        bump()
    }
}

class SecretBox(private val alias: String = "nghenho_api_keys") {
    @Volatile private var cached: SecretKey? = null

    private fun key(): SecretKey {
        cached?.let { return it }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey(alias, null) as? SecretKey
        val k = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
        cached = k
        return k
    }

    fun encrypt(plain: String): String? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }.getOrNull()

    fun decrypt(enc: String): String? = runCatching {
        val all = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
        String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    }.getOrNull()
}

// ───────────────────────── Khôi phục sau sự cố ─────────────────────────

object Recovery {
    fun run(ctx: Context, dao: MemDao, processStart: Long) {
        dao.closeStaleActive(processStart)
        recoverOrphans(ctx, dao)
    }

    /** File âm thanh đã ghi nhưng chưa kịp lưu vào DB (app bị tắt đột ngột) => đưa lại vào hàng chờ. */
    private fun recoverOrphans(ctx: Context, dao: MemDao) {
        val known = dao.allChunkPaths().toHashSet()
        val files = AudioFiles.dir(ctx).listFiles()
            ?.filter { it.isFile && it.name.startsWith("c_") && it.absolutePath !in known }
            ?.sortedBy { it.name } ?: return
        var convId = 0L
        var lastEnd = 0L
        for (f in files) {
            if (f.length() < 4096) { f.delete(); continue }
            val start = f.nameWithoutExtension.removePrefix("c_").toLongOrNull() ?: f.lastModified()
            val dur = AudioFiles.estimateDurationMs(f)
            if (convId == 0L || start - lastEnd > 3 * 60_000L) {
                convId = dao.insertConv(Conversation(startedAt = start, endedAt = start + dur, status = ConvStatus.CLOSED))
            }
            dao.insertChunk(Chunk(conversationId = convId, path = f.absolutePath, startedAt = start, durationMs = dur, speechMs = dur))
            dao.touchConv(convId, start + dur)
            lastEnd = start + dur
        }
    }
}
