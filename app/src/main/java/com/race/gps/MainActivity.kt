package com.race.gps

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.race.gps.data.model.BluetoothDeviceModel
import com.race.gps.service.BluetoothManager
import com.race.gps.viewmodel.BluetoothDeviceViewModel
import com.race.gps.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private lateinit var bluetoothViewModel: BluetoothDeviceViewModel
    private lateinit var mainViewModel: MainViewModel
    
    // Bluetooth Manager
    private lateinit var bluetoothManager: BluetoothManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val bluetoothScanGranted = permissions[Manifest.permission.BLUETOOTH_SCAN] ?: false
        val bluetoothConnectGranted = permissions[Manifest.permission.BLUETOOTH_CONNECT] ?: false
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false

        if (bluetoothScanGranted && bluetoothConnectGranted && locationGranted) {
            bluetoothViewModel.startScan()
        } else {
            Toast.makeText(this, "Permissions not granted", Toast.LENGTH_SHORT).show()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            checkPermissions()
        } else {
            Toast.makeText(this, "Bluetooth not enabled", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set status bar to black/immersive style
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.BLACK

        bluetoothViewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[BluetoothDeviceViewModel::class.java]
        mainViewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[MainViewModel::class.java]

        // Initialize Bluetooth Manager
        bluetoothManager = BluetoothManager.getInstance(this)

        // Check permissions when app starts
        checkPermissions()

        setContent {
            // Add MaterialTheme with light color scheme for better visibility
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF6200EE),
                    background = Color.White,
                    surface = Color.White,
                    onPrimary = Color.White,
                    onBackground = Color.Black,
                    onSurface = Color.Black
                )
            ) {
                BluetoothDeviceScreen(
                    bluetoothViewModel = bluetoothViewModel,
                    mainViewModel = mainViewModel,
                    onEnableBluetooth = { enableBluetooth() },
                    onCheckPermissions = { checkPermissions() },
                    onDeviceSelect = { device ->
                        // Check if device is available before proceeding
                        if (bluetoothViewModel.isDeviceAvailable(device.address)) {
                            // Connect to device via BluetoothManager
                            bluetoothManager.connectToDevice(device.address)
                            
                            val intent = Intent(this, TestSelectionActivity::class.java)
                            intent.putExtra("device_name", device.name)
                            intent.putExtra("device_address", device.address)
                            startActivity(intent)
                        } else {
                            Toast.makeText(this, "设备不可用，请重新发现蓝牙设备", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }

    private fun enableBluetooth() {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        enableBluetoothLauncher.launch(enableBtIntent)
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        } else {
            bluetoothViewModel.startScan()
        }
    }
}

@Composable
fun BluetoothDeviceScreen(
    bluetoothViewModel: BluetoothDeviceViewModel,
    mainViewModel: MainViewModel,
    onEnableBluetooth: () -> Unit,
    onCheckPermissions: () -> Unit,
    onDeviceSelect: (BluetoothDeviceModel) -> Unit
) {
    val scanState by bluetoothViewModel.scanState.observeAsState()
    val discoveredDevices by bluetoothViewModel.discoveredDevices.observeAsState(emptyList())
    val savedDevices by bluetoothViewModel.savedDevices.observeAsState(emptyList())
    val testRecords by mainViewModel.testRecords.collectAsState(emptyList())
    
    // Track if user has triggered search
    var showSearchResults by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .padding(top = 48.dp), // Add top padding to avoid status bar overlap
        verticalArrangement = Arrangement.Top
    ) {
        Button(
            onClick = {
                if (!BluetoothAdapter.getDefaultAdapter().isEnabled) {
                    onEnableBluetooth()
                } else {
                    onCheckPermissions()
                    showSearchResults = true // Show search results when user triggers search
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Text(text = when (scanState) {
                is BluetoothDeviceViewModel.ScanState.Scanning -> "Scanning..."
                else -> "Discover RaceChrono BLE Devices"
            })
        }

        if (scanState is BluetoothDeviceViewModel.ScanState.Error) {
            Text(
                text = (scanState as BluetoothDeviceViewModel.ScanState.Error).message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        if (savedDevices.isNotEmpty()) {
            Text(
                text = "Saved Devices:",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .padding(bottom = 16.dp)
            ) {
                items(items = savedDevices) { device ->
                    SavedDeviceItem(
                        device = device,
                        onSelect = { onDeviceSelect(device) },
                        onRemove = { bluetoothViewModel.removeDevice(device.address) }
                    )
                }
            }
        }

        // Only show discovered devices when user has triggered search
        if (showSearchResults) {
            Text(
                text = "Discovered Devices:",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyColumn {
                // Show all discovered devices that match the RaceChrono protocol
                items(items = discoveredDevices) { device ->
                    DiscoveredDeviceItem(
                        device = device,
                        onSelect = { 
                            bluetoothViewModel.saveDevice(device) // Auto-save when selecting
                            onDeviceSelect(device) 
                        }
                    )
                }
                if (discoveredDevices.isEmpty() && scanState !is BluetoothDeviceViewModel.ScanState.Scanning) {
                    item {
                        Text(text = "No RaceChrono devices found.")
                    }
                }
            }
        }
        
        // Test Records Section
        if (testRecords.isNotEmpty()) {
            Text(
                text = "Test Records:",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )
            
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            ) {
                items(testRecords) {
                    SimpleTestRecordItem(record = it)
                }
            }
        }
    }
}

@Composable
fun DiscoveredDeviceItem(
    device: BluetoothDeviceModel,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = device.name ?: "RaceChrono Device",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(text = device.address)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Button(onClick = onSelect) {
                    Text(text = "Select")
                }
            }
        }
    }
}

@Composable
fun SavedDeviceItem(
    device: BluetoothDeviceModel,
    onSelect: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = device.name ?: "RaceChrono Device",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(text = device.address)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = onRemove,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(text = "Remove")
                }
                Button(onClick = onSelect) {
                    Text(text = "Select")
                }
            }
        }
    }
}
