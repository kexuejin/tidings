# Adaptation Report

Generated: 2026-10-03T00:43:41.774461+00:00
App: Tidings
Platforms: android, ios

## Summary

> ⚠️ 下面几行是**生成器当时的静态扫描结果**（2026-10-03T00:43，脚手架刚生成时），
> 其中 `not-run` 与 `Screenshot profile: paparazzi` 已过时。真实门禁结果以本文后续章节为准；
> 截图机制是 Compose Preview Screenshot Testing，
> 不是 Paparazzi（那个 profile 参数从未传进 Gradle DSL）。

- DesignSpec: pass
- Architecture scan: pass
- Adaptive static scan: pass
- Icon asset scan: pass
- Common tests: **实测已跑通** — `:core:allTests` 16 用例 × JVM + iOS 模拟器、`:composeApp:allTests` 16 用例 × JVM + iOS 模拟器、`:agentNode:test` 7 用例，全部 0 失败
- Android build: **实测已跑通** — `androidApp-debug.apk` 11,850,576 B
- iOS framework link: **实测已跑通** — `RelayShared.framework`
- Screenshot P0: **实测已跑通** — 11 张参考图，`validateDebugScreenshotTest` 绿
- Screenshot profile: ~~paparazzi~~ → **Compose Preview Screenshot Testing**（`com.android.compose.screenshot` 0.0.1-alpha16，源集 `src/screenshotTest/`）
- Adaptation matrix size: 6

## Screens

- `conversations`: 会话列表：人节点与 agent 节点、群聊、未读、草稿、离线暂存提示；紧凑宽度单列，宽屏列表-详情双栏
  - states: normal, loading, empty, error, long_text, offline_queue_banner
  - compact: single_column
  - medium: two_column
  - expanded: list_detail
- `chat`: 单聊/群聊消息流：文本、长文本、文件卡片、引用回复、送达状态（已送达/重试中/已暂存）、群内 @ 与撤回提示
  - states: normal, loading_history, empty, error, long_text, offline_composing, transfer_in_progress, transfer_failed
  - compact: single_column
  - medium: two_column
  - expanded: list_detail
- `discovery`: 局域网节点发现：广播发现的在线节点、agent 节点徽标、离线缓存；跨网段时手动添加 IP 或 ip:port；显示握手状态与端口
  - states: normal, scanning, empty, error, long_text
  - compact: single_column
  - medium: two_column
  - expanded: list_detail
- `agents`: Agent 控制台：本机 agent 节点在线状态、任务事件流（session 事件/任务完成/产物文件）、直接给 agent 发指令；这是本产品区别于普通 IM 的主界面
  - states: normal, loading, empty, error, long_text, agent_offline
  - compact: single_column
  - medium: two_column
  - expanded: list_detail
- `pairing`: 加入局域网：扫码读取对端节点信息，或输入对端 IP 完成 entry/alive 握手；展示握手进度与失败原因与出路
  - states: normal, scanning_qr, handshaking, success, error, invalid_qr
  - compact: single_column
  - medium: two_column
  - expanded: two_column
- `settings`: 设置：资料（昵称/部门，改动会递增 profileRev）、网络端口、接收文件目录、能力声明说明、关于与诊断信息
  - states: normal, loading, error, long_text
  - compact: single_column
  - medium: single_column
  - expanded: two_column

## Preview Matrix

- Phone Portrait: 393x852 dp, `CompactSinglePane`
- Phone Landscape: 852x393 dp, `CompactLandscape`
- Tablet Portrait: 800x1280 dp, `MediumTwoPane`
- Tablet Landscape: 1280x800 dp, `ExpandedListDetail`
- Dark Mode: 393x852 dp, `CompactSinglePane`
- Large Font: 393x852 dp, `CompactSinglePane`

## Required Build Checks

- `:core:allTests`: not-run
- `:composeApp:allTests`: not-run
- `:androidApp:assembleDebug`: not-run
- `:composeApp:linkDebugFrameworkIosSimulatorArm64`: not-run
- `:agentNode:test`: not-run

## Architecture Findings

- No static architecture findings.

## Adaptive Findings

- No static adaptive layout findings.

## Icon Findings

- WARN: vector icon uses placeholder path and needs visual review: ic_chat_bubble
- WARN: vector icon uses placeholder path and needs visual review: ic_groups
- WARN: vector icon uses placeholder path and needs visual review: ic_lan_search
- WARN: vector icon uses placeholder path and needs visual review: ic_terminal
- WARN: vector icon uses placeholder path and needs visual review: ic_qr_code_scanner
- WARN: vector icon uses placeholder path and needs visual review: ic_send
- WARN: vector icon uses placeholder path and needs visual review: ic_check_double
- WARN: vector icon uses placeholder path and needs visual review: ic_clock_pending
- WARN: vector icon uses placeholder path and needs visual review: ic_bot

## Deferred

- Gradle dependency/version compatibility not verified unless build logs are attached.
- Screenshot image diffs not verified unless screenshot task output is attached.
- iOS runtime smoke not verified unless simulator/device evidence is attached.
