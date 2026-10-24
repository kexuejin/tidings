// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

/**
 * 提示（banner）的结构化形式。
 *
 * 为什么单独一个文件：控制器与 UI 都要引用它，而 `RelayUiState.kt` 的本体是
 * 「一张 UI 状态表的字段布局」——把 51 个文案变体混在里面，会让读状态表的人
 * 先读完 200 行文案才看到字段。这里只放 `BannerUi` / `BannerTone` / `BannerMessage`
 * 三个类型，`RelayUiState` 只留一个 `banner: BannerUi?` 字段。
 *
 * 为什么不直接存一段中文见 [BannerMessage] 的 KDoc（用户可见字符串只住 strings.xml，
 * 控制器不是 `@Composable` 拿不到 `stringResource`，所以传"发生了什么 + 参数"出来）。
 */
/** 一次性提示（错误出路、操作结果）。UI 必须能显示它，也必须有办法关掉。 */
data class BannerUi(
    val message: BannerMessage,
    val tone: BannerTone,
)

enum class BannerTone { Info, Warn, Error }

/**
 * 提示文案的**结构化**形式。
 *
 * 为什么不直接存一段中文：本仓约定「用户可见字符串只住在 composeResources 的
 * values 目录下的 strings.xml」（见 `composeApp/build.gradle.kts` 的注释），
 * 而控制器不是 `@Composable`，拿不到 `stringResource`。把"发生了什么 + 参数"传出来、
 * 由 UI 层翻译，既守住约定，也让文案可测试（断言 code 而不是断言一句中文）。
 */
sealed interface BannerMessage {
    /** 用户没填地址。 */
    data object EmptyHost : BannerMessage

    /** 填的东西不像 IP/主机名。 */
    data object InvalidHost : BannerMessage

    /** 端口不在 1–65535。 */
    data object InvalidPort : BannerMessage

    /** 这个节点已经配过了。 */
    data object DuplicatePeer : BannerMessage

    /** 已向 target 单播 entry，等它 alive。 */
    data class Handshaking(val target: String) : BannerMessage

    /** 运行时还没起来就点了发送。 */
    data object NodeNotStarted : BannerMessage

    /**
     * 一次要传的文件太多（拖进来的文件夹展开后超过上限）。
     *
     * 为什么必须**先判、再给具体数**：传输层超限时只是 `return null`，界面看到的是
     * 「这张传不出去」，说不出原因。用户拖了一个几千文件的目录时，最需要知道的是
     * 「有多少、上限多少」——否则只会反复重试。
     */
    data class TooManyFiles(val count: Int, val limit: Int) : BannerMessage

    /** 单条文本超过上限。 */
    data class MessageTooLong(val bytes: Int, val limit: Int) : BannerMessage

    /** 发送抛错，detail 是底层原因。 */
    data class SendFailed(val detail: String) : BannerMessage

    /** 昵称留空。 */
    data object NickEmpty : BannerMessage

    /** 资料保存成功，资料版本升到 rev。 */
    data class ProfileSaved(val rev: Int) : BannerMessage

    /** 端口保存成功。 */
    data class PortsSaved(val udp: Int, val tcp: Int) : BannerMessage

    /**
     * 能力声明（`tw1` / `mrec1`）改动已生效。
     *
     * 它值得一条横幅，而通知开关不值得：改能力会**重启节点**（连接会闪一下），
     * 用户需要知道那一下是刚才那个开关引起的，不是掉线。
     */
    data object CapabilitiesSaved : BannerMessage

    /** 端口监听失败，reason 是底层原因。 */
    /** 选文件失败（权限、读取异常）。用户主动取消不算失败，不报。 */
    data class PickFailed(val detail: String) : BannerMessage

    /** 文件传输失败。[detail] 是协议里的短机器串，例如 hash-mismatch。 */
    data class TransferFailed(val detail: String) : BannerMessage

    /** 邀请已发出，等对方接受。 */
    data class FilesOffered(val count: Int, val name: String) : BannerMessage

    /**
     * 端口监听失败。[reason] 是底层原因，`null` = 异常连 message 都没有。
     *
     * 原来是 `String` 且调用方拿 `error::class.simpleName` 兜底：把一个类名摆给用户看
     * 既不是原因也不是出路。现在留 null，UI 层取 `error_unknown`。
     */
    data class BindFailed(val port: Int, val reason: String?) : BannerMessage

