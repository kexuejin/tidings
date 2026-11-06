# Tenon（榫卯）全域插件化扩展架构详细设计规范（LLD）

> **规范标识**：`SPEC-TENON-PLUGIN-ARCHITECTURE-2026-11-05`  
> **文档版本**：`1.0.0 (Detailed Design)`  
> **适用模块**：`:agent-contract`、`:agentNode`、`:orchestration`、`:core`、`:app`、`:composeApp`  
> **对齐基线**：`AGENTS.md` / `SPEC-ORCHESTRATION-P2-REVISED` / `agent-autoloop` 源码反刍

---

## 一、 概述与架构背景

### 1.1 问题陈述与第一性原理
在 Tenon 多智能体协同网络演进中，系统面临着核心矛盾：
**不断涌现的外部大模型 CLI、自定义自动化脚本、多厂商协作编排逻辑，与 Tenon 作为高可靠去中心化通信/协同内核的纯粹性之间的冲突。**

如果把每一个新模型（Claude Code CLI、Codex、DeepSeek、Ollama）或特定业务流水线都以 Kotlin 代码硬编码在主工程中，会导致：
1. **主工程膨胀与频繁发版**：外部 CLI 改一个参数或新出一个模型，都需要全量重新发版；
2. **缺乏崩溃隔离**：外部脚本抛出未捕获异常或环境变量缺失，会污染并威胁主应用稳定性；
3. **协作流程固化**：用户无法随心所欲定制“双模型对审”、“先巡检后修复”、“多棒接力”等业务剧本。

为此，本规范基于**微内核（Microkernel）与外置插件化**的第一性原理确立核心骨架：
> **Tenon 内核固化为不可变底座与裁判**（协议传输、P2P 同步、事件台账、安全硬门禁、防死循环熔断）；  
> **执行体（Agent）、编排流（Workflow）、外部触发（Source）、触达通道（Channel）全部外置为语言无关的标准目录插件包。**

---

## 二、 总体架构拓扑与模块分工

```mermaid
flowchart TB
    subgraph Trigger_Layer ["1. 触发层 (Source 插件)"]
        S1["GitHub / GitLab Webhook"]
        S2["Sentry / Prometheus 报警"]
        S3["本地 Cron 定时器"]
    end

    subgraph Desktop_Engine ["2. Tenon 桌面宿主 (:desktopApp + :agentNode + :orchestration)"]
        direction TB
        PR["PluginRegistry (本地扫描与防穿透校验)"]
        WPE["WorkflowPluginEngine (声明式剧本推进与自愈)"]
        PAD["ProcessAgentDriver (子进程调度与流式解析)"]
        SWR["SourceWebhookRoute (:agentNode Ktor 监听)"]
        CB["ChannelBridge (卡片原位打补丁桥)"]

        PR --> WPE
        PR --> PAD
        SWR -->|触发新工单| WPE
        WPE -->|调度执行| PAD
        WPE -->|审批门禁/进度卡片| CB
    end

    subgraph Mobile_Cockpit ["3. Tenon 移动驾驶舱 (:composeApp + :app)"]
        direction TB
        PS["P2P 同步接收器 (轻量清单同步)"]
        Timeline["工作流时间线渲染 (原生 Compose)"]
        ReviewCard["审批门禁卡片 (一键掌上裁决)"]

        PS --> Timeline
        PS --> ReviewCard
    end

    Trigger_Layer -->|HTTP POST| SWR
    PAD -->|stdio JSON-Lines| WPE
    CB -.->|P2P TLS (CardSync)| ReviewCard
    ReviewCard -.->|用户点击裁决| CB
    CB -->|飞书/企微/桌面通知| External_IM["外部企业 IM (lark-cli)"]
```

