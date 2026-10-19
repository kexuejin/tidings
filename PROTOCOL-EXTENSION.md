# 本地 Agent 协同协议扩展规范 v1（PPE-1）

> 目标：在**保持与局域网点对点通信既有帧完全兼容**的前提下，为 捎话 (Tidings) / `agentNode` / 宿主 Agent 插件
> 提供 **Agent 会话 · 任务 · 事件 · 审批 · 工作区** 等原生协同语义规范。

---

## 1. 结论先行：扩展是安全的，但"沉默"不是

上游对**未知 message type** 的处理链已逐行核实：

| 位置 | 代码 | 行为 |
|---|---|---|
| `src/main/net/codec.ts:63` | `const KNOWN_TYPES = new Set<string>(Object.values(MSG_TYPES))` | 已知类型全集来自上游常量表 |
| `src/main/net/codec.ts:517` | `const known = KNOWN_TYPES.has(raw.type)` | 未知 type → `known=false`，**不抛错** |
| `src/main/net/codec.ts:457` | `validatePayload` 的 `default:` 分支返回 `isRecord(payload)` | 未知 type 的 payload 只校验"是对象"，字段不校验 |
| `src/main/net/messenger.ts:71-72` | `this.udp.on('envelope', (env, known, rinfo) => { if (known) this.handle(env, rinfo) })` | **未知 type 直接不进 handle** |
| `src/main/net/messenger.ts:159` | `if (!result.ok \|\| !result.known) return false` | 不发 ACK、不入队、不落库 |

**推论（决定整个扩展策略）**

1. 新节点发 `agent-*` 给老节点 → 老节点**解码成功、静默丢弃、不崩溃**。vertical extension 安全。
2. 反向同样成立：新节点能读全部既有 15 个已知类型。**没有单向锁死**。
3. 代价是**不可见**：老节点不会告诉发送方"我不认识"。发送方无法区分"对方还没处理"和"对方根本不认识"。
   → 必须由**发送方自己**做能力协商（§4）与降级文案（§10），不能指望对端回报。

---

## 2. 三条铁律

1. **绝不向 `Envelope` 加字段。** `hasOnlyKeys` 多一个键即整帧丢弃（已实测，`group-text` 多出 `type` 键是 golden suite 唯一的失败用例）。信封恒为 `{v, type, id, from, ts, payload}`，`v === 1`。
2. **绝不把长期 agent JSON 塞进 `group-text` / `text`。** 这类"借用"在 7 天补发队列、离线同步、`messages.seq` 单调递增序列里都会变成技术债，且无法表达游标语义。人类可读的通知才走 `text`（§3 优先级 1）。
3. **绝不在 `agentNode` 里写第二套编解码器。** headless `agentNode` 与移动端共用同一个 `:core` codec 模块；差异只落在平台契约层。

> 规范声明：本规范是**协议行为**的独立描述，实现方 clean-room 独立实现。

---

## 3. 语义承载的三级优先级

| 级 | 手段 | 何时用 | 老节点可见性 |
|---|---|---|---|
| 1 | 既有 `text` / `group-text` | 需要对方**看得见**的人类可读通知（如"Agent 已离线，指令将在其上线后送达"） | ✅ 正常显示 |
| 2 | 新 `agent-*` 类型 + `caps` | 机器语义：会话、游标、任务、审批、产物 | ❌ 静默丢弃（预期行为） |
| 3 | 既有 `file-msg` | 产物**二进制体**、大文本溢出 | ✅ 走 TCP 17879 |

第 1 级不是妥协，是**有意的双写**：通知走 `text` 让老节点用户知情，语义走 `agent-*` 让新节点可机读。
禁止只用第 1 级承载机器语义（人类解析 JSON 是错的设计），也禁止只用第 2 级承载用户必须知道的告知。

---

## 4. 能力协商（caps）

沿用上游 caps 键风格（`tw1`/`mrec1`/`fd1`/`tbl1`/`gr1`/…），新增三个：

