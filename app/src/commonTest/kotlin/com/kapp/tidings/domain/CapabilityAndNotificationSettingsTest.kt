// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.isAgentNode
import com.kapp.tidings.platform.InMemoryAppPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 能力声明与通知策略的落盘（审计 P1-7）。
 *
 * 这两组开关在接入之前只改屏上的 `rememberSaveable` 副本：拨过去，重开设置又弹回原位，
 * 对端看到的能力也从来没变过。接线之后它们分属两条**不同的**路径，这里把区别钉住：
 *
 * - 能力声明（tw1 / mrec1）住在协议档案里 → 落盘 + 递增 `profileRev` + 重启节点重新广播，
 *   因为对端是靠 `profileRev` 判断"资料变了没有"的；
 * - 通知策略是收到消息那一刻现读的 → 只落盘，不重启也不弹"已保存"横幅。
 */
class CapabilityAndNotificationSettingsTest {

    private fun settings(
        text: Boolean = true,
        record: Boolean = true,
        agent: Boolean = true,
        mentionOnly: Boolean = true,
        quietHours: Boolean = false,
    ) = RelaySettings(
        nodeId = "self",
        waitFrameCapability = text,
        recordMessageCapability = record,
        notifyAgentMessages = agent,
        notifyMentionOnly = mentionOnly,
        notifyQuietHours = quietHours,
    )

    // ---- caps() 的四种组合 ----

    /**
     * 两个开关逐项决定 caps，顺序与 [MsgTypes.MOBILE_CAPS] 一致
     * （它是 golden vector 的一部分：字段顺序变了，字节对拍就失败）。
     */
    @Test
    fun theTwoSwitchesNarrowTheDeclaredCapabilities() {
        assertEquals(MsgTypes.MOBILE_CAPS, settings().caps(), "两个都开 = 原来那套 MOBILE_CAPS，顺序都不能变")
        assertEquals(
            listOf(MsgTypes.CAP_MOBILE_TEXT, MsgTypes.CAP_AGENT_SESSION, MsgTypes.CAP_AGENT_QUEUE, MsgTypes.CAP_AGENT_WORKSPACE, MsgTypes.CAP_AGENT_GRANT, MsgTypes.CAP_AGENT_SESSION_PAGE, MsgTypes.CAP_TABLE_TEXT, MsgTypes.CAP_ATTACHMENT, MsgTypes.CAP_GROUP_TEXT_ATTRIBUTED),
            settings(record = false).caps(),
        )
        assertEquals(
            listOf(MsgTypes.CAP_MOBILE_RECORD, MsgTypes.CAP_AGENT_SESSION, MsgTypes.CAP_AGENT_QUEUE, MsgTypes.CAP_AGENT_WORKSPACE, MsgTypes.CAP_AGENT_GRANT, MsgTypes.CAP_AGENT_SESSION_PAGE, MsgTypes.CAP_TABLE_TEXT, MsgTypes.CAP_ATTACHMENT, MsgTypes.CAP_GROUP_TEXT_ATTRIBUTED),
            settings(text = false).caps(),
        )
        assertEquals(
            listOf(MsgTypes.CAP_AGENT_SESSION, MsgTypes.CAP_AGENT_QUEUE, MsgTypes.CAP_AGENT_WORKSPACE, MsgTypes.CAP_AGENT_GRANT, MsgTypes.CAP_AGENT_SESSION_PAGE, MsgTypes.CAP_TABLE_TEXT, MsgTypes.CAP_ATTACHMENT, MsgTypes.CAP_GROUP_TEXT_ATTRIBUTED),
            settings(text = false, record = false).caps(),
            "两个开关都关掉也不影响 agn1 / agq1 / aws1 / tbl1：它们是事实声明，不是用户偏好",
        )
    }

