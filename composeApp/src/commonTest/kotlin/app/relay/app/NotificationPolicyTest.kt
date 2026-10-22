// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「通知」卡三个开关的判定。
 *
 * 这三个开关以前只改屏上的本地状态，对真收到的消息毫无影响 —— 审计 P1-7 的原话是
 * 「接线或撤下，不能留在屏上骗人」。接线之后判定全部收在 [shouldNotify] 这一个纯函数里，
 * 所以这里逐条钉死屏上那句承诺：
 *
 * - `set_more_notif_agent`：「Agent 会话有新消息时提醒」
 * - `set_more_notif_mention`：「其余消息静默进列表」
 * - `set_more_notif_quiet`：「22:00 – 08:00 不响铃」
 *
 * 默认值刻意与屏上的默认档逐一相同（[RelaySettings] 里那两个 true、一个 false），
 * 否则"屏上显示 ON、存储里是 OFF"就是两个事实来源。
 */
class NotificationPolicyTest {

    private fun settings(
        agent: Boolean = true,
        mentionOnly: Boolean = true,
        quietHours: Boolean = false,
    ) = RelaySettings(
        nodeId = "self",
        notifyAgentMessages = agent,
        notifyMentionOnly = mentionOnly,
        notifyQuietHours = quietHours,
    )

    private fun notify(
        settings: RelaySettings,
        fromAgent: Boolean = false,
        isGroup: Boolean = false,
        mentioned: Boolean = false,
        localHour: Int = 12,
        convId: String? = null,
    ) = shouldNotify(
        settings = settings,
        fromAgent = fromAgent,
        isGroup = isGroup,
        mentioned = mentioned,
        localHour = localHour,
        convId = convId,
    )

    // ---- 会话级静音（task-18）----

    /**
     * **被静音的会话一律不弹**，哪怕它是对端 agent、哪怕当前不是静默时段。
     *
     * 静音与「全局通知开关」是两件事：全局开关是「我什么都不想收」，
     * 静音是「这一个别吵我」。这条钉住的是：静音真的生效，而不只是改了个设置字段。
     */
    @Test
    fun aMutedConversationNeverNotifies() {
        val muted = settings().copy(mutedConvIds = listOf("group:g1"))
        assertFalse(
            notify(muted, isGroup = true, mentioned = true, convId = "group:g1"),
            "静音的会话不该因为「@ 到我」就绕过去",
        )
    }

    /** 静音是**按会话**的：别的会话照常提醒。 */
    @Test
    fun mutingOneConversationLeavesOthersAlone() {
        val s = settings().copy(mutedConvIds = listOf("group:g1"))
        assertTrue(notify(s, convId = "group:g2"), "没被静音的会话照常提醒")
    }

    /** 不知道会话键（null）时不能吞掉通知 —— 宁可多提醒一次。 */
    @Test
    fun anUnknownConversationIsNotTreatedAsMuted() {
        val s = settings().copy(mutedConvIds = listOf("group:g1"))
        assertTrue(notify(s, convId = null), "查不到键就当没静音，别把通知吞掉")
    }

    // ---- 静默时段 ----

    /**
     * 22:00–08:00 跨零点。
     *
     * 写成 `hour in 22..8` 是空区间，那个开关会永远不生效；这里把边界两侧都钉住，
     * 22 点与 7 点算静默、8 点与 21 点不算。
     */
    @Test
    fun quietHoursWrapPastMidnight() {
        for (hour in listOf(22, 23, 0, 1, 7)) {
            assertTrue(isQuietHour(hour), "$hour 点应在静默时段内")
        }
        for (hour in listOf(8, 12, 18, 21)) {
            assertFalse(isQuietHour(hour), "$hour 点不该静默")
        }
    }

