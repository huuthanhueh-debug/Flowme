package vn.nghenho.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.nghenho.App
import vn.nghenho.R
import vn.nghenho.ai.Providers
import vn.nghenho.service.Notifs
import vn.nghenho.service.RecState
import vn.nghenho.service.RecorderService
import vn.nghenho.service.RecorderState
import vn.nghenho.util.Fmt
import vn.nghenho.work.Cleanup
import vn.nghenho.work.Net
import vn.nghenho.work.Pipeline
import vn.nghenho.work.Work
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@SuppressLint("BatteryLife")
@Composable
fun HomeScreen(onOpenConv: (Long) -> Unit, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    // Không đọc mức âm lượng ở đây (tránh vẽ lại cả màn hình 8 lần/giây) – chỉ nút tròn đọc mức âm
    val st by remember { RecorderState.state.map { it.copy(level = 0f) }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(RecorderState.state.value.copy(level = 0f))
    val dao = App.dao
    val pending by remember { dao.pendingCountFlow() }.collectAsStateWithLifecycle(0)
    val failed by remember { dao.failedCountFlow() }.collectAsStateWithLifecycle(0)
    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(1000) }
    }
    val dayStart = Fmt.startOfDay(now) // tự sang ngày mới lúc nửa đêm
    val speechToday by remember(dayStart) { dao.speechSinceFlow(dayStart) }.collectAsStateWithLifecycle(0L)
    val convToday by remember(dayStart) { dao.convCountSinceFlow(dayStart) }.collectAsStateWithLifecycle(0)
    val latest by remember { dao.latestSegmentsFlow(14) }.collectAsStateWithLifecycle(emptyList())
    val ver by App.settings.changes.collectAsStateWithLifecycle()
    val sttReady = remember(ver) { Providers.sttReady(App.settings) }
    val llmReady = remember(ver) { Providers.llmReady(App.settings) }
    val pm = remember { ctx.getSystemService(PowerManager::class.java) }
    var batteryOk by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { batteryOk = pm.isIgnoringBatteryOptimizations(ctx.packageName) }
    val lastError = remember(ver) { App.settings.lastError.substringAfter('|', "") }
    val net by Net.state.collectAsStateWithLifecycle()
    val pendingErr by remember { dao.latestPendingErrorFlow() }.collectAsStateWithLifecycle(null)
    val totalConvs by remember { dao.convCountFlow() }.collectAsStateWithLifecycle(0)
    val send = rememberSendNow()
    var cleanupTick by remember { mutableIntStateOf(0) }
    val cleanup by produceState<Cleanup.Plan?>(null, ver, totalConvs, dayStart, cleanupTick) {
        val p = withContext(Dispatchers.IO) { if (Cleanup.shouldAsk()) Cleanup.dailyPlan() else null }
        value = p
    }
    val scope = rememberCoroutineScope()

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            RecorderService.start(ctx)
        } else {
            toast(ctx, "Flowme cần quyền micro để nghe")
        }
    }

    fun toggle() {
        if (st.running) {
            RecorderService.send(ctx, RecorderService.ACTION_STOP)
            return
        }
        val need = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) RecorderService.start(ctx) else permLauncher.launch(need.toTypedArray())
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { BrandHeader("Trợ lý ghi nhớ cả ngày · ${Fmt.dayHeader(now)}") }
        item {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                RecordButton(st) { toggle() }
                StatusPill(st, now)
                if (st.running) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 14.dp)) {
                        FilledTonalButton(onClick = {
                            RecorderService.send(ctx, if (st.paused) RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE)
                        }) {
                            Icon(painterResource(if (st.paused) R.drawable.ic_play else R.drawable.ic_pause), null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (st.paused) "Tiếp tục" else "Tạm dừng")
                        }
                        FilledTonalButton(
                            enabled = st.activeConvId > 0 || st.inSpeech,
                            onClick = {
                                RecorderService.send(ctx, RecorderService.ACTION_SPLIT)
                                toast(ctx, "Đã chốt đoạn – đang tóm tắt")
                            },
                        ) {
                            Icon(painterResource(R.drawable.ic_split), null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Chốt đoạn")
                        }
                    }
                }
            }
        }
        if (!sttReady) item {
            InfoCard("Chưa có API key chép lời. Flowme vẫn ghi và lưu âm thanh, sẽ tự xử lý khi bạn thêm key.", "Cài đặt", onOpenSettings)
        } else if (!llmReady) item {
            InfoCard("Chưa có API key cho tóm tắt AI.", "Cài đặt", onOpenSettings)
        }
        if (lastError.isNotBlank()) item {
            InfoCard("⚠️ $lastError\nKiểm tra API key trong Cài đặt – âm thanh vẫn được giữ để xử lý lại.", "Cài đặt", onOpenSettings)
        }
        if (!batteryOk) item {
            InfoCard("Để nghe cả ngày trên Samsung, hãy cho Flowme chạy nền không giới hạn pin.", "Cho phép") {
                runCatching {
                    ctx.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                }
            }
        }
        if (pending > 0) item {
            val reason = remember(net, ver, pending) { Net.blockReason() }
            val err = pendingErr
            val text = when {
                reason != null -> "⏳ $pending đoạn đang chờ – $reason. Sẽ tự gửi khi được."
                !err.isNullOrBlank() -> "⏳ $pending đoạn chưa chép lời được.\nLỗi gần nhất: $err" + errorHint(err)
                else -> "⏳ Đang chép lời $pending đoạn…"
            }
            InfoCard(text, if (reason == null) send.label else null) { send.run() }
        }
        cleanup?.let { p ->
            if (p.ids.isNotEmpty()) item {
                CleanupCard(
                    p,
                    onDelete = {
                        scope.launch {
                            val n = withContext(Dispatchers.IO) { Cleanup.delete(p.ids).also { Cleanup.snooze() } }
                            toast(ctx, "Đã xoá $n cuộc hội thoại cũ")
                            Notifs.cancel(ctx, Notifs.CLEANUP_ID)
                            cleanupTick++
                        }
                    },
                    onKeep = { scope.launch { withContext(Dispatchers.IO) { Cleanup.keep() }; Notifs.cancel(ctx, Notifs.CLEANUP_ID); cleanupTick++ } },
                    onLater = { Cleanup.snooze(); Notifs.cancel(ctx, Notifs.CLEANUP_ID); cleanupTick++ },
                )
            }
        }
        if (failed > 0) item {
            InfoCard("❗ $failed đoạn chép lời bị lỗi.", "Thử lại") {
                scope.launch {
                    withContext(Dispatchers.IO) { dao.retryFailedChunks(); dao.retryFailedConvs() }
                    Pipeline.processSoon(ctx)
                }
            }
        }
        st.error?.let { e -> item { InfoCard("⚠️ $e") } }

        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatBox(painterResource(R.drawable.ic_mic), "Lời nói hôm nay", Fmt.duration(speechToday), Modifier.weight(1f))
                StatBox(painterResource(R.drawable.ic_chat), "Cuộc hội thoại", convToday.toString(), Modifier.weight(1f))
                StatBox(rememberVectorPainter(Icons.Filled.Refresh), "Chờ xử lý", pending.toString(), Modifier.weight(1f))
            }
        }
        item { SectionTitle("Bản ghi gần nhất") }
        if (latest.isEmpty()) {
            item {
                InfoText("Chưa có lời nói nào được chép. Bật Flowme rồi trò chuyện bình thường – bản ghi xuất hiện sau khoảng 1 phút, bản tóm tắt sau khi cuộc trò chuyện kết thúc.")
            }
        } else {
            items(latest.reversed(), key = { it.id }) { s ->
                Row(Modifier.fillMaxWidth().clickable { onOpenConv(s.conversationId) }.padding(horizontal = 20.dp, vertical = 7.dp)) {
                    Text(
                        Fmt.hm(s.startAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(50.dp).padding(top = 2.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        if (s.speaker.isNotBlank()) {
                            Text(s.speaker, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = speakerColor(s.speaker))
                        }
                        Text(s.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/**
 * Nút "Gửi ngay": xử lý ngay các đoạn đang chờ (bỏ qua thời gian chờ thử lại) rồi báo kết quả.
 * Chạy trên scope của App nên vẫn tiếp tục khi người dùng chuyển màn hình.
 */
class SendNow(private val ctx: Context, private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set
    val label: String get() = if (busy) "Đang gửi…" else "Gửi ngay"

    fun run() {
        if (busy) return
        busy = true
        scope.launch {
            val r = App.instance.io.async { runCatching { Pipeline.processNow(ctx, userInitiated = true) }.getOrNull() }.await()
            busy = false
            toast(ctx, Pipeline.describe(r))
        }
    }
}

@Composable
fun rememberSendNow(): SendNow {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    return remember(scope) { SendNow(ctx, scope) }
}

/** Gợi ý cách xử lý theo nội dung lỗi của nhà cung cấp. */
fun errorHint(err: String): String = when {
    err.contains("429") || err.contains("quota", true) || err.contains("RESOURCE_EXHAUSTED") || err.contains("rate limit", true) ->
        "\nGợi ý: hết hạn mức / quá nhiều lượt – vào Cài đặt đổi model chép lời (vd. gemini-flash-lite-latest) hoặc chọn Groq."
    err.contains("503") || err.contains("overloaded", true) || err.contains("UNAVAILABLE") -> "\nMáy chủ AI đang quá tải – app tự thử lại mỗi phút."
    err.contains("lỗi mạng") -> "\nKết nối tới máy chủ AI bị gián đoạn – app tự thử lại."
    else -> ""
}

@Composable
private fun CleanupCard(p: Cleanup.Plan, onDelete: () -> Unit, onKeep: () -> Unit, onLater: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.DateRange, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Đã sang ngày mới – dọn trí nhớ?", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                buildString {
                    append("${p.ids.size} cuộc hội thoại của những ngày trước đã lưu lịch hẹn xong. Xoá cho gọn?")
                    if (p.openTasks > 0) append("\n${p.openTasks} việc chưa xong vẫn được giữ trong tab Việc.")
                    if (p.waitingCalendar > 0) append("\n${p.waitingCalendar} cuộc còn lịch hẹn chưa thêm vào Lịch – được giữ lại.")
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDelete) { Text("Xoá ${p.ids.size} cuộc") }
                OutlinedButton(onClick = onKeep) { Text("Giữ lại") }
                TextButton(onClick = onLater) { Text("Để sau") }
            }
        }
    }
}

private fun statusText(st: RecState, now: Long): String = when {
    !st.running -> "Đang tắt – chạm BẬT để nghe cả ngày"
    st.paused -> "Tạm dừng"
    st.silenced -> "Micro đang bị cuộc gọi/ứng dụng khác dùng"
    st.inSpeech -> "Đang ghi lời nói · ${Fmt.clock(now - st.sessionStart)}"
    else -> "Đang nghe · ${Fmt.clock(now - st.sessionStart)}"
}

/** Viên trạng thái dưới nút ghi: chấm màu + chữ (xám = tắt, đỏ hồng = đang nghe, hổ phách = tạm dừng/micro bận). */
@Composable
private fun StatusPill(st: RecState, now: Long) {
    val cs = MaterialTheme.colorScheme
    val fx = LocalFlowme.current
    val dot = when {
        !st.running -> cs.outline
        st.paused || st.silenced -> fx.warning
        else -> cs.tertiary
    }
    Surface(shape = CircleShape, color = cs.surfaceContainerLowest, border = BorderStroke(1.dp, cs.outlineVariant)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(statusText(st, now), style = MaterialTheme.typography.labelLarge, color = cs.onSurface, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun StatBox(icon: Painter, label: String, value: String, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLowest),
        border = BorderStroke(1.dp, cs.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Icon(icon, null, tint = cs.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 2)
        }
    }
}

@Composable
fun RecordButton(st: RecState, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val fx = LocalFlowme.current
    val brush = when {
        !st.running -> fx.brand
        st.paused -> fx.paused
        else -> fx.live
    }
    val halo = when {
        !st.running -> cs.primary
        st.paused -> fx.warning
        else -> cs.tertiary
    }
    // Làm tròn mức âm (10 nấc) + không dùng hoạt ảnh: chỉ vẽ lại khi mức thật sự đổi (đỡ tốn pin khi màn hình bật)
    val level by remember { RecorderState.state.map { kotlin.math.round(it.level * 10f) / 10f }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(0f)
    val live = st.running && !st.paused
    val lvl = if (live) level else 0f
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(232.dp)) {
        // Vòng sáng theo mức âm – chỉ vẽ lại khi mức thật sự đổi (không có hiệu ứng chạy liên tục => tiết kiệm pin)
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            if (live) drawCircle(halo.copy(alpha = 0.08f), radius = r)
            drawCircle(halo.copy(alpha = if (live) 0.16f else 0.08f), radius = r * (0.72f + 0.26f * lvl))
        }
        Box(
            Modifier
                .size(156.dp)
                .shadow(16.dp, CircleShape, ambientColor = halo, spotColor = halo)
                .clip(CircleShape)
                .background(brush)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = if (st.running) "Tắt Flowme" else "Bật Flowme" },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painterResource(if (st.running) R.drawable.ic_stop else R.drawable.ic_mic), null,
                    tint = Color.White, modifier = Modifier.size(50.dp),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (st.running) "TẮT" else "BẬT", color = Color.White,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 17.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}
