package com.blazepush.feature.test.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraBindingReadyContractTest {
    @Test
    fun `bind request generation accepts only the current callback`() {
        assertTrue(isCurrentCameraBindRequest(requestGeneration = 7, currentGeneration = 7))
        assertFalse(isCurrentCameraBindRequest(requestGeneration = 6, currentGeneration = 7))
        assertFalse(isCurrentCameraBindRequest(requestGeneration = 8, currentGeneration = 7))
    }

    @Test
    fun `bind ready callback covers idempotent success real success and failure`() {
        val source = engineSource()
        val idempotentBlock = source.substringAfter("if (isBound && boundLifecycleOwner === lifecycleOwner")
            .substringBefore("val requestGeneration")
        val successBlock = source.substringAfter("bindResult.onSuccess")
            .substringBefore("}.onFailure")
        val failureBlock = source.substringAfter("}.onFailure { t ->")
            .substringBefore("}, ContextCompat.getMainExecutor")

        assertTrue(source.contains("onReady: (Boolean) -> Unit = {}"))
        assertTrue(idempotentBlock.contains("onReady(true)"))
        assertTrue(successBlock.contains("videoCapture = vc"))
        assertTrue(successBlock.indexOf("videoCapture = vc") < successBlock.indexOf("onReady(true)"))
        assertTrue(failureBlock.contains("onReady(false)"))
    }

    @Test
    fun `stale bind callback is rejected before touching CameraX or auto start`() {
        val source = engineSource()
        val listenerBlock = source.substringAfter("cameraProviderFuture.addListener({")
            .substringBefore("val bindResult = runCatching")

        assertTrue(listenerBlock.contains("isCurrentCameraBindRequest"))
        assertTrue(listenerBlock.contains("onReady(false)"))
        assertTrue(source.contains("bindRequestGeneration++"))
    }

    private fun engineSource(): String {
        val relative = "feature/test/src/main/java/com/blazepush/feature/test/recording/CameraRecordingEngine.kt"
        val userDir = File(requireNotNull(System.getProperty("user.dir")))
        return generateSequence(userDir) { current -> current.parentFile }
            .map { root -> File(root, relative) }
            .firstOrNull(File::exists)
            ?.readText()
            ?: error("source file not found from ${userDir.absolutePath}: $relative")
    }
}
