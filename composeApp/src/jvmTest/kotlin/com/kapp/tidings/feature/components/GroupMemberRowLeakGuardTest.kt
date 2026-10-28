// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.components

import com.kapp.tidings.testsupport.GuardSourceScan

import com.kapp.tidings.domain.model.PeerUi
import com.kapp.tidings.ui.components.memberOrgPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 守卫用户点名的这一处：群成员行不再常显完整 UUID。
 *
 * 为什么需要源码级守卫：成员行在 Composable 内部，本仓没有 Compose UI 测试基建，
 * 单测只能覆盖抽出来的纯函数；「副行画的是组织路径而不是 id」只能靠源码文本钉住。
 * 守卫剥掉注释再匹配，否则它会被自己的说明绊倒（本仓记录过的假绿形态）。
 *
 * 反向验证：把副行改回 `text = "$id · …"`、或在成员行里自己拼短 id 兜底，
 * [theMemberRowNoLongerPrintsTheMemberId] 立刻变红。
 *
 * 缺名的**口径**在之后被产品裁决改过一次：不再显示「未知成员」，而是由身份解析器
 * （`SenderIdentity`）给出 8 位短 id + 副行「未获取到资料」。所以这里钉的是
 * 「GroupInfoDialog 自己不拼 id、并且如实标注资料未到」，短 id 那一档的测试在
 * `SenderIdentityTest` 里。
 */
class GroupMemberRowLeakGuardTest {

    @Test
    fun orgPathJoinsCompanyDeptTeamInOrder() {
        assertEquals(
            "研发 · 后端 · 平台组",
            memberOrgPath(PeerUi(nodeId = "n1", nick = "老周", company = "研发", dept = "后端", team = "平台组")),
        )
    }

    @Test
    fun orgPathKeepsOnlyThePresentFields() {
        assertEquals("研发", memberOrgPath(PeerUi(nodeId = "n1", nick = "老周", company = "研发")))
        assertEquals("后端", memberOrgPath(PeerUi(nodeId = "n1", nick = "老周", dept = "后端")))
    }

    @Test
    fun orgPathIsNullWhenThereIsNothingToSay() {
        assertNull(memberOrgPath(null), "连对端都不认识时不该编一行出来")
        assertNull(memberOrgPath(PeerUi(nodeId = "n1", nick = "老周")))
        assertNull(
            memberOrgPath(PeerUi(nodeId = "n1", nick = "老周", company = "   ", dept = "", team = "")),
            "三个字段都空（含纯空白）时必须整行不画，而不是画一行空的",
        )
    }

    @Test
    fun theMemberRowNoLongerPrintsTheMemberId() {
        val live = GuardSourceScan.stripComments(GuardSourceScan.read(GROUP_INFO))
        assertFalse(
            live.contains("\"\$id"),
            "成员行又把成员 id 印进 Text 了 —— 完整 UUID 对用户零信息量，是界面技术化的一级泄漏",
        )
        assertFalse(live.contains(".take(8)"), "缺名兜底又退回 id 前 8 位那串十六进制了")
        assertFalse(live.contains("common_online"), "在线/离线又回到文字行了；状态该归头像")
        assertFalse(live.contains("common_offline"), "在线/离线又回到文字行了；状态该归头像")
        assertTrue(live.contains("memberOrgPath("), "副行必须画组织路径")
        assertTrue(live.contains("StatusDot("), "在线状态必须画在头像上的圆点里")
        // 缺名的口径：名字本体由解析器给 8 位短 id，成员行**必须**配一句「未获取到资料」——
        // 让裸 id 冒充真名，与当年显示「未知成员」是同一个谎的两种写法。
        assertTrue(
            live.contains("common_profile_unavailable"),
            "资料未到时必须如实说明（未获取到资料），不能让裸 id 冒充真名",
        )
    }

    @Test
    fun theGroupIdCopyReceiptSelfClears() {
        // 「已复制」翻上去之后必须 2 秒自清（与节点详情/连接页同一范式）：
        // 不自清的话反馈会挂到对话框关闭为止，同一次打开里再点没有变化可看 —— 等于没有反馈。
        // 反向验证：删掉 GroupInfoDialog 里的 LaunchedEffect 自清块，这条立刻红。
        val live = GuardSourceScan.stripComments(GuardSourceScan.read(GROUP_INFO))
        assertTrue(
            live.contains("LaunchedEffect(copied)") && live.contains("copied = false"),
            "复制群 ID 的回执缺自清：需要 LaunchedEffect(copied) + copied = false",
        )
    }

    private companion object {
        const val GROUP_INFO =
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/GroupInfoDialog.kt"
    }
}
