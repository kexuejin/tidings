// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 外部来源配对的闸门（task-54）。
 *
 * 修之前：`relay://` / `relay-pair://` 深链与 debug 的 `TEST_INJECT_QR` 广播**直接**调
 * `RelayController.handleQrCodeAsync` —— 那条路会新增/改写手动节点、写候选池、落下对端
 * TLS pin（公网 fail-closed 的信任根），**全程无二次确认**。
 *
 * 这里钉三件事：① 两个外部入口都被识别、且**只**登记不落盘；② 取消 ⇒ 零副作用；
 * ③ 允许 ⇒ 载荷**恰好交付一次**（防"确认后调了两次"）。
 *
 * "零副作用"的边界就是 [ExternalPairingGate.allow] 的那个 `apply` 回调：
 * 全应用里唯一会写节点/候选池/pin 的入口是 `handleQrCodeAsync`，它只可能从这个回调进 ——
 * 所以"取消后 `apply` 从未被调用"等价于"没有任何存储被碰过"。
 */
class ExternalPairingGateTest {

    // ---------------------------------------------------------------- 入口分类

    @Test
    fun bothExternalEntryPointsAreClassifiedAsPairingPayloads() {
        val deepLink = "relay-pair://?url=https%3A%2F%2Frelay.example&token=t"
        assertEquals(
            deepLink,
            externalPairingPayloadOf(null, deepLink, null),
            "relay-pair:// 深链必须被识别",
        )
        assertEquals(
            "relay://192.168.1.5:17878",
            externalPairingPayloadOf(null, "relay://192.168.1.5:17878", null),
            "relay:// 深链必须被识别",
        )
        assertEquals(
            "relay://192.168.1.5:17878",
            externalPairingPayloadOf(MainActivity.ACTION_TEST_INJECT_QR, null, "relay://192.168.1.5:17878"),
            "debug 广播（extra 里带 qr）必须被识别",
        )
        assertEquals(
            "relay://192.168.1.5:17878",
            externalPairingPayloadOf(MainActivity.ACTION_TEST_INJECT_QR, "relay://192.168.1.5:17878", null),
            "广播把载荷放 data 里的老写法也要认（历史脚本两种都发过）",
        )
    }

    @Test
    fun unrelatedIntentsAreNotPairingPayloads() {
        assertNull(externalPairingPayloadOf(null, null, null))
        assertNull(externalPairingPayloadOf("android.intent.action.VIEW", "https://example.com", null))
        assertNull(externalPairingPayloadOf(MainActivity.ACTION_TEST_INJECT_QR, null, "   "))
        assertNull(externalPairingPayloadOf(null, "http://192.168.1.5:17878", null), "http 不是配对深链")
    }

    // ---------------------------------------------------------------- 闸门行为

    @Test
    fun aRequestOnlyRegistersAndNeverApplies() {
        val gate = ExternalPairingGate()
        val applied = mutableListOf<String>()
        assertTrue(gate.request("relay://192.168.1.5:17878", preview = null))
        assertEquals("relay://192.168.1.5:17878", gate.pending?.payload, "载荷应当只是被登记下来")
        assertTrue(applied.isEmpty(), "登记阶段不许交付载荷")
    }

    @Test
    fun denyingLeavesNothingBehind() {
        val gate = ExternalPairingGate()
        val applied = mutableListOf<String>()
        gate.request("relay://192.168.1.5:17878", preview = null)
        gate.deny()
        assertNull(gate.pending, "取消之后不该还留着待确认")
        assertFalse(gate.allow { applied += it }, "取消之后 allow 必须什么都不做")
        assertTrue(applied.isEmpty(), "取消 = 零副作用：唯一的写入入口一次都没被调用")
    }

    @Test
    fun allowingDeliversThePayloadExactlyOnce() {
        val gate = ExternalPairingGate()
        val applied = mutableListOf<String>()
        gate.request("relay://192.168.1.5:17878", preview = null)
        assertTrue(gate.allow { applied += it })
        assertEquals(listOf("relay://192.168.1.5:17878"), applied)
        assertFalse(gate.allow { applied += it }, "第二次 allow 必须被拒（防确认后调两次）")
        assertEquals(1, applied.size, "载荷只许交付一次")
    }

    @Test
    fun aBlankPayloadIsRefusedInsteadOfQueued() {
        val gate = ExternalPairingGate()
        assertFalse(gate.request("   ", preview = null))
        assertNull(gate.pending)
    }

