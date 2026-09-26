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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hydro.link.server.R
import com.hydro.link.server.service.LockAccessibilityService
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 权限管理（独立 Activity，系统返回栈：返回键直接 finish 回主页）。
 * 逐项检查无障碍 / 通知使用权 / 通知权限，未授予给「去授予」实心按钮。
 */
class PermissionsActivity : AppCompatActivity() {

    private val state = mutableStateOf(PermState(false, false, false))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars()
        setContent {
            MiuixTheme(remember { ThemeController(ColorSchemeMode.System) }) {
                PermissionsContent(
                    state = state.value,
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

    private fun applySystemBars() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val color = if (night) 0xFF000000.toInt() else 0xFFF7F7F7.toInt()
        window.statusBarColor = color
        window.navigationBarColor = color
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }
}

private data class PermState(val acc: Boolean, val listener: Boolean, val post: Boolean)

@Composable
private fun PermissionsContent(
    state: PermState,
    onBack: () -> Unit,
    onGrantAccessibility: () -> Unit,
    onGrantListener: () -> Unit,
    onGrantPost: () -> Unit,
) {
    Scaffold(
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = "权限管理",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
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
            contentPadding = PaddingValues(
                top = inner.calculateTopPadding() + 8.dp,
                bottom = 12.dp,
            ),
        ) {
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    PermissionRow("无障碍服务", state.acc, onGrantAccessibility)
                    PermissionRow("通知使用权", state.listener, onGrantListener)
                    PermissionRow("通知权限", state.post, onGrantPost)
                }
            }
        }
    }
}

/** 权限项：右侧「去授予」实心按钮；已授予时灰显不可点。 */
@Composable
private fun PermissionRow(title: String, granted: Boolean, onGrant: () -> Unit) {
    BasicComponent(
        title = title,
        endActions = {
            if (granted) {
                TextButton(text = "已授予", onClick = {}, enabled = false)
            } else {
                TextButton(
                    text = "去授予",
                    onClick = onGrant,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        },
    )
}
