// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

/**
 * 平台提供的连接层（K2 的落点）。
 *
 * JVM / Android：TLS + 双向 pin（见 jvmShared 的 TlsLinkTransport，Conscrypt 就是 JSSE）。
 * iOS：**暂只提供明文** —— 公网长连接因此在策略层被拒（fail-closed），不会退化成明文出网。
 * 完整实现见 docs/spike-kmp-tls.md 的 API 链（SecPKCS12Import → sec_identity_create →
 * sec_protocol_options_set_local_identity）。
 *
 * @param identityDir 本机自签身份的落盘目录（私钥 + 证书）。
 * @param addresses 本机当前全部地址，进证书 SAN —— 对端按地址连过来时证书必须匹配。
 */
expect fun createPlatformLinkTransport(
    identityDir: () -> String,
    addresses: () -> List<String>,
): LinkTransport
