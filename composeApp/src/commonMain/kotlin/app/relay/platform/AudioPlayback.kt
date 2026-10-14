// SPDX-License-Identifier: GPL-3.0-only

package app.relay.platform

/**
 * 播放聊天里收到的音频文件。
 *
 * **为什么只有一个「播放」动作，没有「停止」，也不显示任何播放状态：**
 * 界面拿不到「播完了」这件事。三个平台的播放器都能报「开始失败」，但没有一个能报
 * 「已经播完」——`AVAudioPlayer`/`MediaPlayer`/`Clip` 各有各的完成回调，而本工程的
 * 平台接口层不转发它们。做一个点了之后永远停在「正在播放」的切换按钮，正是这个仓库
 * 点名过的「界面状态过期没自愈」缺陷。所以按钮的语义就是「从头播一遍」：
 * 再点一次 = 重播，界面不画任何状态。这与「已存到」那类只讲事实的文案是同一条纪律。
 *
 * **为什么不直接用 kapp-lib 的 `com.kapp:audio`：** 它的 `AudioPlayer.playFile` 返回
 * Unit，且 iOS 实现是 `AVAudioPlayer(contentsOfURL:error:null)?.play()` —— 构造失败时
 * 返回 null、`?.` 直接吞掉，调用方分不清「正在播」和「这个格式这台设备放不了」。
 * 那正是本仓记过的「调用了但把结果静默丢掉」。我们只要一个布尔值，三个平台各三行就够，
 * 不必为此让这个原本零第三方依赖的工程多出一整套 moko-permissions。
 */
public interface AudioPlayback {
    /**
     * 开始播放 [path]（会先掐掉上一条）。返回 false 表示这台设备**现在**放不了它：
     * 格式不支持（桌面只有 WAV/AIFF/AU）、没有声音设备、或者文件已经不在了。
     *
     * 实现不许抛异常 —— 与 [ReceivedFileActions] 同一条契约。
     */
    fun play(path: String): Boolean

    /** 停掉当前播放。没有在播就什么都不做。 */
    fun stop()
}

/**
 * 进程内共用一个播放器：聊天里同时只该响一条，第二条按下去就该把第一条掐掉。
 */
public object RelayAudioPlayback : AudioPlayback {
    override fun play(path: String): Boolean = platformPlayAudioFile(path)

    override fun stop() = platformStopAudio()
}

/** 开始播放。false = 放不了。实现不许抛异常。 */
internal expect fun platformPlayAudioFile(path: String): Boolean

/** 停掉当前播放；没有在播就什么都不做。实现不许抛异常。 */
internal expect fun platformStopAudio()