### 模块权责边界（严格遵守 `AGENTS.md`）
*   **`:agent-contract`（纯契约层）**：只包含 `PluginManifest`、`WorkflowDefinition`、`StreamFormat` 等基础数据结构，零外部依赖；
*   **`:agentNode`（控制面网络）**：暴露本地 Ktor HTTP 路由，接收 Source Webhook 入站请求并做 HMAC 验签；
*   **`:orchestration`（编排核心）**：承载 `PluginRegistry`、`ProcessAgentDriver`、`WorkflowPluginEngine`，执行所有子进程与状态流转；
*   **`:app`（领域层）**：**严格零 Compose 依赖**（遵守 `:domainComposeFreeGate`），管理插件元数据状态流；
*   **`:composeApp`（表现层）**：负责移动端/桌面端动态表单渲染与工作流时间线展示；
*   **`:core`（通信层）**：负责跨端 P2P 轻量元数据广播，严格遵守 64 KiB 帧预算。

---

## 三、 四大插件能力标准规范

Tenon 将插件能力抽象为四类正交的契约：

```
~/.tenon/plugins/<plugin-id>/
├── manifest.json       // 核心清单定义（必须）
├── icon.png            // 图标（可选，64x64）
├── workflow.json       // 编排剧本（能力为 workflow 时必须）
└── run.py / run.sh     // 执行脚本（自包含型插件自带）
```

### 3.1 Agent 插件规范（执行体）

#### 契约模型与占位符
Agent 插件负责具体的任务运算、代码编写或数据分析。在 `manifest.json` 中定义 `capabilities.agent`：

```json
{
  "command": ["claude", "-p", "{prompt}", "--resume", "{session}", "--output-format", "stream-json"],
  "streamFormat": "CLAUDE_STREAM_JSON",
  "workingDir": "{workspace}",
  "timeoutSec": 300,
  "checkCommand": ["claude", "auth", "status"],
  "sessionLink": "claude://resume?session={session}"
}
```

*   **标准支持占位符**：
    *   `{prompt}`：本轮输入提示词；
    *   `{session}`：会话持久化唯一 ID；
    *   `{workspace}`：当前工作区绝对路径；
    *   `{contextFile}`：上下文文件指针落盘路径；
    *   `{pluginDir}`：插件包自身的绝对路径。

#### stdio JSON-Lines 事件流协议
当 `streamFormat = "TENON_EVENT_STREAM"` 时，插件进程往 `stdout` 逐行打印 JSON，由 Tenon 映射为事件七型：

```json
{"type": "thought", "text": "正在检索代码中的 SQLDelight 迁移文件..."}
{"type": "message", "text": "已定位到第 15 个迁移版本存在索引不一致。"}
{"type": "card", "kind": "ask", "title": "是否执行强制清理？", "options": ["是", "否"], "recommend": 0}
{"type": "artifact", "path": "build/report.html", "summary": "测试报告"}
{"type": "end", "usage": {"input": 1200, "output": 450}, "cost": 0.008}
{"type": "error", "message": "编译错误：未解析的引用"}
```

---

### 3.2 Workflow 插件规范（编排剧本）

Workflow 插件定义“活怎么干”，由 `workflow.json` 进行声明式定义。

```json
{
  "id": "code-autofix",
  "name": "代码自动修复流水线",
  "requires": {
    "analyzer": "claude-code",
    "coder": "deepseek-local",
    "tester": "gradle-runner"
  },
  "steps": [
    {
      "id": "analyze",
      "name": "根因诊断",
      "type": "AGENT",
      "agentAlias": "analyzer",
      "inputTemplate": "分析 Issue #{input.issueId} 的原因并给出方案",
      "outputKey": "diagnosis"
    },
    {
      "id": "fix",
      "name": "编写修复补丁",
      "type": "AGENT",
      "agentAlias": "coder",
      "inputTemplate": "根据诊断报告：\n{steps.analyze.output}\n编写代码补丁",
      "outputKey": "patchDiff"
    },
    {
      "id": "test",
      "name": "自动化测试与自愈",
      "type": "AGENT",
      "agentAlias": "tester",
      "command": ["./gradlew", "test"],
      "retryPolicy": {
        "maxRetries": 3,
        "feedbackTo": "coder",
        "feedbackPrompt": "单测失败：\n{error}\n请修正补丁代码"
      }
    },
    {
      "id": "review-gate",
      "name": "人工合流审批门禁",
      "type": "GATE",
      "card": {
        "kind": "ask",
        "title": "测试全绿，是否允许合入主分支？",
        "text": "补丁已通过单测，详情请查看工件链接。",
        "options": ["批准合并", "打回重修", "放弃任务"],
        "recommend": 0
      }
    },
    {
      "id": "merge",
      "name": "合入主分支",
      "type": "AGENT",
      "agentAlias": "tester",
      "condition": "{steps.review-gate.choice} == 0",
      "command": ["git", "merge", "--ff-only", "fix-branch"]
    }
  ]
}
```

