@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package vn.nghenho.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.nghenho.App
import vn.nghenho.ai.Llm
import vn.nghenho.ai.Providers
import vn.nghenho.audio.AudioFiles
import vn.nghenho.integrations.CalendarHelper
import vn.nghenho.integrations.Export
import vn.nghenho.integrations.Notion
import vn.nghenho.service.RecorderState
import vn.nghenho.work.Pipeline
import vn.nghenho.work.ProcLog
import vn.nghenho.work.Work
import vn.nghenho.BuildConfig
import androidx.compose.ui.text.font.FontFamily
import android.provider.Settings as SysSettings

@SuppressLint("BatteryLife")
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val s = App.settings
    val ver by s.changes.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var notionResult by remember { mutableStateOf<String?>(null) }
    var confirmWipe by remember { mutableStateOf(false) }
    var sttResult by remember { mutableStateOf<String?>(null) }
    var sttTesting by remember { mutableStateOf(false) }
    var showOld by remember { mutableStateOf(false) }
    val logVer by ProcLog.changes.collectAsStateWithLifecycle()
    val log by produceState(emptyList<String>(), logVer) {
        val l = withContext(Dispatchers.IO) { ProcLog.recent(15) }
        value = l
    }
    var calVersion by remember { mutableIntStateOf(0) }
    val calPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { calVersion++ }
    val calendars by produceState(emptyList<CalendarHelper.Cal>(), calVersion) {
        val list = withContext(Dispatchers.IO) { CalendarHelper.list(ctx) }
        value = list
    }
    val hasCal = remember(calVersion) { CalendarHelper.hasPermission(ctx) }
    val pm = remember { ctx.getSystemService(PowerManager::class.java) }
    var batteryOk by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        batteryOk = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        calVersion++
    }
    // Rời màn hình Cài đặt: chỉ làm mới hàng chờ nếu cấu hình xử lý thật sự đổi
    // (tránh huỷ một lần chép lời/tóm tắt đang chạy dở => tốn lại tiền API)
    val startSig = remember { processingSignature() }
    DisposableEffect(Unit) {
        onDispose {
            val changed = processingSignature() != startSig
            Work.kick(ctx, replace = changed)
            if (changed) {
                Work.schedulePeriodic(ctx, update = true)
                Pipeline.notBefore = 0L // key/model mới: không chờ hết thời gian chờ của lỗi cũ
                Pipeline.processSoon(ctx)
            }
        }
    }
    val stt = remember(ver) { Providers.stt(s.sttProvider) }
    val llm = remember(ver) { Providers.llm(s.llmProvider) }
    val calPerms = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        ScreenHeader("Cài đặt", "Dữ liệu chỉ nằm trên điện thoại của bạn")

        SectionTitle("Cài đặt nhanh")
        InfoText("Chọn một cấu hình, rồi dán API key ở mục bên dưới.")
        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {
                s.sttProvider = "gemini"; s.sttModel = ""; s.llmProvider = "gemini"; s.llmModel = ""
                toast(ctx, "Đã chọn Gemini – chỉ cần 1 key")
            }, label = { Text("⭐ Gemini – 1 key cho tất cả") })
            AssistChip(onClick = {
                s.sttProvider = "groq"; s.sttModel = ""; s.llmProvider = "anthropic"; s.llmModel = ""
                toast(ctx, "Groq chép lời (miễn phí) + Claude tóm tắt: cần 2 key")
            }, label = { Text("🎯 Claude + Groq chép lời miễn phí") })
            AssistChip(onClick = {
                s.sttProvider = "gemini"; s.sttModel = ""; s.llmProvider = "anthropic"; s.llmModel = ""
                toast(ctx, "Gemini chép lời (tách người nói) + Claude tóm tắt: cần 2 key")
            }, label = { Text("🗣️ Claude + Gemini (tách người nói)") })
            AssistChip(onClick = {
                s.sttProvider = "groq"; s.sttModel = ""; s.llmProvider = "deepseek"; s.llmModel = ""
                toast(ctx, "Groq chép lời (miễn phí) + DeepSeek tóm tắt: cần 2 key")
            }, label = { Text("🐳 DeepSeek + Groq chép lời") })
            AssistChip(onClick = {
                s.sttProvider = "groq"; s.sttModel = ""; s.llmProvider = "groq"; s.llmModel = ""
                toast(ctx, "Đã chọn Groq – miễn phí, không tách người nói")
            }, label = { Text("🆓 Groq – miễn phí hoàn toàn") })
        }

        SectionTitle("1 · Chép lời (giọng nói → chữ)")
        ChoiceRow("Nhà cung cấp", Providers.STT.map { it.id to it.label }, stt.id, desc = stt.note) { s.sttProvider = it; s.sttModel = "" }
        if (stt.models.isNotEmpty()) {
            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val current = Providers.sttModel(s)
                stt.models.forEach { m ->
                    FilterChip(selected = current == m, onClick = { s.sttModel = if (m == stt.defaultModel) "" else m }, label = { Text(m) })
                }
            }
        }
        TextSetting("Model (để trống = ${stt.defaultModel})", s.sttModel) { s.sttModel = it.trim() }
        KeyFields(stt.keyId)
        OutlinedButton(
            enabled = !sttTesting,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            onClick = {
                sttTesting = true
                sttResult = null
                scope.launch {
                    sttResult = Pipeline.testStt(ctx)
                    sttTesting = false
                }
            },
        ) { Text(if (sttTesting) "Đang gửi đoạn ghi gần nhất…" else "Kiểm tra chép lời (dùng đoạn ghi gần nhất)") }
        sttResult?.let { InfoText(it + (if (it.startsWith("❌")) errorHint(it) else "")) }
        ChoiceRow(
            "Ngôn ngữ nói",
            listOf("vi_en" to "Tiếng Việt + English (khuyên dùng)", "vi" to "Chỉ tiếng Việt", "en" to "English only", "auto" to "Tự nhận mọi ngôn ngữ"),
            s.language,
            desc = "Việt + English: giữ nguyên câu tiếng Anh khi bạn nói xen kẽ, không dịch.",
        ) { s.language = it }

        SectionTitle("2 · Tóm tắt & hỏi đáp (AI)")
        ChoiceRow("Nhà cung cấp", Providers.LLM.map { it.id to it.label }, llm.id, desc = llm.note.ifBlank { null }) { s.llmProvider = it; s.llmModel = "" }
        if (llm.models.isNotEmpty()) {
            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val current = Providers.llmModel(s)
                llm.models.forEach { m ->
                    FilterChip(selected = current == m, onClick = { s.llmModel = if (m == llm.defaultModel) "" else m }, label = { Text(m) })
                }
            }
        }
        TextSetting("Model (để trống = ${llm.defaultModel})", s.llmModel) { s.llmModel = it.trim() }
        ChoiceRow(
            "Ngôn ngữ bản tóm tắt",
            listOf("vi" to "Tiếng Việt", "en" to "English", "same" to "Theo ngôn ngữ cuộc trò chuyện"), s.summaryLang,
        ) { s.summaryLang = it }
        if (llm.keyId != stt.keyId) KeyFields(llm.keyId)
        Button(
            enabled = !testing,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            onClick = {
                testing = true
                testResult = null
                scope.launch {
                    testResult = runCatching { Llm.complete("Bạn là trợ lý. Trả lời thật ngắn.", "Trả lời đúng một từ: OK", null, 2_000) }
                        .fold({ "✅ Kết nối được: ${it.take(40)}" }, { "❌ ${it.message}" })
                    testing = false
                }
            },
        ) { Text(if (testing) "Đang kiểm tra…" else "Kiểm tra kết nối AI") }
        testResult?.let { InfoText(it) }

        SectionTitle("3 · Ghi âm")
        ChoiceRow(
            "Độ nhạy thu tiếng",
            listOf(0 to "Thấp – chỉ giọng to, ở gần", 1 to "Vừa (khuyên dùng)", 2 to "Cao – bắt cả giọng nhỏ, ở xa"), s.sensitivity,
        ) { s.sensitivity = it }
        ChoiceRow(
            "Tốc độ hiện bản ghi",
            listOf(0 to "Nhanh (~20 giây, nhiều lượt gọi API)", 1 to "Cân bằng (~1 phút)", 2 to "Tiết kiệm (~3 phút, ít lượt gọi)"), s.latencyMode,
        ) { s.latencyMode = it }
        ChoiceRow("Kết thúc cuộc hội thoại sau khi im lặng", listOf(1, 2, 3, 5, 10, 15).map { it to "$it phút" }, s.convGapMin) { s.convGapMin = it }
        ChoiceRow(
            "Nguồn micro",
            listOf(
                MediaRecorder.AudioSource.MIC to "Mặc định",
                MediaRecorder.AudioSource.VOICE_RECOGNITION to "Tối ưu nhận dạng giọng nói",
                MediaRecorder.AudioSource.VOICE_COMMUNICATION to "Lọc ồn mạnh (kiểu gọi thoại)",
                MediaRecorder.AudioSource.CAMCORDER to "Micro quay phim (thu xa)",
            ),
            s.audioSource,
        ) { s.audioSource = it }
        SwitchRow("Lọc tạp âm", "Bộ lọc ồn của điện thoại – hữu ích ở quán cà phê, ngoài đường", s.noiseSuppress) { s.noiseSuppress = it }
        SwitchRow("Tự cân bằng âm lượng", "Giúp nghe rõ giọng ở xa", s.autoGain) { s.autoGain = it }
        InfoText("Thay đổi mục Ghi âm có hiệu lực từ lần bật tiếp theo.")

        SectionTitle("4 · Lưu trữ & đồng bộ")
        SwitchRow(
            "Chỉ gửi đi xử lý khi có Wi-Fi",
            "Mất mạng hoặc dùng 4G/5G: âm thanh được lưu trong máy và tự xử lý khi có Wi-Fi. Để TẮT nếu muốn dùng cả 4G/5G.", s.wifiOnly,
        ) { s.wifiOnly = it }
        ChoiceRow(
            "Giữ file âm thanh",
            listOf(0 to "Xoá ngay sau khi chép lời", 1 to "1 ngày", 7 to "7 ngày", 30 to "30 ngày", -1 to "Giữ mãi"), s.keepAudioDays,
        ) { s.keepAudioDays = it }
        SwitchRow(
            "Hỏi dọn hội thoại cũ mỗi sáng",
            "Sang ngày mới, nếu các cuộc hôm trước đã lưu lịch hẹn xong, Flowme hỏi bạn có xoá cho gọn không (việc chưa xong vẫn giữ)",
            s.askCleanupDaily,
        ) { s.askCleanupDaily = it }
        OutlinedButton(onClick = { showOld = true }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text("🧹 Xoá hội thoại cũ…")
        }

        SectionTitle("5 · Tích hợp")
        TextSetting("Tên của bạn (giúp AI nhận ra bạn)", s.userName) { s.userName = it }
        if (!hasCal) {
            OutlinedButton(onClick = { calPerm.launch(calPerms) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Cho phép truy cập Lịch (Google Calendar)")
            }
        } else {
            ChoiceRow(
                "Lịch để thêm hẹn",
                listOf(-1L to "Tự chọn (lịch Google chính)") + calendars.map { it.id to "${it.name} · ${it.account}" },
                s.calendarId,
            ) { s.calendarId = it }
        }
        SwitchRow("Tự thêm lịch hẹn vào Lịch", "Tắt: Flowme chỉ gợi ý, bạn bấm “Thêm vào lịch” trên thông báo", s.autoCalendar) { v ->
            s.autoCalendar = v
            if (v && !hasCal) calPerm.launch(calPerms)
        }
        TextSetting("Notion – Internal Integration Secret", s.key("notion"), secret = true) { s.setKey("notion", it) }
        TextSetting("Notion – link hoặc ID database", s.notionDbId, keyboard = KeyboardType.Uri) { s.notionDbId = it.trim() }
        SwitchRow("Tự gửi mỗi cuộc hội thoại sang Notion", null, s.notionAuto) { s.notionAuto = it }
        OutlinedButton(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            onClick = {
                scope.launch {
                    notionResult = withContext(Dispatchers.IO) { runCatching { Notion.test() }.fold({ "✅ $it" }, { "❌ ${it.message}" }) }
                }
            },
        ) { Text("Kiểm tra Notion") }
        notionResult?.let { InfoText(it) }
        InfoText("Tạo integration tại notion.so/my-integrations, mở database → ••• → Connections → thêm integration đó.")
        TextSetting("Webhook URL (Zapier, Make, n8n…)", s.webhookUrl, keyboard = KeyboardType.Uri) { s.webhookUrl = it.trim() }
        SwitchRow("Tự gửi webhook sau mỗi tóm tắt", "Dùng để nối Gmail, Google Sheets, Slack… tự động", s.webhookAuto) { s.webhookAuto = it }
        TextSetting("Email nhận tóm tắt (Gmail)", s.emailTo, keyboard = KeyboardType.Email) { s.emailTo = it.trim() }
        SwitchRow("Thông báo khi có tóm tắt mới", null, s.notifySummaries) { s.notifySummaries = it }

        SectionTitle("6 · Pin & chạy nền (quan trọng trên Samsung)")
        InfoText(if (batteryOk) "✅ Đã cho phép chạy nền không giới hạn." else "⚠️ Android đang tối ưu pin cho Flowme – có thể bị dừng khi tắt màn hình.")
        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!batteryOk) {
                Button(onClick = {
                    runCatching { ctx.startActivity(Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) }
                }) { Text("Cho phép chạy nền") }
            }
            OutlinedButton(onClick = {
                runCatching { ctx.startActivity(Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }
            }) { Text("Mở cài đặt ứng dụng") }
        }
        InfoText("Trên Galaxy Z Fold: Cài đặt → Pin → Giới hạn sử dụng nền → Ứng dụng không bao giờ ngủ → thêm Flowme. Trong Thông tin ứng dụng → Pin → chọn “Không hạn chế”.")
        InfoText("Bật/tắt nhanh: vuốt xuống 2 lần → ✏️ → kéo ô “Flowme” vào Cài đặt nhanh; hoặc thêm widget Flowme ra màn hình chính / màn hình ngoài.")

        SectionTitle("7 · Dữ liệu")
        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { Export.exportAll(ctx) } }
                    toast(ctx, r.fold({ "Đã xuất: $it" }, { "Lỗi xuất: ${it.message}" }))
                }
            }) { Text("Xuất toàn bộ (Markdown + JSON)") }
            OutlinedButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { App.dao.retryFailedChunks(); App.dao.retryFailedConvs() }
                    Pipeline.notBefore = 0L
                    Pipeline.processSoon(ctx)
                    toast(ctx, "Đang thử lại các mục lỗi")
                }
            }) { Text("Thử lại mục lỗi") }
            OutlinedButton(
                onClick = { confirmWipe = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Xoá toàn bộ dữ liệu") }
        }

        SectionTitle("8 · Nhật ký xử lý")
        InfoText("Mỗi lần chép lời / tóm tắt (thành công hay lỗi) được ghi ở đây. Nếu bản ghi không hiện, xem dòng ❌ mới nhất.")
        if (log.isEmpty()) {
            InfoText("Chưa có hoạt động nào.")
        } else {
            Text(
                log.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    val all = withContext(Dispatchers.IO) { ProcLog.recent(200) }
                    copyText(ctx, "Flowme ${BuildConfig.VERSION_NAME}\n" + all.joinToString("\n"))
                    toast(ctx, "Đã sao chép nhật ký")
                }
            }) { Text("Sao chép nhật ký") }
        }

        SectionTitle("Về Flowme")
        InfoText(
            "Flowme biến điện thoại thành trợ lý ghi nhớ cả ngày: ghi âm khi có tiếng nói, chép lời, phân biệt người nói, tóm tắt, " +
                "rút việc cần làm, lịch hẹn và sơ đồ tư duy. Không có máy chủ trung gian: âm thanh chỉ gửi thẳng tới nhà cung cấp AI bạn chọn, " +
                "bằng key của bạn. Hãy cho người đối diện biết khi bạn ghi âm cuộc trò chuyện."
        )
        InfoText("Phiên bản ${BuildConfig.VERSION_NAME}")
    }

    if (showOld) OldConversationsDialog(onDismiss = { showOld = false })

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Xoá toàn bộ dữ liệu?") },
            text = { Text("Xoá mọi cuộc hội thoại, âm thanh, bản ghi, việc cần làm và lịch sử hỏi đáp trên máy. API key và cài đặt được giữ lại. Không hoàn tác được.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    if (RecorderState.state.value.running) {
                        toast(ctx, "Hãy TẮT Flowme trước khi xoá dữ liệu")
                    } else {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                App.instance.database.clearAllTables()
                                AudioFiles.dir(ctx).listFiles()?.forEach { it.delete() }
                            }
                            toast(ctx, "Đã xoá toàn bộ dữ liệu")
                        }
                    }
                }) { Text("Xoá hết") }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Huỷ") } },
        )
    }
}

private fun processingSignature(): Int {
    val s = App.settings
    return listOf(
        s.sttProvider, s.sttModel, s.llmProvider, s.llmModel, s.customBaseUrl, s.wifiOnly, s.language, s.summaryLang,
        s.key(Providers.stt(s.sttProvider).keyId), s.key(Providers.llm(s.llmProvider).keyId),
    ).hashCode()
}

@Composable
private fun KeyFields(keyId: String) {
    val s = App.settings
    if (keyId == "custom") {
        TextSetting("Địa chỉ máy chủ (vd http://192.168.1.10:8000/v1)", s.customBaseUrl, keyboard = KeyboardType.Uri) { s.customBaseUrl = it.trim() }
    }
    TextSetting(Providers.keyLabel(keyId), s.key(keyId), secret = true) { s.setKey(keyId, it) }
    InfoText(Providers.keyHelp(keyId))
}
