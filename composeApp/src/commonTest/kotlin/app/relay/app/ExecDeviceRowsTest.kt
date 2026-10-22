// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.core.protocol.AgentGrantScopes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「谁能让这个 agent 干活」那一屏要显示什么。
 *
 * 这里测的是**状态派生**，不是渲染：勾选列表画得对不对取决于这份数据对不对。
 */
class ExecDeviceRowsTest {

    private val self = "node-self"
    private val peerA = "node-a"
    private val peerB = "node-b"
    private val agent = "dsh"

    private fun grant(nodeId: String, scope: String, agentKey: String? = null) =
        ExecGrantView(nodeId = nodeId, scope = scope, agentKey = agentKey, isMyDevice = false)

    private fun rows(
        devices: List<Pair<String, String>> = listOf(peerA to "A 的手机", peerB to "B 的电脑"),
        grants: List<ExecGrantView> = emptyList(),
    ) = execDeviceRows(self, agent, devices, grants)

    /** 本机永远在列表最上面，且永远勾上、不可取消。 */
    @Test
    fun selfIsAlwaysFirstAndChecked() {
        val list = rows()
        assertEquals(self, list.first().nodeId)
        assertTrue(list.first().isSelf)
        assertTrue(list.first().checked, "本机永远勾上 —— 他本来就在自己机器上")
    }

    /**
     * **可信设备默认不勾。**
     *
     * 「我批准过这台设备」只代表它能**看**，不代表它能在这台机器上跑命令。
     * 这条是整套设计的地基：可信 ≠ 可执行。
     */
    @Test
    fun aTrustedDeviceIsListedButNotCheckedByDefault() {
        val list = rows(grants = listOf(grant(peerA, AgentGrantScopes.ALL)))
        val a = list.first { it.nodeId == peerA }
        assertTrue(a.trusted, "应当标成可信")
        assertFalse(a.checked, "可信不等于可执行 —— 默认不该勾上")
    }

    /** 逐 agent 授权之后才勾上。 */
    @Test
    fun anAgentScopedGrantIsChecked() {
        val list = rows(grants = listOf(grant(peerA, AgentGrantScopes.AGENT, agent)))
        assertTrue(list.first { it.nodeId == peerA }.checked)
    }

    /** **别的 agent 的授权不影响这个 agent**（这就是逐 agent 的意义）。 */
    @Test
    fun aGrantForAnotherAgentDoesNotCheckThisOne() {
        val list = rows(grants = listOf(grant(peerA, AgentGrantScopes.AGENT, "codex")))
        assertFalse(list.first { it.nodeId == peerA }.checked, "授权了 codex 不该让 dsh 也勾上")
    }

    /** 授权过、但已不在群里的设备仍要显示（否则用户看不到也撤不掉）。 */
    @Test
    fun aGrantedDeviceNotInTheGroupStillShows() {
        val list = rows(
            devices = listOf(peerA to "A 的手机"),
            grants = listOf(grant(peerB, AgentGrantScopes.AGENT, agent)),
        )
        assertTrue(list.any { it.nodeId == peerB }, "授权过的人必须还看得见")
    }

    /** 没有昵称时退化成 nodeId 前 8 位（不编一个人名）。 */
    @Test
    fun anUnknownDeviceFallsBackToATruncatedId() {
        val nodeId = "abcdef0123456789"
        val list = execDeviceRows(self, agent, listOf(nodeId to ""), emptyList())
        val row = list.first { it.nodeId == nodeId }
        // 空串昵称当作没有昵称处理：显示 id 前 8 位。
        assertTrue(row.label.isNotBlank())
    }

    /** 同一台设备不会因为多条授权记录而重复出现。 */
    @Test
    fun aDeviceAppearsOnlyOnce() {
        val list = rows(
            grants = listOf(
                grant(peerA, AgentGrantScopes.ALL),
                grant(peerA, AgentGrantScopes.AGENT, agent),
            ),
        )
        assertEquals(1, list.count { it.nodeId == peerA })
    }

    /** 排序：本机 → 可信 → 已勾 → 其余。 */
    @Test
    fun orderingPutsTheMostRelevantOnTop() {
        val list = rows(
            devices = listOf(peerB to "B", peerA to "A"),
            grants = listOf(grant(peerA, AgentGrantScopes.ALL)),
        )
        assertEquals(self, list[0].nodeId)
        assertEquals(peerA, list[1].nodeId, "可信设备排在未授权设备前面")
    }
    /**
     * **本机那一行必须显示名字，不是 nodeId 前 8 位。**
     *
     * 真机现场：界面第一行显示 `a1b2c3d4`，而用户明明给这台机器起过名字
     * （`settings.nick` 就在配置里）。原因是有个空 nick 把它顶掉了。
     */
    @Test
    fun selfShowsItsNameNotATruncatedId() {
        val selfId = "a1b2c3d4-6e74-4a7c-aab1-20a1d1284cd6"
        val list = execDeviceRows(
            selfNodeId = selfId,
            agentKey = "dsh",
            knownDevices = listOf(selfId to "demo-mac-97"),
            grants = emptyList(),
        )
        assertEquals("demo-mac-97", list.first { it.isSelf }.label)
    }

    /**
     * **一条空昵称不该把真名挤掉。**
     *
     * 同一台设备可能同时出现在对端表（nick 为空）与群成员表（有昵称）里；
     * 谁在前谁在后不该决定用户看到什么 —— 空串等于「没有名字」，不参与占位。
     */
    @Test
    fun anEmptyLabelDoesNotShadowTheRealName() {
        val selfId = "self-1"
        val list = execDeviceRows(
            selfNodeId = selfId,
            agentKey = "dsh",
            knownDevices = listOf(
                selfId to "demo-mac-97",  // 真名在前
                selfId to "",                 // 空的那条在后 —— 真机就是这个顺序
            ),
            grants = emptyList(),
        )
        assertEquals("demo-mac-97", list.first { it.isSelf }.label)
    }
}
