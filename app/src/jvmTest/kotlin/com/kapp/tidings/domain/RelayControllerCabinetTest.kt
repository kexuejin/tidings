// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.Envelope
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.ShareDenyReasons
import com.kapp.tidings.core.protocol.ShareEntry
import com.kapp.tidings.core.protocol.makeEnvelope
import com.kapp.tidings.domain.model.CabinetEntryUi
import com.kapp.tidings.domain.model.CabinetUiState
import com.kapp.tidings.testsupport.GuardSourceScan
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import com.kapp.tidings.testutil.awaitUntil

/**
 * 文件柜域层的三条硬纪律（2026-10 排查清单）：
 *
 * 1. **节点未启动时不许编造数据**：过去 `handleLoadCabinetPath` 的 `rt == null` 分支填了
 *    一套演示条目（docs/、contract-v2.pdf…、totalEntries=1280、currentPerm="write"）——
 *    界面上是一个根本不存在的目录。现在必须落成 error 态、零条目。
 * 2. **get 请求拿不出内容时必须回 deny**：全部路径都不可下载（目录/不存在）却不回执，
 *    发送侧「下载 N 项」之后永远等不到下文 —— 「发了没下文」正是本仓最贵的一类缺陷。
 *    deny 的决策抽成 [shareGetDenyReason] 纯函数单独钉住。
 * 3. **下载没有「另存为」**：`handleDownloadCabinetEntries` 曾带一个被整条忽略的
 *    `saveAs` 参数（发送侧恒 false/true 都走同一条 `sendShareGet`），界面上的
 *    「另存为…」是一颗假按钮 —— 签名里不许再有死参数（编译器守住，不需要测试）。
 *
 * 装置与 [RelayControllerGroupTest] 同形：不起节点（`runtime == null`），
 * 这正是用例 1 需要的世界。
 */
class RelayControllerCabinetErrorStateTest {

    private val self = "abc12345-0000-4000-8000-000000000000"
    private val lin = "11111111-1111-4111-8111-111111111111"

    @Test
    fun browsingAPeerCabinetWithoutARuntimeShowsAnErrorNotMockData() = runBlocking {
        val controller = controller()

        controller.selectCabinetPeer(lin)

        val cabinet = controller.state.value.cabinet
        assertEquals(lin, cabinet.activePeerId)
        assertTrue(
            cabinet.entries.isEmpty(),
            "节点未启动时不许给出任何条目 —— 编造的 docs/、contract-v2.pdf 是在骗人",
        )
        assertEquals(
            0,
            cabinet.totalEntries,
            "totalEntries 不再是编造的 1280：没加载就是 0",
        )
        assertEquals(
            CABINET_ERR_NODE_OFFLINE,
            cabinet.errorMessage,
            "必须是 error 态且原因可辨认（UI 层按这个哨兵翻译成「节点未启动」）",
        )
        assertEquals(
            false,
            cabinet.loading,
            "错误态不是加载中 —— 停在 true 会让界面永远转圈",
        )
    }

    @Test
    fun retryingTheLoadWithoutARuntimeStaysInErrorState() = runBlocking {
        val controller = controller()
        controller.selectCabinetPeer(lin)

        // 错误态上的「重新加载」在没有节点时必须**仍**是错误态 —— 而不是把错误的
        // errorMessage 清掉之后画一个假的空目录。
        controller.loadCabinetPath("")

        val cabinet = controller.state.value.cabinet
        assertEquals(CABINET_ERR_NODE_OFFLINE, cabinet.errorMessage)
        assertTrue(cabinet.entries.isEmpty())
    }

    @Test
    fun leavingThePeerCabinetClearsTheErrorState() = runBlocking {
        val controller = controller()
        controller.selectCabinetPeer(lin)
        assertEquals(CABINET_ERR_NODE_OFFLINE, controller.state.value.cabinet.errorMessage)

        // 退回「我的文件柜」时上一屏的错误态必须跟着清掉，不能残留到下一次浏览。
        controller.selectCabinetPeer(null)

        assertEquals(null, controller.state.value.cabinet.activePeerId)
        assertEquals(null, controller.state.value.cabinet.errorMessage)
        assertTrue(controller.state.value.cabinet.entries.isEmpty())
    }

    // ---- get 回执决策（纯函数）----

