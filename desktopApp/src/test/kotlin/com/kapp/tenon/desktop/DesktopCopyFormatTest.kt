// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [desktopFormat] 与 Compose Multiplatform 的 `stringResource(res, args)` **同一套替换规则**。
 *
 * 为什么要单独钉：纯 JVM 侧（文件对话框、自启注册、诊断包）拿不到 suspend 的
 * `getString`，只能先取模板、再用这里的替换。两套语义一旦分叉，症状是**只有桌面、
 * 只有参数化的那几句**出错 —— 比如 `%%` 多出一个百分号，或者位置参数没被替换。
 *
 * CMP 的实现是 `SimpleStringFormatRegex = Regex("%(\\d+)\\$[ds]")` + `replaceWithArgs`，
 * **不是** `String.format`。本仓在 KMP 文案上踩过这个坑（`%%` 不是转义；字面百分号
 * 就写一个 `%`），所以这里把三条最容易写错的判据钉住。
 */
class DesktopCopyFormatTest {

    @Test
    fun positionalStringAndIntAreReplaced() {
        assertEquals(
            "昵称 = 张三",
            desktopFormat("昵称 = %1\$s", "张三"),
        )
        assertEquals(
            "a.txt 等 3 个文件",
            desktopFormat("%1\$s 等 %2\$d 个文件", "a.txt", 3),
        )
    }

    @Test
    fun placeholderOrderFollowsTheIndexNotTheArgumentOrder() {
        assertEquals("B-A", desktopFormat("%2\$s-%1\$s", "A", "B"))
    }

    /** `%%` 不是转义：CMP 会原样显示两个百分号，这里必须与它一致。 */
    @Test
    fun doublePercentIsNotAnEscape() {
        assertEquals("100%% 完成", desktopFormat("100%% 完成"))
    }

    /** 字面百分号就写一个 `%`：CMP 的替换只认 `%N$s` / `%N$d`，裸 `%` 原样保留。 */
    @Test
    fun aBarePercentSurvivesUntouched() {
        assertEquals("100% done", desktopFormat("100% done"))
    }

    /** 越界的位置参数原样留着 —— 露出模板痕迹，也比悄悄吞掉一段文字好。 */
    @Test
    fun outOfRangeIndexIsLeftAsIs() {
        assertEquals("x = %2\$s", desktopFormat("x = %2\$s", "only-one"))
    }

    @Test
    fun templateWithoutPlaceholdersIsReturnedVerbatim() {
        assertEquals("已回到默认心跳频率", desktopFormat("已回到默认心跳频率"))
        assertEquals("", desktopFormat(""))
    }
}
