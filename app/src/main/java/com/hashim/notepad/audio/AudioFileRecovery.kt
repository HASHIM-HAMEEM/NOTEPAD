package com.hashim.notepad.audio

import java.io.File

internal object AudioFileRecovery {
    fun deleteInterruptedTempFiles(audioRoot: File, activeRecordingId: String?) {
        if (!audioRoot.isDirectory) return
        audioRoot.walkTopDown()
            .filter { file ->
                file.isFile &&
                    (file.name.endsWith(".tmp") || file.name.endsWith(".partial")) &&
                    file.parentFile?.name != activeRecordingId
            }
            .forEach(File::delete)
    }
}
