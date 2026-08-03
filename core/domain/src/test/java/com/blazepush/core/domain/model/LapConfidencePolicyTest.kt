package com.blazepush.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LapConfidencePolicyTest {
    private fun evidence(
        gaps: List<LapGapInterval> = emptyList(),
        accepted: Set<String> = setOf("SF", "S1"),
        flags: Set<LapEvidenceFlag> = emptySet(),
        provenance: LapReviewProvenance = LapReviewProvenance.AutomaticEvidence,
    ) = LapEvidence(
        startCrossingTimestampMillis = 1_000,
        finishCrossingTimestampMillis = 2_000,
        requiredGateIds = setOf("SF", "S1"),
        acceptedGateIds = accepted,
        gaps = gaps,
        flags = flags,
        reviewProvenance = provenance,
    )

    @Test fun `unrelated gap remains clean and fully eligible`() {
        val result = LapConfidencePolicy.evaluate(evidence(gaps = listOf(LapGapInterval(1200, 1300))))
        assertEquals(LapConfidence.Clean, result.confidence)
        assertTrue(result.eligibility.personalBest)
        assertTrue(result.eligibility.voiceAnnouncement)
    }

    @Test fun `gap affecting required sector is estimated and not unconditional best`() {
        val result = LapConfidencePolicy.evaluate(
            evidence(gaps = listOf(LapGapInterval(1200, 1500, setOf("S1"))))
        )
        assertEquals(LapConfidence.Estimated, result.confidence)
        assertFalse(result.eligibility.personalBest)
        assertTrue(result.eligibility.comparison)
        assertFalse(result.eligibility.upload)
    }

    @Test fun `missing required gate is incomplete`() {
        val result = LapConfidencePolicy.evaluate(evidence(accepted = setOf("SF")))
        assertEquals(LapConfidence.Incomplete, result.confidence)
        assertFalse(result.eligibility.comparison)
        assertFalse(result.eligibility.upload)
    }

    @Test fun `legacy absence is reviewed unknown and safely ineligible`() {
        val result = LapConfidencePolicy.evaluate(null)
        assertEquals(LapConfidence.Reviewed, result.confidence)
        assertEquals(LapReviewProvenance.LegacyUnknown, result.provenance)
        assertFalse(result.eligibility.personalBest)
        assertFalse(result.eligibility.voiceAnnouncement)
        assertFalse(result.eligibility.upload)
    }

    @Test fun `reviewed PB and voice depend on explicit provenance`() {
        val automatic = LapConfidencePolicy.evaluate(
            evidence(flags = setOf(LapEvidenceFlag.LowAccuracy))
        )
        val approved = LapConfidencePolicy.evaluate(
            evidence(
                flags = setOf(LapEvidenceFlag.LowAccuracy),
                provenance = LapReviewProvenance.ManualApproved,
            )
        )
        assertFalse(automatic.eligibility.personalBest)
        assertTrue(approved.eligibility.personalBest)
        assertTrue(approved.eligibility.voiceAnnouncement)
        assertFalse("server cannot persist quality yet, so Reviewed upload fails closed", approved.eligibility.upload)
    }

    @Test fun `only clean confidence may upload`() {
        val clean = LapConfidencePolicy.evaluate(evidence())
        val reviewed = LapConfidencePolicy.evaluate(evidence(flags = setOf(LapEvidenceFlag.SparseSamples)))
        val estimated = LapConfidencePolicy.evaluate(
            evidence(gaps = listOf(LapGapInterval(1200, 1300, setOf("SF"))))
        )
        val incomplete = LapConfidencePolicy.evaluate(evidence(accepted = emptySet()))
        assertTrue(clean.eligibility.upload)
        assertFalse(reviewed.eligibility.upload)
        assertFalse(estimated.eligibility.upload)
        assertFalse(incomplete.eligibility.upload)
    }
}
