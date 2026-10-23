// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.setActive
import platform.Foundation.NSURL

/**
 * iOS 走 `AVAudioPlayer`，它能解 m4a/aac/mp3/wav 这些系统认识的一切格式。
 *
 * 三处细节都是必要的：
 * - 会话类别设成 `Playback` 并 `setActive(true)`：不设的话静音开关会把播放静掉，
 *   而语音消息被静音键静掉、用户只会以为「点了没反应」；
 * - `AVAudioPlayer(contentsOfURL:error:null)` 在格式不认识 / 文件读不了时返回 **nil**，
 *   而 Kotlin/Native 把它绑成**非空**类型（写 `?:` 编译器会说那是死代码）—— 拿到 nil
 *   会在解引用时抛 NPE，所以整段必须包在 `runCatching` 里，不能靠判空；
 * - `play()` 本身返回 Boolean，直接把它当结果 —— 这正是 kapp-lib 那版用 `?.` 丢掉的东西。
 */
@OptIn(ExperimentalForeignApi::class)
private var activePlayer: AVAudioPlayer? = null

@OptIn(ExperimentalForeignApi::class)
internal actual fun platformPlayAudioFile(path: String): Boolean {
    platformStopAudio()
    val session = AVAudioSession.sharedInstance()
    session.setCategory(AVAudioSessionCategoryPlayback, error = null)
    session.setMode(AVAudioSessionModeDefault, error = null)
    session.setActive(true, error = null)

    return runCatching {
        val player = AVAudioPlayer(contentsOfURL = NSURL.fileURLWithPath(path), error = null)
        player.prepareToPlay()
        if (!player.play()) return@runCatching false
        activePlayer = player
        true
    }.getOrDefault(false)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun platformStopAudio() {
    val player = activePlayer ?: return
    activePlayer = null
    player.stop()
}
