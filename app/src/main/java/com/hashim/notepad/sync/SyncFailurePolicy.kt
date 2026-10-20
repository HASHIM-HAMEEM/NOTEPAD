package com.hashim.notepad.sync

internal object SyncFailurePolicy {
    fun shouldRetryHttp(code: Int): Boolean = code == 408 || code == 429 || code in 500..599
    fun shouldClearSession(code: Int): Boolean = code == 400 || code == 401
}
