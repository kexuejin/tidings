# Tidings (捎话)

<p align="center">
  <b>Local-First Decentralized Peer-to-Peer Messaging & Private Agent Collaboration Platform (Dual-Mode: Near-Field LAN + Wide-Area IPv6)</b><br>
  Zero Public Servers · TLS + Mutual SPKI Pinning on Public Routes (plaintext stays on the LAN) · Breaking Physical LAN Limits · 100% Data Sovereignty on Private Devices · Multiplatform (Android / iOS / macOS / Linux / Windows)
</p>

<p align="center">
  <a href="README.md"><b>简体中文</b></a> | <a href="README_EN.md"><b>English</b></a>
</p>

<p align="center">
  <a href="#quick-start--build"><img src="https://img.shields.io/badge/Platform-Android%20%7C%20iOS%20%7C%20Desktop-blue.svg" alt="Platforms"/></a>
  <a href="#protocol--specifications"><img src="https://img.shields.io/badge/Protocol-P2P%20%2B%20PPE--1%20Phase%202.0-success.svg" alt="Protocol"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0--only-orange.svg" alt="License"/></a>
</p>

---

## Architectural Philosophy & Multi-Platform Support

**Tidings (捎话)** is a **Local-First, decentralized peer-to-peer messaging and private Agent collaboration infrastructure** built with Kotlin Multiplatform (KMP) and Compose Multiplatform:

1. **Core Runtime (`:core`)**: Pure Kotlin implementation of a peer-to-peer communication runtime (UDP rapid discovery & status synchronization, TCP authoritative large message / file block streaming, and PPE-1 Phase 2.0 Agent collaboration bus). Decoupled from UI frameworks for cross-platform reuse.
2. **Dual-Mode Communication (Near-Field + Wide-Area), with two transport-security tiers**:
   - **Near-Field (LAN)**: zero-configuration discovery via UDP broadcast and mDNS, direct peer-to-peer within milliseconds. Traffic on the LAN is **plaintext**, and plaintext never leaves the LAN.
   - **Wide-Area (public IPv6)**: native direct connection over Global Unicast IPv6, secured by **TLS with mutual SPKI pinning** and **fail-closed** — if verification fails we do not connect, and we never fall back to plaintext over the internet. The identity is an EC P-256 self-signed certificate (stable private key, certificate re-issued automatically on expiry). The QR code hands the host pin to the phone, and a composite QR carries a candidate pool (`lan_v4` / `lan_v6` / `public_v6`) that picks the best route automatically.
   - **One single definition of "LAN"**: `isLanAddress` (private ∨ shares a /64 with one of our own addresses ∨ link-local/ULA). **An IPv6 GUA is not the same thing as public reachability**: a typical Chinese home broadband LAN hands out global addresses such as `2408:...::/64`, which look public by classification yet are the same local segment. Judging by private ranges alone silently switches the LAN plaintext long connection off (see `docs/public-transport-security.md` §11).
   - **Precondition for public direct connection**: the remote router must allow inbound IPv6. This app does no NAT traversal and does not change your network settings.
3. **Mobile App (`:composeApp` / `:androidApp` / `:iosApp`)**: Built on Compose Multiplatform, delivering native UX, responsive adaptive layout, and sandboxed storage across Android and iOS devices.
4. **Desktop App (`:desktopApp`)**: Standalone Compose Desktop client sharing core business state and UI components, optimized for widescreen split-pane workflows, window management, and background tray execution.
5. **Headless Agent Node & Pluggable Drivers (`:agentNode`)**: Decouples network communication from LLM inference engines. Supports `NativeRelayDriver`, `DsBotDriver`, `OpenAiDriver`, and MCP (Model Context Protocol) tool extensions via a local loopback HTTP control plane (`127.0.0.1:8788`) and WebSocket gateway.

### Supported connection paths

Four paths — they differ only in **how the two sides find each other**; once found, the encryption rules and message semantics are identical:

| Path | Auto-discovery | QR scan needed first | Requirement |
|---|---|---|---|
| **Same LAN (IPv4)** | ✅ UDP broadcast + subnet broadcast | No | Same broadcast domain |
| **Same LAN (IPv6)** | ✅ mDNS / same `/64` | No | Same as above |
| **Public IPv6** | ❌ broadcast cannot cross subnets | **Yes** (QR carries the GUA) | Peer's router allows inbound |
| **Tailscale** | ❌ point-to-point tunnel, no broadcast domain | **Yes** (QR carries the tunnel address) | Both on the same tailnet |
| **ZeroTier** | ✅ subnet broadcast (phones included) | No | Same subnet, same ZT network |

**Why ZeroTier discovers itself like a LAN**: the desktop treats it as an ordinary NIC (macOS exposes it as `feth`, with a broadcast address) and broadcasts on it as usual; the phone receives it through a **socket bound to the wildcard address** — verified on device: a wildcard socket on Android does receive packets sent to the ZeroTier subnet broadcast address.

**After pairing, discovery matters much less**: both sides tell each other **every address they have** (including Tailscale / ZeroTier tunnel ranges) and send a unicast heartbeat to all known addresses every 30 seconds. Moving between networks (LAN ↔ cellular) therefore recovers automatically — no re-scan and no manual address entry.

**Stated honestly — the recovery window**: how fast a switch recovers depends on how fast the overlay itself reconnects (ZeroTier on mobile can take minutes after a network change, especially when the screen is off or the app is background-restricted). The peer shows as offline meanwhile, and messages queue until it returns.

**Reinstalling means scanning again**: node identity lives with the installation, so a reinstall is a brand-new device — the previous pairings and group memberships no longer point at it. What saves you there is exactly what is described above: subnet-broadcast discovery on the same network, or one QR scan.

---

## Key Features

- **Decentralized Direct P2P Connectivity**: Direct point-to-point transmission without third-party punch servers. SPKI public key hashes embedded in pairing tokens prevent MITM eavesdropping.
- **PPE-1 Reasoning & Thought Stream**: Native decoupling of `<think>` reasoning traces from formal responses, featuring millisecond latency measurement and collapsible UI cards (`ThinkingBlock`).
- **Structured Tool Call & Human-in-the-Loop (HITL) Approval**: Promotes raw JSON payloads into L1 (Read-Only) / L2 (Modification) / L3 (High-Risk) visual approval cards with diff drawers, one-click authorization, and a 300s fail-safe timeout.
- **Workspace Context Awareness & Session Segmentation**: Shows active workspace directory, path, and Git branch status. Supports switching project directories and triggering session segmentation to isolate prompts and reduce token consumption.
- **Decentralized Multi-Agent Group Collaboration**: Models local agents as virtual group members (`VirtualAgentMember`), supporting `@Agent` routing, Chief coordination, and real-time task progress broadcasting (`GroupProgress`). A workspace group can be **created first and joined later**: it does not need another device to exist yet; the agent of an existing workspace joins together with the group, and devices are added afterwards via the share code or "add member" in group info. **When an agent actually wakes up**: every message in a one-to-one chat with the host counts; in a group you either mention `@DSH` or (in a workspace group) reply to the agent's own message — chatter between other people does not trigger execution.
- **Mobile MCP Governance & Dynamic ACLs**: Inspect host MCP tools from mobile devices and toggle dynamic permission ACLs (e.g., disabling Bash execution while away from desk) without restarting host services.
- **Zero-Server Native System Notifications**: Uses offline cursor syncing and native OS notification APIs (Android Notifications / iOS UserNotifications / Desktop Tray alerts) to deliver timely task completion and approval wakeups without third-party push servers.
- **Resilient File Transfer & Resume (including cross-network)**: streaming chunked transfer with resume, sandboxed opening, system share integration, and inline image/audio preview. Cross-network transfer opens a second TCP connection that reuses the same machinery (`file-ctl` / the raw stream / resume), and public routes must use TLS with a pinned peer key.
- **Long-connection downlink (K1 / K2)**: the phone dials the host and keeps one TCP long connection for downlink push and low-latency control. LAN traffic uses plaintext (K1); public traffic uses TLS with a pinned peer key (K2) — **public routes must use TLS, and a failed pin check is refused**, never downgraded to plaintext. There is nothing for you to configure: whichever network the phone is on picks the route, and switching networks (Wi-Fi ↔ cellular ↔ airplane mode) happens automatically with no manual redial.
- **Background keep-alive and battery**: an Android foreground service (`remoteMessaging`) keeps the app reachable in the background; Doze lowers the frequency; **heartbeats are skipped while data is flowing**, with a fallback ping (dead after 45s, more than 2× the heartbeat) so a live connection is neither killed by mistake nor drained by idle chatter.
- **Strictly Ordered Offline Queue & Reliable Delivery**: Messages sent while a peer is offline are preserved in transactional local queues and automatically flushed upon handshake re-establishment.
  Queueing is **bounded** (see Known Limitations below): entries are kept long-term only for devices this node has actually met; an object that never completed a handshake gets 24 hours, and anything still undeliverable after 3 days is dropped.

