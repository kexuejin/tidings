// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.crypto.TlsPin
import com.kapp.tidings.core.protocol.IpAddressUtils
import com.kapp.tidings.core.protocol.Ipv6Scope
import com.kapp.tidings.core.protocol.Protocol
import com.kapp.tidings.core.protocol.isUuidV4
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 候选对端地址（用于多网络自适应直连）。
 */
data class PeerCandidate(
    val type: String, // PeerCandidateTypes 之一：lan_v4 / lan_v6 / public_v6 / domain / other
    val host: String,
    val port: Int,
)

/**
 * 二维码里的 luciola 段（`l` 键）:对端 Ed25519 公钥 + 配对证明。
 *
 * 端口不单列:luciola 的加密信道 TCP 监听端口**复用发现 UDP 端口**(同号不同协议),
 * 就是二维码里那条候选自带的 port。主机想在别的端口监听,就得先回来改这个格式。
 *
 * `secret` = 主机生成的随机配对密钥(128 位)。它只在二维码里出现,证明扫码方**真的
 * 看过这张码**;配对帧里带上它,主机才放行(见 core 的 `PairingAdmission`)。
 */
public data class LuciolaQrInfo(
    val publicKey: String,
    val secret: String,
) {
    public companion object {
        /** 公钥 32 字节、secret 16 字节;长度不对返回 null（宁可不写,不写坏码）。 */
        public fun of(publicKey: ByteArray, secret: ByteArray): LuciolaQrInfo? {
            if (publicKey.size != ED25519_PUBLIC_KEY_SIZE) return null
            if (secret.size != PAIR_SECRET_BYTES) return null
            return LuciolaQrInfo(base64UrlEncode(publicKey), base64UrlEncode(secret))
        }

        internal const val ED25519_PUBLIC_KEY_SIZE = 32
        internal const val PAIR_SECRET_BYTES = 16
    }
}

/** 编成线上的 `"<公钥 b64url>:<配对密钥 b64url>"`;非法长度返回 null（宁可不写）。 */
internal fun encodeLuciolaQrInfo(info: LuciolaQrInfo): String? {
    val publicKey = base64UrlDecode(info.publicKey) ?: return null
    val secret = base64UrlDecode(info.secret) ?: return null
    if (publicKey.size != LuciolaQrInfo.ED25519_PUBLIC_KEY_SIZE) return null
    if (secret.size != LuciolaQrInfo.PAIR_SECRET_BYTES) return null
    return info.publicKey + ":" + info.secret
}

/** [encodeLuciolaQrInfo] 的逆;任何一段解不出来就返回 null（只丢这一段,不废整张码）。 */
internal fun decodeLuciolaQrInfo(raw: String): LuciolaQrInfo? {
    val parts = raw.split(':')
    if (parts.size != 2) return null
    val publicKey = base64UrlDecode(parts[0]) ?: return null
    val secret = base64UrlDecode(parts[1]) ?: return null
    if (publicKey.size != LuciolaQrInfo.ED25519_PUBLIC_KEY_SIZE) return null
    if (secret.size != LuciolaQrInfo.PAIR_SECRET_BYTES) return null
    return LuciolaQrInfo(base64UrlEncode(publicKey), base64UrlEncode(secret))
}

/**
 * 候选地址的类型标签 —— **两端共用这一份**。
 *
 * 曾经桌面端写 "lan"/"ipv6"、手机端找 "lan_v4"/"public_v6"，两边对不上 ⇒ 手机永远落到
 * `candidates.first()`（网卡枚举序），于是"优先局域网"那条规则从来没生效过。类型字符串
 * 散在两个模块里迟早还会分叉，所以收在这里：**生成端与解析端都从这里取**。
 */
public object PeerCandidateTypes {
    /** 直连 IPv4（同网段时最省事：不经防火墙、不受公网入站策略影响）。 */
    public const val LAN_V4: String = "lan_v4"
    /** 局域网 IPv6（ULA `fc00::/7`）：同网段可用，跨网不可用。 */
    public const val LAN_V6: String = "lan_v6"
    /** 公网 IPv6（GUA `2000::/3`）：跨网络时唯一可能直连的类型（前提是主机侧允许入站）。 */
    public const val PUBLIC_V6: String = "public_v6"
    /**
     * **覆盖网隧道 IPv4**（Tailscale / ZeroTier 的 `100.64.0.0/10`）。
     *
     * ## 为什么单独一档、而不是并入 `lan_v4`
     *
     * 它的可达性**依赖两端都在同一张覆盖网里** —— 不像局域网那样「同网段就连得上」。
     * 所以它在优先级阶梯里排最后（见 [choosePeerCandidate](同一文件)）：同网段的设备仍走
     * `lan_v4`，只有别的路都不可用时才落到它。
     *
     * ## 为什么必须有这一档（真机现场）
     *
     * 手机卸载重装 ⇒ nodeId 变了 ⇒ 那台机器上 `known-peers` 是空的。三条发现路径同时断：
     * 受限广播出不了本机子网、`utun` 是 `/32` 点对点没有广播语义、mDNS 同样限于局域网。
     * 唯一剩下的引导通道是**扫桌面端的码** —— 而 `100.64/10` 原先被 `isFakeCandidateRange`
     * 整段挡掉，于是「走 Tailscale 的那台 Mac」永远进不了码，手机永远连不上它。
     */
    public const val TUNNEL_V4: String = "tunnel_v4"
    /** 主机名（`desktop-node.local` 这种）。 */
    public const val DOMAIN: String = "domain"
    /** 认不出类型但仍是合法主机串。 */
    public const val OTHER: String = "other"
}

/**
 * 给一个主机串判候选类型；**返回 null = 不该进配对码**。
 *
 * 为什么宁可不给也不猜：配对码里的每个地址都会被真的连过去，也会被用户当成"这是我这台机器的
 * 地址"。所以这里只放三类**真的可达**的地址，其余一律丢掉：
 *  - IPv4（非回环、非 `169.254/16` 链路本地、非代理 fake-IP `198.18/15`、非隧道 CGNAT
 *    `100.64/10` —— 见 [isFakeCandidateRange]）→ [PeerCandidateTypes.LAN_V4]；
 *  - IPv6 GUA `2000::/3`（且无 `%网卡` scope）→ [PeerCandidateTypes.PUBLIC_V6]；
 *  - IPv6 ULA `fc00::/7` → [PeerCandidateTypes.LAN_V6]；
 *  - IPv6 链路本地 `fe80::/10` / 回环 / 未指定 / 组播 / IPv4-mapped → **null**（跨设备不可路由，
 *    "拿它冒充公网地址"是这条规则要防的事）；
 *  - 主机名 → [PeerCandidateTypes.DOMAIN]；其余合法串 → [PeerCandidateTypes.OTHER]。
 */
