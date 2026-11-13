// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import kotlin.test.Test
import kotlin.test.assertTrue
import com.kapp.tenon.testsupport.GuardSourceScan

/**
 * Phase 4 切片**形状**守卫（配合 `RelayStateSlicesTest` 的行为断言）。
 *
 * 钉三件事，都是行为用例区分不出来的：
 *
 * 1. **字段清单固定**：每个 slice 的字段集合必须与蓝图 §4.4.2 的表一致。往 [ChatSlice]
 *    里加一个 agent 字段会让「改 agent 表不产生 chatFlow 新值」这条语义静默失效 ——
 *    而所有行为用例可能全绿（多一个字段只是多一次发射）。
 * 2. **切片只读**：切片文件里不得出现 `var` / `MutableStateFlow` / 写方法。一旦切片能写，
 *    它就变成第二份存储，单一写入方的前提没了。
 * 3. **派生必须等值合并**：7 条 flow 都必须 `distinctUntilChanged()` 后 `stateIn(`，
 *    否则「投影后相等的值不发射」这条性质要靠 StateFlow 的内部实现兜底（不同来源的
 *    StateFlow 实现并不保证同一语义，而且去掉它会丢掉「先合并再共享」的本意）。
 *
 * 另外按蓝图 §4.4 的补充要求：投影里不许 `toList` / `toSet` 这类容器重建 ——
 * 那会造出「内容相同但引用不同」的中间值，等值判断与重建时机都变得不可预测。
 *
 * 反向验证：往 ChatSlice 加 `agentSessions`、删掉某条 flow 的 `distinctUntilChanged`、
 * 或在投影里写 `.toList()`，本用例变红。
 */
class RelayStateSlicesGuardTest {

    private val slicesPath = "app/src/commonMain/kotlin/com/kapp/tenon/domain/RelayStateSlices.kt"

    /** 与蓝图 §4.4.2 一一对应；改这里必须同时改文档与行为用例。 */
    private val expected: Map<String, List<String>> = mapOf(
        "ChatSlice" to listOf("messages", "conversationSummaries", "unreadByPeer", "pendingByPeer"),
        "AgentSlice" to listOf(
            "agentSessions", "agentEvents", "agentApprovals", "agentTasks", "agentRuns", "agentInvokes",
            "agentArtifacts", "agentWorkspaces", "agentGrants",
            // 免批规则快照（cap atr1）：按宿主分键的整表快照，归 agent 面（它跟审批/授权
            // 同一族的宿主事实）。readFailed 是它的伴生格：读没送出去的宿主，
            // 界面据此区分「读不到」与「没有规则」（真机走查抓到的首帧撒谎）。
            "toolApprovalRules", "toolRulesReadFailed", "pendingGrantRequests",
            "sessionPageLoading", "sessionPageExhausted",
        ),
        // selfNodeId（ARP-10）：身份恢复会换值，QR/分享面必须响应式读它 —— 归网络面
        // （身份/地址事实同面），不是跨面塞入。
        "NodeSlice" to listOf("network", "selfNodeId", "peers", "mdnsServices", "scanProgress"),
        "TransferSlice" to listOf("transferLogs", "cabinet", "screenSession"),
        "SettingsSlice" to listOf("settings", "updateState", "restarting"),
        "ChromeSlice" to listOf("banner", "settingsNote", "focusRequest", "externalStoreTick"),
        "AvatarSlice" to listOf("avatarCache", "pickedAvatarBytes", "pickedAvatarHash", "pickedAvatarName"),
    )

    @Test
    fun sliceFieldsMatchTheDocumentedPartition() {
        val source = live()
        for ((name, fields) in expected) {
            val actual = fieldsOf(source, name)
            assertTrue(
                actual == fields,
                name + " 的字段集合必须与蓝图 §4.4.2 一致：期望 " + fields + "，实际 " + actual +
                    "。多一个跨面字段 = 该切片退化成「改什么都会变」的聚合，行为用例未必察觉",
            )
        }
    }

    @Test
    fun slicesAreReadOnlyProjections() {
        val source = live()
        for (forbidden in listOf(" var ", "MutableStateFlow", "fun update", "RelayStateWriter")) {
            assertTrue(
                !source.contains(forbidden),
                "切片文件出现 \"" + forbidden + "\"：切片必须是纯投影，不能成为第二份存储/第二个写入方",
            )
        }
    }

    @Test
    fun everySliceIsDistinctAndShared() {
        val source = live()
        for (flow in listOf("chat", "agent", "node", "transfer", "settings", "chrome", "avatar")) {
            val start = source.indexOf("val " + flow + ": StateFlow<")
            assertTrue(start >= 0, "找不到切片 flow：$flow")
            val end = source.indexOf("\n" + "\n", start)
            val decl = source.substring(start, if (end < 0) source.length else end)
            assertTrue(decl.contains("distinctUntilChanged()"), "$flow 的派生必须 distinctUntilChanged() 做等值合并")
            assertTrue(decl.contains(".stateIn("), "$flow 的派生必须 stateIn(scope, WhileSubscribed, 初值) 暴露")
        }
    }

    @Test
    fun projectionsDoNotRebuildContainers() {
        val source = live()
        for (forbidden in listOf(".toList(", ".toSet(", ".toMap(", ".associate(")) {
            assertTrue(
                !source.contains(forbidden),
                "投影里出现 \"" + forbidden + "\"：容器重建会造出「内容相同但引用不同」的中间值，等值判断与重建时机变得不可预测；直接取不可变字段",
            )
        }
    }

    /** 从源码里取一个 data class 的属性名（按声明顺序）。 */
    private fun fieldsOf(source: String, className: String): List<String> {
        val start = source.indexOf("data class " + className + "(")
        assertTrue(start >= 0, "找不到 data class " + className)
        val open = source.indexOf("(", start)
        val close = source.indexOf("\n" + ")", open)
        assertTrue(close > open, className + " 的声明形状不像 data class(...)：预期每个属性一行")
        return source.substring(open + 1, close)
            .split("\n")
            .mapNotNull { line ->
                val m = Regex("""^\s*val ([A-Za-z_][A-Za-z0-9_]*)\s*:""").find(line)
                m?.groupValues?.get(1)
            }
    }

    private fun live(): String = GuardSourceScan.stripComments(GuardSourceScan.read(slicesPath))
}
