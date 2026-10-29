# Security Policy

## 1. Threat Model & Trust Boundaries

Tidings (`捎话`) is a decentralized peer-to-peer messaging, file-transfer, screen-sharing and
agent protocol. It runs on a **local network** *and* **directly across the public internet**
(public IPv6), with two distinct transport-security tiers. There is still no centralized
authentication authority, directory server, or cloud signaling gateway.

### 1.1 Two transport tiers, one judgement

| Tier | When | Protection |
| :--- | :--- | :--- |
| **Plaintext** | peer is on the **LAN** | local network only — plaintext never leaves the LAN |
| **TLS + mutual pin** | peer is on the **public internet** | TLS with a pinned peer key (SPKI), **fail-closed** |

The LAN/public judgement has exactly **one** implementation:
`IpAddressUtils.isLanAddress(peer, localAddresses)` — private/loopback (RFC1918, `127/8`)
∨ link-local (`fe80::/10`) ∨ ULA (`fc00::/7`) ∨ **the peer IPv6 shares a /64 with one of our own
addresses**. It is shared by dialing, dial diagnostics, link acceptance and the post-change
re-check; see `docs/public-transport-security.md` §11 for the four call sites and why they must
not drift apart.

**Key fact: an IPv6 GUA is not the same thing as "reachable from the public internet".**
A typical Chinese home broadband LAN hands out global unicast addresses (measured on a real
device: `2001:db8:2020:cb44::/64`) — classified by address alone they "are public", yet they
are the same local segment as us. Judging by `isPrivateOrLocal` alone silently switched K1 off
in exactly those networks. The other half is **not** relaxed: a peer that is neither private nor
on our own /64 still **must** use TLS + mutual pin.

What enforces it (`core/.../transport/Link.kt`):
- the dialer only ever produces a **plaintext** target for an `isLanAddress` peer, otherwise only TLS;
- acceptance (`linkDenyDiagnostic`) answers `not-lan` to any plaintext link from a non-LAN peer (fail-closed);
- after a peer address change, the re-check (`enforcePlaintextPolicy`) only drops a connection that
  declares itself plaintext **and** is provably no longer on the LAN (fail-safe — never drops a TLS one).

**iOS has no TLS link transport yet**, so public long connections are refused by policy rather
than downgraded to plaintext (`LinkTransportFactory.ios.kt`; `docs/spike-kmp-tls.md`).

### 1.1.1 Frame budgets are part of the attack surface

The largest thing a peer can make us accept in one frame is a budget, and budgets are chosen,
not inherited. Three of them are worth stating explicitly:

| Frame | Budget | Why |
| :--- | :--- | :--- |
| `msg` / `group-text` text | UDP 800 B, **TCP 32 KiB** | 4096 B was a UDP-era number; agent replies routinely exceed it, and a rejected frame is silently lost. 32 KiB leaves room for the 24 KiB `reasoning` field inside one 64 KiB frame. |
| `agent-event` body | TCP 64 KiB | code blocks, diffs, deep reasoning |
| one-to-one `kind:"text"` | 4096 B (unchanged) | hand-typed text; no reason to widen the surface |

A sender that does not check the receiver's budget does not get an error — the receiver drops the
whole frame (`bad-payload`). So `NodeOutbound.clampGroupText` / `clampGroupReasoning` exist to make
"we never emit a frame that is guaranteed to be rejected" an invariant rather than a hope.

**Do not fold the two budgets into one.** The numbers above bound an *application envelope* (how long
one message may be). A data-plane chunk frame carries raw file bytes and caps its payload at
**256 KiB** per chunk — a different quantity with a different purpose; neither number bounds or
widens the other (`PROTOCOL-EXTENSION.md` §15.3).

### 1.1.2 A third surface: the always-encrypted data plane

Besides the two tiers above there is a data plane that carries file bytes and screen frames
(`luciolaPort` / `screenPort` in the profile, caps `fch1` / `scr1`). It is encrypted **even on the
LAN**, so it is not governed by the LAN/public judgement at all, and its trust root is **separate**
from the TLS pin (see the last section of this file).