| 键 | 含义 | 解锁能力 |
|---|---|---|
| `ag1` | **agent 节点身份标记**（已有实现，本表原先漏记） | 不携带任何帧语义，只用于在节点列表/会话列表里把 agent 节点与人对端区分开（`MsgTypes.CAP_AGENT`、`Profile.isAgentNode()`）。它**不能**替代 `agn1` 做帧能力协商 |
| `agn1` | **agent 会话语义基线**（必选前置） | 任何 `agent-*` 帧发送给新节点前，必须先确认对方声明了 `agn1` |
| `agq1` | 任务队列 + 审批语义 | `agent-queue`、`agent-run`、`agent-approval`、`agent-cancel`、`agent-ack` |
| `age1` | 可恢复事件流 / 游标语义 | `agent-event` 批量投递、`GET /v1/inbox?cursor=`（**仍未声明**，见下） |
| `aws1` | 工作区身份与宣告帧（D1/D2/D3） | `agent-session` 的四个可选键 + `agent-workspace` 帧（见 `docs/PROTOCOL-AGENT-WORKSPACE.md`） |
| `agr1` | 授权回路（D7） | 三张 grant 帧；**宿主在投递 agent 帧前按策略过滤** —— 没授权 = 没数据，沉默 ≠ 拒绝 |
| `agp1` | 会话分页（D8） | `agent-session-page`：设备向宿主要一页历史会话（与 `agn1` 是两个方向的能力） |
| `att1` | agent 事件附件卡片（task-45） | 发送方据此决定要不要在 `agent-event` 里带 `attachment`；**不声明就只发文本占位** |
| `lnk1` | 长连接下行（K1/K2） | 由**主机**声明；手机据此决定要不要拨号（见 `ARCHITECTURE.md` §5.4） |

> 兼容规则不变，但补一条**主动**保障：新字段只在「对端声明了对应 cap」时才发。`att1` 就是这么用的 —— 不靠"指望旧端恰好宽容"，而是先看 caps 再决定带不带。

规则：

- caps 是**协商标记**，不是授权。上游不会、也不需要替我们执行权限检查；安全边界仍由 Bearer token + 本地监听范围承担。
- 未声明对应 cap ⇒ **禁止**发送该类帧。发送方在应用层拦截并走降级路径（§10），不得"先发再说"。
- **`agentNode` 不得无条件宣告 caps。** 移动端基线是 `tw1` + `mrec1`；caps 只能**在实际实现对应能力后**逐个追加。这是 caps 机制的固有风险（声明了却没实现 = 老用户被静默吞帧），因此实现顺序必须与 §12 分期严格一致。
- `agn1` 命名沿用上游"能力名 + 版本号"惯例，v1 后不兼容变更一律开新键（`agn2`），**不改旧键语义**。

---

## 5. 载荷规范

- **扁平结构**，禁止嵌套对象、禁止 `@SerialName` 多态判别（多态 `type` 判别字段正是 golden 失败用例的成因）。
- `@Serializable`，模块级 `encodeDefaults = false`（默认值不落到线上）。
- 键名短、可读、camelCase；时间一律 **epoch 毫秒整数**（不用 ISO-8601 字符串，每事件省 ~14 字节）；枚举用短码字符串。
- UUID 过长时允许使用**缩写 id**（见 §7 字节预算）；缩写表由发送方在 `agent-session` 中一次性声明，其余帧引用。
- 每个类型的 payload 字段集合是**严格白名单**（与上游 `hasOnlyKeys` 对称），实现时必须逐一列出 `EXPECTED_KEYS` 常量并被 golden 用例覆盖。

---

## 6. 消息类型（8 个）

全部为扁平 payload；`agent-*` 前缀确保不与上游 15 个已知类型冲突。

### 6.1 `agent-session` — 会话宣告 / 状态变更（cap: `agn1`）

会话是"某台 agent 机器上的一次运行中会话"的可见投影。

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionId` | string | ✅ | 会话稳定标识（宿主分配，非每次重连重生成） |
| `alias` | string | | 展示名，如 `refactor runner` |
| `agentKind` | string | | `codex` / `claude` / … （宿主自定义） |
| `state` | string | | `idle` \| `busy` \| `done` \| `error` \| `archived` \| `closed` |
| `title` | string | | 会话标题（工作区首行或用户命名） |
| `startedAt` | int | | epoch ms |

```json
{"v":1,"type":"agent-session","id":"…","from":"<nodeId>","ts":1737000000123,
 "payload":{"sessionId":"s-1a97a6f3","alias":"refactor runner","agentKind":"codex","state":"busy"}}
