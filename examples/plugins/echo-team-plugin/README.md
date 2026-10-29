# echo-team-plugin —— Tidings 参考插件

进程外插件的**完整最小实现**：零第三方依赖（Python 3 标准库），收到任何任务都
原样回声，并回一条含声明式卡片的标准事件流。用途：

1. 给插件作者当**契约模板**（把 echo 换成你的真实现）；
2. 给宿主当**联调探针**（宿主发什么就该收到什么，任何丢帧一眼可见）。

## 文件

| 文件 | 是什么 |
|---|---|
| `manifest.json` | 插件清单（方案 §2.2）：身份、leader、组员、工作区白名单、能力声明 |
| `main.py` | 插件本体：回环 IPC 服务器 + 注册 + 回声任务 |

## 插件侧 HTTP 契约（宿主会来调的路由）

| 路由 | 语义 |
|---|---|
| `GET /healthz` | 存活探测（宿主台账据此维护在线状态，探不到 ⇒ 翻离线） |
| `POST /session/open` | 开/续会话，返回 `{sessionId, resumed, createdAt}` |
| `POST /task/execute` | 执行任务；**流式 NDJSON** 应答，每行一个事件 |
| `POST /task/action` | 卡片操作回传（`decidedBy` 是操作者，审批防重在宿主） |
| `POST /task/queryProgress` | 点查进度；回 `null` = 没有可报告的进度 |

事件行（`task/execute` 的每一行）按 `type` 区分：`thought` / `token` / `tool` /
`card` / `finished` / `error`。`card` 的值就是方案 §4.3 的声明式卡片 JSON
（字节预算由宿主把关，超限会被拒收 —— 插件侧自行瘦身重发）。

## 两种接入模式（都不需要改宿主一个字）

### 模式一：目录托管（Managed Subprocess）

把整个目录拷进宿主插件目录，桌面端启动时自动扫描、拉起并注入 token：

```
~/Library/Application Support/捎话 Tidings/plugins/echo-team-plugin/
├── manifest.json
└── main.py
```

- 宿主启动插件子进程时注入 `TIDINGS_CONTROL_URL` 与 `TIDINGS_PLUGIN_TOKEN`；
- 插件就绪后自己 `POST /v1/plugin/register` 入驻；
- `kill` 插件进程 ⇒ 宿主在下一个探测周期把它翻成**离线**，主程序不受影响；
  重启插件（同 pluginId 重注册）⇒ 台账条目被替换、状态回到 online。

### 模式二：独立守护进程（Dynamic Hot-Plugging）

自己把插件跑起来（launchd / systemd / 手动都行），token 三选一：

```bash
# a. 环境变量（托管模式的同一把 token）
TIDINGS_PLUGIN_TOKEN=<token> python3 main.py

# b. 插件目录旁放一个 plugin-token 文件（内容 = token）

# c. 什么都不做：插件去读宿主数据目录下 0600 的
#    ~/Library/Application Support/捎话 Tidings/plugins/plugin-token
#    （桌面端启动时保证它存在；Linux 走 XDG_DATA_HOME 同名路径）
```

插件停止时自动 `POST /v1/plugin/unregister`（SIGTERM/SIGINT 都接了）；
没来得及注销就死了也无所谓 —— 宿主探测周期会把条目翻成离线。

## 手动冒烟（桌面端）

1. 拷贝目录到插件目录（模式一），启动捎话桌面端；
2. 看注册：`curl -s http://127.0.0.1:8788/v1/plugins`（`echo-team` 应为 `online`）；
3. 派单：
   ```bash
   curl -s -X POST http://127.0.0.1:8788/v1/plugin/task \
     -d '{"pluginId":"echo-team","prompt":"hello echo"}'
   ```
   回执的 `events` 里有 `card` 事件；`curl -s "http://127.0.0.1:8788/v1/agent/sessions?sessionId=<回执里的 sessionId>"`
   能看到同一批事件（台账）；
4. 热拔：`pkill -f "python3 main.py"` ⇒ `/v1/plugins` 里状态翻 `offline`，
   控制面 `/healthz` 照常应答；重新跑 `python3 main.py`（读 c 的 token 文件）⇒ 状态回 `online`。

## 注册失败的常见原因（都如实打在插件 stdout）

- `register rejected: HTTP 401` —— token 不对（env 与宿主的 `TIDINGS_PLUGIN_TOKEN` 不是同一把）；
- `HTTP 400 … over the 21 char budget` —— manifest 里的 agentId slug 化后超预算，
  换个短名（发送侧不变量：超限的 senderId 会被整帧拒收）;
- `no token found` —— 三种来源都没找到；模式一不会有这种情况（宿主会注入）。
