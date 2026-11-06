// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

import com.kapp.tenon.desktop.testutil.RepoSource
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 守卫桌面文件选择器实现 [com.kapp.tenon.platform.FilePicker.pickMultiple]。
 */
class DesktopFilePickerContractTest {

    @Test
    fun desktopFilePickerOverridesPickMultipleViaDesktopDialogs() {
        val src = RepoSource.read("desktopApp/src/main/kotlin/com/kapp/tenon/desktop/DesktopPlatform.kt")
        assertTrue(
            src.contains("override suspend fun pickMultiple()"),
            "DesktopFilePicker 未覆盖 pickMultiple 契约",
        )
        assertTrue(
            src.contains("DesktopDialogs.chooseFiles(parent())"),
            "DesktopFilePicker.pickMultiple 未调用 DesktopDialogs.chooseFiles",
        )
    }
}
