@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package vn.nghenho.ui

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.nghenho.App
import vn.nghenho.ai.Parse
import vn.nghenho.ai.Prompts
import vn.nghenho.ai.Providers
import vn.nghenho.data.ActionItem
import vn.nghenho.data.CalEvent
import vn.nghenho.data.Chunk
import vn.nghenho.data.ChunkStatus
import vn.nghenho.data.ConvStatus
import vn.nghenho.data.Conversation
import vn.nghenho.data.Segment
import vn.nghenho.integrations.Export
import vn.nghenho.integrations.Notion
import vn.nghenho.integrations.ShareHelper
import vn.nghenho.integrations.Webhook
import vn.nghenho.service.RecorderService
import vn.nghenho.util.Fmt
import vn.nghenho.util.Mindmap
import vn.nghenho.work.Cleanup
import vn.nghenho.work.Net
import vn.nghenho.work.Pipeline
import java.io.File
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton

@Composable
fun MemoriesPane(wide: Boolean, selected: Long, onSelect: (Long) -> Unit, onClose: () -> Unit) {
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(0.42f).fillMaxHeight()) { MemoriesList(selected, onSelect) }
            VerticalDivider()
            Box(Modifier.weight(0.58f).fillMaxHeight()) {
                if (selected > 0) ConversationDetail(selected, showBack = false, onBack = onClose)
                else EmptyHint("Chọn một cuộc hội thoại để xem tóm tắt, việc cần làm, sơ đồ tư duy và bản ghi.", Modifier.fillMaxSize())
            }
        }
    } else if (selected > 0) {
        ConversationDetail(selected, showBack = true, onBack = onClose)
    } else {
        MemoriesList(selected, onSelect)
    }
}

