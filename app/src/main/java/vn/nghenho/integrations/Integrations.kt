package vn.nghenho.integrations

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import vn.nghenho.App
import vn.nghenho.ai.Http
import vn.nghenho.ai.Parse
import vn.nghenho.ai.Prompts
import vn.nghenho.data.ActionItem
import vn.nghenho.data.CalEvent
import vn.nghenho.data.ConvStatus
import vn.nghenho.data.Conversation
import vn.nghenho.data.EventState
import vn.nghenho.data.Segment
import vn.nghenho.service.Notifs
import vn.nghenho.util.Fmt
import vn.nghenho.util.Mindmap
import vn.nghenho.util.Times
import vn.nghenho.util.str
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Chạy sau mỗi lần tóm tắt: thông báo, lịch hẹn, Notion, webhook. */
object Integrations {
    fun afterSummary(ctx: Context, conv: Conversation, events: List<CalEvent>) {
        val s = App.settings
        if (!conv.trivial && s.notifySummaries) Notifs.showSummary(ctx, conv)
        val now = System.currentTimeMillis()
        val canAdd = CalendarHelper.hasPermission(ctx)
        for (ev in events) {
            if (ev.startAt < now - 3_600_000L) continue
            if (s.autoCalendar && canAdd) {
                val id = CalendarHelper.insert(ctx, ev)
                if (id != null) {
                    App.dao.setEventState(ev.id, EventState.ADDED, id)
                    Notifs.showEvent(ctx, ev, added = true, canAdd = true)
                    continue
                }
            }
            Notifs.showEvent(ctx, ev, added = false, canAdd = canAdd)
        }
        if (!conv.trivial && s.notionAuto) runCatching { Notion.pushConversation(conv.id) }.onFailure { Log.w("NgheNho", "notion", it) }
        if (!conv.trivial && s.webhookAuto) runCatching { Webhook.post(conv.id) }.onFailure { Log.w("NgheNho", "webhook", it) }
    }
}

/** Google Calendar qua lịch của điện thoại (tài khoản Google trên máy tự đồng bộ) – không cần đăng nhập thêm. */
object CalendarHelper {
    data class Cal(val id: Long, val name: String, val account: String, val primary: Boolean, val type: String)

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun list(ctx: Context): List<Cal> {
        if (!hasPermission(ctx)) return emptyList()
        val proj = arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.IS_PRIMARY, Calendars.ACCOUNT_TYPE)
        val sel = "${Calendars.CALENDAR_ACCESS_LEVEL} >= ${Calendars.CAL_ACCESS_CONTRIBUTOR} AND ${Calendars.VISIBLE} = 1"
        val out = mutableListOf<Cal>()
        runCatching {
            ctx.contentResolver.query(Calendars.CONTENT_URI, proj, sel, null, null)?.use { c ->
                while (c.moveToNext()) {
                    out += Cal(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getInt(3) == 1, c.getString(4) ?: "")
                }
            }
        }
        return out
    }

    private fun pickCalendar(ctx: Context): Long? {
        val cals = list(ctx)
        if (cals.isEmpty()) return null
        val chosen = App.settings.calendarId
        cals.firstOrNull { it.id == chosen }?.let { return it.id }
        return (cals.firstOrNull { it.type == "com.google" && it.primary }
            ?: cals.firstOrNull { it.type == "com.google" }
            ?: cals.firstOrNull { it.primary }
            ?: cals.first()).id
    }

    fun insert(ctx: Context, ev: CalEvent): Long? {
        if (!hasPermission(ctx)) return null
        val calId = pickCalendar(ctx) ?: return null
        val v = ContentValues().apply {
            put(Events.CALENDAR_ID, calId)
            put(Events.TITLE, ev.title)
            put(Events.DTSTART, ev.startAt)
            put(Events.DTEND, ev.endAt)
            put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (ev.location.isNotBlank()) put(Events.EVENT_LOCATION, ev.location)
            put(Events.DESCRIPTION, (ev.notes + "\n\n— Tạo bởi Flowme từ cuộc trò chuyện").trim())
        }
        val uri = runCatching { ctx.contentResolver.insert(Events.CONTENT_URI, v) }.getOrNull() ?: return null
        val id = ContentUris.parseId(uri)
        runCatching {
            ctx.contentResolver.insert(Reminders.CONTENT_URI, ContentValues().apply {
                put(Reminders.EVENT_ID, id)
                put(Reminders.MINUTES, 30)
                put(Reminders.METHOD, Reminders.METHOD_ALERT)
            })
        }
        return id
    }

    fun insertIntent(ev: CalEvent): Intent = Intent(Intent.ACTION_INSERT)
        .setData(Events.CONTENT_URI)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ev.startAt)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, ev.endAt)
        .putExtra(Events.TITLE, ev.title)
        .putExtra(Events.EVENT_LOCATION, ev.location)
        .putExtra(Events.DESCRIPTION, ev.notes)

    fun viewIntent(calEventId: Long): Intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(Events.CONTENT_URI, calEventId))
}