    @Test
    fun aGetRequestWithNothingDownloadableMustBeDenied() {
        assertEquals(
            ShareDenyReasons.NOT_FOUND,
            shareGetDenyReason(validCount = 0, requestedCount = 3),
            "三个路径一个都拿不出来（整批目录/不存在）必须回 deny —— 沉默就是「发了没下文」",
        )
        assertEquals(
            ShareDenyReasons.NOT_FOUND,
            shareGetDenyReason(validCount = 0, requestedCount = 1),
        )
    }

    @Test
    fun aPartiallyFulfilledGetIsAnsweredByTheOfferNotADeny() {
        assertNull(
            shareGetDenyReason(validCount = 2, requestedCount = 3),
            "有可下载文件时回 offer；deny 是按 reqId 对整次请求的否决，与 offer 并存自相矛盾" +
                "（混合请求里的目录路径由发送侧 UI 禁止勾选，这里只兼容旧对端）",
        )
    }

    @Test
    fun anEmptyGetRequestIsNotDenied() {
        assertNull(
            shareGetDenyReason(validCount = 0, requestedCount = 0),
            "对端什么都没要时无事可说，不该回一条没人问的 deny",
        )
    }

    // ---- 路径切换 / 回执落点（行为级：走真实的入站信封路径）----

    /**
     * 换目录必须**先清列表**。
     *
     * 反身性：把 `cabinetPathLoadingState` 里的 `entries = emptyList()` 或
     * `totalEntries = 0` 删掉，这条立刻变红 —— 界面上会变成「新面包屑 + 上一级那些行」
     * （`loading && entries.isEmpty()` 永远不成立，转圈那支根本没机会出现）。
     */
    @Test
    fun switchingIntoASubdirectoryDropsTheParentListingInsteadOfDrawingItUnderTheNewBreadcrumb() = runBlocking {
        val controller = controller()
        controller.selectCabinetPeer(lin)
        inbound(
            controller,
            listOkPayload(
                path = "",
                perm = "write",
                entries = listOf(
                    ShareEntry(name = "docs", size = 0, isDir = true, mtime = 1),
                    ShareEntry(name = "a.pdf", size = 100, isDir = false, mtime = 2),
                ),
                total = 2,
            ),
        )
        assertEquals(2, controller.state.value.cabinet.entries.size)
        assertEquals("write", controller.state.value.cabinet.currentPerm)

        controller.loadCabinetPath("docs")

        val cabinet = controller.state.value.cabinet
        assertTrue(cabinet.entries.isEmpty(), "换目录后不许还画着上一级的行")
        assertEquals(0, cabinet.totalEntries, "陈旧的总数会让「仅显示前 N 项」报上一级的数字")
        assertEquals("read", cabinet.currentPerm, "换了目录，权限还没问到 —— 不许沿用上一档的「可上传」")
        assertEquals(CABINET_ERR_NODE_OFFLINE, cabinet.errorMessage)
    }

    /**
     * 下载被拒只影响那次下载。
     *
     * 反身性：把 `ShareMessage.Deny` 那支改回「不看 reqId，一律写 errorMessage」，
     * 这条立刻变红（errorMessage 非空 + 横幅缺失）。
     */
    @Test
    fun aDeniedDownloadIsItsOwnBannerAndNeverReplacesTheListing() = runBlocking {
        val controller = controller()
        controller.selectCabinetPeer(lin)
        inbound(controller, listOkPayload(path = "", perm = "write", total = 1))
        var asked = false
        shareGetSenderForTest = { _, _ ->
            asked = true
            "req-get-1"
        }
        try {
            controller.downloadCabinetEntries(listOf("a.pdf"))
            awaitUntil("下载请求发出") { asked }
            inbound(controller, denyPayload("req-get-1", ShareDenyReasons.NO_PERM))

            val cabinet = controller.state.value.cabinet
            assertEquals(
                BannerMessage.CabinetDownloadDenied,
                controller.state.value.banner?.message,
                "下载被拒必须走自己的横幅，而不是列表错误态",
            )
            assertNull(cabinet.errorMessage, "一次下载被拒不许把整张目录列表顶成错误页")
            assertEquals(1, cabinet.entries.size, "列表必须还在")
        } finally {
            shareGetSenderForTest = null
        }
    }

