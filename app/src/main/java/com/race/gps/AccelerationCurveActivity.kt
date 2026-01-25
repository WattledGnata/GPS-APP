package com.race.gps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.race.gps.data.model.AccelerationDataPoint
import com.race.gps.data.model.TestRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AccelerationCurveActivity : ComponentActivity() {
    companion object {
        const val TAG = "AccelerationCurveActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get the test record from intent
        val testRecord = intent.getSerializableExtra("test_record") as? TestRecord

        if (testRecord == null) {
            finish()
            return
        }

        setContent {
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
                AccelerationCurveScreen(testRecord = testRecord)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccelerationCurveScreen(
    testRecord: TestRecord
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = "加速曲线")
                },
                navigationIcon = {
                    IconButton(onClick = { /* Handle back button */ }) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) {
        Box(modifier = Modifier.padding(it)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Test information
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(4.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = testRecord.testType,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = testRecord.carModel,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text(
                            text = "成绩: ${testRecord.result}",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text(
                            text = "测试时间: ${formatDate(testRecord.timestamp)}",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text(
                            text = "数据点数量: ${testRecord.accelerationData.size}",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Acceleration curve
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    elevation = CardDefaults.cardElevation(4.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (testRecord.accelerationData.isEmpty()) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(text = "暂无加速度数据")
                            }
                        } else {
                            AccelerationCurve(testRecord.accelerationData)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AccelerationCurve(
    dataPoints: List<AccelerationDataPoint>
) {
    // Calculate min/max values for scaling
    val maxTime = dataPoints.maxOfOrNull { it.time } ?: 10.0
    val maxSpeed = dataPoints.maxOfOrNull { it.speed } ?: 100.0
    val minSpeed = dataPoints.minOfOrNull { it.speed } ?: 0.0

    Canvas(modifier = Modifier.fillMaxSize()) {
        val padding = 40.dp.toPx()
        val graphWidth = size.width - 2 * padding
        val graphHeight = size.height - 2 * padding

        // Draw background grid
        drawRect(
            color = Color.LightGray.copy(alpha = 0.2f),
            size = Size(width = graphWidth, height = graphHeight),
            topLeft = Offset(x = padding, y = padding)
        )

        // Draw axes
        drawLine(
            color = Color.Black,
            start = Offset(x = padding, y = padding),
            end = Offset(x = padding, y = padding + graphHeight),
            strokeWidth = 2f
        )
        drawLine(
            color = Color.Black,
            start = Offset(x = padding, y = padding + graphHeight),
            end = Offset(x = padding + graphWidth, y = padding + graphHeight),
            strokeWidth = 2f
        )

        // Draw grid lines
        val gridLines = 5
        for (i in 0..gridLines) {
            // Vertical lines
            val x = padding + (i.toFloat() / gridLines) * graphWidth
            drawLine(
                color = Color.Gray.copy(alpha = 0.5f),
                start = Offset(x = x, y = padding),
                end = Offset(x = x, y = padding + graphHeight),
                strokeWidth = 1f
            )

            // Horizontal lines
            val y = padding + graphHeight - (i.toFloat() / gridLines) * graphHeight
            drawLine(
                color = Color.Gray.copy(alpha = 0.5f),
                start = Offset(x = padding, y = y),
                end = Offset(x = padding + graphWidth, y = y),
                strokeWidth = 1f
            )
        }

        // Draw data points and line
        val path = Path()
        dataPoints.forEachIndexed { index, point ->
            val x = (padding + (point.time / maxTime) * graphWidth).toFloat()
            val y = (padding + graphHeight - ((point.speed - minSpeed) / (maxSpeed - minSpeed)) * graphHeight).toFloat()

            // Draw data point
            drawCircle(
                color = Color.Red,
                radius = 4f,
                center = Offset(x = x, y = y)
            )

            // Draw line
            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        // Draw path
        drawPath(
            path = path,
            color = Color.Blue,
            style = Stroke(
                width = 3f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        // Draw axes labels
        drawContext.canvas.nativeCanvas.drawText(
            "时间 (s)",
            padding + graphWidth / 2,
            padding + graphHeight + 20.dp.toPx(),
            android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 14.sp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
            }
        )

        drawContext.canvas.nativeCanvas.rotate(
            -90f,
            padding - 20.dp.toPx(),
            padding + graphHeight / 2
        )
        drawContext.canvas.nativeCanvas.drawText(
            "速度 (km/h)",
            padding - 20.dp.toPx(),
            padding + graphHeight / 2,
            android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 14.sp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
            }
        )
    }
}

// Helper function to format date
fun formatDate(date: Date): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(date)
}