// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 帧帽**只有一个定义点**：`Limits.MAX_FRAME_BYTES`（ARP-18④）。
 *
 * ## 为什么这条判据只能在源码层
 *
 * Kotlin 的 `const val` 在**编译期内联**：`Link.MAX_FRAME_BYTES` 与
 * `Limits.MAX_FRAME_BYTES` 在字节码里就是同一个字面量，运行期不存在「这个名字指向谁」
 * 这回事。所以「四处实现读的是同一个数」这件事，单测一条也看不见 —— 只能读源码。
 *
 * ## 为什么值得守
 *
 * 这条链上有**四条各自读帧**的实现：明文 `Link`、`TcpFramedChannel`、TLS 的
 * jvmShared 与 ios。它们原先各写一份 `64 * 1024`，改一处漏三处就会演成
 * 「TLS 上收得下、明文上判 peer-closed」这种只在某一条路上复现的断链 ——
 * 而症状是「对端莫名其妙掉线」，没人会想到去比五份常量。
 *
 * ## 判据的边界
 *
 * 只认名字 `MAX_FRAME` / `MAX_FRAME_BYTES`。`SecureChunkSender.CHUNK` 那些 64 KiB
 * 是**另外一档**（一次搬运多少字节），与帧帽无关，不在此列 —— 混进来只会逼人
 * 为了绿而去改语义不同的常量。
 */
class FrameCapSingleSourceTest {

    private val declaration = Regex("""const\s+val\s+(MAX_FRAME|MAX_FRAME_BYTES)\b([^\n]*)""")

    /** 定义点所在文件：唯一允许写数值字面量的地方。 */
    private val definitionFile = "core/src/commonMain/kotlin/com/kapp/tenon/core/protocol/Limits.kt"

    @Test
    fun everyFrameCapDeclarationPointsAtTheOneInLimits() {
        val seen = mutableListOf<String>()
        val offenders = mutableListOf<String>()
        for (relative in GuardSourceScan.kotlinFiles("core/src")) {
            val live = GuardSourceScan.stripComments(GuardSourceScan.read(relative))
            for (match in declaration.findAll(live)) {
                val initializer = match.groupValues[2].substringAfter('=', "").trim()
                seen += relative + " → " + initializer
                val expected = if (relative == definitionFile) "64 * 1024" else "Limits.MAX_FRAME_BYTES"
                if (initializer != expected) {
                    offenders += relative + "：" + match.value.trim() + "（应为 " + expected + "）"
                }
            }
        }
        // 五处 = Limits 自己 + 四条读帧实现（Link / TcpFramedChannel / TLS jvmShared / TLS ios）
        // + FileTransfer 的别名。数量对不上说明有人删了实现或改名了 —— 那要重看判据，
        // 而不是让「没有 offender」在空集合上恒真。
        assertTrue(
            seen.size >= 6,
            "帧帽声明只找到 " + seen.size + " 处（期望 ≥6）：" + seen + " —— 判据的锚已经失配",
        )
        assertEquals(
            emptyList(),
            offenders,
            "帧帽出现了第二个手抄来源。只剩一个定义点（Limits.MAX_FRAME_BYTES），" +
                "别处一律写成它的别名 —— 五份手抄漏改一处就是某条链路上的静默断链",
        )
    }
}
