# 真机第二轮：修复后的闭环（2026-11-10 15:07–15:50）

**结论先行**：两个修复在真机上闭环成立。

- 单文件入站**直落下载根**，不再套一层与自己同名的目录；
- 卡片上「已存到 …」与「打开」指的是**去重之后的真实路径**，打开后看到的是**本次那份**；
- `a2r` 的四条判据在真实产物上**全绿**（此前 `prototype-card-delivered` 恒红、
  `prototype-opened-in-browser` 红）。

## 一、这一轮怎么跑的

| 项 | 值 |
| :--- | :--- |
| 设备 | Pixel 6 `<device-serial>`，`com.kapp.tenon.debug`（15:38 用当天源码重装） |
| 宿主 | `46c23456…`，控制面 `127.0.0.1:8788`，数据目录 `~/Library/Application Support/榫卯 Tenon` |
| 派单 | 15:07:21 群里 `@dsh-teahouse-mobile prototype weather app home, single html file, keep it small` |
| 卡片 | `chain-tmwacl52p`，rev 1→2→3→4，终态 `prototype` |
| 沙箱 | `t-1-1e6a963f`，`harvest +1281 -0 in 1 file(s), patch=47478 B` |
| 墙钟 | 约 23 分钟（比上一轮 6 分钟长：这次要求整页，agent 在自检上多绕了几轮） |

## 二、缺陷一（打开的是上一轮那份）的修后行为

### 落盘布局

```text
15:30  files/relay/downloads/prototype(1).html     46052 B   ← 新布局：直落下载根
14:06  files/relay/downloads/prototype.html/       目录      ← 旧布局（历史数据，不迁移）
```

`prototype(1).html` 的 `(1)` 来自**旧目录 `prototype.html/` 仍然占着这个名字**：
`dedupeTargetPath` 只看「目标是否存在」，目录也算存在。行为正确（不覆盖、不崩），
只是名字带后缀。旧目录按上一轮决定**不迁移**。

### 卡片与打开

任务卡产物行：`prototype.html · text/html · 44.9 KB`（46052 B 对得上）。

`a2r` 第三条判据（内容指纹）实跑：

```text
[ok] prototype-artifact-bytes-landed — prototype.html（46052B）逐字节落到手机：
     files/relay/downloads/prototype(1).html
```

### 确定性探针（不经 LLM，专打「去重 + 打开」这条）

用 `POST /v1/send-file {"to": <手机>, "groupId": …, "groupRev": 2, "path": /tmp/probe-open.html,
"name": "prototype.html"}` 再发一个**同名** 475 B 文件：

```text
落盘     files/relay/downloads/prototype(2).html     475 B
卡片     已存到 …/downloads/prototype(2).html        ← 去重后的真名，不是 prototype.html
点「打开」→ 系统选择器 → 使用Chrome 打开 → 仅此一次
前台     com.android.chrome
页面     PROBE-2 / 本轮去重命名与「打开」指向验证 · 2026-11-10
sha256   源 ed368ead9f4132a9…  =  机上 ed368ead9f4132a9…
URI      content://com.kapp.tenon.*（FileProvider，不是 file://）
```

修复前的同一动作：卡片写「已存到 …/prototype.html/prototype.html」，
点「打开」⇒ `AndroidFileActions: 文件不在了，打不开也分享不了：…/downloads/prototype.html`。

## 三、a2r 四条判据（本轮真实数据）

| 判据 | 结果 | 读数 |
| :--- | :--- | :--- |
| `card-reader-self-check` | ok | 合成库（首帧 rev1 / 载荷 rev4+delivered）读回 rev4 |
| `prototype-card-delivered` | ok | 1 张 rev=4 / prototype / `prototype.html` delivered=true |
| `prototype-artifact-bytes-landed` | ok | 46052 B 逐字节落到 `prototype(1).html` |
| `prototype-opened-in-browser` | ok | `com.android.chrome` |

> 第五判据 `prototype-no-main-tree-write` 是**定因之后**才加的（同日 16:2x），本轮读数里没有它。
> 若拿它重跑这一轮，15:45 的事故会让它变红 —— 这正是它存在的意义：前四条全在读手机与卡片库，
> 没有一条会看源仓那一侧。

## 四、这一轮新暴露的问题（未修，如实记）

1. **两侧对「在线」判断不一致**：手机顶部横幅「宿主设备睡眠/离线中 · 命令已安全排队挂起」，
   同时宿主 `/v1/peers` 报该手机 `online=true, linkConnected=true`。此时点「接收」不生效
   （报价卡停在「等你接收」，宿主 `accepted=false`）。**重启手机 App 后恢复**，随后同一份
   报价一次成功。判据：`/v1/peers` 与手机横幅可同时出现矛盾读数。
2. **卡状态与真实进展不一致**：15:45 那次派单在群里生成「方向确认 · 受阻 60%」卡，
   但 dsh 实际已写出 `probe3.html` 并回复「Created probe3.html with the PROBE3 heading」。
   卡停在受阻、活儿已经干完。
