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

    /**
     * **invoke 路的追问分支必须与文本路由同判据**（真机现场）：
     * 原型待选期的正文在 `followUpIfActive`（文本路）出方向确认卡，而
     * `handlePromptAwait`（invoke 路）缺同一分支 ⇒ 同一句追问在 1:1 双派 ——
     * 文本路出 `chain-<id>-ask` 方向卡、invoke 路另开一张全新工单（其卡面还写着
     * 「Reply to adjust it」，与实际行为相反）。手机 1:1 的派单生命周期归 invoke
     * 通路，这条分支就是它的追问语义。
     *
     * 结构钉子：prototype 分支必须存在于 `handlePromptAwait`，且排在 proposed
     * 撤单分支之前（顺序错了，待选工单会先被当点将期撤掉）。
     */
    @Test
    fun invokeAwaitHandlesPrototypeStageLikeTheTextRoute() {
        val path = "orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"
        val code = GuardSourceScan.stripComments(GuardSourceScan.read(path))
        val awaitBody = GuardSourceScan.bracedBlockAfter(code, "suspend fun handlePromptAwait(")

        assertTrue(
            awaitBody.contains("""active.stage == "prototype"""") && awaitBody.contains("askPrototypeDirection("),
            "handlePromptAwait 必须处理 prototype 待选工单（出方向确认卡，不是新派单）——" +
                "1:1 药丸派单的追问走 invoke 通路，缺这条分支就是双派",
        )
        val prototypeAt = awaitBody.indexOf("""active.stage == "prototype"""")
        val proposedAt = awaitBody.indexOf("""active.stage == "proposed"""")
        assertTrue(
            prototypeAt in 0 until proposedAt,
            "prototype 分支必须排在 proposed 撤单之前 —— 顺序反了，待选工单会被先撤掉",
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

    /**
     * **群简介声明的目录是第三条确定性判据**（真机缺口）：席位没声明 `defaultWsId` 的群
     * （teahouse 协同群的 DSH 席位），兜底卡「降级为本机执行」时 `resolveWorkspacePath`
     * 只剩单工作区推导，多仓宿主上必退单 `no git workspace bound to group`。
     * 建群「关联本地目录」会把绝对路径写进 `description` —— 与已宣告工作区的 wsPath
     * **精确相等**才认，不匹配就如实退单，不猜。
     */
    @Test
    fun groupDescriptionIsNoLongerABindingCriterion() = runBlocking {
        val tempRoot = File(System.getProperty("java.io.tmpdir"), "tenon-test-ws-" + System.nanoTime())
        val repo = File(tempRoot, "repo").apply { mkdirs() }
        ProcessBuilder("git", "init", repo.absolutePath).start().waitFor()

        try {
            val scope = CoroutineScope(Dispatchers.Default)
            val groupId = "g-desc-test"
            val controller = RelayController(
                platform = InMemoryAppPlatform(),
                store = InMemorySettingsStore(
                    RelaySettings.firstRun().copy(
                        udpPort = 0, tcpPort = 0,
                        persistMessages = false, persistMessagesDecided = true,
                        groups = listOf(
                            com.kapp.tenon.core.protocol.GroupMeta(
                                groupId = groupId,
                                name = "协同群",
                                members = listOf("peer-1"),
                                rev = 1,
                                updatedBy = "peer-1",
                                updatedTs = 1L,
                                // 席位不带 defaultWsId —— 群绑定推导（判据 1）必然落空。
                                virtualAgents = listOf(
                                    com.kapp.tenon.core.protocol.VirtualAgentMember(
                                        agentId = "DSH",
                                        hostNodeId = "self",
                                        name = "dsh-repo",
                                    ),
                                ),
                                description = repo.absolutePath,
                            ),
                        ),
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
            controller.recordLocalWorkspace(
                wsId = "ws-repo",
                wsTitle = "repo",
                wsPath = repo.absolutePath,
                agentKey = "codex",
                agentKind = "codex",
            )
            // 再录第二个工作区：关掉「单工作区自然锁定」这条既有兜底，
            // 下面的阴性对照才能真正考到判据 2b 自己。
            val other = File(tempRoot, "other").apply { mkdirs() }
            controller.recordLocalWorkspace(
                wsId = "ws-other",
                wsTitle = "other",
                wsPath = other.absolutePath,
                agentKey = "codex",
                agentKind = "codex",
            )

            // F2/D5：群简介**不再是绑定判据** —— 即使简介与已宣告工作区的 wsPath 逐字
            // 相等也不解析（绑定看席位声明/指针投影，不看群简介文案）。席位没带
            // defaultWsId 的群在这里如实退单；想让它可执行，走指针投影写回声明。
            assertNull(
                orch.resolveWorkspacePath(groupId = groupId, wsId = null),
                "群简介写的目录不再参与解析（2b 已删，F2）：绑定只认席位声明",
            )
            // 描述与任何工作区路径都不相等 ⇒ 如实退单（返回 null），不做包含/前缀等模糊匹配。
            assertNull(
                orch.resolveWorkspacePath(groupId = "g-never-created", wsId = null),
                "群不存在（无从读声明）时必须退单 —— 多工作区宿主上没有任何确定性判据可落",
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
