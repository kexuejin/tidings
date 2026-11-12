// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.guards

import com.kapp.tenon.core.protocol.EventRouteReasons
import com.kapp.tenon.core.protocol.InboxUnresolved
import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「接入与通道」页的诚实性守卫（规范 §4.8 的验收第 7 条）。
 *
 * ## 为什么需要源码守卫
 *
 * 这一页的三条纪律都不是「跑起来会崩」那类，而是**安静地说错话**：
 *
 *  1. **不给桥装在线灯** —— `dsh-tenon` 不注册进 `PluginHost`，宿主没有它的心跳。
 *     画一盏绿灯不报错、不崩、界面还挺好看，只是在真出故障时把人引到错的方向；
 *  2. **空态与读不到要分开** —— 「一条可注入消息都没有」是正常状态，「读不到桥」是
 *     能力缺失；两者画成同一个空卡，用户就永远分不清是桥没跑还是没消息；
 *  3. **未解析必须给成因** —— 只给计数，用户除了重装什么都做不了（真机现场：
 *     席位没绑会话，页面只显示「未解析 1」）。
 *
 * 三条都在 Composable 内部、且都是「有没有那句话」的形状，本仓没有 Compose UI 测试
 * 基建 ⇒ 只能在源码层钉住（与 [PairingEntryPointsGuardTest] 同一取舍）。
 */
class AccessChannelHonestyGuardTest {

    private val page = "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/settings/" +
        "SettingsAccessChannelSubpage.kt"
    private val zhStrings = "composeApp/src/commonMain/composeResources/values/strings.xml"
    private val enStrings = "composeApp/src/commonMain/composeResources/values-en/strings.xml"
    private val desktopExtension = "orchestration/src/main/kotlin/com/kapp/tenon/orchestration/" +
        "DesktopExtension.kt"
    private val inboxRoute = "agentNode/src/main/kotlin/com/kapp/tenon/agent/ControlPlaneInboxRoutes.kt"

    @Test
    fun bridgeNeverGetsAnOnlineLight() {
        for (path in listOf(zhStrings, enStrings)) {
            val text = GuardSourceScan.read(path)
            // 反向探针：给桥加一条 `set_access_bridge_online`（或 offline）文案，
            // 就说明有人开始画灯了 —— 这条必须红。
            val light = Regex("name=\"set_access_bridge_(online|offline)\"").find(text)
            assertTrue(
                light == null,
                path + " 里出现了桥的在线/离线文案（" + light?.value + "）—— 宿主没有桥的心跳，" +
                    "这盏灯只能是假的；能证明的只有「最近一次取件」与判定统计。",
            )
        }
        // 反过来：必须真的把「能证明的事实」画出来，否则这条纪律退化成「什么都不说」。
        val source = GuardSourceScan.read(page)
        assertTrue(
            source.contains("set_access_bridge_poll") && source.contains("set_access_bridge_never_poll"),
            "页面既没有「最近一次取件」也没有它的故障态 —— 桥没跑时用户看不出任何线索",
        )
        assertTrue(
            source.contains("set_access_bridge_note"),
            "页面没有那句「这里没有在线灯，只有它来取件时的那条证据」—— 读者会把沉默当结论",
        )
    }

    @Test
    fun unreadableAndEmptyAreTwoDifferentStates() {
        val source = GuardSourceScan.read(page)
        assertTrue(
            source.contains("set_access_bridge_unreadable"),
            "读不到桥（手机 / 宿主没起）必须有专门的一句，不能混进空态",
        )
        assertTrue(
            source.contains("set_access_bridge_empty"),
            "「还没有任何消息被判定过」是一等状态（isEmpty），不能画成 0/0/0 的统计",
        )
    }

