// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

/**
 * 录制音频消息。
 */
public interface AudioRecording {
    /** 是否正在录音 */
    val isRecording: Boolean

    /**
     * 开始录音，录音输出至 [outputPath]（.m4a 或 .wav）。
     * 返回 true 表示启动成功，false 表示失败（未授予权限、无输入设备等）。
     */
    fun start(outputPath: String): Boolean

    /**
     * 停止录音。
     * 返回录音时长（毫秒），若录音失败则返回 0L。
     */
    fun stop(): Long

    /** 取消录音并丢弃中间文件。 */
    fun cancel()
}

public object RelayAudioRecording : AudioRecording {
    override val isRecording: Boolean
        get() = platformIsRecordingAudio()

    override fun start(outputPath: String): Boolean = platformStartAudioRecording(outputPath)

    override fun stop(): Long = platformStopAudioRecording()

    override fun cancel() = platformCancelAudioRecording()
}

internal expect fun platformIsRecordingAudio(): Boolean
internal expect fun platformStartAudioRecording(outputPath: String): Boolean
internal expect fun platformStopAudioRecording(): Long
internal expect fun platformCancelAudioRecording()