---

## Project Structure

```text
.
├── core/                          # Core protocol: transport, crypto, state machine, session management (Pure KMP, no UI)
├── composeApp/                    # Shared Compose Multiplatform UI (Android / iOS / Desktop)
├── androidApp/                    # Android host application shell & screenshot regression tests
├── desktopApp/                    # Compose Desktop standalone executable entry point
├── agentNode/                     # Headless JVM library: HTTP control plane (:8788) & WebSocket gateway
├── iosApp/                        # iOS Xcode project shell (generated via XcodeGen from project.yml)
├── design/                        # Design specifications, token system, and visual baselines
├── prototype/                     # Design prototypes and interaction specifications
├── tools/                         # Automated quality gates, token ratchets, and developer tooling
├── docs/                          # Architecture records, design ledgers, and distribution guides
├── PROTOCOL-COVERAGE.md           # Base protocol compatibility and frame support matrix
├── PROTOCOL-EXTENSION.md          # PPE-1 & Phase 2.0 Agent protocol extensions specification
├── CONTRIBUTING.md                # Community contribution guidelines and quality standards
├── AGENTS.md                      # Constraints for anyone (agent or human) changing this repo
└── SECURITY.md                    # Responsible vulnerability disclosure policy
```

---

## Quick Start & Build

### Prerequisites

