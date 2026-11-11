// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.domain.model.AgentSessionKey
import com.kapp.tenon.domain.model.visibleInDsh

/**
 * 文件柜「智能体产物」段最多列几行。
 *
 * 这一屏是 `LazyColumn` 里的一张卡，不是分页列表：产物表是**全局**的（所有宿主 ×
 * 所有会话），不设上限时它会把下面四张设置卡全挤到屏外。「最近有人放进来」那段用的是
 * `takeLast(20)`，这里取同一档 —— 两段在同一个屏上的体量相当。
 */
public const val CABINET_ARTIFACT_LIMIT: Int = 20

/**
 * 文件柜里「一个项目目录」这一组产物。
 *
 * 分组键是 **wsId**，不是群：一个项目目录可以被多个群共用，按群分会把同一批产物
 * 复制成好几份。[groupName] 只是这一组**绑定**的那个群（归因不了就是 null，不编造）。
 */
public data class CabinetArtifactGroupUi(
    /** 分组键；null = 归不到任何项目目录的那一档。 */
    val wsId: String?,
    /** 项目目录名；null = 未命名（UI 翻成「未命名项目目录」，**不拿 wsId 顶替**）。 */
    val wsTitle: String?,
    /** 该目录绑定的群名；null = 没绑群或归因不了。 */
    val groupName: String?,
    val rows: List<CabinetArtifactRowUi>,
)

/**
 * 一件产物在文件柜里的一行 —— **只有元数据**。
 *
 * ## 为什么没有路径、也没有「打开」
 *
 * `AgentArtifactEntity` 里只有 `file_id`，**没有本地路径**：产物帧按协议就不带二进制，
 * 本体要靠 `file_id` 指的那条 `file-msg` 落地。所以这一行点开的是**产出它的那条会话**
 * （文件气泡在那儿，由既有文件动作去开），而不是这一行自己 —— 把一个 `fileId` 当
 * 可打开路径是编造。
 */
public data class CabinetArtifactRowUi(
    val name: String,
    val mime: String?,
    val bytes: Long?,
    /** false = 这一帧按协议就不带文件（只有元数据）。 */
    val hasFile: Boolean,
    /** 产出它的会话（点这一行要落到那里）。 */
    val sessionId: String,
    /** 产出它的宿主。 */
    val peerId: String,
    /** 该会话的项目目录；null = 会话没报 wsId 或报的是合成键。 */
    val wsId: String?,
    /** 本端收到这一帧的时刻。 */
    val at: Long,
)

/**
 * 把 `agentArtifacts` 取成文件柜要画的分组（UX 规范 §2.5③ 的「只做视图」那一档）。
 *
 * ## 排序与上限
 *
 * 全局按**收到时刻倒序**取前 [limit] 件，再分组 —— 先截断后分组，否则「每组各取几件」
 * 会让旧产物挤掉新产物。组的先后 = 组内最新那件产物的先后（`groupBy` 保留首次出现序）。
 *
 * ## 可见性沿用同一条纪律
 *
 * 子代理静默 / 空壳会话（[visibleInDsh] 之外）里的产物**不进这一屏**：它们在卡片流与
 * 看板里本来就不存在，产物不该是那个例外。会话帧还没到（查不到）时**不丢** ——
 * 「还没收到」与「不该显示」是两件事，前者归到未归属那一档。
 */
public fun cabinetArtifactGroups(
    agent: AgentSlice,
    settingsSlice: SettingsSlice,
    limit: Int = CABINET_ARTIFACT_LIMIT,
): List<CabinetArtifactGroupUi> {
    if (limit <= 0) return emptyList()
    val sessions = agent.agentSessions
    // wsId → 目录名：会话自报的 wsTitle 优先，其次 `agent-workspace` 宣告里的标题。
    // 同一目录在多次会话里可能只有一条报了名字，这里按首次命中去重。
    val titles = HashMap<String, String>()

    val rows = ArrayList<CabinetArtifactRowUi>(limit)
    for (artifact in agent.agentArtifacts.values.sortedByDescending { it.at }) {
        if (rows.size >= limit) break
        val session = sessions[AgentSessionKey(artifact.peerId, artifact.sessionId)]
        if (session != null && listOf(session).visibleInDsh().isEmpty()) continue
        val wsId = session?.wsId?.takeIf { it.isNotBlank() }?.takeIf { !isSyntheticWsId(it) }
        val wsTitle = session?.wsTitle?.takeIf { it.isNotBlank() }
            ?: wsId?.let { id ->
                agent.agentWorkspaces.values.firstOrNull { it.wsId == id }?.wsTitle?.takeIf { it.isNotBlank() }
            }
        if (wsId != null && wsTitle != null) titles.putIfAbsent(wsId, wsTitle)
        rows += CabinetArtifactRowUi(
            name = artifact.name,
            mime = artifact.mime?.takeIf { it.isNotBlank() },
            bytes = artifact.bytes,
            hasFile = artifact.fileId != null,
            sessionId = artifact.sessionId,
            peerId = artifact.peerId,
            wsId = wsId,
            at = artifact.at,
        )
    }

    return rows.groupBy { it.wsId }.map { (wsId, groupRows) ->
        CabinetArtifactGroupUi(
            wsId = wsId,
            wsTitle = wsId?.let { titles[it] },
            // 群名走 `groupBoundToWorkspace` 这**一个**来源（与看板审批归因同一把尺子）。
            groupName = groupBoundToWorkspace(wsId, settingsSlice.settings.groups)
                ?.name?.takeIf { it.isNotBlank() },
            rows = groupRows,
        )
    }
}
