// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.HostDirectoryStore
import com.kapp.tenon.domain.HostDirectoryPort
import com.kapp.tenon.domain.HostDirectorySnapshotUi
import com.kapp.tenon.domain.HostDirectoryUi
import com.kapp.tenon.domain.WhitelistUi

/**
 * [HostDirectoryPort] 的桌面实现（项目目录决策稿 §5.1）：把 UI 侧的形状逐字段映射到
 * [HostDirectoryStore]（宿主本地档案，落盘 `host-directories.json`）。
 *
 * 端口与 store 是**同一个实例**的两个视图：面板经这里写的每一条，控制面的
 * `/v1/host/` 路由读到的就是它（store 内部一把写锁 + 同锁落盘，不存在两个实例各持一份
 * 半新不旧档案的形态）。手机端不装配这个类 ⇒ `hostDirectoryPort` 为 null ⇒ 只读投影。
 */
class DesktopHostDirectoryPort(
    private val store: HostDirectoryStore,
    /**
     * 「群项目目录 → agent wsId」的解析回调（决策稿 §3.1）。装配方把它接到
     * ControlPlane 的解析器实例上 —— 判据只有那一份，端口不许自建第二份
     * 「dirId → wsId」。null（测试装配）= 一律未解析。
     */
    private val resolveGroupWsId: ((groupId: String, agentKey: String) -> String?)? = null,
) : HostDirectoryPort {

    override fun snapshot(): HostDirectorySnapshotUi = HostDirectorySnapshotUi(
        directories = store.directories().map { d ->
            HostDirectoryUi(d.dirId, d.path, d.displayName, d.gitRemote)
        },
        groupPointers = store.groupPointers(),
        whitelist = store.whitelist().map { w -> WhitelistUi(w.dirId, w.readOnly) },
    )

    override fun register(path: String, displayName: String?): HostDirectoryUi? {
        if (path.isBlank()) return null
        val dir = runCatching { store.register(path, displayName) }.getOrNull() ?: return null
        return HostDirectoryUi(dir.dirId, dir.path, dir.displayName, dir.gitRemote)
    }

    override fun setGroupPointer(groupId: String, dirId: String?): Boolean =
        store.setGroupPointer(groupId, dirId)

    override fun setWhitelist(dirId: String, readOnly: Boolean, allowed: Boolean): Boolean =
        store.setWhitelist(dirId, readOnly, allowed)

    override fun resolveForGroup(groupId: String, agentKey: String): String? =
        resolveGroupWsId?.invoke(groupId, agentKey)
}