/** Đẩy tóm tắt sang một database Notion (Internal Integration). */
object Notion {
    private const val API = "https://api.notion.com/v1"
    private const val VERSION = "2022-06-28"

    fun normalizeId(raw: String): String {
        val r = raw.trim()
        if (r.isEmpty()) return ""
        val hex32 = Regex("^[0-9a-fA-F]{32}$")
        val compact = r.replace("-", "")
        if (hex32.matches(compact)) return compact.lowercase()
        val path = r.substringBefore('?').trimEnd('/')
        val last = path.substringAfterLast('/').substringAfterLast('-')
        if (hex32.matches(last)) return last.lowercase()
        return Regex("[0-9a-fA-F]{32}").find(path.replace("-", ""))?.value?.lowercase() ?: r
    }

    private fun req(token: String, path: String) = Request.Builder()
        .url(API + path)
        .header("Authorization", "Bearer $token")
        .header("Notion-Version", VERSION)

    private fun titleProperty(token: String, dbId: String): String {
        val txt = Http.call(req(token, "/databases/$dbId").get().build(), "Notion")
        val props = (Parse.JSON.parseToJsonElement(txt) as? JsonObject)?.get("properties") as? JsonObject ?: return "Name"
        return props.entries.firstOrNull { (it.value as? JsonObject).str("type") == "title" }?.key ?: "Name"
    }

    fun test(): String {
        val s = App.settings
        val token = s.key("notion")
        val db = normalizeId(s.notionDbId)
        require(token.isNotBlank() && db.isNotBlank()) { "Chưa nhập token hoặc database Notion" }
        return "Kết nối được. Cột tiêu đề: \"${titleProperty(token, db)}\""
    }

    private fun rich(text: String): JsonArray = buildJsonArray {
        text.chunked(1900).take(3).forEach { part ->
            addJsonObject {
                put("type", "text")
                putJsonObject("text") { put("content", part) }
            }
        }
    }

    private fun block(type: String, text: String): JsonObject = buildJsonObject {
        put("object", "block")
        put("type", type)
        putJsonObject(type) { put("rich_text", rich(text)) }
    }

    private fun todo(text: String, done: Boolean): JsonObject = buildJsonObject {
        put("object", "block")
        put("type", "to_do")
        putJsonObject("to_do") {
            put("rich_text", rich(text))
            put("checked", done)
        }
    }

