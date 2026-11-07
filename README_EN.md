# Tenon (榫卯)

<p align="center">
  <b>Local-First Decentralized P2P Messaging & Private Agent Collaboration Platform (Dual-Mode: LAN + Public IPv6)</b><br>
  Zero Public Cloud Servers · Public TLS + Mutual SPKI Pinning (Plaintext LAN) · Break Physical LAN Boundaries · 100% Data Sovereignty on Private Devices · Multiplatform (Android / iOS / macOS / Linux / Windows)
</p>

<p align="center">
  <a href="README.md"><b>简体中文</b></a> | <a href="README_EN.md"><b>English</b></a>
</p>

<p align="center">
  <a href="#quick-start-and-build"><img src="https://img.shields.io/badge/Platform-Android%20%7C%20iOS%20%7C%20Desktop%20%7C%20CLI-blue.svg" alt="Platforms"/></a>
  <a href="#protocol-and-specifications"><img src="https://img.shields.io/badge/Protocol-P2P%20%2B%20PPE--1%20Phase%202.0-success.svg" alt="Protocol"/></a>
  <a href="#agent-orchestration-and-execution"><img src="https://img.shields.io/badge/Agents-Claude%20%7C%20Codex%20%7C%20agy%20%7C%20dsh%20%7C%20opencode-purple.svg" alt="Agents"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0--only-orange.svg" alt="License"/></a>
</p>

---

## Architectural Positioning

**Tenon (榫卯)** is a **Local-First decentralized peer-to-peer messaging and private Agent collaboration platform** built with Kotlin Multiplatform and Compose Multiplatform:

1. **Core Runtime (`:core`)**: Pure Kotlin P2P runtime (UDP discovery/status sync, TCP authoritative payloads/streams, PPE-1 Phase 2.0 Agent bus), fully decoupled from UI frameworks.
2. **Dual-Mode Transport**:
   - **Near-field (LAN)**: UDP broadcast + mDNS zero-config discovery with millisecond latency; plaintext within LAN.
   - **Far-field (Public IPv6)**: Global Unicast Address (GUA) direct link, **TLS 1.3 + mutual SPKI pinning**, strictly **fail-closed**.
3. **Domain Layer & Executable Verification (`:app` & `:cli`)**:
   - The domain layer (`:app`) strictly maintains **zero Compose dependencies**, encapsulating controllers, UI models, offline queues, and SQLDelight persistence.
   - Headless client (`:cli`) links only to `:app` with 0 Compose jars, acting as an executable verification of domain isolation.
4. **Adaptive Multiplatform UI (`:composeApp`)**:
   - Mobile (Android / iOS): Native-feeling, responsive UI with platform sandbox integrations.
   - Desktop (macOS / Linux / Windows): Wide-screen dual-pane navigation, tray daemon, on-demand window creation.
5. **Builtin 5 CLI Agents & Control Plane (`:agentNode`)**:
   - Out-of-the-box support for Claude Code, Codex, agy, dsh, and opencode with Git Worktree sandbox isolation and credential mounting.
   - Loopback HTTP API (default `127.0.0.1:8788`) and standard Model Context Protocol (MCP) tool server (`scripts/tenon-mcp-server.py`).
6. **Declarative Orchestration & Autonomous Execution (`:orchestration`)**:
   - Stage chain pipeline (`StageChain`), autonomous healing loops, multi-stick reviews, rate limiting, and circuit breaking.

---

## Key Features

### 1. Human-in-the-Loop & Interactive Card System
- **Card Density Specification (.acard)**: Replaces raw JSON dumps with structured, production-grade card rendering.
- **Approval Cards (ApprovalCard)**: L1/L2/L3 security tiers, parameter diffs, command syntax highlighting (`CardCmdBox`), workspace context cards (`CardContextBox`), 1-click approvals, and 5-minute timeout protections.
- **Review Cards (ReviewCard)**: Live document diff previews (**diff hunk syntax highlighting and line modification metrics**) with delivered artifacts inspection.
- **Triple-Header Density**: Digital Employee Badge (Avatar + name), Status Capsule, and Duration Badge.
- **Decision Cards (`ask_question` Production Loop)**: Interactive decision cards for architectural choices with real-time receipt long-polling.
- **Notice Capsules**: Lightweight centered notices for machine receipts without cluttering chat flows.

