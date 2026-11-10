# A22 单聊派单闭环 · 真机验收指南

本指南只跑 [ACCEPTANCE.md](../ACCEPTANCE.md) 的 A22 表格第 3 行——**1:1 普通文本派单**。
`@mention` 点名（第 4 行）与工作区协同群派单不在本指南范围内；它们失败不代表这一行失败，
反过来也一样。

要证明的是七跳：

> 手机在 1:1 会话选中工作区 → 发送需求 → 桌面在**该工作区**的沙箱执行 →
> 工单卡回到手机 → 产物直投 → 手机点「合并此补丁」→ 文件真的落到工作区，卡面翻「已合并」。

只看日志、气泡或「已送达」都不算通过。**判据是工作区里的文件和手机上的卡面。**
现场原始读数示例见
[2026-11-08-a22-1to1-workspace-dispatch.txt](../design/runtime-evidence/2026-11-08-a22-1to1-workspace-dispatch.txt)。

## 1. 准备

### 1.1 两端与网络

下面以 **macOS 桌面宿主 + Android 手机**为例；Linux/Windows 只换安装方式与数据目录，判据相同。

- 两端都装本仓 **HEAD** 构建。Android 用 debug 包（`com.kapp.tenon.debug`，`run-as` 才读得到库）：

  ```bash
  ./gradlew :androidApp:assembleDebug
  ./gradlew :desktopApp:createDistributable
  ```

  装包、替换 `Tenon.app`、`lsregister` 与「grep 新符号证明装的是新代码」见
  [ACCEPTANCE.md §1b](../ACCEPTANCE.md)。**不要只看「装上了」。**

- 两端在同一个 Wi-Fi，且没有开 AP 隔离；节点页显示的是真名，不是 `node-xxxxxxxx`。
- 桌面控制面默认在 `127.0.0.1:8788`。先确认活着：

  ```bash
  curl -s http://127.0.0.1:8788/healthz
  ```

  如果控制面设了 token，后续每个 `curl` 都加
  `-H 'Authorization: Bearer <token>'` 或 `-H 'X-Tenon-Token: <token>'`。

### 1.2 选一个真工作区

列出本宿主的工作区，记下 `nodeId`、`wsId`、`wsPath`、`agentKind`：

```bash
curl -s http://127.0.0.1:8788/v1/agent/workspaces
```

只选 `nodeId` 等于 `/healthz` 里本机 `nodeId` 的那一行。要求：

- 列表为空 = 还没有可派单的工作区，本验收不成立；先让桌面端的 agent/插件上报工作区，
  不要手造 `wsId`；
- `wsPath` 是本机真实 git 仓库，不是 `/tmp` 下的沙箱；
- 目录可写、磁盘有空间；
- 本轮用一个**唯一的探针文件名**，并确认它现在不存在（避免旧文件冒充成功）：

  ```bash
  test ! -e "<wsPath>/a22-probe.txt" && echo "clean"
  ```

另外确认目标驱动能真的跑完一个最小任务（该驱动的无头探针、模型配额、凭据都正常）。
这一步不通过，后面的七跳没有意义。

### 1.3 授权：1:1 直派只认节点级 `scope=agent`

这是 A22 现场最容易卡住的一步，不是缺陷：1:1 直派按**节点级执行授权**判定。默认
`request` 策略下，这条授权必须是 `scope=agent`，`scope=all` 不能替代。手机端只能发申请，
不能直接写宿主的授权台账；所以首次派单会被 `DENIED`，并在宿主 grants 的 `requests[]`
留一条申请。

先读台账：

```bash
curl -s http://127.0.0.1:8788/v1/agent/grants
```

从 `/v1/peers` 或 `requests[].nodeId` 取手机 `nodeId`，补一条授权：

```bash
curl -s -X PUT http://127.0.0.1:8788/v1/agent/grants \
  -H 'Content-Type: application/json' \
  -d '{"grant":{"nodeId":"<手机 nodeId>","scope":"agent","agentKey":"<agentKind>","access":"rw","grantedBy":"a22"}}'
```

- `agentKey` 用工作区记录里的 `agentKind`，或派单日志 `[OCGO-BRIDGE] invoke agent=...` 的值；
  大小写不敏感。
- `scope=all` **不能**替代 `scope=agent`。
- 返回 `{"ok":true,...}` 后重新 `GET` 一次，确认 `grants[]` 里真有这一条。

