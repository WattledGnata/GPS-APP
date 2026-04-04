package com.blazepush.core.domain.permission

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionRequestOutcomeTest {

    @Test
    fun `returns all granted when every permission is granted`() {
        val outcome = PermissionRequestOutcome.from(
            permissions = listOf("p1", "p2"),
            result = mapOf("p1" to true, "p2" to true)
        )

        assertEquals(PermissionRequestOutcome.AllGranted, outcome)
    }

    @Test
    fun `returns missing permissions instead of terminal failure when some permissions are denied`() {
        val outcome = PermissionRequestOutcome.from(
            permissions = listOf("p1", "p2", "p3"),
            result = mapOf("p1" to true, "p2" to false, "p3" to false)
        )

        assertEquals(
            PermissionRequestOutcome.MissingPermissions(listOf("p2", "p3")),
            outcome
        )
    }

    @Test
    fun `treats absent permissions in callback as still missing`() {
        val outcome = PermissionRequestOutcome.from(
            permissions = listOf("p1", "p2"),
            result = mapOf("p1" to true)
        )

        assertEquals(
            PermissionRequestOutcome.MissingPermissions(listOf("p2")),
            outcome
        )
    }
}