*   **零开销挂起机制（Zero-Cost Suspension）**：
    推进至 `type == "GATE"` 时，引擎将当前工单持久化为 `SUSPENDED` 态并立即退出协程，**零 CPU 占用、零 Token 消耗、不占线程池**。
*   **回调唤醒**：用户在手机或桌面点击卡片后，引擎加载上下文，将 `choice` 和用户附带的指引注入上下文变量，无缝推进下一步。

---

### 3.3 Source 插件规范（外部触发源）

Source 插件负责接收外部系统信号（如 GitHub Webhook），并在 `:agentNode` 网关做安全清洗：

1.  **HMAC 安全验签**：使用 `timingSafeEqual` 对比请求签名（如 `X-Hub-Signature-256`），防止伪造与时序攻击；
2.  **6 字段标准化事件**：抹平所有第三方载荷，统一提取为标准事件模型：
    *   `id`：外部投递唯一 ID（天然幂等去重键）；
    *   `type`：如 `issues.opened`、`pull_request.labeled`；
    *   `subject`：标识目标（如 `owner/repo#123`）；
    *   `summary`：摘要（限 200 字符）；
    *   `detail`：详细内容（限 4000 字符）；
    *   `link`：外部回跳地址。

---

### 3.4 Channel 插件规范（触达与原位打补丁）

Channel 插件连接外部通知表面（飞书、钉钉、企微、桌面系统原生通知）：

1.  **单卡原位打补丁（In-Place Patching）**：
    *   首次推送时调用 IM 发送交互式卡片，记下消息引用 ID（`ref`，如飞书 `message_id`）；
    *   卡片状态推进（`open` ➔ `decided` ➔ `done` ➔ `closed`）时，**直接调用 `patchMessage(ref)` 原位刷新卡片外观与操作按钮**，聊天流绝不刷屏追加多余消息。
2.  **反向免公网穿透长连接**：
    *   利用官方 CLI 客户端（如 `lark-cli event consume`）维持本地反向长连接拉取点击事件，**主机无需暴露任何公网端口或配置内网穿透（FRP）**。

---

## 四、 核心数据模型（Kotlin 契约实现）

在 `:agent-contract` 模块落地强类型数据契约：

```kotlin
package com.kapp.tenon.agent.contract.plugin

import kotlinx.serialization.Serializable

@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val author: String = "",
    val capabilities: PluginCapabilities,
    val settings: List<PluginSettingDef> = emptyList()
) {
    init {
        require(Regex("^[a-z0-9-]+$").matches(id)) { "插件 ID 必须完全符合 ^[a-z0-9-]+$: $id" }
    }
}

@Serializable
data class PluginCapabilities(
    val agent: AgentCapability? = null,
    val workflow: WorkflowCapability? = null,
    val source: SourceCapability? = null,
    val channel: ChannelCapability? = null
)

@Serializable
data class AgentCapability(
    val command: List<String>,
    val streamFormat: StreamFormat,
    val workingDir: String = "{workspace}",
    val checkCommand: List<String>? = null,
    val sessionLink: String? = null,
    val timeoutSec: Long = 300
)

@Serializable
enum class StreamFormat {
    TENON_EVENT_STREAM,
    CLAUDE_STREAM_JSON,
    RAW_TEXT
}

@Serializable
data class WorkflowCapability(
    val definitionFile: String = "workflow.json"
)

@Serializable
data class SourceCapability(
    val webhookPath: String,
    val secretEnv: String,
    val signatureHeader: String = "x-hub-signature-256"
)

@Serializable
data class ChannelCapability(
    val type: String,
    val configKeys: List<String> = emptyList()
)

@Serializable
data class PluginSettingDef(
    val key: String,
    val label: String,
    val type: SettingType,
    val default: String? = null,
    val required: Boolean = false,
    val options: List<String> = emptyList()
)

@Serializable
enum class SettingType {
    STRING, PASSWORD, BOOLEAN, ENUM
}
```

