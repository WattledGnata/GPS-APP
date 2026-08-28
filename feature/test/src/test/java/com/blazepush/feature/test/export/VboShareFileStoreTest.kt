package com.blazepush.feature.test.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VboShareFileStoreTest {
    @Test
    fun `file names cannot escape the dedicated cache directory`() {
        assertEquals("secret.vbo", VboShareFileStore.safeFileName("../../secret"))
        assertEquals("session.vbo", VboShareFileStore.safeFileName("session.vbo"))
        assertEquals("lap-session.vbo", VboShareFileStore.safeFileName("..."))
    }

    @Test
    fun `cleanup removes only expired files`() {
        val directory = Files.createTempDirectory("vbo-share-cleanup").toFile()
        try {
            val now = 2L * 24L * 60L * 60L * 1000L
            val expired = File(directory, "old.vbo").apply { writeText("old"); setLastModified(1L) }
            val recent = File(directory, "new.vbo").apply { writeText("new"); setLastModified(now - 1_000L) }

            VboShareFileStore.cleanupExpired(directory, now)

            assertFalse(expired.exists())
            assertTrue(recent.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
