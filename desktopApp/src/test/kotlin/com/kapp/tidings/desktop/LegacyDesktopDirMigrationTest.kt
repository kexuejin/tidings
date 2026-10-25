// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 改名迁移的判据是「文件跟着目录走、内容一个字节不动」：迁移失败最贵的形态不是
 * 抛异常，而是**静默当成全新安装**（空库、新 nodeId），所以断言必须落在
 * 「relay.db 出现在新目录且内容不变」这种能区分两种结局的量上，而不是「新目录存在」。
 */
class LegacyDesktopDirMigrationTest {
    private fun tempHome(): File = Files.createTempDirectory("tidings-home").toFile()

    @Test
    fun `老数据目录整体搬到新名字且内容不变`() {
        val home = tempHome()
        val dataBase = File(home, "Library/Application Support").apply { mkdirs() }
        val legacyData = File(dataBase, LEGACY_DESKTOP_APP_DIR).apply { mkdirs() }
        File(legacyData, "relay.db").writeText("the-db")
        File(legacyData, "stores").mkdirs()
        File(legacyData, "stores/known-peers.json").writeText("peers")
        val cacheBase = File(home, "Library/Caches").apply { mkdirs() }
        File(cacheBase, LEGACY_DESKTOP_APP_DIR).mkdirs()

        val migrated = migrateLegacyDesktopDirs(osName = "Mac OS X", home = home.absolutePath)

        assertEquals(listOf(dataBase.absolutePath, cacheBase.absolutePath), migrated)
        val movedDb = File(dataBase, "$DESKTOP_APP_DIR/relay.db")
        assertTrue(movedDb.isFile, "relay.db 必须跟着目录搬到新名字下")
        assertEquals("the-db", movedDb.readText(), "搬的是整个目录，内容不许被重写")
        assertEquals(
            "peers",
            File(dataBase, "$DESKTOP_APP_DIR/stores/known-peers.json").readText(),
        )
        assertFalse(File(dataBase, LEGACY_DESKTOP_APP_DIR).exists(), "老目录搬走后不应残留")
    }

    @Test
    fun `新目录已存在时不迁移不合并`() {
        val home = tempHome()
        val dataBase = File(home, "Library/Application Support").apply { mkdirs() }
        val legacyData = File(dataBase, LEGACY_DESKTOP_APP_DIR).apply { mkdirs() }
        File(legacyData, "relay.db").writeText("old-db")
        File(dataBase, DESKTOP_APP_DIR).mkdirs()
        File(dataBase, "$DESKTOP_APP_DIR/relay.db").writeText("new-db")

        val migrated = migrateLegacyDesktopDirs(osName = "Mac OS X", home = home.absolutePath)

        assertEquals(emptyList(), migrated, "半迁移状态程序不自作主张")
        assertEquals(
            "old-db",
            File(dataBase, "$LEGACY_DESKTOP_APP_DIR/relay.db").readText(),
            "两边都有时老目录原样保留，交给用户处置",
        )
    }

    @Test
    fun `没有旧目录时是空操作`() {
        val home = tempHome()
        File(home, "Library/Application Support").mkdirs()

        val migrated = migrateLegacyDesktopDirs(osName = "Mac OS X", home = home.absolutePath)

        assertEquals(emptyList(), migrated)
        assertFalse(File(home, "Library/Application Support/$DESKTOP_APP_DIR").exists())
    }

    @Test
    fun `Linux 走 XDG 两级目录`() {
        val home = tempHome()
        val dataBase = File(home, "xdg-data").apply { mkdirs() }
        val legacyData = File(dataBase, LEGACY_DESKTOP_APP_DIR).apply { mkdirs() }
        File(legacyData, "relay.db").writeText("db")
        File(home, "xdg-cache").mkdirs()

        val migrated = migrateLegacyDesktopDirs(
            osName = "Linux",
            home = home.absolutePath,
            dataHome = dataBase.absolutePath,
            cacheHome = File(home, "xdg-cache").absolutePath,
        )

        assertEquals(listOf(dataBase.absolutePath), migrated, "只有数据目录有旧名，缓存没有就不搬")
        assertTrue(File(dataBase, "$DESKTOP_APP_DIR/relay.db").isFile)
    }
}
