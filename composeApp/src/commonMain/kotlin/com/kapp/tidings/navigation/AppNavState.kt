// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.navigation

import androidx.compose.runtime.saveable.Saver

/**
 * 导航状态。
 *
 * 管两件事：选中的顶级页签，以及是否正停在某个会话的详情里。`activeSessionId != null`
 * 等价于「聊天详情盖在页签之上」——紧凑布局下正是它决定要不要画返回按钮、要不要接管
 * 系统返回键。
 *
 * 这里**没有**「并排打开的多个会话」这个概念。聊天详情顶部原来有一条会话人标签条
 * （`openSessionIds`），已按产品决策整体移除：它切的是**会话人**而不是 agent session，
 * 且 40dp 换来的只是与上一行同样的标题。要换会话就回列表 —— 列表还多给预览、时间、
 * 未读与分节。
 *
 * 纯数据 + 纯函数，不依赖 Compose，也不依赖 `RelayUiState`：未知页签 id 该不该被接受
 * 这类规则必须能被测试盯住 —— 理由同 `resolveAppLayoutMode`，规则本身就是这块功能的
 * 全部内容。（本文件末尾的 `AppNavStateSaver` 是唯一的例外：它只是把同样纯的
 * `toSaverValue` / `appNavStateFromSaverValue` 包成 Saver，规则本体仍是纯函数。）
 */