### 2. Multi-Agent Orchestration & Governance
- **Builtin 5 CLI Agents**: Full lifecycle management for Claude Code, Codex, agy, dsh, and opencode.
- **StageChain Workflow**: Managed lifecycle for multi-step task execution, artifact handover, and error auto-recovery.
- **Context Isolation**: Session segmentation based on active workspace and Git branch, dramatically reducing token waste.
- **Decentralized Multi-Peer Groups**: Virtual peer abstraction for local agents, `@Agent` routing, and group progress broadcasts.

---

## Project Structure

```text
.
├── core/                          # Core protocol & transport runtime (pure KMP, no UI)
├── app/                           # Domain layer: controllers, storage, SQLDelight (zero Compose)
├── composeApp/                    # Presentation layer: adaptive multiplatform UI
├── cli/                           # Headless terminal client for domain decoupling validation
├── agent-contract/                # Pure interfaces: Agent contracts, capabilities & event schemas
├── orchestration/                 # Workflow orchestration, healing loop & card patching
├── agentNode/                     # Agent control plane: loopback HTTP API & plugin manager
├── desktopApp/                    # Desktop entrypoint: tray daemon & window management (Tenon.app)
├── androidApp/                    # Android native host & screenshot test suites
├── iosApp/                        # iOS Xcode project (generated via XcodeGen)
├── testsupport/                   # Test-only source guard inspection utilities
├── design/                        # Design tokens, visual specs & guidelines
├── tools/                         # Quality gates & verification automation scripts
├── docs/                          # Architecture blueprints & operational runbooks
├── PROTOCOL-COVERAGE.md           # Protocol frame specifications
├── PROTOCOL-EXTENSION.md          # PPE-1 Agent extension specifications
├── CONTRIBUTING.md                # Contribution guidelines
├── AGENTS.md                      # Operational constraints & invariants
└── SECURITY.md                    # Security vulnerability policy
```

---

## Quick Start and Build

### Prerequisites

- **JDK 21** or higher
- **Android Studio** (API 34 / 36)
- **macOS + Xcode 15+** and [XcodeGen](https://github.com/yonaskolb/XcodeGen) (for iOS)

### Building Desktop

```bash
# Run locally
./gradlew :desktopApp:run

# Package distribution (DMG / MSI / DEB)
./gradlew :desktopApp:packageDistributionForCurrentOS
```

### Building Android

```bash
./gradlew :androidApp:assembleDebug
```

### Running Headless CLI

```bash
./gradlew :cli:run
```

---

## Quality Gates

Before submitting code, ensure all quality gates and test suites pass:

```bash
# Test suites (run with --max-workers=1 for final verification)
./gradlew :core:jvmTest :app:jvmTest :cli:test :agent-contract:test :orchestration:test \
          :composeApp:jvmTest :composeApp:testAndroidHostTest :agentNode:test \
          :desktopApp:test --continue --max-workers=1

# Quality gates
./gradlew :cjkLiteralGate :constructionTraceGate :staleCommentRefGate \
          :domainComposeFreeGate :envDependentTestGate :designSpecDrift \
          :designTokenUsageGate :designStateSlotGate :designLocaleParityGate \
          :jlinkModulesGate --continue
python3 tools/unused-imports.py --check
```

---

## License

This project is licensed under **[GPL-3.0-only](LICENSE)**.  
All source files include `SPDX-License-Identifier: GPL-3.0-only`. Third-party dependencies and notices are in [NOTICE](NOTICE).
