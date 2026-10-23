// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.protocol.FilePurpose
import com.kapp.tidings.domain.model.FileCardUi

/**
 * 一条文件卡在聊天里怎么呈现。
 *
 * **对外契约**：`feature/chat/ChatBubbles` 的活代码按它分支画缩略图与「播放」按钮。
 * 「文件卡长什么样」是领域事实、由 UI 消费，所以这条可见性必须跨包成立 ——
 * 按模块拆开后，声明与调用点不再同模块，`internal` 会在**每个调用点**报错。
 */
enum class MediaKind {
    /** 画缩略图。 */
    Image,

    /** 给一个「播放」按钮。 */
    Audio,

    /** 就是一张文件卡，什么都不多画。 */
    Other,
}

/**
 * 这条文件卡能不能内联预览，以及预览成什么。
 *
 * 判据分两路，**缺一不可**：
 * 1. 协议侧 [FilePurpose.MEDIA]（`image` / `sticker`）是对端**明确声明**的媒体用途；
 * 2. 文件扩展名是给**本端发出去的**文件兜底 —— 本客户端调
 *    `rt.offerFiles(peerId, files, msgId = msgId)` 时从不带 `purpose`，
 *    所以只看用途的话，自己发的图永远不命中。
 *
 * 声明优先于猜测，两个方向都成立：用途是 `image` 就按图片算（哪怕叫 `.bin`），
 * 用途是 `share-put` 那种**协议里已知的**非媒体用途就按普通文件算（哪怕叫 `.png`）——
 * 共享文件柜里的图不是聊天媒体，给它在气泡里画缩略图是过度解读。
 *
 * 「已知的」真机验出来的：第一版写的是「非空且不是媒体用途
 * 就按普通文件算」，于是对端只要带一个协议里根本不存在的用途字符串（实测用
 * `purpose: "share"` 发一个 `.m4a`，控制面照单全收并原样转发），语音卡片就再也
 * 拿不到「播放」按钮了 —— 一个**对端自造的值**不该有权力压掉本端的判断。
 * 现在只有 `FilePurpose.ALL` 里那几个真的用途才算声明，其余（含 null）按扩展名判。
 *
 * 协议里**没有** voice/audio 用途（`core/protocol` 全仓没有语音类型），
 * 语音就是普通音频文件，所以音频这一路只能靠扩展名。
 *
 * 只按名字判、不看内容：一个后缀是 `.png` 的文本文件会被判成 [MediaKind.Image]，
 * 然后解码失败、退回普通文件卡 —— 那是解码器该管的事。这里判错的代价只是白读一次文件，
 * 而在这里预读内容会把每次渲染都变成一次磁盘 I/O。
 *
 * **对外契约**：`feature/chat/ChatBubbles` 用它在气泡里选图标、决定走哪条内联预览分支。
 * 判据只能有一份 —— UI 侧各自再写一遍后缀表，就会漂成两套互相矛盾的「什么算图」。
 */
fun mediaKindOf(card: FileCardUi): MediaKind {
    val purpose = card.purpose
    if (purpose == FilePurpose.IMAGE || purpose == FilePurpose.STICKER) return MediaKind.Image
    // 只认协议里真的存在的用途；对端自造的值当作没说。
    if (purpose != null && purpose in FilePurpose.ALL) return MediaKind.Other

    val extension = card.name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when (extension) {
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif" -> MediaKind.Image
        "m4a", "aac", "mp3", "ogg", "oga", "opus", "wav", "amr", "flac", "caf" -> MediaKind.Audio
        else -> MediaKind.Other
    }
}
