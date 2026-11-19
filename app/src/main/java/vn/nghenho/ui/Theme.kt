package vn.nghenho.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.nghenho.R
import vn.nghenho.util.BrandPalette

/** Màu ngữ nghĩa ngoài bộ Material (cảnh báo, thành công) + gradient thương hiệu. */
@Immutable
data class FlowmeExtras(
    val warning: Color, val warningContainer: Color, val onWarningContainer: Color,
    val success: Color, val successContainer: Color, val onSuccessContainer: Color,
    val brand: Brush, val live: Brush, val paused: Brush,
)

private fun c(v: Long) = Color(v)
private fun grad(stops: List<Long>) = Brush.linearGradient(stops.map(::c))

private fun extrasOf(p: BrandPalette.Scheme) = FlowmeExtras(
    warning = c(p.warning), warningContainer = c(p.warningContainer), onWarningContainer = c(p.onWarningContainer),
    success = c(p.success), successContainer = c(p.successContainer), onSuccessContainer = c(p.onSuccessContainer),
    brand = grad(BrandPalette.BRAND_GRADIENT), live = grad(BrandPalette.LIVE_GRADIENT), paused = grad(BrandPalette.PAUSE_GRADIENT),
)

val LocalFlowme = staticCompositionLocalOf { extrasOf(BrandPalette.Light) }

private fun schemeOf(p: BrandPalette.Scheme, dark: Boolean): ColorScheme =
    (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c(p.primary), onPrimary = c(p.onPrimary), primaryContainer = c(p.primaryContainer), onPrimaryContainer = c(p.onPrimaryContainer),
        inversePrimary = c(if (dark) BrandPalette.Light.primary else BrandPalette.Dark.primary),
        secondary = c(p.secondary), onSecondary = c(p.onSecondary), secondaryContainer = c(p.secondaryContainer), onSecondaryContainer = c(p.onSecondaryContainer),
        tertiary = c(p.tertiary), onTertiary = c(p.onTertiary), tertiaryContainer = c(p.tertiaryContainer), onTertiaryContainer = c(p.onTertiaryContainer),
        error = c(p.error), onError = c(p.onError), errorContainer = c(p.errorContainer), onErrorContainer = c(p.onErrorContainer),
        background = c(p.background), onBackground = c(p.onBackground),
        surface = c(p.surface), onSurface = c(p.onSurface), surfaceVariant = c(p.surfaceVariant), onSurfaceVariant = c(p.onSurfaceVariant),
        surfaceTint = c(p.primary),
        surfaceContainerLowest = c(p.surfaceLowest), surfaceContainerLow = c(p.surfaceLow), surfaceContainer = c(p.surfaceMid),
        surfaceContainerHigh = c(p.surfaceHigh), surfaceContainerHighest = c(p.surfaceHighest),
        surfaceBright = c(if (dark) p.surfaceHigh else p.surfaceLowest), surfaceDim = c(if (dark) p.surfaceLowest else p.surfaceHigh),
        outline = c(p.outline), outlineVariant = c(p.outlineVariant),
    )

private val base = Typography()

/** Kiểu chữ: tiêu đề đậm, khoảng chữ âm nhẹ (kiểu app quốc tế), nội dung dễ đọc. */
val FlowmeType = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(letterSpacing = 0.1.sp),
    bodyMedium = base.bodyMedium.copy(letterSpacing = 0.1.sp, lineHeight = 21.sp),
    bodySmall = base.bodySmall.copy(letterSpacing = 0.15.sp, lineHeight = 18.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
)

val FlowmeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun FlowmeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) BrandPalette.Dark else BrandPalette.Light
    val scheme = remember(dark) { schemeOf(p, dark) }
    val extras = remember(dark) { extrasOf(p) }
    CompositionLocalProvider(LocalFlowme provides extras) {
        MaterialTheme(colorScheme = scheme, typography = FlowmeType, shapes = FlowmeShapes) {
            Surface(color = MaterialTheme.colorScheme.background) { content() }
        }
    }
}

@Composable
fun BrandMark(size: Dp = 36.dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ic_brand_mark), contentDescription = "Flowme", modifier = modifier.size(size))
}

/** Đầu trang của màn hình chính: logo + tên Flowme + dòng phụ. */
@Composable
fun BrandHeader(subtitle: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark(40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Flowme", style = MaterialTheme.typography.headlineSmall)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}
