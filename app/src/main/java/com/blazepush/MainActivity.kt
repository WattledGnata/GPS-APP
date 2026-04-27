package com.blazepush
// @IgnoreFormatCheck

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.blazepush.core.domain.permission.PermissionRequestOutcome
import com.blazepush.core.domain.permission.RequiredBluetoothPermissions
import com.blazepush.feature.test.ui.theme.NeonTheme
import com.blazepush.feature.test.ui.tracktech.TrackTechAppShell

class MainActivity : ComponentActivity() {

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

    var pendingPermissions by remember(permissions) { mutableStateOf(permissions) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        when (val outcome = PermissionRequestOutcome.from(pendingPermissions, result)) {
            PermissionRequestOutcome.AllGranted -> onAllGranted()
            is PermissionRequestOutcome.MissingPermissions -> {
                pendingPermissions = outcome.permissions
                Toast.makeText(context, "仍缺少权限，请继续授权", Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(pendingPermissions) {
        if (pendingPermissions.isNotEmpty()) {
            permissionLauncher.launch(pendingPermissions.toTypedArray())
        }
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

        Button(onClick = { permissionLauncher.launch(pendingPermissions.toTypedArray()) }) {
            Text("继续授权")
        }
    }
}