public fun peerCandidateTypeOf(host: String): String? {
    // 先剥 scope 后缀：macOS 的 JDK 会给每个 IPv6 都带 `%en1`，带着它判作用域会被一律判成
    // 链路本地（`getIpv6Scope` 见 % 即 LINK_LOCAL），一个公网地址就这么被冤枉掉。
    val clean = IpAddressUtils.unbracket(host).trim().substringBefore('%')
    if (clean.isEmpty() || clean.contains(' ') || clean.contains('/')) return null
    if (IpAddressUtils.isIpv4(clean)) {
        val octets = clean.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) return null
        if (octets[0] == 127) return null                 // 回环：只有本机能连
        if (octets[0] == 169 && octets[1] == 254) return null // 链路本地：不可路由
        // 代理 fake-IP：对端照它连只会连到不存在的隧道网关，**任何情况都不进码**。
        if (isFakeCandidateRange(octets)) return null
        // 覆盖网隧道（Tailscale / ZeroTier）：能连，但**只限同在那一张网里的设备**，
        // 所以单独一档、优先级最低，而不是丢掉 —— 见 TUNNEL_V4 的 KDoc。
        if (isTunnelRange(octets)) return PeerCandidateTypes.TUNNEL_V4
        return PeerCandidateTypes.LAN_V4
    }
    if (IpAddressUtils.isIpv6(clean)) {
        return when (IpAddressUtils.getIpv6Scope(clean)) {
            Ipv6Scope.GLOBAL -> PeerCandidateTypes.PUBLIC_V6
            Ipv6Scope.UNIQUE_LOCAL -> PeerCandidateTypes.LAN_V6
            // LINK_LOCAL / LOOPBACK / OTHER（未指定、组播、IPv4-mapped）都不进配对码。
            // 带 % 的地址在 getIpv6Scope 里已经归到 LINK_LOCAL，这里同样被挡掉。
            else -> null
        }
    }
    // 既不是 IPv4 也不是 IPv6：主机名走 domain，其余（含含冒号的怪串）只当 other。
    val looksLikeHostname = clean.length <= 253 && clean.any { it.isLetter() } &&
        clean.all { it.isLetterOrDigit() || it == '-' || it == '.' || it == '_' }
    return if (looksLikeHostname) PeerCandidateTypes.DOMAIN else PeerCandidateTypes.OTHER
}

/**
 * IPv4 里**不该进配对候选**的两段（真机实测：Mac 上 5 个 v4 候选里有 2 个来自这两段）：
 *
 *  - `198.18.0.0/15`：RFC 2544 基准测试段，实际是 Clash / sing-box 这类代理的 **fake-IP**
 *    （`198.18.0.1` 是它的网关）—— 对端照它连过去只会连到一个不存在的隧道网关；
 *  - `100.64.0.0/10`：RFC 6598 运营商级 NAT 段，本机上是 **Tailscale / ZeroTier** 之类的
 *    点对点隧道地址 —— 只有同在那一张覆盖网里的设备才连得上，而"局域网直连"这张码不该
 *    承诺覆盖网的可达性。
 *
 * 只挡这两段（加上回环与 `169.254/16`）：`10/8`、`172.16/12`、`192.168/16` 这些**真·私网**
 * 一律放行 —— 过滤过宽会把用户真实的局域网地址也挡掉，那比多两条假候选坏得多。
 */
public fun isFakeCandidateRange(octets: List<Int>): Boolean {
    if (octets.size != 4) return false
    val a = octets[0]
    val b = octets[1]
    if (a == 198 && (b == 18 || b == 19)) return true   // 198.18.0.0/15
    return false
}

/**
 * RFC 6598 运营商级 NAT 段 `100.64.0.0/10` —— 本机上是 **Tailscale / ZeroTier** 的隧道地址。
 *
 * 与 [isFakeCandidateRange] 分开的理由：fake-IP 是**任何情况都连不通**（照它拨只会打到代理
 * 自己的网关），隧道地址是**同网可达**。前者要从候选里彻底消失，后者只是优先级低。
 */
public fun isTunnelRange(octets: List<Int>): Boolean {
    if (octets.size != 4) return false
    return octets[0] == 100 && octets[1] in 64..127
}

/**
 * 虚拟/点对点网卡的接口名前缀：这些接口上的地址**不是**"对端能直接连过来的地址"。
 *
 * - `utun*`：macOS 的隧道口（WireGuard / IKEv2 / Tailscale 都落在这里）；
 * - `feth*`：macOS 的 fake ethernet（VM / 网络扩展用；真机上它带着 `172.26.*`/`10.233.*`
 *   这种"看着像私网、其实只存在于本机网络栈"的地址）；
 * - `tailscale*`：某些版本会把隧道接口直接叫这个名字；
 * - `tun*`：Android 的 VPN 接口就叫 `tun0`（macOS 侧是 `utun*`，两个前缀都要，因为 `utun0`
 *   并不以 `tun` 打头）。
 *
 * 只按名字挡这三类：`en*`/`eth*`/`wlan*` 这些真网卡一律放行（`awdl*`/`llw0` 只带链路本地
 * 地址，已经被作用域判据挡掉了，不必再按名字判）。
 */
public fun isVirtualInterfaceName(name: String): Boolean {
    val lower = name.trim().lowercase()
    return lower.startsWith("utun") || lower.startsWith("feth") ||
        lower.startsWith("tailscale") || lower.startsWith("tun")
}

/**
 * 各配对入口**共用**的二维码载荷选择：有本机候选就用多候选（二维码预算版），否则退回
 * 单地址形态（`relay://host:port`）。
 *
 * 存在的理由：同一个选择要在五处调用点重复（节点页「我的二维码」、设置页连接子页、手机与
 * 桌面的三处工作区直通码）。散着写迟早漏一处，而漏掉的表现是"那张码永远只有一个 IPv4"——
 * 跨网络的手机扫了也没用，且没有任何报错。
 */
