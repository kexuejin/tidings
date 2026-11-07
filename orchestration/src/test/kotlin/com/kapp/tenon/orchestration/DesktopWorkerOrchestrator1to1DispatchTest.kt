// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.domain.InMemoryAgentStore
import com.kapp.tenon.domain.InMemoryMessageStore
import com.kapp.tenon.domain.InMemorySettingsStore
import com.kapp.tenon.domain.RelayController
import com.kapp.tenon.domain.RelaySettings
import com.kapp.tenon.domain.model.NoticeKind
import com.kapp.tenon.platform.InMemoryAppPlatform
import com.kapp.tenon.testsupport.GuardSourceScan
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 验证 1:1 直聊与群聊统一架构下的工单派发、工作区解析与防吞单守卫。
 *
 * 核心目标（大道至简）：
 * 1. 1:1 对话与群聊共用同一套底层工单机制（ActiveTicket.targetConvId = groupId ?: convId）。
 * 2. 移除对 groupId 的强制硬编码绑定，1:1 对话中无论立项卡、原型卡、进度心跳还是终验补丁卡，
 *    均原位推向 1:1 消息流（targetConvId），绝不静默吞单。
 * 3. 工作区解析三段式：
 *    - 显式 wsId 优先（1:1 项目指针点选挂接）；
 *    - 既有群绑定解析（群聊场景 1 群 1 仓）；
 *    - 单工作区自然锁定（本地仅 1 个 Git 工作区时免点选自动推导）；
 *    - 多工作区未指定时安全退单（fail-closed，绝不在多仓之间随机猜测）。
 */
class DesktopWorkerOrchestrator1to1DispatchTest {

    @Test
    fun orchestratorSourceGuardsUnified1to1AndGroupDispatch() {
        val path = "orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"
        val code = GuardSourceScan.stripComments(GuardSourceScan.read(path))

        // 1. targetConvId 归一化定义
        assertTrue(
            code.contains("val targetConvId: String get() = groupId ?: convId"),
            "ActiveTicket 必须将目标会话统一为 targetConvId (groupId ?: convId)",
        )

        // 2. 打断与取消发向 targetConvId
        assertTrue(
            code.contains("publishCardJson(active.targetConvId, abortedJson, active.agentKey)"),
            "abortIfRequested 与 abortByCard 必须向 targetConvId 发布中止卡",
        )
        assertTrue(
            code.contains("publishCardJson(active.targetConvId, cancelledJson, active.agentKey)"),
            "cancelByCard 必须向 targetConvId 发布取消卡",
        )

        // 3. 原型流与自愈流使用统一 targetId
        assertTrue(
            code.contains("val targetId = groupId ?: convId"),
            "runPrototypeFlow 与 runDevelopmentAndHealLoop 必须定义统一 targetId",
        )

        // 4. 离线产物直投通道支持 1:1 peer ID
        assertTrue(
            code.contains("toConvPeerId(targetId)"),
            "deliverPrototypeArtifact / deliverPatchBundle 必须通过 toConvPeerId 支持 1:1 产物投递",
        )
    }

