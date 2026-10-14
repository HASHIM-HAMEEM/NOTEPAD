package com.hashim.notepad.audio

import android.Manifest
import android.media.MediaMetadataRetriever
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.hashim.notepad.data.AudioRecording
import com.hashim.notepad.data.InkTimestamp
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.data.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID

@Composable
internal fun AudioRecorderButton(
    repo: NotePadRepository,
    notebookId: String,
    notebookTitle: String,
    onRecordingSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recordings by repo.audioRecordings(notebookId).collectAsState(initial = emptyList())
    val active = recordings.firstOrNull(AudioRecording::active)
    var showRationale by remember { mutableStateOf(false) }
    var showDenied by remember { mutableStateOf(false) }
    fun requiredPermissionsGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    fun startRecording() {
        scope.launch {
            val id = UUID.randomUUID().toString()
            runCatching {
                repo.createAudioRecording(id, notebookId, "${notebookTitle.ifBlank { "Lecture" }} recording")
                val start = Intent(context, AudioRecordingService::class.java)
                    .setAction(AudioRecordingService.ACTION_START)
                    .putExtra(AudioRecordingService.EXTRA_RECORDING_ID, id)
                    .putExtra(AudioRecordingService.EXTRA_NOTEBOOK_ID, notebookId)
                    .putExtra(AudioRecordingService.EXTRA_TITLE, notebookTitle)
                ContextCompat.startForegroundService(context, start)
                onRecordingSelected(id)
            }.onFailure {
                repo.finishAudioRecording(id)
                showDenied = true
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it } && requiredPermissionsGranted()) startRecording() else {
            showDenied = true
        }
    }

    IconButton(
        onClick = {
            if (active != null) {
                context.startService(Intent(context, AudioRecordingService::class.java).setAction(AudioRecordingService.ACTION_STOP))
            } else {
                showRationale = true
            }
        },
        modifier = modifier,
    ) {
        Icon(
            imageVector = if (active != null) Icons.Default.Stop else Icons.Default.Mic,
            contentDescription = if (active != null) "Stop audio recording" else "Record lecture audio",
            tint = if (active != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text("Record lecture audio") },
            text = { Text("NotePad records audio in short local segments and links them to the page items you create. Microphone and notification permission are needed while recording.") },
            confirmButton = {
                Button(onClick = {
                    showRationale = false
                    val permissions = buildList {
                        add(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    if (requiredPermissionsGranted()) {
                        startRecording()
                    } else {
                        permissionLauncher.launch(permissions.toTypedArray())
                    }
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { showRationale = false }) { Text("Cancel") } },
        )
    }
    if (showDenied) {
        AlertDialog(
            onDismissRequest = { showDenied = false },
            title = { Text("Recording permission needed") },
            text = { Text("Allow microphone and notification access in system settings to record lecture audio. Your notes remain available if you choose not to enable it.") },
            confirmButton = {
                TextButton(onClick = {
                    showDenied = false
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { showDenied = false }) { Text("Not now") } },
        )
    }
}

@Composable
internal fun AudioPlaybackBar(
    repo: NotePadRepository,
    notebookId: String,
    pages: List<Page>,
    recordingId: String?,
    onRecordingIdChange: (String?) -> Unit,
    onPlaybackState: (String?, Long, List<InkTimestamp>) -> Unit,
    seekToMs: Long? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val recordings by repo.audioRecordings(notebookId).collectAsState(initial = emptyList())
    val recording = recordings.firstOrNull { it.id == recordingId }
    val player = remember(context) { ExoPlayer.Builder(context).build() }
    var timestamps by remember(recordingId) { mutableStateOf(emptyList<InkTimestamp>()) }
    var positionMs by remember(recordingId) { mutableLongStateOf(0L) }
    var durationMs by remember(recordingId) { mutableLongStateOf(0L) }
    var playing by remember(recordingId) { mutableStateOf(false) }
    var speed by remember(recordingId) { mutableFloatStateOf(1f) }
    var segmentDurationsMs by remember(recordingId) { mutableStateOf(emptyList<Long>()) }

    fun globalPosition(): Long =
        segmentDurationsMs.take(player.currentMediaItemIndex.coerceAtLeast(0)).sum() + player.currentPosition.coerceAtLeast(0)

    fun seekGlobal(position: Long) {
        val segment = AudioTimeline.segmentAt(segmentDurationsMs, position) ?: return
        player.seekTo(segment.segmentIndex, segment.offsetMs)
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }
    LaunchedEffect(recordingId) {
        timestamps = recordingId?.let { repo.inkTimestamps(it) }.orEmpty()
        onPlaybackState(recordingId, 0L, timestamps)
        positionMs = 0L
        durationMs = 0L
        playing = false
        player.stop()
        player.clearMediaItems()
        val selected = recording ?: return@LaunchedEffect
        val mediaFiles = selected.segments.mapNotNull { segment ->
            File(File(File(context.filesDir, "audio"), selected.id), segment).takeIf(File::exists)
        }
        segmentDurationsMs = withContext(Dispatchers.IO) { mediaFiles.map { readDuration(it) } }
        val mediaItems = mediaFiles.map { MediaItem.fromUri(Uri.fromFile(it)) }
        durationMs = segmentDurationsMs.sum().takeIf { it > 0 } ?: selected.durationMs
        if (mediaItems.isNotEmpty()) {
            player.setMediaItems(mediaItems)
            player.prepare()
        }
    }
    LaunchedEffect(seekToMs) { seekToMs?.let(::seekGlobal) }
    LaunchedEffect(recording?.segments, recording?.active) {
        if (recording == null || recording.active || recording.id != recordingId) return@LaunchedEffect
        val mediaFiles = recording.segments.mapNotNull { segment ->
            File(File(File(context.filesDir, "audio"), recording.id), segment).takeIf(File::exists)
        }
        segmentDurationsMs = withContext(Dispatchers.IO) { mediaFiles.map { readDuration(it) } }
        val mediaItems = mediaFiles.map { MediaItem.fromUri(Uri.fromFile(it)) }
        if (mediaItems.isNotEmpty()) {
            val position = globalPosition()
            durationMs = segmentDurationsMs.sum().takeIf { it > 0 } ?: recording.durationMs
            player.setMediaItems(mediaItems)
            player.prepare()
            seekGlobal(position)
        }
    }
    LaunchedEffect(player, recordingId) {
        while (isActive) {
            positionMs = globalPosition()
            durationMs = segmentDurationsMs.sum().takeIf { it > 0 } ?: recording?.durationMs ?: 0L
            playing = player.isPlaying
            onPlaybackState(recordingId, positionMs, timestamps)
            delay(250)
        }
    }

    if (recordingId != null && recording != null) {
        Surface(
            modifier = modifier.fillMaxWidth().widthIn(max = 560.dp).padding(horizontal = 16.dp).navigationBarsPadding(),
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(recording.title, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.labelLarge)
                    Text(
                        "${formatAudioTime(positionMs)} / ${formatAudioTime(durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    TextButton(onClick = {
                        val next = AUDIO_SPEEDS[(AUDIO_SPEEDS.indexOf(speed).coerceAtLeast(0) + 1) % AUDIO_SPEEDS.size]
                        speed = next
                        player.setPlaybackSpeed(next)
                    }) { Text("${speed}×") }
                    IconButton(onClick = { onRecordingIdChange(null) }) {
                        Icon(Icons.Default.Close, contentDescription = "Close audio player")
                    }
                }
                Slider(
                    value = positionMs.toFloat().coerceIn(0f, durationMs.coerceAtLeast(1).toFloat()),
                    onValueChange = { value ->
                        positionMs = value.toLong()
                        seekGlobal(positionMs)
                    },
                    valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { seekGlobal((globalPosition() - 10_000).coerceAtLeast(0)) }) {
                        Icon(Icons.Default.FastRewind, contentDescription = "Back 10 seconds")
                    }
                    IconButton(
                        enabled = recording.segments.isNotEmpty(),
                        onClick = {
                            if (player.isPlaying) player.pause() else {
                                if (globalPosition() >= durationMs) seekGlobal(0)
                                player.play()
                            }
                        },
                    ) {
                        Icon(
                            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "Pause audio" else "Play audio",
                        )
                    }
                    IconButton(onClick = { seekGlobal((globalPosition() + 10_000).coerceAtMost(durationMs)) }) {
                        Icon(Icons.Default.FastForward, contentDescription = "Forward 10 seconds")
                    }
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        timestamps.forEach { marker ->
                            val pageNumber = pages.indexOfFirst { it.id == marker.pageId } + 1
                            if (pageNumber > 0) {
                                TextButton(onClick = { seekGlobal(marker.timeMs) }) {
                                    Text("${marker.itemKind} · p$pageNumber · ${formatAudioTime(marker.timeMs)}", maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun readDuration(file: File): Long = runCatching {
    MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    }
}.getOrDefault(0L)

private val AUDIO_SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

private fun formatAudioTime(timeMs: Long): String {
    val totalSeconds = (timeMs.coerceAtLeast(0) / 1000).toInt()
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