public fun pairingQrPayloadFor(
    host: String?,
    addresses: List<String>,
    port: Int,
    nodeId: String = "",
    /**
     * 本机 TLS pin（`state.network.selfTlsPin`）。**必须传**（task-86 真机）：
     *
     * 公网长连接的拨号目标要求 `pin != null`（fail-closed），而二维码是主机→手机单向的
     * 信任通道 —— 不印 pin，手机建不出公网 TLS 目标，只剩局域网明文那条，
     * 在蜂窝上一直 `connect-timeout`。**节点页「我的二维码」正是漏掉这一处**。
     */
    tlsPin: String? = null,
    /**
     * 本机昵称（`state.settings.nick`）。带上它，对方节点表里**第一眼**就是真名，
     * 而不是 `node-a1b2c3d4` 这种合成名（见 [qrPairingPayloadForAddresses] 的 KDoc）。
     * 预算同样要算（[qrCandidatesFor] 会把它算进去）。
     */
    nick: String? = null,
    /** luciola 段（可选）:开了实验信道的主机把它放进同一个码,老端忽略。 */
    luciola: LuciolaQrInfo? = null,
): String? {
    if (nodeId.isNotBlank() && addresses.isNotEmpty()) {
        qrPairingPayloadForAddresses(
            nodeId, addresses, port, tlsPin = tlsPin, nick = nick, luciola = luciola,
        )?.let { return it }
    }
    return host?.takeIf { it.isNotBlank() }?.let { pairingPayload(it, port) }
}
/**
 * 本机地址列表 → 候选池：判类型、丢掉不该进码的、按 host 去重、保序。
 *
 * 公网 IPv6 **只在确实拿到 GUA 时出现**（[peerCandidateTypeOf] 的判据），ULA 只会是 lan_v6 ——
 * 没有公网地址时这里就少一条，绝不编一个出来。
 */
public fun peerCandidatesFrom(addresses: List<String>, port: Int): List<PeerCandidate> {
    if (port !in 1..65535) return emptyList()
    val out = LinkedHashMap<String, PeerCandidate>()
    for (raw in addresses) {
        val host = IpAddressUtils.unbracket(raw).trim()
        if (host.isEmpty()) continue
        val type = peerCandidateTypeOf(host) ?: continue
        if (out.containsKey(host)) continue
        out[host] = PeerCandidate(type = type, host = host, port = port)
    }
    return out.values.toList()
}

/**
 * 候选与本机的某个地址是否同网段（v4 按 /24、v6 按 /64）。
 *
 * 只做最常见的两种前缀：配对选择要的是"大概率同网段"这个判断，不是子网计算器。
 */
internal fun isSameLanAddress(candidateHost: String, myAddresses: List<String>): Boolean {
    val clean = IpAddressUtils.unbracket(candidateHost).trim()
    for (raw in myAddresses) {
        val mine = IpAddressUtils.unbracket(raw).trim()
        if (mine.isEmpty()) continue
        if (IpAddressUtils.isIpv4(clean) && IpAddressUtils.isIpv4(mine)) {
            val a = clean.split('.')
            val b = mine.split('.')
            if (a.size == 4 && b.size == 4 && a[0] == b[0] && a[1] == b[1] && a[2] == b[2]) return true
        } else if (IpAddressUtils.isIpv6(clean) && IpAddressUtils.isIpv6(mine)) {
            if (IpAddressUtils.sameIpv6Prefix(clean, mine, 64)) return true
        }
    }
    return false
}

/**
 * 从候选里挑"先打哪个"。规则按优先级逐条可解释（**不是"第一个"**）：
 *
 *  1. **同网段**（v4 /24 或 v6 /64，[isSameLanAddress]）→ 直连：不经防火墙、不受公网入站策略影响；
 *     同类里先 v4（本产品的 v4 单播路径最成熟）；
 *  2. **已知自己不在同一网段**（本机地址表非空、但一条都没命中）→ **public_v6**：跨网络时
 *     唯一可能直连的类型（前提：主机侧路由器/防火墙放行入站）；
 *  3. **本机地址表为空**（刚切网 / 平台拿不到地址）→ lan_v4 → lan_v6：不知道自己在哪时，
 *     "两台机器同处一网"仍是更常见的场景，先把内网地址试掉；
 *  4. 再退回 public_v6 → domain → other。
 *
 * 同一档内保留调用方给的顺序（那是网卡枚举序）—— 只有这一层允许"取第一个"，因为档位已经说明了原因。
 * 候选为空时返回 null（调用方据此报"二维码内容不认识"，而不是连一个编出来的地址）。
 */
internal fun choosePeerCandidate(
    candidates: List<PeerCandidate>,
    myAddresses: List<String> = emptyList(),
): PeerCandidate? {
    if (candidates.isEmpty()) return null
    fun pick(vararg types: String, sameLanOnly: Boolean = false): PeerCandidate? =
        candidates.firstOrNull { c ->
            c.type in types && (!sameLanOnly || isSameLanAddress(c.host, myAddresses))
        }
    // 1. 同网段（有本机地址可对账时）——最省事也最可靠的一条。
    pick(PeerCandidateTypes.LAN_V4, sameLanOnly = true)
        ?.let { return it }
    pick(PeerCandidateTypes.LAN_V6, sameLanOnly = true)
        ?.let { return it }
    // 2. 有本机地址却一条都没命中 ⇒ 我们确实在别的网段：公网 v6 是唯一可能直连的类型。
    if (myAddresses.isNotEmpty()) {
        pick(PeerCandidateTypes.PUBLIC_V6)?.let { return it }
    }
    // 3./4. 不知道自己在哪（或没有公网候选）：先内网、再公网、最后兜底。
    return pick(PeerCandidateTypes.LAN_V4)
        ?: pick(PeerCandidateTypes.LAN_V6)
        ?: pick(PeerCandidateTypes.PUBLIC_V6)
        // 覆盖网隧道（Tailscale / ZeroTier）：同网才可达，所以排在所有直连类型之后。
        ?: pick(PeerCandidateTypes.TUNNEL_V4)
        ?: pick(PeerCandidateTypes.DOMAIN)
        ?: pick(PeerCandidateTypes.OTHER)
        ?: candidates.first()
}

/**
 * 本机地址列表 → 多候选配对载荷；**一个可用候选都没有时返回 null**（调用方据此不画码）。
 *
 * 这是"配对码带全部候选"的唯一入口：`lan_v4` / `lan_v6` / `public_v6` 三种类型都从
 * [peerCandidateTypeOf] 来，IPv6 一律方括号（[multiCandidatePairingPayload] 负责）。
 */
public fun pairingPayloadForAddresses(
    nodeId: String,
    nick: String? = null,
    addresses: List<String>,
    port: Int,
    tlsPin: String? = null,
    /** luciola 段（可选）:同一个码双栈,老端忽略。 */
    luciola: LuciolaQrInfo? = null,
): String? {
    val candidates = peerCandidatesFrom(addresses, port)
    if (candidates.isEmpty()) return null
    return multiCandidatePairingPayload(nodeId, nick, tlsPin, candidates, luciola = luciola)
}


