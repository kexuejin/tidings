// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

/**
 * 派单 preflight 的**四问**（项目目录决策稿 D7/§4.3）：执行设备在线、agent 在位、
 * 项目目录已解析、授权。
 *
 * ## 为什么是纯函数、为什么在 core
 *
 * 判据要**两端同源**：手机端画「执行设备卡片」（发送前看得见的四问），宿主端执行前的
 * fail-closed 判定也按同一组问题回答 —— 各写一遍就是「显示说能跑、执行说不行」的分叉。
 * 这里只回答 ok/不 ok 与**稳定的 key**；用户可读的文案归 UI（strings.xml 按 key 取），
 * 本文件不携带任何展示文案。
 *
 * ## 如实口径（照抄决策稿 §4.5）
 *
 * - 宿主离线**不是拦截**：群文本走可靠队列落盘，宿主上线后补发（store-and-forward
 *   是既有语义）—— ok=false 只改变显示（「执行设备离线 · 已排队」），不改变发送。
 * - 「授权」对发送端通常是**未知**（授权台账在宿主侧）：未知 = 不判红、如实标注
 *   「由宿主判定」；宿主执行时的授权判定走 [AgentExecAuthorizer]，不在这里。
 * - 目录未解析 = 真正的执行阻断（宿主侧退单）：显示「未暴露本项目目录 / 去暴露」。
 */
object DispatchPreflight {

    /** 四问的稳定 key（UI 文案按 key 取，不按位置）。 */
    enum class Key { HOST_ONLINE, AGENT_PRESENT, DIRECTORY_RESOLVED, AUTHORIZED }

    data class Answer(val key: Key, val ok: Boolean, /** null = 判定者无法知道（如实标注，不编） */
                      val unknown: Boolean = false)

    /**
     * @param hostOnline 执行设备（席位宿主）当前在线（presence 判据）。
     * @param agentPresent 该 agent 有席位且在位（工作区/会话台账可见）。
     * @param directoryResolved 群项目目录对该 agent 解析出了执行点（席位投影 `defaultWsId`
     *   非空）；null = 群还没设项目目录（同为未解析）。
     * @param authorized 发送端能否回答「授权」；null = 不知道（宿主判定）。
     */
    fun evaluate(
        hostOnline: Boolean,
        agentPresent: Boolean,
        directoryResolved: Boolean?,
        authorized: Boolean? = null,
    ): List<Answer> = listOf(
        Answer(Key.HOST_ONLINE, hostOnline),
        Answer(Key.AGENT_PRESENT, agentPresent),
        Answer(
            Key.DIRECTORY_RESOLVED,
            directoryResolved == true,
            unknown = directoryResolved == null,
        ),
        Answer(Key.AUTHORIZED, authorized ?: true, unknown = authorized == null),
    )

    /** 全绿才谈得上「派单就该成功」；有任何一问不 ok，宿主执行面按 fail-closed 退单。 */
    fun isDispatchable(answers: List<Answer>): Boolean = answers.all { it.ok }
}
