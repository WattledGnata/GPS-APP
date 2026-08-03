package com.blazepush.feature.test.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Conflates reliable crossing flushes and binds delayed work to its originating session. */
internal class LapTelemetryFlushScheduler(
    private val scope: CoroutineScope,
    private val delayMs: Long = 5_000L,
    private val flush: suspend (String) -> Unit,
) {
    private var pending: Job? = null

    fun schedule(sessionId: String) {
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            flush(sessionId)
        }
    }

    fun cancel() {
        pending?.cancel()
        pending = null
    }
}