    /**
     * `agn1` 恒在、`age1` 恒不在。
     *
     * 两个方向都要钉：漏声明 `agn1` 会让对端不敢发 agent 帧（我们明明看得懂）；
     * 多声明 `age1` 更糟 —— 那是"支持批量投递与 `GET /v1/inbox?cursor=` 游标续读"的承诺，
     * 本端没实现，对端就会用我们接不住的方式发（PPE-1 §4：声明了却没实现 = 老用户被静默吞帧）。
     */
    @Test
    fun agentSessionBaselineIsAlwaysDeclaredAndCursorResumeNeverIs() {
        val declared = settings().caps() + settings(text = false, record = false).caps()
        assertTrue(MsgTypes.CAP_AGENT_SESSION in declared)
        assertFalse("age1" in declared, "age1 属于 PPE-1 P1.5，还没实现就不许声明")
    }

    @Test
    fun theDeclaredCapabilitiesReachTheProfileThatGetsBroadcast() {
        val profile = settings(text = false).toProfile(host = "192.168.1.44", ver = "relay-0.1.0")
        assertEquals(
            listOf(MsgTypes.CAP_MOBILE_RECORD, MsgTypes.CAP_AGENT_SESSION, MsgTypes.CAP_AGENT_QUEUE, MsgTypes.CAP_AGENT_WORKSPACE, MsgTypes.CAP_AGENT_GRANT, MsgTypes.CAP_AGENT_SESSION_PAGE, MsgTypes.CAP_TABLE_TEXT, MsgTypes.CAP_ATTACHMENT, MsgTypes.CAP_GROUP_TEXT_ATTRIBUTED),
            profile.caps,
        )
        assertEquals("self", profile.nodeId, "收窄 caps 不该顺手改动档案的其它字段")
        assertEquals("192.168.1.44", profile.host)
    }

    /** 默认（全新安装）与 `Profile.mobile` 的默认 caps 是同一条 —— 也就是"行为零变化"。 */
    @Test
    fun aFreshInstallDeclaresExactlyTheOldDefaultCaps() {
        val fresh = RelaySettings(nodeId = "self")
        assertEquals(MsgTypes.MOBILE_CAPS, fresh.caps())
    }


    // ---- agent 宿主角色（设计文档 §4 第 6 条：防「顺手统一」三份 profile）----

    /**
     * **承载 agent 节点的宿主才声明 `ag1`**（2026-10-14 设计文档 §2 方案 A）。
     *
     * 为什么这条值得单独钉：手机端靠 `ag1` 把对端分成「同事」与「Agent」两类，
     * 而两类误认都很难从截图看出来 ——
     * · 手机误声明 `ag1` ⇒ 对端把 AI 会话帧推给一个不承载会话的设备（只 ACK、业务层丢）；
     * · 桌面端漏声明 ⇒ 手机把它画成同事，且永远不向它要 DSH 会话（本轮要修的就是这个）。
     */
    @Test
    fun onlyTheAgentHostDeclaresAg1() {
        // 手机（默认）：不声明 ag1，且连它带来的那套帧能力也不该出现。
        val mobile = settings().caps()
        assertFalse(MsgTypes.CAP_AGENT in mobile, "手机端绝不能声明 ag1：$mobile")

        // 桌面端（advertiseAgent = true）：ag1 在最前，后面接同一套帧能力。
        val desktop = settings().caps(advertiseAgent = true)
        assertTrue(MsgTypes.CAP_AGENT in desktop, "承载 agent 的宿主必须声明 ag1：$desktop")
        assertEquals(MsgTypes.CAP_AGENT, desktop.first(), "ag1 是身份声明，排在最前（与 agentNode 同序）")
        // 除 ag1（身份）与 lnk1（下行通道）外与手机端逐项相同：桌面端承载会话与下行，
        // 但收发文本的能力不因角色而变。
        assertEquals(
            listOf(MsgTypes.CAP_AGENT, MsgTypes.CAP_LINK) + mobile,
            desktop,
            "除 ag1/lnk1 外必须与手机端逐项一致",
        )
    }