data class AppNavState(
    val selectedTabId: String = AppNavItems.first().id,
    /** 当前会话；null 表示没停在聊天详情里。 */
    val activeSessionId: String? = null,
    /**
     * Agent 档当前选中的 agent session；null = 还没选过，交给 [resolveAgentSessionId] 回落。
     *
     * 放在状态里而不是界面自己的 `remember`：默认选中与回落这两条规则必须能被
     * `commonTest` 盯住 —— 与本文件既有定位一致（纯数据 + 纯函数）。
     */
    val activeAgentSessionId: String? = null,
    /**
     * 扫码页是否正盖在最上面。
     *
     * 它没有自己的顶级页签：扫码是「往节点表里加一台机器」这条路上的一个动作，
     * 加完就回到节点页 —— 给它一个页签等于让用户以为它是一个可以"停在里面"的地方。
     * 与设置第二屏同一个做法（见 `App.kt` 的 `showSettingsMore`）。
     */
    val scanning: Boolean = false,
    /**
     * 节点详情页正看着哪个节点；null = 没停在节点详情里。
     *
     * 把节点页从「点一行直冲会话」改成「点一行进这个节点的详情」：
     * 手机是整屏 `m-nodedetail`，桌面是左栏选、右栏出详情。于是"选中了哪个节点"
     * 第一次需要在导航状态里有一格。
     *
     * ## 它替换的是页签内容，不是整个页签
     *
     * `m-nodedetail` 在原型注册表里**没有** `full:true`（`prototype/relay.html:2379`
     * 逐字 `{id:'m-nodedetail', form:'mobile', label:'节点详情', tab:'nodes'}`），
     * 而 `device()`（`:2529`）只用 `full` 决定 `noNav`。所以这一屏**底栏照画** ——
     * 与聊天详情（`m-chat` 有 `full:true`）不是一回事。详情替换的是节点页签那一片内容，
     * 用户还能从底栏直接切去设置。
     *
     * ## 为什么还是放在这里，而不是 `NodesScreen` 的 `remember`
     *
     * 两条规则必须在组合根上成立：**系统返回键**要从详情退回列表（`BackKeyHandler`
     * 是 `App.kt` 里的私有函数，`NodesScreen` 够不到），以及**切页签要退出详情**
     * （否则切回来时人还停在某个节点的详情里，而它已经不在视野里了）。放在这里
     * 还能让这两条纯函数规则被 `commonTest` 钉住 —— 与本文件既有定位一致。
     */
    val detailPeerId: String? = null,
    /**
     * 工作区详情页正看着哪个工作区；null = 停在工作区看板（雷达）里。
     * 对齐原型 `m-workspacedetail`（标记 `full:true`），全屏呈现，不画底部导航栏。
     */
    val detailWorkspaceId: String? = null,
    /**
     * 工作区会话详情页正看着哪个会话；null = 停在工作区看板（雷达）里。
     * 保持与 detailPeerId 相同的范式：单列下钻，宽屏/平板分栏。
     * 对齐原型 `m-session-detail`（标记 `full:true`），全屏呈现，不画底部导航栏。
     */
    val detailAgentSessionId: String? = null,
    /**
     * 聊天页当前收窄在哪个**工作区**（null = 不收窄）。
     *
     * ## 为什么它必须住在导航状态里
     *
     * 保持路由状态，确保从工作区列表跳转到聊天页时能准确按目标工作区过滤会话。
     *
     * 与 [detailPeerId] / [detailAgentSessionId] 同一范式：导航状态里只有「当前是哪一个」。
     * [openSession] 会**清掉**它（换到另一个会话 = 不再是「从这个工作区进来的」）。
     */
    val chatWorkspaceId: String? = null,
) {
    /** 聊天详情是否正盖在页签之上。 */
    val inChatDetail: Boolean get() = activeSessionId != null

    /** 节点详情是否正停在节点页签里。 */
    val inPeerDetail: Boolean get() = detailPeerId != null

    /** 工作区详情（二级）是否正打开。 */
    val inAgentWorkspaceDetail: Boolean get() = detailWorkspaceId != null

    /** 工作区会话详情（三级）是否正打开。 */
    val inAgentSessionDetail: Boolean get() = detailAgentSessionId != null

    /** 是否处于工作区或会话详情子页中（两屏均对应原型 full:true，全屏无底导）。 */
    val inAgentSubpage: Boolean get() = inAgentWorkspaceDetail || inAgentSessionDetail

    /** 进入某个工作区详情（二级）。 */
    fun openAgentWorkspaceDetail(peerId: String, wsId: String): AppNavState =
        copy(detailPeerId = peerId, detailWorkspaceId = wsId, detailAgentSessionId = null)

    /** 退出工作区详情，回到工作区看板（一级）。 */
    fun leaveAgentWorkspaceDetail(): AppNavState =
        if (detailWorkspaceId == null) this else copy(detailWorkspaceId = null, detailAgentSessionId = null)

    /** 进入某个工作区会话的详情。 */
    fun openAgentSessionDetail(sessionId: String): AppNavState =
        if (detailAgentSessionId == sessionId && activeAgentSessionId == sessionId) this
        else copy(detailAgentSessionId = sessionId, activeAgentSessionId = sessionId)

    /** 退出工作区会话详情，回到工作区详情或看板。 */
    fun leaveAgentSessionDetail(): AppNavState =
        if (detailAgentSessionId == null) this else copy(detailAgentSessionId = null)

    /** 进入某个节点的详情。
     *
     * 连点同一个节点无害、幂等，理由同 [openSession]：状态里只有「当前是哪一个」。
     */
    fun openPeerDetail(peerId: String): AppNavState =
        if (detailPeerId == peerId) this else copy(detailPeerId = peerId)

    /** 退出节点详情，回到节点页签。 */
    fun leavePeerDetail(): AppNavState =
        if (detailPeerId == null) this else copy(detailPeerId = null)

    /**
     * 进入某个会话的详情。
     *
     * 连点同一个会话无害，也因此是幂等的：状态里本来就只有「当前是哪一个」，
     * 没有会被堆出一排的标签要防。
     *
     * **进入聊天会同时退出所有详情层**（[detailPeerId] / [detailWorkspaceId] /
     * [detailAgentSessionId]）：聊天详情与节点详情、Agent 子页都盖在页签之上，而
     * 「发消息 / 进聊天」恰恰是从那些详情里出发的 —— 不清的话旧详情还挂在状态里，
     * 返回键先弹**看不见的**那层（画面一动不动），第二次才退聊天。同时注册顺序上
     * Agent 子页的返回键处理在聊天详情之后，两层同时活着时返回键永远先吃掉
     * 看不见的那层 —— 现场正是「从节点详情进聊天，第一次返回画面不动、第二次才
     * 回列表」。这个互斥由这里的 open 函数保证，不由调用方自觉。
     */
    fun openSession(peerId: String): AppNavState {
        // 普通进入会话要**清掉工作区收窄**：`chatWorkspaceId` 的语义是「我是从工作区 A
        // 点进来的」，而 `openSession` 是「直接跟这个节点聊」—— 留着上一轮的 wsId 会让
        // 这一屏继续按旧工作区过滤，用户看到的会话列表莫名其妙地少。
        val detailLayersClear =
            detailPeerId == null && detailWorkspaceId == null && detailAgentSessionId == null
        return if (activeSessionId == peerId && chatWorkspaceId == null && detailLayersClear) {
            this
        } else {
            copy(
                activeSessionId = peerId,
                chatWorkspaceId = null,
                detailPeerId = null,
                detailWorkspaceId = null,
                detailAgentSessionId = null,
            )
        }
    }

    /**
     * 进入某个节点时**收窄到某个工作区**（工作区列表点一行时走这条路）。
     *
     * 与 [openSession] 分开而不是给它加一个可选参数：两者的语义不同 —— 一个说「跟这台
     * 设备聊」，另一个说「看这台设备上的这个工作区」。合成一个会让「不传 wsId」与
     * 「显式清掉 wsId」变成同一个调用，而后者其实没人是那个意思。
     *
     * 具有幂等守卫：目标状态未变时返回 this，避免重复重组。
     * Compose 的状态，新实例会让下游每一个 remember/LaunchedEffect 的 key
     * 看起来都变了；工作区看板点一行会走这条路两次（选中 + 进聊天），
     * 第二次必须是个 no-op。
     *
     * 与 [openSession] 同一条纪律：进入聊天会**清掉所有详情层** —— 「没有绑定协同群
     * → 提示建群 → 直接进会话」这条路正是从工作区详情里出发的，不清的话返回键先
     * 弹看不见的详情层（理由详见 [openSession] 的 KDoc）。
     */
    fun openWorkspaceChat(peerId: String, wsId: String): AppNavState {
        val detailLayersClear =
            detailPeerId == null && detailWorkspaceId == null && detailAgentSessionId == null
        return if (activeSessionId == peerId && chatWorkspaceId == wsId && detailLayersClear) {
            this
        } else {
            copy(
                activeSessionId = peerId,
                chatWorkspaceId = wsId,
                detailPeerId = null,
                detailWorkspaceId = null,
                detailAgentSessionId = null,
            )
        }
    }

    /**
     * 退出聊天详情，回到当前页签。
     */
    fun leaveChatDetail(): AppNavState =
        if (activeSessionId == null) this else copy(activeSessionId = null)

    /**
     * 选中顶级页签。
     *
     * 会离开聊天详情。未知 id 原样忽略 —— 宁可停在原地，也不要进一个画不出东西的页签。
     *
     * 顺带关掉扫码页：扫码是从节点页出发的动作，用户已经切走了就不该再让取景框
     * 盖在新页签上（相机也会一直开着）。**这不是"顺手清理"，是必须的**——
     * 扫码页盖在页签之上，不关就永远看不到刚切过去的页签。
     *
     * 节点详情与会话详情同理一起关掉：它们也是盖在页签之上的一层，不关就看不到刚切过去的页签。
     */
    fun selectTab(tabId: String): AppNavState =
        if (AppNavItems.none { it.id == tabId }) {
            this
        } else {
            copy(
                selectedTabId = tabId,
                activeSessionId = null,
                scanning = false,
                detailPeerId = null,
                detailAgentSessionId = null,
                detailWorkspaceId = null,
            )
        }

    /** 打开扫码页（幂等：已经在扫就原样返回，免得重组时把会话重新起一遍）。 */
    fun startScanning(): AppNavState =
        if (scanning) this else copy(scanning = true)

    /** 关掉扫码页。扫到了、扫不到、用户自己返回，都走这一条。 */
    fun stopScanning(): AppNavState =
        if (!scanning) this else copy(scanning = false)

    /**
     * 记住用户在 Agent 档选的 session。
     *
     * 有意**不**在 [selectTab] 里清掉：切到别的页签再回来，选择还在。回落（见顶层
     * [resolveAgentSessionId]）也**不回写**这里，于是 A 节点 → B 节点 → 回 A 时 A 的选择也还在。
     * 如果正处于会话详情中，同步切换详情会话。
     */
    fun selectAgentSession(sessionId: String): AppNavState =
        copy(
            activeAgentSessionId = sessionId,
            detailAgentSessionId = if (detailAgentSessionId != null) sessionId else null,
        )

    /**
     * 逐字段存档：转屏 / 进程重建时 `rememberSaveable` 靠它把导航状态原样带回来。
     *
     * 纯函数、零 Compose 依赖，配合 [appNavStateFromSaverValue] 组成可被
     * `commonTest` roundtrip 盯住的映射（规则的主体在纯函数里，[AppNavStateSaver]
     * 只是把它包成 Saver）。顺序即格式：`selectedTabId, activeSessionId,
     * activeAgentSessionId, scanning, detailPeerId, detailWorkspaceId,
     * detailAgentSessionId, chatWorkspaceId`。
     */
    fun toSaverValue(): List<Any> = listOf(
        selectedTabId,
        activeSessionId.orSaverNull(),
        activeAgentSessionId.orSaverNull(),
        scanning,
        detailPeerId.orSaverNull(),
        detailWorkspaceId.orSaverNull(),
        detailAgentSessionId.orSaverNull(),
        chatWorkspaceId.orSaverNull(),
    )
}

