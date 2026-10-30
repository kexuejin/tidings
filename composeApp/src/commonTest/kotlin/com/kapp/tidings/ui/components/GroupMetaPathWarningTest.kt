// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 群简介里的本机路径提醒（M5 真机缺陷的回归判据）。
 *
 * 现场：群简介被填成了本机仓库目录，**原样同步给了全群** —— 对别人没有意义，
 * 还把 `/Volumes/…`、用户名、仓库名一起发了出去。这里不拦截（那是用户的内容），
 * 只在编辑时提醒一次；提醒的触发条件就是这条判据。
 *
 * 判据的关键不是「认得出路径」，而是**不误报**：一段正常的中文简介里出现
 * `2026/10/30`、`1/2` 这类斜杠，不该跳出一条「你泄露了路径」。
 */
class GroupMetaPathWarningTest {

    @Test
    fun absolutePosixPathsAreDetected() {
        // 真机现场的原值。
        assertTrue(looksLikeLocalPath("/Volumes/Kapp/source/teahouse-mobile"))
        assertTrue(looksLikeLocalPath("配置见 /Users/someone/work/app 里的说明"))
        assertTrue(looksLikeLocalPath("`/private/var/tmp/x`"))
    }

    @Test
    fun homeRelativeAndWindowsPathsAreDetected() {
        assertTrue(looksLikeLocalPath("~/projects/teahouse"))
        assertTrue(looksLikeLocalPath("""C:\Users\someone\work"""))
        assertTrue(looksLikeLocalPath("D:/work/repo"))
    }

    @Test
    fun datesAndRatiosAreNotPaths() {
        // 斜杠不等于路径：日期与比例都带斜杠，但它们不以分隔符开头。
        assertFalse(looksLikeLocalPath("2026/10/30 更新"))
        assertFalse(looksLikeLocalPath("今天 1/2 的人在线"))
        assertFalse(looksLikeLocalPath("支持 Android/iOS 两端"))
    }

    @Test
    fun ordinaryDescriptionsAndBlanksAreNotPaths() {
        assertFalse(looksLikeLocalPath("一起写代码的地方"))
        assertFalse(looksLikeLocalPath(""))
        assertFalse(looksLikeLocalPath("   "))
        // 单段 `/tmp` 不算：它短到不构成「目录结构泄露」，误报的代价比漏报大。
        assertFalse(looksLikeLocalPath("/tmp"))
    }
}