    /** 相机权限被拒绝。 */
    data object CameraPermissionDenied : BannerMessage

    /**
     * 通知权限没拿到（用户拒绝，或系统设置里关掉了通知）。
     *
     * 这条必须报：拒了之后**收到的消息不会有任何提醒**，而界面上原本没有一点迹象 ——
     * 平台层的 `requestPermission()` 已经如实返回了答案，丢掉它就等于把问题留给用户自己猜。
     * 文案是平台中立的：各家的设置路径不同，只说"去哪做什么"。
     */
    data object NotificationPermissionDenied : BannerMessage

    /**
     * 这台设备给不出相机画面（模拟器没有相机、相机被别的应用占用、没有后摄）。
     *
     * 与 [CameraPermissionDenied] 的区别是**权限没问题**：问过了，也给了，就是拿不到画面。
     * 文案由 UI 层给（`banner_camera_unavailable`）—— 平台层只报"拿不到输入"这件事本身。
     */
    data object CameraUnavailable : BannerMessage

    /** 扫描失败。 */
    data class ScanFailed(val detail: String) : BannerMessage

    /** 二维码内容无效。 */
    data object InvalidQrCode : BannerMessage

    /** 已配置公网中继。 */
    data class RemoteRelayConfigured(val name: String) : BannerMessage

    /** 已清除公网中继配置。 */
    data object RemoteRelayCleared : BannerMessage

    /**
     * 选中的那张图里没有二维码。
     *
     * 与 [InvalidQrCode] 分开是必须的：一个是"码读出来了，但不是我们的格式"，
     * 一个是"压根没读到码"（拿错图了、码太小、图被裁过）。合成一句"二维码无效"，
     * 用户会去怀疑码本身，而真正该做的是换一张图。
     */
    data object NoQrInImage : BannerMessage

    /**
     * 操作抛了异常，而异常自己**一个字的 message 都没有**。
     *
     * 这条存在的唯一理由是「不知道就别编一个原因」：`RuntimeException()`、`BindException()`
     * 这类构造出来的异常 `message` 就是 null，以前控制器只好拿中文字面量「未知错误」当兜底 ——
     * 那既违反「用户可见字符串只住 strings.xml」的约定，又把"我问不出来"说成"出错了"。
     * 现在由 UI 层取 [com.kapp.tidings.generated.resources.error_unknown]，控制器不再拼用户可见文案。
     */
    data object UnknownError : BannerMessage

    /** 点了「清理没有回应的」，但一个都没有 —— 不必假装做了事。 */
    data object NoUnreachablePeers : BannerMessage

    /** 清掉了 [count] 个从来没回应过的手动节点。 */
    data class UnreachablePeersRemoved(val count: Int) : BannerMessage

    /**
     * 戳一碰没戳到：对端没 ACK。
     *
     * 协议里 `nudge` **不入离线队列**（协议 §8），所以这不是"稍后会到"，是"这次没戳到"，
     * 必须如实说 —— 与 [MessageTooLong] 之类"用户能自己补救"的提示同一档。
     */
    data object NudgeFailed : BannerMessage

    /** 戳得太勤，被限流挡下了（同一对端 60 秒最多 2 次、相邻至少 15 秒）。 */
    data object NudgeThrottled : BannerMessage

    /** 建群时名字是空的（或只有空白）。 */
    data object GroupNameRequired : BannerMessage

    /**
     * 建群时名字超过了协议上限（[com.kapp.tidings.core.protocol.Limits.GROUP_NAME]）。
     *
     * **必须在这里拦**，不能靠截断：超长的 `name` 会让对方 `codec.ts` 的
     * `isStr(meta.name, 32)` 失败、**整帧拒收**，而拒收是静默的 ——
     * 用户看到的是"群建好了，成员那边什么都没有"，且找不到原因。
     */
    data class GroupNameTooLong(val limit: Int) : BannerMessage

    /**
     * 建群时一个成员都没选（或只选了自己）。
     *
     * 一个人也能有群，但那不是"群聊" —— 它发不出任何东西，界面上会是一个永远空着的会话。
     */
    data object GroupNeedsMember : BannerMessage

    /** 群建好了，正在逐个通知成员。 */
    data class GroupCreated(val name: String, val memberCount: Int) : BannerMessage

    /**
     * 要发的这个群本机没有成员表。
     *
     * 只可能是"元数据还没同步到"（刚被拉进来、`group.info` 还在路上），所以文案要指向
     * "等一下"或"让对方再发一条"，而不是"这个群不存在"。
     */
    data object GroupUnknown : BannerMessage