    /** 静默时段是**最高优先级**：连 @ 到我的群消息和 Agent 会话也不响铃，与文案的"不响铃"一致。 */
    @Test
    fun quietHoursSilenceEvenTheMessagesThatWouldOtherwiseRing() {
        val quiet = settings(agent = true, mentionOnly = true, quietHours = true)
        assertFalse(notify(quiet, fromAgent = true, localHour = 23), "Agent 会话在静默时段也不响")
        assertFalse(notify(quiet, isGroup = true, mentioned = true, localHour = 7), "@ 到我的群消息也不响")
        assertTrue(notify(quiet, localHour = 12), "同一个配置在白天照常提醒 —— 静默是有时段边界的")
    }

    // ---- Agent 消息通知 ----

    @Test
    fun agentNotificationsOffSilencesOnlyAgentSessions() {
        val muted = settings(agent = false)
        assertFalse(notify(muted, fromAgent = true), "关掉之后 Agent 会话静默")
        assertTrue(notify(muted, fromAgent = false), "这只是 Agent 分类开关，不该顺带关掉同事的消息")
    }

    // ---- 仅被 @ 时提醒 ----

    @Test
    fun mentionOnlySilencesGroupChatterThatNeverNamedMe() {
        // 群里没人 @ 我 → 静默进列表（这就是"其余消息静默进列表"的承诺）。
        assertFalse(notify(settings(), isGroup = true, mentioned = false))
    }

    @Test
    fun mentionOnlyLetsThroughTheGroupMessageThatNamesMe() {
        assertTrue(notify(settings(), isGroup = true, mentioned = true))
    }

    @Test
    fun turningMentionOnlyOffRestoresEveryGroupMessage() {
        assertTrue(notify(settings(mentionOnly = false), isGroup = true, mentioned = false))
    }

    /**
     * 私聊不受「仅被 @」影响。
     *
     * 协议里 `mentions` 只存在于群消息载荷（`GroupTextMsgPayload`），一对一没有这个概念，
     * 所以对私聊按"没人 @ 我"来判定等于把每一条私聊都静音掉 —— 那是把"少打扰"做成了"收不到"。
     */
    @Test
    fun privateChatsAreNeverJudgedByMentions() {
        assertTrue(
            notify(settings(mentionOnly = true), isGroup = false, mentioned = false),
            "一对一没有 @ 这回事，必须照常提醒",
        )
    }

    // ---- 全新安装的默认档 ----

    @Test
    fun theDefaultSettingsNotifyEverythingOutsideQuietHours() {
        val fresh = RelaySettings(nodeId = "self")
        assertTrue(notify(fresh, fromAgent = true), "默认提醒 Agent 会话")
        assertTrue(notify(fresh, isGroup = true, mentioned = true), "默认提醒 @ 到我的群消息")
        assertTrue(notify(fresh), "默认提醒同事的私聊")
        assertTrue(notify(fresh, localHour = 23), "静默时段默认是关的，半夜也提醒")
    }

    // ---- 戳一碰（协议 §8） ----

    /**
     * 戳一碰必须发通知：以前收端只往聊天里写一行系统提示，人不在那一屏就完全看不见 ——
     * 而对端按下这个动作的意思恰恰是"现在看我一眼"。
     */
    @Test
    fun aNudgeIsSurfacedUnlessWeAreInQuietHours() {
        assertTrue(shouldNotifyNudge(settings(quietHours = false), localHour = 23), "静默时段关着，半夜也提醒")
        assertFalse(shouldNotifyNudge(settings(quietHours = true), localHour = 23), "静默时段内不响")
        assertTrue(shouldNotifyNudge(settings(quietHours = true), localHour = 12), "同一个配置白天照常提醒")
    }

    /**
     * 戳一碰**不**受「Agent 消息通知」开关管。
     *
     * 那个开关管的是 Agent 自己往外说的状态/进度（最吵的一类）；戳一碰是屏幕后面的人
     * 明确按了一下。把两者绑在一起，会出现"用户关了 Agent 通知，于是人被戳了也毫无反应"。
     */
    @Test
    fun theAgentNotificationSwitchDoesNotSwallowANudge() {
        assertTrue(
            shouldNotifyNudge(settings(agent = false), localHour = 12),
            "关掉 Agent 消息通知不该让人戳不动我",
        )
    }
}