    /**
     * **只有宿主声明 lnk1**（task-31 真机缺陷的回归护栏）。
     *
     * 这条不钉住的话，后果是**静默**的：手机的 LinkClient 会把宿主当老端，一个拨号都不发，
     * 而消息仍然走 UDP/TCP —— 于是"消息一切正常、长连接其实从未建立"（真机就是这么过的 K1 验收）。
     * 所以它必须和 ag1 一样，有一条只读 caps 的断言盯着。
     */
    @Test
    fun onlyTheHostDeclaresTheDownlinkLinkCapability() {
        val desktop = settings().caps(advertiseAgent = true)
        assertTrue(MsgTypes.CAP_LINK in desktop, "宿主必须声明 lnk1，否则手机永远不拨号：$desktop")

        val mobile = settings().caps()
        assertFalse(MsgTypes.CAP_LINK in mobile, "手机不声明 lnk1：桌面不该反过来拨手机（K1/K2 是手机拨主机）")

        // 真正广播出去的是 profile.caps —— 只测 caps() 不够。
        val hostProfile = settings().toProfile(host = "mac-mini", ver = "relay-test", advertiseAgent = true)
        assertTrue(MsgTypes.CAP_LINK in hostProfile.caps, "profile.caps 必须含 lnk1：${hostProfile.caps}")
        val phoneProfile = settings().toProfile(host = "redmi", ver = "relay-test")
        assertFalse(MsgTypes.CAP_LINK in phoneProfile.caps, "手机 profile 不该含 lnk1：${phoneProfile.caps}")
    }

    /** 桌面端的 profile 必须带上 ag1（caps 进了 profile 才是真广播出去的东西）。 */
    @Test
    fun theAgentHostProfileCarriesAg1() {
        val profile = settings().toProfile(host = "mac-mini", ver = "relay-test", advertiseAgent = true)
        assertTrue(MsgTypes.CAP_AGENT in profile.caps, "profile.caps 必须含 ag1：${profile.caps}")
        assertTrue(profile.isAgentNode(), "isAgentNode() 必须为真 —— 手机端就是读它")

        val plain = settings().toProfile(host = "mac-mini", ver = "relay-test")
        assertFalse(plain.isAgentNode(), "默认（手机）必须不为 agent 节点：${plain.caps}")
    }
    /**
     * **显式保留清单**：这些 cap 本仓既不声明、也不消费（0/0），保留是为了与对端产品的既有
     * 约定（`ams1` 的 KDoc 指向「把 IM 文本流关联到 agent 会话线索」的互通），**不是「忘了加」**。
     *
     * 两个方向都断言，才能把「保留」与「漏加」分开：
     *  · 它必须在保留清单里 —— 有人明确认领了这个决定；
     *  · 它必须**不在** `MOBILE_CAPS`、也不在 `caps()`/`profile.caps` —— 否则就变成「悄悄生效」。
     *
     * 清单变了就红：要么把它接上（从清单里移走），要么再明确保留一个。
     */
    @Test
    fun reservedCapabilitiesAreExplicitlyNotEffective() {
        val reserved = listOf(MsgTypes.CAP_MSG_AGENT_SESSION)
        assertEquals(
            listOf("ams1"),
            reserved,
            "保留清单变了：新加的要写清为什么保留，接上的要从这里移走",
        )
        for (cap in reserved) {
            assertFalse(cap in MsgTypes.MOBILE_CAPS, "保留的 cap 不该在 MOBILE_CAPS 里（那是生效清单）：$cap")
            assertFalse(cap in settings().caps(), "保留的 cap 不该在 caps() 里：$cap")
            assertFalse(
                cap in settings().toProfile(host = "mac-mini", ver = "relay-test").caps,
                "保留的 cap 不该进 profile.caps（那才是真广播出去的东西）：$cap",
            )
        }
    }
    // ---- 控制器：落盘与重启语义 ----

