package vn.nghenho.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Nơi nhận PCM 16-bit mono của một đoạn (AAC hoặc WAV). */
interface PcmWriter {
    fun write(pcm: ShortArray, n: Int)
    fun finish()
}

data class SegmenterConfig(
    val marginDb: Double = 10.0,       // tiếng nói phải to hơn nền ồn bao nhiêu dB
    val absMinDb: Double = -55.0,      // ngưỡng tuyệt đối tối thiểu (dBFS)
    val startFrames: Int = 3,          // số khung 20ms liên tiếp có tiếng để mở đoạn
    val preRollMs: Long = 800,         // giữ lại âm thanh trước khi phát hiện tiếng nói
    val minChunkMs: Long = 60_000,     // đoạn đủ dài này thì cắt ở chỗ ngắt nghỉ
    val cutSilenceMs: Long = 1_000,    // khoảng lặng để cắt khi đoạn đã đủ dài
    val endSilenceMs: Long = 6_000,    // im lặng bấy lâu thì đóng đoạn dù ngắn
    val maxChunkMs: Long = 360_000,    // trần độ dài một đoạn
    val minVoicedMs: Long = 1_200,     // ít tiếng nói hơn mức này => bỏ (tiếng động lạ)
) {
    companion object {
        fun of(sensitivity: Int, latency: Int): SegmenterConfig {
            val (margin, absMin) = when (sensitivity) {
                0 -> 14.0 to -48.0
                2 -> 7.0 to -62.0
                else -> 10.0 to -55.0
            }
            return when (latency) {
                0 -> SegmenterConfig(margin, absMin, minChunkMs = 20_000, cutSilenceMs = 700, endSilenceMs = 3_500, maxChunkMs = 180_000)
                2 -> SegmenterConfig(margin, absMin, minChunkMs = 180_000, cutSilenceMs = 1_200, endSilenceMs = 8_000, maxChunkMs = 600_000)
                else -> SegmenterConfig(margin, absMin)
            }
        }
    }
}

/**
 * Phát hiện tiếng nói (VAD theo năng lượng + nền ồn thích nghi) và cắt luồng âm thanh liên tục
 * thành các đoạn có lời nói. Bỏ qua im lặng => tiết kiệm pin, dung lượng và chi phí AI.
 */
class Segmenter(private val sampleRate: Int, private val cfg: SegmenterConfig, private val sink: Sink) {

    interface Sink {
        fun open(startMs: Long): PcmWriter
        fun close(info: ChunkInfo)
    }

    data class ChunkInfo(val startMs: Long, val durationMs: Long, val voicedMs: Long, val lastSpeechAt: Long, val keep: Boolean)

    private var noiseDb = -60.0
    private val preRoll = ArrayDeque<ShortArray>()
    private var writer: PcmWriter? = null
    private var chunkStart = 0L
    private var chunkSamples = 0L
    private var voicedMs = 0L
    private var silenceRunMs = 0L
    private var speechStreak = 0
    private var lastSpeechAt = 0L

    var level = 0f
        private set
    val isOpen: Boolean get() = writer != null

    fun onFrame(buf: ShortArray, n: Int, nowMs: Long) {
        if (n <= 0) return
        val frameMs = n * 1000L / sampleRate
        val db = rmsDb(buf, n)
        noiseDb = if (db < noiseDb) noiseDb + 0.08 * (db - noiseDb) else noiseDb + 0.001 * (db - noiseDb)
        noiseDb = noiseDb.coerceIn(-90.0, -30.0)
        val speech = db > max(noiseDb + cfg.marginDb, cfg.absMinDb)
        level = ((db + 75.0) / 65.0).coerceIn(0.0, 1.0).toFloat()
        if (speech) { speechStreak++; lastSpeechAt = nowMs } else speechStreak = 0

        val w = writer
        if (w == null) {
            preRoll.addLast(buf.copyOf(n))
            while (preRoll.size * frameMs > cfg.preRollMs) preRoll.removeFirst()
            if (speechStreak >= cfg.startFrames) {
                val start = nowMs - (preRoll.size - 1) * frameMs
                val nw = try { sink.open(start) } catch (e: Exception) { null } ?: return
                writer = nw
                chunkStart = start
                chunkSamples = 0
                voicedMs = speechStreak * frameMs
                silenceRunMs = 0
                for (f in preRoll) { nw.write(f, f.size); chunkSamples += f.size }
                preRoll.clear()
            }
            return
        }
        w.write(buf, n)
        chunkSamples += n
        if (speech) { voicedMs += frameMs; silenceRunMs = 0 } else silenceRunMs += frameMs
        val dur = chunkSamples * 1000 / sampleRate
        if (silenceRunMs >= cfg.endSilenceMs ||
            (dur >= cfg.minChunkMs && silenceRunMs >= cfg.cutSilenceMs) ||
            dur >= cfg.maxChunkMs
        ) close()
    }

    fun closeIfOpen() { if (writer != null) close() }

    /** Tạm dừng: đóng đoạn đang ghi và bỏ âm thanh đệm cũ (tránh dính vào đoạn sau khi tiếp tục). */
    fun reset() {
        closeIfOpen()
        preRoll.clear()
        speechStreak = 0
    }

    private fun close() {
        val w = writer ?: return
        writer = null
        runCatching { w.finish() }
        val dur = chunkSamples * 1000 / sampleRate
        val last = if (lastSpeechAt >= chunkStart) lastSpeechAt else chunkStart + dur
        silenceRunMs = 0
        sink.close(ChunkInfo(chunkStart, dur, voicedMs, last, keep = voicedMs >= cfg.minVoicedMs))
    }

    companion object {
        fun rmsDb(b: ShortArray, n: Int): Double {
            var sum = 0.0
            for (i in 0 until n) { val v = b[i].toDouble(); sum += v * v }
            val rms = sqrt(sum / max(1, n))
            return if (rms < 1e-9) -120.0 else 20 * log10(rms / 32768.0)
        }
    }
}

/** Header ADTS cho từng khung AAC-LC => file .aac phát/truyền được ngay cả khi app bị tắt giữa chừng. */
object Adts {
    private val FREQS = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    fun header(frameLen: Int, sampleRate: Int, channels: Int = 1): ByteArray {
        val packetLen = frameLen + 7
        val freqIdx = FREQS.indexOf(sampleRate).let { if (it < 0) 8 else it }
        val profile = 2 // AAC LC
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            (((profile - 1) shl 6) or (freqIdx shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (packetLen shr 11)).toByte(),
            ((packetLen and 0x7FF) shr 3).toByte(),
            (((packetLen and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }
}