- It is **on by default**. The cost is a visibility one: a peer that has not upgraded becomes
  mutually *invisible* — legacy discovery stops, so there is no node to send to. That is not the same
  failure as a message that is silently dropped; do not document it as one.
- The only rollback is turning the switch off and **restarting the node**. There is no per-peer
  downgrade, and adding one would turn a security boundary into a toggle.

### 1.2 Trust roots: how a peer key becomes trusted

- A **pin** is `sha256:` + SHA-256 of the peer's SubjectPublicKeyInfo (DER), 64 hex chars.
  `TlsPin.parse` is strict; a malformed pin is *dropped* rather than failing the pairing.
- The pairing QR is **one-way**: it carries the host pin to the phone. When the host has never
  seen the phone's key it falls back to **TOFU** (trust-on-first-use), persists it to
  `stores/peer-pins.json`, and records its **source**. Once the phone also carries its pin in
  the pairing payload, new×new pairings are `pairing`-sourced and no longer TOFU.
- **TOFU is a documented downgrade path** (it has a first-use race: whoever answers first gets
  pinned). It must therefore stay diagnosable: `pinSource` (`pairing` / `tofu` / `unknown`) is
  reported by the host (`/v1/peers`) and by the phone (`files/relay/diag/link.json`). Pin files
  written before this key existed read back as `unknown` — never reported as `pairing`.
- **Verification is fail-closed** (`peerPinAccepted`): no peer certificate, no expected pin, or a
  mismatching pin all mean *reject*. A TLS failure never falls back to plaintext.
- **No forward secrecy, no rotation UI yet**: the pin is the SPKI of a long-lived self-signed
  identity. The private key stays stable and the certificate is re-issued automatically when it
  expires; key rotation / user-facing expiry prompts are still planned (P4).

### 1.3 Pairing from outside the app requires explicit confirmation

`relay://` and `relay-pair://` deep links (`exported=true` + BROWSABLE) and the debug
`app.relay.TEST_INJECT_QR` broadcast are **outside the app's own UI**. They no longer pair on
arrival: the payload is only *registered*, the user sees a confirmation dialog stating which
node it is and — only when the payload really carries one — that its key will be pinned.
**Declining has zero side effects** (no node, no candidate pool, no pin). Storing an externally
supplied key as a trust root is an irreversible trust decision, so it is treated as a
destructive action: conservative, never implicit.

### 1.4 Agent sandbox & grants

- External or local agent execution relies on host approvals; unapproved actions and unauthorized
  control frames are blocked at the capability layer (`CAP_AGENT_QUEUE`).
- Authorization is fine-grained per workspace and per access level (`agr1`). The host filters
  before delivery, so **silence means unauthorized** (no data, no request flow, no guessing).
- A button's wording caps what it can grant: "collaborate" is at most read/write, only
  "my devices" reaches owner.
- Workspace→group binding matches on three keys; an ambiguous match is dropped (fail-closed)
  rather than delivered to a guessed group.

### Known Protocol Scope & Non-Goals

- **No centralized routing / cloud signaling.** Public reachability uses direct IPv6; we do no
  NAT traversal or hole punching. The peer's router must allow inbound IPv6 — that is the user's
  network to configure, not ours.
- **Cloud push is designed, not implemented** (`docs/cloud-push-design.md`).
- **Cross-network screen sharing is not implemented** — screen assistance still requires the same
  LAN. Cross-network *file* transfer is implemented (second TCP connection, reusing `file-ctl` /
  raw stream / resume); the end-to-end send on a real device is still pending acceptance.
- **Opening an attachment by tapping it is not implemented** (we carry `attachmentId` but no
  `fileId`/path yet).
- **No forward secrecy / asymmetric message signing**; key rotation is planned (P4).

---

## 2. Supported Versions

| Version | Supported |
| :--- | :--- |
| `main` branch (latest commit) | :white_check_mark: |
| Historical releases | :x: |

---

## 3. Reporting a Vulnerability

We take the security of Tidings seriously. If you discover a security vulnerability, please do **NOT** open a public issue.