@Composable
fun MemoriesList(selected: Long, onSelect: (Long) -> Unit) {
    val convs by remember { App.dao.convsFlow() }.collectAsStateWithLifecycle(emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var cat by rememberSaveable { mutableStateOf("") }
    var showTrivial by rememberSaveable { mutableStateOf(false) }
    val q = query.trim()
    val textHits by produceState(emptySet<Long>(), q) {
        var hits = emptySet<Long>()
        if (q.length >= 2) {
            delay(250) // gõ xong mới tìm
            hits = withContext(Dispatchers.IO) { App.dao.convIdsWithText("%$q%").toSet() }
        }
        value = hits
    }
    val cats = remember(convs) { convs.map { it.category }.filter { it.isNotBlank() }.distinct() }
    val filtered = remember(convs, q, cat, showTrivial, textHits) {
        convs.filter { c ->
            (showTrivial || !c.trivial) && (cat.isEmpty() || c.category == cat) &&
                (q.isEmpty() || c.title.contains(q, true) || c.overview.contains(q, true) || c.keyPoints.contains(q, true) || c.id in textHits)
        }
    }
    val groups = remember(filtered) { filtered.groupBy { Fmt.dayHeader(it.startedAt) } }
    val ctx = LocalContext.current
    // Chọn nhiều để xoá: nhấn giữ một cuộc, hoặc bấm "Chọn" trên đầu trang
    var selectMode by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(emptySet<Long>()) }
    var confirmBulk by remember { mutableStateOf(false) }
    var showOld by remember { mutableStateOf(false) }
    val livePicked = remember(picked, convs) { val ids = convs.mapTo(HashSet()) { it.id }; picked.filterTo(LinkedHashSet()) { it in ids } }
    fun exitSelect() { selectMode = false; picked = emptySet() }
    fun toggle(c: Conversation) {
        if (c.status == ConvStatus.ACTIVE) { toast(ctx, "Cuộc này đang diễn ra – bấm “Chốt đoạn” trước rồi mới xoá được"); return }
        picked = if (c.id in picked) picked - c.id else picked + c.id
    }
    BackHandler(enabled = selectMode) { exitSelect() }
    Column(Modifier.fillMaxSize()) {
        if (selectMode) {
            val all = remember(filtered) { filtered.filter { it.status != ConvStatus.ACTIVE }.mapTo(LinkedHashSet()) { it.id } }
            val allPicked = all.isNotEmpty() && livePicked.containsAll(all)
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { exitSelect() }) { Icon(Icons.Filled.Close, "Thoát chọn") }
                Text("Đã chọn ${livePicked.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { picked = if (allPicked) emptySet() else all }) { Text(if (allPicked) "Bỏ chọn hết" else "Chọn hết") }
                IconButton(enabled = livePicked.isNotEmpty(), onClick = { confirmBulk = true }) {
                    Icon(Icons.Filled.Delete, "Xoá các cuộc đã chọn", tint = if (livePicked.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            ScreenHeader("Trí nhớ", "${convs.count { !it.trivial && it.status == ConvStatus.DONE }} cuộc hội thoại đã tóm tắt") {
                if (convs.isNotEmpty()) {
                    TextButton(onClick = { selectMode = true }) { Text("Chọn") }
                    TextButton(onClick = { showOld = true }) { Text("Dọn dẹp") }
                }
            }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text("Tìm trong tóm tắt và bản ghi…") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = cat.isEmpty(), onClick = { cat = "" }, label = { Text("Tất cả") }) }
            items(cats) { c -> FilterChip(selected = cat == c, onClick = { cat = if (cat == c) "" else c }, label = { Text(c) }) }
            item { FilterChip(selected = showTrivial, onClick = { showTrivial = !showTrivial }, label = { Text("Hiện đoạn ngắn") }) }
        }
        if (filtered.isEmpty()) {
            EmptyHint(
                if (convs.isEmpty()) "Chưa có cuộc hội thoại nào. Bật Flowme ở tab Nghe – mỗi cuộc trò chuyện sẽ được tóm tắt tự động."
                else "Không có kết quả phù hợp.",
                Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                groups.forEach { (day, list) ->
                    item(key = "h_$day") {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                day, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f).padding(top = 6.dp, bottom = 4.dp),
                            )
                            if (selectMode) {
                                val dayIds = list.filter { it.status != ConvStatus.ACTIVE }.map { it.id }
                                val dayAll = dayIds.isNotEmpty() && livePicked.containsAll(dayIds)
                                if (dayIds.isNotEmpty()) TextButton(onClick = { picked = if (dayAll) picked - dayIds.toSet() else picked + dayIds }) {
                                    Text(if (dayAll) "Bỏ chọn ngày" else "Chọn cả ngày")
                                }
                            }
                        }
                    }
                    items(list, key = { it.id }) { c ->
                        ConvRow(
                            c, c.id == selected,
                            checked = if (selectMode) c.id in livePicked else null,
                            onLongClick = { if (!selectMode) selectMode = true; toggle(c) },
                        ) { if (selectMode) toggle(c) else onSelect(c.id) }
                    }
                }
            }
        }
    }

    if (confirmBulk) {
        val ids = livePicked.toList()
        AlertDialog(
            onDismissRequest = { confirmBulk = false },
            title = { Text("Xoá ${ids.size} cuộc hội thoại?") },
            text = { Text(DELETE_NOTE) },
            confirmButton = {
                TextButton(onClick = {
                    confirmBulk = false
                    if (selected in ids) onSelect(0L)
                    exitSelect()
                    deleteConversations(ctx, ids)
                }) { Text("Xoá ${ids.size} cuộc", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmBulk = false }) { Text("Huỷ") } },
        )
    }
    if (showOld) OldConversationsDialog(onDismiss = { showOld = false }) { ids -> if (selected in ids) onSelect(0L) }
}

const val DELETE_NOTE =
    "Xoá âm thanh, bản ghi và tóm tắt. Việc chưa xong vẫn được giữ trong tab Việc; lịch đã thêm vào Lịch Google không bị ảnh hưởng. Không hoàn tác được."

/** Xoá trên scope của App (không bị huỷ khi rời màn hình), báo kết quả bằng toast. */
fun deleteConversations(ctx: Context, ids: List<Long>) {
    if (ids.isEmpty()) return
    val app = ctx.applicationContext
    App.instance.io.launch {
        val n = Cleanup.delete(ids)
        withContext(Dispatchers.Main) { toast(app, "Đã xoá $n cuộc hội thoại") }
    }
}

