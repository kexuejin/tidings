# 榫卯 (Tenon)

<p align="center">
  <b>Local-First 去中心化端对点传讯与私有 Agent 协同底座（近场 LAN + 远场 IPv6 双模）</b><br>
  无需公网服务端 · 公网通道 TLS + 双向公钥指纹锁定（局域网走明文） · 突破物理局域网 · 数据主权完全归属私有设备 · 跨平台 (Android / iOS / macOS / Linux / Windows)
</p>

<p align="center">
  <a href="README.md"><b>简体中文</b></a> | <a href="README_EN.md"><b>English</b></a>
</p>

<p align="center">
  <a href="#快速上手与构建"><img src="https://img.shields.io/badge/Platform-Android%20%7C%20iOS%20%7C%20Desktop%20%7C%20CLI-blue.svg" alt="Platforms"/></a>
  <a href="#协议与规范"><img src="https://img.shields.io/badge/Protocol-P2P%20%2B%20PPE--1%20Phase%202.0-success.svg" alt="Protocol"/></a>
  <a href="#智能体编排与执行"><img src="https://img.shields.io/badge/Agents-Claude%20%7C%20Codex%20%7C%20agy%20%7C%20dsh%20%7C%20opencode-purple.svg" alt="Agents"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0--only-orange.svg" alt="License"/></a>
</p>

---

## 架构定位与系统全景

**榫卯 (Tenon)** 是一套基于 Kotlin Multiplatform 与 Compose Multiplatform 构建的 **Local-First 去中心化端对点传讯与私有 Agent 协同底座**。它将跨平台点对点通信通信栈、离线数据主权持久化、多端响应式自适应界面与私有智能体宿主控制面融为一体：

1. **核心协议（Core Runtime）**：纯 Kotlin 实现的点对点通讯协议运行时（UDP 快速发现与状态同步、TCP 权威长报文/文件块流、基于 PPE-1 Phase 2.0 的 Agent 协同总线），与 UI 框架完全解耦，支持跨端复用。
2. **近场 + 远场双模通信，传输安全分两档**：
   - **近场（局域网）**：UDP 广播 + mDNS 零配置发现，毫秒级直连；局域网内走明文，明文永不出局域网；
   - **远场（公网 IPv6）**：全球单播 IPv6（GUA）直连，走 **TLS 1.3 + 双向 SPKI pin**，**fail-closed** —— 验不过就不连，绝不退回明文出网。身份是 EC P-256 自签名证书（私钥不变、证书到期自动续签）；二维码把主机 pin 交给手机，复合二维码内置候选池（`lan_v4` / `lan_v6` / `public_v6`）自动优选路由。
   - **「局域网」只有一条判据**：`isLanAddress`（私网 ∨ 与本机同 `/64` ∨ 链路本地/ULA）。**IPv6 的 GUA ≠ 公网可达**：中国宽带家庭 LAN 常分配 `2408:...::/64` 这类全局地址，按地址分类它「像公网」，其实与本机同段网 —— 只认私网会把局域网明文长连接静默关掉。
3. **领域层与可执行验证（Domain Layer & Headless Client）**：
   - 领域层（`:app`）严格保持**零 Compose 依赖**，内聚控制器、UI 状态模型、离线排队与 SQLDelight 数据库。
   - 提供无 UI 的终端客户端（`:cli`），只链接 `:app`，0 个 Compose jar，实测 AWT/Swing/Skiko 加载数为 0，作为领域层独立性的可执行验收物。
4. **多端自适应表现层（Adaptive Compose Multiplatform）**：
   - 移动端（Android / iOS）：符合平台原生交互规范与安全沙盒机制的现代移动界面。
   - 桌面端（macOS / Linux / Windows）：宽屏自适应双栏交互、托盘常驻守护、按需建窗、同进程承载控制面。
5. **五大内置 CLI Agent 与本地控制面（Agent Node & Control Plane）**：
   - 内置主流开发智能体驱动（Claude Code、Codex、agy、dsh、opencode），基于 Git Worktree 沙箱物理隔离与凭据安全挂载，开箱即用；
   - 提供本地回环 HTTP API（默认 `127.0.0.1:8788`），集成标准 Model Context Protocol（MCP）服务（`scripts/tenon-mcp-server.py`）。