/**
 * 二维码载荷的字节上限 = [com.kapp.tidings.core.qr.QrEncoder] 的**版本 8** / 纠错等级 M 容量（153 字节）。
 *
 * ## 为什么不是编码器的上限 213（版本 10）
 *
 * 编码器能编到版本 10，那是**标准**允许，不是**屏幕**允许。版本 8 是 49×49 模块、版本 10 是
 * 57×57；码画在 180dp 的卡片上时，每模块只有 `180 / (49 + 8)` = **3.2dp**（含 4 模块静区）。
 * 真机上这个密度**扫不出来**：同样的载荷内容、同样的屏幕，之前 152 字节（版本 8）的码一扫即中，
 * 而带上 TLS pin 后涨到 207 字节（版本 10）就再也读不出——用户看到的是「码画得出来但相机没反应」。
 *
 * 所以这里主动**把预算压到版本 8**，代价是码里装不下太多候选（见 [qrCandidatesFor] 的裁剪），
 * 收益是这张码**真的能扫**。跨网唯一不可替代的 public_v6 仍排在轮转第一档，优先留在码里。
 *
 * 同一个理由也让载荷本身做了二进制瘦身（[multiCandidatePairingPayload]）：不改格式只改上限，
 * 版本 8 只装得下 1 条候选；改成 base64url 二进制后，**pin + 公网 v6 + 内网 v4** 一起装得下。
 */
public const val QR_PAYLOAD_MAX_BYTES: Int = 153

/**
 * 本机地址 → **二维码用的**候选池：按前缀折叠 → 按类型轮转 → 裁到字节预算内。
 *
 * 三步各自解决一个真机问题：
 *  - **折叠**：同一张网卡上 5 个公网 GUA 只是接口标识不同（隐私地址轮换），/64 一样 ⇒ 一条就够；
 *  - **轮转**：先各类型取一条（lan_v4 → public_v6 → lan_v6），保证"同网段优先"与"跨网可选公网"
 *    两条路都留得下；同类型里私网 IPv4 排在前面；
 *  - **预算**：逐条试装，装不下的跳过（后面更短的条目还有机会），保证生成的载荷一定能编码成二维码。
 *
 * 拿不到任何可用地址时返回空列表（调用方据此不画码，而不是画一张扫不出来的）。
 */
public fun qrCandidatesFor(
    addresses: List<String>,
    port: Int,
    nodeId: String,
    budgetBytes: Int = QR_PAYLOAD_MAX_BYTES,
    /**
     * 最终载荷会带上的 TLS pin。**预算必须把它算进去**（task-86 回归）：
     *
     * pin 是 `"pin":"sha256:<64 hex>"` ≈ 78 字节 —— 不预留就会出现"候选裁剪时说能装下、
     * 加上 pin 之后超预算"，编码器直接抛异常，界面只剩一句「二维码过长」，**码画不出来**。
     */
    tlsPin: String? = null,
    /**
     * 最终载荷会带上的昵称（`"k"` 键）。**与 [tlsPin] 同一个纪律：预算必须算进去** ——
     * 载荷是一次性生成的，裁剪时按"不带昵称"估、生成时又带上昵称，就会超预算。
     *
     * 为什么二维码里要带昵称（真机反馈）：手机扫完只知道 nodeId，于是节点表里显示成
     * `node-a1b2c3d4` 这种合成名，而对方明明是 `mac-mini` —— 要等下一次
     * profile 交换才显示真名，中间那段时间用户以为自己连错了设备。二维码是配对时
     * **唯一**能主动交代"我是谁"的通道，昵称 15–25 字节就能省掉这段困惑。
     */
    nick: String? = null,
    /** 将要写进载荷的 luciola 段;预算必须把它算进去(同 [tlsPin] / [nick] 的纪律)。 */
    luciola: LuciolaQrInfo? = null,
): List<PeerCandidate> {
    val ranked = roundRobinByType(collapseSamePrefix(peerCandidatesFrom(addresses, port)))
    val out = mutableListOf<PeerCandidate>()
    for (c in ranked) {
        val trial = out + c
        val trialLen = multiCandidatePairingPayload(
            nodeId, nick = nick, tlsPin = tlsPin, candidates = trial, luciola = luciola,
        ).length
        if (trialLen > budgetBytes) continue
        out += c
    }
    return out
}

/**
 * 二维码载荷：**带昵称**（`k` 键）。
 *
 * 昵称必须放进码里（真机反馈）：手机扫完只知道 nodeId，节点表里就先显示 `node-a1b2c3d4`
 * 这样的合成名，要等下一次 profile 交换才变成 `mac-mini`；中间那段时间用户会以为
 * 连错了设备。二维码是配对时**唯一**的"我是谁"通道，昵称只占 15–25 字节。
 *
 * 与 [pairingPayloadForAddresses] 的分工：那个给"复制链接"（文本不限长，列**全部**候选），
 * 这个给二维码（受 [QR_PAYLOAD_MAX_BYTES] 约束，只放优先级最高的几条）。
 * 一个可用候选都没有时返回 null。
 */
public fun qrPairingPayloadForAddresses(
    nodeId: String,
    addresses: List<String>,
    port: Int,
    budgetBytes: Int = QR_PAYLOAD_MAX_BYTES,
    tlsPin: String? = null,
    nick: String? = null,
    luciola: LuciolaQrInfo? = null,
): String? {
    val candidates = qrCandidatesFor(
        addresses, port, nodeId, budgetBytes, tlsPin = tlsPin, nick = nick, luciola = luciola,
    )
    if (candidates.isEmpty()) return null
    // tlsPin **必须带**（task-86 真机）：公网长连接的拨号目标要求 `pin != null`（fail-closed），
    // 二维码是主机→手机单向的 ⇒ 不在这里交出主机 pin，手机就永远建不出公网目标，
    // 只剩局域网明文那条 ⇒ 手机在蜂窝上一直 connect-timeout（真机 9 次全砸在 192.168.x）。
    return multiCandidatePairingPayload(
        nodeId, nick = nick, tlsPin = tlsPin, candidates = candidates, luciola = luciola,
    )
}

/**
 * 同前缀折叠：IPv4 按 /24、IPv6 按 /64（IPv6 用**字节前缀**而不是字符串前缀，`fd7a::1` 与
 * `fd7a:0:0:0:...` 才算同一个 /64）。认不出前缀的（主机名）按原串去重。
 */
internal fun collapseSamePrefix(candidates: List<PeerCandidate>): List<PeerCandidate> {
    val seen = HashSet<String>()
    val out = mutableListOf<PeerCandidate>()
    for (c in candidates) {
        if (seen.add(prefixKeyOf(c.host) ?: c.host)) out += c
    }
    return out
}

