// SPDX-License-Identifier: GPL-3.0-only

package app.relay.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFAudio.setActive
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL

@OptIn(ExperimentalForeignApi::class)
private var activeRecorder: AVAudioRecorder? = null
private var recordStartTime = 0L
private var currentPath: String? = null

@OptIn(ExperimentalForeignApi::class)
internal actual fun platformIsRecordingAudio(): Boolean = activeRecorder != null

@OptIn(ExperimentalForeignApi::class)
internal actual fun platformStartAudioRecording(outputPath: String): Boolean {
    platformCancelAudioRecording()
    return runCatching {
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayAndRecord, error = null)
        session.setMode(AVAudioSessionModeDefault, error = null)
        session.setActive(true, error = null)

        val url = NSURL.fileURLWithPath(outputPath)
        val settings = mapOf<Any?, Any?>(
            AVFormatIDKey to kAudioFormatMPEG4AAC,
            AVSampleRateKey to 44100.0,
            AVNumberOfChannelsKey to 1,
        )
        val recorder = AVAudioRecorder(uRL = url, settings = settings, error = null)
        if (!recorder.record()) return@runCatching false
        activeRecorder = recorder
        currentPath = outputPath
        recordStartTime = platform.posix.time(null) * 1000L
        true
    }.getOrDefault(false)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun platformStopAudioRecording(): Long {
    val recorder = activeRecorder ?: return 0L
    activeRecorder = null
    val duration = (platform.posix.time(null) * 1000L) - recordStartTime
    return runCatching {
        recorder.stop()
        val session = AVAudioSession.sharedInstance()
        session.setActive(false, error = null)
        duration
    }.getOrDefault(0L)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun platformCancelAudioRecording() {
    val recorder = activeRecorder ?: return
    activeRecorder = null
    val path = currentPath
    currentPath = null
    runCatching {
        recorder.stop()
        recorder.deleteRecording()
        val session = AVAudioSession.sharedInstance()
        session.setActive(false, error = null)
    }
    if (path != null) {
        runCatching {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        }
    }
}
