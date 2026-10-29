// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

/**
 * core 侧的能力常数**台账**（task-48 F2）。
 *
 * 为什么需要它：今天两次「静默不生效」（`lnk1` / `att1`）都是同一个形状 —— 代码支持，
 * 但那个键**没进真正生效的那份清单**（`RelaySettings.caps()`），而 core 里没有任何测试
 * 会发现这件事。这个台账把每个 `CAP_*` 分成三档，逼着"加一个新 cap"必须在这里表态：
 *
 *  1. 被 [MsgTypes.MOBILE_CAPS] / [MsgTypes.AGENT_CAPS] 声明（core 自己的两份 profile 清单）；
 *  2. 不在上面两份里，但有**明确理由**（[excused]：只读、预埋、或由壳按平台/开关条件声明）；
 *  3. 其它 ⇒ 测试失败。
 *
 * 第三档不存在，就是这条测试要守的不变量。
 *
 * 注意：`RelaySettings.caps()` 在 composeApp，core 看不到；所以第 2 档里"由壳条件声明"那几个
 * 只能记**理由**，不能在这里断言它真的被声明了 —— 那一侧的守卫在 composeApp 的
 * `CapabilityAndNotificationSettingsTest`（两份角度不同、并存）。
 */
internal object CapabilityInventory {

    /** 常量名 → wire 取值。加一个新 cap 必须在这里补一行（jvmTest 的源码守卫会强制这件事）。 */
    val constants: Map<String, String> = mapOf(
        "CAP_AGENT" to MsgTypes.CAP_AGENT,
        "CAP_AGENT_SESSION" to MsgTypes.CAP_AGENT_SESSION,
        "CAP_MOBILE_TEXT" to MsgTypes.CAP_MOBILE_TEXT,
        "CAP_MOBILE_RECORD" to MsgTypes.CAP_MOBILE_RECORD,
        "CAP_TABLE_TEXT" to MsgTypes.CAP_TABLE_TEXT,
        "CAP_FILE_DIRECT" to MsgTypes.CAP_FILE_DIRECT,
        "CAP_FILE_CHANNEL" to MsgTypes.CAP_FILE_CHANNEL,
        "CAP_SCREEN_CHANNEL" to MsgTypes.CAP_SCREEN_CHANNEL,
        "CAP_SHARE" to MsgTypes.CAP_SHARE,
        "CAP_REMOTE_VIEW" to MsgTypes.CAP_REMOTE_VIEW,
        "CAP_REMOTE_SHARE" to MsgTypes.CAP_REMOTE_SHARE,
        "CAP_AVATAR" to MsgTypes.CAP_AVATAR,
        "CAP_AGENT_QUEUE" to MsgTypes.CAP_AGENT_QUEUE,
        "CAP_MSG_AGENT_SESSION" to MsgTypes.CAP_MSG_AGENT_SESSION,
        "CAP_AGENT_WORKSPACE" to MsgTypes.CAP_AGENT_WORKSPACE,
        "CAP_AGENT_GRANT" to MsgTypes.CAP_AGENT_GRANT,
        "CAP_AGENT_SESSION_PAGE" to MsgTypes.CAP_AGENT_SESSION_PAGE,
        "CAP_LINK" to MsgTypes.CAP_LINK,
        "CAP_ATTACHMENT" to MsgTypes.CAP_ATTACHMENT,
        "CAP_GROUP_TEXT_ATTRIBUTED" to MsgTypes.CAP_GROUP_TEXT_ATTRIBUTED,
        "CAP_AGENT_INVOKE" to MsgTypes.CAP_AGENT_INVOKE,
    )

    /** core 的两份 profile 清单合并（去重）：被它们声明的 cap 是"活的"。 */
    val declaredByCoreProfiles: Set<String> =
        (MsgTypes.MOBILE_CAPS + MsgTypes.AGENT_CAPS).toSet()

    /**
     * 不在 [declaredByCoreProfiles] 里、但有理由的 cap → 理由。
     *
     * 每条都必须写得出**为什么它不是"漏声明"**；写不出就该把它加进某份清单或删掉常数。
     */
    val excused: Map<String, String> = mapOf(
        MsgTypes.CAP_FILE_DIRECT to
            "只读不声明：手机不对外承诺「可免确认写盘」（见 MsgTypes.CAP_FILE_DIRECT 的 KDoc）",
        MsgTypes.CAP_FILE_CHANNEL to
            "由壳按实验开关条件声明（RelaySettings.caps 的 fileOverChannel）："
                + "只有 luciola 数据面确认可用才声明，否则对端推字节而本端永远等文件",
        MsgTypes.CAP_SCREEN_CHANNEL to
            "由壳按同一条开关条件声明（RelaySettings.caps 的 screenOverChannel）："
                + "与 fch1 同一族的第二块数据面；声明了却不起画面监听，对端会一直等握手",
        MsgTypes.CAP_MSG_AGENT_SESSION to
            "预埋：阶段 C1 未实现，本端既不声明也不解析（见 docs/plans/2026-10-12-relay-agent-session-scheme.md）",
        MsgTypes.CAP_SHARE to
            "由壳按开关条件声明（RelaySettings.caps 的 isShareEnabled）",
        MsgTypes.CAP_REMOTE_VIEW to
            "由壳按平台能力条件声明（isShareSupported）",
        MsgTypes.CAP_REMOTE_SHARE to
            "由壳按平台能力条件声明（isShareSupported）",
        MsgTypes.CAP_AVATAR to
            "由壳按用户开关条件声明（customAvatar）",
    )
}
