package vn.nghenho.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.nghenho.App
import vn.nghenho.ai.Memory
import vn.nghenho.ai.Providers
import vn.nghenho.data.ActionItem
import vn.nghenho.data.CalEvent
import vn.nghenho.data.ChatMsg
import vn.nghenho.util.ChatState
import androidx.compose.foundation.BorderStroke
import vn.nghenho.data.EventState
import vn.nghenho.integrations.CalendarHelper
import vn.nghenho.util.Fmt
import vn.nghenho.util.Times

// ───────────────────────── Việc & lịch hẹn ─────────────────────────

@Composable
fun TasksScreen(onOpenConv: (Long) -> Unit) {
    val dao = App.dao
    val actions by remember { dao.allActionsFlow() }.collectAsStateWithLifecycle(emptyList())
    val convs by remember { dao.convsFlow() }.collectAsStateWithLifecycle(emptyList())
    val since = remember { System.currentTimeMillis() - 3_600_000L }
    val upcoming by remember(since) { dao.upcomingFlow(since) }.collectAsStateWithLifecycle(emptyList())
    val titles = remember(convs) { convs.associate { it.id to "${it.emoji} ${it.title}".trim() } }
    var showDone by rememberSaveable { mutableStateOf(false) }
    val open = actions.filter { !it.done }
    val done = actions.filter { it.done }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenHeader("Việc & lịch hẹn", "${open.size} việc chưa xong · ${upcoming.size} lịch hẹn sắp tới") }
        item { SectionTitle("Lịch hẹn sắp tới") }
        if (upcoming.isEmpty()) {
            item { InfoText("Chưa có lịch hẹn nào. Khi bạn nói “hẹn gặp lúc 3 giờ chiều mai…”, Flowme sẽ gợi ý thêm vào Google Calendar.") }
        }
        items(upcoming, key = { "e${it.id}" }) { ev -> EventCard(ev, titles[ev.conversationId]) { onOpenConv(ev.conversationId) } }
        item { SectionTitle("Việc cần làm") }
        if (open.isEmpty()) item { InfoText("Không có việc nào đang chờ. 🎉") }
        items(open, key = { "a${it.id}" }) { a -> ActionRow(a, titles[a.conversationId]) { onOpenConv(a.conversationId) } }
        if (done.isNotEmpty()) {
            item {
                TextButton(onClick = { showDone = !showDone }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(if (showDone) "Ẩn ${done.size} việc đã xong" else "Hiện ${done.size} việc đã xong")
                }
            }
            if (showDone) items(done, key = { "d${it.id}" }) { a -> ActionRow(a, titles[a.conversationId]) { onOpenConv(a.conversationId) } }
        }
    }
}

fun dueText(due: String): String = Times.parseLocal(due)?.let { Fmt.dateTime(it) } ?: due

@Composable
fun ActionRow(a: ActionItem, convTitle: String? = null, onOpenConv: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Checkbox(checked = a.done, onCheckedChange = { v -> scope.launch(Dispatchers.IO) { App.dao.setActionDone(a.id, v) } })
        Column(Modifier.weight(1f).padding(top = 12.dp)) {
            Text(
                a.text, style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (a.done) TextDecoration.LineThrough else null,
                color = if (a.done) cs.onSurfaceVariant else Color.Unspecified,
            )
            val meta = listOfNotNull(
                a.owner.takeIf { it.isNotBlank() }?.let { "👤 $it" },
                a.due.takeIf { it.isNotBlank() }?.let { "⏰ ${dueText(it)}" },
            ).joinToString("   ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            if (convTitle != null) {
                Text(
                    "Từ: $convTitle", style = MaterialTheme.typography.labelSmall, color = cs.primary,
                    modifier = Modifier.clickable(enabled = onOpenConv != null) { onOpenConv?.invoke() },
                )
            }
        }
        IconButton(onClick = { scope.launch(Dispatchers.IO) { App.dao.deleteAction(a.id) } }) {
            Icon(Icons.Filled.Close, "Xoá", tint = cs.onSurfaceVariant)
        }
    }
}

@Composable
fun EventCard(ev: CalEvent, convTitle: String? = null, onOpenConv: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    fun add() = scope.launch {
        val id = withContext(Dispatchers.IO) {
            CalendarHelper.insert(ctx, ev)?.also { App.dao.setEventState(ev.id, EventState.ADDED, it) }
        }
        if (id != null) toast(ctx, "Đã thêm vào Lịch")
        else runCatching { ctx.startActivity(CalendarHelper.insertIntent(ev)) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (CalendarHelper.hasPermission(ctx)) add() else runCatching { ctx.startActivity(CalendarHelper.insertIntent(ev)) }
    }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("📅 ${ev.title}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "${Fmt.dateTime(ev.startAt)}–${Fmt.hm(ev.endAt)}" + if (ev.location.isNotBlank()) " · ${ev.location}" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (ev.notes.isNotBlank()) Text(ev.notes, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            if (convTitle != null) {
                Text(
                    "Từ: $convTitle", style = MaterialTheme.typography.labelSmall, color = cs.primary,
                    modifier = Modifier.padding(top = 4.dp).clickable(enabled = onOpenConv != null) { onOpenConv?.invoke() },
                )
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (ev.state) {
                    EventState.SUGGESTED -> {
                        Button(onClick = {
                            if (CalendarHelper.hasPermission(ctx)) add()
                            else launcher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                        }) { Text("Thêm vào lịch") }
                        TextButton(onClick = { scope.launch(Dispatchers.IO) { App.dao.setEventState(ev.id, EventState.DISMISSED, 0L) } }) { Text("Bỏ qua") }
                    }
                    EventState.ADDED -> {
                        Text("✓ Đã có trong Lịch", color = cs.primary, style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { runCatching { ctx.startActivity(CalendarHelper.viewIntent(ev.calendarEventId)) } }) { Text("Mở Lịch") }
                    }
                    else -> {
                        Text("Đã bỏ qua", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                        TextButton(onClick = { scope.launch(Dispatchers.IO) { App.dao.setEventState(ev.id, EventState.SUGGESTED, 0L) } }) { Text("Khôi phục") }
                    }
                }
            }
        }
    }
}

