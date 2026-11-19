// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

/**
 * 派单失败 blocked 卡的**分类码**（方案 §3.5）：chip 上给排查的码，一句话给人，
 * 主行动按码选 —— 三个东西缺一就算没做完。
 *
 * ## 为什么住 `:app` 而不是编排层
 *
 * 与 `TeamExecutionSaveResult.Rejected` 同一手法（编排层产事实，域层给形状）：
 * `composeApp` 不依赖 `:orchestration`，UI 只认这个枚举选文案与主行动；
 * 编排层的 `Target.NotConfigured` 投影成这个形状，**不许直接漏进 UI**。
 *
 * ## 判定顺序（调用方必须遵守，§3.5）
 *
 * **先人、再权、后配置**：[TARGET_MISSING] → [TEAM_NOT_IN_GROUP] → [NOT_AUTHORIZED] →
 * [NO_DIRECTORY] → [DIR_NOT_ALLOWED] → [NO_DRIVER] → [DRIVER_MISSING]。
 * 宿主离线**不进**这组码（不是配置错误，重连即自愈）。
 *
 * 这些码是**本地投影**，不进任何协议帧 —— blocked 卡 JSON 里带的 code 字段随卡片走，
 * 老手机端按宽容解码忽略未知键（不画 chip 而已，不崩）。
 */
enum class DispatchBlockedCode {

    /** 这个员工用哪个运行时跑，定不下来（没配执行档、档案也没底座）。 */
    NO_DRIVER,

    /** 这个群还没绑项目目录。 */
    NO_DIRECTORY,

    /** 这台电脑没被授权访问这个目录。 */
    DIR_NOT_ALLOWED,

    /** 你没有调用它的权限。 */
    NOT_AUTHORIZED,

    /** 这支队伍还没加进这个群（token 命中本机团队档案 ∧ 该群无该队席位）。 */
    TEAM_NOT_IN_GROUP,

    /** 这个名字已经不在了（可能刚改名或注销；或席位在别的设备上）。 */
    TARGET_MISSING,

    /** 配的那个运行时不在这台电脑上（唯一一条「装上就能好」的）。 */
    DRIVER_MISSING,
}

/**
 * blocked 卡「把这支队伍加进本群」主行动的 id 前缀（`attach-team:<teamId>`）。
 *
 * 发送端（宿主拼卡面按钮）与接收端（宿主处理动作）必须是**同一个字面量** ——
 * 分开写两份时，「按钮发的是 attach-team:tm-1、分支认的是 attach-team」这类漂移
 * 只在真机点下去那一刻暴露，且表现为「点了没反应」。UI 侧（`composeApp`）也读这条。
 */
public const val ATTACH_TEAM_ACTION_PREFIX: String = "attach-team:"
