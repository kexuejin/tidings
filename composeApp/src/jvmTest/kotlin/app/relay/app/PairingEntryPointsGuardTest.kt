// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 守卫「配对入口都走多候选载荷」这条不变量。
 *
 * 为什么需要源码级守卫：这些入口都在 Composable 内部（节点页「我的二维码」、设置页连接子页、
 * 三处工作区直通码调用点），而本仓没有 Compose UI 测试基建 —— 单测只能覆盖
 * [pairingQrPayloadFor] 这个共用入口，覆盖不到"调用点有没有用它"。而漏一处的表现是**静默**的：
 * 那张码永远只有一个 IPv4，跨网络的手机扫了连不上，界面上没有任何报错。
 *
 * 为什么放在 jvmTest 而不是 commonTest：断言的是源码文本，只有 JVM 侧能读文件
 * （与 SystemBarsInsetGuardTest 同一取舍）。
 *
 * ## 这条守卫**曾经把缺陷钉死**（真机现场）
 *
 * 它原来断言的标记是 `addresses = ...localAddresses` —— 也就是「每处入口都必须用**窄**的那份
 * 地址表」。于是手机卸载重装后（nodeId 变了、`known-peers` 空了），三条发现路径全断：
 * 受限广播出不了本机子网、`utun` 是 `/32` 点对点没有广播语义、mDNS 同样限于局域网。
 * 唯一剩下的引导通道是扫这张码 —— 而码里恰好没有隧道地址，走 Tailscale 的那台 Mac
 * 永远连不上。**守卫绿色的那段时间，缺陷一直是活的。**
 *
 * 教训写在断言里：守卫要钉「性质」，不要钉「当时那一行怎么写」。
 */
class PairingEntryPointsGuardTest {

    /** 每处入口必须出现的标记：要么调共用入口，要么把地址表交给直通码弹层。 */
    private val entryPoints = listOf(
        "composeApp/src/commonMain/kotlin/app/relay/feature/nodes/NodeSheets.kt" to
            "pairingQrPayloadFor(",
        "composeApp/src/commonMain/kotlin/app/relay/feature/nodes/NodesScreen.kt" to
            "addresses = network.advertisedAddresses",
        "composeApp/src/commonMain/kotlin/app/relay/feature/settings/SettingsConnectionSubpage.kt" to
            "pairingQrPayloadFor(",
        "composeApp/src/commonMain/kotlin/app/relay/App.kt" to
            "addresses = state.network.advertisedAddresses",
        "composeApp/src/commonMain/kotlin/app/relay/ui/desktop/DesktopShell.kt" to
            "addresses = state.network.advertisedAddresses",
        "composeApp/src/commonMain/kotlin/app/relay/feature/agents/WorkspaceDetailView.kt" to
            "addresses = if (qrIsSelf) state.network.advertisedAddresses else emptyList()",
    )

    @Test
    fun pairingEntriesAllCarryMultiCandidates() {
        for ((relativePath, marker) in entryPoints) {
            val file = File(scaffoldRoot(), relativePath)
            assertTrue(file.isFile, "找不到入口源码：$relativePath")
            assertTrue(
                file.readText().contains(marker),
                relativePath + " 必须带上多候选（缺标记：" + marker + "）—— 只给一个 IPv4 时，" +
                    "跨网络的手机扫这张码连不上，且不会有任何报错。",
            )
        }
    }

    @Test
    fun pairingEntriesDoNotBuildSingleHostPayloadsThemselves() {
        // pairingPayload(host, port) 是单地址形态；入口里直接用它就等于静默退回旧行为。
        // （它自己仍然合法：pairingQrPayloadFor 的兜底分支与 WorkspaceQrDialog 的旧形态在用。）
        val offenders = entryPoints.map { it.first }.filter { path ->
            File(scaffoldRoot(), path).readText().contains("pairingPayload(")
        }
        assertTrue(
            offenders.isEmpty(),
            "这些入口还在直接调 pairingPayload(...)（单地址）：" + offenders + " —— " +
                "改用 pairingQrPayloadFor，或把 addresses 交给 WorkspaceQrDialog / MyQrSheet。",
        )
    }

    /**
     * 守卫第二件静默的事：**算候选预算时必须把 pin 算进去**。
     *
     * `qrCandidatesFor(...)` 的 `tlsPin` 参数就是"这次载荷会不会带 pin"。漏掉它，裁剪得到的是
     * "没有 pin 时"的那一池候选，而真正生成载荷时带了 pin ⇒ **多出 43 个字节**，最后可能装不下、
     * 或者提示"码里有公网 v6"而码里其实已经被裁掉。两种都不会报错，只会让用户扫了连不上。
     */
    @Test
    fun candidateBudgetsReserveThePinTheyWillPrint() {
        // 只查**在同一个文件里既算候选预算、又印 pin** 的地方：这两件事必须用同一组参数。
        val files = listOf(
            "composeApp/src/commonMain/kotlin/app/relay/ui/components/WorkspaceQrDialog.kt",
            "composeApp/src/commonMain/kotlin/app/relay/ui/desktop/DesktopConversationsPane.kt",
            "composeApp/src/commonMain/kotlin/app/relay/app/QrPairing.kt",
        )
        val offenders = files.filter { path ->
            val text = File(scaffoldRoot(), path).readText()
            // 有裸的 qrCandidatesFor(...) 调用（不带 tlsPin =）就记一笔。参数里出现 "tlsPin =" 的
            // 那个调用不算 —— 用正则抓每个调用的整段参数。
            val callPattern = Regex("qrCandidatesFor\\s*\\((?:[^()]|\\([^()]*\\))*\\)", RegexOption.DOT_MATCHES_ALL)
            callPattern.findAll(text).any { match ->
                !match.value.contains("tlsPin")
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "这些文件里的 qrCandidatesFor(...) 没有预留 pin 的字节：" + offenders + " —— " +
                "预算与最终载荷必须带**同一组**参数，否则裁剪结果与实际码内容不一致（静默）。",
        )
    }

    /**
     * 守卫第三件静默的事：**印码的地方必须把昵称带上**。
     *
     * 真机反馈：扫完码，对方节点表里显示 `node-a1b2c3d4` 而不是 `demo-mac-97`，
     * 要等下一次 profile 交换才改过来 —— 中间那段时间用户以为连错了设备。
     * 漏传 `nick =` 不会报错、不会崩、单测也不会红（除非逐个入口断言），所以在源码层钉住。
     */
    @Test
    fun pairingEntriesPassTheNickname() {
        val printers = listOf(
            "composeApp/src/commonMain/kotlin/app/relay/feature/nodes/NodeSheets.kt",
            "composeApp/src/commonMain/kotlin/app/relay/feature/settings/SettingsConnectionSubpage.kt",
            "composeApp/src/commonMain/kotlin/app/relay/ui/components/WorkspaceQrDialog.kt",
            "composeApp/src/commonMain/kotlin/app/relay/ui/desktop/DesktopConversationsPane.kt",
        )
        val offenders = printers.filter { path ->
            !File(scaffoldRoot(), path).readText().contains("nick")
        }
        assertTrue(
            offenders.isEmpty(),
            "这些印码文件没有把昵称带上：" + offenders + " —— 对方扫完只会看到 node-xxxx 合成名。",
        )
    }
    /** 从 Gradle 的测试工作目录向上找 settings.gradle.kts，避免写死相对层级。 */
    private fun scaffoldRoot(): File {
        var dir: File? = File("").absoluteFile.normalize()
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "composeApp").isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError("找不到 scaffold 根目录（工作目录 " + File("").absolutePath + "）")
    }
}