```

### 6.2 `agent-event` — 事件流条目（cap: `agn1` + `age1`）

事件流的**原子条目**。批量投递由 §7 定义；单帧也可只带一条。

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionId` | string | ✅ | 归属会话 |
| `cursor` | int | ✅ | 事件流单调游标（epoch ms 生成，**只在同一 (node,session) 内比较**） |
| `eventType` | string | ✅ | `session.started` / `tool.started` / `tool.failed` / `artifact.ready` / `assistant.text` |
| `summary` | string | | 一行摘要（原型 Agent 屏"事件流"用） |
| `body` | string | | 正文，可长；超预算时必须走 `file-msg` |
| `ref` | string | | 关联 id（runId / artifactId / toolName） |

### 6.3 `agent-queue` — 任务入队 / 出队（cap: `agq1`）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `taskId` | string | ✅ | 任务 id |
| `sessionId` | string | | 为空表示"待分配到新会话" |
| `op` | string | ✅ | `enqueue` \| `dequeue` |
| `prompt` | string | | 指令正文（`enqueue` 时） |
| `queuedAt` | int | | epoch ms |

### 6.4 `agent-run` — 执行状态（cap: `agq1`）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionId` | string | ✅ | |
| `runId` | string | ✅ | 单次执行 id |
| `state` | string | ✅ | `running` \| `succeeded` \| `failed` \| `cancelled` |
| `at` | int | | epoch ms |
| `note` | string | | 失败摘要 |

### 6.5 `agent-approval` — 审批请求（cap: `agq1`）⚠️ 关键控制路径

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `approvalId` | string | ✅ | |
| `sessionId` | string | ✅ | |
| `tool` | string | | 待审批工具名 |
| `summary` | string | | 一句话说明 |
| `expiresAt` | int | ✅ | epoch ms；过期后双方均视为失效 |
| `choices` | string | | CSV：`allow,deny` / `allow,deny,always` |

> 审批**不得**降级为"已送达"（§10）。

### 6.6 `agent-cancel` — 取消（cap: `agq1`）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionId` | string | ✅ | |
| `runId` | string | | 省略 = 取消整个会话 |
| `reason` | string | | |

> 禁止复用 `file-msg.ctl` 的 accept/decline/cancel —— 那三个值语义是**文件领取**，不是任务控制。

### 6.7 `agent-artifact` — 产物元数据（cap: `agn1`）

**只描述产物，不传二进制。** 二进制走 `file-msg`。

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionId` | string | ✅ | |
| `artifactId` | string | ✅ | |
| `fileId` | string | | 指向已发送的 `file-msg.id` |
| `name` | string | ✅ | 如 `report.md` |
| `bytes` | int | | 文件大小 |
| `mime` | string | | |

### 6.8 `agent-ack` — 队列 / 审批回执（cap: `agq1`）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `ackOf` | string | ✅ | 被确认的 `msgId` |
| `kind` | string | ✅ | `queued` \| `approved` \| `denied` \| `cancelled` \| `received` |
| `at` | int | | epoch ms |

### 6.9 `group` 的 `memberNicks`（可选键，**纯展示**）

**背景**：群成员在每台设备上只有 nodeId。要让成员列表显示真名，只能靠"自己发现过那个节点"
（同网段 mDNS / 曾经直连）。跨网时不成立 —— 真机现场：红米走蜂窝加入群，成员列表里
`demo-mac-97` 正常（配对过），另外两人只显示 `f6a7b8c9` / `a7b8c9da` 这样的前 8 位；
**而桌面端四个都正常**，因为它同一时刻在局域网里认识所有人。同一个群两端显示不一致。

**做法**：`group{op:"info"}` 的 `group` 对象里加一个可选键：

```json
{"op":"info","group":{
  "groupId":"…","name":"…","members":["A","B","C"],"rev":3,"updatedBy":"A","updatedTs":1789…,
  "memberNicks":{"A":"demo-mac-97","B":"panda"}
}}
```

| 方面 | 约定 |
|---|---|
| 类型 | 对象，`nodeId → 昵称` |
| 必填 | **否**。旧端不发 ⇒ 成员行退回 nodeId 前 8 位（与以前一致，不更差） |
| 键约束 | **必须属于 `members`**；越界键 ⇒ 整帧不合法 |
| 值约束 | `Limits.NICK`（32 字符）；超长/非串 ⇒ 整帧不合法（不截断） |
| 空对象 | 等同"没带" ⇒ 解码为 null |
| 语义 | **只用于显示**。不参与成员判定、不参与权限 —— 成员身份永远只认 `members` / `ownerId` / `adminIds`，伪造昵称没有任何特权收益 |
| 发送侧 | 只填**本机确实知道**的昵称（自己的 + 认识的对端）；不知道的不编，由接收方自己补或降级 |
| 接收侧优先级 | 自己 > 本机认识的对端 > `memberNicks` > nodeId 前 8 位（本机知识更新鲜） |

**为什么越界键要拒整帧而不是丢弃那个键**：前端拿到 `memberNicks` 会把它们当"群成员"显示，
一个越界键等于让对端往我的成员列表里塞一个不存在的成员。拒整帧比"悄悄丢掉一个键"
更容易在联调时暴露问题。

测试：`core/src/commonTest/.../GroupProtocolTest.kt` 的 `memberNicks*` 五条
（往返 / 缺省为 null / 越界键拒帧 / 形状与长度拒帧 / 空对象为 null）。

### 6.10 群文本的 `senderId` / `reasoning`（可选键，**纯展示**）

**背景（两个用户可见缺陷，同一条根因）**：agent 事件由桌面端转发进群时，信封 `from` 永远是
**桌面这台设备**，而这条话其实是 agent 说的；同时转发只带**剥掉思考块之后的正文**，
思考过程只落在桌面自己的库里。于是同一个群：

1. 手机端把 agent 的话显示成「桌面端发的」，桌面端显示 `agent:DSH` —— 两端发送者不一致，
   用户以为群里多了一个人；
2. 桌面端气泡有「思考」折叠块，手机端没有 —— 用户看到的是「过程只在桌面，手机只剩最后一句」。

**做法**：`msg`/`kind:"group-text"` 的载荷加两个可选键：

```json
{"kind":"group-text","text":"1509 项测试全部通过。","groupId":"…","groupRev":7,
 "senderId":"agent:dsh","reasoning":"先把 FTS 的写入路径读一遍…"}
