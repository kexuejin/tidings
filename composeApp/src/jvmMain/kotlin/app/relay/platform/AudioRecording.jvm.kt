// SPDX-License-Identifier: GPL-3.0-only

package app.relay.platform

private var activeRecording = false

internal actual fun platformIsRecordingAudio(): Boolean = activeRecording

internal actual fun platformStartAudioRecording(outputPath: String): Boolean = false

internal actual fun platformStopAudioRecording(): Long = 0L

internal actual fun platformCancelAudioRecording() {
    activeRecording = false
}
