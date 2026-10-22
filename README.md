# 捎话 (Tidings)

<p align="center">
  <b>Local-First 去中心化端对点传讯与私有 Agent 协同底座（近场 LAN + 远场 IPv6 双模）</b><br>
  无需公网服务端 · 公网通道 TLS + 双向公钥指纹锁定（局域网走明文） · 突破物理局域网 · 数据主权完全归属私有设备 · 跨平台 (Android / iOS / macOS / Linux / Windows)
</p>

<p align="center">
  <a href="README.md"><b>简体中文</b></a> | <a href="README_EN.md"><b>English</b></a>
</p>

<p align="center">
  <a href="#快速上手与构建"><img src="https://img.shields.io/badge/Platform-Android%20%7C%20iOS%20%7C%20Desktop-blue.svg" alt="Platforms"/></a>
  <a href="#协议与规范"><img src="https://img.shields.io/badge/Protocol-P2P%20%2B%20PPE--1%20Phase%202.0-success.svg" alt="Protocol"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0--only-orange.svg" alt="License"/></a>
</p>

---

## 架构定位与多端支持

**捎话 (Tidings)** 是一套基于 Kotlin Multiplatform 与 Compose Multiplatform 构建的 **Local-First 去中心化端对点传讯与私有 Agent 协同底座**：

1. **核心协议（Core Runtime）**：纯 Kotlin 实现的点对点通讯协议运行时（UDP 快速发现与状态同步、TCP 权威长报文/文件块流、基于 PPE-1 Phase 2.0 的 Agent 协同总线），与 UI 框架完全解耦，支持跨端复用。
2. **近场 + 远场双模通信，传输安全分两档**：
   - **近场（局域网）**：UDP 广播 + mDNS 零配置发现，毫秒级直连；局域网内走**明文**，明文永不出局域网；
   - **远场（公网 IPv6）**：全球单播 IPv6（GUA）直连，走 **TLS + 双向 SPKI pin**，**fail-closed** —— 验不过就不连，绝不退回明文出网。身份是 EC P-256 自签名证书（私钥不变、证书到期自动续签）；二维码把主机 pin 交给手机，复合二维码内置候选池（`lan_v4` / `lan_v6` / `public_v6`）自动优选路由。
   - **「局域网」只有一条判据**：`isLanAddress`（私网 ∨ 与本机同 /64 ∨ 链路本地/ULA）。**IPv6 的 GUA ≠ 公网可达**：中国宽带家庭 LAN 常分配 `2408:...::/64` 这类全局地址，按地址分类它「像公网」，其实与本机同段网 —— 只认私网会把局域网明文长连接**静默关掉**（详见 `docs/public-transport-security.md` §11）。
   - **公网直连的前提**：对端路由器放行 IPv6 入站。本应用不做打洞、不代改网络设置。
3. **移动端（Mobile App）**：基于 Compose Multiplatform 开发，面向 Android 与 iOS 平台提供符合原生设计规范与安全沙盒机制的现代移动界面。
4. **自研桌面端（Compose Desktop）**：独立桌面客户端（`desktopApp`），共享底层业务状态与组件库，支持宽屏自适应双栏交互与多任务操作。
5. **无头 Agent 节点与可插拔驱动（Agent Node & Drivers）**：解耦底层通信与模型运行时，支持 `NativeRelayDriver`、`DsBotDriver`、`OpenAiDriver` 及 MCP 扩展工具接入，提供本地 HTTP 控制面与 WebSocket 网关。

---

## 核心特性

- **近场 + 远场双模点对点直连**：无需第三方打洞中继服务器，局域网与 IPv6 公网均支持原生点对点加密直达；二维码内置 SPKI 指纹防止任何中间人监听。
- **PPE-1 思考流与推理交互**：原生解耦 `<think>` 思考流与正式回复流，支持毫秒级耗时统计与自适应折叠卡片组件（`ThinkingBlock`）。
- **结构化工具调用与人机回环（HITL）审批**：从生硬 JSON 升级为 L1/L2/L3 三级安全卡片，支持入参 Diff 详情抽屉、一键批准/本次免审与 5 分钟超时闭环保护。
- **工作区上下文感知与会话分段**：移动端常驻显示绑定工作区与 Git 分支，支持切换工程目录并触发会话分段（Session Segmentation），物理隔离上下文并大幅节省 Token。
- **去中心化群聊人机混合协同**：将本地 Agent 抽象为虚拟群成员（Virtual Peer），支持 `@Agent` 意图精准路由、Chief 编排调度与群顶部实时工作态广播（`GroupProgress`）。**什么时候会真的叫醒 Agent**：跟主机的私聊**每条都算**；群里要 `@DSH` 点名，或（工作区群里）**回复 Agent 那条** —— 群里别人之间的闲聊不会触发执行。
- **移动端 MCP 治理与动态 ACL**：可视化检视主机加载的 MCP 工具与执行能力，支持手机端下发动态 ACL 开关（如外出禁用 Bash 执行），即时生效无需重启。
- **零服务器本地系统通知**：基于离线增量游标同步与 Android / iOS 系统原生通知通道，在完全无公网推送服务下实现任务完成与审批唤醒。
- **大文件安全传输与断点续传（含跨网）**：分块流式传输与断点续传；接收端支持沙盒打开、系统级分享，以及图片与音频的原位预览。跨网时另开第二条 TCP 复用同一条链路（`file-ctl` / 裸流 / 续传），公网必须 TLS + 双向 pin。
- **长连接下行通道（K1 / K2）**：手机主动拨主机并维持一条 TCP 长连接，承载下行推送与低延迟控制。局域网走明文（K1），公网走 TLS + 双向 pin（K2）；**公网必须 TLS，验不过就拒**，不存在降级成明文出网这条路。没有需要用户配置的开关：手机在哪张网上就用哪条路，换网（Wi-Fi ↔ 蜂窝 ↔ 飞行模式）自动切换，不需要手动重拨。
- **后台保活与省电**：Android 前台服务（`remoteMessaging`）保证后台可达；Doze 期间降频；**有数据往来时免心跳**，并保留保底 Ping（2×心跳 < 45s 判死），既不误杀也不白耗电。
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