private fun prefixKeyOf(host: String): String? {
    if (IpAddressUtils.isIpv4(host)) return "v4/24:" + host.substringBeforeLast('.')
    val bytes = IpAddressUtils.parseIpv6Bytes(host) ?: return null
    val head = StringBuilder("v6/64:")
    for (i in 0 until 8) {
        val v = bytes[i].toInt() and 0xFF
        head.append(v.toString(16).padStart(2, '0'))
    }
    return head.toString()
}

/**
 * 按类型轮转排序：`lan_v4 → public_v6 → lan_v6 → domain → other` 各取一条，再取第二条……
 *
 * 为什么不是"按类型分组顺序排完再排下一组"：二维码预算只够 2 条时，必须**同时**留下
 * "内网地址"与"公网地址"（前者给同网段的手机，后者给跨网络的手机），分组排法会把第二类整个挤掉。
 * 同一类型内：私网 IPv4 优先（更可能是真的局域网），其余保持调用方给的原序。
 */
internal fun roundRobinByType(candidates: List<PeerCandidate>): List<PeerCandidate> {
    // **公网 v6 排在最前**（task-86）：二维码容量只有 213 字节，带上 pin 之后**只装得下一条**
    // 地址 —— 同一局域网内的对端本来就有 mDNS 自动发现兜底，跨网那条（公网 v6）才是二维码
    // 唯一不可替代的价值。排在后面会被预算裁掉，表现就是「手机在蜂窝上永远连不上」。
    val order = listOf(
        PeerCandidateTypes.PUBLIC_V6,
        PeerCandidateTypes.LAN_V4,
        PeerCandidateTypes.LAN_V6,
        // 隧道排在预算的最末：装不下时它是第一个被裁掉的（同 LAN 的设备本来也不需要它）。
        PeerCandidateTypes.TUNNEL_V4,
        PeerCandidateTypes.DOMAIN,
        PeerCandidateTypes.OTHER,
    )
    val buckets = order.map { t ->
        candidates.filter { it.type == t }.sortedBy { if (isPrivateIpv4(it.host)) 0 else 1 }
    }
    val out = mutableListOf<PeerCandidate>()
    var index = 0
    while (out.size < candidates.size) {
        var added = false
        for (b in buckets) {
            if (index < b.size) {
                out += b[index]
                added = true
            }
        }
        if (!added) break
        index++
    }
    return out
}

/** 私网 IPv4（RFC 1918）：10/8、172.16/12、192.168/16。 */
internal fun isPrivateIpv4(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4) return false
    val a = parts[0].toIntOrNull() ?: return false
    val b = parts[1].toIntOrNull() ?: return false
    return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
}
/**
 * 解析后的二维码类型。
 *
 * 可以是直接单播节点（host + port，含 IPv4 与 IPv6，可选携带 TLS pin 与多候选池），
 * 也可以是公网中继端点（url + token + nodeId + nick）。
 */
sealed interface ParsedQr {
    data class LanPeer(
        val host: String,
        val port: Int,
        val tlsPin: String? = null,
        val candidates: List<PeerCandidate> = emptyList(),
        val nodeId: String? = null,
        val nick: String? = null,
        /** 同一个码里的 luciola 配对信息;老端/未开启时是 null。 */
        val luciola: LuciolaQrInfo? = null,
    ) : ParsedQr

    data class RemoteRelay(
        val url: String,
        val token: String = "",
        val nodeId: String? = null,
        val nick: String? = null,
        val lan: String? = null,
    ) : ParsedQr
}

/**
 * 统一二维码解析入口。
 *
 * [myAddresses] = 本机地址表（`NetworkSnapshot.localAddresses`）。它只影响**多候选**码里
 * "先打哪个"的选择：同网段优先 lan、跨网选 public_v6（规则见 [choosePeerCandidate]）。
 * 单地址码（`relay://host:port` / 裸 `host:port`，也就是手填与手贴那条路）**不看它、行为不变**。
 */
