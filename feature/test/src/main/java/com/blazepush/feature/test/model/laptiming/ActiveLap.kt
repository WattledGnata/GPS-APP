package com.blazepush.feature.test.model.laptiming

data class ActiveLap(
    val lapIndex: Int,
    val startedAtMillis: Long,
    val passedGateIds: List<String> = emptyList(),
    val sectorEntries: List<SectorEntry> = emptyList(),
    val sampleStartIndex: Int
)
