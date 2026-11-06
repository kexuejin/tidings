// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 窗口几何持久化（整改方案 N5）：window.json 的读写判据。
 *
 * ## 守的是什么
 *
 * 默认 1100×720 每次「重启即丢」，用户每次都要把窗口拖回自己习惯的大小/位置。
 * 与 ui-prefs.properties 同一条纪律：读不出来（首次启动/损坏/只读）退回默认，
 * **不能让应用起不来**。
 *
 * 纯函数判据（序列化 ⇄ 反序列化、缺字段兜底、损坏文件兜底）在这里测；
 * 「窗口真的恢复了几何」是 AWT 行为，属真机取证项（多显示器恢复按 2026-10-04 §7
 * 待实测清单记账跳过），本地以单显示器「重启后尺寸/位置恢复」为验收口径。
 */
class DesktopWindowPrefsTest {

    @Test
    fun roundTripsGeometryThroughJson() {
        val dir = File(System.getProperty("java.io.tmpdir"), "tenon-winprefs-" + System.nanoTime())
        dir.mkdirs()
        try {
            DesktopWindowPrefs.save(
                dir.absolutePath,
                DesktopWindowPrefs.Geometry(x = 80, y = 120, width = 1440, height = 900, isMaximized = false),
            )
            assertEquals(
                DesktopWindowPrefs.Geometry(80, 120, 1440, 900, false),
                DesktopWindowPrefs.load(dir.absolutePath),
                "写进什么读出什么 —— 序列化不能丢字段",
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun missingFileFallsBackToNull() {
        val dir = File(System.getProperty("java.io.tmpdir"), "tenon-winprefs-absent-" + System.nanoTime())
        assertNull(DesktopWindowPrefs.load(dir.absolutePath), "首次启动没有 window.json —— 返回 null 走默认尺寸")
    }

    @Test
    fun corruptFileFallsBackToNullNotCrash() {
        val dir = File(System.getProperty("java.io.tmpdir"), "tenon-winprefs-bad-" + System.nanoTime())
        dir.mkdirs()
        try {
            File(dir, "window.json").writeText("{ not json at all ")
            assertNull(DesktopWindowPrefs.load(dir.absolutePath), "文件损坏退回默认，不能让启动崩")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun missingFieldsFallBackPerKey() {
        val dir = File(System.getProperty("java.io.tmpdir"), "tenon-winprefs-partial-" + System.nanoTime())
        dir.mkdirs()
        try {
            File(dir, "window.json").writeText("""{"x": 40, "width": 1280}""")
            assertNull(DesktopWindowPrefs.load(dir.absolutePath), "缺 y/height 的几何不完整 —— 整条不用，不猜值")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun maximizedFlagSurvivesWithoutPixelGuessing() {
        val dir = File(System.getProperty("java.io.tmpdir"), "tenon-winprefs-max-" + System.nanoTime())
        dir.mkdirs()
        try {
            DesktopWindowPrefs.save(
                dir.absolutePath,
                DesktopWindowPrefs.Geometry(x = 0, y = 0, width = 800, height = 600, isMaximized = true),
            )
            val loaded = DesktopWindowPrefs.load(dir.absolutePath)
            assertTrue(loaded?.isMaximized == true, "最大化态单独成键，不靠像素猜")
        } finally {
            dir.deleteRecursively()
        }
    }
}