/** Hộp thoại "Xoá hội thoại cũ": chọn mốc thời gian, xem trước số cuộc sẽ bị xoá. */
@Composable
fun OldConversationsDialog(onDismiss: () -> Unit, onDeleted: (List<Long>) -> Unit = {}) {
    val ctx = LocalContext.current
    val options = remember { listOf(0 to "Mọi cuộc trước hôm nay", 7 to "Cũ hơn 7 ngày", 30 to "Cũ hơn 30 ngày") }
    var days by remember { mutableIntStateOf(7) }
    val found by produceState<Map<Int, List<Long>>?>(null) {
        val m = withContext(Dispatchers.IO) { options.associate { (d, _) -> d to Cleanup.olderThan(d) } }
        value = m
    }
    val ids = found?.get(days).orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Xoá hội thoại cũ") },
        text = {
            Column {
                options.forEach { (d, label) ->
                    Row(Modifier.fillMaxWidth().clickable { days = d }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = days == d, onClick = { days = d })
                        Text(label + (found?.get(d)?.let { " · ${it.size} cuộc" } ?: ""), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Text(
                    "Chỉ xoá các cuộc đã xử lý xong (không đụng cuộc đang diễn ra hoặc đang chờ chép lời). $DELETE_NOTE",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = ids.isNotEmpty(), onClick = { onDismiss(); onDeleted(ids); deleteConversations(ctx, ids) }) {
                Text(
                    when {
                        found == null -> "Đang đếm…"
                        ids.isEmpty() -> "Không có cuộc nào"
                        else -> "Xoá ${ids.size} cuộc"
                    },
                    color = if (ids.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } },
    )
}

fun statusEmoji(status: Int): String = when (status) {
    ConvStatus.ACTIVE -> "🔴"
    ConvStatus.CLOSED -> "⏳"
    ConvStatus.FAILED -> "⚠️"
    else -> "💬"
}

fun convTitle(c: Conversation): String = when (c.status) {
    ConvStatus.ACTIVE -> "Đang diễn ra…"
    ConvStatus.CLOSED -> c.title.ifBlank { "Đang chép lời & tóm tắt…" }
    ConvStatus.FAILED -> c.title.ifBlank { "Chưa tóm tắt được" }
    else -> c.title.ifBlank { "Cuộc trò chuyện" }
}

@Composable
fun ConvRow(
    c: Conversation,
    selected: Boolean,
    checked: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val active = selected || checked == true
    Card(
        colors = CardDefaults.cardColors(containerColor = if (active) cs.primaryContainer else cs.surfaceContainerLowest),
        border = BorderStroke(1.dp, if (active) cs.primary.copy(alpha = 0.35f) else cs.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Row(Modifier.fillMaxWidth().combinedClickable(onLongClick = onLongClick, onClick = onClick).padding(14.dp)) {
            if (checked != null) {
                Checkbox(checked = checked, enabled = c.status != ConvStatus.ACTIVE, onCheckedChange = { onClick() }, modifier = Modifier.padding(end = 4.dp))
            }
            Box(
                Modifier.size(46.dp).background(cs.primary.copy(alpha = 0.08f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) { Text(c.emoji.ifBlank { statusEmoji(c.status) }, fontSize = 22.sp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(convTitle(c), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append("${Fmt.hm(c.startedAt)}–${Fmt.hm(c.endedAt)} · ${Fmt.duration(c.endedAt - c.startedAt)}")
                        if (c.category.isNotBlank()) append(" · ${c.category}")
                    },
                    style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
                )
                if (c.overview.isNotBlank()) {
                    Text(c.overview, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
                if (c.status == ConvStatus.FAILED && !c.error.isNullOrBlank()) {
                    Text("Lỗi: ${c.error}", style = MaterialTheme.typography.bodySmall, color = cs.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun ConversationDetail(id: Long, showBack: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = App.dao
    val conv by remember(id) { dao.convFlow(id) }.collectAsStateWithLifecycle(null)
    val segs by remember(id) { dao.segmentsFlow(id) }.collectAsStateWithLifecycle(emptyList())
    val actions by remember(id) { dao.actionsFlow(id) }.collectAsStateWithLifecycle(emptyList())
    val events by remember(id) { dao.eventsFlow(id) }.collectAsStateWithLifecycle(emptyList())
    val chunks by remember(id) { dao.chunksFlow(id) }.collectAsStateWithLifecycle(emptyList())
    var tab by rememberSaveable(id) { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renameFrom by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val c = conv
    if (c == null) {
        EmptyHint("Đang tải…", Modifier.fillMaxSize())
        return
    }

    fun share(email: Boolean) = scope.launch {
        val md = withContext(Dispatchers.IO) { Export.markdown(id) }
        val subject = "Flowme: ${c.emoji} ${c.title}".trim()
        if (email) ShareHelper.email(ctx, App.settings.emailTo, subject, md) else ShareHelper.text(ctx, subject, md)
    }
    fun notion() = scope.launch {
        val r = withContext(Dispatchers.IO) { runCatching { Notion.pushConversation(id) } }
        toast(ctx, r.fold({ "Đã gửi sang Notion" }, { "Notion: ${it.message}" }))
    }
    fun webhook() = scope.launch {
        val r = withContext(Dispatchers.IO) { runCatching { Webhook.post(id) } }
        toast(ctx, r.fold({ "Đã gửi webhook" }, { "Webhook: ${it.message}" }))
    }
    fun resummarize() = scope.launch {
        withContext(Dispatchers.IO) { dao.reopenConv(id) }
        Pipeline.processSoon(ctx)
        toast(ctx, "Đang tóm tắt lại…")
    }
    fun retranscribe() = scope.launch {
        val n = withContext(Dispatchers.IO) {
            val n = dao.resetChunksOf(id)
            if (n > 0) dao.reopenConv(id)
            n
        }
        if (n > 0) {
            Pipeline.processSoon(ctx)
            toast(ctx, "Đang chép lời lại $n đoạn…")
        } else toast(ctx, "Không còn file âm thanh để chép lại")
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại") }
            Text(c.emoji.ifBlank { statusEmoji(c.status) }, fontSize = 24.sp, modifier = Modifier.padding(horizontal = 8.dp))
            Column(Modifier.weight(1f)) {
                Text(convTitle(c), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${Fmt.dateTime(c.startedAt)} · ${Fmt.duration(c.endedAt - c.startedAt)}" + if (c.category.isNotBlank()) " · ${c.category}" else "",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Thêm") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Chia sẻ") }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = { menu = false; share(false) })
                    DropdownMenuItem(text = { Text("Gửi email (Gmail)") }, leadingIcon = { Icon(Icons.Filled.Email, null) }, onClick = { menu = false; share(true) })
                    DropdownMenuItem(text = { Text("Gửi sang Notion") }, onClick = { menu = false; notion() })
                    DropdownMenuItem(text = { Text("Gửi webhook") }, onClick = { menu = false; webhook() })
                    if (c.status != ConvStatus.ACTIVE) {
                        DropdownMenuItem(text = { Text("Tóm tắt lại") }, leadingIcon = { Icon(Icons.Filled.Refresh, null) }, onClick = { menu = false; resummarize() })
                        DropdownMenuItem(text = { Text("Chép lời lại") }, onClick = { menu = false; retranscribe() })
                    } else {
                        DropdownMenuItem(text = { Text("Chốt đoạn & tóm tắt ngay") }, onClick = { menu = false; RecorderService.send(ctx, RecorderService.ACTION_SPLIT) })
                    }
                    // Cuộc đang diễn ra: phải chốt đoạn trước rồi mới xoá (tránh ghi tiếp vào cuộc đã xoá)
                    if (c.status != ConvStatus.ACTIVE) {
                        DropdownMenuItem(text = { Text("Xoá") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; confirmDelete = true })
                    }
                }
            }
        }
        TabRow(selectedTabIndex = tab) {
            listOf("Tóm tắt", "Việc", "Sơ đồ", "Bản ghi").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1) })
            }
        }
        when (tab) {
            0 -> SummaryTab(c, chunks) { resummarize() }
            1 -> TasksTab(c, actions, events)
            2 -> MindTab(c)
            else -> TranscriptTab(segs, chunks) { renameFrom = it }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Xoá cuộc hội thoại?") },
            text = { Text(DELETE_NOTE) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        withContext(Dispatchers.IO) { Pipeline.deleteConversation(id) }
                        onBack()
                    }
                }) { Text("Xoá") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Huỷ") } },
        )
    }
    renameFrom?.let { old ->
        RenameDialog(old, onDismiss = { renameFrom = null }) { newName ->
            renameFrom = null
            scope.launch(Dispatchers.IO) { dao.renameSpeaker(id, old, newName) }
        }
    }
}

@Composable
private fun SummaryTab(c: Conversation, chunks: List<Chunk>, onRetry: () -> Unit) {
    val ctx = LocalContext.current
    val kp = remember(c.keyPoints) { Prompts.keyPoints(c.keyPoints) }
    val ver by App.settings.changes.collectAsStateWithLifecycle()
    val llmReady = remember(ver) { Providers.llmReady(App.settings) }
    val failedChunks = chunks.count { it.status == ChunkStatus.FAILED }
    val pendingChunks = chunks.count { it.status == ChunkStatus.PENDING }
    val netState by Net.state.collectAsStateWithLifecycle()
    val block = remember(netState, ver) { Net.blockReason() }
    val send = rememberSendNow()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (c.status) {
            ConvStatus.ACTIVE -> item {
                InfoCard("🔴 Cuộc trò chuyện đang diễn ra. Flowme sẽ tóm tắt khi im lặng ${App.settings.convGapMin} phút, hoặc bấm “Chốt đoạn”.")
            }
            ConvStatus.CLOSED -> item {
                val chunkErr = chunks.lastOrNull { it.status == ChunkStatus.PENDING && !it.error.isNullOrBlank() }?.error
                val convErr = c.error?.takeIf { it.isNotBlank() }
                when {
                    pendingChunks > 0 && block != null -> InfoCard("⏳ Chờ chép lời $pendingChunks đoạn – $block.")
                    pendingChunks > 0 && chunkErr != null ->
                        InfoCard("⏳ $pendingChunks đoạn chưa chép lời được.\nLỗi: $chunkErr" + errorHint(chunkErr), send.label) { send.run() }
                    pendingChunks > 0 -> InfoCard("⏳ Đang chép lời $pendingChunks đoạn…", send.label) { send.run() }
                    !llmReady -> InfoCard("Chưa có API key tóm tắt – vào Cài đặt để thêm.")
                    block != null -> InfoCard("⏳ Chờ tóm tắt – $block.")
                    convErr != null -> InfoCard("⏳ Chưa tóm tắt được: $convErr" + errorHint(convErr), send.label) { send.run() }
                    else -> InfoCard("⏳ Đang tóm tắt…", send.label) { send.run() }
                }
            }
            ConvStatus.FAILED -> item { InfoCard("⚠️ ${c.error ?: "Không tóm tắt được"}", "Thử lại", onRetry) }
        }
        if (failedChunks > 0) item { InfoCard("❗ $failedChunks đoạn âm thanh chép lời lỗi – xem tab Bản ghi để thử lại.") }
        if (c.trivial && c.status == ConvStatus.DONE) item { InfoCard("Đoạn này ít nội dung (tạp âm, TV, câu rời rạc) nên được ẩn khỏi danh sách chính.") }
        if (c.overview.isNotBlank()) item {
            SelectionContainer { Text(c.overview, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp)) }
        }
        if (kp.isNotEmpty()) {
            item { Text("Ý chính", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp)) }
            items(kp) { p ->
                Row(Modifier.padding(horizontal = 16.dp)) {
                    Text("•  ", color = MaterialTheme.colorScheme.primary)
                    Text(p, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        c.notionUrl?.takeIf { it.isNotBlank() }?.let { url ->
            item {
                TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Mở trang Notion")
                }
            }
        }
    }
}

@Composable
private fun TasksTab(c: Conversation, actions: List<ActionItem>, events: List<CalEvent>) {
    val scope = rememberCoroutineScope()
    var newTask by rememberSaveable(c.id) { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item { SectionTitle("Lịch hẹn") }
        if (events.isEmpty()) item { InfoText("Không phát hiện lịch hẹn nào trong cuộc trò chuyện này.") }
        items(events, key = { "e${it.id}" }) { ev -> EventCard(ev) }
        item { SectionTitle("Việc cần làm") }
        if (actions.isEmpty()) item { InfoText("Không có việc cần làm.") }
        items(actions, key = { "a${it.id}" }) { a -> ActionRow(a) }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newTask, { newTask = it }, placeholder = { Text("Thêm việc…") }, singleLine = true, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    val t = newTask.trim()
                    if (t.isNotEmpty()) {
                        newTask = ""
                        scope.launch(Dispatchers.IO) { App.dao.insertAction(ActionItem(conversationId = c.id, text = t, owner = "Bạn")) }
                    }
                }) { Icon(Icons.Filled.Add, "Thêm") }
            }
        }
    }
}

@Composable
private fun MindTab(c: Conversation) {
    val ctx = LocalContext.current
    if (c.mindmap.isBlank()) {
        EmptyHint(
            if (c.status == ConvStatus.DONE) "Cuộc trò chuyện này không có sơ đồ tư duy." else "Sơ đồ tư duy sẽ có sau khi tóm tắt xong.",
            Modifier.fillMaxSize(),
        )
        return
    }
    val outline = remember(c.mindmap) {
        Mindmap.fromJson(runCatching { Parse.JSON.parseToJsonElement(c.mindmap) }.getOrNull())?.let { Mindmap.outline(it) } ?: ""
    }
    var resetKey by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        key(resetKey) { MindmapView(c.mindmap, Modifier.weight(1f).fillMaxWidth()) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { resetKey++ }) { Text("Căn lại") }
            OutlinedButton(onClick = { copyText(ctx, outline); toast(ctx, "Đã sao chép dàn ý") }) { Text("Sao chép") }
            OutlinedButton(onClick = { ShareHelper.text(ctx, "Sơ đồ tư duy: ${c.title}", outline) }) { Text("Chia sẻ") }
        }
        InfoText("Kéo để di chuyển, chụm hai ngón để phóng to/thu nhỏ. Dàn ý sao chép dán được vào XMind, Markmap, Notion.")
    }
}

@Composable
private fun TranscriptTab(segs: List<Segment>, chunks: List<Chunk>, onRename: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var playing by remember { mutableLongStateOf(0L) }
    val netState by Net.state.collectAsStateWithLifecycle()
    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) { onDispose { player.value?.release(); player.value = null } }

    fun play(ch: Chunk) {
        player.value?.release()
        player.value = null
        if (playing == ch.id) { playing = 0L; return }
        val f = File(ch.path)
        if (ch.path.isBlank() || !f.exists()) { toast(ctx, "File âm thanh đã được xoá theo cài đặt lưu trữ"); return }
        runCatching {
            MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnCompletionListener { playing = 0L }
                prepare()
                start()
            }
        }.onSuccess { player.value = it; playing = ch.id }
            .onFailure { toast(ctx, "Không phát được: ${it.message}") }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionTitle("Âm thanh (${chunks.size} đoạn)") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(chunks, key = { it.id }) { ch ->
                    val mark = when (ch.status) {
                        ChunkStatus.PENDING -> " ⏳"
                        ChunkStatus.FAILED -> " ❗"
                        ChunkStatus.EMPTY -> " ∅"
                        else -> ""
                    }
                    AssistChip(
                        onClick = {
                            if (ch.status == ChunkStatus.FAILED) {
                                scope.launch {
                                    withContext(Dispatchers.IO) { App.dao.updateChunk(ch.copy(status = ChunkStatus.PENDING, attempts = 0, error = null)) }
                                    Pipeline.processSoon(ctx)
                                }
                                toast(ctx, "Đang chép lời lại đoạn này…")
                            } else play(ch)
                        },
                        label = { Text("${if (playing == ch.id) "■" else "▶"} ${Fmt.hm(ch.startedAt)} · ${Fmt.duration(ch.durationMs)}$mark") },
                    )
                }
            }
        }
        chunks.filter { (it.status == ChunkStatus.FAILED || it.status == ChunkStatus.PENDING) && !it.error.isNullOrBlank() }.takeLast(3).forEach { ch ->
            item(key = "err${ch.id}") {
                InfoText(
                    if (ch.status == ChunkStatus.FAILED) "❗ ${Fmt.hm(ch.startedAt)}: ${ch.error} – chạm vào đoạn đó để thử lại."
                    else "⏳ ${Fmt.hm(ch.startedAt)}: ${ch.error} – app tự thử lại (lần ${ch.attempts})."
                )
            }
        }
        item { SectionTitle("Bản ghi") }
        if (segs.isEmpty()) {
            item {
                val pend = chunks.count { it.status == ChunkStatus.PENDING }
                val block = remember(netState) { Net.blockReason() }
                InfoText(
                    when {
                        pend == 0 -> "Chưa có bản ghi."
                        block != null -> "Đang chờ chép lời $pend đoạn – $block."
                        else -> "Đang chép lời $pend đoạn…"
                    }
                )
            }
        }
        items(segs, key = { it.id }) { s ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(
                    Fmt.hms(s.startAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(64.dp).padding(top = 2.dp),
                )
                Column(Modifier.weight(1f)) {
                    if (s.speaker.isNotBlank()) {
                        Text(
                            s.speaker, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = speakerColor(s.speaker),
                            modifier = Modifier.clickable { onRename(s.speaker) },
                        )
                    }
                    SelectionContainer { Text(s.text, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        if (segs.any { it.speaker.isNotBlank() }) item { InfoText("Chạm vào tên người nói để đổi tên (áp dụng cho cả cuộc trò chuyện).") }
    }
}

@Composable
private fun RenameDialog(old: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(old) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Đổi tên người nói") },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Tên") }) },
        confirmButton = {
            TextButton(onClick = { val t = text.trim(); if (t.isNotEmpty() && t != old) onConfirm(t) else onDismiss() }) { Text("Lưu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } },
    )
}
