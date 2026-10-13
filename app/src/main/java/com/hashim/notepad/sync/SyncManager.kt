package com.hashim.notepad.sync

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.sync.SupabaseHttp.orThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class SyncState { DISABLED, SIGNED_OUT, NEEDS_KEY, IDLE, SYNCING, ERROR, OFFLINE }

data class SyncStatus(val state: SyncState, val message: String? = null, val lastSyncAt: Long = 0, val pending: Int = 0)

/** Process-wide owner of auth, encryption key and scheduling; the UI observes [status]. */
class SyncManager private constructor(private val context: Context) {
    val auth = AuthManager(context)
    val repo = NotePadRepository.get(context)
    private val prefs = context.getSharedPreferences("notepad.sync", Context.MODE_PRIVATE)
    private val engine = SyncEngine(context, repo, auth) { key() }
    private val mutex = Mutex()
    private val _status = MutableStateFlow(SyncStatus(SyncState.DISABLED))
    val status: StateFlow<SyncStatus> = _status

    init {
        repo.onLocalChange = { if (auth.session.value != null && key() != null) requestSync(delayMs = 4_000) }
        refreshStatus()
        if (auth.session.value != null) schedulePeriodic()
    }

    fun key(): ByteArray? = prefs.getString("key", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
    private val deviceId: String get() = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }

    private fun refreshStatus(message: String? = null) {
        val base = when {
            !SupabaseHttp.configured -> SyncState.DISABLED
            auth.session.value == null -> SyncState.SIGNED_OUT
            key() == null -> SyncState.NEEDS_KEY
            else -> SyncState.IDLE
        }
        _status.value = SyncStatus(base, message, prefs.getLong("last_sync", 0), runCatching { engine.pendingCount() }.getOrDefault(0))
    }

    suspend fun signIn(email: String, password: String) = withContext(Dispatchers.IO) {
        val previous = prefs.getString("uid", null)
        auth.signIn(email.trim(), password)
        onSignedIn(previous)
    }

    /** Returns a message when the account still needs e-mail confirmation. */
    suspend fun signUp(email: String, password: String): String? = withContext(Dispatchers.IO) {
        val previous = prefs.getString("uid", null)
        val msg = auth.signUp(email.trim(), password)
        if (msg == null) onSignedIn(previous)
        msg
    }

    suspend fun resendSignUpConfirmation(email: String) = withContext(Dispatchers.IO) {
        auth.resendSignUpConfirmation(email)
    }

    suspend fun completeEmailConfirmation(accessToken: String, refreshToken: String, expiresIn: Long) = withContext(Dispatchers.IO) {
        val previous = prefs.getString("uid", null)
        auth.completeEmailConfirmation(accessToken, refreshToken, expiresIn)
        onSignedIn(previous)
    }

    private fun onSignedIn(previousUid: String?) {
        val uid = auth.session.value?.userId
        if (uid != previousUid) {
            // Different account: the old key and watermarks must not leak across accounts.
            prefs.edit().remove("key").putString("uid", uid).apply()
            engine.resetWatermarks()
            repo.helper.writableDatabase.execSQL("delete from outbox")
            for (t in SyncEngine.TABLES) repo.helper.writableDatabase.execSQL(
                if (t == "stroke_batches" || t == "recognition") "insert or ignore into outbox(table_name, row_id) select distinct '$t', page_id from $t"
                else "insert or ignore into outbox(table_name, row_id) select '$t', id from $t"
            )
        }
        schedulePeriodic()
        refreshStatus()
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        auth.signOut()
        prefs.edit().remove("key").apply()
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        refreshStatus()
    }

    /**
     * Derives the account key from the passphrase. The first device publishes salt + key-check to `device_keys`;
     * later devices must produce the same key or the passphrase is rejected.
     */
    suspend fun setPassphrase(passphrase: String): Unit = withContext(Dispatchers.IO) {
        val session = auth.session.value ?: throw IllegalStateException("Sign in first")
        require(passphrase.length >= 8) { "Passphrase must be at least 8 characters" }
        val token = auth.token()
        val existing = JSONArray(SupabaseHttp.json("GET", "/rest/v1/device_keys?user_id=eq.${session.userId}&kdf_salt=not.is.null&select=kdf_salt,key_check&limit=1", token).orThrow().text)
        val salt: ByteArray
        val k: ByteArray
        if (existing.length() > 0) {
            val row = existing.getJSONObject(0)
            salt = SyncEngine.unhex(row.getString("kdf_salt"))
            k = Crypto.deriveKey(passphrase.toCharArray(), salt)
            if (!Crypto.verifyKey(k, SyncEngine.unhex(row.getString("key_check")))) throw IllegalArgumentException("Wrong passphrase for this account")
        } else {
            salt = Crypto.randomBytes(16)
            k = Crypto.deriveKey(passphrase.toCharArray(), salt)
        }
        val row = JSONObject().put("device_id", deviceId).put("user_id", session.userId).put("name", android.os.Build.MODEL ?: "Android")
            .put("public_key", SyncEngine.hex(ByteArray(0))).put("kdf_salt", SyncEngine.hex(salt)).put("key_check", SyncEngine.hex(Crypto.keyCheck(k)))
            .put("last_seen_at", SyncEngine.iso(System.currentTimeMillis()))
        SupabaseHttp.json("POST", "/rest/v1/device_keys?on_conflict=device_id", token, JSONArray().put(row).toString(), mapOf("Prefer" to "resolution=merge-duplicates,return=minimal")).orThrow()
        prefs.edit().putString("key", Base64.encodeToString(k, Base64.NO_WRAP)).apply()
        refreshStatus()
        requestSync(0)
    }

    fun requestSync(delayMs: Long = 0) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, req)
    }

    private fun schedulePeriodic() {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Runs one sync pass; returns false when it should be retried later. */
    suspend fun syncNow(): Boolean = withContext(Dispatchers.IO) {
        if (auth.session.value == null || key() == null) { refreshStatus(); return@withContext true }
        mutex.withLock {
            _status.value = _status.value.copy(state = SyncState.SYNCING, message = null)
            try {
                val r = engine.sync()
                prefs.edit().putLong("last_sync", System.currentTimeMillis()).apply()
                refreshStatus("Pushed ${r.pushed}, pulled ${r.pulled}")
                true
            } catch (e: java.io.IOException) {
                Log.w(TAG, "sync offline/failed", e)
                _status.value = SyncStatus(SyncState.OFFLINE, e.message, prefs.getLong("last_sync", 0), engine.pendingCount())
                false
            } catch (e: Exception) {
                Log.e(TAG, "sync error", e)
                _status.value = SyncStatus(SyncState.ERROR, e.message ?: e.toString(), prefs.getLong("last_sync", 0), engine.pendingCount())
                e !is IllegalStateException
            }
        }
    }

    companion object {
        private const val TAG = "SyncManager"
        private const val ONCE = "notepad.sync.once"
        private const val PERIODIC = "notepad.sync.periodic"
        @Volatile private var instance: SyncManager? = null
        fun get(context: Context): SyncManager = instance ?: synchronized(this) { instance ?: SyncManager(context.applicationContext).also { instance = it } }
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ok = SyncManager.get(applicationContext).syncNow()
        return if (ok) Result.success() else if (runAttemptCount < 5) Result.retry() else Result.failure()
    }
}
