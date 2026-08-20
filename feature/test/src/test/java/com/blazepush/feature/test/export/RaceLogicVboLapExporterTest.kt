package com.blazepush.feature.test.export

import com.blazepush.core.domain.model.LapConfidence
import com.blazepush.core.domain.model.LapEvidence
import com.blazepush.core.domain.model.LapEvidenceFlag
import com.blazepush.core.domain.model.LapReviewProvenance
import com.blazepush.core.domain.model.LapTelemetry
import com.blazepush.core.domain.model.LapTelemetrySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RaceLogicVboLapExporterTest {
    @Test
    fun `minimal header contains only real fields and round trips rows in source order`() {
        val first = sample("2026-08-20T12:34:59.994Z", -33.865143, -151.209900, 123.4567, 278.129)
        val second = sample("2026-08-20T12:35:00.006Z", 30.491240, 104.433236, 98.7654, 1.999)

        val document = ready(telemetry(samples = listOf(first, second)))

        assertTrue(document.text.contains("[header]\ntime\nlatitude\nlongitude\nvelocity kmh\nheading"))
        assertTrue(document.text.contains("[column names]\ntime lat long velocity heading"))
        assertFalse(document.text.contains("satellites"))
        assertFalse(document.text.contains("height\n"))
        assertFalse(document.text.contains("accel"))
        val rows = RaceLogicVboTelemetryParser.parse(document.text)
        assertEquals(2, rows.size)
        assertEquals(45_299_990L, rows[0].utcMillisOfDay)
        assertEquals(-33.865143, rows[0].latitudeDegrees, 0.00000001)
        assertEquals(-151.209900, rows[0].longitudeDegrees, 0.00000001)
        assertEquals(123.457, rows[0].speedKmh, 0.0001)
        assertEquals(278.13, rows[0].headingDegrees!!, 0.001)
        assertEquals(45_300_010L, rows[1].utcMillisOfDay)
        assertEquals(30.491240, rows[1].latitudeDegrees, 0.00000001)
    }

    @Test
    fun `UTC time rounds across minute and midnight`() {
        val document = ready(
            telemetry(
                samples = listOf(
                    sample("2026-08-20T23:59:59.996Z", 1.0, 1.0, 1.0, 1.0),
                    sample("2026-08-21T00:00:00.014Z", 1.1, 1.1, 2.0, 2.0),
                ),
            ),
        )
        val rows = RaceLogicVboTelemetryParser.parse(document.text)
        assertEquals(0L, rows[0].utcMillisOfDay)
        assertEquals(10L, rows[1].utcMillisOfDay)
    }

    @Test
    fun `invalid boundary start uses first actual exportable sample for file header time`() {
        val sample = sample("2026-08-20T07:08:09.123Z", 30.0, 104.0, 80.0, 90.0)
        val document = ready(telemetry(samples = listOf(sample)).copy(lapStartWallClock = -1L))

        assertTrue(document.text.startsWith("File created on 20/08/2026 at 07:08:09 UTC"))
        assertTrue(document.text.contains("File time source: first exported sample"))
        assertEquals(RaceLogicVboLapExporter.FileTimeSource.FIRST_EXPORTED_SAMPLE, document.fileTimeSource)
        assertFalse(document.text.contains("1970"))
    }

    @Test
    fun `missing heading selects honest smaller column set`() {
        val document = ready(
            telemetry(
                samples = listOf(
                    sample("2026-08-20T12:00:00Z", 30.0, 104.0, 80.0, 90.0),
                    sample("2026-08-20T12:00:01Z", 30.1, 104.1, 81.0, null),
                ),
            ),
        )
        assertFalse(document.headingIncluded)
        assertTrue(document.text.contains("[column names]\ntime lat long velocity\n"))
        assertTrue(RaceLogicVboTelemetryParser.parse(document.text).all { it.headingDegrees == null })
    }

    @Test
    fun `empty and entirely invalid coordinates reject while mixed data reports omission`() {
        val empty = RaceLogicVboLapExporter.export(telemetry(samples = emptyList()), metadata())
        assertEquals(
            RaceLogicVboLapExporter.Rejection.EMPTY_SAMPLES,
            (empty as RaceLogicVboLapExporter.Result.Rejected).reason,
        )

        val invalid = sample("2026-08-20T12:00:00Z", 91.0, 104.0, 80.0, 90.0)
        val noPosition = RaceLogicVboLapExporter.export(telemetry(samples = listOf(invalid)), metadata())
        assertEquals(
            RaceLogicVboLapExporter.Rejection.NO_VALID_POSITION_SAMPLES,
            (noPosition as RaceLogicVboLapExporter.Result.Rejected).reason,
        )

        val invalidSpeed = sample("2026-08-20T12:00:00Z", 30.0, 104.0, Double.NaN, 90.0)
        val noRequiredFields = RaceLogicVboLapExporter.export(telemetry(samples = listOf(invalidSpeed)), metadata())
        assertEquals(
            RaceLogicVboLapExporter.Rejection.NO_VALID_REQUIRED_FIELDS,
            (noRequiredFields as RaceLogicVboLapExporter.Result.Rejected).reason,
        )

        val invalidTime = sample("1970-01-01T00:00:00Z", 30.0, 104.0, 80.0, 90.0)
        val noReliableTime = RaceLogicVboLapExporter.export(telemetry(samples = listOf(invalidTime)), metadata())
        assertEquals(
            RaceLogicVboLapExporter.Rejection.NO_VALID_REQUIRED_FIELDS,
            (noReliableTime as RaceLogicVboLapExporter.Result.Rejected).reason,
        )

        val valid = sample("2026-08-20T12:00:01Z", 30.0, 104.0, 80.0, 90.0)
        val mixed = ready(telemetry(samples = listOf(invalid, valid)))
        assertEquals(1, mixed.exportedSampleCount)
        assertEquals(1, mixed.omittedSampleCount)
        assertEquals(30.0, RaceLogicVboTelemetryParser.parse(mixed.text).single().latitudeDegrees, 0.0)
    }

    @Test
    fun `incomplete boundary and estimated confidence remain exportable and truthful`() {
        val telemetry = telemetry(
            samples = listOf(sample("2026-08-20T12:00:00Z", 30.0, 104.0, 80.0, 90.0)),
        ).copy(lapEndWallClock = 1L, lapDurationMs = 999L)
        val document = ready(
            telemetry,
            RaceLogicVboLapExporter.Metadata(LapConfidence.Estimated, LapReviewProvenance.AutomaticEvidence),
        )
        assertFalse(document.boundaryComplete)
        assertTrue(document.text.contains("Lap confidence: Estimated"))
        assertTrue(document.text.contains("Lap boundary: incomplete"))
    }

    @Test
    fun `filename is legal stable and identifies track session and lap`() {
        val first = RaceLogicVboLapExporter.buildFileName("天府/赛道:*?", "session:abc/123", 4)
        val second = RaceLogicVboLapExporter.buildFileName("天府/赛道:*?", "session:abc/123", 4)
        assertEquals(first, second)
        assertEquals("天府-赛道_session-abc-123_lap-04.vbo", first)
        assertFalse(Regex("[\\\\/:*?\"<>|]").containsMatchIn(first))

        val long = RaceLogicVboLapExporter.buildFileName("赛道".repeat(100), "s".repeat(100), 1)
        assertTrue(long.length <= 48 + 1 + 48 + "_lap-01.vbo".length)
        assertEquals(long, RaceLogicVboLapExporter.buildFileName("赛道".repeat(100), "s".repeat(100), 1))
    }

    @Test
    fun `export preparation blocks while evidence loads and allows truthful low confidence`() {
        val telemetry = telemetry(
            samples = listOf(sample("2026-08-20T12:00:00Z", 30.0, 104.0, 80.0, 90.0)),
        )
        assertEquals(
            LapVboExportPreparation.Loading,
            LapVboExportPreparation.resolve(telemetry, evidence = null, evidenceLoaded = false),
        )

        val estimatedEvidence = evidence(flags = setOf(LapEvidenceFlag.CrossGapInterpolation))
        val estimated = LapVboExportPreparation.resolve(telemetry, estimatedEvidence, evidenceLoaded = true)
            as LapVboExportPreparation.Ready
        assertEquals(LapConfidence.Estimated, estimated.metadata.confidence)

        val incompleteEvidence = evidence(flags = setOf(LapEvidenceFlag.MissingRequiredGate))
        val incomplete = LapVboExportPreparation.resolve(telemetry, incompleteEvidence, evidenceLoaded = true)
            as LapVboExportPreparation.Ready
        assertEquals(LapConfidence.Incomplete, incomplete.metadata.confidence)

        val loadedLegacy = LapVboExportPreparation.resolve(telemetry, evidence = null, evidenceLoaded = true)
        assertTrue(loadedLegacy is LapVboExportPreparation.Ready)
    }

    private fun ready(
        telemetry: LapTelemetry,
        metadata: RaceLogicVboLapExporter.Metadata = metadata(),
    ): RaceLogicVboLapExporter.Document =
        (RaceLogicVboLapExporter.export(telemetry, metadata) as RaceLogicVboLapExporter.Result.Ready).document

    private fun metadata() = RaceLogicVboLapExporter.Metadata(
        LapConfidence.Clean,
        LapReviewProvenance.AutomaticEvidence,
    )

    private fun evidence(flags: Set<LapEvidenceFlag>) = LapEvidence(
        startCrossingTimestampMillis = 1L,
        finishCrossingTimestampMillis = 2L,
        requiredGateIds = setOf("start", "finish"),
        acceptedGateIds = if (LapEvidenceFlag.MissingRequiredGate in flags) setOf("start") else setOf("start", "finish"),
        flags = flags,
    )

    private fun telemetry(samples: List<LapTelemetrySample>) = LapTelemetry(
        sessionId = "session-abc",
        lapIndex = 2,
        lapStartWallClock = Instant.parse("2026-08-20T12:00:00Z").toEpochMilli(),
        lapEndWallClock = Instant.parse("2026-08-20T12:01:00Z").toEpochMilli(),
        lapDurationMs = 60_000L,
        samples = samples,
        sectorBoundaries = emptyList(),
        trackId = "track-id",
        trackNameSnapshot = "天府赛道",
    )

    private fun sample(
        instant: String,
        lat: Double,
        lon: Double,
        speed: Double,
        heading: Double?,
    ) = LapTelemetrySample(
        absoluteTsMs = Instant.parse(instant).toEpochMilli(),
        elapsedMsInLap = 0L,
        lat = lat,
        lon = lon,
        speedKmh = speed,
        bearingDeg = heading,
    )
}