internal fun parseQr(content: String, myAddresses: List<String> = emptyList()): ParsedQr? {
    val trimmed = content.trim()
    if (trimmed.isEmpty()) return null

    // 1. JSON 格式: {"type":"relay"|"remote"|"direct", "url":"...", "token":"...", "addrs":[...], ...}
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
        try {
            val json = Json.parseToJsonElement(trimmed).jsonObject
            // 键名读法：**短键优先、长键回退**（task-86）—— 新端发单字母键省字节，
            // 老端发的长键（type/nodeId/pin/port/addrs/ip）必须照旧能读。
            val type = json["t"]?.jsonPrimitive?.contentOrNull ?: json["type"]?.jsonPrimitive?.contentOrNull

            // 1.1 多候选直连格式。两代并存：
            //   老（没有 `v` 键）: {"t":"direct","n":"<uuid>","p":"<64hex>","o":17878,"a":[{"t":"...","i":"..."}]}
            //   新（`v` = 2）    : {"v":2,"n":"<b64>","p":"<b64>","a":["6:b64","4:b64"]}
            // 判据是 **`v` 在不在**，不是"a 里是字符串还是对象" —— 版本号是唯一不会误判的
            // 那一个键（见 PAIRING_PAYLOAD_VERSION 的 KDoc）。
            val addrsArray = (json["a"] ?: json["addrs"])?.jsonArray
            if (addrsArray != null && addrsArray.isNotEmpty()) {
                val isCompact = json["v"]?.jsonPrimitive?.intOrNull == PAIRING_PAYLOAD_VERSION
                // 信封上的 port 是条目的**缺省值**（生成端把共用端口提上来省字节；等于协议缺省
                // 端口时连信封都不写）。老载荷每条自带 port，这里同样读得到。
                val defaultPort = (json["o"] ?: json["port"])?.jsonPrimitive?.intOrNull ?: Protocol.DEFAULT_UDP_PORT
                val candidates = mutableListOf<PeerCandidate>()
                if (isCompact) {
                    for (item in addrsArray) {
                        val encoded = item.jsonPrimitive.contentOrNull ?: continue
                        decodeCandidate(encoded, defaultPort)?.let { candidates.add(it) }
                    }
                } else {
                    for (item in addrsArray) {
                        val obj = item as? JsonObject ?: continue
                        val host = obj["i"]?.jsonPrimitive?.contentOrNull
                            ?: obj["ip"]?.jsonPrimitive?.contentOrNull
                            ?: obj["host"]?.jsonPrimitive?.contentOrNull
                        val port = (obj["o"] ?: obj["port"])?.jsonPrimitive?.intOrNull ?: defaultPort
                        if (host.isNullOrBlank() || port !in 1..65535) continue
                        val clean = IpAddressUtils.unbracket(host.trim())
                        // 类型**以地址本身为准**，不信任载荷里那个标签：旧版桌面端写的是
                        // "lan"/"ipv6"（与手机找的 lan_v4/public_v6 对不上），若照抄标签，
                        // 选择规则会整条失效。判不出类型（link-local/回环/多播）的直接丢掉 ——
                        // 那种地址本来就不该出现在配对码里。
                        val type = peerCandidateTypeOf(clean) ?: continue
                        candidates.add(PeerCandidate(type, clean, port))
                    }
                }
                if (candidates.isNotEmpty()) {
                    val pin = if (isCompact) {
                        (json["p"] ?: json["pin"] ?: json["tlsPin"])
                            ?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
                            ?.let { decodePin(it) }
                    } else {
                        // 老载荷的 pin 是 hex：可能带 `sha256:` 前缀（更老）或不带（上一版省字节）。
                        // 统一补回前缀 ⇒ 下游 TlsPin.parse 与落库一字不变。
                        (json["p"] ?: json["pin"] ?: json["tlsPin"])
                            ?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
                            ?.let { if (it.startsWith("sha256:")) it else "sha256:" + it }
                    }
                    val nodeIdRaw = (json["n"] ?: json["nodeId"] ?: json["id"])
                        ?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
                    val nodeId = nodeIdRaw?.let { if (isCompact) decodeNodeId(it) else it }
                    val nick = (json["k"] ?: json["nick"])?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
                    // luciola 段是**可选**的:解析失败只丢这一段,整张码照旧可用
                    // （老主机不会发它;发坏了也不该让用户看到"二维码内容不认识"）。
                    val luciola = json["l"]?.jsonPrimitive?.contentOrNull?.let { decodeLuciolaQrInfo(it) }
                    val best = choosePeerCandidate(candidates, myAddresses) ?: candidates.first()
                    return ParsedQr.LanPeer(
                        host = best.host,
                        port = best.port,
                        tlsPin = pin,
                        candidates = candidates,
                        nodeId = nodeId,
                        nick = nick,
                        luciola = luciola,
                    )
                }
            }

            // 1.2 中继远程格式
            val url = json["url"]?.jsonPrimitive?.contentOrNull ?: json["relay"]?.jsonPrimitive?.contentOrNull
            if (url != null && url.isNotBlank() && (type == null || type == "relay" || type == "remote")) {
                val token = json["token"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                val nodeId = (json["nodeId"] ?: json["id"])?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
                val nick = json["nick"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
                val lan = json["lan"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
                return ParsedQr.RemoteRelay(url.trim(), token, nodeId, nick, lan)
            }
        } catch (_: Throwable) {
            // Not JSON
        }
    }

    // 2. URI 格式: relay://relay?... 或 relay://remote?... 或 relay-pair://...
    if (trimmed.startsWith("relay://relay?") || trimmed.startsWith("relay://remote?") || trimmed.startsWith("relay-pair://")) {
        val query = trimmed.substringAfter("?")
        val params = query.split("&").mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq > 0) part.substring(0, eq).trim() to part.substring(eq + 1).trim() else null
        }.toMap()
        val url = params["url"] ?: params["relay"]
        if (!url.isNullOrBlank()) {
            val token = params["token"].orEmpty()
            val nodeId = params["nodeId"] ?: params["id"]
            val nick = params["nick"]
            val lan = params["lan"]
            return ParsedQr.RemoteRelay(
                url = url,
                token = token,
                nodeId = nodeId?.ifEmpty { null },
                nick = nick?.ifEmpty { null },
                lan = lan?.ifEmpty { null },
            )
        }
    }

    // 3. 直接节点 host:port 或 relay://host:port（含带 ?pin=... 查询参数）
    val lan = parseQrPayload(trimmed)
    if (lan != null) {
        val pin = if (trimmed.contains("?")) {
            val query = trimmed.substringAfter("?")
            query.split("&").firstNotNullOfOrNull { part ->
                val eq = part.indexOf('=')
                if (eq > 0) {
                    val k = part.substring(0, eq).trim()
                    val v = part.substring(eq + 1).trim()
                    if (k == "pin" || k == "tlsPin") v else null
                } else null
            }
        } else null

        return ParsedQr.LanPeer(lan.first, lan.second, tlsPin = pin)
    }

    return null
}

/**
 * 二维码配对载荷的解析（支持 IPv4 与带方括号的 IPv6 地址，保持完全向后兼容）。
 */
internal fun parseQrPayload(content: String): Pair<String, Int>? {
    val trimmed = content.trim()
    if (trimmed.startsWith("{") || trimmed.startsWith("relay://relay?") || trimmed.startsWith("relay://remote?") || trimmed.startsWith("relay-pair://")) {
        return null
    }
    val withoutScheme = if (trimmed.startsWith("relay://")) {
        trimmed.removePrefix("relay://")
    } else {
        trimmed
    }

    // 剔除查询参数
    val base = withoutScheme.substringBefore('?').trim()

    // 1. 标准方括号 IPv6: [240e:...]:17878
    if (base.startsWith("[")) {
        val closeBracket = base.indexOf(']')
        if (closeBracket <= 1) return null
        val host = base.substring(1, closeBracket).trim()
        val rest = base.substring(closeBracket + 1).trim()
        if (!rest.startsWith(":")) return null
        val port = rest.removePrefix(":").trim().toIntOrNull() ?: return null
        if (port !in 1..65535) return null

        // 校验 IPv6 有效性，严禁 Link-Local (fe80::) 伪装
        if (!IpAddressUtils.isIpv6(host)) return null
        if (IpAddressUtils.getIpv6Scope(host) == Ipv6Scope.LINK_LOCAL) return null

        return host to port
    }

    // 2. IPv4 或 主机名: host:port
    val parts = base.split(":")
    if (parts.size != 2) return null
    val host = parts[0].trim()
    val port = parts[1].trim().toIntOrNull() ?: return null
    if (host.isEmpty() || port !in 1..65535) return null
    if (host.contains(':')) return null
    return host to port
}

/**
 * 二维码配对载荷的生成 —— [parseQrPayload] 的逆。
 * 支持 IPv4 与公网/内网 IPv6。
 */
fun pairingPayload(host: String, port: Int): String? {
    val trimmed = host.trim()
    if (trimmed.isEmpty() || port !in 1..65535) return null

    val cleanHost = IpAddressUtils.unbracket(trimmed)
    if (cleanHost.contains(':')) {
        // IPv6 必须有效且非链路本地
        if (!IpAddressUtils.isIpv6(cleanHost)) return null
        if (IpAddressUtils.getIpv6Scope(cleanHost) == Ipv6Scope.LINK_LOCAL) return null
        return "relay://[$cleanHost]:$port"
    }

    return "relay://$cleanHost:$port"
}

