// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

/** JVM / Android 的 K2 连接层：TLS + 双向 pin（JSSE / Conscrypt）。 */
actual fun createPlatformLinkTransport(
    identityDir: () -> String,
    addresses: () -> List<String>,
): LinkTransport = TlsLinkTransport(identityDir, addresses)