    // ---------------------------------------------------------------- 只读预览的"别比事实更确定"

    @Test
    fun thePreviewOnlyClaimsAKeyWhenThePayloadReallyCarriesOne() {
        val withPin = "{\"type\":\"direct\",\"pin\":\"abc123\",\"nodeId\":\"node-a\",\"nick\":\"客厅的电脑\"," +
            "\"addrs\":[{\"ip\":\"192.168.1.5\",\"port\":17878}]}"
        val parsed = parseExternalPairingPreview(withPin, emptyList())
        assertEquals("lan-peer", parsed?.kind)
        assertTrue(parsed?.hasPin == true, "载荷里带 pin ⇒ 确认框才可以说会固定密钥")
        assertEquals("node-a", parsed?.nodeId)
        assertEquals("客厅的电脑", parsed?.nick)

        val withoutPin = "{\"type\":\"direct\",\"nodeId\":\"node-b\"," +
            "\"addrs\":[{\"ip\":\"192.168.1.6\",\"port\":17878}]}"
        val noPin = parseExternalPairingPreview(withoutPin, emptyList())
        assertEquals("lan-peer", noPin?.kind)
        assertFalse(noPin?.hasPin == true, "载荷里没有 pin ⇒ 不许说会固定密钥")
    }

    @Test
    fun anUnparseablePayloadYieldsNoPreviewRatherThanAGuess() {
        assertNull(parseExternalPairingPreview("这不是任何配对载荷", emptyList()))
    }

    @Test
    fun aBareHostPortStillHasAnAddressToShowAsIdentity() {
        // 载荷里没有昵称/ID 时，确认框至少要说得出"要与哪一条地址配对"。
        val parsed = parseExternalPairingPreview("192.168.1.9:17878", emptyList())
        assertEquals("lan-peer", parsed?.kind)
        assertEquals("192.168.1.9:17878", parsed?.address)
        assertNull(parsed?.nodeId)
        assertNull(parsed?.nick)
        assertFalse(parsed?.hasPin == true)
    }

    // ---------------------------------------------------------------- 结构守卫：广播没有旁路

    /**
     * 广播接收器里**不许**出现任何直达配对/直达平台的调用（lead 裁决 a）。
     *
     * 为什么用源码级断言：这是一条**结构性**不变量（"不存在第二个分支"），行为测试测不到它
     * —— 只要分叉重新出现，运行时才会在某个特定界面组合下绕过闸门，而那种组合很难在单测里
     * 复现。读一眼源码是最直接的钉法（与 task-48 的 cap 台账守卫同一个手法）。
     *
     * **刻意不盯具体函数名**：原先那条分叉调的 `injectQrResultForTesting` 已按裁决删除，
     * 盯名字会变成空转（调一个不存在的函数根本编译不过）。改成钉"旁路的两条现实形状"：
     * 直接调配对入口（`handleQrCodeAsync(`）或直接碰平台层（`app.platform.`）—— 任何旁路都得是
     * 这两者之一，而闸门本身用的是 `requestExternalPairing(`。
     */
    @Test
    fun theBroadcastReceiverHasNoConfirmationBypass() {
        val source = readMainActivitySource()
        val receiver = source.substringAfter("private val qrInjectionReceiver", "")
        assertTrue(receiver.isNotEmpty(), "没找到广播接收器 —— 这条源码守卫会变成空转")
        val body = receiver.substringBefore("\n    }")
        assertTrue(body.contains("requestExternalPairing("), "广播载荷必须交给闸门")
        assertFalse(body.contains("handleQrCodeAsync("), "接收器不许直接调配对入口（旁路形状一）")
        assertFalse(body.contains("app.platform."), "接收器不许直接碰平台层/扫码器（旁路形状二）")
    }

    private fun readMainActivitySource(): String {
        val relative = "src/main/kotlin/MainActivity.kt"
        val starts = listOfNotNull(
            System.getProperty("user.dir")?.let(::File),
            runCatching {
                File(ExternalPairingGateTest::class.java.protectionDomain.codeSource.location.toURI())
            }.getOrNull(),
        )
        for (start in starts) {
            var dir: File? = start.absoluteFile
            while (dir != null) {
                val candidate = File(dir, relative)
                if (candidate.isFile) return candidate.readText()
                dir = dir.parentFile
            }
        }
        error("找不到 $relative（起点：${starts.joinToString { it.path }}）")
    }
}
