# DSH-Tidings 桥接适配器与宿主控制面通信规范 (v1.0)
## —— 智能体接入 Tidings 协同网络的标准通信契约与安全准则

> **定义**：`dsh-tidings` 是运行在开发机宿主上的**无状态桥接插件（Bridge Plugin）**。它将本地 AI 智能体（如 DSH、Gemini CLI、Claude Code）的底层执行状态、事件流与敏感操作，转译为 Tidings 局域网 P2P 标准协议，并通过本地回环 HTTP 端口与 Tidings Desktop 宿主控制面交互。

---

## 一、 整体拓扑与四层生态分工

```text
┌────────────────────────────────────────────────────────────────────────┐
│ 1. 智能体执行内核 (Execution Kernel, 如 DSH / Gemini)                  │
│    • 物理磁盘文件读写、本地终端 Shell 执行、大模型推理调度                 │
│    • 维护真实会话日志: ~/.dsh/sessions/<ws>/<session>/session.v4.jsonl.zstd│
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │ 本地进程 IPC / SDK 钩子
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 2. 桥接适配器 (Bridge Plugin, 如 dsh-tidings / gemini-relay)             │
│    • 定位: 无状态翻译官与事件中继器 (不持核心台账，不做安全决策)          │
│    • 职责: 双向协议转译、事件流切片、心跳与会话生命周期通知              │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │ 本地安全回环: http://127.0.0.1:8788
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 3. 宿主控制面 (Host Control Plane, Tidings Desktop / agentNode)          │
│    • 定位: 宿主安全守门人 & 局域网广播节点                               │
│    • 职责: 持有 4 级角色台账 (agent-grants.json)、白名单过滤 (allows)     │
│    • 目录防重: 依据 canonical_path 实施工作区 1:1 防重互斥                │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │ 加密 P2P 局域网信道 (UDP/TCP, agn1/ams1)
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 4. 远程协同终端 (Collaborative UI, Tidings 手机端 / 协作者端)             │
│    • 工作区协同群聊、任务工单线程、阻断审批卡片、运行态进度条与全局打断   │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 二、 桥接适配器的“三不做”安全准则

为了防止插件被攻击导致宿主机器被提权或突破防线，所有接入 Tidings 控制面的适配器（`*-relay`）必须恪守以下三条铁律：

1. **不做权限决定（Decisions Outside Bridge）**：
   * 适配器本身严禁私自判断“远端手机能否下发 Prompt”、“某个命令是否允许跳过审批”；
   * 所有的授权校验、角色判定（所有者/管理员/协作者/只读访客）一律由 Tidings 宿主控制面（`agentNode`）的 `allows()` 统一裁决。
2. **不持有持久化私账（Stateless by Design）**：
   * 授权白名单、设备公钥、配对关系由 Tidings 宿主统一落盘于 `agent-grants.json` 与 SQLite；
   * 桥接插件随 Agent 进程生命周期即生即灭，不维护分裂的权限私账。
3. **不直接碰外部物理网络（Isolated to Loopback）**：
   * 桥接插件只与本机回环地址（`http://127.0.0.1:8788`）通信；
   * 外部设备的 Wi-Fi 发现、UDP 打洞、TCP 帧分片、端到端加密与防重放，全部委托给 Tidings 的传输中枢处理。

---

## 三、 本地回环接口规范 (`http://127.0.0.1:8788`)

### 1. 事件与状态上报 (`POST /v1/inbound`)
适配器将智能体的生命周期转译为标准 JSON 信封投递：

* **工作区宣告 (`agent-workspace`)**：
  ```json
  {
    "type": "agent-workspace",
    "payload": {
      "wsId": "ws-my-project",
      "wsTitle": "my-project",
      "wsPath": "/workspace/src/my-project",
      "agentKind": "DSH",
      "online": true,
      "at": 1729000000000
    }
  }
  ```

* **任务会话状态 (`agent-session`)**：
  ```json
  {
    "type": "agent-session",
    "payload": {
      "sessionId": "sess-task-01",
      "agentKind": "DSH",
      "state": "busy",
      "alias": "#修复支付崩溃",
      "title": "处理支付回调时的空指针异常",
      "wsId": "ws-my-project",
      "wsTitle": "my-project",
      "wsPath": "/workspace/src/my-project",
      "startedAt": 1729000000000
    }
  }
  ```

* **阻断执行审批 (`agent-approval`)**：
  ```json
  {
    "type": "agent-approval",
    "payload": {
      "approvalId": "appr-exec-101",
      "sessionId": "sess-task-01",
      "tool": "terminal",
      "summary": "执行数据库迁移与热补丁: ./gradlew flywayMigrate",
      "expiresAt": 1729000120000
    }
  }
  ```

### 2. 用户指令与审批结果下拉 (`GET /v1/inbox`)
适配器通过轮询或长连接拉取来自远端设备的指令与批复：
* **查询参数**：`since=<timestamp>&limit=50`
* **响应格式**：
  ```json
  {
    "items": [
      {
        "type": "agent-ack",
        "payload": {
          "ackFor": "appr-exec-101",
          "status": "approved",
          "decidedBy": "📱 iPhone 15 (所有者)"
        }
      },
      {
        "type": "msg",
        "payload": {
          "text": "@DSH 继续优化索引配置",
          "sender": "node-phone-uuid"
        }
      }
    ]
  }
  ```

### 3. 主动下发消息 (`POST /v1/send`)
智能体向群聊或指定对端推送文本、文件或执行进度：
```json
{
  "to": "192.0.2.22",
  "text": "已成功完成代码重构，测试用例全部通过！"
}
```

---

## 四、 多 Agent 生态接入范式

当宿主电脑引入新的智能体（如 Gemini CLI）时，无需修改 Tidings 核心代码，只需运行对应的桥接脚本：
1. 启动 `gemini-relay`，以 `agentKind = "Gemini"` 向 `127.0.0.1:8788` 宣告属于同一 `wsId` 的工作区和会话；
2. 手机端与桌面端的工作区协同群自动感知新增的 `🤖 Gemini (Resident Bot)`；
3. 多智能体在同一项目下共享工作区物理上下文，实现流水线分工协作。
