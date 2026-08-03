package com.blazepush.feature.test.model.laptiming

/**
 * GPS readiness for an already-created lap session.
 *
 * Session existence is deliberately independent from this state. Only [ARMED] may feed the
 * lap-timing engine; earlier states remain observable so camera recording can still bind to the
 * persisted session while GPS catches up.
 */
enum class LapGpsReadiness {
    WAITING_DEVICE,
    WAITING_MAIN,
    ACQUIRING_FIX,
    STABILIZING,
    ARMED,
}
