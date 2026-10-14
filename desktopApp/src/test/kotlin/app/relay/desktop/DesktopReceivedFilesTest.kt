// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package app.relay.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 「打开下载目录」指向的目录必须与文件真正落盘的目录同源（审计 P1-9）。
 *
 * 这里钉的是路径推导本身。[openDownloadsFolder] 只做「建目录 + 交给 AWT `Desktop.open`」，
 * 后一步要有图形环境才能验证；离屏环境里能证明的是「按钮算出来的路径 = 写入方用的路径」，
 * 以及它**不是**以前那个 `~/Downloads/捎话`。
 *
 * 以前这里还钉着一个零调用点的 `DesktopDownloads`（落盘 + 同名改名 + sha256 旁车）和它
 * 默认参数用的 `desktopDownloadsDirectory()`。收件全在 core 里落盘，桌面侧没有第二个写入方，
 * 于是两者一起撤下（审计「仍待产品裁定的四项」第 ③ 项）：留着的死代码看起来像一条能走的路，
 * 而它的默认目录正是 P1-9 那个错目录。需要时从 git 历史取回。
 */
class DesktopReceivedFilesTest {

    private val dataDir = "/Users/x/Library/Application Support/捎话 Tidings"

    @Test
    fun 收件目录从dataDirectory推导() {
        assertEquals("$dataDir/downloads", desktopReceivedFilesDirectory(dataDir))
    }

    @Test
    fun 末尾带斜杠的dataDirectory不会拼出双斜杠() {
        // pathJoin 折掉重复分隔符。不折的话 macOS 上照样能打开，Windows 上就是另一个路径 ——
        // 这类差异只在打包后的机器上暴露，所以在这里钉住。
        assertEquals("/data/downloads", desktopReceivedFilesDirectory("/data/"))
    }

    @Test
    fun 收件目录不是以前那个Downloads字面量() {
        val real = desktopReceivedFilesDirectory(dataDir)
        // P1-9 的原始形态：按钮开 ~/Downloads/捎话，文件却在 data 目录下，用户点开只看到空目录。
        // 这个字面量在这里写死：产出它的函数已随 DesktopDownloads 一起撤下，没有别处再引用它。
        assertNotEquals("/Users/x/Downloads/捎话", real)
        assertTrue(real.startsWith(dataDir), "收件目录必须在 dataDirectory 里面：$real")
        assertFalse(real.contains("/Downloads/"), "收件目录不该再指向用户的 Downloads：$real")
    }
}
