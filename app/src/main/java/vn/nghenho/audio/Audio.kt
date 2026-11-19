package vn.nghenho.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioFiles {
    const val SAMPLE_RATE = 16000

    fun dir(ctx: Context): File = File(ctx.filesDir, "audio").apply { mkdirs() }

    /** Tạo file cho một đoạn mới: ưu tiên AAC (nhẹ ~14 MB/giờ), dự phòng WAV nếu máy không có bộ mã hoá. */
    fun create(ctx: Context, startMs: Long): Pair<File, PcmWriter> {
        val d = dir(ctx)
        val aac = File(d, "c_$startMs.aac")
        return try {
            aac to AdtsAacWriter(aac, SAMPLE_RATE)
        } catch (e: Exception) {
            Log.w("NgheNho", "AAC encoder lỗi, dùng WAV", e)
            aac.delete()
            val wav = File(d, "c_$startMs.wav")
            wav to WavWriter(wav, SAMPLE_RATE)
        }
    }

    fun mimeOf(f: File): String = when (f.extension.lowercase()) {
        "wav" -> "audio/wav"
        "m4a", "mp4" -> "audio/mp4"
        else -> "audio/aac"
    }

    fun estimateDurationMs(f: File): Long =
        if (f.extension.equals("wav", true)) (f.length() - 44).coerceAtLeast(0) * 1000 / (SAMPLE_RATE * 2)
        else f.length() * 8 * 1000 / AdtsAacWriter.BIT_RATE
}

/** Mã hoá PCM => AAC-LC, ghi dạng ADTS (.aac). */
class AdtsAacWriter(file: File, private val sampleRate: Int, bitRate: Int = BIT_RATE) : PcmWriter {
    companion object { const val BIT_RATE = 32_000 }

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val out: BufferedOutputStream
    private val info = MediaCodec.BufferInfo()
    private var totalSamples = 0L
    private var finished = false

    init {
        try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            out = BufferedOutputStream(FileOutputStream(file), 16 * 1024)
        } catch (e: Exception) {
            runCatching { codec.release() }
            throw e
        }
    }

    override fun write(pcm: ShortArray, n: Int) {
        if (finished) return
        var off = 0
        var spins = 0
        while (off < n) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = codec.getInputBuffer(idx) ?: break
                buf.clear()
                buf.order(ByteOrder.LITTLE_ENDIAN)
                val count = minOf(buf.remaining() / 2, n - off)
                buf.asShortBuffer().put(pcm, off, count)
                val pts = totalSamples * 1_000_000L / sampleRate
                codec.queueInputBuffer(idx, 0, count * 2, pts, 0)
                totalSamples += count
                off += count
            } else if (++spins > 200) {
                break // codec kẹt: bỏ khung này thay vì treo luồng ghi âm
            }
            drain(false)
        }
    }

    private fun drain(endOfStream: Boolean) {
        var waits = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream || ++waits > 100) return
                continue
            }
            if (idx < 0) continue // INFO_OUTPUT_FORMAT_CHANGED…
            val ob = codec.getOutputBuffer(idx)
            if (ob != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                val bytes = ByteArray(info.size)
                ob.position(info.offset)
                ob.get(bytes, 0, info.size)
                out.write(Adts.header(info.size, sampleRate))
                out.write(bytes)
            }
            codec.releaseOutputBuffer(idx, false)
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
        }
    }

    override fun finish() {
        if (finished) return
        finished = true
        try {
            val idx = codec.dequeueInputBuffer(50_000)
            if (idx >= 0) {
                codec.queueInputBuffer(idx, 0, 0, totalSamples * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drain(true)
        } catch (e: Exception) {
            Log.w("NgheNho", "AAC finish", e)
        }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { out.flush(); out.close() }
    }
}

/** Dự phòng: WAV PCM 16-bit mono. */
class WavWriter(file: File, private val sampleRate: Int) : PcmWriter {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L

    init {
        raf.setLength(0)
        raf.write(header(0))
    }

    override fun write(pcm: ShortArray, n: Int) {
        val bytes = ByteArray(n * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, 0, n)
        raf.write(bytes)
        dataBytes += bytes.size
    }

    override fun finish() {
        runCatching {
            raf.seek(0)
            raf.write(header(dataBytes))
            raf.close()
        }
    }

    private fun header(data: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + data).toInt()); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(sampleRate); b.putInt(sampleRate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(data.toInt())
        return b.array()
    }
}

object AudioConvert {
    /** ADTS .aac => .m4a (OpenAI/Groq/Whisper tự host không nhận .aac thô). */
    fun adtsToM4a(src: File, dst: File) {
        val ex = MediaExtractor()
        var mux: MediaMuxer? = null
        try {
            ex.setDataSource(src.absolutePath)
            require(ex.trackCount > 0) { "Không đọc được âm thanh" }
            val fmt = ex.getTrackFormat(0)
            ex.selectTrack(0)
            val m = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = m
            val track = m.addTrack(fmt)
            m.start()
            val buf = ByteBuffer.allocate(256 * 1024)
            val info = MediaCodec.BufferInfo()
            var wrote = 0
            while (true) {
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                info.set(0, n, ex.sampleTime, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                m.writeSampleData(track, buf, info)
                wrote++
                ex.advance()
            }
            require(wrote > 0) { "File âm thanh rỗng" }
            m.stop()
        } finally {
            runCatching { mux?.release() }
            ex.release()
        }
    }
}