    @Test
    fun everyUnresolvedTokenHasACauseInThePage() {
        val source = GuardSourceScan.read(page)
        // 词表在 core（单一真源）；页面按 token 查表。新增一个 token 却忘了给文案 ⇒ 红。
        // 页面仍然会**原样显示**未知 token（`set_access_cause_unknown`），
        // 但那条兜底不是「翻译」，不该拿它顶掉新词条的映射。
        for (token in InboxUnresolved.ALL) {
            val constant = "InboxUnresolved." + tokenConstantNameOf(token)
            assertTrue(
                source.contains(constant),
                "页面没有为未解析成因 " + token + " 给出文案映射（缺 " + constant + "）—— " +
                    "只给计数的话，用户除了重装什么都做不了。",
            )
        }
    }

    @Test
    fun unpairedIsOnlyClaimedWhenTheHostWasReadable() {
        // 判据本身是纯函数 `pairingStateOf`，三态由 `AccessChannelProjectionTest` 直接测
        // （那里有反向探针：删掉 Unknown 一支即红）。这里只查**页面确实用了它** ——
        // 页面自己再写一遍 `pin == null ⇒ 未配对` 会丢掉 Unknown 那一档，
        // 手机壳就会把「读不到信任库」画成「未配对」（反向的假红灯）。
        //
        // 只匹配调用点而不是整行赋值：换行、格式化、改变量名都不该让这条假红。
        val source = GuardSourceScan.read(page)
        assertTrue(
            source.contains("pairingStateOf("),
            "页面没有用 pairingStateOf 判配对 —— 自己写一遍会丢掉「读不到」这一档",
        )
    }

    @Test
    fun thePortReadsTheControlPlaneTraceInsteadOfANewOne() {
        // 「台账只此一份」如今由**编译器**保证：`InboxInjectionTrace` 的构造器是 internal，
        // `orchestration` 这侧根本造不出第二份。所以这里不再断言「文件里没有 new」——
        // 那条断言既会被 `InboxInjectionTrace( )` 这种写法绕过，也会被无害的改名弄成假红。
        // 剩下的是接线检查：端口读的是控制面交出来的那一份。
        val source = GuardSourceScan.read(desktopExtension)
        assertTrue(
            source.contains("createdPlane.inboxTrace"),
            "接入面端口没有读控制面的那一份台账 —— 两份实例的症状是「桥在干活、页面说没判定过」",
        )
    }

    @Test
    fun neverInjectedIsOnlySaidInTheJudgedBranch() {
        // 「一次都没能注入」是一句结论，只能用「判定过、但都没投成」来支撑。
        // 空窗口时先说它、下面再说「还没有判定过任何消息」，同一张卡里自相矛盾 ——
        // 等于用「还没收到证据」冒充「收到了坏消息」。
        //
        // 三档判据在 `bridgeBlockStateOf`（有单测：NeverPolled / Empty / Judged 分开）。
        // 这里按**分支切片**查，而不是匹配整行：分支体里加一句话、换行、改变量名都不该假红。
        val source = GuardSourceScan.read(page)
        val emptyAt = source.indexOf("BridgeBlockState.Empty ->")
        val judgedAt = source.indexOf("BridgeBlockState.Judged ->")
        assertTrue(
            emptyAt >= 0 && judgedAt > emptyAt,
            "页面没有按 BridgeBlockState 分支（或 Empty 不在 Judged 之前）—— 三态又写回去了",
        )
        assertTrue(
            !source.substring(emptyAt, judgedAt).contains("set_access_bridge_never_inject"),
            "「一次都没能注入」出现在空窗口那一档 —— 那是用「还没收到证据」冒充「收到了坏消息」",
        )
        assertTrue(
            source.indexOf("set_access_bridge_never_inject", judgedAt) > judgedAt,
            "「有判定」这一档没有说「一次都没能注入」—— 桥装上了却一条没投成时，页面上没有任何线索",
        )
    }