3. **续跑把文件写到主仓根**：15:45 那次没有产生新的 `[OCGO-BRIDGE] invoke`（宿主日志里
   只有 15:07 那一条），即它是**同一个会话的续跑**；它写出的 `probe3.html`（138 B，
   15:45:59）落在**主仓根** `/workspace/src/`，不在任何 worktree 里。
   与「未获批准前不落主树」冲突。已把该文件移出仓外（`/tmp/tenon-artifacts/probe3.html`），
   主仓 `git status` 回到 292 行（跑前 287 + 本轮新增单测/证据 5 项）。
   未能定因：会话续跑时 cwd 是否仍是 worktree、还是被主仓绝对路径带回主树 —— 需另案查。

### 定因（同日 16:15 补记，三条同源）

原记录把第 2、3 条当成两件事、且把第 3 条写成「续跑丢了 worktree」。读库与读会话后翻案：
**它们是同一条消息被两套执行通道各处理一次**。

时间线（宿主库 `relay.db` + `~/.dsh/sessions/`，均为原始读数）：

| 时刻 | 事件 | 证据 |
| :--- | :--- | :--- |
| 15:45:48.239 | 手机发来 `@dsh-teahouse-mobile write one small html file probe3.html…` | `MessageEntity` mine=0 sender=25222a45 |
| 15:45:54.927 | dsh 会话 `session-dfd50c8e…` 被写入一条 user prompt（`agent/inbox/spliced`） | 该会话 `session.v4.jsonl.zstd` |
| 15:45:54.992 | 宿主发出 `direction-ask` rev=1 state=**blocked** 卡 | `MessageEntity.card_payload` |
| 15:45:59 | `probe3.html`（138 B）落主仓根 | `stat birth=15:45:59` |
| 15:46:00 | dsh 回「Created probe3.html…」并 turn/end | 同一会话 |

- **通道一（编排器）**：消息走 `onAgentPrompt` → `handlePrompt`。上一单收在 `prototype`
  阶段（15:30 的 rev=4 prototype 卡），于是命中「原型待选期来的正文**不是新派单**」分支，
  只出方向确认卡、`blocked` —— **编排器这一侧的行为是对的**，它没打算派活。
- **通道二（桥）**：同一条消息也进了 `/v1/inbox`，带 `agentHint.targetSessionId`
  （`AgentSessionPick` 选「最近活跃」会话）。外部桥 `dsh-tenon` 按 hint 把 prompt 注入那个
  会话 —— 而它正是 **14:03:35 建、cwd 就是主仓**的 `session-dfd50c8e`
  （会话首行 `{"type":"session","cwd":"/workspace/src","createdAt":1794290615684}`）。
  桥改不了既有会话的 cwd，于是 dsh 按主仓写文件。
- 对照：15:07 那次派单的三个 dsh 会话 cwd 全是 `/private/tmp/tenon-ws/t-1-1e6a963f`，
  说明**沙箱隔离本身是好的**；越界只发生在「编排器没接这单、桥却接了」的缝里。

所以：卡停 blocked 不是卡刷新滞后，主仓落文件也不是沙箱失效 —— 是**编排器的判定对桥不可见**，
两边各按自己的理解处理了同一条消息。

**处置**：a2r 增加第五判据 `prototype-no-main-tree-write`（仓库根本轮新建未跟踪文件即红），
源码守卫 `review-gate-watches-main-tree-writes` 防它被删；反向探针已验证（建文件 → 红，删 → 绿）。
双通道重复执行的**修法**未动，见下节待决。
4. 旧目录 `downloads/prototype.html/` 与 `prototype(1).html` 并存期间，新同名文件只能拿
   `prototype(2)(3)…` —— 不阻塞使用，但界面上「同名多份」会持续累积，属可接受的取舍。

## 五、待决：同一句 @ 被两套通道各执行一次

定因之后要选一条修法。三条路，代价不同，收益也不同 —— 不在本轮擅自开工，因为有两条要动
对外契约，而用户机器上跑的桥是独立进程，不会随本仓升级。

| 选项 | 做法 | 代价 | 收益 |
| :--- | :--- | :--- | :--- |
| A（推荐） | 编排器判定「这单我不接（原型待选/讨论/退单）」时登记消息 id；`/v1/inbox` 的 `agentHint` 带 `orchestratorHandled=true`，桥据此跳过 | 协议新键 + **桥必须升级**；不升级则无效 | 根治「卡说受阻、活已干完」；也顺带堵住主仓落文件 |
| B | hint 带 `boundWsPath`，桥注入前校验目标会话 cwd 是否落在绑定工作区内 | 协议新键 + 桥必须升级 | 只防越界，不解决卡与事实相反 |
| C | 只留第五判据（**本轮已做**） | 无 | 事故照发生，但事后一定看得见，不再靠人 `git status` 发现 |

选 A 的理由：用户真正受伤的不是「文件写哪」，是**屏上写着「受阻 60%」，活其实已经干完**。
B 让文件落回沙箱，屏上仍然说假话。

## 六、证据文件

- `measurements.json` —— 本轮全部读数（派单、布局、探针、判据、遗留问题）。
- 截图：`/tmp/phone.png`（文件卡：已接收 + 已存到 …/prototype(2).html + 打开/分享）、
  `/tmp/chrome.png`（Chrome 里的 PROBE-2）。截图未入库，读数已转录到上面。
