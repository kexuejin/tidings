// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.persist

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import com.kapp.tenon.testsupport.GuardSourceScan

/**
 * ARP-9 的落盘侧判据：**写不进去必须说出来**。
 *
 * ## 为什么专门为这四行日志写测试
 *
 * 这四条 store（known-peer / endpoint / pin / tag）原来都是
 * `runCatching { … rewriteLog(…) }`，看起来"补个 `onFailure` 就有了诊断"。实际不是：
 * [`rewriteLog`] **没有失败通道** —— `openFresh` 拿不到句柄就 `return`，
 * `deleteFile` 的失败被自己吃掉。所以 `onFailure` 在磁盘满 / 目录只读 / 路径变成文件时
 * **永远不触发**（死分支）。真正的现场是：用户加了个节点、改了备注、信任了一台设备，
 * 重启之后全没了，而 `settings.json` / `peer-pins.json` 的修改时间停在几天前 ——
 * 连"曾经尝试写过"都没有痕迹。
 *
 * 所以判据落在 [`rewriteLog`] 内部那一行：它是唯一知道「哪个文件、丢了多少字节」的地方。
 * 第一条用例用**真实的失败条件**（父路径是一个文件）触发它，而不是 mock 一个异常。
 */
class PersistFailureVisibilityTest {

    /**
     * 造一个**真的写不进去**的路径：`<tmp>/blocked` 是一个普通文件，
     * 于是 `<tmp>/blocked/stores/peer-pins.json` 既 mkdirs 不出目录、也 open 不出来。
     */
    private fun blockedPath(tmp: File): String {
        val blocker = File(tmp, "blocked")
        assertTrue(blocker.createNewFile(), "测试前置：没能在临时目录里建出 blocker 文件")
        return File(blocker, "stores/peer-pins.json").path
    }

    @Test
    fun aFailedRewriteSaysWhichFileAndHowManyBytesWereLost() {
        val tmp = kotlin.io.path.createTempDirectory("persist-failure").toFile()
        val path = blockedPath(tmp)

        val original = System.out
        val captured = ByteArrayOutputStream()
        val store = FilePeerPinStore(path)
        try {
            System.setOut(PrintStream(captured, true, "UTF-8"))
            store.save(mapOf("node-1" to "pin-1"))
        } finally {
            System.setOut(original)
        }

        val printed = captured.toString("UTF-8")
        assertTrue(
            printed.contains("[persist] rewrite failed: cannot open "),
            "写失败必须留一行诊断（这里以前是完全静默的）；实际打印：" + printed,
        )
        assertTrue(printed.contains(path), "诊断里必须带上**哪个文件** —— 不然四个 store 分不清：" + printed)
        assertTrue(printed.contains(" B dropped)"), "诊断里必须带上丢了多少字节：" + printed)

        // 降级语义不变：写不进去仍然不抛给调用方，内存里那份照旧可用（下一次重启才会丢）。
        // 这里断言的是**文件确实没写出来** —— 那才是「重启后没了」的真凭据；
        // `load()` 返回的是内存里的 `current`，它在失败后依然是新值。
        assertEquals(mapOf("node-1" to "pin-1"), store.load(), "内存里的那份不该因为写失败而回滚")
        assertTrue(!java.io.File(path).exists(), "测试前置失效：blocker 之下不该真的生出文件")
    }

    /**
     * 四个 store 的 `persist()` 是同一条纪律的四份副本 —— 只修一处等于留三个缺口。
     *
     * 这条也在源码上守（读整包而不是某一个文件，见 AGENTS §4）：`onFailure` 被谁
     * 顺手删掉时，上面那条用例只覆盖 `FilePeerPinStore`，另外三个不会红。
     */
    @Test
    fun everyPeerStorePersistReportsTheFailure() {
        val names = listOf(
            "PeerPinFileStore.kt",
            "KnownPeerFileStore.kt",
            "PeerEndpointFileStore.kt",
            "PeerTagFileStore.kt",
        )
        val missing = names.filter { name ->
            !live("core/src/commonMain/kotlin/com/kapp/tenon/core/persist/" + name)
                .contains("reportPersistFailure(path, t)")
        }
        assertEquals(emptyList(), missing, "这些 store 的 persist() 又把失败吞了：" + missing.joinToString("、"))
    }

    /**
     * 剥注释再匹配：这几个文件的 KDoc 里到处写着 `onFailure` / `runCatching` 的解释。
     *
     * 找源码用 [GuardSourceScan]（仓内源码守卫的公共工具）而不是自己从 `user.dir`
     * 往上摸 —— 自带的那份会被 `envDependentTestGate` 判为 A 类（测试结论取决于
     * 这台机器的工作目录），而公共工具是既有守卫同一条被放行的定位路径。
     */
    private fun live(relativePath: String): String =
        GuardSourceScan.stripComments(GuardSourceScan.read(relativePath))
}
