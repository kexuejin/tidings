// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

/**
 * 宿主本地项目目录的**壳层端口**（项目目录决策稿 §5.1）：UI 读/写目录档案、群项目
 * 指针与节点目录白名单的唯一通路。
 *
 * ## 为什么是端口而不是直调
 *
 * 目录档案、指针、白名单的真源都在宿主进程（`:agentNode` 的落盘 store），`:app`（域层）
 * 不许 import agentNode 类型（`:domainComposeFreeGate` 同一条边界纪律），实现经桌面壳注入。
 * 与 `teamExecutionPort` 同一形状 —— **手机端端口为 null ⇒ UI 只读投影**（目录档案由
 * 宿主管理，手机只看到群里已指向的目录名，不能改宿主的档案）。
 */
interface HostDirectoryPort {

    /**
     * 整份快照（目录档案 + 群指针 + 白名单）。null = 该壳没有目录面（手机端常态），
     * UI 据此只画「本群指向的目录」投影，不画编辑区。
     */
    fun snapshot(): HostDirectorySnapshotUi?

    /**
     * 登记一个项目目录（幂等：同一路径不换 dirId）。登记即纳入白名单为可读写。
     * 返回 null = path 无效（空白 / 无法归一），**不是**「已存在」—— 已存在会返回既有条目。
     */
    fun register(path: String, displayName: String?): HostDirectoryUi?

    /**
     * 设置 / 清除一个群的项目目录指针（dirId = null 清除）。
     * false = 群 id 空白或 dirId 不存在，什么都没改。
     */
    fun setGroupPointer(groupId: String, dirId: String?): Boolean

    /** 放行 / 收回一个目录的白名单资格（allowed = false 即收回）。false = 目录不存在。 */
    fun setWhitelist(dirId: String, readOnly: Boolean, allowed: Boolean): Boolean

    /**
     * 「这个群的项目目录，对这个 agent 解析出哪个 wsId」（决策稿 §3.1 四规则）。
     *
     * null = 群没设指针 / 指针悬空 / 该 agent 未宣告这条路径 —— 调用方按「未暴露」处理，
     * **不猜**：猜错的后果是命令在错误的项目里跑。与 [snapshot] 读同一份 store 实例。
     */
    fun resolveForGroup(groupId: String, agentKey: String): String? = null

    /**
     * 这个壳能不能**弹系统目录选择器**（macOS/Windows 的原生选目录窗）。
     *
     * ## 为什么这件事属于目录端口
     *
     * 「项目目录从哪来」有两个入口：从台账里挑一条，或者**去磁盘上指一个**。后者要弹
     * 系统窗，而弹窗是壳的能力。把它摆在端口上，是为了让**所有**登记目录的地方
     * （建群表单、群设置里的「登记新目录」）共用一个判据 —— 从前这条能力挂在
     * `AppPlatform.saveDirectoryPicker` 上，可它在全仓**零调用点**：三个壳都实现了，
     * 却没有任何界面接它，用户只能靠手打绝对路径，而 macOS 上的绝对路径基本打不对。
     *
     * 默认 false，UI 据此**整颗不画**「浏览」按钮（本仓纪律：画不出来的能力不画，
     * 而不是画一颗点下去没反应的）。手机端 `hostDirectoryPort` 本身为 null，走不到这里。
     */
    val canPickDirectory: Boolean get() = false

    /**
     * 弹系统目录选择器，返回**绝对路径**；null = 用户取消（或该壳没有这个能力）。
     *
     * 取消不是失败：调用方把 null 当成「什么都没发生」，不要清掉已填的路径。
     * 返回的路径可能带系统相关的大小写/分隔符，登记前一律交给 [register] 归一。
     */
    suspend fun pickDirectory(): String? = null
}

/** 宿主本机的一个项目目录（UI 形状；与存储形状逐字段对齐）。 */
data class HostDirectoryUi(
    val dirId: String,
    val path: String,
    val displayName: String,
    val gitRemote: String?,
)

/** 节点目录白名单的一条（UI 形状）。默认 deny：没有条目的目录一律不放行。 */
data class WhitelistUi(
    val dirId: String,
    val readOnly: Boolean,
)

/** 目录台账的整份快照（UI 形状；GET /v1/host/directories 的同构投影）。 */
data class HostDirectorySnapshotUi(
    val directories: List<HostDirectoryUi>,
    /** groupId → dirId；群「选中的项目目录」在本机的落点。 */
    val groupPointers: Map<String, String>,
    val whitelist: List<WhitelistUi>,
)

/** 某个群当前指向的项目目录（UI 投影）；null = 没设指针 / 指针悬空 / 目录档案查无此事。 */
fun HostDirectorySnapshotUi?.pointedDirectoryOf(groupId: String): HostDirectoryUi? {
    val snap = this ?: return null
    val dirId = snap.groupPointers[groupId] ?: return null
    return snap.directories.firstOrNull { it.dirId == dirId }
}
