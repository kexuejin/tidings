// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.drivers

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AgentDriver 网关契约（M2 接口扩形）的反向验证。
 *
 * 本文件钉住三件事，每一件都对应方案 §2.4 的一条裁决：
 *
 * 1. **会话生命周期成员在接口上**：`openSession` / `handleAction` / `queryProgress`
 *    是驱动契约的一部分（此前只有 executeTask），`SessionHandle` / `TaskProgressSnapshot`
 *    是它们的定型形状。写侧（ExternalPluginDriver）与读侧（宿主总线）都走同一份契约。
 * 2. **能力 ACL 收归宿主**：`updateCapabilityAcl` / `isCapabilityAllowed` 是
 *    [DriverRegistry] 的方法，**不在** AgentDriver 接口上 —— 一个只实现会话与执行成员的
 *    驱动能直接挂上总线，就是这条的编译级证据（本文件末尾那个最小驱动没有 ACL 成员）。
 *    ACL 不透传给插件：ExternalPluginDriver 永远不会把 ACL 调用转发到插件进程。
 * 3. **宿主侧 ACL 对既有驱动的治理语义不变**：手机端下发 ACL 后，native 驱动对被禁能力
 *    依旧回报 ACL 错误事件，而不是静默执行（原 DriverTest 的钉条目，搬到这里换成宿主入口）。
 */
class AgentDriverGatewayContractTest {

    /** 只实现会话与执行成员的最小驱动 —— 没有任何 ACL 成员也能挂上总线。 */
    private class RecordingDriver : AgentDriver {
        override val driverId: String = "recording"
        override val displayName: String = "Recording Driver"

        val opened = mutableListOf<Pair<String, String?>>()
        val actions = mutableListOf<Triple<String, String, String>>()
        val progressProbes = mutableListOf<String>()
        val executed = mutableListOf<Pair<String, String>>()

        var nextHandle: SessionHandle = SessionHandle(sessionId = "sess-1", createdAt = 42L)
        var nextSnapshot: TaskProgressSnapshot? =
            TaskProgressSnapshot(sessionId = "sess-1", state = "running", progressPercent = 70, updatedAt = 43L)

        override suspend fun listCapabilities(): List<DriverCapability> = emptyList()

        override suspend fun openSession(wsPath: String, resumeSessionId: String?): SessionHandle {
            opened += wsPath to resumeSessionId
            return nextHandle
        }

        override suspend fun executeTask(
            sessionId: String,
            prompt: String,
            onEvent: suspend (DriverEvent) -> Unit,
        ) {
            executed += sessionId to prompt
        }

        override suspend fun handleAction(sessionId: String, actionId: String, decidedBy: String) {
            actions += Triple(sessionId, actionId, decidedBy)
        }

        override suspend fun queryProgress(sessionId: String): TaskProgressSnapshot? {
            progressProbes += sessionId
            return nextSnapshot
        }
    }

    @Test
    fun sessionLifecycleMembersAreOnTheDriverInterface() = runBlocking {
        val registry = DriverRegistry()
        val driver = RecordingDriver()
        registry.register(driver)

        val handle = registry.getDriver("recording")?.openSession("/ws/demo", "resume-9")
        assertNotNull(handle)
        assertEquals(SessionHandle(sessionId = "sess-1", createdAt = 42L), handle)
        assertEquals(listOf<Pair<String, String?>>("/ws/demo" to "resume-9"), driver.opened)

        registry.getDriver("recording")?.handleAction("sess-1", "pause-task", "alice")
        assertEquals(listOf(Triple("sess-1", "pause-task", "alice")), driver.actions)

        val snap = registry.getDriver("recording")?.queryProgress("sess-1")
        assertEquals(70, snap?.progressPercent)
        assertEquals(listOf("sess-1"), driver.progressProbes)
    }

    @Test
    fun aDriverWithoutProgressReportsNullInsteadOfThrowing() = runBlocking {
        val registry = DriverRegistry()
        val driver = RecordingDriver()
        driver.nextSnapshot = null
        registry.register(driver)
        assertNull(registry.getDriver("recording")?.queryProgress("missing"))
    }

    @Test
    fun capabilityAclIsHeldByTheHostNotByDrivers() = runBlocking {
        val registry = DriverRegistry()
        // 宿主侧入口：没有覆盖记录时按驱动声明放行；覆盖之后以宿主名单为准。
        assertTrue(registry.isCapabilityAllowed("native", "bash"))
        registry.updateCapabilityAcl("native", setOf("fs_read", "fs_write"))
        assertFalse(registry.isCapabilityAllowed("native", "bash"))
        assertTrue(registry.isCapabilityAllowed("native", "fs_read"))
        // 覆盖按驱动隔离：别的驱动不受 native 这份名单影响。
        assertTrue(registry.isCapabilityAllowed("ds-bot", "bash"))
    }

    @Test
    fun hostAclStillGatesTheNativeDriverExecution() = runBlocking {
        val registry = DriverRegistry()
        registry.updateCapabilityAcl("native", setOf("fs_read", "fs_write"))

        val events = mutableListOf<DriverEvent>()
        val driver = registry.getDriver("native")
        assertNotNull(driver)
        driver.executeTask("sess-acl", "please list files") { events += it }

        val hasAclError = events.any { it is DriverEvent.Error && it.message.contains("bash") }
        assertTrue(hasAclError, "宿主禁用 bash 之后，native 驱动必须回报 ACL 错误而不是继续执行")
    }

    @Test
    fun hostAclWithoutOverrideLeavesTheNativeDriverPermissive() = runBlocking {
        val registry = DriverRegistry()
        val events = mutableListOf<DriverEvent>()
        val driver = registry.getDriver("native")
        assertNotNull(driver)
        driver.executeTask("sess-open", "please list files") { events += it }
        assertTrue(
            events.none { it is DriverEvent.Error && it.message.contains("ACL") },
            "没有宿主覆盖时 native 按声明放行（治理语义与收编前一致）",
        )
    }
}
