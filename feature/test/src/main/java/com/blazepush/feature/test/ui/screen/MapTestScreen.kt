package com.blazepush.feature.test.ui.screen

import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.blazepush.core.domain.model.GpsData
import com.blazepush.feature.test.viewmodel.GpsDataViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * 地图测试页面 - 显示蓝牙 GPS 设备位置
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapTestScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    gpsDataViewModel: GpsDataViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val gpsData by gpsDataViewModel.gpsData.collectAsState()

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var aMap by remember { mutableStateOf<AMap?>(null) }
    var currentMarker by remember { mutableStateOf<Marker?>(null) }

    // 创建地图
    DisposableEffect(Unit) {
        val view = MapView(context)
        mapView = view

        view.onCreate(Bundle())

        onDispose {
            view.onDestroy()
        }
    }

    // 当 GPS 数据更新时，更新地图标记
    LaunchedEffect(gpsData) {
        val map = aMap ?: mapView?.map ?: return@LaunchedEffect
        aMap = map

        if (gpsData.latitude != 0.0 && gpsData.longitude != 0.0) {
            val gcj02 = gpsData.toGcj02()
            val latLng = LatLng(gcj02.latitude, gcj02.longitude)

            // 移除旧标记
            currentMarker?.remove()

            // 添加新标记
            currentMarker = map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title("GPS 设备")
                    .snippet("速度: ${String.format("%.1f", gpsData.speed)} km/h")
            )

            // 移动相机到当前位置
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(latLng, 16f)
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("地图测试") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            mapView?.let { view ->
                AndroidView(
                    factory = { view },
                    modifier = Modifier.fillMaxSize()
                )
            } ?: run {
                CircularProgressIndicator()
            }

            // 显示 GPS 状态信息
            if (gpsData.latitude == 0.0) {
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (gpsData.isConnected) "等待 GPS 定位..."
                            else "GPS 设备未连接",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (gpsData.isConnected) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "卫星: ${gpsData.satelliteCount} | HDOP: ${String.format("%.1f", gpsData.hdop)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // 地图生命周期
    DisposableEffect(mapView) {
        mapView?.onResume()
        onDispose {
            mapView?.onPause()
        }
    }
}
