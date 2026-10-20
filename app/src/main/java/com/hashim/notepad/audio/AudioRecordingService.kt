package com.hashim.notepad.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.hashim.notepad.MainActivity
import com.hashim.notepad.R
import com.hashim.notepad.data.NotePadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class AudioRecordingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var repository: NotePadRepository? = null
    private var recordingId: String? = null
    private var notebookId: String? = null
    private var title: String = "Lecture recording"
    private var recorder: MediaRecorder? = null
    private var segmentFile: File? = null
    private var segmentStartedAt = 0L
    private var segmentElapsedBeforePause = 0L
    private var recordingStartedAt = 0L
    private var segmentIndex = 0
    private var paused = false
    private var focusRequest: AudioFocusRequest? = null
    private var audioManager: AudioManager? = null

    private val rollover = Runnable {
        val id = recordingId ?: return@Runnable
        val segment = finalizeSegment()
        if (segment != null) {
            serviceScope.launch {
                repository?.appendAudioSegment(id, segment.name, segment.durationMs)
                withContext(Dispatchers.Main) {
                    if (!paused && recordingId != null) startSegment()
                }
            }
        } else if (!paused && recordingId != null) {
            startSegment()
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change < 0) pauseForFocusLoss()
    }

    override fun onCreate() {
        super.onCreate()
        repository = NotePadRepository.get(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> beginRecording(
                intent.getStringExtra(EXTRA_RECORDING_ID).orEmpty(),
                intent.getStringExtra(EXTRA_NOTEBOOK_ID).orEmpty(),
                intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            )
            ACTION_STOP -> stopRecording()
            ACTION_PAUSE -> pauseForFocusLoss()
            ACTION_RESUME -> resumeRecording()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(rollover)
        if (recorder != null) {
            val id = recordingId
            finalizeSegment()?.let { segment ->
                if (id != null) serviceScope.launch { repository?.appendAudioSegment(id, segment.name, segment.durationMs) }
            }
            if (id != null) serviceScope.launch { repository?.finishAudioRecording(id) }
        }
        serviceRecordingId = null
        abandonAudioFocus()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun beginRecording(id: String, notebook: String, recordingTitle: String) {
        if (id.isBlank() || notebook.isBlank()) {
            stopSelf()
            return
        }
        if (recordingId == id && recorder != null) return
        recordingId = id
        serviceRecordingId = id
        notebookId = notebook
        title = recordingTitle.ifBlank { "Lecture recording" }
        recordingStartedAt = SystemClock.elapsedRealtime()
        val directory = File(File(filesDir, "audio"), id).apply { mkdirs() }
        segmentIndex = (directory.listFiles().orEmpty().mapNotNull {
            Regex("segment-(\\d+)\\.m4a(?:\\.tmp)?").matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: -1) + 1
        startForegroundCompat(buildNotification())
        requestAudioFocus()
        startSegment()
    }

    private fun startSegment() {
        val id = recordingId ?: return
        val directory = File(File(filesDir, "audio"), id).apply { mkdirs() }
        val basename = String.format(Locale.ROOT, "segment-%04d.m4a", segmentIndex++)
        val temp = File(directory, "$basename.tmp")
        temp.delete()
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
        try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioSamplingRate(44_100)
            mediaRecorder.setAudioEncodingBitRate(96_000)
            mediaRecorder.setOutputFile(temp.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()
            recorder = mediaRecorder
            segmentFile = temp
            segmentStartedAt = SystemClock.elapsedRealtime()
            handler.removeCallbacks(rollover)
            handler.postDelayed(rollover, SEGMENT_LENGTH_MS)
        } catch (error: Exception) {
            runCatching { mediaRecorder.release() }
            temp.delete()
            stopRecording()
        }
    }

    private data class FinishedSegment(val name: String, val durationMs: Long)

    private fun finalizeSegment(): FinishedSegment? {
        handler.removeCallbacks(rollover)
        val current = recorder ?: return null
        recorder = null
        val temp = segmentFile
        segmentFile = null
        val fallbackDuration = (SystemClock.elapsedRealtime() - segmentStartedAt).coerceAtLeast(0)
        val stopped = runCatching { current.stop() }.isSuccess
        runCatching { current.reset() }
        runCatching { current.release() }
        if (!stopped || temp == null || temp.length() == 0L) {
            temp?.delete()
            return null
        }
        val completed = File(temp.parentFile, temp.name.removeSuffix(".tmp"))
        if (completed.exists()) completed.delete()
        if (!temp.renameTo(completed)) {
            temp.delete()
            return null
        }
        val duration = runCatching {
            MediaMetadataRetriever().use { metadata ->
                metadata.setDataSource(completed.absolutePath)
                metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }
        }.getOrNull() ?: fallbackDuration
        return FinishedSegment(completed.name, duration)
    }

    private fun stopRecording() {
        handler.removeCallbacks(rollover)
        val id = recordingId
        val segment = finalizeSegment()
        recordingId = null
        notebookId = null
        serviceRecordingId = null
        paused = false
        if (id != null) {
            serviceScope.launch {
                segment?.let { repository?.appendAudioSegment(id, it.name, it.durationMs) }
                repository?.finishAudioRecording(id)
                withContext(Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        abandonAudioFocus()
    }

    private fun pauseForFocusLoss() {
        val current = recorder ?: return
        if (paused) return
        runCatching { current.pause() }.onSuccess {
            paused = true
            segmentElapsedBeforePause = (SystemClock.elapsedRealtime() - segmentStartedAt).coerceAtLeast(0)
            handler.removeCallbacks(rollover)
            val id = recordingId ?: return
            val time = (SystemClock.elapsedRealtime() - recordingStartedAt).coerceAtLeast(0)
            serviceScope.launch { repository?.addAudioMarker(id, time, "focus-loss") }
            updateNotification()
        }
    }

    private fun resumeRecording() {
        val current = recorder ?: return
        if (!paused) return
        runCatching { current.resume() }.onSuccess {
            paused = false
            segmentStartedAt = SystemClock.elapsedRealtime() - segmentElapsedBeforePause
            handler.postDelayed(rollover, (SEGMENT_LENGTH_MS - segmentElapsedBeforePause).coerceAtLeast(500L))
            updateNotification()
        }
    }

    private fun requestAudioFocus() {
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setOnAudioFocusChangeListener(focusListener, handler)
                .build()
            focusRequest = request
            audioManager?.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(focusListener)
        }
        focusRequest = null
        audioManager = null
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopService = PendingIntent.getService(
            this,
            2,
            Intent(this, AudioRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (paused) "Recording paused" else "Recording lecture audio")
            .setContentText(title)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (paused) "Resume" else "Pause",
                PendingIntent.getService(
                    this,
                    3,
                    Intent(this, AudioRecordingService::class.java)
                        .setAction(if (paused) ACTION_RESUME else ACTION_PAUSE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopService)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Audio recording", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        const val ACTION_START = "com.hashim.notepad.audio.START"
        const val ACTION_STOP = "com.hashim.notepad.audio.STOP"
        const val ACTION_PAUSE = "com.hashim.notepad.audio.PAUSE"
        const val ACTION_RESUME = "com.hashim.notepad.audio.RESUME"
        const val EXTRA_RECORDING_ID = "recording_id"
        const val EXTRA_NOTEBOOK_ID = "notebook_id"
        const val EXTRA_TITLE = "recording_title"
        private const val CHANNEL_ID = "lecture_recordings"
        private const val NOTIFICATION_ID = 3102
        private const val SEGMENT_LENGTH_MS = 30_000L
        @Volatile private var serviceRecordingId: String? = null

        fun cleanupInterruptedFiles(context: android.content.Context) {
            val audioRoot = File(context.filesDir, "audio")
            AudioFileRecovery.deleteInterruptedTempFiles(audioRoot, serviceRecordingId)
            if (serviceRecordingId == null) {
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    val repo = NotePadRepository.get(context.applicationContext)
                    repo.activeAudioRecordings().forEach { recording ->
                        val directory = File(audioRoot, recording.id)
                        val stored = recording.segments.toSet()
                        directory.listFiles().orEmpty()
                            .filter { it.isFile && it.extension == "m4a" && it.name !in stored }
                            .sortedBy(File::getName)
                            .forEach { file -> repo.appendAudioSegment(recording.id, file.name, audioDuration(file)) }
                        repo.finishAudioRecording(recording.id)
                    }
                    repo.recoverInterruptedAudioRecordings()
                }
            }
        }

        private fun audioDuration(file: File): Long = runCatching {
            MediaMetadataRetriever().use { metadata ->
                metadata.setDataSource(file.absolutePath)
                metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }
        }.getOrNull() ?: SEGMENT_LENGTH_MS
    }
}
