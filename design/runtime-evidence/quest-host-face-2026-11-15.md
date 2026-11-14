# 主支线生命周期 · 宿主面端到端验收（2026-11-15）

HEAD：main `64027518`（squash 合入 feat/main-side-quest-assistant，14 提交，
树哈希 643d96ada 与分支 tip 逐字节一致）。

## 判据与结果

`QuestRunnerHostFaceAcceptanceTest.realCliQuestLifecycleEndToEnd`
（orchestration/src/test/…，显式启用 `TENON_QUEST_E2E=1`，缺驱动大声跳过）：

- **真驱动栈**：生产装配函数 `cliDriverRegistrations(sandbox=…)`——真凭证注入
  （CliProviderEnv 白名单）、真 WorktreeSandboxExecutor、真 codex CLI
  （OpenAI Codex v0.145.0，provider tenon-go / mimo-v2.6-flash）。
- **真 git**：临时主仓（main + 1 commit）+ QuestRunner 派发的持久 quest worktree。
- **结果**：**通过，26.6s**。
  - 通知序列 `RUNNING rev0 → COMPLETED rev2`（一任务一卡原位翻面）；
  - 终态 `terminalStatus = COMPLETED`；
  - 沙箱收割 `+1 -0 in 1 file(s), patch=136 B`（accepted.txt，内容 OK）；
  - 门禁命令 `test -f accepted.txt` 放行；ff 合流真落盘（主仓出现 accepted.txt）；
  - 终态后 worktree 清场（沙箱目录仅剩 artifacts，工作面无残骸）。

## 过程中实测到的环境事实（非代码缺陷）

1. **本机 dsh 为开发版**，CLI 面与驱动实测的 v0.2.1-rc.2 不兼容（prompt 被当作
   profile 名，`dsh: profile … does not exist`）——验收的驱动候选序按实况改为
   codex 优先。dsh 驱动 argSpec 对旧版的兼容属驱动侧后续工作。
2. **claude 未登录**（`Not logged in · Please run /login`）——环境事实。
3. codex 凭证经 `CliProviderEnv` 白名单注入沙箱后可用；注入脚本需正确剥引号
   （第一次提取 sed 转义错误 → 401 Invalid credential，修正后 PONG 通）。
4. 全量测试批**不得与其它构建并发**：同仓并发编译曾让吞吐量尖刺用例
   （LuciolaChannelThroughputSpikeTest）在负载下失败；干净串行重跑全绿
   （2m12s）。与 AGENTS §1 `--max-workers=1` 的动机同源。

## 与测试清单的映射

ACCEPTANCE §6b 的机制半边（1 派单翻面 / 3 门禁 / 4 合流 / 清场）由本轮宿主面
run 覆盖；7 号（越界写）与 6 号（跨重启）有各自的单测钉子
（escapedWriteToHostRepoFailsClosed / ledgerSurvivesRestart…）；2 号（调度）
与 5 号（叫停）的 GUI 半边待真机。

## 追加：Pixel 6 真机验收（2026-11-15，同日）

设备：Pixel 6（Android 15，USB），安装 `com.kapp.tenon.debug`（旧版 0.1.0-debug，
2026-11-14 构建）。先按 §2.3 备份库至 `~/Downloads/relay-build-20261115/`。

| 判据 | 结果 |
|---|---|
| 升级安装 + DB 迁移 22→24（QuestLedger 建表） | ✅ user_version=24，app 正常启动 |
| 启用私人助理开关（设置 → 接入与通道） | ✅ 开关落库，会话列表 3→4 |
| 助理会话置顶注入（助理徽标 + 置顶标 + 预览） | ✅ 首行 |
| **助理会话可打开** | ❌→✅ 真机抓到真缺陷：`chatPeer("assistant")` 在便签线程为空时返回 null，点行只出「选一个会话」空态。修复：chatPeer 为 assistant 合成伪 peer（nick="assistant" 接管头部标题分支）；同修 ChatHeader 标题分支过窄问题（displayName 8 位回退 "assistan" 盖过助理分支 → 一律本地化「私人助理」收口） |
| 「支线派单」chip 渲染与改道 | ✅ 发送盖 client_token（`note-1d99…`）落库（mine=1），无执行体如实留存（quest_id=NULL、无卡）——手机侧语义正确 |
| 读库纪律 | ⚠️ 只拉 relay.db 会丢 WAL 内的写入——必须三件套同拉（§5 已有此教训的镜像） |

## 追加：Mac GUI 半边阻塞点

本会话无法驱动桌面 GUI：`cliclick` 未安装、shell 无「屏幕录制」权限
（screencapture 失败）、osascript System Events 卡 TCC 授权。**剩余 GUI 半边
（chip 视觉、卡片交互、通知翻面的截图证据）需机主在桌面端给终端授予
「辅助功能 + 屏幕录制」权限后按 ACCEPTANCE §6b 手动过**。新构建的桌面实例
已在跑（旧实例已停），可直接点。控制器层的等价行为已由
AssistantQuestNoteBindingTest / QuestCardFlipTest 钉死。
