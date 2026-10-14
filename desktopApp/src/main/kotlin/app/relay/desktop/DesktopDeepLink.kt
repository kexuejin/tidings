// SPDX-License-Identifier: GPL-3.0-only

package app.relay.desktop

/**
 * `relay://` 深链与 `.relaymsg` 文件关联的解析。
 *
 * ## 为什么桌面端自己写一份解析
 * 手机侧那份是 `app.relay.app.parseQrPayload`（`QrPairing.kt`），它是 `internal`，
 * 跨模块取不到。而这里**不适合**靠放宽可见性去复用：扫码框的语义是「用户把二维码
 * 对着摄像头」，会接受裸 `host:port`；桌面收到的却是命令行参数与系统 URL 事件，
 * 裸串会和文件路径混淆（`C:\a\b` 也是一个「有冒号的串」）。两处容错范围不同，
 * 混用一份解析迟早会让某一边接受不该接受的东西。
 *
 * ## 不猜
 * 解析不出来就返回 `null`，由调用方决定「忽略」还是「提示」。绝不把
 * `relay://garbage` 半推半就当成 `garbage:0` 之类的东西塞进节点列表 ——
 * 往节点列表里加一个假节点，比什么都不做更糟。
 */
internal object DesktopDeepLink {

    /** 深链协议头。大小写不敏感（URL scheme 本来就如此）。 */
    const val SCHEME = "relay://"

    /** 文件关联后缀；打包 DSL 里的 `fileAssociation` 必须与它一致。 */
    const val MESSAGE_SUFFIX = ".relaymsg"

    /**
     * `relay://<host>:<port>` → `host to port`；不合法返回 `null`。
     *
     * 接受的形态：
     * - `relay://192.168.1.5:8788`
     * - `relay://desktop-node.local:8788`
     * - `relay://[2602:feda::1]:8788`（IPv6 带方括号，返回时去掉方括号）
     *
     * 端口取**最后一个**冒号之后的部分，这样 IPv6 地址里那一堆冒号不会被误当成端口。
     */
    fun parse(raw: String): Pair<String, Int>? {
        val text = raw.trim()
        if (!text.startsWith(SCHEME, ignoreCase = true)) return null

        val rest = text.substring(SCHEME.length)
        if (rest.isEmpty()) return null

        val colon = rest.lastIndexOf(':')
        // colon <= 0 表示没有冒号，或冒号打头（`:8788` 没有主机）。
        if (colon <= 0 || colon == rest.length - 1) return null

        val hostPart = rest.substring(0, colon).trim()
        // 带冒号的主机只接受方括号包起来的 IPv6。`relay://::1:8788` 这种没有方括号的
        // 写法在语法上就是歧义的（倒数第二个冒号前到底是主机还是端口的一部分？），
        // 与其猜一个，不如判为非法让它显式失败。
        val host = when {
            hostPart.startsWith("[") && hostPart.endsWith("]") ->
                hostPart.substring(1, hostPart.length - 1).trim()

            hostPart.contains(':') -> return null
            else -> hostPart
        }
        if (host.isEmpty()) return null

        val port = rest.substring(colon + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null

        return host to port
    }

    /** 是否是本次关联的消息文件。只认后缀，不解析内容（本期不定义文件格式）。 */
    fun isMessageFile(path: String): Boolean =
        path.trim().endsWith(MESSAGE_SUFFIX, ignoreCase = true)
}