- **JDK 21** or later (Temurin 21 recommended)
- **Android Studio** (Latest stable release)
- **Android SDK** (API 34 / 36)
- **macOS with Xcode 15+** and [XcodeGen](https://github.com/yonaskolb/XcodeGen) (for iOS builds)

Configure your local Android SDK location in `local.properties`:

```properties
sdk.dir=/path/to/your/Android/sdk
```

> **Note**: For environments with restricted access to default Maven repositories, append `-PrelayMirror=aliyun` or set `export RELAY_MAVEN_MIRROR=aliyun` to enable the mirror.

### Build Android App

```bash
./gradlew :androidApp:assembleDebug
```

Outputs will be located at `androidApp/build/outputs/apk/debug/`.

### Run Desktop App

```bash
./gradlew :desktopApp:run
```

Package distribution binaries for current OS:

```bash
./gradlew :desktopApp:packageDistributionForCurrentOS
```

### Build iOS App

```bash
./gradlew :composeApp:compileKotlinIosArm64
cd iosApp && xcodegen generate
open Tidings.xcodeproj
```

---

## Quality Gates & Verification

Tidings enforces strict engineering standards with automated verification gates. Before opening a Pull Request, verify that the **complete** pipeline passes locally — this list matches [AGENTS.md](AGENTS.md) §1, which is the authoritative note for anyone changing code here:

```bash
# Tests (seven tasks / six modules)
./gradlew :core:jvmTest :app:jvmTest :cli:test :composeApp:jvmTest \
          :composeApp:testAndroidHostTest :agentNode:test :desktopApp:test --continue \
          --max-workers=1

# Gates (all required)
./gradlew :cjkLiteralGate :constructionTraceGate :staleCommentRefGate \
          :domainComposeFreeGate :envDependentTestGate :designSpecDrift \
          :designTokenUsageGate :designStateSlotGate :designLocaleParityGate \
          :jlinkModulesGate --continue
python3 tools/unused-imports.py --check
```

> **Always pass `--continue`**: without it the first failure stops the run, so what looks green may simply never have executed.

If you touched UI, design tokens or screenshot fixtures, also run `designScreenDiffGate`, `designIconGate`, `designConvergeGate`, `designFormPairingGate`, `designPreviewPairingGate`, `designDupFrameGate`, `designDpDebtGate` (all registered in `tools/design-contract-gate.gradle.kts`).

### Gate Highlights

- **Multiplatform Automated Tests**: Full test suite covering `:core` (JVM / iOS Native), `:composeApp`, `:desktopApp`, and `:agentNode`.
- **Design Token & Contract Ratchets (`:designTokenUsageGate`, `:designIconGate`)**: Verifies through AST parsing that UI code consumes tokens from design specifications and maintains vector icon coverage.
- **Localization Parity (`:designLocaleParityGate`)**: Guarantees complete parity across string resources (0 missing keys, 0 untranslated strings in secondary locales).
- **String Literal Boundary (`:cjkLiteralGate`)**: Forbids hardcoded Chinese literals in production code, requiring proper localization resources.
- **Code Cleanliness (`:staleCommentRefGate`, `:constructionTraceGate`, `unused-imports.py`)**: Forbids fragile line-number references in comments, temporary dev markers, and unused imports.

---

## Protocol & Specifications

Tidings uses UDP unicast + JSON envelopes for low-latency transmission, automatically switching to TCP byte streams for long payloads and file chunks. In addition there is a **phone-to-host long connection** (`lnk1`, below) that carries downlink push and cross-network direct traffic.

**Public routes must use TLS + mutual pin** (an optional `tlsPort` key, with no after-the-fact sniffing): without a pin the host refuses to carry content, and a TLS failure never falls back to plaintext. Plaintext is allowed on the LAN only, judged by the single `isLanAddress` predicate (`docs/public-transport-security.md` §11).

### Base Protocol Frame Support Matrix

| Frame Type | Codec Validation | Client Processing | Description |
|---|---|---|---|
| `entry` `alive` `exit` `presence` | Strict Validation | ✅ Full Support | Peer discovery, heartbeat, and presence tracking |
| `profile` | Strict Validation | ✅ Full Support | Peer profile update with incremental revision (`profileRev`) |
| `msg` (`text` / `group-text`) | Strict Validation | ✅ Full Support | Direct & group text messaging via unicast multicast |
| `msg` (`recall`) | Strict Validation | ✅ Full Support | Text and media recall within 5-minute window |
| `msg` (`nudge`) | Strict Validation | ✅ Full Support | Lightweight LAN nudge interaction with client rate limiting |
| `ack` | Strict Validation | ✅ Full Support | Reliable delivery acknowledgment mechanism |
| `file-ctl` | Strict Validation | ✅ Full Support | File transfer signaling (offer / accept / decline / cancel) |
| `screen` | Strict Validation | ✅ Full Support | Real-time LAN screen mirroring control and streaming |
| `group` | Strict Validation | ✅ Core Support | Group metadata snapshot & sync (`op:info` / `op:need`) |
| `link` frame family | Strict Validation | ✅ Full Support | Long-connection handshake and downlink push from phone to host (capability `lnk1`); public routes use TLS + mutual pin |
| `agent-workspace` / `agent-grant-*` / `agent-session-page` | Strict Validation | ✅ Full Support | Workspace identity (`aws1`), grant loop (`agr1`), session paging (`agp1`); only **optional** keys are added, so older peers ignore them |
| `avatar` `update` `share` | Compatibility Mode | ACK / Discard | Graceful acknowledgment to maintain backwards compatibility |

For the complete protocol specification, see [PROTOCOL-COVERAGE.md](PROTOCOL-COVERAGE.md).

### Capability declarations (caps): what we announce and what we do not

The capability bits the phone broadcasts are assembled **explicitly** by `RelaySettings.caps()` — all in that one function, not scattered; the full table lives in [PROTOCOL-COVERAGE.md](PROTOCOL-COVERAGE.md) section 4. The essentials:

- **Always announced**: `agn1` (agent session frames), `agq1` (queue and approvals), `aws1` (workspace identity), `agr1` (grant loop), `agp1` (session paging), `tbl1` (table-image text view), `att1` (agent-event attachment cards). `tw1` / `mrec1` each have a settings switch and are dropped when it is off.
- **Conditionally announced**: `rv1` / `rvs1` (screen sharing, where the platform supports it), `shr1` (a shared directory is configured), `av1` (custom avatar is enabled). `ag1` and `lnk1` are announced **only by a host that carries agents** (the phone is the dialing side and does not announce them).
- **Read-only, never announced**: `fd1` — we read the peer flag to decide whether to add `file-ctl direct` after an `offer`, and we never promise silent writes to our own storage.
- **Never announced**: `age1` (batch delivery and cursor resume are not implemented), `upd1` (LAN P2P update source, see PROTOCOL-COVERAGE.md section 3), `gr1` / `dp1` / `img1` (half-implemented, or not a real bit in the implementation).
- **Discipline**: announcing a capability we do not implement means the peer sends frames in a shape we silently swallow. Every bit therefore needs a single source of truth, pinned by the core-side ledger tests (`CapabilityDeclarationTest` / `CapabilityDeclarationSourceTest`).

### PPE-1 & Phase 2.0 Local Agent Collaboration Bus

Tidings implements local agent collaboration capabilities with 8 specialized agent control envelopes:

- **Sessions & Queues**: `agent-session`, `agent-queue`, `agent-run`
- **Security Approval & Cancellation**: `agent-approval`, `agent-cancel`, `agent-ack` (validated against `CAP_AGENT_QUEUE` capability)
- **Event Streaming & Artifacts**: `agent-event` (ordered by incremental cursor), `agent-artifact` (metadata binding and chunked stream)
- **Physical Data Isolation**: Agent envelopes bypass standard chat stores and are persisted in dedicated SQLDelight tables to prevent polluting user communication logs.

For detailed design specifications, see [PROTOCOL-EXTENSION.md](PROTOCOL-EXTENSION.md).

### The bridge to DeepSeek Harness ([`dsh-tidings-bridge`](https://github.com/kexuejin/dsh-tidings-bridge))

This repository owns the **protocol and runtime**: discovery, handshake, the offline resend queue, the
`agent-*` envelope family, and the loopback `agentNode` control plane (`127.0.0.1:8788`) that the desktop
app keeps resident from its tray. What connects a **DeepSeek Harness** session to that node is the
companion plugin **[`dsh-tidings-bridge`](https://github.com/kexuejin/dsh-tidings-bridge)**:

- it speaks **only** the control plane's loopback HTTP and never touches the wire protocol — every wire
  concern stays in this repository;
- one direction: projects the control plane into DSH (nine agent tools: node status, peers, send/receive,
  announcing sessions and workspaces);
- the other: pushes DSH's session and workspace state back, so the phone can see the session working and
  receive its reasoning and answers.

**They are used together**: this repository alone leaves the host empty (no DSH session to bridge), and the
plugin alone has no node to connect to. See that repository's README for install and configuration.

---

## Known Limitations (stated honestly)

This section is split three ways, because "cannot", "not yet" and "deliberately not" mean very different
things to a user: **the not-yet items say what is missing, the cannot items name the platform, and the
deliberately-not items state the cost.**

### Not built yet, but buildable

- **Attachments from an agent can be viewed but not saved**: the card renders the file name, dimensions and size, but it cannot be opened and cannot be written to local storage. The reason is that an attachment is identified by `attachmentId`, while the file-transfer layer identifies files by `fileId` / `transferId` — the two are not the same identifier and there is no mapping between them, so saving needs a new message that fetches bytes by attachment id. (This entry used to claim that the file channel and `fileId` were already in place and only a save button was missing; that was not true.)
- **A large cross-network file transfer has never been run end to end on a real device**: the code path and its unit tests exist; real-device acceptance is what is missing.
- **Messages do not appear while DSH is queued**: a message sitting in the host's queue reaches the phone when the next round begins. The phone now does say that the instruction is queued and will start when the current round ends, so it no longer looks like the message was lost.
- **Offline queueing is not permanent**: the queue keeps entries long-term only for objects that may still come back. Two tiers — devices this node has met (handshake completed, present in the known-peers roster) are kept indefinitely; objects that exist **only in a group's member list and never completed a handshake** get 24 hours. Independently, any entry still undelivered after 3 days is dropped, and each device keeps at most 200 entries.
  The reason is a real defect in the opposite direction: a device long retired stayed in a group's member list, so every group broadcast appended another entry for it — the queue grew without bound and nothing was ever deliverable. **The cost is**: if a device comes back after more than 3 days offline, messages sent during that window are not resent — to the recipient they are already stale.

### Not possible (platform constraints)

- **iOS needs a signed build for cross-network long connections**: an unsigned build has no keychain container and therefore no TLS transport, so public long connections are refused (fail-closed, never downgraded to plaintext). This is an iOS sandbox constraint, not a choice made by this app.

### Deliberately not done (trade-offs already weighed)

- **No cloud push**: background wakeups rely on the Android foreground service plus offline incremental sync, with no third-party push service involved. The cost is that an app the system has fully frozen receives no timely alert — that is the necessary consequence of not integrating a third-party push service, not a defect.
- **Cross-network screen assistance is unavailable**: screen mirroring / assistance still requires both ends to be on the **same LAN**; cross-network support covers **files** only. The reason is volume: a screen stream runs about 5 MB/s (roughly 300 MB per minute), which does not hold up on cellular data plans or relay bandwidth. It is not technically impossible — it is not worth it.

### Security boundaries

- **No forward secrecy at the identity layer**: a pin covers the SPKI of a long-lived self-signed identity. **The transport itself is TLS 1.3 / 1.2** (ECDHE key exchange, which does provide forward secrecy); the **identity layer** does not — if the private key leaked, past traffic could be decrypted.
- **No key-rotation entry point**: the private key stays stable and the certificate is re-issued automatically (7 days before expiry), so the pin never changes. Manual rotation is not offered because rotating would require every paired device to pair again — a cost that outweighs the benefit.
- **"Plaintext or TLS" is decided by address, so one overlay can land in different tiers.** The rule is `isLanAddress` (private ∨ same /64 ∨ link-local/ULA), and it has no idea *which interface* an address came from. Consequently ZeroTier's `10.x` / `172.26.x` and Tailscale's `fd7a:…` (ULA) count as private ⇒ **plaintext**, while Tailscale's `100.64/10` is not RFC1918 ⇒ **TLS**. **The same pair of devices is encrypted over v4 and plaintext over v6.**

  This is not plaintext leaking onto the internet: Tailscale is WireGuard and ZeroTier encrypts its own link, so the tunnel already protects that layer. It is still an **inconsistency**, and no clean address rule removes it (`fc00::/7` is also used by real LANs). Making it fully consistent means turning the security tier into a property of *which interface the connection used* rather than of the address family — a protocol-layer change that is known to be outstanding.

### Honesty statements (commitments, not limitations)

- **The demo driver does not pretend**: it is off by default, and when selected it states plainly that no real task was executed and counts no tokens.

---

## Contributing

We welcome contributions to Tidings in all forms (bug reports, feature proposals, pull requests, and documentation improvements).

- Please review [CONTRIBUTING.md](CONTRIBUTING.md) for commit conventions and code style,
  and [AGENTS.md](AGENTS.md) — the list of practices in this repo that fail a gate, lose data
  silently, or have already burned someone on a real device.
- Ensure all quality gates pass before submitting a PR.
- For security vulnerabilities, follow the disclosure policy in [SECURITY.md](SECURITY.md).

---

## License

This project is licensed under **[GPL-3.0-only](LICENSE)**.  
All source files carry the `SPDX-License-Identifier: GPL-3.0-only` header. Third-party dependencies and notices are documented in [NOTICE](NOTICE).

---

## Acknowledgments

Tidings's protocol design is inspired by and maintains LAN interoperability with the [茶话间 (Teahouse)](https://github.com/skyjt/teahouse) protocol. We sincerely thank skyjt and the Teahouse open-source community for their foundational work.