捎话采用 UDP 单播 + JSON 信封作为基础传输层，超长报文与二进制数据切换至独立的 TCP 流传输通道；此外还有一条**手机→主机的长连接**（`lnk1`，见下）承载下行推送与跨网直连。

**公网通道必须 TLS + 双向 pin**（`tlsPort` 可选键，不做事后嗅探）：没 pin 就拒绝承载内容；TLS 验不过不回退明文。明文只允许局域网，判据统一为 `isLanAddress`（`docs/public-transport-security.md` §11）。

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
| `link` 帧族 | 严格校验 | ✅ 完整支持 | 手机→主机的长连接握手与下行推送（能力位 `lnk1`）；公网段走 TLS + 双向 pin |
| `agent-workspace` / `agent-grant-*` / `agent-session-page` | 严格校验 | ✅ 完整支持 | 工作区身份（`aws1`）、授权回路（`agr1`）、会话分页（`agp1`）；只加**可选**键，旧端忽略即兼容 |
| `avatar` `update` `share` | 基础协议兼容 | 保持 ACK / 忽略 | 移动端不作为更新源或文件柜，协议层优雅解包回执以维持对端兼容 |

完整的协议支持矩阵与设计细节请参阅 [PROTOCOL-COVERAGE.md](PROTOCOL-COVERAGE.md)。

### 能力声明（caps）：声明了什么、没声明什么

手机端广播的能力位由 `RelaySettings.caps()` **显式拼出**（`composeApp/.../RelaySettings.kt:300-340`），完整清单见 [`PROTOCOL-COVERAGE.md`](PROTOCOL-COVERAGE.md) 第四节。要点：

- **固定声明**：`agn1`（agent 会话帧）、`agq1`（队列与审批）、`aws1`（工作区身份）、`agr1`（授权回路）、`agp1`（会话分页）、`tbl1`（表格图片文字视图）、`att1`（agent 事件附件卡片）。`tw1` / `mrec1` 各有一个设置开关，关掉即移除。
- **按条件声明**：`rv1` / `rvs1`（投屏，平台支持时）、`shr1`（配置了共享目录时）、`av1`（「自定义头像」打开时）。`ag1` 与 `lnk1` **只有承载 agent 的主机声明**（手机是拨号侧，不声明）。
- **只读不声明**：`fd1` —— 读对端的 `fd1` 决定要不要在 `offer` 后补 `file-ctl direct`，本机不承诺免确认写盘。
- **不声明**：`age1`（批量投递与游标续读未实现）、`upd1`（局域网 P2P 更新源，理由见 `PROTOCOL-COVERAGE.md` 第三节）、`gr1` / `dp1` / `img1`（只做了半边，或实现里根本没有这一位）。
- **纪律**：声明了却没实现 = 对端按「你能收」发过来却被静默吞掉。所以每个能力位都必须有唯一真源，并由 core 侧台账测试钉住（`CapabilityDeclarationTest` / `CapabilityDeclarationSourceTest`）。

### PPE-1 本地 Agent 协同总线

本项目基于 PPE-1 规范实现了完整的本地 Agent 协同能力，扩展了 8 类标准 Agent 控制信封：

- **任务会话与队列**：`agent-session`、`agent-queue`、`agent-run`
- **安全审批流与撤销**：`agent-approval`、`agent-cancel`、`agent-ack`（均受 `CAP_AGENT_QUEUE` 能力权限强校验）
- **事件流与产物交付**：`agent-event`（按 cursor 保序去重）、`agent-artifact`（元数据绑定与流式传输）
- **数据物理隔离**：Agent 交互信封全流程物理旁路隔离于普通聊天消息，通过 SQLDelight 独立分表维护，避免污染用户通讯记录。

关于 Agent 协议设计的详细设计规范与控制面定义，请参阅 [PROTOCOL-EXTENSION.md](PROTOCOL-EXTENSION.md)。