/** [AppNavState.toSaverValue] 的项数；恢复时项数对不上就放弃（见 [appNavStateFromSaverValue]）。 */
internal const val APP_NAV_STATE_SAVER_FIELDS = 8

/**
 * 可空 String 在存档里的 null 哨兵。
 *
 * 本仓的页签 id 是固定常量、节点 / 群 / 会话 id 都是生成器产出的非空串，
 * 空串从来不是合法值 —— 拿它当哨兵，存档里就只有 String 与 Boolean 两种类型。
 * 不往集合里塞 null：各平台对「Bundle 集合项为 null」的校验口径不一，
 * 而纯 String + Boolean 的定长表在 Android / JVM / iOS 三边都没有疑问。
 */
private const val SAVER_NULL_STRING = ""

private fun String?.orSaverNull(): String = this ?: SAVER_NULL_STRING

private fun String.saverNullToReal(): String? = ifEmpty { null }

/**
 * [AppNavState.toSaverValue] 的逆运算；格式对不上（项数错、类型错）返回 null，
 * 即「放弃恢复、回到初值」—— 不猜、不抛：存档是跨配置变更 / 进程重建的边界，
 * 读到一份不合身的旧格式时，宁可让用户回到会话列表，也不要崩在启动路径上。
 */
internal fun appNavStateFromSaverValue(saved: List<*>): AppNavState? {
    if (saved.size != APP_NAV_STATE_SAVER_FIELDS) return null
    val selectedTabId = saved[0] as? String ?: return null
    val activeSessionId = saved[1] as? String ?: return null
    val activeAgentSessionId = saved[2] as? String ?: return null
    val scanning = saved[3] as? Boolean ?: return null
    val detailPeerId = saved[4] as? String ?: return null
    val detailWorkspaceId = saved[5] as? String ?: return null
    val detailAgentSessionId = saved[6] as? String ?: return null
    val chatWorkspaceId = saved[7] as? String ?: return null
    return AppNavState(
        selectedTabId = selectedTabId,
        activeSessionId = activeSessionId.saverNullToReal(),
        activeAgentSessionId = activeAgentSessionId.saverNullToReal(),
        scanning = scanning,
        detailPeerId = detailPeerId.saverNullToReal(),
        detailWorkspaceId = detailWorkspaceId.saverNullToReal(),
        detailAgentSessionId = detailAgentSessionId.saverNullToReal(),
        chatWorkspaceId = chatWorkspaceId.saverNullToReal(),
    )
}

