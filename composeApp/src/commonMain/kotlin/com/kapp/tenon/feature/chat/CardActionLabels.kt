// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import androidx.compose.runtime.Composable
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.card_action_cancel_task
import com.kapp.tenon.generated.resources.card_action_send_as_adjustment
import com.kapp.tenon.generated.resources.card_action_send_as_new_task
import com.kapp.tenon.generated.resources.card_action_start_task
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * 卡片动作文案的**跨端约定**（卡片内层 JSON，不是协议白名单字段）。
 *
 * ## 为什么动作标签要有键，而不是直接发一句人话
 *
 * 卡片是单个 JSON 对象随群帧广播的，发送方只有一个壳、接收方可能是任意语言环境。
 * 宿主在发送时把 `label` 定死成中文（或宿主的语言），手机上就再也翻不回去了。
 * 所以发出方给**稳定键**（跨语言不变）、渲染端按本端资源翻 —— 与文件产物的
 * `intent` 键同一套做法（见 [AgentCardArtifact] 的 KDoc）。
 *
 * ## 兼容边界（硬要求）
 *
 * - **旧 payload（只有 label）**：`labelKey` 为 null → 用 label 原文，逐字同旧行为；
 * - **旧端（不认识 labelKey）**：只读 label；新卡里 label 仍然保留，旧端照样有字；
 * - **本端不认识的新键**：回退 label；label 也为空 → actionId。**绝不空白**。
 */
internal object CardActionLabelKeys {
    /**
     * 键 → 本端词条。键一旦发布就不许改语义（改了等于让旧端把新键翻成旧话），
     * 需要新语义时加新键。
     */
    val keys: Map<String, StringResource> = mapOf(
        "card.action.start_task" to Res.string.card_action_start_task,
        "card.action.cancel_task" to Res.string.card_action_cancel_task,
        "card.action.send_as_adjustment" to Res.string.card_action_send_as_adjustment,
        "card.action.send_as_new_task" to Res.string.card_action_send_as_new_task,
    )
}

/**
 * 动作文案的**纯回退规则**（可单测，不碰 Compose）：
 * 本端认识的键 → 本地词条；否则 payload 里的 label；label 空 → actionId。
 *
 * @param localized 已按 [CardActionLabelKeys] 翻好的词条；null = 没有键或本端不认识
 */
internal fun cardActionLabel(action: AgentCardAction, localized: String?): String =
    localized?.takeIf { it.isNotBlank() }
        ?: action.label.takeIf { it.isNotBlank() }
        ?: action.actionId

/** 渲染层入口：按键查本端词条；没有键 / 键未知时返回 null，交给 [cardActionLabel] 回退。 */
@Composable
internal fun localizedCardActionLabel(action: AgentCardAction): String? {
    val resource = action.labelKey?.let { CardActionLabelKeys.keys[it] } ?: return null
    return stringResource(resource, *action.labelArgs.toTypedArray())
}