```

| 方面 | `senderId` | `reasoning` |
|---|---|---|
| 类型 | 字符串 | 字符串 |
| 必填 | 否（旧端不发 ⇒ 接收方按信封 `from` 处理，与以前一致） | 否（旧端不发 ⇒ 没有思考块） |
| 取值 | 真人 = nodeId；agent = `agent:<小写 kind>` | 就是桌面端 `MessageUi.reasoning` 那一份 |
| 约束 | `Limits.ID`（64 字符）、非空 | UTF-8 字节 ≤ `Limits.GROUP_REASONING_BYTES`（24 KiB）、非空 |
| 语义 | **只用于显示归属**，不参与鉴权 | **只用于显示**，不参与任何判定 |
| 超长 | 拒帧 | 拒帧；发送侧必须先截断（`NodeOutbound.clampGroupReasoning`） |

**⚠️ 加可选键必须同步加进 `CodecMsg.GROUP_TEXT_KEYS`。** 群文本走的是**严格字段白名单**
（多一个键整帧丢弃，golden vector `neg-group-text-extra-field` 守着这条）。
`senderId` 第一版就踩了这个坑：键加进了 data class 与编码器，**漏进白名单**，
于是 agent 转发进群的每一条都被对端判成 extra-field 丢掉 ——
发送侧编码成功、本地落库成功、`SendOutcome.SENT`，接收侧一条都没有。

**为什么 `reasoning` 是独立字段，而不是把 ` thinking…</think>` 塞回 `text`**：正文与思考各有各的
上限。混在 `text` 里时，正文 + 思考一起顶到通道上限（UDP 800 / TCP 4096 字节）会被整帧拒收 ——
那正是「带思考的原文发出去、对端反而收不到」的形态。分开之后思考有 24 KiB 预算，
正文仍受原上限约束，两者互不挤占。

**安全边界**：两个键都只影响显示。任何群成员本来就能在群里说任何话，把 `senderId` 写成别人的
nodeId 只会让那条气泡挂在别人名下（本协议不防冒名，群内成员互信）；`reasoning` 同理，
伪造一个思考块没有任何特权收益。所以只校验长度形态，不做归属校验。

测试：`core/src/commonTest/.../CodecTest.kt` 的
`groupTextWithSenderIdSurvivesInboundValidation`（**回归：走解码而不是只断言 JSON 里有键**）、
`groupTextCarriesReasoningAlongsideTheAnswer`、`groupTextWithoutReasoningKeepsTheOldShape`、
`oversizedReasoningIsRejected`；`GroupReasoningClampTest.kt` 四条（原样 / 空为 null /
按字节截断且过校验 / 不在代理对中间切）。

---

## 7. 传输预算与批量上限

| 事实 | 值 | 来源 |
|---|---|---|
| UDP 单包硬上限 | **800 字节** | `TEXT_UDP_LIMIT` |
| TCP 文本上限（单聊 `kind:"text"`） | 4096 字节 | `TEXT_TCP_LIMIT` |
| TCP 文本上限（**群文本** `kind:"group-text"`） | **32 KiB** | `Limits.GROUP_TEXT_TCP_LIMIT`，见 **§7.1** |
| 上限判定单位 | **UTF-8 字节数**，不是字符数 | 已对上游源码核实 |
| 超限行为 | 自动由 UDP 回退 TCP 17879（4 字节大端长度前缀） | 本项目实测 |

实测样本（原型 Agent 屏同款内容）：

| 样本 | 字节 |
|---|---|
| 单条 `agent-event`（完整 UUID + 中文） | 281 B |
| 同上，缩写 id | 218 B |
| 批量 K=1 | 211 B |
| 批量 K=2 | 280 B |
| K=3 / K=4 / K=5 | 349 / 418 / 487 B |

拟合：`bytes ≈ 142 + 69·K`（K ≥ 1，缩写 id 前提）。

据此定批量上限：

| 通道 | 公式上限 | **本规范取值** | 余量 |
|---|---|---|---|
| UDP 17878 | K ≤ 9（763 B） | **K = 5** | 约 1/3，留给真实 body 变长 |
| TCP 17879 | K ≤ 57（4075 B） | **K = 50** | 同上 |

> 这些数字来自**单一样本拟合**，不构成上界证明。真实事件的 `body` 可任意长。
> 因此 §11 的 golden budget 用例必须用**最坏样本**（最长 `body`、最长 `summary`）跑，而不是用上面的样本。

### 7.1 群文本在 TCP 上的上限从 4096 抬到 32 KiB（`GROUP_TEXT_TCP_LIMIT`）

| 通道 | 旧上限 | **新上限** | 说明 |
|---|---|---|---|
| UDP 17878 | 800 B | 800 B（不变） | 防 IP 分片，仍按调用方给的 800 卡死 |
| TCP / 链路 | 4096 B | **32 KiB** | 与 `agent-event.body` 同一档思路（那边早就放宽到 64 KiB） |
| 单聊 `kind:"text"` | 4096 B | 4096 B（不变） | 用户手打的字，4096 足够，不跟着放大攻击面 |

**为什么必须抬（真机数据，不是推测）**：`TEXT_TCP_LIMIT = 4096` 是 UDP 时代的产物
（UDP 800 防分片，TCP 翻 5 倍而已），但 agent 的长回复动辄 4000+ 字节。实测桌面端库里
**140 条群消息**超过 4096 字节（最大 9902 字节），全部被接收侧
`utf8ByteLength(text) > textLimit` 判成 `bad-payload` **整帧丢弃** ——
手机端一条都看不到，桌面端却好好地留着。

反证很干净：手机侧**没有任何一条**超过 4096 字节，最大一条恰好 **3970 字节**。
不是「偶尔丢」，是「到 4096 就整齐地断掉」。用户明确问的那条「全部完成。」
（2530 字 / **4239 字节**）只超了 143 字节。

**为什么是 32 KiB 而不是 65536**：一帧的硬上限是 64 KiB（`Link.MAX_FRAME_BYTES`），
而群文本还可以同时带 §6.10 的 `reasoning`（24 KiB）—— 正文必须先给它们留出余量。

**两条配套纪律**（缺一条就还会静默丢）：

1. **发送侧截断**（`NodeOutbound.clampGroupText`）：让「发送侧永远不产出注定被拒的帧」
   成为不变量，而不是「希望 agent 别写太长」。
2. **队列重放按 TCP 上限解码**（`Messenger.flushQueue` / `requeue`）：队列里存的是
   *已经发过一次*的信封，重放走链路/TCP，不是数据报。原来用 `Codec.decode`（UDP 那套）
   读它，会把长群消息判成坏帧然后 `queueStore.remove` —— 比「发不出去」更糟：**它被删了**。

> 兼容性：旧接收端仍按 4096 卡，长消息照样收不到 —— 与改动前一样，不是新退化。
> 两端需同版本。

---

## 8. 控制面与插件对齐

`agentNode` 的 HTTP 控制面需要两处明确化：

### 8.1 `POST /v1/send` 扩 kind，而非新增编解码

`kind` 增加 `agent-event` / `agent-approval` / `agent-cancel` 等取值；**沿用同一套 JSON over HTTP**。
不引入二进制通道，不引入第二套 codec。

> 注：P0 验收命令 `curl -X POST localhost:8788/v1/send -d '{"to":"电脑IP","text":"hi"}'`
> **缺少 `kind` 字段**，与协议的正式 schema 不一致。实现时以本节 schema 为准，并同步修正验收命令。

### 8.2 `GET /v1/inbox` 引入 cursor，与 `since` 并存

`since=<ts>` 是**时间戳**语义；`agent-event.cursor` 是**事件流游标**语义。两者混用会产生"同 ts 多事件丢失"或重复投递。

- 保留 `?since=<ts>`（老用法不变）
- 新增 `?cursor=<int>`（事件流续读）
- 两者互斥；同时出现返回 400

### 8.3 宿主 agent 插件

插件继续是 **JSON HTTP 客户端**：不实现发现、编解码、ACK、文件分片。
它做的是把宿主 agent 的会话事件翻译成 `agent-event`，把审批翻译成 `agent-approval`，
并在**能力不匹配时**（§10）拒绝发送而不是静默丢弃。

---

## 9. 存储模型

**明确禁止**把 agent 事件写进 `messages` 表：

- 会污染 IM 本地单调递增的 `messages.seq`
- 会污染未读计数与按日期分组
- 游标语义与 `dedup(msg_id, peer_id)` 完全不同

新增三张表（SQLDelight，需写 migration）：

```sql
CREATE TABLE agent_sessions (
  node_id   TEXT NOT NULL,
  session_id TEXT NOT NULL,
  alias TEXT, agent_kind TEXT, state TEXT, title TEXT, started_at INTEGER,
  PRIMARY KEY (node_id, session_id)
);