6. **声明式编排与自治执行（Orchestration Engine）**：
   - 工单流水线、责任链执行体（`StageChain`）、自愈纠偏循环、多棒审查（Multi-stick review）、限流冷却与熔断保护。

---

## 核心特性

### 1. 人机协同与全闭环卡片系统
- **卡片密度规范（.acard）**：彻底摆脱生硬的原始 JSON 结构，像素级还原结构化卡片排版。
- **审批卡（ApprovalCard）**：L1/L2/L3 三级安全卡片，支持入参 Diff 详情、命令代码块高亮渲染（`CardCmdBox`）、上下文感知块（`CardContextBox`）、一键批准/本次免审与 5 分钟超时闭环保护。
- **合流卡（ReviewCard）**：支持**文档差异实时预览（diff hunk 语法高亮与变更增删行号统计）**，展示任务交付产物与验证证据。
- **卡片头行三件套**：数字员工工牌（Avatar + 昵称）、状态胶囊（Capsule）、验收耗时徽章（Duration badge），一目了然。
- **问答决策卡（`ask_question` 生产闭环）**：Agent 执行中遇到设计抉择时自动生成多选决策卡，手机端直接点选选项，通过回执长轮询信道秒级唤醒挂起协程，实现真正的人机回环。
- **机器回执系统行**：纯客户端精准识别回执动作，转化为居中轻量 `.notice` 胶囊呈现，避免无关消息打乱主交互。

### 2. 多智能体编排与治理
- **五大内置 CLI Agent 驱动**：开箱支持 Claude Code、Codex、agy、dsh 与 opencode，通过 `BuiltinPluginSeeder` 自动探测环境凭据与安装状态。
- **工单责任链与状态驱动**：`StageChain` 统一调度任务生命周期，支持分阶段执行、中间产物传递与失败自愈。
- **多棒连续审查与限流冷却**：线性多棒交叉审查与自动化纠偏，防抖降温冷却机制避免频繁打扰。
- **工作区上下文感知与会话分段**：移动端常驻显示绑定工作区与 Git 分支，支持切换工程目录并触发会话分段（Session Segmentation），物理隔离上下文并大幅节省 Token。
- **去中心化群聊人机混合协同**：将本地 Agent 抽象为虚拟群成员（Virtual Peer），支持 `@Agent` 意图精准路由、Chief 编排调度与群顶部实时工作态广播。

### 3. 本地优先与安全通信
- **端到端近场/远场直连**：无需第三方中继服务器，局域网与 IPv6 公网均原生点对点加密直达；二维码内置 SPKI 指纹防止任何中间人劫持。
- **长连接双模通道（K1 / K2）**：手机主动拨主机并维持一条 TCP 长连接，承载下行推送与低延迟控制。局域网走明文（K1），公网走 TLS + 双向 pin（K2），无感自动切换。
- **大文件安全传输与断点续传**：分块流式传输与断点续传；接收端支持沙盒打开、系统级分享，以及图片与音频原位预览。
- **数据物理隔离与 FTS 索引安全**：Agent 交互信封全流程物理旁路隔离于普通聊天消息，通过 SQLDelight 独立分表维护；消息表与 FTS 索引行数保持严格一致。
- **零服务器本地系统通知**：基于离线增量游标同步与 Android / iOS 系统原生通知通道，在完全无公网推送服务下实现任务完成与审批唤醒。

---

## 项目模块划分