---

## 五、 关键组件实现机制（Low-Level Implementation）

### 5.1 `PluginRegistry`（本地安全扫描器）
位于 `:orchestration`，负责扫描 `~/.tenon/plugins/`：

```kotlin
class PluginRegistry(private val pluginsDir: File) {
    private val plugins = ConcurrentHashMap<String, PluginManifest>()
    private val drivers = ConcurrentHashMap<String, ProcessAgentDriver>()

    fun scan() {
        if (!pluginsDir.exists()) pluginsDir.mkdirs()
        pluginsDir.listFiles { f -> f.isDirectory }?.forEach { folder ->
            // 严格防目录穿越检查
            if (!Regex("^[a-z0-9-]+$").matches(folder.name)) return@forEach
            val manifestFile = File(folder, "manifest.json")
            if (manifestFile.exists()) {
                runCatching {
                    val manifest = Json.decodeFromString<PluginManifest>(manifestFile.readText())
                    if (manifest.id == folder.name) {
                        plugins[manifest.id] = manifest
                        manifest.capabilities.agent?.let { cap ->
                            drivers[manifest.id] = ProcessAgentDriver(manifest, cap, folder)
                        }
                    }
                }
            }
        }
    }

    fun getDriver(id: String): ProcessAgentDriver? = drivers[id]
    fun getAllManifests(): List<PluginManifest> = plugins.values.toList()
}
```

### 5.2 `ProcessAgentDriver`（子进程调度与流式解析）
位于 `:orchestration`，实现 `AgentDriver` 接口：

```kotlin
class ProcessAgentDriver(
    val manifest: PluginManifest,
    val capability: AgentCapability,
    val pluginDir: File
) : AgentDriver {

    override fun launchTurn(turn: AgentTurn): Flow<AgentEvent> = flow {
        emit(AgentEvent.TurnStart(turnId = turn.turnId, agentId = manifest.id))

        val cmd = capability.command.map { part ->
            part.replace("{prompt}", turn.prompt)
                .replace("{session}", turn.sessionId)
                .replace("{workspace}", turn.workspaceRoot.absolutePath)
                .replace("{contextFile}", turn.contextFile?.absolutePath ?: "")
                .replace("{pluginDir}", pluginDir.absolutePath)
        }

        val pb = ProcessBuilder(cmd)
            .directory(File(capability.workingDir.replace("{workspace}", turn.workspaceRoot.absolutePath)))
            .redirectErrorStream(false)

        turn.settings.forEach { (k, v) -> pb.environment()["TENON_PLUGIN_$k"] = v }

        val process = pb.start()
        val stderrBuffer = StringBuilder()

        // 异步排空 stderr，防止子进程缓冲区满导致死锁
        val errReader = thread(name = "stderr-pump-${manifest.id}", isDaemon = true) {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (stderrBuffer.length < 2000) stderrBuffer.appendLine(line)
                }
            }
        }

        // 超时看门狗
        val timeoutScope = CoroutineScope(Dispatchers.IO)
        val timeoutJob = timeoutScope.launch {
            delay(capability.timeoutSec * 1000)
            if (process.isAlive) process.destroyForcibly()
        }

        try {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    when (capability.streamFormat) {
                        StreamFormat.TENON_EVENT_STREAM -> parseTenonJsonLine(line)?.let { emit(it) }
                        StreamFormat.CLAUDE_STREAM_JSON -> parseClaudeJsonLine(line)?.let { emit(it) }
                        StreamFormat.RAW_TEXT -> emit(AgentEvent.MessageChunk(text = line + "\n"))
                    }
                }
            }

            val exitCode = process.waitFor()
            timeoutJob.cancel()
            errReader.join(500)

            if (exitCode != 0) {
                emit(AgentEvent.Error(
                    message = "Agent 异常退出 (退出码 $exitCode): ${stderrBuffer.takeLast(300).trim()}"
                ))
            } else {
                emit(AgentEvent.TurnEnd(turnId = turn.turnId))
            }
        } catch (e: Exception) {
            process.destroyForcibly()
            emit(AgentEvent.Error(message = "Agent 执行异常: ${e.message}"))
        }
    }
}
```

