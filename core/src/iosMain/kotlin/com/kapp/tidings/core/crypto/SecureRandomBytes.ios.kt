// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(size: Int): ByteArray {
    require(size >= 0) { "size must be >= 0" }
    val out = ByteArray(size)
    if (size == 0) return out
    out.usePinned { pinned ->
        val status = SecRandomCopyBytes(kSecRandomDefault, size.toULong(), pinned.addressOf(0).reinterpret<UByteVar>())
        check(status == 0) { "SecRandomCopyBytes failed: $status" }
    }
    return out
}
