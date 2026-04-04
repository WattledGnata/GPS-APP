package com.blazepush.core.domain.permission

sealed class PermissionRequestOutcome {
    data object AllGranted : PermissionRequestOutcome()
    data class MissingPermissions(val permissions: List<String>) : PermissionRequestOutcome()

    companion object {
        fun from(
            permissions: List<String>,
            result: Map<String, Boolean>
        ): PermissionRequestOutcome {
            val missingPermissions = permissions.filter { result[it] != true }
            return if (missingPermissions.isEmpty()) {
                AllGranted
            } else {
                MissingPermissions(missingPermissions)
            }
        }
    }
}