---

## 六、 跨端 P2P 同步与移动端渲染规范

### 6.1 广播帧规范（严格控制在 1 KiB 内）
桌面端扫描插件变动后，向手机端 P2P 连接广播轻量概要，**严禁跨端传输可执行代码**：

```json
{
  "kind": "agent-plugin-sync",
  "plugins": [
    {
      "id": "claude-code",
      "name": "Claude Code",
      "type": "agent",
      "ready": true
    },
    {
      "id": "code-autofix",
      "name": "代码自动修复流水线",
      "type": "workflow",
      "ready": true
    }
  ]
}
```

### 6.2 移动端呈现模型
1.  **新建会话与智能体选择器**：下拉列表直接合并展示内置驱动与插件驱动；
2.  **工作流时间线卡片（TicketCard）**：
    *   在会话列表中以单卡展现多节点状态流（Step 1 完成 ➔ Step 2 运行中 ➔ Step 3 待审批）；
    *   遇到 `GATE` 门禁时卡片原地翻蓝，展示操作按钮；用户在手机上点击审批，指令通过 P2P TLS 传回桌面，桌面驱动继续往下走。

---

## 七、 安全边界与架构护城河（Security Guardrails）

必须严格守住以下 5 条工程红线：

1.  **进程级别崩溃隔离**：插件崩溃只表现为单个任务报错，Tenon 主应用进程 100% 免疫；
2.  **防目录穿透（Anti-Traversal）**：插件 ID 强制校验 `^[a-z0-9-]+$`，加载路径严格限制在 `plugins/` 根目录下；
3.  **无检出安全代码审查（No-Checkout Review）**：借鉴 `agent-autoloop` 实践，执行代码审查时只读取 Git 裸对象与 Diff 快照，**严禁使用 `git checkout`**（防止被审查分支包含恶意 Git Hook 或提权配置代码）；
4.  **环境缺失优雅降级**：未安装对应 CLI 依赖时，必须通过 `checkCommand` 提前拦截，并在 UI 上标灰提示，杜绝盲目派单超时假死；
5.  **帧预算与工件解耦**：代码 Diff 与测试报告统一落盘为本地文件，通过 `Artifact` 引用交互，严禁直接塞进 P2P 协议帧。

---

## 八、 研发排期与测试矩阵

### 8.1 排期落地
*   **P2 阶段（当前交付周期）**：
    *   `:agent-contract` 落地 `PluginManifest` 契约；
    *   `:orchestration` 落地 `ProcessAgentDriver` 与 `PluginRegistry` 本地目录扫描；
    *   验证 Claude Code CLI 与本地 Bash/Python 脚本的零发版接入。
*   **P2.5 / P3 阶段（后续扩展周期）**：
    *   落地 `WorkflowPluginEngine` 声明式剧本推进与自愈重试；
    *   落地 `:agentNode` Source Webhook 外部入站与 Channel 外部 IM 桥接。

### 8.2 四重证据链测试矩阵
1.  **接线守卫测试**：验证 `PluginRegistry` 仅在 `:orchestration` 与 `:desktopApp` 被装配，保持 `:app` 领域层零 Compose 依赖；
2.  **防穿透否定断言测试**：测试 `id = "../attack"`、`id = "foo/bar"`、`id = ".hidden"` 必须 100% 拒绝；
3.  **进程超时与强杀断言测试**：模拟死循环脚本（`sleep 1000`），验证 `timeoutSec` 到期后进程被强杀且主线程零卡顿；
4.  **稳定性回归测试**：运行 `:testStabilityGate`，连跑 3 轮并发任务，确保插件进程管道读写无资源泄漏。
