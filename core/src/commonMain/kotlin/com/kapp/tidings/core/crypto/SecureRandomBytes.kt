// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.crypto

/** 密码学安全随机字节（恢复包的 salt / nonce 生成）。平台实现：JVM SecureRandom、iOS SecRandomCopyBytes。 */
expect fun secureRandomBytes(size: Int): ByteArray
