package com.blazepush
// @IgnoreFormatCheck

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.blazepush.core.data.repository.TelemetryRepository
import com.blazepush.core.domain.permission.PermissionRequestOutcome
import com.blazepush.core.domain.permission.RequiredBluetoothPermissions
import com.blazepush.feature.test.ui.theme.NeonTheme
import com.blazepush.feature.test.ui.tracktech.TrackTechAppShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get

class MainActivity : ComponentActivity() {

    override fun onStop() {
        super.onStop()
        // Independent short-lived IO job: Activity destruction must not cancel the durability boundary.
        CoroutineScope(Dispatchers.IO).launch {
            get<TelemetryRepository>().flush()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requiredPermissions = RequiredBluetoothPermissions.forSdk(Build.VERSION.SDK_INT)

        // 检查权限
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            // 权限已授予，显示主界面
            setContent {
                NeonTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        TrackTechAppShell()
                    }
                }
            }
        } else {
            // 权限未授予，显示权限请求界面
            setContent {
                NeonTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        PermissionRequestScreen(
                            permissions = missingPermissions,
                            onAllGranted = {
                                // 重新启动，显示主界面
                                recreate()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRequestScreen(
    permissions: List<String>,
    onAllGranted: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var pendingPermissions by remember(permissions) { mutableStateOf(permissions) }
    var showSettingsAction by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        when (val outcome = PermissionRequestOutcome.from(pendingPermissions, result)) {
            PermissionRequestOutcome.AllGranted -> onAllGranted()
            is PermissionRequestOutcome.MissingPermissions -> {
                pendingPermissions = outcome.permissions
                val activity = context as? Activity
                showSettingsAction = activity != null && outcome.permissions.any {
                    !activity.shouldShowRequestPermissionRationale(it)
                }
                val message = if (showSettingsAction) {
                    "系统已不再弹出授权窗口，请到应用设置开启权限"
                } else {
                    "仍缺少权限，请继续授权"
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(pendingPermissions, showSettingsAction) {
        if (pendingPermissions.isNotEmpty() && !showSettingsAction) {
            permissionLauncher.launch(pendingPermissions.toTypedArray())
        }
    }

    // 从应用设置返回时自动复检，不要求用户再次杀进程或反复点按钮。
    DisposableEffect(lifecycleOwner, showSettingsAction) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && showSettingsAction) {
                val stillMissing = pendingPermissions.filter {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
                if (stillMissing.isEmpty()) {
                    onAllGranted()
                } else {
                    pendingPermissions = stillMissing
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "需要以下权限",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "• 位置权限 - 用于 GPS 测试\n• 蓝牙权限 - 用于连接 GPS 设备\n• 若只授权部分权限，页面会保留并继续补申请",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = {
            if (showSettingsAction) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        },
                    )
                }.onFailure {
                    Toast.makeText(context, "无法打开系统设置，请手动为 BlazePush 开启权限", Toast.LENGTH_LONG).show()
                }
            } else {
                permissionLauncher.launch(pendingPermissions.toTypedArray())
            }
        }) {
            Text(if (showSettingsAction) "打开应用设置" else "继续授权")
        }
    }
}