Instead, please report the vulnerability privately:
1. **GitHub Private Vulnerability Reporting**: Use the "Report a vulnerability" button in the **Security** tab of the GitHub repository.
2. **Email Disclosure**: If GitHub Private Vulnerability Reporting is unavailable, send an encrypted email with detailed reproduction steps to the repository maintainers.

### What to Include
- Detailed description of the vulnerability and attack vector.
- Minimum reproducible example (PoC script, Wireshark packet capture, or test case).
- Potential impact on local network nodes (e.g. DoS, path traversal, sandbox breakout, unauthorized approvals).

We will acknowledge receipt within 48 hours and work with you on validation and coordinated disclosure.


---

## 身份恢复的安全边界（2026-10-25）

- **恢复包永远口令加密**（L1 强制口令；Android L0 的 `KDF(ANDROID_ID)` 定性为防误触
  混淆——ANDROID_ID 任何应用可读，不构成安全边界，强安全由 L1 承担）。
- **身份与库不进云备份、不进 OEM 迁移通道**：`dataExtractionRules` 显式排除
  （Q12）。恢复包私钥等价物只存在于：本机沙箱、平台存活存储（钥匙串/公共 Downloads）、
  用户自己保存的口令加密文件。
- **L2 拉回的信任根**：恢复方持有旧 link 私钥，对端用 `peer-pins[旧 nodeId]` 验
  TLS 证书通过 ⇒ 密码学证明「这就是原来那台」，无需人工确认框（§5.5）。
  局域网明文档与聊天同等级（能发起拉取的攻击者本来就能嗅探同样的明文流）；
  公网档 fail-closed 不变。
- 恢复包导出/导入的失败一律 fail-closed：成套校验（manifest 与载荷咬合）、
  半截包（缺钥匙）在解码层拒收。

---

## 加密数据面（luciola）的信任根与准入

文件字节与投屏画面走的是**始终加密**的数据面（局域网里也不降明文）。它是与 §1.2 的 TLS pin **并行**的第二套信任根，两者不要互相替代：

| | TLS pin（K1/K2 长连接、跨网文件与投屏） | 加密数据面（luciola） |
|---|---|---|
| 身份 | EC P-256 自签证书；pin = SPKI DER 的 SHA-256 | Ed25519 身份；指纹 = 公钥指纹 |
| 落盘 | `stores/peer-pins.json`（`pairing` / `tofu` / `unknown` 记来源） | 地址→公钥表 + 指纹账本（同一份 JSON） |
| 回答什么问题 | 这条 TLS 连接是不是那台设备 | 该往哪个地址发、这条入站会话是不是已受信 |

**deny-by-default 是双侧的**：

- **出站**：目标地址在地址→公钥表里查不到 ⇒ **不拨**（查不到就是对端未配对）。出站方向没有「谁先连进来就信谁」这种入口。
- **入站**：握手出来的指纹不在信任账本里 ⇒ 直接断开；若本机开着配对准入，则给它一个**自证窗口**（默认 30 秒），窗口内**一帧业务数据都不进应用层**，超时断开。
- **准入三关**（传输层控制帧，`TLPAIR1`）：① 帧里公钥的指纹必须**等于**这条会话握手出来的指纹（防冒名：不能拿别人的公钥来配对）；② 帧里的配对密钥与本机二维码里那段做**常量时间**相等比较（证明确实扫过码，不是撞上来的；换成普通比较会让人按响应时间逐字节猜）；③ **落信任成功**才算通过，落盘失败按拒绝处理。任一条不过：先回拒绝帧（带原因）**再**断开 —— 顺序反了，对端只看到「超时」，而超时不可诊断。
- **pin/指纹与配对密钥的分工**：指纹回答「这是不是那台设备」（长期、可入账本、重启后仍在）；配对密钥回答「它是不是从我的二维码里拿到地址的」（一次性、**不进发现广播**、换掉即等于强制所有已扫码设备重新配对）。两者缺一不可 —— 只有指纹，首次使用存在 TOFU 竞态；只有密钥，任何拿到二维码的人都能冒充。
- **控制帧不进 `Messenger`**：它在传输层被分流。`Envelope` 解码会把它整条判成坏帧，所以它既不进消息流，也不需要任何白名单。