    @Test
    fun turningOffACapabilityBumpsTheProfileRevisionAndAnnouncesTheRebroadcast() = runTest {
        val store = RecordingSettingsStore()
        val controller = RelayController(
            platform = InMemoryAppPlatform(),
            store = store,
            scope = backgroundScope,
        )
        val revBefore = controller.state.value.settings.profileRev

        controller.updateWaitFrameCapability(false)

        val saved = store.current
        assertEquals(false, saved?.waitFrameCapability, "开关必须落到设置文件")
        assertEquals(revBefore + 1, saved?.profileRev, "profileRev 不递增，对端会认为资料没变、继续按旧能力对待我们")
        assertEquals(
            BannerMessage.CapabilitiesSaved,
            controller.state.value.settingsNote,
            "重启前要告诉用户「正在重新广播」，否则开关动了、对端没动",
        )
    }

    @Test
    fun aCapabilityChangeSurvivesAReload() = runTest {
        val store = RecordingSettingsStore().apply {
            current = RelaySettings(nodeId = "self", waitFrameCapability = false)
        }

        val controller = RelayController(
            platform = InMemoryAppPlatform(),
            store = store,
            scope = backgroundScope,
        )

        assertEquals(false, controller.state.value.settings.waitFrameCapability)
        assertEquals(
            listOf(MsgTypes.CAP_MOBILE_RECORD, MsgTypes.CAP_AGENT_SESSION, MsgTypes.CAP_AGENT_QUEUE, MsgTypes.CAP_AGENT_WORKSPACE, MsgTypes.CAP_AGENT_GRANT, MsgTypes.CAP_AGENT_SESSION_PAGE, MsgTypes.CAP_TABLE_TEXT, MsgTypes.CAP_ATTACHMENT, MsgTypes.CAP_GROUP_TEXT_ATTRIBUTED),
            controller.state.value.settings.caps(),
            "重启后广播出去的能力必须还是用户关掉之后的那个（agn1 / agq1 / aws1 / tbl1 恒在，与开关无关）",
        )
    }

    /**
     * 通知三条只落盘。
     *
     * 与能力声明刻意不同：它不改档案、不递增 `profileRev`、也不写 `settingsNote`
     * （屏上那个开关自己就是反馈，再弹一条"已保存"是噪音）。
     */
    @Test
    fun notificationSwitchesPersistWithoutRebroadcastingAnything() = runTest {
        val store = RecordingSettingsStore()
        val controller = RelayController(
            platform = InMemoryAppPlatform(),
            store = store,
            scope = backgroundScope,
        )
        val revBefore = controller.state.value.settings.profileRev
        assertNull(controller.state.value.settingsNote)

        controller.updateNotifyAgentMessages(false)
        controller.updateNotifyMentionOnly(false)
        controller.updateNotifyQuietHours(true)

        val saved = store.current
        assertEquals(false, saved?.notifyAgentMessages)
        assertEquals(false, saved?.notifyMentionOnly)
        assertEquals(true, saved?.notifyQuietHours)
        assertEquals(revBefore, saved?.profileRev, "通知开关不该动档案版本")
        assertNull(controller.state.value.settingsNote, "通知开关不弹横幅")
    }

    /** 拨回原位（或重复拨同一档）不该再写一次设置文件。 */
    @Test
    fun settingTheSameValueTwiceDoesNotWriteAgain() = runTest {
        val store = RecordingSettingsStore()
        val controller = RelayController(
            platform = InMemoryAppPlatform(),
            store = store,
            scope = backgroundScope,
        )

        controller.updateNotifyQuietHours(true)
        val saves = store.saveCount
        controller.updateNotifyQuietHours(true)
        controller.updateWaitFrameCapability(true) // 默认就是 true

        assertEquals(saves, store.saveCount, "值没变就不该再写一次设置文件")
        assertNull(controller.state.value.settingsNote, "没变的改动也不该报重启")
    }

    /** 设置侧的替身：既当"设置文件"，又能被测试读回来看存了什么。 */
    private class RecordingSettingsStore(
        var current: RelaySettings? = null,
    ) : SettingsStore {
        var saveCount = 0
            private set

        override fun load(): RelaySettings? = current

        override fun save(settings: RelaySettings) {
            saveCount++
            current = settings
        }
    }
}