## 2. 跑

1. 桌面端打开，进入与手机的 **1:1 会话**（不是群）。
2. 顶栏应出现工作区药丸；点「切换工作区」→ 标题为「选择工作区」的抽屉 → 选中
   1.2 里的目标工作区。确认药丸文字已更新。
3. 在输入框发送一条**无 `@`、非问句**的派单文本，例如：

   ```text
   implement create a22-probe.txt at repo root with content A22-OK
   ```

   带 `@` 会走第 4 行的 mention 路径；问句/讨论语气可能被判成 `Discuss`，不派单。
   用 `adb shell input text` 注入时只保证 ASCII，且发送前回读输入框——它会吞字。

4. 等。模型执行可能需要几十秒到几分钟，**给足预算（例如 5 分钟）**，不要用固定 10 秒判失败。

## 3. 七跳判据

> 下面说的「宿主 stdout」：用 `./gradlew :desktopApp:run` 启动时就是该终端；打包产物从启动它的
> 终端或重定向文件读（Finder 双击没有可读 stdout，验收时请从终端启动或重定向）。

| # | 看哪里 | 通过长什么样 |
|---|---|---|
| 1 | 宿主 stdout | `[OCGO-BRIDGE] invoke agent=... wsId=<目标 wsId> ... cliDriver=...`；`wsId` 与 1.2 一致，且没有 `DENIED` |
| 2 | 宿主 stdout / 沙箱产物 | `[sandbox] harvest <taskId>: +N -M ... patch=<bytes>`；`/tmp/tenon-ws/artifacts/<taskId>/` 下有 `report.json`、`patch.diff`（通常还有 `task.log`） |
| 3 | 手机 logcat / 会话 | `[card] inbound card=... rev=...`；会话里出现工单卡。宿主应打 `[CARD] 1:1 direct card sent`，**不能只有** `persisted locally` |
| 4 | 宿主 stdout / 手机卡面 | `[xfer] ... state=Completed`；卡片上的产物/进度到达手机 |
| 5 | 手机点「合并此补丁」/ 宿主 stdout | `[RELAY-INVOKE] patch merged into workspace ... (card ...)`；点完没有这行就是没合流 |
| 6 | 工作区文件 | `cat "<wsPath>/a22-probe.txt"` 输出 `A22-OK`；`git -C "<wsPath>" status --short a22-probe.txt` 显示 `?? a22-probe.txt`（只改工作树，不自动提交） |
| 7 | 手机卡面 | uiautomator dump 里卡面出现「已合并」/ 进度 100%，而不是停在「待合并」或旧 rev |

第 6 跳是 A22 第 3 行的**决定性判据**。合并前先确认文件不存在；合并后内容逐字一致，
才能排除「文件本来就躺在那」。

## 4. 读库与日志（判「到了没」）

- 手机原始信封在 `files/relay/stores/messages.log`，**落库时刻看 `at` 列，不要看信封 `ts`**。
- 手机会话真身是 SQLDelight 的 `databases/relay.db`；`messages.json` 只是导出。需要时把
  `relay.db` 与 `relay.db-wal` 一起拉出来再查：

  ```bash
  adb -s <serial> exec-out run-as com.kapp.tenon.debug cat databases/relay.db > /tmp/a22-phone.db
  adb -s <serial> exec-out run-as com.kapp.tenon.debug cat databases/relay.db-wal > /tmp/a22-phone.db-wal 2>/dev/null || rm -f /tmp/a22-phone.db-wal
  sqlite3 /tmp/a22-phone.db \
    "select datetime(timestamp/1000,'unixepoch','localtime'), conv_id, mine, substr(text,1,40)
       from MessageEntity order by timestamp desc limit 10;"
  ```

- 桌面库（macOS 默认）在
  `~/Library/Application Support/榫卯 Tenon/relay.db`。要核 rev 台账可以查：

  ```bash
  sqlite3 "$HOME/Library/Application Support/榫卯 Tenon/relay.db" \
    "select card_id, last_rev, datetime(updated_at/1000,'unixepoch','localtime')
       from BoardCardRev order by updated_at desc limit 5;"
  ```

- 手机卡面用 uiautomator 读，不要写死坐标：

  ```bash
  adb -s <serial> shell uiautomator dump /sdcard/a22.xml
  adb -s <serial> shell cat /sdcard/a22.xml
  ```

## 5. 常见误判

