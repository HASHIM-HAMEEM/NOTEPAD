package com.hashim.notepad.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SyncDeviceIdTest {
    @Test
    fun deviceIdIsStablePerInstallAndDistinctPerAccount() {
        val a = SyncManager.deviceIdFor("install-1", "user-a")
        assertEquals(a, SyncManager.deviceIdFor("install-1", "user-a"))
        assertNotEquals(a, SyncManager.deviceIdFor("install-1", "user-b"))
        assertNotEquals(a, SyncManager.deviceIdFor("install-2", "user-a"))
    }
}
