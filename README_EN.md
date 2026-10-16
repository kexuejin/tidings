# Tidings (捎话)

<p align="center">
  <b>Local-First Decentralized Peer-to-Peer Messaging & Private Agent Collaboration Platform (Dual-Mode: Near-Field LAN + Wide-Area IPv6)</b><br>
  Zero Public Servers · End-to-End SPKI Pinning · Breaking Physical LAN Limits · 100% Data Sovereignty on Private Devices · Multiplatform (Android / iOS / macOS / Linux / Windows)
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
2. **Dual-Mode Communication (Near-Field + Wide-Area)**:
   - **Near-Field Mode**: Zero-configuration automatic discovery via UDP broadcast and mDNS on local networks, establishing direct peer-to-peer links within milliseconds.
   - **Wide-Area Mode**: Native direct connection via Global Unicast IPv6 (`2000::/3`), secured by ECDSA P-256 self-signed certificates and SPKI public key pinning. Intelligent composite QR code candidate pools dynamically select optimal routes, completely overcoming physical LAN boundaries without central relay servers.
3. **Mobile App (`:composeApp` / `:androidApp` / `:iosApp`)**: Built on Compose Multiplatform, delivering native UX, responsive adaptive layout, and sandboxed storage across Android and iOS devices.
4. **Desktop App (`:desktopApp`)**: Standalone Compose Desktop client sharing core business state and UI components, optimized for widescreen split-pane workflows, window management, and background tray execution.
5. **Headless Agent Node & Pluggable Drivers (`:agentNode`)**: Decouples network communication from LLM inference engines. Supports `NativeRelayDriver`, `DsBotDriver`, `OpenAiDriver`, and MCP (Model Context Protocol) tool extensions via a local loopback HTTP control plane (`127.0.0.1:8788`) and WebSocket gateway.

---

## Key Features

- **Decentralized Direct P2P Connectivity**: Direct point-to-point transmission without third-party punch servers. SPKI public key hashes embedded in pairing tokens prevent MITM eavesdropping.
- **PPE-1 Reasoning & Thought Stream**: Native decoupling of `<think>` reasoning traces from formal responses, featuring millisecond latency measurement and collapsible UI cards (`ThinkingBlock`).
- **Structured Tool Call & Human-in-the-Loop (HITL) Approval**: Promotes raw JSON payloads into L1 (Read-Only) / L2 (Modification) / L3 (High-Risk) visual approval cards with diff drawers, one-click authorization, and a 300s fail-safe timeout.
- **Workspace Context Awareness & Session Segmentation**: Shows active workspace directory, path, and Git branch status. Supports switching project directories and triggering session segmentation to isolate prompts and reduce token consumption.
- **Decentralized Multi-Agent Group Collaboration**: Models local agents as virtual group members (`VirtualAgentMember`), supporting `@Agent` routing, Chief coordination, and real-time task progress broadcasting (`GroupProgress`).
- **Mobile MCP Governance & Dynamic ACLs**: Inspect host MCP tools from mobile devices and toggle dynamic permission ACLs (e.g., disabling Bash execution while away from desk) without restarting host services.
- **Zero-Server Native System Notifications**: Uses offline cursor syncing and native OS notification APIs (Android Notifications / iOS UserNotifications / Desktop Tray alerts) to deliver timely task completion and approval wakeups without third-party push servers.
- **Resilient File Transfer & Resume**: Streaming chunked transfer for large files with SHA-256 checksums, sandboxed opening, system share integration, and inline image/audio preview.
- **Strictly Ordered Offline Queue & Reliable Delivery**: Messages sent while a peer is offline are preserved in transactional local queues and automatically flushed upon handshake re-establishment.

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

Tidings enforces strict engineering standards with automated verification gates. Before opening a Pull Request, verify that the complete gate pipeline passes locally:

```bash
./gradlew \
  :core:jvmTest \
  :composeApp:jvmTest \
  :desktopApp:test \
  :agentNode:test \
  :cjkLiteralGate \
  :staleCommentRefGate \
  :constructionTraceGate \
  :designTokenUsageGate \
  :designIconGate \
  :designLocaleParityGate \
  && python3 tools/unused-imports.py --check
```

### Gate Highlights

- **Multiplatform Automated Tests**: Full test suite covering `:core` (JVM / iOS Native), `:composeApp`, `:desktopApp`, and `:agentNode`.
- **Design Token & Contract Ratchets (`:designTokenUsageGate`, `:designIconGate`)**: Verifies through AST parsing that UI code consumes tokens from design specifications and maintains vector icon coverage.
- **Localization Parity (`:designLocaleParityGate`)**: Guarantees complete parity across string resources (0 missing keys, 0 untranslated strings in secondary locales).
- **String Literal Boundary (`:cjkLiteralGate`)**: Forbids hardcoded Chinese literals in production code, requiring proper localization resources.
- **Code Cleanliness (`:staleCommentRefGate`, `:constructionTraceGate`, `unused-imports.py`)**: Forbids fragile line-number references in comments, temporary dev markers, and unused imports.

---

## Protocol & Specifications

Tidings uses UDP unicast + JSON envelopes for low-latency transmission, automatically switching to TCP byte streams for long payloads and file chunks.

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
| `avatar` `update` `share` | Compatibility Mode | ACK / Discard | Graceful acknowledgment to maintain backwards compatibility |

For the complete protocol specification, see [PROTOCOL-COVERAGE.md](PROTOCOL-COVERAGE.md).

### PPE-1 & Phase 2.0 Local Agent Collaboration Bus

Tidings implements local agent collaboration capabilities with 8 specialized agent control envelopes:

- **Sessions & Queues**: `agent-session`, `agent-queue`, `agent-run`
- **Security Approval & Cancellation**: `agent-approval`, `agent-cancel`, `agent-ack` (validated against `CAP_AGENT_QUEUE` capability)
- **Event Streaming & Artifacts**: `agent-event` (ordered by incremental cursor), `agent-artifact` (metadata binding and chunked stream)
- **Physical Data Isolation**: Agent envelopes bypass standard chat stores and are persisted in dedicated SQLDelight tables to prevent polluting user communication logs.

For detailed design specifications, see [PROTOCOL-EXTENSION.md](PROTOCOL-EXTENSION.md).

---

## Contributing

We welcome contributions to Tidings in all forms (bug reports, feature proposals, pull requests, and documentation improvements).

- Please review [CONTRIBUTING.md](CONTRIBUTING.md) for commit conventions and code style.
- Ensure all quality gates pass before submitting a PR.
- For security vulnerabilities, follow the disclosure policy in [SECURITY.md](SECURITY.md).

---

## License

This project is licensed under **[GPL-3.0-only](LICENSE)**.  
All source files carry the `SPDX-License-Identifier: GPL-3.0-only` header. Third-party dependencies and notices are documented in [NOTICE](NOTICE).

---

## Acknowledgments

Tidings's protocol design is inspired by and maintains LAN interoperability with the [茶话间 (Teahouse)](https://github.com/skyjt/teahouse) protocol. We sincerely thank skyjt and the Teahouse open-source community for their foundational work.
