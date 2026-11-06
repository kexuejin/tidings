# 真机实测：agent 会话「残留行」在这台设备上不存在（2026-10-18）

> **一句话结论：真机实测，这台设备上不存在 agent 会话残留行；用户看到的「多个单独的 agent 会话」是真实 DSH 会话，不是脏数据。**
>
> 因此 task-44（应用内迁移：清掉 `origin`/`blank`/`ws_id` 全 NULL 且终态的行）**不落迁移、不留 `.sqm`** —— 实测目标为 0 行，落一个删 0 行的迁移是死代码，还要把 schema 从 10 抬到 11。
>
> 取证执行：chat-ux（task-42 ③ / task-44）｜设备 `<device-serial>`｜应用 `app.relay`（debug，`run-as` 可用）｜库 `user_version = 10`、`journal_mode = wal`。

## 0. 结论速览（可直接引用）

1. **前提不成立**：`AgentSessionEntity` 里「`origin`/`blank`/`ws_id` 三 NULL」的行 **0 条**，再加「`state` 属终态」仍是 0 条。
2. **迁移判据本身与老行形状不吻合**（这次取证最有价值的一条）：老行的 `state` 恰恰常常是 **NULL**（本机 19/21 行如此）。即使将来真遇到三 NULL 的老行，只要 `state` 是 NULL，就**不会**被 `state IN (done, error, archived, closed)` 删掉 —— 这条判据从一开始就抓不住它想抓的东西，所以**不该为了一个不成立的判据去改 schema**。
3. **用户看到的「多个单独 agent 会话」= 真实 DSH 会话**：一个节点下 21 条真实会话（有标题、有 `ws_id`、`updated_at` 是当天），其中 2 条空壳（`blank=1`）因同一工作区还有 19 条非空壳，`visibleInDsh()` 本来就把它们隐藏。它们按设计出现在聊天页头部「切换会话」那一列（**每个 DSH 会话一条**）。⇒ task-42 ③ 的结论以这份实测为准：**不是脏数据**。

## 1. 库路径纠正（先看这条，别再踩）

库**不在** `files/relay/relay.db`。真机上的实际布局：

```
databases/relay.db        relay.db-wal   relay.db-shm   relay.db-journal   ← 真身在这里
files/relay/              dedup.log · diag/ · link-identity/ · messages.json · settings.json · stores/
```

- 长连接诊断仍然是 `files/relay/diag/link.json`（`run-as app.relay cat files/relay/diag/link.json` 没问题）；
- 但**拉库必须走 `databases/`**：按 `files/relay/relay.db` 拉会得到一个 **53 字节的假文件**，`sqlite3` 直接报 `Error: stepping, file is not a database (26)`（本次取证第一轮就踩了这个）。

## 2. 复现命令与纪律（照做即可）

```bash
# 1) 先停应用：内存快照/写事务会与读取打架，也让 WAL 落定
adb shell am force-stop app.relay

# 2) 把库与它的 WAL/SHM/journal 一起拉到主机（只在副本上查，设备上不动库）
for f in relay.db relay.db-wal relay.db-shm relay.db-journal; do
  adb exec-out run-as app.relay cat databases/$f > /tmp/relay44.${f#relay.}
done

# 3) 在主机查
sqlite3 /tmp/relay44.db "PRAGMA user_version; PRAGMA journal_mode;"

# 4) 读完把应用起回（设备状态与取证前一致）
adb shell monkey -p app.relay -c android.intent.category.LAUNCHER 1
```

纪律（本次遵守）：**不在应用运行时手改 relay.db**；force-stop 只用于读；查询全部在主机副本上；读完把应用起回；设备无改动，主机只留 `/tmp` 副本。

`run-as` 需要 debuggable 包；release 包这条路走不通，那种情况只能靠应用内诊断出口。

## 3. 库形状（2026-10-18，逐条一行一值）

```
total=21                     # AgentSessionEntity 全部行
nodes=1   (a1b2c3d4-6e74-4a7c-aab1-20a1d1284cd6)
workspaces=1   (ws-e9b18063d183)     # 21 行全在同一个工作区
triple_null=0                # origin/blank/ws_id **全 NULL** 的行：0 条
triple_null_terminal=0       # 再加「state 属终态」：仍是 0 条
ws_null=0                    # 21 行**全部**带 ws_id
origin_null=21               # 所有行的 origin 都是 NULL（宿主没报这一键）
blank1=2                     # 只有 2 行是空壳（blank=1）
subagent=0
state_null=19                # 19 行 state 为 NULL，2 行 busy
terminal=0                   # **没有任何一行是终态**（done/error/archived/closed）
```

库头：`PRAGMA user_version = 10`（与仓里 `migrations/1..9.sqm` ⇒ version 10 一致）、`journal_mode = wal`；
`AgentSessionEntity` 16 列齐全（含 `agent_id` / `ws_id` / `ws_title` / `ws_path` / `host_updated_at` / `origin` / `blank`）。

## 4. 样例行（19 行是有真标题的真实 DSH 会话，`updated_at` 21:27–22:11）

```
session-868856e1-6  "1. 会话列表中, 目前不是按最近聊天时间排"   busy    DSH  blank=0  seen 22:11:48
session-8e400908-3  "会话消息要支持富文本显示,比如agent消息"    busy    DSH  blank=0  seen 22:10:38
session-841e2942-1  "当前项目质量分析"                          <null>  DSH  blank=0  seen 21:27:25
session-1012be43-b  "你是什么模型"                              <null>  DSH  blank=0  seen 21:27:30
session-692b2f85-9  "原型稿审查Review"                         <null>  DSH  blank=0  seen 21:27:25
session-b99a8a9e-f  "AI原型稿转代码适配效率优化方案"            <null>  DSH  blank=0  seen 21:27:25
session-a4101d97-a  "你梳理下整体架构及代码, 这…"                <null>  DSH  blank=0  seen 21:27:30
session-9195d9d5-7  "dsh-tidings原型稿是否…"                      <null>  DSH  blank=0  seen 21:27:25
session-72ef49c1-8  "捎话与捎个话取名比较"                       <null>  DSH  blank=0  seen 21:27:25
session-53182ef3-1  "目前整体的配色总感觉不太对…"                <null>  DSH  blank=0  seen 21:27:25
```

## 5. 判定（三条）

1. **前提不成立**：这台设备上「`origin`/`blank`/`ws_id` 三 NULL 的老行」一条都没有；连「三 NULL 且终态」也是 0。
2. **判据与老行形状不吻合**（见 §0 第 2 条）：老行 `state` 常为 NULL，`state IN (终态)` 抓不住它们。
3. **不是脏数据**：21 行是真实 DSH 会话；2 条空壳已被运行期 `visibleInDsh()` 过滤（同工作区有非空壳时隐藏空壳），无需迁移清理。

## 6. 给 task-43（文档刷新）的一句话

**请把这份证据引到「文档刷新的 agent 会话」一节**：会话列表/工作区里的「多个 agent 会话」是**每个 DSH 会话一条**（设计如此），不是脏数据；同时把「库在 `databases/relay.db`」这条路径纠正写进任何取证/排障说明。

## 7. 范围与设备状态

- 本次**没有**新增任何 `.sqm`、没有改 schema、没有动 `visibleInDsh()`、没有改库；task-44 的代码类 writeScopes 一个文件都没改。
- 设备：应用已用 `monkey` 起回，库无改动；主机侧只留 `/tmp/relay44.*` 副本（可随时删）。
