// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 授权台账（PPE-1 增补 D7）的**纯逻辑**测试：不建运行时、不起控制面。
 *
 * 存在的理由：`ControlPlaneTest` 那半边测的是"HTTP 上进出对不对"，而这里守的三件事
 * 与网络无关，且每一件错了都会变成**静默放行**：
 *  1. `byDevice`/`request` 档下缺省拒绝（沉默 = 未授权）；
 *  2. `scope=agent` 的授权**不放行**没带 agentKey 的帧（缺哪一层就退化到哪一层，
 *     但不许退着放行 —— 否则一条 agent 授权等于整台设备授权）；
 *  3. 策略与授权**跨重启**留存（用户按过的按钮不能被重启抹掉）。
 */
class AgentGrantStoreTest {

    private val file: File = File.createTempFile("relay-grants-unit-", ".json").apply { delete() }

    @AfterTest
    fun cleanup() {
        runCatching { file.delete() }
    }

    @Test
    fun requestPolicyDefaultsDenyWhileAutoAllowsEverything() {
        val store = AgentGrantStore(file)
        assertEquals("request", store.snapshot().policy, "出厂档默认闭合（request 申请制）")
        assertFalse(store.allows("any-node", "dsh-cli"), "request 档缺省拒绝，需申请授权")

        assertTrue(store.setPolicy("auto"))
        assertTrue(store.allows("any-node", "dsh-cli"), "auto 档任何设备可见")

        assertTrue(store.setPolicy("byDevice"))
        assertFalse(store.allows("any-node", "dsh-cli"), "byDevice + 空白名单 ⇒ 缺省拒绝")
        assertFalse(store.allows("any-node", null), "连「整台设备」都不放行")

        assertTrue(store.setPolicy("request"))
        assertFalse(store.allows("any-node", "dsh-cli"), "request 档同样缺省拒绝（申请不等于已批）")

        assertFalse(store.setPolicy("granted-everything"), "策略取值封闭：拼错的值必须被拒")
        assertEquals("request", store.snapshot().policy, "被拒的写入不许顺手改掉现值")
    }

    @Test
    fun agentScopedGrantNeverAuthorizesAnAnonymousFrame() {
        val store = AgentGrantStore(file)
        store.setPolicy("byDevice")
        store.grant("phone-1", "agent", "dsh-cli", grantedBy = "kexuejin", at = 1L)

        assertTrue(store.allows("phone-1", "dsh-cli"), "同 agentKey 命中")
        assertFalse(store.allows("phone-1", "codex-runner"), "另一个 agent 不命中")
        // 这一条是**安全**判据：帧没带身份时，若按 scope=agent 放行，
        // 一条 agent 授权就变成了整台设备的授权。
        assertFalse(store.allows("phone-1", null), "无身份的帧不许吃 scope=agent 的授权（不猜）")
        assertFalse(store.allows("phone-2", "dsh-cli"), "别的设备不命中")

        store.grant("phone-1", "all", null, grantedBy = null, at = 2L)
        assertTrue(store.allows("phone-1", null), "scope=all 才覆盖无身份的帧")
    }

    @Test
    fun policyGrantsAndRequestsSurviveAReopen() {
        val first = AgentGrantStore(file)
        first.setPolicy("request")
        first.grant("phone-1", "agent", "dsh-cli", grantedBy = "me", at = 100L)
        first.recordRequest("phone-2", "codex", "想在手机上看", at = 200L)

        val second = AgentGrantStore(file)
        val snap = second.snapshot()
        assertEquals("request", snap.policy, "策略必须跨重启留存")
        assertEquals(1, snap.grants.size)
        assertEquals("phone-1", snap.grants.first().nodeId)
        assertEquals("dsh-cli", snap.grants.first().agentKey)
        assertEquals(1, snap.requests.size, "待办申请也要跨重启留存（否则人没批就永久消失）")
        assertEquals("想在手机上看", snap.requests.first().reason)
        assertTrue(second.allows("phone-1", "dsh-cli"))
    }

    @Test
    fun approvingARequestRemovesItFromTheTodoQueue() {
        val store = AgentGrantStore(file)
        store.recordRequest("phone-9", "dsh-cli", "申请", at = 1L)
        assertEquals(1, store.snapshot().requests.size)
        store.grant("phone-9", "agent", "dsh-cli", grantedBy = "me", at = 2L)
        assertEquals(0, store.snapshot().requests.size, "批完的申请要从待办里摘掉，否则面板永远挂着一条")
    }