CREATE TABLE agent_events (
  node_id TEXT NOT NULL, session_id TEXT NOT NULL, cursor INTEGER NOT NULL,
  ts INTEGER NOT NULL, type TEXT NOT NULL, payload_json TEXT NOT NULL,
  PRIMARY KEY (node_id, session_id, cursor)
);

CREATE TABLE agent_approvals (
  id TEXT PRIMARY KEY, node_id TEXT NOT NULL, session_id TEXT NOT NULL,
  tool TEXT, summary TEXT, expires_at INTEGER NOT NULL, state TEXT NOT NULL
);
```

- 原 7 张表（`peers`/`conversations`/`messages`/`dedup`/`send_queue`/`transfers`/`groups`）保持不变。
- 「人话通知落进会话」是**显式的转换动作**（`agent-event` → 一条 `messages` 行），不是自动副作用。

---

## 10. 降级原则与文案

| 情形 | 判定 | UI 文案方向 |
|---|---|---|
| 对方未声明 `agn1` | **降级**（不是失败） | "对方版本不支持 Agent 会话，已改为普通消息送达" |
| 草稿超 800 B | 透明 | 沿用现有"将自动改走 TCP"提示（原型已实现） |
| 审批 / 取消**无法送达** | **未送达** | 必须显示"未送达"，**禁止**显示"已送达"或任何成功暗示 |
| 结果待确认 | 未确认 | "待确认"优于"已送达" |

判定原则：**能力声明保守，文案更保守。** 任何一个"已送达"都必须有真实回执
（`agent-ack`、或 `send_queue` 确认入队）支撑。

---

## 11. Golden Vector 护栏

位置（**以实际实现为准**）：`core/src/commonTest/kotlin/app/relay/core/protocol/`
下的两个 Kotlin 文件 —— `GoldenVectorsData.kt` 存向量本身，`GoldenVectorTest.kt` 跑断言。
向量是 Kotlin 源码里的字符串常量，不是 `resources/golden/NN-<type>.json` 那种外部文件；
这样它们参与编译期检查、也不需要额外的资源加载路径。

向量分三组，**全部通过**才允许注册新的 Kotlin message type：

| 组 | 条数 | 来源 | 断言 |
|---|---|---|---|
| `GOLDEN_VECTORS` | 14 | TypeScript 参考实现（teahouse 0.61.1）生成，**不要手改** | parity：同一信封两侧逐字节相同；`known` / `decodes` / `reason` 与 TS 结论一致 |
| `OLD_NODE_EXTENSION_VERDICTS` | 2 | 同一次生成的字节，但记的是**老节点**对 `agent-session`/`agent-event` 的结论 | accept：老解码器返回 `known=false`、**不抛错**（对应 §1 的 `codec.ts:517` + `messenger.ts:159`），同时断言本端现在会**拒收**这两个老形状（§5 白名单生效） |
| `EXTENSION_VECTORS` | 5 | 本仓维护（TS 侧生成不出来） | 收发形状逐字节 + 反例（多一个键、错类型）+ 一条**仍未实现**的类型（`agent-queue`）继续守未知类型契约 |

budget 类断言（最坏样本落在 UDP 800 / TCP 4096 内）在
`AgentFramesTest.kt` 的 `aTypicalEventFitsInOneUdpDatagram` / `aMaxSummaryEventMustGoOverTcp` /
`bodyIsBoundedByTheChannelNotByAFieldLimit` 三条用例里。

**群文本是另一条预算**（§7.1）：`CodecTest.groupTextOverTheOldTcpLimitNowDecodes` /
`groupTextOverTheNewLimitIsStillRejected` / `directTextKeepsTheTcpLimit` 三条钉住
「TCP 放宽到 32 KiB、UDP 仍 800、单聊不跟着放宽」；
`GroupTextClampTest` 另外钉住发送侧截断与「正文 + 思考仍在 64 KiB 整帧内」。

保留既有的"意外序列化字段"golden 覆盖（即 `group-text` 多出 `type` 键的失败用例），
它是 §2 铁律一的活体守卫。

---

## 12. 反模式清单

| 反模式 | 为什么错 |
|---|---|
| 往 `Envelope` 加 `projectId` / `sessionId` | `hasOnlyKeys` 整帧丢弃 |
| 把长期 agent JSON 塞进 `group-text` | 污染 IM 序列与补发队列，无游标语义 |
| 复用 `file-msg.ctl` 表达任务取消 | 那是文件领取语义 |
| `agentNode` 里写第二套 codec | 双实现必然漂移 |
| 未声明 caps 就发 `agent-*` | 老节点静默吞帧，用户零感知 |
| 无回执却显示"已送达" | 违反 §10 |
| agent 事件写入 `messages` 表 | 污染 seq / 未读 / 日期分组 |

---

## 13. 文件夹传输扩展与自定义头像（av1）

### 13.1 文件夹传输扩展（Folder Transfer）

为兼容桌面端与移动端的文件交互，当用户通过文件夹选择器发送目录时：
1. **信封载荷**：在既有 `file-ctl offer` 帧增加可选字段 `isFolder: Boolean? = null`（默认省略）。
2. **元数据**：
   - `rootName`：根目录名称（或多文件聚合名 `${basename(paths[0])} 等 ${fileCount} 个文件`）；
   - `fileCount`：包含的条目总数；
   - `files` 数组中每项包含相对路径 `path` 及 `isDir: Boolean?`，目录条目 `size` 记为 0；
   - 所有入站 `path` 必须经过 `sanitizeRelativePath` 净化，杜绝路径穿越。
3. **落盘规则**：
   - 接收侧自动归档在：`{下载根目录}/{上传者显示名}/` 子目录下，保持内部相对层级；
   - 界面展现为单张聚合卡片，不展开完整子树，目录条目大小显示 `—`。

### 13.2 自定义头像能力（av1）

1. **能力声明**：节点在 Profile `caps` 中声明 `av1`（`MsgTypes.CAP_AVATAR`）。
2. **协议报文**：
   - 请求头像：`{ "type": "avatar", "payload": { "op": "get", "hash": "<sha256>" } }`（UDP 可靠帧，回送 ACK）；
   - 响应数据：`{ "type": "avatar", "payload": { "op": "data", "hash": "<sha256>", "data": "<base64_webp>" } }`；
     - 图片规范：WebP 编码，尺寸 ≤ 192×192，字节数 ≤ 32 KiB；
     - 接收侧比对 SHA-256 哈希值无误后落盘持久化缓存；
   - 响应未命中：`{ "type": "avatar", "payload": { "op": "miss", "hash": "<sha256>" } }`。
3. **架构处置**：
   - `AVATAR` 正式移入 `MsgTypes.HANDLED`，提供跨平台完整支持。

---

## 附录：类型索引

「协议层」= 进了 `MsgTypes.KNOWN`、`Codec` 有白名单校验、golden 向量覆盖；
「接收接线」= 控制器分派 + 界面显示（移动端还包含声明对应 cap）；「发送接线」= 本端有发出入口。

| type | cap | 字节量级 | 老节点行为 | 协议层 | 接收接线 | 发送接线 |
|---|---|---|---|---|---|---|
| `agent-session` | `agn1` | ~250 B | 静默丢弃 | ✅ | ✅ | ✅ |
| `agent-event` | `agn1` | ~210 B | 静默丢弃 | ✅ | ✅ | ✅ |
| `agent-queue` | `agq1` | ~230 B | 静默丢弃 | ✅ | ✅ | ✅ |
| `agent-run` | `agq1` | ~200 B | 静默丢弃 | ✅ | ✅ | ✅ |
| `agent-approval` | `agq1` | ~280 B | 静默丢弃 | ✅ | ✅ | ✅ |
| `agent-cancel` | `agq1` | ~170 B | 静默丢弃 | ✅ | ✅ | ✅ |
| `agent-artifact` | `agn1` | ~240 B | 静默丢弃 | ✅ | ✅（收元数据） | ✅ |
| `agent-ack` | `agq1` | ~150 B | 静默丢弃 | ✅ | ✅ | ✅ |
| `agent-workspace` | `aws1` | ~300 B | 静默丢弃 | ✅ | ✅ | ✅ |
| `agent-grant-request` | `agr1` | ~180 B | 静默丢弃 | ✅ | ✅ | ✅ |
| `agent-grant` | `agr1` | ~180 B | 静默丢弃 | ✅ | ✅ | ✅ |
| `agent-grant-list` | `agr1` | ~200 B | 静默丢弃 | ✅ | ✅ | ✅ |
| `agent-session-page` | `agp1` | ~180 B | 静默丢弃 | ✅ | ✅ | ✅ |
| `avatar` | `av1` | ~100 B~32 KB | 静默丢弃 | ✅ | ✅ | ✅ |

---

## 14. Phase 2.0 协议规范增补

### 14.1 远场通信与智能候选池（IPv6 + SPKI Pinning）
1. **URI 规范**：`relay://[2001:...]:17878?pin=sha256:...&cands=...`
2. **地址识别**：纯 Kotlin 位运算严格过滤 IPv6 GUA（全球单播 `2000::/3`），坚决剔除 Link-Local（`fe80::/10`）。
3. **安全锁定**：支持基于自签 ECDSA P-256 证书的公钥指纹恒定时间比对，杜绝中间人劫持。

