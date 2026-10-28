// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.settings

import com.kapp.tidings.testsupport.GuardSourceScan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「点了要有反应」这一轮（T24）的回归：字段级校验、诊断页盖章口径、以及几条只能靠
 * 源码守卫钉住的外壳纪律。
 *
 * 反向验证：把 [parsePortInput] 改回"直接透传字符串"、把网段校验删掉（回到无条件清空输入）、
 * 把 `minimumInteractiveComponentSize`/`Role.Button` 拿掉、把网段行换回 Surface chip、
 * 把头像页的移除按钮删掉、把更新失败换回原始异常 —— 下面每一条都会变红。
 */
class SettingsInteractionFeedbackTest {

    private val conn = "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/settings/SettingsConnectionSubpage.kt"
    private val diag = "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/settings/SettingsDiagSubpage.kt"
    private val avatar = "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/settings/SetAvatarPage.kt"
    private val update = "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/settings/SettingsUpdateSubpage.kt"
    private val home = "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/settings/SettingsHomeScreen.kt"
    private val chrome = "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/settings/SettingsPageChrome.kt"

    /** 取 `marker` 之后 [after] 个字符的窗口（守卫只关心"紧邻的这段活代码"）。 */
    private fun window(text: String, marker: String, after: Int = 400): String {
        val at = text.indexOf(marker)
        assertTrue(at >= 0, "源码里找不到标记：$marker")
        return text.substring(at, minOf(text.length, at + after))
    }

    /* ===== 端口：与控制器同一条规则，提前到字段上 ===== */

    @Test
    fun portInputUsesTheSameRangeAsTheController() {
        assertEquals(17878, parsePortInput("17878", fallback = 1))
        // 空输入 = 用当前端口（保存按钮原先的行为）。
        assertEquals(9000, parsePortInput("   ", fallback = 9000))
        // 越界与不可解析都必须被拒 —— 否则值会递到控制器，只换来一条用户可能看不见的横幅。
        assertNull(parsePortInput("0", fallback = 1))
        assertNull(parsePortInput("65536", fallback = 1))
        assertNull(parsePortInput("999999", fallback = 1))
        assertNull(parsePortInput("abc", fallback = 1))
        assertEquals(1, parsePortInput("1", fallback = 7))
        assertEquals(65535, parsePortInput("65535", fallback = 7))
    }

    /* ===== 网段：非法不清空、就地报错 ===== */

    @Test
    fun scanRangeIsNormalizedAndHostCapIsEnforced() {
        assertEquals("192.168.2.0/24", normalizeScanRangeOrNull("192.168.2.0/24"))
        // 与控制器同一条规范化：主机位被抹掉。
        assertEquals("192.168.1.0/24", normalizeScanRangeOrNull("192.168.1.55/24"))
        assertEquals("10.0.0.0/30", normalizeScanRangeOrNull(" 10.0.0.0/30 "))
        // /16 = 65534 台，超过 1024 上限 ⇒ 拒（与 CidrUtils.parseCidrHosts 一致）。
        assertNull(normalizeScanRangeOrNull("192.168.0.0/16"))
        assertNull(normalizeScanRangeOrNull("192.168.2.0/33"))
        assertNull(normalizeScanRangeOrNull("hello"))
        assertNull(normalizeScanRangeOrNull(""))
    }

    @Test
    fun batchScanRangeParsingNormalizesDeduplicatesAndPreservesInvalidTokens() {
        val input = """
            192.168.1.100/24
            192.168.1.200/24, 10.0.0.0/30; 172.16.0.0/16
            invalid_cidr
        """.trimIndent()
        val result = parseBatchScanRanges(input)
        // 192.168.1.100/24 与 192.168.1.200/24 规范化后同为 192.168.1.0/24，只留一份
        assertEquals(listOf("192.168.1.0/24", "10.0.0.0/30"), result.valid)
        // /16 (>1024 主机) 与 invalid_cidr 归入 invalid 列表保留反馈
        assertEquals(listOf("172.16.0.0/16", "invalid_cidr"), result.invalid)
    }

    @Test
    fun batchScanRangeParsingRespectsMaxItemsCap() {
        val many = (1..60).joinToString("\n") { "10.0.$it.0/24" }
        val result = parseBatchScanRanges(many, maxItems = 10)
        assertEquals(10, result.valid.size)
    }