    @Test
    fun revokingOnlyStopsFutureDeliveryAndFileIsOwnerOnly() {
        val store = AgentGrantStore(file)
        store.setPolicy("byDevice")
        store.grant("phone-1", "all", null, grantedBy = null, at = 1L)
        assertTrue(store.allows("phone-1", "dsh-cli"))

        assertEquals(1, store.revoke("phone-1", "all", null))
        assertFalse(store.allows("phone-1", "dsh-cli"), "撤销后不再放行（但**不回收**已送达的数据）")

        // 这份文件是**安全策略**（谁能看这台宿主的 agent），不是偏好：
        // 同机其它用户能读/写它 = 能窥探甚至给自己发授权。0600 是它的下限。
        assertTrue(file.isFile, "撤销也要落盘")
        val p = java.nio.file.Files.getPosixFilePermissions(file.toPath())
        assertFalse(p.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ), "组可读 = 同机他人能窥探：$p")
        assertFalse(p.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ), "他人可读/可写 = 能给自己发授权：$p")
        assertTrue(p.contains(java.nio.file.attribute.PosixFilePermission.OWNER_READ))
        assertTrue(p.contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE))
    }

    @Test
    fun aMissingFileIsFirstRunAndStaysRequest() {
        // 区分「文件不存在」与「文件坏了」的第一半：首次运行没有台账是正常的，
        // 出厂档 request（默认闭合，需设备申请），且不该报 loadError。
        assertFalse(file.exists())
        val fresh = AgentGrantStore(file)
        assertEquals("request", fresh.snapshot().policy, "文件不存在 = 首次运行，出厂档 request")
        assertEquals(null, fresh.snapshot().loadError, "首次运行不是错误，不许报 loadError")
        assertFalse(fresh.allows("any-node", "dsh-cli"), "request 档缺省拒绝，需申请授权")
    }

    @Test
    fun workspaceScopeAndAccessControlTested() {
        val store = AgentGrantStore(file)
        store.setPolicy("byDevice")

        // 授予只读工作区权限
        store.grant(
            nodeId = "phone-1",
            scope = "workspace",
            agentKey = "dsh-cli",
            wsId = "ws-relay",
            access = "ro",
            grantedBy = "admin",
            at = 100L,
        )

        // 读权限检查
        assertTrue(store.allows("phone-1", "dsh-cli", "ws-relay", requireRw = false), "同工作区只读放行")
        assertFalse(store.allows("phone-1", "dsh-cli", "ws-other", requireRw = false), "不同工作区不放行")
        assertFalse(store.allows("phone-1", "codex", "ws-relay", requireRw = false), "不同 agent 不放行")
        assertFalse(store.allows("phone-2", "dsh-cli", "ws-relay", requireRw = false), "不同设备不放行")

        // 写权限检查（ro 必须被拒绝）
        assertFalse(store.allows("phone-1", "dsh-cli", "ws-relay", requireRw = true), "ro 权限在 requireRw 时必须拒绝")

        // 升级为 rw 权限
        store.grant(
            nodeId = "phone-1",
            scope = "workspace",
            agentKey = "dsh-cli",
            wsId = "ws-relay",
            access = "rw",
            grantedBy = "admin",
            at = 200L,
        )
        assertTrue(store.allows("phone-1", "dsh-cli", "ws-relay", requireRw = true), "rw 权限在 requireRw 时放行")

        // 测试跨重启保留 wsId 和 access
        val reopened = AgentGrantStore(file)
        assertTrue(reopened.allows("phone-1", "dsh-cli", "ws-relay", requireRw = true))
        val grant = reopened.snapshot().grants.first { it.nodeId == "phone-1" && it.scope == "workspace" }
        assertEquals("ws-relay", grant.wsId)
        assertEquals("rw", grant.access)
    }

    @Test
    fun aBrokenFileFailsClosedToTheStrictestPolicy() {
        // 第二半（H1 的判据）：文件**在**但读不出来 —— 半途崩留下的截断 JSON，
        // 或被人改坏的文件。此时绝不能退回 auto：auto = 任何已添加的节点可见全部 agent，
        // 那等于"把文件写坏"就是"给自己发一份全量授权"。
        // 必须退回最严档（request，缺省拒绝），并**说出原因**供面板/403 文案解释。
        file.writeText("{\"policy\":\"byDevice\",\"grants\":[{\"nodeId\":\"p1\",\"scope\":\"all\"}")
        val broken = AgentGrantStore(file)
        val snap = broken.snapshot()
        assertEquals("request", snap.policy, "坏文件 ⇒ 退回最严档，不崩、也不假装读到了 byDevice")
        assertFalse(broken.allows("any-node", "dsh-cli"), "坏文件之后一个都不放行（fail-closed）")
        assertFalse(broken.allows("any-node", null), "连整台设备都不放行")
        val why = snap.loadError
        assertTrue(why != null, "必须能查出「这次启动读不回台账」，否则界面无从解释")
        assertTrue(why!!.contains("JSON"), "原因要说人话，能指出是解析失败：" + why)

        // 自愈：一次正常写入就把坏文件覆盖掉 —— 但**已经丢掉的授权不会自己回来**，
        // 所以 loadError 在同一进程内保持粘住（它描述"这次启动丢掉了什么"）。
        broken.setPolicy("byDevice")
        val healed = AgentGrantStore(file)
        assertEquals("byDevice", healed.snapshot().policy, "一次正常写入就把坏文件覆盖掉（自愈）")
        assertEquals(null, healed.snapshot().loadError, "重开后文件是健康的，不再报错")
    }

    @Test
    fun aFileWithoutAUsablePolicyFieldAlsoFailsClosed() {
        // 本类每次落盘都写 policy，所以"由我们写出的文件必有该字段"。
        // 它缺失/非法只可能是被截断或被外部改过 —— 沿用 auto 与截断同样危险。
        file.writeText("{\"grants\":[{\"nodeId\":\"p1\",\"scope\":\"all\",\"grantedAt\":1}]}")
        val noPolicy = AgentGrantStore(file)
        assertEquals("request", noPolicy.snapshot().policy, "缺 policy 字段 ⇒ 最严档")
        assertTrue(noPolicy.snapshot().loadError != null)
        assertFalse(noPolicy.allows("p1", "dsh-cli"), "文件里那条 scope=all 也不许生效（整档不可信）")

        file.writeText("{\"policy\":\"granted-everything\",\"grants\":[]}")
        val badPolicy = AgentGrantStore(file)
        assertEquals("request", badPolicy.snapshot().policy, "非法档位 ⇒ 最严档（不静默退回 auto）")
        assertTrue(badPolicy.snapshot().loadError != null)

        file.writeText("[1,2,3]")
        val notAnObject = AgentGrantStore(file)
        assertEquals("request", notAnObject.snapshot().policy, "顶层不是 JSON 对象 ⇒ 最严档")
        assertTrue(notAnObject.snapshot().loadError != null)
    }

    @Test
    fun concurrentGrantsAndRevokesAllLandOnDisk() {
        // M1 的判据：从前 persist() 在锁外写文件（快照在锁里取、写盘在锁外），
        // 4 线程池下并发的 grant/revoke 会互相覆盖 —— 撤销可能被一条更早的快照吞掉。
        // 这里用多线程反复"授权 → 撤销"同一台设备，最后盘上必须与内存一致：
        // 谁最后改的，谁说了算。
        val store = AgentGrantStore(file)
        store.setPolicy("byDevice")
        val threads = 8
        val rounds = 60
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        try {
            val tasks = (0 until threads).map { t ->
                java.util.concurrent.Callable {
                    repeat(rounds) {
                        if ((t + it) % 2 == 0) {
                            store.grant("phone-c", "all", null, grantedBy = "t" + t, at = it.toLong())
                        } else {
                            store.revoke("phone-c", "all", null)
                        }
                    }
                }
            }
            pool.invokeAll(tasks)
        } finally {
            pool.shutdown()
        }
        // 内存与盘上必须一致（重开读到的就是盘上的）。
        val inMemory = store.snapshot().grants.count { it.nodeId == "phone-c" }
        val reopened = AgentGrantStore(file).snapshot()
        assertEquals("byDevice", reopened.policy)
        assertEquals(inMemory, reopened.grants.count { it.nodeId == "phone-c" }, "盘上必须与内存一致：撤销不许被旧快照吞掉")
        assertEquals(store.snapshot().grants.size, reopened.grants.size, "整份台账都不许丢条目")
    }

    @Test
    fun anOversizedLedgerFileIsCappedInsteadOfLoadedWhole() {
        // L2 的判据：load() 会照单全收 JSON 数组里的 grants，而 readGrants 又把整份名单
        // 吐进 HTTP 响应体 —— 一个（坏掉的或伪造的）文件就能把控制面撑爆。
        // 装载后必须按上限截断，且淘汰方向是**收紧**：丢最旧的，留最新的。
        val count = 1_200
        val entries = (0 until count).joinToString(",") { i ->
            "{\"nodeId\":\"p" + i + "\",\"scope\":\"all\",\"grantedAt\":" + i + "}"
        }
        file.writeText("{\"policy\":\"byDevice\",\"grants\":[" + entries + "],\"requests\":[]}")

        val store = AgentGrantStore(file)
        val snap = store.snapshot()
        assertEquals("byDevice", snap.policy, "文件本身是好的，不该走 fail-closed")
        assertEquals(500, snap.grants.size, "装载后必须有界")
        assertTrue(snap.grants.any { it.nodeId == "p1199" }, "留下的必须是最新的那批")
        assertFalse(snap.grants.any { it.nodeId == "p0" }, "最旧的必须被丢掉（淘汰方向是收紧，不是放行）")
    }

    @Test
    fun roleHierarchyAndDelegatedApprovalPermissions() {
        val store = AgentGrantStore(file)
        store.setPolicy("byDevice")

        // 1. ro (旁观者)
        store.grant("observer-node", "workspace", "codex", wsId = "ws-1", access = "ro", grantedBy = "host", at = 1L)
        assertTrue(store.allows("observer-node", "codex", "ws-1", requireRw = false), "ro 可读")
        assertFalse(store.allows("observer-node", "codex", "ws-1", requireRw = true), "ro 拒绝写入/发指令")
        assertFalse(store.allows("observer-node", "codex", "ws-1", requireApproval = true), "ro 拒绝审批")

        // 2. rw (普通协作者)
        store.grant("collab-node", "workspace", "codex", wsId = "ws-1", access = "rw", grantedBy = "host", at = 2L)
        assertTrue(store.allows("collab-node", "codex", "ws-1", requireRw = true), "rw 允许下发指令")
        assertFalse(store.allows("collab-node", "codex", "ws-1", requireApproval = true), "rw 无权代批终端执行")

        // 3. admin (管理员但未开启委托代批)
        store.grant("admin-node", "workspace", "codex", wsId = "ws-1", access = "admin", isMyDevice = false, delegatedApproval = false, grantedBy = "host", at = 3L)
        assertTrue(store.allows("admin-node", "codex", "ws-1", requireRw = true), "admin 允许下发指令")
        assertFalse(store.allows("admin-node", "codex", "ws-1", requireApproval = true), "未开启委托审批时 admin 不可代批")

        // 4. admin (开启委托代批)
        store.grant("admin-delegated", "workspace", "codex", wsId = "ws-1", access = "admin", isMyDevice = false, delegatedApproval = true, grantedBy = "host", at = 4L)
        assertTrue(store.allows("admin-delegated", "codex", "ws-1", requireApproval = true), "开启委托审批的 admin 允许代批")

        // 5. myDevice (我的设备，私有信任圈)
        store.grant("my-phone", "workspace", "codex", wsId = "ws-1", access = "rw", isMyDevice = true, delegatedApproval = false, grantedBy = "host", at = 5L)
        assertTrue(store.allows("my-phone", "codex", "ws-1", requireApproval = true), "我的设备允许远程代批")

        // 重启后验证盘上数据留存
        val reopened = AgentGrantStore(file)
        assertTrue(reopened.allows("admin-delegated", "codex", "ws-1", requireApproval = true), "委托审批配置跨重启持久化")
        assertTrue(reopened.allows("my-phone", "codex", "ws-1", requireApproval = true), "我的设备配置跨重启持久化")
    }

    @Test
    fun theDefaultLedgerDoesNotLiveInTheTempDirectory() {
        // L1 的判据：默认落点曾经是 java.io.tmpdir —— 重启即失，丢了就退回出厂 auto（全放行）。
        // 一个能自己消失的授权台账比没有台账更坏，因为它看起来像配置过。
        val default = ControlPlane.defaultGrantFile()
        val tmp = System.getProperty("java.io.tmpdir").orEmpty()
        assertFalse(tmp.isNotEmpty() && default.absolutePath.startsWith(tmp), "默认台账不许落在临时目录：" + default)
        assertTrue(default.name.endsWith(".json"), "默认台账必须是个具名文件：" + default)
    }
}
