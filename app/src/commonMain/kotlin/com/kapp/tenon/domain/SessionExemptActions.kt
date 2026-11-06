// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

/**
 * 卡片动作 id 的**单槽编码**（会话免审）—— 一个事实只有一个来源。
 *
 * ## 为什么住在领域层
 *
 * 编码侧（手机卡片点击）与解码侧（宿主编排）必须逐字一致，否则「免审的是哪张卡」
 * 会在两端各猜一次。它只是字符串前缀编解码，零 Compose、零 IO，因此两侧共同依赖
 * :app 是最短的路径。此前解码侧住在桌面壳里、靠 import composeApp 的 UI 文件拿到
 * 编码函数 —— 那是把「壳依赖 UI」误当成了「契约共享」；下沉编排层时它就编不过了。
 *
 * ## 编码格式
 *
 * `session-exempt|<cardId>`；裸前缀 `session-exempt` 表示没有指定卡（维持既有路由，
 * 不携带目标）。竖线之后的 cardId 是宿主定位会话、把原卡翻成「已免审」的唯一线索。
 */

/** 会话免审开关的 subAction id（分级免审；L1 会话免审的中期形态 §4.2）。 */
const val SYSTEM_ACTION_SESSION_EXEMPT = "session-exempt"

/** 见文件头：免审动作与所属卡的编码，空 cardId 退回裸前缀。 */
fun sessionExemptActionIdOf(cardId: String): String {
    val cleaned = cardId.trim()
    if (cleaned.isEmpty()) return SYSTEM_ACTION_SESSION_EXEMPT
    return SYSTEM_ACTION_SESSION_EXEMPT + "|" + cleaned
}

/** 见 [sessionExemptActionIdOf]：非免审前缀或空 cardId 返回 null（维持既有路由）。 */
fun sessionExemptCardIdOf(actionId: String): String? =
    actionId.takeIf { it.startsWith(SYSTEM_ACTION_SESSION_EXEMPT + "|") }
        ?.removePrefix(SYSTEM_ACTION_SESSION_EXEMPT + "|")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/** 该动作串是不是会话免审动作（前缀判定，不做解码）。 */
fun isSessionExemptAction(actionId: String): Boolean =
    actionId.startsWith(SYSTEM_ACTION_SESSION_EXEMPT + "|")