### 与 DeepSeek Harness 的桥（[`dsh-tidings-bridge`](https://github.com/kexuejin/dsh-tidings-bridge)）

本仓持有**协议与运行时**：发现、握手、离线补发队列、`agent-*` 信封族，以及桌面端由托盘常驻的
`agentNode` 回环控制面（`127.0.0.1:8788`）。真正让 **DeepSeek Harness** 的会话接上这个节点的，
是配套插件 **[`dsh-tidings-bridge`](https://github.com/kexuejin/dsh-tidings-bridge)**：

- 它**只**说控制面的回环 HTTP，从不直接碰线协议 —— 线协议的一切都留在本仓；
- 方向一：把控制面投影进 DSH（九个 agent 工具：节点状态、邻居、收发消息、宣告会话/工作区）；
- 方向二：把 DSH 的会话与工作区状态推回控制面，于是手机上能看到这个会话在工作、
  并收到它的思考与正文。

**两边要一起用**：只有本仓，宿主是空的（没有 DSH 会话可桥）；只有插件，没有节点可接。
装法与配置见那个仓的 README。

---

## 已知限制（如实）

这一节分三档，因为「做不到」「没做」「有意不做」对用户的含义完全不同：
**没做的会给出来路，做不到的说清是哪个平台，有意不做的写清代价。**

### 还没做，但可以做

- **Agent 发来的附件只能看，不能保存**：卡片能渲染出文件名、尺寸与大小，但点不开、也落不到本地。
  原因是附件的标识（`attachmentId`）与文件传输用的标识（`fileId` / `transferId`）不是同一个，
  两者之间没有映射 —— 要给「保存」加一条按附件标识取字节的消息。
  （此前这里写的是「文件通道与 `fileId` 都已具备，缺的只是保存入口」，那句不成立。）
- **跨网发大文件还没在真机上端到端跑过一次**：代码链路与单测都在，缺的是真机验收。
- **DSH 排队期间的消息不会立刻出现**：排在宿主队列里的消息，要等下一轮开始才到手机上。
  不过手机现在**会**说「指令已排队，等这一轮结束就轮到它」，不再让人以为消息丢了。

### 做不到（平台限制）

- **iOS 要有签过名的构建才有跨网长连接**：未签名构建拿不到钥匙串，iOS 侧没有 TLS 传输，
  公网长连接会被拒（fail-closed，不会降级成明文出网）。这是 iOS 沙箱的约束，不是本应用的选择。

### 有意不做（代价已权衡）

- **不接云推送**：后台唤醒靠 Android 前台服务 + 离线增量同步，不依赖任何第三方推送服务。
  代价是 App 被系统彻底冻结时收不到实时提醒 —— 这是不接第三方推送的必然结果，不是缺陷。
- **跨网屏幕协助不可用**：投屏与远程协助要求两端在**同一局域网**，跨网只支持文件传输。
  原因是量级：屏幕约 5MB/s（一分钟约 300MB）的持续流量，在蜂窝资费和中继带宽上都不成立。
  技术上并非做不到，是不划算。

### 安全边界

- **没有前向保密**：pin 固定的是长期自签身份的 SPKI。**传输层本身是 TLS 1.3 / 1.2**
  （ECDHE 密钥交换，具备前向保密）；**身份层**没有 —— 私钥一旦泄露，历史流量可被解密。
- **没有密钥轮换入口**：私钥保持不变、证书自动续签（到期前 7 天），因此 pin 长期不变。
  不提供手动轮换，是因为轮换意味着所有已配对设备都要重新配对，代价大于收益。

### 诚信说明（不是限制，是承诺）

- **演示驱动不会假装干了活**：默认不选中；选中后它如实标注「未执行真实任务」，也不计 token。

---

## 参与贡献

我们欢迎社区对捎话项目的任何形式的贡献（包括但不限于发现 Bug、提交 PR、完善文档或设计提出建议）。

- 在提交代码前，请仔细阅读 [CONTRIBUTING.md](CONTRIBUTING.md) 了解代码风格与提交要求，
  以及 [AGENTS.md](AGENTS.md) —— 后者记着本仓「会判红 / 会静默丢数据 / 真机上翻过车」的做法清单。
- 提交前请务必运行全量门禁流水线以确保各项质量门禁均为绿色。
- 如发现安全相关漏洞，请参考 [SECURITY.md](SECURITY.md) 中的指引进行负责任的私下披露。

---

## 开源许可证

本项目采用 **[GPL-3.0-only](LICENSE)** 许可证开源。  
源码文件均标注 `SPDX-License-Identifier: GPL-3.0-only`。第三方依赖与版权声明详见 [NOTICE](NOTICE)。

---

## 致谢

捎话在协议设计上深度参考了 [茶话间 (Teahouse)](https://github.com/skyjt/teahouse) 协议并保持了局域网互联兼容性，在此由衷感谢 skyjt 及茶话间社区的开源贡献。