// ───────────────────────── Hỏi trí nhớ ─────────────────────────

/** Chạy hỏi đáp ở phạm vi của app (không bị huỷ khi chuyển tab) – câu trả lời luôn được lưu. */
object ChatRunner {
    val busy = MutableStateFlow(false)

    /** saveQuestion = false: hỏi lại câu đã lưu nhưng chưa có trả lời (app bị đóng giữa chừng) – không lưu trùng câu hỏi. */
    fun ask(q0: String, saveQuestion: Boolean = true) {
        val q = q0.trim()
        if (q.isEmpty() || busy.value) return
        busy.value = true
        App.instance.io.launch {
            try {
                if (saveQuestion) App.dao.insertChat(ChatMsg(role = "user", text = q))
                val ans = try {
                    Memory.answer(q)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "⚠️ ${e.message ?: "Lỗi"}"
                }
                App.dao.insertChat(ChatMsg(role = "assistant", text = ans))
            } finally {
                busy.value = false
            }
        }
    }
}

private val SUGGESTIONS = listOf(
    "Tóm tắt ngày hôm nay của tôi",
    "Hôm nay tôi đã hứa làm những gì?",
    "Tuần này có lịch hẹn nào?",
    "Những ý tưởng tôi nói gần đây",
    "Hôm qua tôi nói chuyện với ai, về việc gì?",
)

@Composable
fun ChatScreen() {
    val dao = App.dao
    val msgs by remember { dao.chatFlow() }.collectAsStateWithLifecycle(emptyList())
    // Câu hỏi đang gõ dở được lưu ngay vào máy: thoát / mở lại app vẫn còn
    var input by rememberSaveable { mutableStateOf(App.settings.draft("chat")) }
    fun setInput(v: String) { input = v; App.settings.setDraft("chat", v) }
    val busy by ChatRunner.busy.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val ver by App.settings.changes.collectAsStateWithLifecycle()
    val ready = remember(ver) { Providers.llmReady(App.settings) }
    LaunchedEffect(msgs.size, busy) {
        val n = (if (msgs.isEmpty()) 1 else msgs.size) + if (busy) 1 else 0
        if (msgs.isNotEmpty() || busy) listState.animateScrollToItem(n - 1)
    }

    fun ask(q0: String) {
        if (q0.isBlank() || busy) return
        setInput("")
        ChatRunner.ask(q0)
    }
    // App bị đóng/ép dừng đúng lúc AI đang trả lời => câu hỏi cuối chưa có trả lời: đề nghị hỏi lại
    val unanswered = remember(msgs, busy) { if (busy) null else ChatState.unanswered(msgs) }

    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader("Hỏi trí nhớ", "Hỏi về bất cứ điều gì bạn đã nghe hay nói") {
            if (msgs.isNotEmpty()) {
                IconButton(onClick = { scope.launch(Dispatchers.IO) { dao.clearChat() } }) { Icon(Icons.Filled.Delete, "Xoá lịch sử") }
            }
        }
        if (!ready) InfoCard("Cần API key cho tóm tắt AI (tab Cài đặt) để hỏi đáp.")
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (msgs.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        BrandMark(56.dp)
                        Text(
                            "Hỏi Flowme về ngày của bạn", style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                        )
                        Text(
                            "“Hôm nay tôi đã hứa làm gì?” · “Tuần này anh Minh nói gì về ngân sách?” · “Tóm tắt bài giảng sáng nay”",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
            items(msgs, key = { it.id }) { m -> Bubble(m) }
            if (unanswered != null && ready) {
                item(key = "retry") {
                    InfoCard("⏳ Câu hỏi này chưa có câu trả lời – có thể app đã bị đóng giữa chừng.", "Hỏi lại") {
                        ChatRunner.ask(unanswered.text, saveQuestion = false)
                    }
                }
            }
            if (busy) {
                item {
                    Text(
                        "Flowme đang lục lại trí nhớ…", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SUGGESTIONS) { s -> SuggestionChip(onClick = { ask(s) }, label = { Text(s) }, enabled = !busy && ready) }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                input, { setInput(it) }, placeholder = { Text("Hỏi Flowme…") }, modifier = Modifier.weight(1f), maxLines = 4,
                shape = RoundedCornerShape(24.dp),
            )
            IconButton(onClick = { ask(input) }, enabled = !busy && ready && input.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, "Gửi")
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMsg) {
    val mine = m.role == "user"
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = if (mine) 40.dp else 0.dp, end = if (mine) 0.dp else 40.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (mine) cs.primary else cs.surfaceContainerLowest,
            contentColor = if (mine) cs.onPrimary else cs.onSurface,
            border = if (mine) null else BorderStroke(1.dp, cs.outlineVariant),
            shape = if (mine) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp),
            modifier = Modifier.widthIn(max = 620.dp),
        ) {
            SelectionContainer {
                Text(m.text.replace("**", ""), modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