    /** 我们已被移出这个群，发送被拒。 */
    data object GroupLeft : BannerMessage

    /** 成功邀请新成员入群。 */
    data class GroupMembersAdded(val count: Int) : BannerMessage

    /** 成功将成员移出群。 */
    data object GroupMemberRemoved : BannerMessage

    /** 权限不足（非群主/管理员尝试移出成员）。 */
    data object GroupNoPermission : BannerMessage

    /** 群公告或简介已更新。 */
    data object GroupMetaUpdated : BannerMessage

    /** 消息转发成功。 */
    data object ForwardSuccess : BannerMessage

    /** 共享文件柜目录已更新。 */
    data object CabinetDirUpdated : BannerMessage

    /** 已停止共享文件柜。 */
    data object CabinetShareStopped : BannerMessage

    /** 共享文件柜下载任务已开始。 */
    data class CabinetDownloadStarted(val count: Int) : BannerMessage

    /** 已开始向同事的文件柜上传文件（`cabinet_upload_started`，此前 0 消费）。 */
    data object CabinetUploadStarted : BannerMessage

    /** 网段格式不正确或超出范围。 */
    data object ScanRangeInvalid : BannerMessage

    /** 已添加网段。 */
    data class ScanRangeAdded(val cidr: String) : BannerMessage

    /** 已触发网段探测。 */
    data class ScanRangeTriggered(val hostCount: Int) : BannerMessage

    /** 当前环境不支持屏幕截图。 */
    data object CaptureUnavailable : BannerMessage

    /** 截图已保存/完成。 */
    data class CaptureSaved(val path: String) : BannerMessage

    /**
     * 截图没有产出结果。
     *
     * **两种成因共用这一条**：用户在覆盖窗里点了取消，或 `CaptureExportHelper` 写盘失败
     * （调用方两条路径都拿 `filePath == null`，生产者分不出也不必分）。
     * 所以文案只说"已取消"，不写"未保存" —— 写"未保存"在保存失败那一路是实话，
     * 在用户主动取消那一路却是多嘴；反过来写"已取消"在保存失败时最多是含糊，不会说反。
     */
    data object CaptureCanceled : BannerMessage

    /** 当前环境不支持屏幕协助。 */
    data object ScreenViewUnavailable : BannerMessage

    /** 屏幕协助已结束。 */
    data class ScreenSessionEnded(val reason: String) : BannerMessage

    /**
     * 审批决定没能送出：要么本机节点没在跑（连离线队列都不存在），要么发送抛了错。
     *
     * 与「QUEUED」的区别必须说清：QUEUED 是帧进了离线补发队列、**终会送达**，本地
     * 的「已批准」是合法的乐观记账；这一条是**连队列都没进**，本地状态已经回滚成
     * 待审批 —— 界面上「已批准」若还挂着，就是对端永远不知道的假账。
     */
    data object AgentAckSendFailed : BannerMessage

    /**
     * 对端不声明审批回执（`agq1`）或当前不在线到无法判定：ack 无处可发。
     *
     * 本地的决定仍然记着（授权决定是本端产生的事实），但**必须**如实说明
     * 「最终以对方 Agent 宿主实际执行为准」—— 界面上的「已批准」在这里不能暗示
     * 对方已经知道。这条横幅就是本仓「静默丢失」清单第 7 条的守门员。
     */
    data object AgentAckUnconfirmed : BannerMessage

    /**
     * 已把一台节点从本机移除（含清理它的手动地址）。
     *
     * 为什么要有回执：移除是**不可撤销**的本地破坏性动作，且桌面上原本连入口都没有 ——
     * 用户点完「移除」若界面只是少一行，很难分清「移除了」和「列表重绘了」。
     * 名字放在文案里，是因为同一屏可能连着移两台，只说「已移除」会认不出是哪台。
     */
    data class PeerRemoved(val name: String) : BannerMessage

    /**
     * 对方拒绝了这个文件柜的下载请求。
     *
     * 与共用 [com.kapp.tidings.domain.RelayControllerCabinetComponent] 的 `errorMessage` 分开：
     * 那个槽位属于**整张目录列表**（浏览失败），一次下载被拒不该把列表顶成错误态。
     */
    data object CabinetDownloadDenied : BannerMessage
}