```text
.
├── core/                          # 核心协议层：传输、加解密、状态机、会话管理（纯 KMP 共享内核，无 UI）
├── app/                           # 领域层：控制器、UI 状态、存储/数据库 (SQLDelight)、平台契约（零 Compose 依赖）
├── composeApp/                    # 表现层：跨平台共享 UI（会话/聊天/节点/工作区/设置，响应式自适应布局）
├── cli/                           # 领域层脱离 UI 的可执行验收物（纯 JVM 终端客户端，0 Compose 依赖）
├── agent-contract/                # Agent 驱动层纯接口契约：会话制契约、能力描述符、事件七型定义
├── orchestration/                 # Agent 编排层：工单责任链、多棒审查、自愈循环、卡片补丁、事件胶水
├── agentNode/                     # Agent 控制面：本地回环 HTTP API、内置驱动池、插件状态管理
├── desktopApp/                    # 桌面端入口：托盘常驻、按需建窗、同进程承载控制面（Tenon.app）
├── androidApp/                    # Android 原生宿主壳工程与截图比对门禁测试
├── iosApp/                        # iOS Xcode 壳工程（由 XcodeGen 根据 project.yml 生成）
├── testsupport/                   # 源码级测试守卫读盘工具（只用于测试，永不进安装包）
├── design/                        # 设计规范、Token 规范与视觉基准
├── prototype/                     # 原型与交互设计文档
├── tools/                         # 门禁脚本、设计契约工具与自动化套件
├── docs/                          # 架构演进蓝图、正式设计规范与物理验收规程
├── PROTOCOL-COVERAGE.md           # 基础协议兼容度与帧类型支持规范
├── PROTOCOL-EXTENSION.md          # PPE-1 Agent 协议扩展规范定义
├── CONTRIBUTING.md                # 社区贡献指南与规范
├── AGENTS.md                      # 在本仓动手的约束（会判红 / 会静默丢数据 / 翻过车的做法）
└── SECURITY.md                    # 安全漏洞披露政策
```

---

## 快速上手与构建

### 环境要求

- **JDK 21** 或更高版本
- **Android Studio**（推荐最新稳定版，构建 Android 端所需）
- **Android SDK**（API 34 / 36）
- **macOS + Xcode 15+** 与 [XcodeGen](https://github.com/yonaskolb/XcodeGen)（构建 iOS 端所需）

首次构建前，在 `local.properties` 中配置本地 Android SDK 路径：

```properties
sdk.dir=/path/to/your/Android/sdk
```

### 构建 Android 端

```bash
./gradlew :androidApp:assembleDebug
```

编译产物位于 `androidApp/build/outputs/apk/debug/`。

### 构建与运行桌面端 (Desktop)

```bash
# 本地开发运行
./gradlew :desktopApp:run

# 打包当前操作系统可分发安装包 (DMG / MSI / DEB)
./gradlew :desktopApp:packageDistributionForCurrentOS
```

### 运行 CLI 无头客户端

```bash
./gradlew :cli:run
```

### 构建 iOS 端

```bash
./gradlew :composeApp:compileKotlinIosArm64
cd iosApp && xcodegen generate
open Tenon.xcodeproj
```

---

## 质量门禁与验证 (Quality Gates)

本项目实施严格的代码质量工程标准。提交前须确保以下全量流水线通过（口径与 [AGENTS.md](AGENTS.md) §1 完全一致）：

```bash
# 测试（九个任务 / 八个模块，验收时加 --max-workers=1 避免并行竞争）
./gradlew :core:jvmTest :app:jvmTest :cli:test :agent-contract:test :orchestration:test \
          :composeApp:jvmTest :composeApp:testAndroidHostTest :agentNode:test \
          :desktopApp:test --continue --max-workers=1

# 质量门禁（缺一不可）
./gradlew :cjkLiteralGate :constructionTraceGate :staleCommentRefGate \
          :domainComposeFreeGate :envDependentTestGate :designSpecDrift \
          :designTokenUsageGate :designStateSlotGate :designLocaleParityGate \
          :jlinkModulesGate --continue
python3 tools/unused-imports.py --check
python3 tools/check-design-palette.py --css-vars
```

动了 UI / 设计 token / 截图夹具，还要跑设计契约门禁：`designScreenDiffGate`、`designIconGate`、`designConvergeGate`、`designFormPairingGate`、`designPreviewPairingGate`、`designDupFrameGate`、`designDpDebtGate`。

---

## 开源许可证

本项目采用 **[GPL-3.0-only](LICENSE)** 许可证开源。  
源码文件均标注 `SPDX-License-Identifier: GPL-3.0-only`。第三方依赖与版权声明详见 [NOTICE](NOTICE)。