    @Test
    fun theInboxRouteRecordsBothThePollAndTheVerdict() {
        // 只匹配标识符、不匹配参数表：`record(peerId = row.peerId, …)` 换个参数顺序或
        // 拆成多行都不该让这条假红。这是接线检查，判据在 `InboxInjectionTraceTest`。
        val source = GuardSourceScan.read(inboxRoute)
        assertTrue(
            source.contains("inboxTrace.recordPoll"),
            "取件没被记录 —— 「桥没在跑」与「没有 @ 消息」在页面上会长得一模一样",
        )
        assertTrue(
            source.contains("inboxTrace.record("),
            "注入判定没被记录 —— 页面统计永远为空（0/0/0 的假读数）",
        )
    }

    /**
     * token → `InboxUnresolved` 里的常量名。
     *
     * **机械换算**（`no-unbound-session` → `NO_UNBOUND_SESSION`）而不是再抄一张对照表：
     * 这张表本身就是「同一份词表抄两处」，而抄两处必错一处。
     */
    private fun tokenConstantNameOf(token: String): String =
        token.uppercase().replace('-', '_')

    @Test
    fun theUnreadableBridgeLineDoesNotClaimPairingIsFine() {
        // 手机壳在 port == null 时**读不到信任库**（`pairingKnown = port != null` ⇒ false），
        // 所以它没有资格说「配对正常」。这句只能讲分工与去处；「节点读数不受影响」可以，
        // 因为那读的是本机切片。
        //
        // 反向探针：把这句改成「配对与节点连接正常；桥状态请在桌面端查看」，这条必须红。
        val banned = listOf("配对", "正常", "paired", "normal", "healthy")
        for (path in listOf(zhStrings, enStrings)) {
            val line = Regex("name=\"set_access_bridge_unreadable\">([^<]*)<")
                .find(GuardSourceScan.read(path))?.groupValues?.get(1)
            assertTrue(line != null, path + " 里找不到 set_access_bridge_unreadable 这句")
            val text = line.orEmpty()
            for (word in banned) {
                assertTrue(
                    !text.contains(word),
                    path + " 的「读不到桥」那句里出现了「" + word + "」—— 手机壳读不到信任库，" +
                        "这句话它说不出口（原句：" + text + "）",
                )
            }
        }
    }

    @Test
    fun routingUnreadableAndNoBlockingAreTwoDifferentStates() {
        val source = GuardSourceScan.read(page)
        assertTrue(
            source.contains("set_access_routing_unreadable"),
            "读不到路由计数（手机 / 宿主没起转发）必须有专门的一句，不能混进「一条都没拦下」",
        )
        assertTrue(
            source.contains("set_access_routing_empty"),
            "「读到了、没有事件被拦下」是个好消息，得能说出来；否则这块只剩沉默",
        )
        // 不再断言 `if (routingUi == null)`：那是**结构**（可空类型必须分开处理），
        // 编译器已经保证 null 不会被当成空列表遍历。
    }

    @Test
    fun everyBlockingRouteReasonHasCopyInThePage() {
        val source = GuardSourceScan.read(page)
        // 与 everyUnresolvedTokenHasACauseInThePage 同一条纪律：词表在 core，
        // 页面按 token 查表。新增一档成因却忘了给文案 ⇒ 红（页面仍会原样显示 token，
        // 但那条兜底不是「翻译」，不能拿它顶掉新词条）。
        for (token in EventRouteReasons.BLOCKING) {
            val constant = "EventRouteReasons." + tokenConstantNameOf(token)
            assertTrue(
                source.contains(constant),
                "页面没有为路由成因 " + token + " 给出文案映射（缺 " + constant + "）—— " +
                    "只给一个裸 token，用户不知道该怎么办。",
            )
        }
    }

    @Test
    fun theRoutingPortReadsTheForwardersOwnCounts() {
        val source = GuardSourceScan.read(desktopExtension)
        assertTrue(
            source.contains("eventForwarder.eventDiagnostics"),
            "接入面端口没有读事件转发自己的计数 —— 另建一份的话，页面会显示「从来没拦下过任何事件」，" +
                "而群里确实少了消息",
        )
    }
}
