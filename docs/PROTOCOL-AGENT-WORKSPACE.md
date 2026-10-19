# 本地 Agent 与工作区协议规范（PPE-1 扩展篇）

本文档定义 捎话 (Tidings) 系统中 **Agent、工作区 (Workspace)、会话 (Session) 及访问控制** 的线协议规范与模型定义。

---

## 1. 核心模型与层级关系

系统采用清晰的四层层级结构：

```text
节点 (Node = Device, nodeId)
  └─ Agent (agentId ?: agentKind)
       └─ 工作区 (Workspace, wsId)
            └─ 会话 (Session, sessionId)
```

- **节点（Node / Device）**：以 `nodeId`（UUIDv4）为全局唯一身份标识，一台设备对应一个物理节点。
- **Agent**：节点上承载的 AI Agent 驱动或宿主（如桌面 IDE 扩展、无头 Agent 引擎等），身份键由 `agentId ?: agentKind` 确定。一个节点内可承载多个 Agent。
- **工作区（Workspace）**：Agent 所管理的代码库或项目空间，身份键为 `(nodeId, agentKey, wsId)`，在节点与 Agent 作用域内唯一。
- **会话（Session）**：特定工作区下的对话或任务流，身份键为 `(nodeId, sessionId)`，要求在节点内唯一。

---

## 2. 报文载荷规范 (Wire Protocol)

### 2.1 `agent-session` 报文

当 Agent 宣告或流转会话状态时发送。在基础会话属性上支持以下工作区与元数据字段：

| 字段 | 类型/约束 | 必须 | 说明 |
|---|---|---|---|
| `sessionId` | string ≤64 | 是 | 会话唯一 ID |
| `state` | string ≤32 | 是 | 状态（`idle` / `running` / `waiting_approval` / `error` / `cancelled`） |
| `agentId` | string ≤64 | 否 | Agent 实例标识。缺失时退化为 `agentKind` |
| `agentKind` | string ≤32 | 否 | Agent 宿主/驱动类型标签（如 `DSH`、`opencode` 等） |
| `wsId` | string ≤64 | 否 | 工作区 ID。缺省表示未归属特定工作区 |
| `wsTitle` | string ≤200 | 否 | 工作区展示标题 |
| `wsPath` | string ≤512 | 否 | 工作区本地文件系统路径（仅详情展示，卡片主位不呈现） |
| `updatedAt` | int (epoch ms) | 否 | 宿主侧最新活动时间戳，用于多端列表排序一致性 |
| `origin` | string ≤32 | 否 | 会话来源标签（如 `"subagent"`，移动端依此可做视图过滤） |
| `blank` | bool | 否 | 是否为空壳会话（已创建但尚未产生有效交互轮次） |

### 2.2 `agent-workspace` 报文

用于主动向对端宣告工作区清单（包含当前尚无活跃会话的工作区）：

- **信封类型**：`"agent-workspace"`（进入可靠传输队列）
- **能力门控**：`aws1`（仅在对端声明 `aws1` 能力时发送）
- **载荷字段**：

```json
{
  "wsId": "ws-123456",
  "wsTitle": "my-project",
  "wsPath": "/Users/workspace/my-project",
  "agentId": "agent-runner-1",
  "agentKind": "DSH",
  "online": true,
  "sessionCount": 3,
  "at": 1729000000000,
  "order": 1
}
```

| 字段 | 类型/约束 | 必须 | 说明 |
|---|---|---|---|
| `wsId` | string ≤64 | 是 | 工作区唯一标识 |
| `wsTitle` | string ≤200 | 否 | 工作区展示名称 |
| `wsPath` | string ≤512 | 否 | 本地绝对路径 |
| `agentId` | string ≤64 | 否 | Agent 实例 ID |
| `agentKind` | string ≤32 | 否 | Agent 宿主类型 |
| `online` | bool | 否 | 工作区在线活跃状态 |
| `sessionCount` | int ≥0 | 否 | 宿主自报的会话数量 |
| `at` | int (epoch ms) | 否 | 宣告时间戳 |
| `order` | int ≥0 | 否 | 注册表持久排序权重 |

