// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui

import com.kapp.tidings.testsupport.GuardSourceScan

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 壳（`App.kt`）里「必须活过转屏 / 进程重建的状态」与「必须露头的反馈」。
 *
 * ## 为什么这几条只能靠源码守卫
 *
 * 「转屏归零」要真的转一次屏才看得出；本仓没有 Compose UI 测试基建（没有
 * `createComposeRule`），而这两件事全在 Composable 内部 —— 状态用什么 remember、
 * 某个路由分支挂没挂横幅。单测够不到，只能在源码文本上钉（与
 * `DesktopComposerFocusGuardTest` 同一取舍）。
 *
 * 每条断言都先 `stripComments`：注释里正当讨论旧写法不能让守卫假绿。
 */
class AppShellSaveableStateGuardTest {

    private val path = "composeApp/src/commonMain/kotlin/com/kapp/tidings/App.kt"

    private fun src(): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    /**
     * 转屏 / 进程重建后导航状态归零（P0）的守卫。
     *
     * `nav`（AppNavState）、`settingsPage`、`globalSearchOpen`、`draftsByConv`
     * 四格都必须是 `rememberSaveable`：Manifest 没有 configChanges，转一次屏
     * Activity 重建，裸 `remember` 会被扔回初值 —— 用户正停在聊天里，转个屏就
     * 落回会话列表、草稿全没。把任何一格改回裸 `remember`，这条就红。
     */
    @Test
    fun shellStateThatMustSurviveRotationIsSaveable() {
        val text = src()
        assertTrue(
            text.contains("var nav by rememberSaveable(stateSaver = AppNavStateSaver)"),
            "nav（AppNavState）没有挂 Saveable —— 转屏会把用户扔回会话列表、详情层全部归零",
        )
        assertTrue(
            text.contains("var settingsPage by rememberSaveable"),
            "settingsPage 没有挂 Saveable —— 转屏会把用户从设置子页扔回设置首页",
        )
        assertTrue(
            text.contains("var globalSearchOpen by rememberSaveable"),
            "globalSearchOpen 没有挂 Saveable —— 转屏会把用户从全局搜索扔回会话列表",
        )
        assertTrue(
            text.contains("var draftsByConv by rememberSaveable(stateSaver = DraftsByConvSaver)"),
            "draftsByConv 没有挂 Saveable —— 转屏会清空所有打到一半的草稿",
        )
    }

    /**
     * AGENTS 路由分支必须挂 `state.banner` 的 AppBanner。
     *
     * 建群失败时 controller 发的就是 banner（群名为空 / 超长 / 没有其他成员）；
     * 这个分支不挂它，用户点「创建」就只是弹窗关了、屏幕上一个字都没有。
     * 守卫取 AGENTS 分支的花括号块，断言里面有 `AppBanner(`——把横幅接线从
     * 这个分支挪走，本条变红。
     */
    @Test
    fun agentsRouteRendersTheStateBanner() {
        val text = src()
        val agentsBranch = GuardSourceScan.bracedBlockAfter(text, "AppRoutes.AGENTS ->")
        assertTrue(
            agentsBranch.contains("AppBanner("),
            "AGENTS 路由分支没有接 AppBanner —— 建群失败（controller 发 banner）时" +
                "用户点了「创建」不会有任何反馈",
        )
        assertTrue(
            agentsBranch.contains("controller::dismissBanner"),
            "AGENTS 分支的横幅没有接关闭回调 —— 横幅会赖在屏幕上关不掉",
        )
    }

    /**
     * 占位符黑科技不许回来：`stringResource(res, "%s")` 再 `.replace("%s", …)`
     * 等于用占位符当参数、再手写一遍替换 —— 直接 `stringResource(res, title)`
     * 一个调用就是全部。
     */
    @Test
    fun defaultGroupNameIsFormattedDirectlyNotViaPlaceholderHack() {
        val text = src()
        assertFalse(
            text.contains("\"%s\""),
            "App.kt 里又出现了 \"%s\" 占位符 —— 用 stringResource(res, 实参) 直接格式化",
        )
        assertFalse(
            text.contains(".replace(\"%s\""),
            "App.kt 里又出现了对 \"%s\" 的手写替换 —— 格式化交给 stringResource",
        )
    }

    /**
     * 两栏高度下限的名字必须说真话：它按 dp 用（喂给 `resolveAppLayoutMode` 的
     * `Dp` 参数），名字必须叫 `_DP`。曾叫 `_PX` —— 下一个改断点的人一换算就把
     * 阈值改错。
     */
    @Test
    fun twoPaneHeightFloorIsNamedByItsUnit() {
        val text = src()
        assertTrue(
            text.contains("TWO_PANE_MIN_HEIGHT_DP"),
            "两栏高度下限常量缺失",
        )
        assertFalse(
            text.contains("TWO_PANE_MIN_HEIGHT_PX"),
            "两栏高度下限又改回 _PX 后缀 —— 它按 dp 用，名字说 px 会把改断点的人带沟里",
        )
    }

    /**
     * 页签内容必须包在按页签 id 分格的 SaveableStateProvider 里：切页签 / 被
     * 聊天详情、扫码、搜索盖走再回来，各屏自己的 rememberSaveable（滚动位置等）
     * 从 holder 恢复，而不是归零。
     */
    @Test
    fun tabContentIsWrappedInAKeyedSaveableStateProvider() {
        val text = src()
        assertTrue(
            text.contains("tabStateHolder.SaveableStateProvider(key = nav.selectedTabId)"),
            "页签内容没有包 SaveableStateProvider(key = nav.selectedTabId) —— " +
                "切页签 / 盖上整屏再回来，各屏的滚动位置会归零",
        )
    }
}