/**
 * 生成多候选智能配对载荷（JSON）。
 *
 * 三条不变量（生成端与解析端都从这里取，别再各写一份）：
 *  - `type` **由地址本身重算**（[peerCandidateTypeOf]），不照抄调用方给的标签 —— 桌面端曾经
 *    写 "lan"/"ipv6"，与手机找的 "lan_v4"/"public_v6" 对不上，于是"同网段优先"从没生效；
 *  - IPv6 一律**方括号**（[IpAddressUtils.bracketIfIpv6]）：`2408:...` 与 `2408:...:17878` 在没有
 *    括号时无法与端口区分，人也读不出来；
 *  - 判不出类型的候选（link-local / 回环 / 多播 / 非法串）与端口越界的条目**直接丢掉**：
 *    配对码里的每个地址都会被真的连过去。
 *
 * 一条都不剩时给出 `addrs: []`（不是编一个地址）—— 要不要画码由调用方
 * [pairingPayloadForAddresses] 决定。
 */
public fun multiCandidatePairingPayload(
    nodeId: String,
    nick: String? = null,
    tlsPin: String? = null,
    candidates: List<PeerCandidate>,
    /**
     * luciola 配对段（可选）。**同一个码走双栈**：老端忽略这个未知键，
     * 新端读到它就能在同一个二维码里拿到 Ed25519 公钥与配对密钥。
     */
    luciola: LuciolaQrInfo? = null,
): String {
    val usable = candidates.mapNotNull { c ->
        val host = IpAddressUtils.unbracket(c.host).trim()
        // 类型仍然**由地址重算**（不信任调用方标签）；这里只丢端口越界与认不出类型的。
        val type = peerCandidateTypeOf(host) ?: return@mapNotNull null
        if (c.port !in 1..65535) return@mapNotNull null
        PeerCandidate(type, host, c.port)
    }
    // 共用端口提到信封上；若它就等于协议缺省端口则**连信封都不写**
    // （解析端缺省回退到 Protocol.DEFAULT_UDP_PORT，与不写完全同义）。
    val sharedPort = usable.map { it.port }.distinct().singleOrNull()
    val implicitPort = if (sharedPort == Protocol.DEFAULT_UDP_PORT) null else sharedPort
    return buildJsonObject {
        // ---- 键名与编码全部为**扫码密度**服务（见 QR_PAYLOAD_MAX_BYTES 的长 KDoc）----
        // v = 载荷版本。2 起：nodeId / pin / 地址都是 base64url 的**二进制**形态。
        // 缺省（没有 v）= 老的长键载荷，解析端两套都读（见 parseQr）。
        put("v", PAIRING_PAYLOAD_VERSION)
        // nodeId 是 UUIDv4 时压成 16 字节 = 22 个字符（原 36）；不是合法 UUID 就原样写，
        // 宁可多 14 字节也不能把一个合法身份压坏。
        put("n", encodeNodeId(nodeId))
        if (!nick.isNullOrBlank()) put("k", nick)
        // pin 与 luciola 段**互斥**（预算 153 字节，装不下两者）：
        //  - 没开 luciola ⇒ 照旧写 pin（legacy K2 公网长连接的信任根）；
        //  - 开了 luciola ⇒ 写 l、不写 p：这个节点不跑 K2 明文/TLS 通道，pin 在这条
        //    链路上没有任何用途；而手机拿到的 pk + secret 才是新信道的信任根。
        // 老端扫到没有 p 的码仍能按地址连（局域网），只是建不出公网 K2 目标 —— 这在
        // 「开了实验 luciola 信道」的节点上本来就是预期（它已不跑 legacy 发现）。
        if (luciola == null) {
            // pin：64 位 hex -> 32 字节 base64url（43 字符），去掉 sha256: 前缀与 hex 两重开销。
            tlsPin?.let { encodePin(it) }?.let { put("p", it) }
        } else {
            // luciola 段："<公钥 b64url>:<配对密钥 b64url>"。端口不写（= 候选自带的端口，
            // 同号不同协议）；写坏就不写 —— 一张能扫的旧码好过一张新端也读不出的坏码。
            encodeLuciolaQrInfo(luciola)?.let { put("l", it) }
        }
        if (implicitPort != null) put("o", implicitPort)
        put("a", buildJsonArray {
            for (c in usable) {
                // 每条候选**就是一个字符串**：<类型>:<base64url(地址字节)>[:<port>]。
                // 地址字节把 IPv6 的 40 字符压成 22；类型前缀让 lan_v4 / lan_v6 / public_v6
                // 三档在码里仍可读（排障时人是会看这张码的）。
                add(JsonPrimitive(encodeCandidate(c, sharedPort)))
            }
        })
    }.toString()
}

/**
 * 载荷版本。**出现即代表短键 + base64url 形态**；没有这个键 = 老的长键载荷。
 *
 * 为什么不用"能解出 a 就是新版"来区分：那样老载荷里一个恰好叫 a 的键就会串味。
 * 版本号是唯一不会误判的判据，代价只有 6 个字节。
 */
internal const val PAIRING_PAYLOAD_VERSION: Int = 2

/**
 * nodeId 的短形态：UUIDv4 -> base64url(16 字节)（36 -> 22 字符）。
 *
 * 不是合法 UUID 时**原样返回** —— 身份串的格式不是本函数能假设的（浏览器端与手工造的
 * 节点 id 都可能不是 UUID），压坏了就是对端认不出来。
 */
internal fun encodeNodeId(nodeId: String): String {
    val trimmed = nodeId.trim()
    if (!isUuidV4(trimmed)) return trimmed
    val hex = trimmed.replace("-", "")
    val bytes = ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    return base64UrlEncode(bytes)
}

/** encodeNodeId 的逆；不是 16 字节的 base64 串就当成原样 nodeId 返回。 */
internal fun decodeNodeId(raw: String): String {
    val bytes = base64UrlDecode(raw) ?: return raw
    if (bytes.size != 16) return raw
    return buildString(36) {
        for (i in 0 until 16) {
            if (i == 4 || i == 6 || i == 8 || i == 10) append('-')
            val v = bytes[i].toInt() and 0xFF
            append(HEX_LOWER[v ushr 4])
            append(HEX_LOWER[v and 0x0F])
        }
    }
}

/**
 * pin 的短形态：sha256:<64 hex> -> base64url(32 字节)（71 -> 43 字符）。
 *
 * 返回 null = 这条 pin **本来就不可信**（TlsPin.parse 不收）。**不抛错、不截断**：
 * 少一条 pin 是降级，编一条 pin 是漏洞。
 */
internal fun encodePin(tlsPin: String): String? {
    val parsed = TlsPin.parse(tlsPin) ?: return null
    val hex = parsed.wire.removePrefix(TlsPin.PREFIX)
    if (hex.length != TlsPin.HEX_LENGTH) return null
    val bytes = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    return base64UrlEncode(bytes)
}

