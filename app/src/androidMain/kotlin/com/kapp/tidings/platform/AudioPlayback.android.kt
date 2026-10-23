// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import android.media.AudioAttributes
import android.media.MediaPlayer

/**
 * Android 走 `MediaPlayer`，`setDataSource(path)` 认系统解码器认识的一切格式。
 *
 * 失败路径要显式收拾：`setDataSource`/`prepare` 抛异常时那个 `MediaPlayer` 还没交出去，
 * 不 release 就是泄漏一个 native 解码器。所以先建、失败就地释放、成功才记成当前播放器。
 */
private var activePlayer: MediaPlayer? = null

internal actual fun platformPlayAudioFile(path: String): Boolean {
    platformStopAudio()
    val player = MediaPlayer()
    val started = runCatching {
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        player.setDataSource(path)
        // 播完/出错就自己收掉：界面拿不到完成回调（见 AudioPlayback 的 KDoc），
        // 但 native 侧的资源不能因此挂着。
        player.setOnCompletionListener { done ->
            if (activePlayer === done) activePlayer = null
            done.release()
        }
        player.setOnErrorListener { failed, _, _ ->
            if (activePlayer === failed) activePlayer = null
            failed.release()
            true
        }
        player.prepare()
        player.start()
    }.isSuccess

    if (!started) {
        runCatching { player.release() }
        return false
    }
    activePlayer = player
    return true
}

internal actual fun platformStopAudio() {
    val player = activePlayer ?: return
    activePlayer = null
    runCatching { player.stop() }
    runCatching { player.release() }
}