- **卡片只在宿主会话里**：宿主日志是 `persisted locally`、手机零入站 ⇒ 不通过（D1）。
- **结果帧到了手机，但手机没有卡**：帧到了不等于有落点，不通过（D2）。
- **`[xfer] Completed` 但工作区没有文件**：产物投递 ≠ 补丁合流，不通过（D3）。
- **合并前文件已经存在**：旧文件冒充本轮结果，不算证据。
- **`@mention` 不工作**：那是 A22 第 4 行，不要拿它判第 3 行。
- **日志出现 `no such table: BoardCardRev`**：先按 A22 D4 处理 rev 台账，不要把它当成派单失败。
- **点「合并」毫无反应**：先查 grant 的 `agentKey` 大小写、手机是否真发出动作帧、宿主
  `dedup.log` 是否有该 `envelopeId`；帧到了却被静默拒绝，是失败，不是「还在跑」。

## 6. 留痕

验收结论写到 `design/runtime-evidence/` 的 **txt**（截图不入库），至少包含：

```text
结论：A22 第 3 行 通过 / 不通过（未通过写清停在第几跳）
装置：手机型号 + 系统 + 包名；桌面构建标识；链路（同 LAN / 长连接）
工作区：wsId / wsPath / agentKind
七跳读数：上面 1–7 的原始日志行与文件内容
反向证据：合并前文件不存在；合并后 cat 输出；git status；卡面文字
未覆盖：本轮没验到的分支
```

只有七跳全过、且第 6 跳的文件是**本轮新产生**的，才把 A22 第 3 行反填为通过；
第 4 行仍按 [ACCEPTANCE.md](../ACCEPTANCE.md) 保持「不通过」，不要顺手一起改。

## 7. Known limits（已知限制）

- 只覆盖 A22 第 3 行的 1:1 普通文本派单；`@mention`、工作区协同群派单及其他验收条目不在范围内。
- 只覆盖同一 Wi-Fi 下的 macOS 桌面与 Android 手机主路径；跨网、断网重连及 iOS/Linux/Windows 差异未验证。
- 不验证驱动质量、模型配额与输出正确性；附件保存及「合并此补丁」以外的卡片交互也不覆盖。

---

> 本轮：**wsId threading merge verification round**（wsId 贯通分支合并后的验证轮）。
> 本轮：**dedupe round**（去重轮验证）。

## 8-Trigger-channel-real-device-verification

- **webhook 先落盘再 ACK**：验签通过后必须同步写入 `TriggerEvent` 台账，只有返回 `Stored` 或 `Duplicate` 才回 `200`；落盘失败（含 sink 抛错）必须回 `503 persist-failed`，不得用 200 掩盖丢事件。真机上以宿主台账新增一行、发送方收到 200 为准，不能只看「请求成功」。
- **幂等键必须是复合键**：重启后的重投用 `(source_id, event_id)` 判重，命中 `Duplicate` 仍回 200 且不再派发；不能只按 `event_id`，否则两个源使用同一投递 id 时后到的那条会被静默吞掉。内存幂等账只能在落盘成功之后记。
- **消费走表驱动、可恢复**：`TriggerEventDrain` 按 `pending → running → done/failed/skipped` 消费，启动时把上一进程遗留的 `running` 放回 `pending`，并用 `claimEvent` 原子认领；验证重启后同一条事件只派发一次，空跑或双派都算失败。
- **cron 只在桌面宿主调度**：手机端不跑定时器；桌面重启或合盖唤醒后按 `SchedulerTaskState.next_run_at` 补判，`RUN_ONCE` 最多补 1 次，`SKIP` 放弃积压批次但当期自然窗口照常派发，`missed_runs` 应如实增加，严禁把多个错过窗口并发补发。
- **以台账和结果卡收口**：真机拉 `relay.db` 查 `TriggerEvent` 终态与 `SchedulerTaskState` 的 `last_run_at` / `next_run_at` / `missed_runs`；若启动日志出现 `no such table: TriggerEvent` 或 `no such table: SchedulerTaskState`，先按库迁移与 `SchemaSelfHeal` 处理（迁移未生效时每条 webhook 都落不了库），不要误判成触发器没注册。
- **`closeCount` 是链路稳定性的唯一判据**：换网、断网重连与冷启动全程必须保持为 **0**；只看「当前在线」或某一刻能连通不算通过，`closeCount` 一旦增长就说明链路抖动，判失败。
