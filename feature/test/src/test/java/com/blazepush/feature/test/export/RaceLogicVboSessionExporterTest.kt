package com.blazepush.feature.test.export

import com.blazepush.core.data.repository.TelemetryRepository
import com.blazepush.core.domain.model.TelemetryCrossingEvent
import com.blazepush.core.domain.model.TelemetrySample
import com.blazepush.core.domain.model.TelemetrySession
import com.blazepush.core.domain.model.TelemetrySessionType
import com.blazepush.feature.test.model.track.GeoLine
import com.blazepush.feature.test.model.track.GeoPoint
import com.blazepush.feature.test.model.track.GeoVector
import com.blazepush.feature.test.model.track.TimingGate
import com.blazepush.feature.test.model.track.TimingGateType
import com.blazepush.feature.test.model.track.Track
import com.blazepush.feature.test.model.track.TrackName
import com.blazepush.feature.test.model.track.TrackPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter
import java.time.Instant

class RaceLogicVboSessionExporterTest {
    private val startTs = Instant.parse("2026-08-24T09:00:00Z").toEpochMilli()

    @Test
    fun `whole session writes samples outside lap windows and ordered laptiming gates`() {
        val samples = listOf(sample(0), sample(1_000), sample(2_000), sample(3_000))
        val snapshot = snapshot(
            samples = samples,
            crossings = listOf(crossing(1_000), crossing(2_000)),
        )
        val output = StringWriter()

        val ready = RaceLogicVboSessionExporter.write(snapshot, track(), output)
            as RaceLogicVboSessionExporter.Result.Ready
        val text = output.toString()

        assertEquals(4, ready.document.exportedSampleCount)
        assertEquals(1, ready.document.completeLapCount)
        assertTrue(ready.document.laptimingIncluded)
        assertTrue(text.contains("Complete laps: 1"))
        assertTrue(text.contains("Start   -6266.00000000 +1830.00000000"))
        assertTrue(text.indexOf("¬ CP1") < text.indexOf("¬ CP2"))
        assertEquals(4, RaceLogicVboTelemetryParser.parse(text).size)
        assertTrue(text.contains("090000.00"))
        assertTrue(text.contains("090003.00"))
    }

    @Test
    fun `no complete lap and no track remain shareable with truthful degradation`() {
        val output = StringWriter()
        val ready = RaceLogicVboSessionExporter.write(snapshot(samples = listOf(sample(0))), null, output)
            as RaceLogicVboSessionExporter.Result.Ready

        assertEquals(0, ready.document.completeLapCount)
        assertFalse(ready.document.laptimingIncluded)
        assertTrue(output.toString().contains("Lap summary: no complete start-finish pair"))
        assertTrue(output.toString().contains("Laptiming omitted: track geometry unavailable"))
        assertFalse(output.toString().contains("[laptiming]"))
    }

    @Test
    fun `invalid samples are omitted and missing heading removes the column`() {
        val output = StringWriter()
        val ready = RaceLogicVboSessionExporter.write(
            snapshot(
                samples = listOf(
                    sample(0),
                    sample(40).copy(lat = 91.0),
                    sample(80).copy(bearingDeg = null),
                ),
            ),
            track(),
            output,
        ) as RaceLogicVboSessionExporter.Result.Ready

        assertEquals(2, ready.document.exportedSampleCount)
        assertEquals(1, ready.document.omittedSampleCount)
        assertFalse(ready.document.headingIncluded)
        assertTrue(output.toString().contains("Omitted invalid samples: 1"))
        assertTrue(output.toString().contains("[column names]\ntime lat long velocity\n"))
    }

    @Test
    fun `empty and entirely invalid sessions reject without writing a document`() {
        val emptyWriter = StringWriter()
        val empty = RaceLogicVboSessionExporter.write(snapshot(samples = emptyList()), track(), emptyWriter)
        assertEquals(
            RaceLogicVboSessionExporter.Rejection.EMPTY_SAMPLES,
            (empty as RaceLogicVboSessionExporter.Result.Rejected).reason,
        )
        assertEquals("", emptyWriter.toString())

        val invalid = RaceLogicVboSessionExporter.write(
            snapshot(samples = listOf(sample(0).copy(speedKmh = Double.NaN))),
            track(),
            StringWriter(),
        )
        assertEquals(
            RaceLogicVboSessionExporter.Rejection.NO_VALID_REQUIRED_FIELDS,
            (invalid as RaceLogicVboSessionExporter.Result.Rejected).reason,
        )
    }

    private fun snapshot(
        samples: List<TelemetrySample>,
        crossings: List<TelemetryCrossingEvent> = emptyList(),
    ) = TelemetryRepository.LapSessionExportSnapshot(
        session = TelemetrySession(
            sessionId = "session-1",
            sessionType = TelemetrySessionType.LAP_SESSION,
            startTs = startTs,
            endTs = startTs + 10_000,
            binaryFilePath = "/private/session.bin",
            trackId = "track-1",
            trackNameSnapshot = "成都天府国际赛道",
        ),
        samples = samples,
        crossings = crossings,
        evidenceByLap = emptyMap(),
    )

    private fun sample(deltaMs: Long) = TelemetrySample(
        tsDeltaMs = deltaMs,
        lat = 30.5,
        lon = 104.43333333333334,
        speedKmh = 100.0 + deltaMs / 1_000.0,
        bearingDeg = 180.0,
    )

    private fun crossing(deltaMs: Long) = TelemetryCrossingEvent(
        sessionId = "session-1",
        lapIndex = 0,
        crossingTimestampMs = deltaMs,
        crossingWallClockTimestampMs = startTs + deltaMs,
        speedKmh = 100.0,
        gateId = "SF",
        gateType = "StartFinish",
        accepted = true,
        reason = "",
        directionScore = 1.0,
    )

    private fun track(): Track {
        fun gate(name: String, type: TimingGateType, sequence: Int, lat: Double, lon: Double) = TimingGate(
            id = name,
            name = name,
            type = type,
            line = GeoLine(GeoPoint(lat, lon), GeoPoint(lat + 0.0001, lon + 0.0001)),
            passDirection = GeoVector(1.0, 0.0),
            sequenceIndex = sequence,
        )
        return Track(
            id = "track-1",
            name = TrackName("成都天府国际赛道", "Chengdu Tianfu"),
            lengthKm = 3.2,
            referencePath = TrackPath(emptyList()),
            startFinishGate = gate("起点", TimingGateType.StartFinish, 0, 30.5, 104.43333333333334),
            sectorGates = listOf(
                gate("CP2", TimingGateType.Sector, 2, 30.52, 104.45),
                gate("CP1", TimingGateType.Sector, 1, 30.51, 104.44),
            ),
        )
    }
}
