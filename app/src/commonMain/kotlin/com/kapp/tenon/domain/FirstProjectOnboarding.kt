// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.domain.model.WorkspaceUi

/**
 * 新手冷启动引导要的那一件事：**哪台设备 + 哪个目录**值得当作「第一个项目」。
 *
 * ## 为什么这不是「空态」
 *
 * UX 规范 §2.6 的字面条件是「未绑定任何项目群时展示引导卡片」。如果照着「空态」去实现，
 * 会撞上一个死结：看板的空态判据是 [`InboxWorkspaceUnitsUi.hasVisibleContent`]，
 * 而一条**已宣告的目录**本身就让它成立 —— 也就是说**空态成立时一个目录都没有**，
 * 引导卡上的「立即关联第一个项目」按下去无目录可绑，是一颗死按钮。
 *
 * 所以引导卡该出现的时刻是另一个：**已经有目录、但一个都还没绑群**。那正是「新用户首次
 * 配对设备后进入第四页」的真实画面 —— 屏幕上列着几个目录名，用户不知道下一步是建群。
 *
 * ## 三条 fail-closed
 *
 * 1. **已经有任何目录绑了群 ⇒ 不给引导。** 「第一个项目」这句话此时已经是假的。
 * 2. **只认别人的设备。** 文案说的是「发现局域网可用设备」；本机宿主不是"发现"来的。
 *    桌面端因此几乎不会看到这张卡 —— 那是**对的**：它自己的目录已经直接列在看板上了。
 * 3. **设备必须在线。** 离线设备上「该设备已就绪」是假话；宁可不引导，也不给一句假承诺。
 */
public data class FirstProjectUi(
    /**
     * 要绑的那个目录。
     *
     * 直接喂 `WorkspaceGroupPromptDialog(workspace = …)` —— 那是仓里既有的「精简建群
     * 弹窗」，规范要求的「10 秒内完成第一个项目协同群」就是它，不另造一支。
     */
    val workspace: WorkspaceUi,
    /** 设备显示名（`PeerUi.displayName` 的口径：备注 > 昵称 > 短 nodeId，恒非空）。 */
    val deviceName: String,
)

/**
 * 挑出「第一个项目」的候选；null = 此刻不该给引导（见 [FirstProjectUi] 的三条 fail-closed）。
 *
 * 挑法：先按目录宣告的到达时刻倒序（用户刚打开的那个项目最可能是他要的），同一时刻按
 * wsId 升序兜底，然后取**第一台在线设备**的第一个目录。合成 wsId 过滤掉 —— 它不是
 * 真目录，绑上去只会得到一个假的群项目目录。
 */
public fun firstProjectCandidate(
    agent: AgentSlice,
    node: NodeSlice,
    settingsSlice: SettingsSlice,
): FirstProjectUi? {
    val groups = settingsSlice.settings.groups
    val selfNodeId = settingsSlice.settings.nodeId
    val workspaces = agent.agentWorkspaces.values

    // 1. 已经有一个目录绑了群 ⇒ 这不是「第一个项目」了。
    if (workspaces.any { ws -> groupBoundToWorkspace(ws.wsId, groups) != null }) return null

    // 2. 只在别人的、在线的设备上挑。
    val (workspace, device) = workspaces
        .asSequence()
        .filter { it.wsId.isNotBlank() && !isSyntheticWsId(it.wsId) }
        .filter { it.peerId != selfNodeId }
        .sortedWith(compareByDescending<WorkspaceUi> { it.at }.thenBy { it.wsId })
        .mapNotNull { ws -> peer(ws.peerId, node, settingsSlice)?.let { ws to it } }
        .firstOrNull { (_, host) -> host.online }
        ?: return null

    return FirstProjectUi(workspace = workspace, deviceName = device.displayName)
}
