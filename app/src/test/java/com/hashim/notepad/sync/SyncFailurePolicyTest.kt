package com.hashim.notepad.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncFailurePolicyTest {
    @Test
    fun onlyRejectedRefreshTokensClearTheSession() {
        listOf(400, 401).forEach { assertTrue(SyncFailurePolicy.shouldClearSession(it)) }
        listOf(403, 404, 408, 429, 500, 503).forEach { assertFalse(SyncFailurePolicy.shouldClearSession(it)) }
    }

    @Test
    fun retriesTransientServerFailures() {
        listOf(408, 429, 500, 502, 503, 504).forEach { assertTrue(SyncFailurePolicy.shouldRetryHttp(it)) }
    }

    @Test
    fun doesNotRetryInvalidCredentialsOrMissingSchema() {
        listOf(400, 401, 403, 404, 409, 422).forEach { assertFalse(SyncFailurePolicy.shouldRetryHttp(it)) }
    }
}
