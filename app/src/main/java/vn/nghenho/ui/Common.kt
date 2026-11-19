package vn.nghenho.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import vn.nghenho.R
import vn.nghenho.util.Tone
import vn.nghenho.util.ToneRules

fun toast(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}

fun copyText(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Flowme", text))
}

// Màu người nói: tông vừa, đọc được (≥ 3:1 cho nhãn đậm) trên cả nền sáng lẫn nền tối
private val SPEAKER_COLORS = listOf(
    Color(0xFF0284C7), Color(0xFFDB2777), Color(0xFF059669), Color(0xFFEA580C), Color(0xFF9333EA), Color(0xFFD97706), Color(0xFF0891B2),
)

fun speakerColor(name: String): Color =
    if (name == "Bạn") Color(0xFF7C6CF7) else SPEAKER_COLORS[(name.hashCode() and 0x7fffffff) % SPEAKER_COLORS.size]

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
fun InfoText(text: String) {
    if (text.isBlank()) return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * Thẻ thông báo. Kiểu (thông tin / đang chờ / cảnh báo / lỗi / thành công / đang ghi) tự suy ra từ ký hiệu đầu dòng
 * (⚠️ ❗ ⏳ ✅ 🔴…) – ký hiệu được thay bằng icon + màu thống nhất cho gọn và chuyên nghiệp.
 */
@Composable
fun InfoCard(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    val tone = remember(text) { ToneRules.of(text) }
    val body = remember(text) { ToneRules.clean(text) }
    val cs = MaterialTheme.colorScheme
    val fx = LocalFlowme.current
    val (container, onContainer, accent) = when (tone) {
        Tone.INFO -> Triple(cs.surfaceContainerLowest, cs.onSurface, cs.primary)
        Tone.PENDING -> Triple(cs.primaryContainer, cs.onPrimaryContainer, cs.primary)
        Tone.WARNING -> Triple(fx.warningContainer, fx.onWarningContainer, fx.warning)
        Tone.ERROR -> Triple(cs.errorContainer, cs.onErrorContainer, cs.error)
        Tone.SUCCESS -> Triple(fx.successContainer, fx.onSuccessContainer, fx.success)
        Tone.LIVE -> Triple(cs.tertiaryContainer, cs.onTertiaryContainer, cs.tertiary)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        border = if (tone == Tone.INFO) BorderStroke(1.dp, cs.outlineVariant) else null,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        val hasAction = actionLabel != null && onAction != null
        Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = if (hasAction) 2.dp else 12.dp)) {
            when (tone) {
                Tone.LIVE -> Icon(painterResource(R.drawable.ic_mic), null, tint = accent, modifier = Modifier.size(20.dp))
                else -> Icon(
                    when (tone) {
                        Tone.PENDING -> Icons.Filled.Refresh
                        Tone.WARNING, Tone.ERROR -> Icons.Filled.Warning
                        Tone.SUCCESS -> Icons.Filled.CheckCircle
                        else -> Icons.Filled.Info
                    },
                    null, tint = accent, modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = onContainer)
                if (hasAction) {
                    TextButton(onClick = onAction!!, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                        Text(actionLabel!!, style = MaterialTheme.typography.labelLarge, color = accent)
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(52.dp, Modifier.alpha(0.9f))
            Spacer(Modifier.height(14.dp))
            Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SwitchRow(title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, selected: T, desc: String? = null, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(options.firstOrNull { it.first == selected }?.second ?: "—", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onSelect(v) })
            }
        }
    }
}

@Composable
fun TextSetting(
    label: String,
    value: String,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    var text by remember(label) { mutableStateOf(value) }
    LaunchedEffect(value) { if (value != text) text = value }
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret && !show) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = { if (secret) TextButton(onClick = { show = !show }) { Text(if (show) "Ẩn" else "Hiện") } },
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