    /**
     * 对不上任何在途下载的 deny **仍按列表处理**（与旧行为一致，保证「拒绝」总有可重试出口）。
     *
     * 这一条与上一条是一对：只有「对得上在途下载 id」的 deny 才会被改道到横幅，其余照旧。
     * 反身性：把 `cabinetDenyTarget` 的 `else` 分支改成 `Download`（全当下载拒），
     * 这条会红 —— 列表错误态再也不会出现。
     */
    @Test
    fun aDenyThatAnswersNoLiveDownloadStillLandsOnTheListingError() = runBlocking {
        val controller = controller()
        controller.selectCabinetPeer(lin)
        inbound(controller, listOkPayload(path = "", perm = "read", total = 1))

        inbound(controller, denyPayload("req-from-a-previous-round", ShareDenyReasons.BUSY))

        val cabinet = controller.state.value.cabinet
        assertEquals(ShareDenyReasons.BUSY, cabinet.errorMessage, "拒绝必须留一个可重试的出口")
    }

    // ---- get 请求的**调用点**回执（不是纯函数）----

    /**
     * 本机没在共享时，get 请求必须回 deny。
     *
     * 反身性：把 `shareGetGuardReason` 那段删回 `settings.shareRoot ?: return`，
     * `sent` 会一直为空 —— 发送侧「下载 N 项」之后永远等不到下文。
     */
    @Test
    fun aGetRequestForACabinetThatIsNotSharedAnyMoreIsDeniedInsteadOfSilentlyDropped() = runBlocking {
        val controller = controller() // shareRoot 未设 ⇒ 本机没在共享
        val sent = mutableListOf<String>()
        shareDenySenderForTest = { _, _, reason -> sent += reason }
        try {
            inbound(controller, getPayload("req-get-9", listOf("a.pdf")))
            awaitUntil("回了 deny") { sent.isNotEmpty() }
            assertEquals(
                listOf(ShareDenyReasons.OFF),
                sent,
                "本机没在共享必须回 OFF —— 静默 return 就是「发了没下文」",
            )
        } finally {
            shareDenySenderForTest = null
        }
    }

