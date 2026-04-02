package com.blazepush.simulator.data.replay

private const val SESSION_TITLE_PREFIX = "Session title,"
private const val CSV_HEADER_PREFIX = "timestamp,"
private const val CSV_METADATA_LINE_COUNT = 3
private val GATE_LINE_PREFIXES = listOf("Start", "Split")
private val VBO_DATA_LINE_REGEX = Regex("^\\d{3}\\s+\\d{6}\\.\\d{2}.*")
private val VBO_SEPARATOR_REGEX = Regex("\\s+")

class RaceChronoReplayParser {

    fun parseCsv(csv: String): ReplaySession {
        val lines = csv.lineSequence().map { it.trim() }.toList()
        val sessionTitle = lines.firstOrNull { it.startsWith(SESSION_TITLE_PREFIX) }
            ?.substringAfter(SESSION_TITLE_PREFIX)
            ?.trim()
            ?.removeSurrounding("\"")
            .orEmpty()

        val headerIndex = lines.indexOfFirst { it.startsWith(CSV_HEADER_PREFIX) }
        require(headerIndex >= 0) { "CSV header not found" }

        val samples = lines.drop(headerIndex + CSV_METADATA_LINE_COUNT)
            .filter { it.isNotBlank() }
            .map(::parseCsvRow)

        return ReplaySession(
            sessionTitle = sessionTitle,
            samples = samples
        )
    }

    fun parseVboGates(vbo: String, referenceSample: ReplaySample? = null): List<ReplayGate> {
        val lines = vbo.lineSequence().map { it.trim() }.toList()
        val normalizer = buildVboNormalizer(lines, referenceSample)
        return lines
            .filter(::isGateLine)
            .map { parseGate(it, normalizer) }
    }

    private fun parseCsvRow(line: String): ReplaySample {
        val parts = line.split(',')
        return ReplaySample(
            timestampMillis = (parts[0].toDouble() * 1000).toLong(),
            latitude = parts[11].toDouble(),
            longitude = parts[12].toDouble(),
            speedKmh = parts[14].toDouble() * 3.6,
            bearingDegrees = parts[7].toDouble(),
            satellites = parts[13].toDouble().toInt(),
            fixType = parts[10].toDouble().toInt(),
            hdop = parts[8].toDouble(),
            altitudeMeters = parts[5].toDouble(),
            altitudePrecisionMeters = parts[6].toDouble()
        )
    }

    private fun parseGate(line: String, normalizer: VboCoordinateNormalizer?): ReplayGate {
        val parts = line.split(VBO_SEPARATOR_REGEX)
        val type = if (parts[0] == "Start") RaceChronoGateType.StartFinish else RaceChronoGateType.Split
        val name = line.substringAfter('¬').trim()
        return ReplayGate(
            type = type,
            name = name,
            line = ReplayGateLine(
                start = ReplayGeoPoint(
                    latitude = parseVboCoordinate(parts[2], normalizer?.latitudeOffset),
                    longitude = parseVboCoordinate(parts[1], normalizer?.longitudeOffset)
                ),
                end = ReplayGeoPoint(
                    latitude = parseVboCoordinate(parts[4], normalizer?.latitudeOffset),
                    longitude = parseVboCoordinate(parts[3], normalizer?.longitudeOffset)
                )
            )
        )
    }

    private fun buildVboNormalizer(lines: List<String>, referenceSample: ReplaySample?): VboCoordinateNormalizer? {
        if (referenceSample == null) return null
        val dataLine = findNearestVboDataLine(lines, referenceSample) ?: return null
        val parts = dataLine.split(VBO_SEPARATOR_REGEX)
        val rawLatitude = parts[2].toDouble()
        val rawLongitude = parts[3].toDouble()
        return VboCoordinateNormalizer(
            latitudeOffset = toNmea(referenceSample.latitude) - rawLatitude,
            longitudeOffset = toNmea(referenceSample.longitude) - rawLongitude
        )
    }

    private fun findNearestVboDataLine(lines: List<String>, referenceSample: ReplaySample): String? {
        val targetSeconds = ((referenceSample.timestampMillis / 1000) % (24 * 60 * 60)) + ((referenceSample.timestampMillis % 1000) / 1000.0)
        return lines
            .asSequence()
            .filter { it.matches(VBO_DATA_LINE_REGEX) }
            .minByOrNull { line ->
                val timeToken = line.split(VBO_SEPARATOR_REGEX)[1]
                kotlin.math.abs(parseRaceChronoTimeSeconds(timeToken) - targetSeconds)
            }
    }

    private fun isGateLine(line: String): Boolean {
        return GATE_LINE_PREFIXES.any(line::startsWith)
    }

    private fun parseRaceChronoTimeSeconds(value: String): Double {
        val hours = value.substring(0, 2).toInt()
        val minutes = value.substring(2, 4).toInt()
        val seconds = value.substring(4).toDouble()
        return (hours * 3600) + (minutes * 60) + seconds
    }

    private fun parseVboCoordinate(raw: String, offset: Double?): Double {
        val normalized = raw.toDouble() + (offset ?: 0.0)
        val absolute = kotlin.math.abs(normalized)
        val degrees = (absolute / 100).toInt()
        val minutes = absolute - (degrees * 100)
        val decimal = degrees + minutes / 60.0
        return if (normalized < 0) -decimal else decimal
    }

    private fun toNmea(decimalDegrees: Double): Double {
        val absolute = kotlin.math.abs(decimalDegrees)
        val degrees = absolute.toInt()
        val minutes = (absolute - degrees) * 60.0
        val value = (degrees * 100) + minutes
        return if (decimalDegrees < 0) -value else value
    }
}

private data class VboCoordinateNormalizer(
    val latitudeOffset: Double,
    val longitudeOffset: Double
)
