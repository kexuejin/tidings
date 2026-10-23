// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import android.media.MediaRecorder
import java.io.File

private var activeRecorder: MediaRecorder? = null
private var recordStartTime = 0L
private var currentPath: String? = null

internal actual fun platformIsRecordingAudio(): Boolean = activeRecorder != null

internal actual fun platformStartAudioRecording(outputPath: String): Boolean {
    platformCancelAudioRecording()
    return runCatching {
        val recorder = @Suppress("DEPRECATION") MediaRecorder()
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setOutputFile(outputPath)
        recorder.prepare()
        recorder.start()
        activeRecorder = recorder
        currentPath = outputPath
        recordStartTime = System.currentTimeMillis()
        true
    }.getOrDefault(false)
}

internal actual fun platformStopAudioRecording(): Long {
    val recorder = activeRecorder ?: return 0L
    activeRecorder = null
    val duration = System.currentTimeMillis() - recordStartTime
    return runCatching {
        recorder.stop()
        recorder.release()
        duration
    }.getOrDefault(0L)
}

internal actual fun platformCancelAudioRecording() {
    val recorder = activeRecorder ?: return
    activeRecorder = null
    val path = currentPath
    currentPath = null
    runCatching {
        recorder.stop()
    }
    runCatching {
        recorder.release()
    }
    if (path != null) {
        runCatching { File(path).delete() }
    }
}
