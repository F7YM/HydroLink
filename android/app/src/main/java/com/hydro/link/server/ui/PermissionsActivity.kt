package com.hydro.link.server.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hydro.link.server.R
import com.hydro.link.server.service.LockAccessibilityService
import com.hydro.link.server.ui.material.ExpressiveBackButton
import com.hydro.link.server.ui.material.HydroMaterialTheme
import com.hydro.link.server.ui.material.MdBaseWidget
import com.hydro.link.server.ui.material.MdSegmentedColumn
import com.hydro.link.server.ui.material.md3BlurBar
import com.hydro.link.server.ui.material.rememberMd3BlurBackdrop
import com.hydro.link.server.util.DataManager
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar as MiuixTopAppBar
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 权限管理（独立 Activity，系统返回栈）。界面跟随当前 UI 引擎：
 * miuix → miuix 组件；Google Material 3 → Material3 组件。
 */
class PermissionsActivity : AppCompatActivity() {

    private val state = mutableStateOf(PermState(false, false, false))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val miuix = DataManager.get(this).loadConfig().miuix
        SystemBars.apply(this, miuix)
        setContent {
            val content: @Composable () -> Unit = {
                PermissionsBody(
                    state = state.value,
                    miuix = miuix,
                    onBack = { finish() },
                    onGrantAccessibility = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onGrantListener = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onGrantPost = { openNotificationSettings() },
                )
            }
            if (miuix) {
                MiuixTheme(remember { ThemeController(ColorSchemeMode.System) }) { content() }
            } else {
                HydroMaterialTheme { content() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        state.value = readState()
    }

    private fun readState(): PermState {
        val listener = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        val post = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return PermState(LockAccessibilityService.isRunning(), listener, post)
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:$packageName"))
        }
        startActivity(intent)
    }
}

private data class PermState(val acc: Boolean, val listener: Boolean, val post: Boolean)

private data class PermItem(val title: String, val granted: Boolean, val onGrant: () -> Unit)

@Composable
private fun PermissionsBody(
    state: PermState,
    miuix: Boolean,
    onBack: () -> Unit,
    onGrantAccessibility: () -> Unit,
    onGrantListener: () -> Unit,
    onGrantPost: () -> Unit,
) {
    val items = listOf(
        PermItem("无障碍服务", state.acc, onGrantAccessibility),
        PermItem("通知使用权", state.listener, onGrantListener),
        PermItem("通知权限", state.post, onGrantPost),
    )
    if (miuix) {
        MiuixPermissionsContent(items, onBack)
    } else {
        MdPermissionsContent(items, onBack)
    }
}

@Composable
private fun MiuixPermissionsContent(items: List<PermItem>, onBack: () -> Unit) {
    MiuixScaffold(
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            MiuixTopAppBar(
                title = "权限管理",
                navigationIcon = {
                    MiuixIconButton(onClick = onBack) {
                        top.yukonga.miuix.kmp.basic.Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = inner.calculateTopPadding() + 8.dp, bottom = 12.dp),
        ) {
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    items.forEach { perm ->
                        BasicComponent(
                            title = perm.title,
                            endActions = {
                                if (perm.granted) {
                                    TextButton(text = "已授予", onClick = {}, enabled = false)
                                } else {
                                    TextButton(
                                        text = "去授予",
                                        onClick = perm.onGrant,
                                        colors = ButtonDefaults.textButtonColorsPrimary(),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MdPermissionsContent(items: List<PermItem>, onBack: () -> Unit) {
    val backdrop = rememberMd3BlurBackdrop(true)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = { Text("权限管理") },
                navigationIcon = { ExpressiveBackButton(onClick = onBack) },
                modifier = Modifier.md3BlurBar(backdrop),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (backdrop != null) Color.Transparent
                    else MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = inner.calculateTopPadding() + 16.dp,
                    bottom = inner.calculateBottomPadding() + 16.dp,
                ),
            ) {
                item {
                    MdSegmentedColumn {
                        items.forEach { perm ->
                            item {
                                MdBaseWidget(
                                    title = perm.title,
                                    trailing = {
                                        if (perm.granted) {
                                            Button(onClick = {}, enabled = false) { Text("已授予") }
                                        } else {
                                            Button(onClick = perm.onGrant) { Text("去授予") }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
