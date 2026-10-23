// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

/**
 * 投屏帧源的「静止重发」包装。
 *
 * 为什么需要它：[ScreenSender] 的契约是 `sample` 返回 null ⇒ 会话以 `capture-ended` 结束。
 * 这对桌面（`Robot.createScreenCapture` 是**拉式**，永远给出新缓冲）成立，
 * 但对手机不成立：MediaProjection 的 `ImageReader.acquireLatestImage()` 与
 * ReplayKit 广播扩展的帧槽都是**推式**的 —— 画面静止时它们就是没有新帧，
 * 而不是采集坏了。没有这层包装，手机投屏的表现是「屏幕一停 100 毫秒就断线」。
 *
 * 语义：
 * - 采到新帧 → 记为缓存并返回；
 * - 采不到新帧 → 重发上一帧（静止画面在观看端看起来仍是"活的"，因为 seq 在前进）；
 * - 一帧都没采到过 → 返回 null，让会话照原契约失败关闭（同意了但采集根本没起来，
 *   不能假装一切正常地挂着一个空会话）；
 * - [markDead] 之后 → 恒 null。采集**外部终止**（用户在系统通知里点了"停止投射"、
 *   广播扩展被系统回收）不能伪装成"这一拍没新帧"——那是缓存会吞掉的信号，
 *   所以平台侧停止时先 [markDead] 再触发会话结束回调。
 */
class ScreenFrameSource(
    private val capture: suspend (seq: Long) -> ByteArray?,
) {
    private var last: ByteArray? = null
    private var dead = false

    /** 采集被外部终止。之后 [sample] 恒返回 null（→ 会话 `capture-ended`）。 */
    fun markDead() {
        dead = true
    }

    /** 采集器抛异常按"这一拍没新帧"处理（重发缓存），而不是拖垮整条泵。 */
    suspend fun sample(seq: Long): ByteArray? {
        if (dead) return null
        val fresh = runCatching { capture(seq) }.getOrNull()
        if (fresh != null && fresh.isNotEmpty()) {
            last = fresh
            return fresh
        }
        return last
    }
}
