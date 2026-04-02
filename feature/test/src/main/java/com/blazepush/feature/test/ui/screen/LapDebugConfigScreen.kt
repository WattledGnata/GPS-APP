package com.blazepush.feature.test.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blazepush.feature.test.model.LapRunConfig
import com.blazepush.feature.test.model.LapViewOptions
import com.blazepush.feature.test.model.track.Track

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LapDebugConfigScreen(
    availableTracks: List<Track>,
    initialConfig: LapRunConfig?,
    onConfirm: (LapRunConfig) -> Unit,
    onBack: () -> Unit
) {
    val validInitialTrackId = initialConfig
        ?.trackId
        ?.takeIf { trackId -> availableTracks.any { it.id == trackId } }
    var selectedTrackId by remember(validInitialTrackId) { mutableStateOf(validInitialTrackId) }

    val initialViewOptions = initialConfig?.viewOptions ?: LapViewOptions()
    var showReferencePath by remember { mutableStateOf(initialViewOptions.showReferencePath) }
    var showTimingGates by remember { mutableStateOf(initialViewOptions.showTimingGates) }
    var showTrajectory by remember { mutableStateOf(initialViewOptions.showTrajectory) }

    LaunchedEffect(availableTracks, selectedTrackId) {
        if (selectedTrackId == null || availableTracks.none { it.id == selectedTrackId }) {
            selectedTrackId = availableTracks.firstOrNull()?.id
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("圈速调试配置") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "选择赛道",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )

            if (availableTracks.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text(
                        text = "暂无可用赛道",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(availableTracks, key = { it.id }) { track ->
                        TrackRow(
                            track = track,
                            selected = selectedTrackId == track.id,
                            onSelected = { selectedTrackId = track.id }
                        )
                    }
                }
            }

            Text(
                text = "视图选项",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )

            OptionToggle(
                title = "显示参考线路",
                checked = showReferencePath,
                onCheckedChange = { showReferencePath = it }
            )
            OptionToggle(
                title = "显示计时门",
                checked = showTimingGates,
                onCheckedChange = { showTimingGates = it }
            )
            OptionToggle(
                title = "显示轨迹",
                checked = showTrajectory,
                onCheckedChange = { showTrajectory = it }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("返回")
                }

                Button(
                    onClick = {
                        val trackId = selectedTrackId ?: return@Button
                        onConfirm(
                            LapRunConfig(
                                trackId = trackId,
                                viewOptions = LapViewOptions(
                                    showReferencePath = showReferencePath,
                                    showTimingGates = showTimingGates,
                                    showTrajectory = showTrajectory,
                                    showCrossingDebug = initialViewOptions.showCrossingDebug
                                )
                            )
                        )
                    },
                    enabled = selectedTrackId != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("确认")
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    selected: Boolean,
    onSelected: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onSelected,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        border = if (selected) CardDefaults.outlinedCardBorder() else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RadioButton(
                selected = selected,
                onClick = onSelected
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(track.name, fontWeight = FontWeight.Bold)
                track.layoutName?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun OptionToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}
