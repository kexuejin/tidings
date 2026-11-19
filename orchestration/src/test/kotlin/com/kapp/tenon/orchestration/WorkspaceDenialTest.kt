// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「派单落不到目录」的分类（方案 §3.5 的顺序：NO_DIRECTORY → DIR_NOT_ALLOWED）。
 *
 * 真机现场（P1b 第 6 条验收时发现）：单兵退单卡把「群没绑目录」与「目录没被授权」
 * 都写成了同一句话，卡面既没有分类码也没有「去授权」——用户看到的出路是
 * 「去选一个已经选过的目录」。这三条钉住两条成因不再合流。
 */
class WorkspaceDenialTest {

    @Test
    fun noCandidatePathMeansTheGroupHasNoDirectory() {
        assertEquals(WorkspaceDenial.NO_DIRECTORY, workspaceDenialOf(null, null))
    }

    @Test
    fun anExplicitWhitelistRefusalWinsOverThePathBeingFine() {
        assertEquals(WorkspaceDenial.DIR_NOT_ALLOWED, workspaceDenialOf("/tmp/repo", false))
    }

    @Test
    fun anAllowedPathIsNoDenialAtAll() {
        assertEquals(WorkspaceDenial.NONE, workspaceDenialOf("/tmp/repo", true))
    }

    @Test
    fun anUnassembledWhitelistDoesNotInventADenial() {
        // 白名单没装配（老装置）⇒ 不放宽也不加严：路径解析出来了就是解出来了。
        assertEquals(WorkspaceDenial.NONE, workspaceDenialOf("/tmp/repo", null))
    }
}