**信任材料的落盘位置与保护强度（三端不同，如实写在这里）**：

| 端 | 位置 | 形态与强度 |
|---|---|---|
| Android | 应用私有 `filesDir/luciola/`（`ed25519-seed.hex` + `state.json`） | 明文 hex seed + 明文 JSON；边界是应用沙箱（root / 设备备份可读），卸载即清 |
| 桌面 | 数据目录下的 `luciola/`（同样两个文件） | 同上；seed 文件尽力收成 0600，但仍是明文 |
| iOS | 钥匙串（独立 service / account，两条条目） | 由系统密钥链保护；**不做 iCloud 同步** —— 跨设备同步身份是恢复身份包那条通道的职责，这里越权会让两台设备争同一份 seed；卸载后仍在 |

所以「同一套判据、不同强度的落盘」是**事实**，不是遗漏：指纹绑定、常量时间比较、落盘失败即拒绝这三条三端共用一份实现；只有「存哪、被谁能读」因平台而异。威胁模型上这意味着：**能读应用私有目录的攻击者可以直接换掉 seed 冒充本机**，而 iOS 上同样的攻击需要先突破钥匙串。

---

## 插件注册的威胁模型（M2 · 进程外插件宿主网关）

插件是**宿主进程外的独立进程**，通过回环 HTTP 向控制面 `POST /v1/plugin/register`
入驻。注册是一道**能力门**：入驻意味着领到任务（派单路由会打到插件）、能向会话
台账回写事件与卡片、能被宿主点查进度 —— 所以注册**强制**校验 token，这不是可选项：

- **token 来源两处**（方案 §2.3）：托管模式由宿主经环境变量 `TIDINGS_PLUGIN_TOKEN`
  注入子进程；守护进程模式读宿主数据目录下 `plugins/plugin-token`（桌面端启动时
  保证存在，缺失即生成 64 位十六进制并收紧到 **0600**）或插件自身配置。两处都没有
  ⇒ 注册端点整体关闸（**503 fail-closed**），不存在「没配 token 就放行」的降级路径。
- **比较是常量时间**（`MessageDigest.isEqual`）：不让人按响应时间逐字节猜 token。
- **负路径有测试钉着**（`PluginRegistrationTest`）：无 token 401、错 token 401、
  宿主未配 token 503、无 token 注销 401 —— 短路门闸会让这四条全红。

### 这道门**诚实的边界**（不要往大了说）

- 它拦的是「本机**任意**进程不能**无 token** 自我入驻」—— 误入（扫端口的脚本、
  陌生工具的自动探测）与**其他用户**的进程进不来。能读用户数据目录的进程
  （同用户 / root）**仍然读得到 0600 的 token 文件** —— 那本来就在既有信任域里
  （与 §1.4 的控制面同一立场：回环上的进程与你有同等权限）。这道门是**能力门**
  （把「入驻」从无门槛变成需刻意配置），不是加密边界。
- 插件 IPC 服务器与控制面都只绑回环；跨机调用不经过这条路。

### 入驻之后插件能做什么、不能做什么

| | 边界 |
|---|---|
| 能 | 收到宿主派发的 prompt（`/v1/plugin/task`）；按事件契约回写思考/正文/工具/卡片事件进**本机**会话台账；被点查进度 |
| 不能 | 自己发群帧（群投递全部由宿主中介，插件碰不到传输层）；读消息库；触达任何对端节点；把 agentId 拼成 `agent:` 前缀（发送者标签唯一来源是注册时算好的 `AgentSenderId`） |

注册时的清单校验同时是攻击面收口：`pluginId` 只许目录安全字符集（它同时是数据
目录下的目录名 —— 路径穿越进不来）；`agentId` 必须过 slug 字符集与 21 字符长度预算
（超预算的 senderId 会被接收端整帧拒收，在入驻这一刻拒绝掉）；同一回环端口不许
两个插件认领（409）。卡片进台账受 `Limits.CARD_BYTES` 预算约束，与群帧预算同源。
