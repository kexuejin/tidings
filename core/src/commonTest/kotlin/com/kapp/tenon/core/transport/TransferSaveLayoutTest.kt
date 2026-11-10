// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.transport

import com.kapp.tenon.core.fs.pathJoin
import com.kapp.tenon.core.testing.createTempDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 入站落盘布局：**单文件直落下载根**，只有真有结构的传输才建目录。
 *
 * ## 现场（2026-11-10，天气原型真机）
 *
 * `downloads/` 下长成三棵只装着自己的树：
 *
 * ```text
 * downloads/prototype.html/prototype.html        21356 B  上一轮的 tinct
 * downloads/prototype.html/prototype(1).html     16825 B  更早一轮
 * downloads/prototype.html/prototype(2).html     49934 B  本轮 Atmos
 * ```
 *
 * 根因是 `rootName` 兼职了两个角色：对**文件夹**它是目录名，对**单文件**它是文件名 ——
 * 而收端拿它无条件建目录，于是单文件被套进一个与自己同名的目录里。同名再传靠
 * `dedupeTargetPath` 递增 `(1)(2)`，于是「打开」永远指向无后缀那份（最早的一份），
 * 用户看到的是上一轮的产物。
 *
 * ## 判据
 *
 * - 单文件 ⇒ `saveDirFor` 返回下载根本身（不建目录）；
 * - 文件夹 / 多文件 / 带子目录的相对路径 ⇒ 仍然 `<downloadDir>/<rootName>/`。
 *
 * 反向验证：把 [transferNeedsFolder] 改成恒 `false` ⇒ 后两条红；改成恒 `true`
 * ⇒ 第一条红。两条都要有，否则"改宽了"和"改窄了"都可能是绿的。
 */
class TransferSaveLayoutTest {

    private fun newManager(scope: CoroutineScope, dir: String) = TransferManager(
        selfId = "self-node",
        scope = scope,
        tcpPort = 0,
        downloadDir = { dir },
        sendReliable = { _, _: JsonElement -> true },
        peerAddress = { null },
        peerSupportsDirect = { false },
    )

    private fun offer(
        rootName: String,
        vararg files: String,
        isFolder: Boolean = false,
    ) = IncomingOffer(
        transferId = "t-1",
        peerId = "peer-node",
        rootName = rootName,
        files = files.mapIndexed { index, path ->
            IncomingFilePlan(fileId = "f" + index, relPath = path, size = 10L)
        },
        totalSize = files.size * 10L,
        purpose = null,
        msgId = null,
        expiresAt = null,
        isFolder = isFolder,
    )

    @Test
    fun aSingleFileLandsDirectlyInTheDownloadRoot() = runTest {
        val dir = createTempDir("relay-layout-single")
        val manager = newManager(this, dir)
        assertEquals(
            dir,
            manager.saveDirFor(offer("prototype.html", "prototype.html")),
            "单文件不该被套进与自己同名的目录",
        )
    }

    @Test
    fun aFolderTransferStillGetsItsOwnRoot() = runTest {
        val dir = createTempDir("relay-layout-folder")
        val manager = newManager(this, dir)
        assertEquals(
            pathJoin(dir, "weather"),
            manager.saveDirFor(offer("weather", "weather/index.html", "weather/app.js", isFolder = true)),
            "文件夹传输的名字本来就是目录名",
        )
    }

    @Test
    fun severalFilesKeepOneRootSoTheyStayTogether() = runTest {
        val dir = createTempDir("relay-layout-multi")
        val manager = newManager(this, dir)
        assertEquals(
            pathJoin(dir, "a.png"),
            manager.saveDirFor(offer("a.png", "a.png", "b.png")),
            "一次发多个文件：平铺到下载根会把同名文件去重成 (1)(2)，一组文件散开",
        )
    }

    @Test
    fun aSingleFileWithSubdirectoriesStillNeedsTheRoot() = runTest {
        val dir = createTempDir("relay-layout-nested")
        val manager = newManager(this, dir)
        assertEquals(
            pathJoin(dir, "bundle"),
            manager.saveDirFor(offer("bundle", "bundle/sub/only.bin")),
            "relPath 带层级时必须有个根，否则相对路径直接落到下载根的地盘上",
        )
    }
}