### 2.3 历史会话分页：`agent-session-page`

当移动端按需浏览历史会话时，向宿主请求指定工作区的一页会话数据：

- **信封类型**：`"agent-session-page"`（可靠单播请求）
- **能力门控**：`agp1`（设备 → 宿主）
- **载荷字段**：

```json
{
  "wsId": "ws-123456",
  "offset": 0,
  "limit": 10,
  "at": 1729000000000
}
```

**响应机制**：宿主收到请求后，按 `updatedAt` 降序使用既有的 `agent-session` 报文逐条异步回填，完成分页拉取，客户端与存储层复用既有状态机。

### 2.4 Agent 授权控制面（`agr1`）

用于控制局域网内其他设备对本机 Agent 与工作区的访问权限：

1. **三档控制策略**：
   - `auto`（默认）：所有已成功添加的配对节点均可查看本机公开的 Agent。
   - `byDevice`：按设备白名单放行，未授权设备缺省拒绝。
   - `request`：未授权设备可通过发送申请报文请求授权。

2. **授权帧类型定义**：
   - `agent-grant-request`（设备 → 宿主）：包含 `agentKey?`、`reason?`、`at?`。
   - `agent-grant`（宿主 → 设备）：包含 `state` (`approved` / `denied` / `revoked`)、`scope` (`all` / `agent`)、`agentKey?`、`at?`。
   - `agent-grant-list`（宿主 → 设备）：包含 `scope`、`agentKey?`、`total?`、`at?`。

3. **安全语义边界**：
   - 授权撤销拦截未来的数据帧投递；已送达对端持久化存储的历史数据由对端本地生命周期管理。
   - **按钮语义封顶**：按钮写什么，就只能授到什么 —— 「协同」至多读写（RW），只有「我的设备」才到 OWNER。
     授权粒度细化到**工作区 + 读写级别**（`AgentGrantEntity` 的 `ws_id` / `access` 两列，见迁移 `9.sqm`）。

### 2.5 工作区 → 群的绑定（三键匹配，歧义 fail-closed）

宿主把 agent 会话投递到群之前，按**三个键**匹配工作区与会话（工作区 id、会话 id、节点 id）。
匹配**有歧义时不投** —— 宁可让用户看到「没有新消息」，也不把某个工作区的会话投进猜出来的群。

**为什么**：群是多人可见的界面。投错群不是显示问题，是**把 A 项目的内容泄给 B 项目的人**。

### 2.6 给群补历史（水位按「会话 × 群」落盘）

- 每个「会话 × 群」维护自己的**水位**（已补到哪个 cursor），落盘；补历史按水位增量拉取，**幂等**（重复请求不会重复投递）。
- **回声保护**：补进来的历史不再触发一次「新消息」广播，避免两端互相回声刷屏。

**为什么**：水位如果按会话单键存，同一个会话被两个群引用时会互相踩；不幂等则重试会重复刷屏。

---

## 3. 控制面 HTTP API 规范

嵌入式 `agentNode` 为本地宿主进程提供标准 HTTP 交互接口：

| 方法 | 路径 | 描述 |
|---|---|---|
| `GET` | `/v1/agent/sessions` | 查询会话列表，支持 `sessionId`、`nodeId`、`wsId` 维度过滤 |
| `POST` | `/v1/agent/session` | 发布或更新会话状态 |
| `GET` | `/v1/agent/workspaces` | 查询本机已知工作区清单 |
| `POST` | `/v1/agent/workspace` | 宣告或更新工作区信息并向对端广播 |
| `GET` | `/v1/agent/grants` | 读取当前授权策略与白名单台账 |
| `PUT` | `/v1/agent/grants` | 更新授权策略或批复授权申请 |
| `GET` | `/v1/agent/session-pages` | 宿主读取待处理的历史会话分页请求队列 |
| `POST` | `/v1/agent/session-pages/resolve` | 宿主处理完指定分页请求后标记出队（幂等操作） |
