package vn.nghenho.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import vn.nghenho.App
import vn.nghenho.data.ConvStatus
import vn.nghenho.data.EventState
import vn.nghenho.service.Notifs
import vn.nghenho.util.Fmt
import java.io.File

/**
 * Trạng thái mạng lấy thẳng từ ConnectivityManager.
 * Không đòi mạng "đã xác thực" như WorkManager (một số máy/mạng có DNS riêng, VPN… làm WorkManager tưởng mất mạng).
 */
object Net {
    data class State(val online: Boolean = true, val metered: Boolean = false, val wifi: Boolean = false)

    val state = MutableStateFlow(State())
    @Volatile private var started = false
    @Volatile private var current: Network? = null

    private fun from(c: NetworkCapabilities?) = State(
        online = c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
        metered = c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true,
        wifi = c?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
    )

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        state.value = runCatching { from(cm.getNetworkCapabilities(cm.activeNetwork)) }.getOrDefault(State())
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    current = network
                    val was = state.value
                    val now = from(caps)
                    state.value = now
                    // Vừa có mạng (hoặc vừa sang Wi-Fi) => gửi ngay các đoạn đang chờ
                    if (now.online && (!was.online || (was.metered && !now.metered))) Pipeline.processSoon(app)
                }

                override fun onLost(network: Network) {
                    // Khi chuyển 5G ⇄ Wi-Fi, mạng cũ có thể báo "mất" SAU khi mạng mới đã sẵn sàng → bỏ qua
                    if (current == null || network == current) {
                        current = null
                        state.value = State(online = false)
                    }
                }
            })
        }
    }

    /** Lý do chưa gửi đi xử lý được; null = gửi được. */
    fun blockReason(): String? {
        var s = state.value
        // Trước khi chặn: hỏi lại Android trạng thái mạng hiện tại (tránh kẹt vì một sự kiện mạng cũ)
        if (!s.online || s.metered) {
            runCatching {
                val cm = App.instance.getSystemService(ConnectivityManager::class.java)
                from(cm.getNetworkCapabilities(cm.activeNetwork))
            }.getOrNull()?.let { fresh -> if (fresh != s) { s = fresh; state.value = fresh } }
        }
        return when {
            !s.online -> "điện thoại chưa có mạng"
            App.settings.wifiOnly && s.metered && s.wifi ->
                "Wi-Fi này bị Android coi là mạng tính phí, mà bạn đang bật “Chỉ gửi khi có Wi-Fi” – tắt tuỳ chọn đó trong Cài đặt"
            App.settings.wifiOnly && s.metered -> "đang chờ Wi-Fi (bạn đã bật “Chỉ gửi khi có Wi-Fi”)"
            else -> null
        }
    }
}

/** Nhật ký xử lý ngắn gọn – hiện trong Cài đặt để người dùng tự xem/gửi khi báo lỗi. */
object ProcLog {
    val changes = MutableStateFlow(0)
    private fun file(): File = File(App.instance.filesDir, "process_log.txt")

    @Synchronized
    fun add(line: String) {
        runCatching {
            val f = file()
            val now = System.currentTimeMillis()
            f.appendText("${Fmt.dm(now)} ${Fmt.hms(now)}  ${line.replace('\n', ' ').take(400)}\n")
            if (f.length() > 100_000) f.writeText(f.readLines().takeLast(300).joinToString("\n", postfix = "\n"))
        }
        changes.update { it + 1 }
    }

    fun recent(n: Int = 30): List<String> = runCatching { file().readLines().takeLast(n).reversed() }.getOrDefault(emptyList())
}

/** Dọn trí nhớ: sang ngày mới, đề nghị xoá các cuộc hội thoại cũ đã lưu lịch hẹn xong. */
object Cleanup {
    data class Plan(val ids: List<Long>, val waitingCalendar: Int, val openTasks: Int)

    fun today(now: Long = System.currentTimeMillis()): String = Fmt.zdt(now).toLocalDate().toString()

    /** Các cuộc hội thoại của những ngày trước đã tóm tắt xong, lịch hẹn đã thêm vào Lịch (hoặc đã qua / đã bỏ qua). */
    fun dailyPlan(now: Long = System.currentTimeMillis()): Plan {
        val dao = App.dao
        val convs = dao.finishedBetween(App.settings.cleanupKeptBefore, Fmt.startOfDay(now)).filter { it.status == ConvStatus.DONE }
        val ids = mutableListOf<Long>()
        var waiting = 0
        var open = 0
        for (c in convs) {
            if (dao.eventsOf(c.id).any { it.state == EventState.SUGGESTED && it.startAt > now }) { waiting++; continue }
            ids += c.id
            open += dao.actionsOf(c.id).count { !it.done }
        }
        return Plan(ids, waiting, open)
    }

    /** days = 0: mọi cuộc trước hôm nay; 7: cũ hơn 7 ngày… (chỉ cuộc đã xử lý xong/lỗi, không đụng cuộc đang diễn ra). */
    fun olderThan(days: Int, now: Long = System.currentTimeMillis()): List<Long> =
        App.dao.finishedBetween(0, Fmt.startOfDay(now) - days * 86_400_000L).map { it.id }

    fun shouldAsk(now: Long = System.currentTimeMillis()): Boolean =
        App.settings.askCleanupDaily && App.settings.cleanupPromptDay != today(now)

    /** Xoá hội thoại (âm thanh, bản ghi, tóm tắt) – việc chưa xong vẫn được giữ trong tab Việc. */
    fun delete(ids: List<Long>): Int {
        ids.forEach { Pipeline.deleteConversation(it, keepOpenActions = true) }
        if (ids.isNotEmpty()) ProcLog.add("🧹 Đã xoá ${ids.size} cuộc hội thoại cũ")
        return ids.size
    }

    /** "Giữ lại": không hỏi lại về các ngày này nữa. */
    fun keep(now: Long = System.currentTimeMillis()) {
        App.settings.cleanupKeptBefore = Fmt.startOfDay(now)
        App.settings.cleanupPromptDay = today(now)
    }

    /** "Để sau": hôm nay không hỏi nữa. */
    fun snooze(now: Long = System.currentTimeMillis()) {
        App.settings.cleanupPromptDay = today(now)
    }

    fun maybeNotify(ctx: Context) {
        if (!shouldAsk()) return
        val p = dailyPlan()
        if (p.ids.isNotEmpty()) Notifs.showCleanup(ctx, p.ids.size, p.openTasks)
    }
}

/** Chạy mỗi sáng (~8:00): nếu có hội thoại cũ đã lưu lịch xong thì hỏi bằng thông báo có nút Xoá / Giữ lại. */
class CleanupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { Cleanup.maybeNotify(applicationContext) }
        return Result.success()
    }
}