    @Test
    fun invalidCidrKeepsTheTypedTextAndReportsInline() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(conn))
        // 非法分支：只标红，不碰输入框。
        val invalidBranch = GuardSourceScan.bracedBlockAfter(text, "if (target == null)")
        assertTrue(
            invalidBranch.contains("cidrInvalid = true"),
            "非法网段必须当场标红，不能把值递下去等一条横幅（子页以前没有横幅宿主）。",
        )
        assertFalse(
            invalidBranch.contains("newCidr = \"\""),
            "非法网段不许清空输入框 —— 那正是「点了没反应」的现场（输入丢了、列表没变）。",
        )
        // 合法分支：写回列表并清空输入。
        val okBranch = window(text, "normalizeScanRangeOrNull(newCidr)", after = 520)
        assertTrue(okBranch.contains("addRange(target)"), "合法值要真的写回列表")
        assertTrue(okBranch.contains("newCidr = \"\""), "合法值才清空输入")
        assertTrue(
            text.contains("banner_scan_range_invalid"),
            "行内错误文案要复用规则本身（/22 上限那句）。",
        )
    }

    /* ===== 诊断页：加密口径与证书读数同源 ===== */

    @Test
    fun cryptoSummaryFollowsTheTlsIdentity() {
        assertEquals(CryptoSummary.LanOnlyPlaintext, cryptoSummary(hasTlsIdentity = false))
        assertEquals(CryptoSummary.LanPlaintextCrossTls, cryptoSummary(hasTlsIdentity = true))
    }

    @Test
    fun diagCryptoRowIsNotHardcodedToPlaintext() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(diag))
        assertTrue(
            text.contains("cryptoSummary(hasTlsIdentity)"),
            "「传输加密」那一行必须按本机 TLS 身份判档，不能恒定写「无 · 局域网内明文」" +
                "（同屏还有「证书到期」，两行会自相矛盾）。",
        )
        assertTrue(
            text.contains("set_diag_crypto_value_lan_cross"),
            "有 TLS 身份时要说清「局域网明文 · 跨网 TLS」。",
        )
        assertTrue(
            text.contains("set_diag_cert_expiry_hint"),
            "证书到期要有一句后果说明（自动续签、无需重配）。",
        )
        assertTrue(
            text.contains("set_diag_copy_node_id"),
            "节点 ID 的复制按钮要有专属 contentDescription（不能只报「复制」）。",
        )
    }

    /* ===== 触达：11sp 小字也是按钮 ===== */

    @Test
    fun fillDefaultPortIsARealTouchTarget() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(conn))
        // 锚用 `Res.string.…`：`import …set_conn_fill_default_port` 那一行也含裸键名。
        val block = window(text, "Res.string.set_conn_fill_default_port", after = 700)
        assertTrue(block.contains("minimumInteractiveComponentSize()"), "触达目标要给到 48dp")
        assertTrue(block.contains("role = Role.Button"), "读屏要按按钮报")
    }

    /* ===== 网段行：与其它设置行同族，扫描中不许重复触发 ===== */

    @Test
    fun scanRowsUseTheSharedRowAndDisableWhileScanning() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(conn))
        assertTrue(
            text.contains("SettingRow(title = entry.cidr"),
            "网段行要走共享 SettingRow（平铺行 + 发丝线），不是卡片里的卡片。",
        )
        assertFalse(
            text.contains("SurfaceTiers.Raised"),
            "网段行不许再包 8dp 圆角的 raised Surface（原型是平铺 .row）。",
        )
        assertTrue(
            text.contains("val progress = state.scanProgress"),
            "扫描中要能拿到状态：正在扫的那一段换「取消」，其余段灰着「探测」。",
        )
        // 行内动作现在按「这一段是不是正在扫」分支：
        //   正在扫 ⇒ 换「取消」（用户注意力在这一行，出口就地给）；
        //   其余段 ⇒ 「探测」but 禁用（点了会取消当前扫描重来，进度归零像卡住）。
        assertTrue(
            text.contains("enabled = progress == null"),
            "扫描进行中，别的段的「探测」必须禁用。",
        )
        assertTrue(
            text.contains("stringResource(Res.string.common_cancel)"),
            "正在扫的那一段要就地给「取消」，不能只有页脚有出口。",
        )
    }

    /* ===== 头像页：能移除，不再是死参数 ===== */

    @Test
    fun avatarPageCanRemoveThePickedImage() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(avatar))
        assertTrue(
            text.contains("onRemove = onRemovePicked"),
            "页面必须把 onRemovePicked 递进选图卡（它以前是**没人调用**的死参数，选了图就撤不回来）。",
        )
        assertTrue(
            text.contains("onClick = onRemove"),
            "选图卡里的「删除」要真的调它。",
        )
        val removeAt = text.indexOf("onClick = onRemove")
        val gate = text.substring(maxOf(0, removeAt - 420), removeAt)
        assertTrue(
            gate.contains("common_remove") && gate.contains("pickedAvatarBytes != null"),
            "移除入口只在真的选了图时出现，且用既有的「删除」文案。",
        )
    }

    /* ===== 更新页：失败给人话 ===== */

    @Test
    fun updateFailureShowsHumanCopyNotTheRawException() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(update))
        assertTrue(text.contains("set_update_failed_generic"), "失败态要给一句人话。")
        assertFalse(
            text.contains("updateState.errorMessage"),
            "不许把原始异常串（常是英文系统错误或字面量 error）直接上屏。",
        )
    }

    /* ===== 首页：三个通知开关同时开启时不藏掉一个 ===== */

    @Test
    fun homeNotifyValueShowsBothActiveFlags() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(home))
        val row = window(text, "Res.string.set_more_notif_title", after = 1400)
        assertTrue(
            row.contains("set_home_notify_mention_only") && row.contains("set_home_notify_quiet"),
            "两个开关都开时要并列显示，不能只报一个（另一个看起来没生效）。",
        )
        assertTrue(row.contains("joinToString"), "值列要把两个状态拼起来。")
    }

    /* ===== 横幅槽：外壳上的接缝不许悄悄消失 ===== */

    @Test
    fun scaffoldKeepsTheBannerSlot() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(chrome))
        assertTrue(
            text.contains("banner: (@Composable () -> Unit)? = null"),
            "SettingsPageScaffold 的横幅槽是宿主注入回执的位置，删掉它等于六屏又没反馈位。",
        )
        assertTrue(text.contains("banner()"), "槽要真的被调用（只声明不用 = 死接缝）。")
    }
}
