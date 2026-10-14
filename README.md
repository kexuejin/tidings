# 捎话 (Tidings)

<p align="center">
  <img src="design/preview/ui-overview.png" alt="Tidings Overview" width="720"/>
</p>

<p align="center">
  <b>局域网去中心化点对点传讯与本地 Agent 协同系统</b><br>
  无需公网服务端 · 无需注册账号 · 数据永不出局域网 · 跨平台 (Android / iOS / macOS / Linux / Windows)
</p>

<p align="center">
  <a href="#快速上手与构建"><img src="https://img.shields.io/badge/Platform-Android%20%7C%20iOS%20%7C%20Desktop-blue.svg" alt="Platforms"/></a>
  <a href="#协议与规范"><img src="https://img.shields.io/badge/Protocol-P2P%20%2B%20PPE--1-success.svg" alt="Protocol"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0--only-orange.svg" alt="License"/></a>
</p>

---

## 架构定位与多端支持

**捎话 (Tidings)** 是一套基于 Kotlin Multiplatform 与 Compose Multiplatform 构建的跨平台局域网点对点通讯与本地 Agent 协同系统：

1. **核心协议（Core Runtime）**：纯 Kotlin 实现的点对点通讯协议运行时（UDP 快速发现与状态同步、TCP 权威长报文/文件块流、基于 PPE-1 的本地 Agent 协同总线），与 UI 框架完全解耦，支持跨端复用。
2. **移动端（Mobile App）**：基于 Compose Multiplatform 开发，面向 Android 与 iOS 平台提供符合原生设计规范与安全沙盒机制的现代移动界面。
3. **自研桌面端（Compose Desktop）**：独立桌面客户端（`desktopApp`），共享底层业务状态与组件库，支持宽屏自适应双栏交互与多任务操作。
4. **无头 Agent 节点与网关（Agent Node & Gateway）**：支持常驻运行的无头模式，提供本地 HTTP 控制面与 WebSocket 网关，用于与外部 Agent 运行时和自动化工具进行 IPC 协同。

---

## 核心特性

- **去中心化点对点传讯**：局域网内直连通讯，文字消息快速直发；超长报文自动平滑切换至 TCP 流通道。
- **大文件安全传输与断点续传**：支持大文件分块流式传输；接收端支持沙盒文件打开与系统级分享，支持媒体图片与音频原位预览。
- **零配置设备自动发现**：基于 mDNS / Bonjour 广播自动识别同网段在线设备，亦支持二维码扫描配对与手动指定节点。
- **去中心化群组协作**：支持基于快照同步的去中心化群聊模型，成员增量变更自动对齐，多端署名保序。
- **PPE-1 本地 Agent 协同总线**：内置 Agent 任务流、状态事件订阅与人工审批流管控（`CAP_AGENT_QUEUE`），支持 Agent 消息流与普通聊天消息物理隔离与独立持久化。
- **离线排队与可靠投递**：对端离线期间消息严格保序入队，对端上线握手后自动发起可靠补发。
- **本地系统通知**：支持跨平台系统级通知（Android Notification / iOS UserNotifications / 桌面通知），支持静音时段与专注模式，无需公网推送服务器。

---

## 项目结构

```text
.
├── core/                          # 核心协议层：传输、加解密、状态机、会话管理（纯 KMP，无 UI）
├── composeApp/                    # 跨平台共享 UI（Compose Multiplatform，支持 Android / iOS / Desktop）
├── androidApp/                    # Android 原生宿主壳工程与截图比对门禁测试
├── desktopApp/                    # Compose Multiplatform 桌面端独立启动入口
├── agentNode/                     # 库：HTTP 控制面与 WebSocket 网关（由桌面端同进程承载）
├── iosApp/                        # iOS Xcode 壳工程（由 XcodeGen 根据 project.yml 生成）
├── design/                        # 设计规范、Token 规范与视觉基准
├── prototype/                     # 原型与交互设计文档
├── tools/                         # 门禁脚本、设计契约工具与自动化套件
├── docs/                          # 架构演进记录与设计契约对账台账
├── PROTOCOL-COVERAGE.md           # 基础协议兼容度与帧类型支持规范
├── PROTOCOL-EXTENSION.md          # PPE-1 Agent 协议扩展规范定义
├── CONTRIBUTING.md                # 社区贡献指南与规范
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

> **提示**：如果网络访问官方 Maven 源受限，可以在 Gradle 命令中增加 `-PrelayMirror=aliyun` 或设置环境变量 `RELAY_MAVEN_MIRROR=aliyun` 启用国内镜像。

### 构建 Android 端

```bash
./gradlew :androidApp:assembleDebug
```

编译产物位于 `androidApp/build/outputs/apk/debug/`。

### 构建 Desktop 端

```bash
./gradlew :desktopApp:run
```

打包桌面分发产物：

```bash
./gradlew :desktopApp:packageDistributionForCurrentOS
```

### 构建 iOS 端

```bash
./gradlew :composeApp:compileKotlinIosArm64
cd iosApp && xcodegen generate
open Tidings.xcodeproj
```

---

## 质量门禁与验证 (Quality Gates)

本项目实施严格的代码质量工程标准，包含 12 项自动化静态与动态检查。在发起 PR 前，需确保本地全量门禁流水线全部通过：

```bash
./gradlew \
  :core:allTests \
  :composeApp:allTests \
  :desktopApp:test \
  :agentNode:test \
  :desktopApp:compileKotlin \
  :cjkLiteralGate \
  :staleCommentRefGate \
  :constructionTraceGate \
  :designSpecDrift \
  :designTokenUsageGate \
  :designStateSlotGate \
  && python3 tools/unused-imports.py --check
