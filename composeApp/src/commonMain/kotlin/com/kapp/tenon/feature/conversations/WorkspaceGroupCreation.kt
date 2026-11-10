// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 榫卯 Tenon contributors

package com.kapp.tenon.feature.conversations

import com.kapp.tenon.core.protocol.resolveVirtualAgentKey
import com.kapp.tenon.domain.DesktopPaneActions
import com.kapp.tenon.domain.model.WorkspaceUi

/**
 * 建群即选项目目录的**接线helpers**（项目目录决策稿 D5/D14：新群建群即设指针；
 * 建群成功后立即把群绑到项目目录并把该 agent 入驻）。
 *
 * ## 为什么收成一个 helper 而不是每处壳各写一遍
 *
 * 手机壳（App.kt）、桌面壳（DesktopShell.kt）、会话侧栏（DesktopConversationsPane.kt）
 * 共用同一个建群对话框 —— 「建群 → 设指针 → 入驻」三步是**同一件事**，四处抄写就是
 * 四处漂移的入口（AGENTS §2.6：内容一份，外壳参数化）。动作面收在
 * [DesktopPaneActions] 上，预览夹具与真壳走同一条签名。
 *
 * ## 手机端（无目录端口）
 *
 * `hostDirectoryPort` 为 null（DesktopExtension 只在桌面宿主装配）⇒ 两个 helper 都
 * 只建群、不设指针 —— 群信息里的目录行显示「由宿主授权」（声明投影），保持现行为。
 */
/**
 * 分支 B（快速建群）的后半段：群已建成（[groupId]），把 wsId 解析到 dirId 并设指针，
 * 随后把该 agent 入驻。
 *
 * - wsId → dirId：先查目录台账里**同一路径**的既有条目；没有就 `register(wsPath, wsTitle)`
 *   登记一条（幂等：同路径不换 dirId，登记即纳入节点白名单）再取它的 dirId；
 * - 入驻走 `addGroupAgent`（D6：不问目录，席位就地继承群项目目录）。建群路径已经
 *   把该工作区的 agent 随群声明过一遍 —— 入驻找到既有席位就**沿用既有名字**、只按
 *   指针重写执行投影，不会换出第二个 @token；
 * - 工作区没报路径 / 本机没有目录端口（手机）⇒ 只跳过绑定步骤，群照常用。
 */
internal fun DesktopPaneActions.bindWorkspaceGroupToDirectory(groupId: String, ws: WorkspaceUi) {
    val port = hostDirectoryPort ?: return
    val path = ws.wsPath?.trim()?.takeIf { it.isNotEmpty() } ?: return
    val dirId = port.snapshot()?.directories.orEmpty().firstOrNull { it.path == path }?.dirId
        ?: port.register(path, ws.wsTitle)?.dirId
        ?: return
    setGroupProjectDirectory(groupId, dirId)
    val agentId = resolveVirtualAgentKey(null, ws.agentId, ws.agentKind) ?: return
    addGroupAgent(groupId, agentId, null)
}

/**
 * 分支 B（快速建群）整段：建群 → 绑目录 → 入驻。签名与旧调用点一致
 * （`createWorkspaceGroup(name, wsId, members, description)` 未动，绑定是追加步骤）。
 */
internal fun DesktopPaneActions.createWorkspaceGroupWithDirectory(
    name: String,
    ws: WorkspaceUi,
    members: List<String>,
): String? {
    val gid = createWorkspaceGroup(name, ws.wsId, members, ws.wsPath ?: ws.wsId)
    if (gid != null) bindWorkspaceGroupToDirectory(gid, ws)
    return gid
}

/**
 * 分支 A（关联本地全新目录）：登记 → 建群 → 设指针。此刻还没有工作区行（wsId=null）、
 * 也没有可入驻的 agent —— 登记让目录先进白名单，agent 宣告这条路径之后由
 * 「去暴露 / 重新解析」接上执行点。
 *
 * 登记失败（路径无法归一）不阻断建群：目录绑定是追加能力，群本身照常成立。
 */
internal fun DesktopPaneActions.createWorkspaceGroupForNewDirectory(
    path: String,
    groupName: String,
    members: List<String>,
    description: String = path,
): String? {
    val registered = hostDirectoryPort?.register(path, groupName)
    val gid = createWorkspaceGroup(groupName, null, members, description)
    if (gid != null && registered != null) {
        setGroupProjectDirectory(gid, registered.dirId)
    }
    return gid
}
