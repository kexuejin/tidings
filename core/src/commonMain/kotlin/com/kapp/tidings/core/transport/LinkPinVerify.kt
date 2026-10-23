// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.crypto.TlsPin

/**
 * 对端证书的 SPKI 是否满足期望的 pin —— **双向固定的判据本体**（task-26 第 4 步）。
 *
 * 为什么抽成纯函数：这段判断要在两处跑（iOS 的 Network.framework verify block、JVM 的
 * X509TrustManager 之后的比对），而它又是**安全边界**（判错了就是"随便谁都能连"）。
 * 抽出来之后：verify block 调它，单测直接钉它 —— 一份逻辑，两处不漂移。
 *
 * fail-closed 的三条：
 *  · 对端没给证书（`peerSpki == null`）⇒ 拒；
 *  · 我们没有期望的 pin（`expectedPin == null`）⇒ 拒（"没验"不等于"验过"）；
 *  · pin 不一致 ⇒ 拒。
 */
internal fun peerPinAccepted(peerSpki: ByteArray?, expectedPin: TlsPin?): Boolean {
    if (expectedPin == null) return false
    val spki = peerSpki ?: return false
    return runCatching { TlsPin.ofSpki(spki) }.getOrNull()?.wire == expectedPin.wire
}