    fun pushConversation(convId: Long): String {
        val s = App.settings
        val token = s.key("notion")
        val db = normalizeId(s.notionDbId)
        require(token.isNotBlank() && db.isNotBlank()) { "Chưa cấu hình Notion trong Cài đặt" }
        val dao = App.dao
        val c = dao.conv(convId) ?: error("Không tìm thấy cuộc hội thoại")
        val segs = dao.segmentsOf(convId)
        val actions = dao.actionsOf(convId)
        val events = dao.eventsOf(convId)

        val blocks = mutableListOf<JsonObject>()
        blocks += block("paragraph", "${Fmt.dateTimeFull(c.startedAt)} · ${Fmt.duration(c.endedAt - c.startedAt)}" + if (c.category.isNotBlank()) " · ${c.category}" else "")
        if (c.overview.isNotBlank()) { blocks += block("heading_2", "Tóm tắt"); blocks += block("paragraph", c.overview) }
        val kp = Prompts.keyPoints(c.keyPoints)
        if (kp.isNotEmpty()) { blocks += block("heading_2", "Ý chính"); kp.forEach { blocks += block("bulleted_list_item", it) } }
        if (actions.isNotEmpty()) {
            blocks += block("heading_2", "Việc cần làm")
            actions.forEach { a -> blocks += todo(a.text + (if (a.owner.isNotBlank()) " (${a.owner})" else "") + (if (a.due.isNotBlank()) " – hạn ${a.due}" else ""), a.done) }
        }
        if (events.isNotEmpty()) {
            blocks += block("heading_2", "Lịch hẹn")
            events.forEach { e -> blocks += block("bulleted_list_item", "${Fmt.dateTime(e.startAt)} – ${e.title}" + if (e.location.isNotBlank()) " @ ${e.location}" else "") }
        }
        val transcript = Fmt.transcript(segs)
        if (transcript.isNotBlank()) {
            blocks += block("heading_2", "Bản ghi")
            val room = (98 - blocks.size).coerceAtLeast(0)
            val paras = mutableListOf(StringBuilder())
            for (line in transcript.lines()) {
                if (paras.last().length + line.length > 1800) paras += StringBuilder()
                paras.last().append(line).append('\n')
            }
            paras.take(room).forEach { blocks += block("paragraph", it.toString().trimEnd()) }
        }

        val titleProp = titleProperty(token, db)
        val title = "${c.emoji} ${c.title.ifBlank { "Cuộc trò chuyện" }}".trim()
        val body = buildJsonObject {
            putJsonObject("parent") { put("database_id", db) }
            putJsonObject("properties") {
                putJsonObject(titleProp) {
                    putJsonArray("title") { addJsonObject { putJsonObject("text") { put("content", title) } } }
                }
            }
            put("children", JsonArray(blocks.take(100)))
        }
        val txt = Http.call(req(token, "/pages").post(body.toString().toRequestBody(Http.JSON)).build(), "Notion")
        val url = (Parse.JSON.parseToJsonElement(txt) as? JsonObject).str("url")
        if (url.isNotBlank()) dao.setNotionUrl(convId, url)
        return url
    }
}

/** Gửi JSON mỗi cuộc hội thoại tới Zapier / Make / n8n… (từ đó nối Gmail, Sheets, Slack…). */
object Webhook {
    fun post(convId: Long) {
        val url = App.settings.webhookUrl.trim()
        require(url.startsWith("http")) { "Chưa nhập Webhook URL" }
        val payload = buildJsonObject {
            put("app", "Flowme")
            put("event", "conversation.processed")
            put("conversation", Export.json(convId))
        }
        Http.call(Request.Builder().url(url).post(payload.toString().toRequestBody(Http.JSON)).build(), "Webhook")
    }
}

object Export {
    fun markdown(c: Conversation, segs: List<Segment>, actions: List<ActionItem>, events: List<CalEvent>): String = buildString {
        appendLine("# ${c.emoji} ${c.title.ifBlank { "Cuộc trò chuyện" }}".trim())
        appendLine("_${Fmt.dateTimeFull(c.startedAt)} · ${Fmt.duration(c.endedAt - c.startedAt)}${if (c.category.isNotBlank()) " · " + c.category else ""}_")
        appendLine()
        if (c.overview.isNotBlank()) { appendLine("## Tóm tắt"); appendLine(c.overview); appendLine() }
        val kp = Prompts.keyPoints(c.keyPoints)
        if (kp.isNotEmpty()) { appendLine("## Ý chính"); kp.forEach { appendLine("- $it") }; appendLine() }
        if (actions.isNotEmpty()) {
            appendLine("## Việc cần làm")
            actions.forEach { a -> appendLine("- [${if (a.done) "x" else " "}] ${a.text}" + (if (a.owner.isNotBlank()) " (${a.owner})" else "") + (if (a.due.isNotBlank()) " – hạn ${a.due}" else "")) }
            appendLine()
        }
        if (events.isNotEmpty()) {
            appendLine("## Lịch hẹn")
            events.forEach { e -> appendLine("- ${Fmt.dateTime(e.startAt)} – ${e.title}" + if (e.location.isNotBlank()) " @ ${e.location}" else "") }
            appendLine()
        }
        Mindmap.fromJson(runCatching { Parse.JSON.parseToJsonElement(c.mindmap) }.getOrNull())?.let {
            appendLine("## Sơ đồ tư duy"); append(Mindmap.outline(it)); appendLine()
        }
        if (segs.isNotEmpty()) { appendLine("## Bản ghi"); appendLine(Fmt.transcript(segs)) }
    }

