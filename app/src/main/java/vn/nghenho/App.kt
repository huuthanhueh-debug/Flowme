package vn.nghenho

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import vn.nghenho.data.AppDb
import vn.nghenho.data.MemDao
import vn.nghenho.data.Recovery
import vn.nghenho.data.Settings
import vn.nghenho.service.Notifs
import vn.nghenho.service.StateSync
import vn.nghenho.work.Net
import vn.nghenho.work.Pipeline
import vn.nghenho.work.Work

class App : Application() {
    lateinit var database: AppDb
        private set
    lateinit var config: Settings
        private set
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        config = Settings(this)
        database = AppDb.create(this)
        Notifs.createChannels(this)
        Net.start(this)
        val processStart = System.currentTimeMillis()
        io.launch {
            // Tiến trình vừa khởi động => không có ghi âm nào đang chạy: dọn trạng thái dở dang lần trước
            runCatching { Recovery.run(this@App, database.dao(), processStart) }
            Pipeline.processSoon(this@App)
            runCatching {
                Work.schedulePeriodic(this@App, update = true) // cập nhật lượt định kỳ cũ (bỏ điều kiện mạng của WorkManager)
                Work.scheduleCleanup(this@App)
            }
        }
        StateSync.refresh(this)
    }

    companion object {
        lateinit var instance: App
            private set
        val settings: Settings get() = instance.config
        val dao: MemDao get() = instance.database.dao()
    }
}