```

### 关键门禁说明

- **全平台自动化测试**：覆盖 `:core`（JVM / iOS Native 双平台）、`:composeApp`、`:desktopApp` 以及 `:agentNode` 的控制面/网关单测。
- **设计契约与 Token 门禁 (`:designSpecDrift`, `:designTokenUsageGate`, `:designStateSlotGate`)**：基于源码 AST 静态分析，保证 UI 组件严格消费设计规范定义的色彩、间距与排版 Token，保障状态槽全量覆盖。
- **文案规范门禁 (`:cjkLiteralGate`)**：生产源码中禁止新增硬编码字面量，强制走国际化资源集中管理。
- **代码整洁门禁 (`:staleCommentRefGate`, `:constructionTraceGate`, `unused-imports.py`)**：静态检查确保注释内引用的代码符号真实存在，杜绝死 import 与开发期遗留绝对路径。
- **可选本地截图比对**：开发者在本地进行深度视觉走查时，可按需运行 `./gradlew :androidApp:updateDebugScreenshotTest` 录制基准图。

---

## 协议与规范

捎话采用 UDP 单播 + JSON 信封作为基础传输层，超长报文与二进制数据切换至独立的 TCP 流传输通道。

### 基础协议帧类型支持概览

| 报文类型 | 编解码与校验 | 客户端处理 | 说明 |
|---|---|---|---|
| `entry` `alive` `exit` `presence` | 严格校验 | ✅ 完整支持 | 节点发现、心跳与在线状态维护；对端退出立即标记离线 |
| `profile` | 严格校验 | ✅ 完整支持 | 节点资料更新与版本号增量同步（`profileRev`） |
| `msg` (`text` / `group-text`) | 严格校验 | ✅ 完整支持 | 单聊与群聊文本通讯，群聊基于多播单播模型实现 |
| `msg` (`recall`) | 严格校验 | ✅ 完整支持 | 文本与媒体撤回支持（受 5 分钟时间窗口约束） |
| `msg` (`nudge`) | 严格校验 | ✅ 完整支持 | 局域网轻量「戳一碰」互动提醒，受客户端速率限流保护 |
| `ack` | 严格校验 | ✅ 完整支持 | 可靠投递帧确认响应机制 |
| `file-ctl` | 严格校验 | ✅ 完整支持 | 文件传输控制信令（offer / accept / decline / cancel） |
| `screen` | 严格校验 | ✅ 完整支持 | 局域网投屏控制与实时数据流传输（支持 `rv1` / `rvs1`） |
| `group` | 严格校验 | ✅ 核心支持 | 群元数据全量快照与增量请求（`op:info` / `op:need`） |
| `avatar` `update` `share` | 基础协议兼容 | 保持 ACK / 忽略 | 移动端不作为更新源或文件柜，协议层优雅解包回执以维持对端兼容 |

完整的协议支持矩阵与设计细节请参阅 [PROTOCOL-COVERAGE.md](PROTOCOL-COVERAGE.md)。

### PPE-1 本地 Agent 协同总线

本项目基于 PPE-1 规范实现了完整的本地 Agent 协同能力，扩展了 8 类标准 Agent 控制信封：

- **任务会话与队列**：`agent-session`、`agent-queue`、`agent-run`
- **安全审批流与撤销**：`agent-approval`、`agent-cancel`、`agent-ack`（均受 `CAP_AGENT_QUEUE` 能力权限强校验）
- **事件流与产物交付**：`agent-event`（按 cursor 保序去重）、`agent-artifact`（元数据绑定与流式传输）
- **数据物理隔离**：Agent 交互信封全流程物理旁路隔离于普通聊天消息，通过 SQLDelight 独立分表维护，避免污染用户通讯记录。

关于 Agent 协议设计的详细设计规范与控制面定义，请参阅 [PROTOCOL-EXTENSION.md](PROTOCOL-EXTENSION.md)。

---

## 参与贡献

我们欢迎社区对捎话项目的任何形式的贡献（包括但不限于发现 Bug、提交 PR、完善文档或设计提出建议）。

- 在提交代码前，请仔细阅读 [CONTRIBUTING.md](CONTRIBUTING.md) 了解代码风格与提交要求。
- 提交前请务必运行全量门禁流水线以确保各项质量门禁均为绿色。
- 如发现安全相关漏洞，请参考 [SECURITY.md](SECURITY.md) 中的指引进行负责任的私下披露。

---

## 开源许可证

本项目采用 **[GPL-3.0-only](LICENSE)** 许可证开源。  
源码文件均标注 `SPDX-License-Identifier: GPL-3.0-only`。第三方依赖与版权声明详见 [NOTICE](NOTICE)。

---

## 致谢

捎话在协议设计上深度参考了 [茶话间 (Teahouse)](https://github.com/skyjt/teahouse) 协议并保持了局域网互联兼容性，在此由衷感谢 skyjt 及茶话间社区的开源贡献。