    @Test
    fun aGetRequestWhileTheGrantIsOffIsDeniedWithNoPermission() = runBlocking {
        val dir = Files.createTempDirectory("relay-cabinet-root").toFile()
        try {
            val controller = controller()
            controller.setShareRoot(dir.absolutePath)
            controller.setShareMode("read")
            controller.setShareGrant(lin, "off")
            val sent = mutableListOf<String>()
            shareDenySenderForTest = { _, _, reason -> sent += reason }
            try {
                inbound(controller, getPayload("req-get-10", listOf("a.pdf")))
                awaitUntil("回了 deny") { sent.isNotEmpty() }
                assertEquals(listOf(ShareDenyReasons.NO_PERM), sent)
            } finally {
                shareDenySenderForTest = null
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * 路径一个都拿不出来时的 deny 必须真的发出去（不只是纯函数对）。
     *
     * 反身性：删掉 GetReq 分支末尾那句 `sendShareDeny(..., denyReason)`，这条变红。
     */
    @Test
    fun aGetRequestWhoseFilesAreGoneIsDeniedAtTheCallSiteNotJustInThePureFunction() = runBlocking {
        val dir = Files.createTempDirectory("relay-cabinet-root").toFile()
        try {
            val controller = controller()
            controller.setShareRoot(dir.absolutePath)
            val sent = mutableListOf<String>()
            shareDenySenderForTest = { _, reqId, reason -> sent += reqId + ":" + reason }
            try {
                inbound(controller, getPayload("req-get-11", listOf("missing.pdf")))
                awaitUntil("回了 deny") { sent.isNotEmpty() }
                assertEquals(
                    listOf("req-get-11:" + ShareDenyReasons.NOT_FOUND),
                    sent,
                    "全拿不出来的 get 必须按 reqId 回 NOT_FOUND",
                )
            } finally {
                shareDenySenderForTest = null
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- 纯函数：清态 / 超时 / deny 落点 / get 闸门 ----

    @Test
    fun thePathTransitionClearsListingTotalsAndSelection() {
        val prev = CabinetUiState(
            currentPath = "",
            entries = listOf(
                CabinetEntryUi(name = "docs", isDir = true, size = 0, mtime = 0),
                CabinetEntryUi(name = "a.pdf", isDir = false, size = 10, mtime = 0),
            ),
            totalEntries = 2,
            currentPerm = "write",
            loading = false,
            selectedNames = setOf("a.pdf"),
            errorMessage = "gone",
        )

        val next = cabinetPathLoadingState(prev, "docs")

        assertEquals("docs", next.currentPath)
        assertTrue(next.entries.isEmpty())
        assertEquals(0, next.totalEntries)
        assertTrue(next.loading, "换目录必须先落成加载态 —— 删掉 loading = true，回执到达前画的是「空目录」")
        assertNull(next.errorMessage)
        assertTrue(next.selectedNames.isEmpty())
    }

    @Test
    fun theTimeoutOnlyFiresForTheCurrentGenerationThatIsStillLoading() {
        assertTrue(cabinetListTimedOut(loading = true, errorMessage = null, seq = 3, latestSeq = 3))
        assertFalse(cabinetListTimedOut(loading = false, errorMessage = null, seq = 3, latestSeq = 3))
        assertFalse(
            cabinetListTimedOut(loading = true, errorMessage = ShareDenyReasons.OFF, seq = 3, latestSeq = 3),
            "已经有错误态了，超时不许再盖一次",
        )
        assertFalse(
            cabinetListTimedOut(loading = true, errorMessage = null, seq = 2, latestSeq = 3),
            "上一代的计时器晚到不许误报（同一路径连点两次重试）",
        )
    }

    @Test
    fun aDenyIsAttributedByReqId() {
        assertEquals(CabinetDenyTarget.List, cabinetDenyTarget("r-list", "r-list", "r-get"))
        assertEquals(CabinetDenyTarget.Download, cabinetDenyTarget("r-get", "r-list", "r-get"))
        assertEquals(
            CabinetDenyTarget.List,
            cabinetDenyTarget("r-old", "r-list", "r-get"),
            "答不上在途下载的 deny 不许被当成下载拒 —— 它仍要落成列表错误态",
        )
        assertEquals(CabinetDenyTarget.List, cabinetDenyTarget("r-x", null, null))
        assertEquals(
            CabinetDenyTarget.Download,
            cabinetDenyTarget("r-get", null, "r-get"),
            "列表没有在途请求时，下载自己的 deny 也不许落成列表错误态",
        )
    }

    @Test
    fun theGetGateMirrorsTheListGate() {
        assertEquals(
            ShareDenyReasons.OFF,
            shareGetGuardReason(shareEnabled = false, shareRoot = "/tmp/x", mode = "read"),
        )
        assertEquals(
            ShareDenyReasons.OFF,
            shareGetGuardReason(shareEnabled = true, shareRoot = null, mode = "read"),
            "共享目录被清空后必须回 OFF —— 过去这里直接 return",
        )
        assertEquals(
            ShareDenyReasons.OFF,
            shareGetGuardReason(shareEnabled = true, shareRoot = "   ", mode = "read"),
        )
        assertEquals(
            ShareDenyReasons.NO_PERM,
            shareGetGuardReason(shareEnabled = true, shareRoot = "/tmp/x", mode = "off"),
        )
        assertNull(shareGetGuardReason(shareEnabled = true, shareRoot = "/tmp/x", mode = "read"))
    }

    // ---- 装置 ----

    private fun inbound(controller: RelayController, payload: JsonElement, from: String = lin) {
        val envelope: Envelope = makeEnvelope(
            type = MsgTypes.SHARE,
            id = "env-cabinet-" + (envelopeCounter++),
            from = from,
            ts = nowMillis(),
            payload = payload,
        )
        controller.messagesCtl.handleOnInbound(envelope)
    }

    private fun listOkPayload(
        path: String,
        perm: String,
        total: Int,
        entries: List<ShareEntry> = listOf(ShareEntry(name = "a.pdf", size = 100, isDir = false, mtime = 2)),
    ): JsonElement = Codec.shareListOkPayload(
        reqId = "req-list-" + path.ifEmpty { "root" },
        path = path,
        perm = perm,
        snapshotId = "snap-1",
        offset = 0,
        total = total,
        truncated = false,
        entries = entries,
    )

    private fun denyPayload(reqId: String, reason: String): JsonElement =
        Codec.shareDenyPayload(reqId, reason)

    private fun getPayload(reqId: String, paths: List<String>): JsonElement =
        Codec.shareGetPayload(reqId, paths)

    private var envelopeCounter: Int = 0

    private fun CoroutineScope.controller(): RelayController = RelayController(
        platform = com.kapp.tidings.platform.InMemoryAppPlatform(),
        store = CabinetMemorySettingsStore(self),
        scope = this,
        version = "relay-test",
    )

    private class CabinetMemorySettingsStore(private val nodeId: String) : SettingsStore {
        private var current: RelaySettings? = RelaySettings(
            nodeId = nodeId,
            nick = "我的手机",
            udpPort = 0,
            tcpPort = 0,
        )

        override fun load(): RelaySettings? = current

        override fun save(settings: RelaySettings) {
            current = settings
        }
    }
}

/**
 * 接线级守卫：上一组用例证明的是「判据对」，这一组证明的是**调用点真的用了它**。
 *
 * 本仓踩过的假绿形态：纯函数有测试、调用点没接线（删掉调用点仍然全绿）。文件柜这批
 * 的「换目录清态」「等回执超时」「deny 按 reqId 归类」三条都在 Composable 之外，
 * 但它们的**接线**在同一个控制器里，只能按源码块钉住。
 */
class CabinetLoadingWiringGuardTest {

    private fun live(): String = GuardSourceScan.stripComments(GuardSourceScan.read(COMPONENT))

    @Test
    fun thePathSwitchGoesThroughTheClearingTransitionAndArmsTheWatchdog() {
        val body = GuardSourceScan.bracedBlockAfter(live(), "fun handleLoadCabinetPath(")
        assertTrue(
            body.contains("cabinetPathLoadingState("),
            "换目录没有走共用的清态函数 —— 自己手拼一份，entries/totalEntries/loading 迟早漏一条",
        )
        assertTrue(
            body.contains("delay(CABINET_LIST_TIMEOUT_MS)"),
            "等回执的看门狗被摘掉了：对端不回执时界面会永远转圈（loading 只有回执能清）",
        )
        assertTrue(
            body.contains("cabinetListTimedOut("),
            "超时判定必须走共用的纯函数（代次校验在里面，直接写 if 会漏掉上一代计时器那条）",
        )
        assertTrue(
            body.contains("CABINET_ERR_TIMEOUT"),
            "超时必须落成可重试的错误态，而不是悄悄把 loading 关掉（那就是一个假空目录）",
        )
    }

    @Test
    fun switchingPeersDoesNotInheritLoadingOrThePreviousGrant() {
        val body = GuardSourceScan.bracedBlockAfter(live(), "fun handleSelectCabinetPeer(")
        assertTrue(body.contains("loading = false"), "换对端不许继承上一对端的加载态")
        assertTrue(
            body.contains("CabinetUiState().currentPerm"),
            "换对端不许沿用上一台设备的权限徽标（「可上传」必须是这一位给的）",
        )
    }

    @Test
    fun theDuplicateShareEnvelopeKdocIsGone() {
        val text = GuardSourceScan.read(COMPONENT)
        val marker = "* 处理入站 " + '`' + "share" + '`' + " 信封（协议 §8.2）。"
        assertEquals(
            1,
            Regex(Regex.escape(marker)).findAll(text).count(),
            "文件里又出现两块同样的 share 信封 KDoc —— 那是中断那轮留下的重复注释",
        )
    }

    @Test
    fun theDenyBranchAttributesByReqIdAndSendsDownloadsToTheirOwnBanner() {
        val body = GuardSourceScan.bracedBlockAfter(live(), "is ShareMessage.Deny ->")
        assertTrue(body.contains("cabinetDenyTarget("), "deny 必须按 reqId 归类，否则下载被拒又会顶掉整张列表")
        assertTrue(
            body.contains("CabinetDownloadDenied"),
            "下载被拒要有自己的横幅 —— 落成列表错误态就是丢用户的浏览上下文",
        )
        assertTrue(
            body.contains("CabinetDenyTarget.List ->") &&
                body.contains("errorMessage = if (pendingListIsLoadMore)") &&
                body.contains("deny.reason"),
            "列表请求的 deny 仍要落成列表错误态（否则连「对方找不到」都不显示了）；" +
                "但「加载更多」被拒不能把已有列表顶成错误页。",
        )
        assertEquals(
            2,
            Regex("CabinetDenyTarget\\.").findAll(body).count(),
            "deny 的落点只能有两支（列表 / 下载）。多一支「不处理」会让某些拒绝彻底消失，" +
                "少一支则下载被拒又会顶掉整张列表",
        )
    }

    private companion object {
        const val COMPONENT =
            "app/src/commonMain/kotlin/com/kapp/tidings/domain/RelayControllerCabinetComponent.kt"
    }
}
