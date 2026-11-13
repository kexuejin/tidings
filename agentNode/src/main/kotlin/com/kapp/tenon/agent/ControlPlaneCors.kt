// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.sun.net.httpserver.HttpExchange

/**
 * `/healthz` 的 CORS 辅助（从 `ControlPlane.kt` 按资源切出来的第二刀）。
 *
 * 为什么搬家：控制面装配文件的 800 行预算被新的工具面顶到 822 行，而这两条判据
 * **不碰任何实例状态**（只读 `Origin` 头、只写响应头）—— 它们既不在 `init` 里被装、
 * 也不在 `stop()` 里被清，留在装配文件里只是占行数。
 *
 * 判据一个字没改（见下方两份 KDoc）：写路径拆家不该改语义。
 */

/**
 * 给 `/healthz` 加 CORS —— **只对回环来源、只在 GET/OPTIONS 这一条路由上**。
 *
 * ## 为什么需要它
 *
 * 桌面端设置页里那张「榫卯」卡片跑在浏览器里（DSH 的 Web GUI，端口每次启动都变）。
 * 它要判断「本机榫卯桌面端在不在」，最直接的办法就是问 `127.0.0.1:8788/healthz`。
 * 但那是**另一个端口** ⇒ 跨域，浏览器不给读响应，除非我们回一个允许的 Origin。
 *
 * 在这之前，卡片只能绕经 DSH 的插件 RPC；而那条通道在桌面端宿主上挂不起来，
 * 于是卡片把「我读不到」显示成「未检测到榫卯桌面端」—— 桌面端明明在跑。
 *
 * ## 边界（这是安全取舍，别放宽）
 *
 * - **只有 `/healthz`**：`/v1/` 下的所有路由（消息、授权台账、发送、投屏）一律不加 CORS；
 * - **只有回环来源**：Origin 的 host 必须是 127.0.0.1 / localhost / [::1]，
 *   用 host 精确比对而不是 `startsWith`（`127.0.0.1.evil.com` 必须被判否）；
 * - 回显 `Vary: Origin`，避免中间缓存把 A 站点的允许头复用到 B 站点。
 *
 * 暴露面就是 healthz 本身（nodeId、昵称、端口、邻居数），不含任何消息内容与授权状态。
 */
internal fun allowLoopbackCors(exchange: HttpExchange) {
    val origin = exchange.requestHeaders.getFirst("Origin") ?: return
    if (!isLoopbackOrigin(origin)) return
    exchange.responseHeaders.add("Access-Control-Allow-Origin", origin)
    exchange.responseHeaders.add("Vary", "Origin")
}

/**
 * `Origin` 是否来自本机回环页面。
 *
 * 只接受 `http(s)://<host>[:port]` 里 host 恰好是回环名之一 —— **按 host 段精确比对**。
 * 用 `contains("127.0.0.1")` 这类写法会把 `http://127.0.0.1.evil.com` 放进来，
 * 那等于给任意站点开了口子。解析失败一律当否（fail-closed）。
 */
internal fun isLoopbackOrigin(origin: String): Boolean {
    // `Origin: null` 是**不透明来源**：桌面端 webview 用 `file://` 载入页面时就是它。
    // 控制面本身只绑 127.0.0.1，能发出请求的一定是本机进程；而这个头只对 `/healthz`
    // 生效，暴露面仍是节点名与邻居数。放行它是为了「桌面端宿主没有面板通道」那种
    // 情况下卡片还能自己读到状态 —— 否则它又会退回那句「未检测到」。
    if (origin == "null") return true
    // **应用注册的自定义协议**（如 DSH 桌面端的 `dsh-app://app`）。
    //
    // 桌面端并不是用 http 载入界面的：它 `loadURL("dsh-app://app/")`，于是页面里
    // `fetch` 到本机控制面时带的是 `Origin: dsh-app://app`。按主机名判断会把它拒掉，
    // 浏览器侧的表现就是同一句 `TypeError: Failed to fetch`。
    //
    // 放行它为什么安全：网页只能发出 http/https（或 `null`）来源，**伪造不了自定义
    // 协议** —— 那需要操作系统里真的注册过该 scheme 的应用。
    val scheme = origin.substringBefore("://", missingDelimiterValue = "")
    if (scheme.isNotEmpty() && scheme != "http" && scheme != "https") return true
    val rest = when {
        origin.startsWith("http://") -> origin.removePrefix("http://")
        origin.startsWith("https://") -> origin.removePrefix("https://")
        else -> return false
    }
    // host[:port] → host；IPv6 形如 [::1]:1234
    val host = when {
        rest.startsWith("[") -> rest.substringBefore(']').removePrefix("[")
        else -> rest.substringBefore(':')
    }.lowercase()
    return host == "127.0.0.1" || host == "localhost" || host == "::1"
}
