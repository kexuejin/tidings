// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 工单 C：1:1 派单的任务级显式工作区绑定——**贯通守卫**。
 *
 * `resolveWorkspacePath` 的解析语义（显式 wsId 优先 / 群绑定 / 单工作区自动锁定 /
 * 多工作区退单）由 `DesktopWorkerOrchestrator1to1DispatchTest` 直接覆盖；本文件钉的是
 * 另一半：**参数必须真的从入口流到解析点**。这曾是「显式 wsId 分支」沦为死代码的根因——
 * 函数支持、没有任何调用方传入。三处贯通点，抽掉任何一处对应守卫即红：
 *
 * 1. `dispatchSingleWorker` 把 `wsId` 传进 `resolveWorkspacePath(groupId, wsId)`；
 * 2. 两个派单入口（`handlePrompt` / `handlePromptAwait`）签名带 `wsId` 且向下穿透；
 * 3. 执行桥（DesktopExtension）把租约的 `request.wsId` 原样传给 `handlePromptAwait`。
 */
class DesktopWorkerOrchestrator1to1WorkspaceDispatchTest {

    private fun orchestratorSource(): String =
        GuardSourceScan.stripComments(
            GuardSourceScan.read(
                "orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt",
            ),
        )

    private fun extensionSource(): String =
        GuardSourceScan.stripComments(
            GuardSourceScan.read(
                "orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopExtension.kt",
            ),
        )

    /**
     * 取「签名 + 函数体」整段（多行参数列表也算）：`bracedBlockAfter` 只回花括号体，
     * 断言签名参数（`wsId: String? = null`）时会漏掉参数列表。
     */
    private fun funWithSignatureAfter(text: String, marker: String): String {
        val at = text.indexOf(marker)
        if (at < 0) throw AssertionError("源码里找不到标记：$marker")
        val open = text.indexOf('{', at)
        if (open < 0) throw AssertionError("标记后面没有花括号：$marker")
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(at, i + 1)
                }
            }
        }
        throw AssertionError("花括号没有闭合：$marker")
    }

    @Test
    fun dispatchSingleWorkerResolvesWorkspaceWithExplicitWsId() {
        val dispatch = GuardSourceScan.bracedBlockAfter(
            orchestratorSource(),
            "private fun dispatchSingleWorker(",
        )
        assertTrue(
            dispatch.contains("resolveWorkspacePath(groupId, wsId)"),
            "dispatchSingleWorker 必须把显式 wsId 传进工作区解析——" +
                "退回 resolveWorkspacePath(groupId) 即为死分支复活前的旧形态",
        )
    }

    @Test
    fun bothDispatchEntriesThreadWsIdDownToSingleWorker() {
        val source = orchestratorSource()
        for (entry in listOf("suspend fun handlePrompt(", "suspend fun handlePromptAwait(")) {
            val block = funWithSignatureAfter(source, entry)
            assertTrue(
                block.contains("wsId: String? = null"),
                "$entry 签名必须带可选 wsId（任务级显式绑定的入口）",
            )
            val call = block.lineSequence().firstOrNull { it.contains("dispatchSingleWorker(") }
            assertTrue(
                call != null && call.contains("wsId"),
                "$entry 调用 dispatchSingleWorker 时必须把 wsId 向下穿透（实际：$call）",
            )
        }
    }

    @Test
    fun invokeBridgeForwardsLeaseWsIdToOrchestrator() {
        val bridge = GuardSourceScan.bracedBlockAfter(
            extensionSource(),
            "override suspend fun execute(",
        )
        assertTrue(
            bridge.contains("wsId = request.wsId"),
            "执行桥必须把租约的 request.wsId 原样传给 handlePromptAwait——" +
                "解析后丢弃等于任务级绑定在最后一公里断线",
        )
    }

    @Test
    fun plainOneToOneTextDoesNotDefaultDispatch() {
        // 决议矩阵的 PlainChat 分支：1:1 纯文本（无药丸 invoke、无 @ 指派）是普通聊天。
        // 从前默认落 codex 的兜底已移除——群路径（groupId != null）的兜底保留。
        val block = GuardSourceScan.bracedBlockAfter(
            orchestratorSource(),
            "suspend fun handlePrompt(",
        )
        assertTrue(
            block.contains("else if (groupId != null)"),
            "handlePrompt 的单棒兜底必须收窄到群路径（1:1 纯文本不再默认派单）",
        )
    }

    @Test
    fun unknownWsIdResolvesToNullAndTriggersHandback() {
        val dispatch = GuardSourceScan.bracedBlockAfter(
            orchestratorSource(),
            "private fun dispatchSingleWorker(",
        )
        assertTrue(
            dispatch.contains("Handed back: no git workspace bound"),
            "wsId 解析不出必须走确定性退单（Handback），严禁随机猜仓",
        )
    }
}
