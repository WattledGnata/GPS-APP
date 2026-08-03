package com.blazepush.core.domain.model

/** Stable presentation levels. These are derived from evidence and are never raw telemetry. */
enum class LapConfidence { Clean, Reviewed, Estimated, Incomplete }

/** Why a reviewed decision exists. Absence of manual review is not itself a quality defect. */
enum class LapReviewProvenance { AutomaticEvidence, ManualApproved, ManualRejected, LegacyUnknown }

enum class LapEvidenceFlag {
    LowAccuracy,
    SparseSamples,
    SuspectedJitter,
    MissingRequiredGate,
    CrossGapInterpolation,
}

/** A silent Main-frame interval. Gate impact is evidence; it does not create a crossing. */
data class LapGapInterval(
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val affectedGateIds: Set<String> = emptySet(),
)

/**
 * Minimal immutable inputs needed to recompute lap confidence. Crossing timestamps reference the
 * existing crossing truth source; gaps reference the existing telemetry rather than copying it.
 */
data class LapEvidence(
    val version: Int = CURRENT_VERSION,
    val startCrossingTimestampMillis: Long,
    val finishCrossingTimestampMillis: Long,
    val requiredGateIds: Set<String>,
    val acceptedGateIds: Set<String>,
    val gaps: List<LapGapInterval> = emptyList(),
    val flags: Set<LapEvidenceFlag> = emptySet(),
    val reviewProvenance: LapReviewProvenance = LapReviewProvenance.AutomaticEvidence,
) {
    companion object { const val CURRENT_VERSION = 1 }
}

data class LapEligibility(
    val personalBest: Boolean,
    val voiceAnnouncement: Boolean,
    val comparison: Boolean,
    val upload: Boolean,
)

data class LapQualityDecision(
    val confidence: LapConfidence,
    val provenance: LapReviewProvenance,
    val flags: Set<LapEvidenceFlag>,
    val eligibility: LapEligibility,
)

/** Single policy consumed by PB, voice, comparison, detail and upload. */
object LapConfidencePolicy {
    fun evaluate(evidence: LapEvidence?): LapQualityDecision {
        if (evidence == null) {
            return LapQualityDecision(
                confidence = LapConfidence.Reviewed,
                provenance = LapReviewProvenance.LegacyUnknown,
                flags = emptySet(),
                eligibility = LapEligibility(
                    personalBest = false,
                    voiceAnnouncement = false,
                    comparison = true,
                    upload = false,
                ),
            )
        }

        val missingGate = evidence.flags.contains(LapEvidenceFlag.MissingRequiredGate) ||
            !evidence.acceptedGateIds.containsAll(evidence.requiredGateIds)
        val impactedRequiredGate = evidence.gaps.any { gap ->
            gap.affectedGateIds.any(evidence.requiredGateIds::contains)
        }
        val crossGapInterpolation = LapEvidenceFlag.CrossGapInterpolation in evidence.flags
        val confidence = when {
            evidence.reviewProvenance == LapReviewProvenance.ManualRejected -> LapConfidence.Incomplete
            missingGate -> LapConfidence.Incomplete
            impactedRequiredGate || crossGapInterpolation -> LapConfidence.Estimated
            evidence.flags.isNotEmpty() -> LapConfidence.Reviewed
            evidence.reviewProvenance == LapReviewProvenance.ManualApproved -> LapConfidence.Reviewed
            else -> LapConfidence.Clean
        }
        val reviewedApproved = confidence == LapConfidence.Reviewed &&
            evidence.reviewProvenance == LapReviewProvenance.ManualApproved
        val eligibility = when (confidence) {
            LapConfidence.Clean -> LapEligibility(true, true, true, true)
            LapConfidence.Reviewed -> LapEligibility(
                personalBest = reviewedApproved,
                voiceAnnouncement = reviewedApproved,
                comparison = true,
                upload = false,
            )
            LapConfidence.Estimated -> LapEligibility(false, false, true, false)
            LapConfidence.Incomplete -> LapEligibility(false, false, false, false)
        }
        return LapQualityDecision(confidence, evidence.reviewProvenance, evidence.flags, eligibility)
    }
}