    /**
     * **派单失败必须落会话**：不能只写进工单台账，聊天流里却一条都没有。
     *
     * 这里走真实的 `handlePrompt` 派单入口（控制面不可用 = 最早的一条终局失败分支），
     * 断言会话流里出现 [NoticeKind.AgentDispatchFailed]，并带齐「哪一单、谁、为什么」。
     * 抽掉发布调用时这条会红 —— 不是断言了别的东西。
     */
    @Test
    fun dispatchFailureLandsAsAVisibleNoticeInTheConversation() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val controller = RelayController(
            platform = InMemoryAppPlatform(),
            store = InMemorySettingsStore(
                RelaySettings.firstRun().copy(
                    udpPort = 0, tcpPort = 0,
                    persistMessages = false, persistMessagesDecided = true,
                ),
            ),
            messageStore = InMemoryMessageStore(),
            agentStore = InMemoryAgentStore(),
            scope = scope,
        )
        try {
            val convId = "peer-phone"
            val orch = DesktopWorkerOrchestrator(
                planeProvider = { null },
                controller = controller,
                scope = scope,
            )

            // 行为变更后 1:1 纯文本不再默认派单（PlainChat 分支），失败可见性走
            // invoke 通路验证：显式 agentKey（缺省 codex）+ plane 不可用 ⇒ 退单通知。
            orch.handlePromptAwait(
                convId = convId,
                senderPeerId = controller.state.value.selfNodeId,
                prompt = "fix the flaky login test",
                replyToId = null,
                agentKey = null,
                wsId = null,
            )

            val notice = controller.state.value.messages[convId].orEmpty()
                .singleOrNull { it.notice == NoticeKind.AgentDispatchFailed }
            assertNotNull(notice, "派单失败必须在会话流里留下一条用户可见的通知")
            assertEquals("codex", notice.noticeDetail?.actor, "通知要说清是谁失败的")
            assertEquals("fix the flaky login test", notice.noticeDetail?.title, "通知要说清是哪一单")
            assertTrue(
                notice.noticeDetail?.reason?.contains("control plane unavailable") == true,
                "通知要说清为什么失败（实际原因：" + notice.noticeDetail?.reason + "）",
            )
        } finally {
            controller.stop()
        }
    }

    /**
     * **两条派单路径的失败发布必须同源**：群意图路径的旧入口转发到
     * `announceDispatchFailure`，工单路径的每个终局失败出口也走它。
     *
     * 这条守的是「多处必须一致」：只要工单路径的发布被抽掉（哪怕只抽一个终局分支），
     * 调用计数就会掉到 4 以下，守卫立刻红。
     */
    @Test
    fun dispatchFailurePublisherIsSharedByGroupAndTicketPaths() {
        val domain = GuardSourceScan.stripComments(
            GuardSourceScan.read(
                "app/src/commonMain/kotlin/com/kapp/tenon/domain/RelayControllerAgentInvokeComponent.kt",
            ),
        )
        val orchestrator = GuardSourceScan.stripComments(
            GuardSourceScan.read(
                "orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt",
            ),
        )

        val groupEntry = GuardSourceScan.bracedBlockAfter(domain, "private fun announceAgentGaveUp(")
        assertTrue(
            groupEntry.contains("announceDispatchFailure("),
            "群意图路径的失败发布必须转发到 announceDispatchFailure —— 否则两条路径又开始各写一份",
        )

        val ticketEntry = GuardSourceScan.bracedBlockAfter(orchestrator, "private fun announceTerminalFailure(")
        assertTrue(
            ticketEntry.contains("controller.announceDispatchFailure("),
            "工单路径的失败发布必须落到域层同一个入口",
        )

        val direct = GuardSourceScan.bracedBlockAfter(domain, "private fun postDirectDispatchFailure(")
        assertTrue(
            direct.contains("notice = NoticeKind.AgentDispatchFailed"),
            "1:1 的落点必须是同一种 NoticeKind，不能另发明一种形状",
        )

        val dispatch = GuardSourceScan.bracedBlockAfter(orchestrator, "private fun dispatchSingleWorker(")
        val publishes = Regex("announceTerminalFailure\\(").findAll(dispatch).count()
        assertTrue(
            publishes >= 4,
            "dispatchSingleWorker 的四个终局失败出口（控制面不可用 / 退单 / 抛错 / 正常终局）" +
                "都必须发布；当前只有 " + publishes + " 处",
        )
    }

    @Test
    fun resolveWorkspacePathExplicitWsIdAndAutoLocking() = runBlocking {
        val tempRoot = File(System.getProperty("java.io.tmpdir"), "tenon-test-ws-" + System.nanoTime())
        val repo1 = File(tempRoot, "repo1").apply { mkdirs() }
        val repo2 = File(tempRoot, "repo2").apply { mkdirs() }
        ProcessBuilder("git", "init", repo1.absolutePath).start().waitFor()
        ProcessBuilder("git", "init", repo2.absolutePath).start().waitFor()

        try {
            val scope = CoroutineScope(Dispatchers.Default)
            val controller = RelayController(
                platform = InMemoryAppPlatform(),
                store = InMemorySettingsStore(
                    RelaySettings.firstRun().copy(
                        udpPort = 0, tcpPort = 0,
                        persistMessages = false, persistMessagesDecided = true,
                    ),
                ),
                messageStore = InMemoryMessageStore(),
                agentStore = InMemoryAgentStore(),
                scope = scope,
            )
            val orch = DesktopWorkerOrchestrator(
                planeProvider = { null },
                controller = controller,
                scope = scope,
            )

            // 情况 1：显式指定 wsId 优先命中
            controller.recordLocalWorkspace(
                wsId = "ws-1",
                wsTitle = "repo1",
                wsPath = repo1.absolutePath,
                agentKey = "codex",
                agentKind = "codex",
            )
            assertEquals(
                repo1.absolutePath,
                orch.resolveWorkspacePath(groupId = null, wsId = "ws-1"),
                "显式指定 wsId 时必须解析出对应工作区路径",
            )

            // 情况 2：仅有 1 个工作区时，未指定 groupId 与 wsId 自动锁定
            assertEquals(
                repo1.absolutePath,
                orch.resolveWorkspacePath(groupId = null, wsId = null),
                "宿主仅有 1 个工作区时，1:1 对话必须自动锁定该工作区",
            )

            // 情况 3：存在多个工作区且未指定 wsId 时，必须安全退单（返回 null），禁止随哈希乱猜
            controller.recordLocalWorkspace(
                wsId = "ws-2",
                wsTitle = "repo2",
                wsPath = repo2.absolutePath,
                agentKey = "codex",
                agentKind = "codex",
            )
            assertNull(
                orch.resolveWorkspacePath(groupId = null, wsId = null),
                "存在多个工作区且未指定 wsId 时，必须安全返回 null 触发退单，不可猜测",
            )

            // 情况 4：多工作区下只要显式指定 wsId，仍可精准解析
            assertEquals(
                repo2.absolutePath,
                orch.resolveWorkspacePath(groupId = null, wsId = "ws-2"),
                "多工作区下显式指定 wsId 必须正确解析",
            )

            controller.stop()
        } finally {
            deleteSafely(tempRoot)
        }
    }

    private fun deleteSafely(dir: File) {
        val root = dir.toPath()
        if (!dir.exists()) return
        java.nio.file.Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                java.nio.file.Files.deleteIfExists(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dirPath: java.nio.file.Path, exc: java.io.IOException?): java.nio.file.FileVisitResult {
                java.nio.file.Files.deleteIfExists(dirPath)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }
}
