// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.core.protocol.FilePurpose
import kotlin.test.Test
import kotlin.test.assertEquals
import app.relay.app.model.FileCardUi

/**
 * [mediaKindOf] 的判据。
 *
 * 这些用例钉住的是**两路判据的优先级**，不是扩展名清单本身：
 * 声明（`purpose`）压过猜测（扩展名），两个方向都要成立。
 */
class MediaKindTest {
    private fun card(name: String, purpose: String? = null): FileCardUi =
        FileCardUi(transferId = "t-1", name = name, size = 1L, purpose = purpose)

    @Test
    fun declaredImagePurposeBeatsAMisleadingExtension() {
        // 对端说这是图，名字叫什么都不能改判 —— 协议字段是明确的声明，扩展名是猜的。
        assertEquals(MediaKind.Image, mediaKindOf(card("scan.bin", FilePurpose.IMAGE)))
    }

    @Test
    fun declaredStickerIsAnImage() {
        assertEquals(MediaKind.Image, mediaKindOf(card("no-extension", FilePurpose.STICKER)))
    }

    @Test
    fun aDeclaredNonMediaPurposeBeatsAnImageExtension() {
        // 共享文件柜里的图不是聊天媒体：给它画缩略图是过度解读对端的用途声明。
        assertEquals(MediaKind.Other, mediaKindOf(card("chart.png", FilePurpose.SHARE_PUT)))
        assertEquals(MediaKind.Other, mediaKindOf(card("photo.jpg", FilePurpose.UPDATE)))
    }

    @Test
    fun anInventedPurposeDoesNotSuppressTheExtension() {
        // 2026-10-07 真机验出来的：控制面对 `purpose` 不做校验，对端塞什么它都原样转发。
        // 第一版把「非空且不是媒体用途」一律当成「对端声明了这不是媒体」，于是
        // 带 `purpose: "share"` 发来的 `.m4a` 就再也拿不到「播放」按钮。
        // 一个协议里根本不存在的值不该有权力压掉本端的判断。
        assertEquals(MediaKind.Audio, mediaKindOf(card("voice.m4a", "share")))
        assertEquals(MediaKind.Image, mediaKindOf(card("shot.png", "whatever-the-peer-invented")))
    }

    @Test
    fun imageExtensionsAreRecognisedCaseInsensitively() {
        // 本端 offerFiles 从不带 purpose，所以自己发的图只能靠扩展名命中。
        assertEquals(MediaKind.Image, mediaKindOf(card("IMG_0042.PNG")))
        assertEquals(MediaKind.Image, mediaKindOf(card("photo.jpeg")))
        assertEquals(MediaKind.Image, mediaKindOf(card("anim.gif")))
        assertEquals(MediaKind.Image, mediaKindOf(card("shot.HEIC")))
    }

    @Test
    fun onlyTheLastDotCounts() {
        assertEquals(MediaKind.Image, mediaKindOf(card("holiday.2026.07.jpeg")))
    }

    @Test
    fun audioExtensionsAreRecognised() {
        // 协议里没有 voice/audio 用途，语音就是普通音频文件 —— 这条路只能靠扩展名。
        assertEquals(MediaKind.Audio, mediaKindOf(card("voice.m4a")))
        assertEquals(MediaKind.Audio, mediaKindOf(card("note.MP3")))
        assertEquals(MediaKind.Audio, mediaKindOf(card("clip.opus")))
        assertEquals(MediaKind.Audio, mediaKindOf(card("rec.wav")))
    }

    @Test
    fun everythingElseStaysAPlainFileCard() {
        assertEquals(MediaKind.Other, mediaKindOf(card("report.pdf")))
        assertEquals(MediaKind.Other, mediaKindOf(card("archive.tar.gz")))
        assertEquals(MediaKind.Other, mediaKindOf(card("README")))
        // 一个后缀是 .png 的文本文件会被判成 Image，然后解码失败退回文件卡 ——
        // 这是有意的：这里判错的代价只是白读一次文件，而在渲染里预读内容会把
        // 每一次组合都变成一次磁盘 I/O。
        assertEquals(MediaKind.Image, mediaKindOf(card("lies.png")))
    }
}
