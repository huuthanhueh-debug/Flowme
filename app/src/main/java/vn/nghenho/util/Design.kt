package vn.nghenho.util

import vn.nghenho.data.ChatMsg
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Bảng màu Flowme (ARGB). Không phụ thuộc Compose để kiểm thử được độ tương phản (WCAG) bằng unit test.
 * Ý tưởng: chàm → tím (thương hiệu), xanh ngọc (điểm nhấn), hồng đỏ (đang ghi), nền trung tính hơi lạnh.
 */
object BrandPalette {
    data class Scheme(
        val primary: Long, val onPrimary: Long, val primaryContainer: Long, val onPrimaryContainer: Long,
        val secondary: Long, val onSecondary: Long, val secondaryContainer: Long, val onSecondaryContainer: Long,
        val tertiary: Long, val onTertiary: Long, val tertiaryContainer: Long, val onTertiaryContainer: Long,
        val error: Long, val onError: Long, val errorContainer: Long, val onErrorContainer: Long,
        val background: Long, val onBackground: Long,
        val surface: Long, val onSurface: Long, val surfaceVariant: Long, val onSurfaceVariant: Long,
        val surfaceLowest: Long, val surfaceLow: Long, val surfaceMid: Long, val surfaceHigh: Long, val surfaceHighest: Long,
        val outline: Long, val outlineVariant: Long,
        val warningContainer: Long, val onWarningContainer: Long, val warning: Long,
        val successContainer: Long, val onSuccessContainer: Long, val success: Long,
    )

    val Light = Scheme(
        primary = 0xFF5B47F5, onPrimary = 0xFFFFFFFF, primaryContainer = 0xFFE9E5FF, onPrimaryContainer = 0xFF1E1460,
        secondary = 0xFF0E7490, onSecondary = 0xFFFFFFFF, secondaryContainer = 0xFFEDEBFF, onSecondaryContainer = 0xFF1E1460,
        tertiary = 0xFFE11D48, onTertiary = 0xFFFFFFFF, tertiaryContainer = 0xFFFFE1E7, onTertiaryContainer = 0xFF5C0F1C,
        error = 0xFFD92D20, onError = 0xFFFFFFFF, errorContainer = 0xFFFEE4E2, onErrorContainer = 0xFF7A1B13,
        background = 0xFFF6F7FB, onBackground = 0xFF111322,
        surface = 0xFFF6F7FB, onSurface = 0xFF111322, surfaceVariant = 0xFFE6E8F0, onSurfaceVariant = 0xFF5A5F73,
        surfaceLowest = 0xFFFFFFFF, surfaceLow = 0xFFFFFFFF, surfaceMid = 0xFFF0F1F7, surfaceHigh = 0xFFE9EBF3, surfaceHighest = 0xFFE2E4EE,
        outline = 0xFF8C90A3, outlineVariant = 0xFFE2E4EC,
        warningContainer = 0xFFFFF1D6, onWarningContainer = 0xFF5C3B00, warning = 0xFFB45309,
        successContainer = 0xFFDCFCE7, onSuccessContainer = 0xFF0B4A26, success = 0xFF15803D,
    )

    val Dark = Scheme(
        primary = 0xFFA99BFF, onPrimary = 0xFF1E1460, primaryContainer = 0xFF3A2CB8, onPrimaryContainer = 0xFFE9E5FF,
        secondary = 0xFF5AD7F0, onSecondary = 0xFF003642, secondaryContainer = 0xFF2E2A5C, onSecondaryContainer = 0xFFE9E5FF,
        tertiary = 0xFFFF8FA3, onTertiary = 0xFF4A0414, tertiaryContainer = 0xFF7A1E2E, onTertiaryContainer = 0xFFFFE1E7,
        error = 0xFFFF8A80, onError = 0xFF5C0B05, errorContainer = 0xFF5A1712, onErrorContainer = 0xFFFEE4E2,
        background = 0xFF0D0E14, onBackground = 0xFFECEDF3,
        surface = 0xFF0D0E14, onSurface = 0xFFECEDF3, surfaceVariant = 0xFF2A2B37, onSurfaceVariant = 0xFFA3A7B8,
        surfaceLowest = 0xFF08090D, surfaceLow = 0xFF15161E, surfaceMid = 0xFF191A23, surfaceHigh = 0xFF20212C, surfaceHighest = 0xFF2A2B37,
        outline = 0xFF6E7285, outlineVariant = 0xFF2E303C,
        warningContainer = 0xFF3A2A0A, onWarningContainer = 0xFFFFE2A8, warning = 0xFFFBBF24,
        successContainer = 0xFF0E3B22, onSuccessContainer = 0xFFBBF7D0, success = 0xFF4ADE80,
    )

    /** Gradient thương hiệu (nút BẬT) và khi đang ghi (nút TẮT) – chữ trắng phải đọc được trên mọi điểm dừng. */
    val BRAND_GRADIENT = listOf(0xFF4338CA, 0xFF5B47F5, 0xFF7C3AED)
    val LIVE_GRADIENT = listOf(0xFFBE123C, 0xFFE11D48, 0xFFDB2777)
    val PAUSE_GRADIENT = listOf(0xFF92400E, 0xFFB45309, 0xFFC2410C)
    /** Chỉ dùng trong logo (không đặt chữ lên). */
    val LOGO_GRADIENT = listOf(0xFF4338CA, 0xFF6D4AFF, 0xFF22D3EE)
}

/** Độ tương phản theo WCAG 2.x. */
object Contrast {
    private fun channel(c: Long, shift: Int): Double {
        val v = ((c shr shift) and 0xFF) / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(argb: Long): Double = 0.2126 * channel(argb, 16) + 0.7152 * channel(argb, 8) + 0.0722 * channel(argb, 0)

    fun ratio(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
}

/** Kiểu thẻ thông báo – thay emoji đầu dòng bằng icon + màu thống nhất. */
enum class Tone { INFO, PENDING, WARNING, ERROR, SUCCESS, LIVE }

object ToneRules {
    private val PREFIX = mapOf(
        "⚠️" to Tone.WARNING, "⚠" to Tone.WARNING, "❗" to Tone.ERROR, "❌" to Tone.ERROR,
        "⏳" to Tone.PENDING, "✅" to Tone.SUCCESS, "🔴" to Tone.LIVE, "🧹" to Tone.INFO, "ℹ️" to Tone.INFO,
    )

    fun of(text: String): Tone {
        val t = text.trimStart()
        return PREFIX.entries.firstOrNull { t.startsWith(it.key) }?.value ?: Tone.INFO
    }

    /** Bỏ ký hiệu đầu dòng (icon đã thể hiện) và khoảng trắng thừa. */
    fun clean(text: String): String {
        var t = text.trimStart()
        PREFIX.keys.sortedByDescending { it.length }.firstOrNull { t.startsWith(it) }?.let { t = t.removePrefix(it) }
        return t.removePrefix("️").trimStart()
    }
}

object ChatState {
    /**
     * Câu hỏi cuối cùng chưa có câu trả lời (ví dụ app bị đóng/ép dừng đúng lúc AI đang trả lời).
     * msgs xếp theo thứ tự cũ → mới.
     */
    fun unanswered(msgs: List<ChatMsg>): ChatMsg? = msgs.lastOrNull()?.takeIf { it.role == "user" }
}
