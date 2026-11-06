// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

import com.kapp.tenon.core.crypto.ArtifactVerification
import com.kapp.tenon.core.crypto.sha256Hex
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DesktopAppPlatform.artifactDigestOf] 的行为单测（M7-T3 2b）：
 * 按文件名在下载目录实测 SHA-256 —— 找得到 ⇒ 摘要正确（与一次性 sha256Hex 对拍）；
 * 找不到 ⇒ null（FILE_MISSING 语义由 core 判定，这里只钉「不编一个值」）。
 * 判据消费端（ArtifactVerification.verify）已在 core 测过，这里钉的是**读盘桥**本身。
 */
class DesktopArtifactDigestBridgeTest {

    private val dir: File = kotlin.io.path.createTempDirectory("relay-digest-test-").toFile()
    private val platform = DesktopAppPlatform(dataDirectory = DesktopDataDirectory(dataPath = dir.absolutePath))

    @Test
    fun digestOfAnExistingFileMatchesTheOneShotDigest() = kotlinx.coroutines.runBlocking {
        val payload = "relay artifact digest probe".encodeToByteArray()
        File(dir, "downloads").mkdirs()
        File(dir, "downloads/report.txt").writeBytes(payload)
        val actual = platform.artifactDigestOf("report.txt")
        assertEquals(sha256Hex(payload), actual, "实测摘要必须与一次性摘要一致")
        assertEquals(
            ArtifactVerification.Status.OK,
            ArtifactVerification.verify(sha256Hex(payload), actual),
        )
    }

    @Test
    fun missingFileYieldsNullInsteadOfInventingADigest() = kotlinx.coroutines.runBlocking {
        File(dir, "downloads").mkdirs()
        assertNull(
            platform.artifactDigestOf("not-downloaded.txt"),
            "文件不在就如实 null —— 编一个值会把校验状态装成已校验",
        )
        assertEquals(
            ArtifactVerification.Status.FILE_MISSING,
            ArtifactVerification.verify("a".repeat(64), platform.artifactDigestOf("not-downloaded.txt")),
        )
    }

    @Test
    fun largerThanOneChunkFileStillVerifies() = kotlinx.coroutines.runBlocking {
        // 流式读盘判据：64 KiB chunk × 3 以上的内容（老实现若 readBytes 全量读也不会错，
        // 但这条钉住流式路径没有丢块 —— 摘要与一次性对拍）。
        File(dir, "downloads").mkdirs()
        val chunk = ByteArray(64 * 1024) { it.toByte() }
        val payload = chunk + chunk + chunk + byteArrayOf(0x42)
        File(dir, "downloads/big.bin").writeBytes(payload)
        val actual = platform.artifactDigestOf("big.bin")
        assertEquals(sha256Hex(payload), actual, "跨 chunk 的内容摘要必须一致（流式没有丢块）")
        assertTrue(actual!!.length == 64)
    }
}
