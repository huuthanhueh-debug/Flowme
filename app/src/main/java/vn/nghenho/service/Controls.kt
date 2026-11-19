package vn.nghenho.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import vn.nghenho.App
import vn.nghenho.R
import vn.nghenho.data.EventState
import vn.nghenho.integrations.CalendarHelper
import vn.nghenho.ui.MainActivity
import vn.nghenho.work.Cleanup

/** Cập nhật widget + ô Cài đặt nhanh theo trạng thái ghi âm. */
object StateSync {
    fun refresh(ctx: Context) {
        runCatching { RecordWidget.refresh(ctx) }
        runCatching { TileService.requestListeningState(ctx, ComponentName(ctx, RecordTileService::class.java)) }
    }
}

/** Activity trong suốt: bật/tắt rồi đóng ngay (dùng cho widget & ô Cài đặt nhanh). */
class ToggleActivity : ComponentActivity() {
    companion object {
        /** Chỉ bật (nút "Bật lại" trên thông báo) – không tắt nếu đang chạy. */
        const val EXTRA_START_ONLY = "start_only"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startOnly = intent?.getBooleanExtra(EXTRA_START_ONLY, false) == true
        if (RecorderState.state.value.running) {
            if (!startOnly) RecorderService.send(this, RecorderService.ACTION_STOP)
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            RecorderService.start(this)
        } else {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}

/** Ô "Flowme" trong thanh Cài đặt nhanh (vuốt xuống từ trên màn hình). */
class RecordTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (RecorderState.state.value.running) {
            RecorderService.send(this, RecorderService.ACTION_STOP)
            qsTile?.let { it.state = Tile.STATE_INACTIVE; it.updateTile() }
            return
        }
        val i = Intent(this, ToggleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 21, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }

    private fun render() {
        val t = qsTile ?: return
        val st = RecorderState.state.value
        t.state = if (st.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Flowme"
        t.subtitle = when {
            !st.running -> "Đang tắt"
            st.paused -> "Tạm dừng"
            else -> "Đang nghe"
        }
        t.updateTile()
    }
}

/** Widget màn hình chính (dùng được cả trên màn hình ngoài của Z Fold). */
class RecordWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        update(context, appWidgetManager, appWidgetIds)
    }

    companion object {
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, RecordWidget::class.java))
            if (ids.isNotEmpty()) update(ctx, mgr, ids)
        }

        private fun update(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            mgr.updateAppWidget(ids, buildViews(ctx))
        }

        fun buildViews(ctx: Context): RemoteViews {
            val st = RecorderState.state.value
            val views = RemoteViews(ctx.packageName, R.layout.widget_record)
            views.setTextViewText(
                R.id.widget_text,
                when {
                    !st.running -> "Bật Flowme"
                    st.paused -> "Tạm dừng · chạm để tắt"
                    else -> "Đang nghe · chạm để tắt"
                },
            )
            views.setImageViewResource(R.id.widget_icon, if (st.running) R.drawable.ic_stop else R.drawable.ic_mic)
            views.setInt(R.id.widget_root, "setBackgroundResource", if (st.running) R.drawable.widget_bg_on else R.drawable.widget_bg_off)
            val i = Intent(ctx, ToggleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(ctx, 31, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            return views
        }
    }
}

/** Sau khi khởi động lại máy: nhắc 1 chạm để bật lại (Android không cho tự bật micro từ nền). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && App.settings.wasRecording) {
            Notifs.showResumePrompt(context, "Điện thoại vừa khởi động lại. Chạm “Bật lại” để Flowme tiếp tục nghe.")
        }
    }
}

/** Nút "Thêm vào lịch" / "Bỏ qua" trên thông báo lịch hẹn. */
class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val ADD_EVENT = "vn.nghenho.ADD_EVENT"
        const val DISMISS_EVENT = "vn.nghenho.DISMISS_EVENT"
        const val CLEANUP_DELETE = "vn.nghenho.CLEANUP_DELETE"
        const val CLEANUP_KEEP = "vn.nghenho.CLEANUP_KEEP"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == CLEANUP_DELETE || intent.action == CLEANUP_KEEP) {
            val pending = goAsync()
            App.instance.io.launch {
                try {
                    if (intent.action == CLEANUP_DELETE) {
                        Cleanup.delete(Cleanup.dailyPlan().ids)
                        Cleanup.snooze()
                    } else {
                        Cleanup.keep()
                    }
                    Notifs.cancel(context, Notifs.CLEANUP_ID)
                } finally {
                    pending.finish()
                }
            }
            return
        }
        val id = intent.getLongExtra("id", 0L)
        if (id == 0L) return
        val pending = goAsync()
        App.instance.io.launch {
            try {
                val dao = App.dao
                val ev = dao.event(id) ?: return@launch
                when (intent.action) {
                    ADD_EVENT -> {
                        val calId = CalendarHelper.insert(context, ev)
                        if (calId != null) {
                            dao.setEventState(id, EventState.ADDED, calId)
                            Notifs.showEvent(context, ev, added = true, canAdd = false)
                        }
                    }
                    DISMISS_EVENT -> {
                        dao.setEventState(id, EventState.DISMISSED, 0L)
                        Notifs.cancel(context, Notifs.eventNotifId(id))
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
