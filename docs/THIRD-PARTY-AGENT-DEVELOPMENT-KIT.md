# 捎话 Tidings · 第三方智能体开发接入套件 (Agent Development Kit & Handover Spec)

> **适用对象**：为 Tidings 开发私有智能体、外部插件（Python / Node.js / Rust / Go 等）、CLI 包装器或团队定制 Agent 的第三方开发者与开源社区协作者。  
> **文档版本**：v1.0.0-ADK (对齐 Tidings M2/M4/M7 插件架构规范)  
> **核心原则**：**零侵入、零特权、进程外独立常驻、标准 HTTP/NDJSON 通信、代码不离机、凭证不上网**。

---

## 目录
1. [系统角色与架构拓扑](#1-系统角色与架构拓扑)
2. [插件元数据清单 (`manifest.json`)](#2-插件元数据清单-manifestjson)
3. [进程外 IPC 通信协议规范](#3-进程外-ipc-通信协议规范)
   - 3.1 会话建立 (`POST /session/open`)
   - 3.2 流式任务执行 (`POST /task/execute` + NDJSON)
   - 3.3 动作与审批回执 (`POST /task/action`)
   - 3.4 进度快照查询 (`POST /task/queryProgress`)
4. [群聊结构化卡片渲染协议 (`card`)](#4-群聊结构化卡片渲染协议-card)
5. [工作区与沙箱交互规范 (Worktree & Sandbox)](#5-工作区与沙箱交互规范-worktree--sandbox)
6. [安全与防踩坑军规 (Strict Invariants)](#6-安全与防踩坑军规-strict-invariants)
7. [开箱即用参考实现 (Python Minimal Reference)](#7-开箱即用参考实现-python-minimal-reference)
8. [调试与联调流程](#8-调试与联调流程)

---

## 1. 系统角色与架构拓扑

在 Tidings 局域网协同 IM 中，第三方智能体（Third-party Agent）是一个**运行在机主本地、通过标准本地回环（`127.0.0.1`）与 Tidings 宿主通信的独立进程**。

```
+---------------------------------------------------------------------------------+
| 机主物理机 (Host Machine: macOS / Linux / Windows)                                |
|                                                                                 |
|  +---------------------------+        注册 & 转发指令 (HTTP IPC)                 |
|  | Tidings 桌面宿主          | <─────────────────────────────────────────────+  |
|  | (:agentNode 控制面)       |                                                |  |
|  | - 端口: 8848 (默认)       | ────+                                          |  |
|  +---------------------------+     │ 派单: POST /task/execute                 |  |
|       ▲                            │ 回流: NDJSON 流式事件                     |  |
|       │ P2P TLS 加密信道            ▼                                          |  |
|       │                     +----------------------------------------------+  |  |
|       ▼                     | 第三方智能体进程 (Python / Node.js / Go / Rust) |  |  |
|  +-----------------------+  | - 本地回环监听: http://127.0.0.1:<pluginPort>   |  |  |
|  | 手机端 / 协同者设备    |  | - 身份: 独立进程，持有自己业务模型与算法       |  |  |
|  | (查看卡片/审批/合流)  |  +----------------------------------------------+  |  |
|  +-----------------------+       │                                            |  |
|                                  ▼ 读写代码 (绝对隔离)                         |  |
|                             +----------------------------------------------+  |  |
|                             | 临时隔离工作区 (Git Worktree: /tmp/tidings-ws)  |  |  |
+---------------------------------------------------------------------------------+
```

- **对外表现**：智能体入驻在局域网协同群聊中，群成员可以通过 `@智能体名称 <任务诉求>` 唤醒它；
- **执行过程**：智能体在隔离的 Git Worktree 中修改代码、跑测试，向群聊推流原位卡片；
- **交付成果**：智能体提取 `patch.diff`，机主在桌面或手机端审查通过后一键合流。

---

## 2. 插件元数据清单 (`manifest.json`)

第三方 Agent 根目录下必须提供一份 `manifest.json`，在启动时向 Tidings 宿主注册：

```json
{
  "pluginId": "deepseek-coder",
  "name": "DeepSeek Coder 协作智能体",
  "version": "1.0.0",
  "type": "single",
  "entrypoint": "python3 main.py",
  "leader": {
    "agentId": "coder",
    "name": "Coder",
    "avatar": "default",
    "title": "高级代码执行员",
    "summary": "擅长代码重构、单测生成与性能优化"
  },
  "capabilities": [
    "git-edit",
    "run-test",
    "diff-review"
  ],
  "defaultWsId": "teahouse-mobile"
}
```

### 字段约束规范（发送侧不变量）：
1. `pluginId`：必须匹配正则 `^[a-z0-9][a-z0-9-]{0,63}$`（纯小写字母、数字和连字符，且不得以连字符开头）；
2. `leader.agentId`：群内 `@` 的唯一标识，同样遵循 slug 字符集约束，长度不超过 32；
3. `type`：`"single"`（单兵执行）或 `"team"`（多 Agent 内部协同团队，对外单一窗口统一由 leader 发言）。

---

## 3. 进程外 IPC 通信协议规范

插件需启动一个轻量 HTTP 服务，监听在本地回环地址（`http://127.0.0.1:<port>`），并实现以下 4 个标准端点：

### 3.1 会话建立 (`POST /session/open`)

当群聊建立或工作区绑定时，宿主调用此接口初始化会话上下文。

- **请求 Payload**：
  ```json
  {
    "wsPath": "/tmp/tidings-ws/task-8842",
    "resumeSessionId": null
  }
  ```
- **响应规格 (HTTP 200)**：
  ```json
  {
    "sessionId": "s-9f82a1b4",
    "status": "ready"
  }
  ```

---

### 3.2 流式任务执行 (`POST /task/execute`)

当群成员 `@Agent` 发送任务指令时，宿主建立 HTTP 长连接，插件以 **NDJSON（Newline-Delimited JSON，逐行 JSON）** 格式流式吐出执行事件。

- **请求 Payload**：
  ```json
  {
    "sessionId": "s-9f82a1b4",
    "prompt": "帮我把 Core 模块里的 PeerTagRouter 改成在线节点优先排序，顺便跑下单测。"
  }
  ```

- **响应格式**：`Content-Type: application/x-ndjson; charset=utf-8`  
  插件必须每推流一个事件就输出一行合法的 JSON 并紧跟换行符 `\n`。

#### 标准 NDJSON 事件行规格：

| 事件类型 (`type`) | 作用 | 完整 JSON 示例 |
|:---|:---|:---|
| `thought` | 思考链切片（用于调试抽屉，群内不直接刷屏） | `{"type":"thought","delta":"正在分析 PeerTagRouter.kt 的排序逻辑..."}\n` |
| `token` | 流式纯文本输出（聊天气泡片段） | `{"type":"token","delta":"收到，我将采用在线节点优先规则重写泛型比较器。"}\n` |
| `card` | **核心：结构化原位卡片推流**（看板、Diff、产物） | `{"type":"card","card":{...}}\n`（结构见第 4 节） |
| `tool` | 申请执行本地受限工具（触发宿主安全策略） | `{"type":"tool","toolCallId":"c-1","tool":"Bash","inputJson":"{\"cmd\":\"./gradlew :core:jvmTest\"}","riskLevel":"L2"}\n` |
| `finished` | 任务正常执行结束（必须作为终态行） | `{"type":"finished","finishReason":"stop","totalTokens":428}\n` |
| `error` | 任务发生致命不可逆错误 | `{"type":"error","message":"未能找到 gradlew 执行文件"}\n` |

> [!IMPORTANT]
> **流控背压纪律**：向宿主推流 `token` 或 `thought` 时，请在插件内部做聚合限流（建议聚合窗口 50ms，推流频率不高于 20Hz），避免高频细碎 I/O 导致主事件循环卡顿。

---

### 3.3 动作与审批回执 (`POST /task/action`)

当机主在群聊中点击卡片按钮（如点击 `[批准]`、`[拒绝]` 或操作选项）时，宿主调用此接口通知插件。

- **请求 Payload**：
  ```json
  {
    "sessionId": "s-9f82a1b4",
    "actionId": "approve",
    "decidedBy": "Bob"
  }
  ```
- **响应规格 (HTTP 200)**：
  ```json
  { "ok": true }
  ```

---

### 3.4 进度快照查询 (`POST /task/queryProgress`)

宿主定时（默认 2s）轮询或拉取任务进度快照，用于离线客户端上线对账。

- **请求 Payload**：
  ```json
  { "sessionId": "s-9f82a1b4" }
  ```
- **响应规格 (HTTP 200)**：
  ```json
  {
    "sessionId": "s-9f82a1b4",
    "state": "running",
    "stage": "正在运行回归测试",
    "progressPercent": 65
  }
  ```

---

## 4. 群聊结构化卡片渲染协议 (`card`)

为了彻底杜绝代码与长日志刷屏，Tidings 采用 **单卡槽原位刷新系统 `(cardId, rev)`**。

当插件推流 `{"type":"card","card":{...}}` 时，`card` 对象需满足以下 Schema：

```json
{
  "cardId": "task-8842",
  "rev": 1,
  "type": "task",
  "title": "PeerTagRouter 排序优化",
  "banner": {
    "tone": "info",
    "text": "正在执行单元测试"
  },
  "progress": 60,
  "steps": [
    { "label": "分析泛型比较器类型推断", "status": "done" },
    { "label": "重写在线优先排序逻辑", "status": "done" },
    { "label": "运行 ./gradlew :core:jvmTest", "status": "running" },
    { "label": "生成 Patch 并提纯交付", "status": "pending" }
  ],
  "diff": {
    "path": "core/.../PeerTagRouter.kt",
    "add": 15,
    "del": 2,
    "note": "消除泛型推断歧义"
  },
  "artifact": {
    "name": "peertag-patch.diff",
    "meta": "1.2 KB · text/x-diff",
    "sha256": "4a7b2e1f..."
  }
}
```

### 渲染关键规则：
- `cardId`：任务全局唯一标识。只要 `cardId` 相同，群界面**原位覆盖更新**，绝不产生多余聊天气泡；
- `rev`：版本号，每次状态发生变动单调递增（1 $\rightarrow$ 2 $\rightarrow$ 3）；
- `banner.tone`：支持 `"info"`（蓝色）、`"done"`（绿色）、`"warn"`（黄色）、`"danger"`（红色）；
- `steps[].status`：支持 `"pending"`（等待）、`"running"`（进行中）、`"done"`（完成）、`"failed"`（失败）。

---

## 5. 工作区与沙箱交互规范 (Worktree & Sandbox)

第三方 Agent 必须严格遵守以下文件操作与构建准则：

1. **绝对不在宿主原始工作区改代码**：
   - 宿主传递的 `wsPath` 是一个已经签出好的 **Git Worktree 独立目录**（如 `/tmp/tidings-ws/task-8842`）；
   - Agent 的所有读写、创建文件必须限制在该目录内部；
2. **复用本地工具链与缓存**：
   - 进程直接调用宿主机已安装的 `gradlew`、`mvn`、`cargo`、`npm` 或 `pytest`；
   - 环境变量自动继承父进程的本地缓存（如 `~/.gradle/caches`），秒级完成增量构建；
3. **交付提纯产物**：
   - 任务完成后，在 `wsPath` 根目录执行 `git diff > patch.diff`，并计算 SHA256；
   - 将补丁摘要填入卡片交付群聊，严禁在临时目录直接执行 `git push`。

---

## 6. 安全与防踩坑军规 (Strict Invariants)

| 军规编号 | 强制要求 | 违反后果 |
|:---|:---|:---|
| **INV-1** | **API Key 严禁外流**：不得在聊天输出、卡片内容或错误日志中打印 API 密钥。 | 触发宿主安全合规拦截，插件直接强制下线。 |
| **INV-2** | **长日志严禁刷屏**：编译报错只提取前 3 行核心摘要回传，完整输出写入本地日志文件。 | 聊天流膨胀，群成员体验极差。 |
| **INV-3** | **尊重审批拒绝**：收到机主 `actionId: "deny"` 后，必须优雅中止高危动作并解释现状，严禁抛未捕获异常崩溃。 | 宿主判定插件异常掉线，触发 502。 |
| **INV-4** | **遵守 64 KiB 帧预算**：任何推流给群聊的卡片 JSON 严格压在 24 KiB 以内，超限文件走本地路径。 | 接收端触发 bad-payload 整帧丢弃。 |

---

## 7. 开箱即用参考实现 (Python Minimal Reference)

这是一个使用 Python 原生内置库实现的**完全零依赖、开箱即用的第三方 Agent 最小骨架**。可以直接运行与 Tidings 宿主联调！

```python
#!/usr/bin/env python3
"""
Tidings 最小参考智能体 (Python 3.8+ 零第三方依赖)
启动命令: python3 tidings_agent_demo.py
"""

import json
import time
from http.server import HTTPServer, BaseHTTPRequestHandler

PLUGIN_PORT = 9988
PLUGIN_MANIFEST = {
    "pluginId": "demo-agent",
    "name": "演示代码助手",
    "version": "1.0.0",
    "type": "single",
    "leader": {
        "agentId": "demo-assistant",
        "name": "DemoAssistant",
        "title": "敏捷协作员"
    },
    "capabilities": ["code-review", "auto-fix"]
}

class AgentHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        content_length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(content_length).decode("utf-8")) if content_length > 0 else {}

        if self.path == "/session/open":
            # 1. 初始化会话
            resp = {"sessionId": "s-demo-101", "status": "ready"}
            self._send_json(resp)

        elif self.path == "/task/execute":
            # 2. 流式推流任务事件 (NDJSON)
            prompt = body.get("prompt", "")
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ndjson; charset=utf-8")
            self.end_headers()

            # ① 发送初始卡片 (rev=1, 进度 20%)
            self._send_ndjson_line({
                "type": "card",
                "card": {
                    "cardId": "card-demo-101",
                    "rev": 1,
                    "type": "task",
                    "title": "任务处理中",
                    "banner": {"tone": "info", "text": "正在分析需求"},
                    "progress": 20,
                    "steps": [
                        {"label": f"解析指令: {prompt[:20]}...", "status": "done"},
                        {"label": "执行修改与代码生成", "status": "running"},
                        {"label": "提纯产物交付", "status": "pending"}
                    ]
                }
            })
            time.sleep(0.5)

            # ② 发送思考与文本 Token
            self._send_ndjson_line({"type": "token", "delta": "正在为您处理该任务，即将生成补丁...\n"})
            time.sleep(0.5)

            # ③ 更新卡片为完成状态 (rev=2, 进度 100%)
            self._send_ndjson_line({
                "type": "card",
                "card": {
                    "cardId": "card-demo-101",
                    "rev": 2,
                    "type": "task",
                    "title": "代码修改完成",
                    "banner": {"tone": "done", "text": "全部步骤执行完毕"},
                    "progress": 100,
                    "steps": [
                        {"label": f"解析指令: {prompt[:20]}...", "status": "done"},
                        {"label": "执行修改与代码生成", "status": "done"},
                        {"label": "提纯产物交付", "status": "done"}
                    ],
                    "diff": {"path": "src/App.kt", "add": 5, "del": 1, "note": "示例修改完成"}
                }
            })

            # ④ 发送终态 finished 行
            self._send_ndjson_line({"type": "finished", "finishReason": "stop", "totalTokens": 150})

        elif self.path == "/task/action":
            # 3. 接收用户审批或操作回执
            action_id = body.get("actionId")
            print(f"收到用户动作: {action_id} by {body.get('decidedBy')}")
            self._send_json({"ok": True})

        elif self.path == "/task/queryProgress":
            # 4. 进度查询
            self._send_json({
                "sessionId": body.get("sessionId", "s-demo-101"),
                "state": "running",
                "stage": "就绪",
                "progressPercent": 100
            })
        else:
            self.send_response(404)
            self.end_headers()

    def _send_json(self, data):
        content = json.dumps(data).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def _send_ndjson_line(self, obj):
        line = (json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8")
        self.wfile.write(line)
        self.wfile.flush()

if __name__ == "__main__":
    server = HTTPServer(("127.0.0.1", PLUGIN_PORT), AgentHandler)
    print(f"[*] Tidings 演示智能体已启动，监听在 http://127.0.0.1:{PLUGIN_PORT}")
    print(f"[*] 注册 Payload 请参考: {json.dumps(PLUGIN_MANIFEST, ensure_ascii=False)}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[*] 智能体已安全退出")
```

---

## 8. 调试与联调流程

1. **启动插件进程**：在终端运行您的智能体程序（如 `python3 tidings_agent_demo.py`），确认服务监听在 `http://127.0.0.1:9988`；
2. **向 Tidings 宿主注册**：
   在宿主机终端向 Tidings 控制面注册该插件：
   ```bash
   curl -X POST http://127.0.0.1:8848/v1/plugin/register \
     -H "Content-Type: application/json" \
     -d '{
       "pluginId": "demo-agent",
       "name": "演示代码助手",
       "port": 9988,
       "manifest": {
         "pluginId": "demo-agent",
         "name": "演示代码助手",
         "version": "1.0.0",
         "type": "single",
         "leader": {
           "agentId": "demo-assistant",
           "name": "DemoAssistant"
         },
         "capabilities": ["code-review"]
       }
     }'
   ```
3. **群聊内验证**：
   打开手机端或桌面端 Tidings，在任何绑定了代码工作区的协同群中输入：
   `@DemoAssistant 帮我修复一下 App.kt`；
4. **观察推流效果**：
   检查群聊流中是否即时渲染出原位卡片，进度从 20% 平滑推进至 100%，并展示 Diff 统计信息！
