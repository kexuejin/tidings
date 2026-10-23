// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.crypto.TlsPin

/**
 * F1：跨网文件拉取的**原始字节流拨号器**（task-39 分叉一(b)）。
 *
 * ## 为什么单独一层
 *
 * 跨网要的是「同一套 TLS 身份 + 双向 pin 之下的原始字节流」，而 [LinkTransport] 那条路
 * 交出来的是**分帧**的 [LinkConnection]（K1 长连接用）。把裸流塞进分帧就是协议变更（方案 A），
 * 设计文档已否。所以这里只做一件事：拨一条 TLS（或明文）的裸字节流。
 *
 * ## fail-closed
 *
 * iOS 的 TLS 实现（task-26 ①）落地前，[createCrossNetworkRawDialer] 返回的实例
 * `canDialTls = false`、`connect` 一律 null ⇒ 公网文件路径在那台设备上整条不可用，
 * **绝不回退明文**（task-13）。
 */
interface CrossNetworkRawDialer {

    /** 本平台能不能拨 TLS。false ⇒ 公网文件路径不可用（不回退明文）。 */
    val canDialTls: Boolean

    /**
     * 拨一条原始字节流连接；null = 拨不通（调用方按 socket-error 处理）。
     *
     * [expectedPin] 非空时必须校验对端证书的 SPKI 并 fail-closed。
     */
    suspend fun connect(host: String, port: Int, expectedPin: TlsPin?, timeoutMs: Long): RawPullConnection?
}

/**
 * 平台工厂。JVM/Android 复用 [TlsLinkTransport] 的同一套身份与 pin（分叉一(b)）；
 * iOS 在 task-26 ① 落地前返回不可用实现。
 */
expect fun createCrossNetworkRawDialer(
    identityDir: () -> String,
    addresses: () -> List<String>,
): CrossNetworkRawDialer