/**
 * [AppNavState] 的 [Saver] —— `App.kt` 用 `rememberSaveable(stateSaver = AppNavStateSaver)`
 * 挂在导航状态上。
 *
 * ## 为什么不用 autoSaver
 *
 * `autoSaver` 把整个对象原样塞进 Bundle，要求类型本身是 Parcelable / Serializable ——
 * `AppNavState` 两者都不是；给它补 `Serializable` 等于把**存档格式**交给 JVM 序列化
 * （字段一改旧存档就反序列化失败，而且失败点在启动路径上）。显式逐字段映射让
 * 「存了什么、按什么顺序、null 怎么表示」成为一眼可见的契约，改字段时必须同时
 * 改映射，roundtrip 测试会盯着这件事。
 */
val AppNavStateSaver: Saver<AppNavState, List<Any>> = Saver(
    save = { it.toSaverValue() },
    restore = { appNavStateFromSaverValue(it) },
)

/**
 * 定出 Agent 档当前该显示哪个 session。
 *
 * 提成顶层函数而不是 [AppNavState] 的成员：界面层只拿得到「已选 id + 候选 id 列表」，
 * 拿不到整个状态对象，规则放这里才能两边共用同一份。
 *
 * 两条规则：
 * 1. 用户显式选过、且那个 session 还在 → 用它；
 * 2. 否则回落 [sessionIds] 第一个 —— session 被移除、或换了 agent 节点都会走到这条。
 *
 * [sessionIds] 的顺序就是优先级：「默认选中最近更新的那个」由 `RelayUiState.agentSessionsOf`
 * 的 `updatedAt` 降序保证，这里不重复排序。
 *
 * 回落结果**不回写** [AppNavState.activeAgentSessionId]（理由见 `selectAgentSession`）。
 */
fun resolveAgentSessionId(requested: String?, sessionIds: List<String>): String? =
    requested?.takeIf { it in sessionIds } ?: sessionIds.firstOrNull()
