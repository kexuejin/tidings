// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import androidx.compose.runtime.saveable.Saver

/**
 * 草稿表（会话键 → 草稿）的**唯一**存档契约：手机壳与桌面/平板壳共用这一个 Saver。
 *
 * ## 为什么它必须只有一处
 *
 * 两壳各自 `rememberSaveable` 一份草稿表，但它们存的是**同一种东西**，而存档格式是
 * 跨配置变更 / 进程重建的边界：两端各写一份编解码，一份存档被另一种读法读出来就是
 * 乱码或空表 —— 症状是"手机上转屏草稿还在、平板上转屏全丢"，或者更糟：草稿串到
 * 别的会话上。曾经这里有两个 `Saver` 实例（`App.kt` 一个、桌面壳一个），编解码已经
 * 共用、**声明仍有两处**；两份声明就是两个可以各自漂移的真相来源，所以收成一个。
 *
 * 位置选 `feature/chat` 而不是任一壳：草稿是**聊天**这件事的状态，两壳都只是它的宿主。
 * 放在 `App.kt` 会让桌面壳反向依赖手机壳所在的文件，放在桌面壳里同理。
 *
 * ## 格式
 *
 * Map 不在各平台 Bundle 校验的确定集合里，所以拆成一张定长表：
 * `[条数, k1, v1, k2, v2, …]`。键与正文都是非空 String（会话键是 nodeId / `group:<id>`，
 * 草稿就是要发的字），没有 null 哨兵问题 —— 编码风格与
 * [com.kapp.tidings.navigation.AppNavStateSaver] 一致，roundtrip 由 `commonTest` 盯住。
 *
 * ## 只活在 SavedState 里，不落盘
 *
 * 产品语义仍是"切页面不丢"：存档活的是转屏与系统回收进程这段窗口，用户主动划掉
 * 应用就该丢。
 */
internal fun draftsByConvToSaverValue(drafts: Map<String, String>): List<Any> =
    listOf(drafts.size) + drafts.flatMap { (key, text) -> listOf<Any>(key, text) }

/**
 * [draftsByConvToSaverValue] 的逆运算；格式对不上（项数错、类型错）返回 null，
 * 即「放弃恢复、草稿清空」—— 一份不合身的旧存档不值得崩在启动路径上。
 */
internal fun draftsByConvFromSaverValue(saved: List<*>): Map<String, String>? {
    val count = saved.firstOrNull() as? Int ?: return null
    if (saved.size - 1 != count * 2) return null
    val out = LinkedHashMap<String, String>(count)
    var i = 1
    while (i < saved.size) {
        val key = saved[i] as? String ?: return null
        val text = saved[i + 1] as? String ?: return null
        out[key] = text
        i += 2
    }
    return out
}

/**
 * 草稿表的 [Saver]，**全仓唯一**：`App.kt`（手机）与 `DesktopShell.kt`（桌面/平板）
 * 都引用这一个实例，谁都不许再声明第二个。
 *
 * ## 为什么不用 `autoSaver`
 *
 * `autoSaver` 会把 Map 原样塞进 Bundle，要求类型本身是 Parcelable / Serializable；
 * 而各平台对"Bundle 集合里允许放什么"的口径并不一致。显式逐字段映射让
 * 「存了什么、按什么顺序、坏档怎么办」成为一眼可见的契约 —— 改格式必须同时改
 * [draftsByConvToSaverValue] 与 [draftsByConvFromSaverValue]，roundtrip 测试会盯着。
 */
internal val DraftsByConvSaver: Saver<Map<String, String>, List<Any>> = Saver(
    save = { draftsByConvToSaverValue(it) },
    restore = { draftsByConvFromSaverValue(it) },
)
