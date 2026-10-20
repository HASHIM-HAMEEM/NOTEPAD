package com.hashim.notepad

import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReporterTest {
    @Test
    fun reportContainsVersionThreadAndStackTrace() {
        val error = IllegalStateException("highlighter exploded")
        val report = CrashReporter.format(Thread.currentThread(), error, "9.9.9")
        assertTrue(report.startsWith("NotePad 9.9.9 crash report"))
        assertTrue(report.contains("Thread: ${Thread.currentThread().name}"))
        assertTrue(report.contains("IllegalStateException: highlighter exploded"))
        assertTrue(report.contains("CrashReporterTest"))
    }
}
