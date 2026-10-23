// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip

/**
 * 桌面走 `javax.sound.sampled`。
 *
 * **它只认 WAV / AIFF / AU**：m4a、mp3 会让 `getAudioInputStream` 抛
 * `UnsupportedAudioFileException`。这不是缺陷，是 JDK 内置解码器的边界，如实反映成
 * `play` 返回 false，界面会说「这台设备放不了这个格式」，而不是静默什么都不发生。
 * 想支持 mp3/m4a 得再引一个解码器，那是另一个决定。
 */
private var activeClip: Clip? = null

internal actual fun platformPlayAudioFile(path: String): Boolean {
    platformStopAudio()
    val file = File(path)
    if (!file.isFile) return false
    return runCatching {
        val clip = AudioSystem.getClip()
        AudioSystem.getAudioInputStream(file).use { input -> clip.open(input) }
        clip.start()
        activeClip = clip
        true
    }.getOrDefault(false)
}

internal actual fun platformStopAudio() {
    val clip = activeClip ?: return
    activeClip = null
    runCatching { clip.stop() }
    runCatching { clip.close() }
}
