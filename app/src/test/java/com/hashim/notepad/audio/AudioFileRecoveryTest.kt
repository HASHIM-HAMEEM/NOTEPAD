package com.hashim.notepad.audio

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFileRecoveryTest {
    @Test
    fun preservesActiveSegmentTempsAndRemovesStaleTemps() {
        val root = Files.createTempDirectory("audio-recovery").toFile()
        try {
            val activeTemp = File(root, "active/segment-0001.m4a.tmp")
            val staleTemp = File(root, "stale/segment-0002.m4a.tmp")
            val stalePartial = File(root, "stale/segment-0003.partial")
            val completedSegment = File(root, "stale/segment-0000.m4a")
            listOf(activeTemp, staleTemp, stalePartial, completedSegment).forEach {
                it.parentFile?.mkdirs()
                it.createNewFile()
            }

            AudioFileRecovery.deleteInterruptedTempFiles(root, "active")

            assertTrue(activeTemp.exists())
            assertFalse(staleTemp.exists())
            assertFalse(stalePartial.exists())
            assertTrue(completedSegment.exists())

            AudioFileRecovery.deleteInterruptedTempFiles(root, null)

            assertFalse(activeTemp.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
