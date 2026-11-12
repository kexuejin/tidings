// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「新建 / 编辑团队」对话框里那几条**纯判据**。
 *
 * 对话框本身不在这里测（它是一层布局），这里钉的是会真的改变写行为的两件事：
 *
 * 1. **已在的群不能再勾**：勾了就是一条重复席位（域层按 teamId 去重，但界面上
 *    「点了一下、什么都没变」正是 `DeadButtonGuardTest` 要拦的形态）；
 * 2. **编辑态允许一个群都不勾**：只改名不该被「至少选一个群」挡住 —— 那是新建的约束。
 */
class TeamProfileDialogTest {

    private val groups = listOf(
        TeamGroupOption(groupId = "g1", name = "研发群", joined = false),
        TeamGroupOption(groupId = "g2", name = "测试群", joined = true),
        TeamGroupOption(groupId = "g3", name = "发布群", joined = false),
    )

    @Test
    fun groupsThatAlreadyHaveTheTeamCannotBePickedAgain() {
        val addable = addableTeamGroups(groups)
        assertEquals(listOf("g1", "g3"), addable.map { it.groupId })
        assertTrue(addable.none { it.joined }, "「已在」的群不该出现在可勾列表里")
    }

    @Test
    fun everythingAlreadyJoinedLeavesNothingToPick() {
        // 全都在 ⇒ 空表 ⇒ 界面画「都已在群里」而不是一排勾不动的 chip。
        assertTrue(addableTeamGroups(groups.map { it.copy(joined = true) }).isEmpty())
    }
}
