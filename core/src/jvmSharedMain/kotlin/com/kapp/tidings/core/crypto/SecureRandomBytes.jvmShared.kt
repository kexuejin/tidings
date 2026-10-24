// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.crypto

import java.security.SecureRandom

private val secureRandom = SecureRandom()

actual fun secureRandomBytes(size: Int): ByteArray {
    require(size >= 0) { "size must be >= 0" }
    return ByteArray(size).also { secureRandom.nextBytes(it) }
}
