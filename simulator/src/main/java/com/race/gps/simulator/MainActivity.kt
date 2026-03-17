package com.race.gps.simulator

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
import com.race.gps.simulator.ui.SimulatorScreen
import com.race.gps.simulator.viewmodel.SimulatorViewModel

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

        // 检查权限
        if (!checkPermissions()) {
            requestPermissions()
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                SimulatorScreen(viewModel)
            }
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