    fun markdown(convId: Long): String {
        val dao = App.dao
        val c = dao.conv(convId) ?: return ""
        return markdown(c, dao.segmentsOf(convId), dao.actionsOf(convId), dao.eventsOf(convId))
    }

    fun json(c: Conversation, segs: List<Segment>, actions: List<ActionItem>, events: List<CalEvent>): JsonObject = buildJsonObject {
        put("id", c.id)
        put("title", c.title)
        put("emoji", c.emoji)
        put("category", c.category)
        put("overview", c.overview)
        put("started_at", Times.isoLocal(c.startedAt))
        put("ended_at", Times.isoLocal(c.endedAt))
        put("key_points", JsonArray(Prompts.keyPoints(c.keyPoints).map { JsonPrimitive(it) }))
        putJsonArray("action_items") {
            actions.forEach { a -> addJsonObject { put("text", a.text); put("owner", a.owner); put("due", a.due); put("done", a.done) } }
        }
        putJsonArray("events") {
            events.forEach { e ->
                addJsonObject {
                    put("title", e.title); put("start", Times.isoLocal(e.startAt)); put("end", Times.isoLocal(e.endAt))
                    put("location", e.location); put("notes", e.notes)
                }
            }
        }
        if (c.mindmap.isNotBlank()) runCatching { Parse.JSON.parseToJsonElement(c.mindmap) }.getOrNull()?.let { put("mindmap", it) }
        putJsonArray("transcript") {
            segs.forEach { s -> addJsonObject { put("time", Times.isoLocal(s.startAt)); put("speaker", s.speaker); put("text", s.text) } }
        }
    }

    fun json(convId: Long): JsonObject {
        val dao = App.dao
        val c = dao.conv(convId) ?: return buildJsonObject { }
        return json(c, dao.segmentsOf(convId), dao.actionsOf(convId), dao.eventsOf(convId))
    }

    /** Xuất toàn bộ trí nhớ ra Download/Flowme (Markdown + JSON). */
    fun exportAll(ctx: Context): String {
        val dao = App.dao
        val convs = dao.allConvs().filter { it.status == ConvStatus.DONE || it.status == ConvStatus.CLOSED }
        val md = StringBuilder("# Flowme – xuất dữ liệu\n\n")
        val arr = mutableListOf<JsonObject>()
        for (c in convs) {
            val segs = dao.segmentsOf(c.id)
            val actions = dao.actionsOf(c.id)
            val events = dao.eventsOf(c.id)
            md.append(markdown(c, segs, actions, events)).append("\n---\n\n")
            arr += json(c, segs, actions, events)
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        saveToDownloads(ctx, "nghenho_$stamp.md", "text/markdown", md.toString().toByteArray())
        saveToDownloads(ctx, "nghenho_$stamp.json", "application/json", JsonArray(arr).toString().toByteArray())
        return "Download/Flowme/flowme_$stamp (.md + .json) – ${convs.size} cuộc hội thoại"
    }

    private fun saveToDownloads(ctx: Context, name: String, mime: String, bytes: ByteArray) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Flowme")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val r = ctx.contentResolver
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Không tạo được file $name")
        r.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Không ghi được file $name")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        r.update(uri, values, null, null)
    }
}

object ShareHelper {
    fun text(ctx: Context, subject: String, body: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
        ctx.startActivity(Intent.createChooser(i, "Chia sẻ").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Mở Gmail (hoặc app email) với nội dung soạn sẵn – bạn chỉ bấm Gửi. */
    fun email(ctx: Context, to: String, subject: String, body: String) {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
        if (to.isNotBlank()) i.putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            text(ctx, subject, body)
        }
    }
}