### 14.2 思考流增量与计时解耦
1. **载荷解耦**：思考过程增量通过 `thought_delta` 独立派发，正文回答增量通过 `token_delta` 派发。
2. **界面折叠**：手机端通过 `ThinkingBlock` 展示耗时并支持聚合折叠。

### 14.3 人机回环审批流（HITL）
1. **三级风险**：`agent-approval` 载荷扩展 `riskLevel`（L1 只读、L2 修改、L3 高危）。
2. **Diff 详情**：`agent-approval` 载荷扩展 `diff` 字段，用于移动端展示待修改代码或入参差异。
3. **5分钟超时保护**：审批挂起超过 300 秒无响应自动转为 `expired` 并在宿主端安全截断。

### 14.4 工作区感知与会话分段
1. **工作区扩展**：`agent-session` 扩展 `gitBranch` 与 `segmentId`。
2. **分段重置**：切换工作区或会话分段时，宿主物理重置 Prompt 上下文，移动端保留完整滚动历史。

### 14.5 群聊多 Agent 协同（Chief Pattern）
1. **虚拟群成员**：`GroupMeta` 扩展 `virtualAgents: List<VirtualAgentMember>`。
2. **精确路由**：群聊内遵循 `@Agent` 唤醒或 Chief 编排，未被唤醒时不消耗 Token。
3. **工作态广播**：群内通过 `GroupProgress` 广播当前正在执行的任务状态。