/** encodePin 的逆：32 字节 base64url -> 规范 wire 形态（带 sha256: 前缀）。 */
internal fun decodePin(raw: String): String? {
    val bytes = base64UrlDecode(raw) ?: return null
    if (bytes.size != 32) return null
    return TlsPin.PREFIX + buildString(64) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(HEX_LOWER[v ushr 4])
            append(HEX_LOWER[v and 0x0F])
        }
    }
}

/**
 * 一条候选的短形态：<类型码>:<base64url(地址字节)>[:<port>]。
 *
 * 类型码：4 = lan_v4，6 = lan_v6，p = public_v6，d = domain，o = other
 * （public_v6 用 p 而不是 6，否则与 lan_v6 撞车）。主机名没有字节形态，原样写 ——
 * 它们本来就短，压不动。
 */
internal fun encodeCandidate(candidate: PeerCandidate, sharedPort: Int?): String {
    val body = candidateBytes(candidate.host)?.let { base64UrlEncode(it) } ?: candidate.host
    val suffix = if (sharedPort != null && candidate.port == sharedPort) "" else ":" + candidate.port
    return typeCode(candidate.type) + ":" + body + suffix
}

/** encodeCandidate 的逆；认不出的形态返回 null（调用方丢掉这一条，不编地址）。 */
internal fun decodeCandidate(raw: String, defaultPort: Int): PeerCandidate? {
    val code = raw.substringBefore(':', missingDelimiterValue = "")
    if (code.length != 1) return null
    val rest = raw.substring(code.length + 1)
    val declaredType = typeOfCode(code) ?: return null
    // 端口只可能是**尾部**那一段，且必须是纯数字 —— 主机名里出现冒号在合法载荷里不会发生
    // （IPv6 已经进了二进制形态），所以按最后一个冒号切分是安全的。
    val lastColon = rest.lastIndexOf(':')
    val tail = if (lastColon >= 0) rest.substring(lastColon + 1) else ""
    val hasPort = lastColon >= 0 && tail.isNotEmpty() && tail.all { it.isDigit() }
    val bodyRaw = if (hasPort) rest.substring(0, lastColon) else rest
    val port = if (hasPort) tail.toIntOrNull() ?: return null else defaultPort
    if (port !in 1..65535) return null
    val host = base64UrlDecode(bodyRaw)?.let { ipBytesToHost(it) } ?: bodyRaw
    if (host.isBlank()) return null
    // 类型以**地址本身**为准（与老载荷同一条纪律）：载荷里的标签只是给人看的。
    val verified = peerCandidateTypeOf(host) ?: return null
    return PeerCandidate(if (verified.isEmpty()) declaredType else verified, host, port)
}

private const val HEX_LOWER = "0123456789abcdef"

private fun typeCode(type: String): String = when (type) {
    PeerCandidateTypes.LAN_V4 -> "4"
    PeerCandidateTypes.LAN_V6 -> "6"
    PeerCandidateTypes.PUBLIC_V6 -> "p"
    PeerCandidateTypes.DOMAIN -> "d"
    else -> "o"
}

private fun typeOfCode(code: String): String? = when (code) {
    "4" -> PeerCandidateTypes.LAN_V4
    "6" -> PeerCandidateTypes.LAN_V6
    "p" -> PeerCandidateTypes.PUBLIC_V6
    "d" -> PeerCandidateTypes.DOMAIN
    "o" -> PeerCandidateTypes.OTHER
    else -> null
}

/** 地址 -> 字节（IPv4 4 字节 / IPv6 16 字节）；不是字面量地址时返回 null。 */
internal fun candidateBytes(host: String): ByteArray? {
    val clean = IpAddressUtils.unbracket(host).trim().substringBefore('%')
    if (IpAddressUtils.isIpv4(clean)) {
        val parts = clean.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (i in 0 until 4) out[i] = parts[i].toIntOrNull()?.toByte() ?: return null
        return out
    }
    return IpAddressUtils.parseIpv6Bytes(clean)
}

/**
 * [candidateBytes] 的逆。
 *
 * IPv6 按 **RFC 5952** 压成最简形态（小写、去前导零、最长的一段连续零压成 `::`）。
 * 这不是"省几个字节"的洁癖：`fd00:1234:5678:0000:0000:0000:0000:0099` 与
 * `fd00:1234:5678::99` 是同一个地址，而**载荷里那个字符串会被原样落进节点表、
 * 出现在日志与界面上** —— 同一个地址两种写法，人以为连了两个地方，比对也永远不相等。
 * 所以解码出来必须是**canonical** 的那一种。
 */
internal fun ipBytesToHost(bytes: ByteArray): String? = when (bytes.size) {
    4 -> bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
    16 -> {
        val words = IntArray(8) { i ->
            ((bytes[i * 2].toInt() and 0xFF) shl 8) or (bytes[i * 2 + 1].toInt() and 0xFF)
        }
        // 找最长的一段连续零（长度必须 >= 2 才值得压，RFC 5952 §4.2.2）。
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < 8) {
            if (words[i] != 0) {
                i++
                continue
            }
            var j = i
            while (j < 8 && words[j] == 0) j++
            if (j - i > bestLen) {
                bestStart = i
                bestLen = j - i
            }
            i = j
        }
        if (bestLen < 2) bestStart = -1
        buildString {
            var k = 0
            while (k < 8) {
                if (k == bestStart) {
                    append("::")
                    k += bestLen
                    continue
                }
                if (isNotEmpty() && last() != ':') append(':')
                append(words[k].toString(16))
                k++
            }
        }
    }
    else -> null
}

/** base64url（无填充）。Kotlin 标准库的多平台实现，JVM/Android/iOS 同一份。 */
internal fun base64UrlEncode(bytes: ByteArray): String =
    Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)

/** base64UrlEncode 的逆；非法输入返回 null（**不抛**：一个坏字符不该让整张码作废）。 */
internal fun base64UrlDecode(text: String): ByteArray? =
    runCatching { Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(text) }.getOrNull()

/**
 * 生成公网中继配对载荷（JSON）。
 */
internal fun remotePairingPayload(
    url: String,
    token: String = "",
    nodeId: String? = null,
    nick: String? = null,
): String = buildJsonObject {
    put("type", "relay")
    put("url", url.trim())
    if (token.isNotBlank()) put("token", token.trim())
    nodeId?.takeIf { it.isNotBlank() }?.let { put("nodeId", it.trim()) }
    nick?.takeIf { it.isNotBlank() }?.let { put("nick", it.trim()) }
}.toString()
