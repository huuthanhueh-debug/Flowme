package vn.nghenho.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.dp
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.flow.MutableStateFlow
import vn.nghenho.R

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_CONV = "conv"
        /** Mở thẳng một cuộc hội thoại (khi bấm vào thông báo tóm tắt). */
        val openConv = MutableStateFlow(0L)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent { FlowmeTheme { AppRoot() } }
    }

    // Activity tự xử lý đổi cấu hình (gập/mở Fold, sáng/tối) nên không bị tạo lại:
    // đặt lại kiểu thanh trạng thái/điều hướng để icon hệ thống luôn đúng màu với giao diện sáng/tối
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        val id = i?.getLongExtra(EXTRA_CONV, 0L) ?: 0L
        if (id > 0) openConv.value = id
    }
}

enum class AppTab(val label: String) { HOME("Nghe"), MEMORIES("Trí nhớ"), TASKS("Việc"), CHAT("Hỏi AI"), SETTINGS("Cài đặt") }

@Composable
private fun TabIcon(t: AppTab) {
    when (t) {
        AppTab.HOME -> Icon(painterResource(R.drawable.ic_mic), null)
        AppTab.MEMORIES -> Icon(Icons.AutoMirrored.Filled.List, null)
        AppTab.TASKS -> Icon(Icons.Filled.CheckCircle, null)
        AppTab.CHAT -> Icon(painterResource(R.drawable.ic_chat), null)
        AppTab.SETTINGS -> Icon(Icons.Filled.Settings, null)
    }
}

@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableStateOf(AppTab.HOME) }
    var convId by rememberSaveable { mutableLongStateOf(0L) }
    val pending by MainActivity.openConv.collectAsState()
    LaunchedEffect(pending) {
        if (pending > 0) {
            tab = AppTab.MEMORIES
            convId = pending
            MainActivity.openConv.value = 0L
        }
    }
    // Z Fold mở ra (≥ 600dp): thanh điều hướng bên trái + danh sách/chi tiết 2 cột
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    BackHandler(enabled = convId > 0 || tab != AppTab.HOME) {
        if (convId > 0) convId = 0L else tab = AppTab.HOME
    }
    val openConv: (Long) -> Unit = { convId = it; tab = AppTab.MEMORIES }
    val body: @Composable (Modifier) -> Unit = { m ->
        Box(m) {
            when (tab) {
                AppTab.HOME -> HomeScreen(onOpenConv = openConv, onOpenSettings = { tab = AppTab.SETTINGS })
                AppTab.MEMORIES -> MemoriesPane(wide = wide, selected = convId, onSelect = { convId = it }, onClose = { convId = 0L })
                AppTab.TASKS -> TasksScreen(onOpenConv = openConv)
                AppTab.CHAT -> ChatScreen()
                AppTab.SETTINGS -> SettingsScreen()
            }
        }
    }
    if (wide) {
        Row(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                BrandMark(36.dp, Modifier.padding(top = 12.dp, bottom = 4.dp))
                Spacer(Modifier.weight(1f))
                AppTab.entries.forEach { t ->
                    NavigationRailItem(
                        selected = tab == t, onClick = { tab = t }, icon = { TabIcon(t) }, label = { Text(t.label) },
                        colors = NavigationRailItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
                Spacer(Modifier.weight(1f))
            }
            body(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Scaffold(
            bottomBar = {
                Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest, tonalElevation = 0.dp) {
                    AppTab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { if (tab == t && t == AppTab.MEMORIES) convId = 0L; tab = t },
                            icon = { TabIcon(t) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
                }
            }
        ) { pad ->
            body(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize())
        }
    }
}
