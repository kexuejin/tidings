# M2 · 插件宿主网关 —— 桌面真机冒烟读数

- 日期：2026-10-29（M2 收尾轮）
- 产物：`~/Downloads/relay-build-2026-10-29-m2/Tidings.app`（`createDistributable` 产物）
- 可执行文件 sha256：`b9b0928476b267997ccc739f1c3b47b01f83bb9dcb7021d1d6297f597b381466`
- 装机核对：`/Applications/Tidings.app/Contents/MacOS/Tidings` 与构建产物 sha256 一致；
  `agentNode-*.jar` 内 grep 到 M2 新符号（`ExternalPluginDriver` / `PluginHost` /
  `PluginRegistration`），证明装的是新代码（AGENTS §5 纪律）。
- 装机流程：替换 `/Applications/Tidings.app` 后按 AGENTS §5 走 lsregister 重注册。
- 插件：`examples/plugins/echo-team-plugin` 拷入数据目录
  `~/Library/Application Support/捎话 Tidings/plugins/echo-team-plugin/`
  （目录名与 manifest 的 pluginId 一致 —— 宿主的入驻判据）。

## 场景 1 · 托管模式自动拉起与注册

- 启动桌面端约 15 秒后首次查询：`GET /v1/plugins` 返回 1 条，
  `pluginId=echo-team-plugin`、`status=online`、插件 IPC 端口由系统分配；
  `agentSenderIds` 三条，形如 `agent:<宿主 nodeId>:echo-lead`（senderId 在注册时
  由宿主按 `AgentSenderId.of` 算好 —— 读数里不抄真 nodeId）。
- 宿主自动生成了 `plugins/plugin-token`（64 位十六进制，0600）。

## 场景 2 · 派单 → 事件流 → 卡片 → 台账

`POST /v1/plugin/task {"pluginId":"echo-team-plugin","prompt":"smoke dispatch from desktop"}`：

- 回执 `status=finished`，事件 4 条（cursor 1..4）：`thought.delta` → `token.delta`
  → **`card`**（`cardId=echo-card-echo-session-1`，team-task-board 形状）→ `finished`。
- 台账 `GET /v1/agent/sessions?sessionId=echo-session-1`：total=1；会话
  `agentId=echo-lead`、`agentKind=echo-team-plugin`、`state=done`；同一批 4 条事件
  按游标排列，卡片 JSON 原样落账。

## 场景 3 · 热拔（kill 插件进程）

- `kill -9` 插件 python 进程：
  - t+10s 仍 `online`（探测周期 15s 未到，符合设计）；
  - t+20s 翻 **`offline`**，`lastError=health probe failed: http://127.0.0.1:<port>/healthz unreachable`；
  - 控制面 `/healthz` 全程应答（`ok=true`，uptime 连续增长无重启）—— 主程序不崩。
- 离线后派单回 502 `plugin-unreachable`（不编 sessionId 进台账）。

## 场景 4 · 守护进程模式重启（token 来自 0600 受控文件）

- 不带任何环境变量，直接 `python3 main.py`：插件从数据目录受控文件读到 token，
  t+5s 内重新注册，台账条目被**替换**（total 仍为 1），状态回 `online` ——
  全生命周期热插拔（宿主探测把离线条目翻回在线）。

## 场景 5 · 真机 token 负路径

- 错 token 注册：401 `plugin token mismatch`；
- 无 token 注册：401 `plugin registration requires a token…`。

## 冒烟中发现并当场修复的缺陷

1. **GUI 最小 PATH**：launchd 拉起的桌面壳解析不到 `python3`（只在用户 shell 的
   pyenv 里），且失败在图形界面上无任何可见面（stdout 不可看、process.log 未创建）。
   修复：宿主按搜索目录（PATH + 常见安装位）解析裸命令、注入子进程 PATH、
   解析/spawn 失败原因一律落插件目录旁 `process.log`（commit：插件拉起适配 GUI 最小 PATH）。
2. **目录名与 pluginId 不一致被静默跳过**：skip 分支只上报返回值（GUI 上不可见）。
   修复：所有不拉起分支的原因都落 `process.log`；示例插件的 pluginId 改为与目录名
   一致（`echo-team-plugin`），README 同步。

## 层内证据（非真机）

- Layer 3 全回路：`Layer3PluginGatewayLoopTest`（进程内回环，零 node/python）。
- 稳定性：`:testStabilityGate` 3 轮（core+app）绿；`:agentNode:test` 连跑 3 轮绿。
