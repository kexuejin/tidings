// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.protocol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 免确认接收（自动接收）的判据。
 *
 * 为什么单独钉这一组：这个判据**同时决定两件事** —— 收端收不收、界面画不画
 * 「接收」按钮。两者一旦分叉，用户看到的就是「文件已经收下来了，卡片还在等你点接收」
 * （2026-10-27 iOS 模拟器实测到过）。所以它必须是单一权威源，且被单独测住。
 */
class FileAutoAcceptTest {

    @Test
    fun aSmallPrivateImageIsAutoAccepted() {
        assertTrue(autoAcceptsFile("image", 1_048_576L, isGroup = false))
    }

    @Test
    fun aPrivateImageAtTheLimitIsStillAccepted() {
        // 边界是"≤"：正好 20MB 应当自动收，差一个字节也一样。
        assertTrue(autoAcceptsFile("image", FileTransfer.IMG_AUTO_ACCEPT, isGroup = false))
    }

    @Test
    fun aPrivateImageOverTheLimitWaitsForTheUser() {
        assertFalse(autoAcceptsFile("image", FileTransfer.IMG_AUTO_ACCEPT + 1, isGroup = false))
    }

    @Test
    fun aGroupImageUsesTheStricterLimit() {
        // 群图上限是私聊的一半：15MB 在私聊里自动收，在群里必须等用户点头。
        val fifteenMb = 15L * 1024 * 1024
        assertTrue(autoAcceptsFile("image", fifteenMb, isGroup = false))
        assertFalse(
            autoAcceptsFile("image", fifteenMb, isGroup = true),
            "群图超 10MB 必须等用户确认（协议 §3 的内联上限减半）",
        )
    }

    @Test
    fun nonImagesAlwaysWaitForTheUser() {
        // 这是安全相关的：不能因为"文件小"就替用户决定收下可执行文件/文档。
        assertFalse(autoAcceptsFile(null, 1024L, isGroup = false))
        assertFalse(autoAcceptsFile("file", 1024L, isGroup = false))
        assertFalse(autoAcceptsFile("update", 1024L, isGroup = false))
    }
}
