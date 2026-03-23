package com.blazepush.simulator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.content.ContextCompat
import com.blazepush.simulator.ui.SimulatorScreen
import com.blazepush.simulator.viewmodel.SimulatorViewModel

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: SimulatorViewModel

    // 权限请求
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show()
            viewModel.checkPermissions(this)
        } else {
            Toast.makeText(this, "权限被拒绝", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = SimulatorViewModel()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                SimulatorScreen(
                    viewModel = viewModel,
                    onRequestPermission = { requestPermissions() }
                )
            }
        }

        // 启动时检查权限
        checkAndRequestPermissions()
    }

    /**
     * 检查并请求权限
     */
    private fun checkAndRequestPermissions() {
        val missingPermissions = SimulatorViewModel.REQUIRED_PERMISSIONS.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            // 有缺失的权限，自动请求
            requestPermissions()
        } else {
            // 所有权限都已授予
            viewModel.checkPermissions(this)
        }
    }

    /**
     * 检查权限
     */
    private fun checkPermissions(): Boolean {
        return SimulatorViewModel.REQUIRED_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 请求权限
     */
    private fun requestPermissions() {
        requestPermissionLauncher.launch(SimulatorViewModel.REQUIRED_PERMISSIONS)
    }
}
