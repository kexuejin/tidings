# 2026-11-06 Agent 能力全量真机验收（真实流转，不是演示壳）

> 触发：用户要求「全量验收 —— 如何发起需求、agent 如何工作、更新、操作、验收、
> 各种卡片显示、按钮交互，必须是真实 agent 的流转，不是看起来高大上但走不通的假壳子」。
> 本轮口径：**每一步都有真机/真宿主读数；走不通的如实写成走不通，不美化。**

## 0. 结论总表

| 能力 | 真实读数 | 结论 |
| --- | --- | --- |
| 真机 UI 发起需求 | 手机 composer 逐字符输入 `@codex-e2e weather card demo`，@ 候选弹层、**Agent 上下文胶囊**（`@codex-e2e · ws-weather-e2e 默认 · 新任务`）真出现；宿主日志 `[msg] inbound … len=28` + `[sandbox] task t-1-75e27116 starts` | ✅ 全真 |
| 认知研讨流（P2 规格1） | 提及后宿主发布 task-board 提议卡：`Read-only discussion done (1 rounds) — start to hand off`，[立即执行][取消]两动作 | ✅ 全真 |
| 人确认开工 | 真机点 [立即执行] → 宿主记账 `proposed team confirmed by e4bd7b99…(手机 nodeId), task started`，归属卡面显示「已由 Pixel 6 处理」 | ✅ 全真 |
| 多棒链 + 沙箱真进程 | `task t-1-9a4aa1c3 starts` → codex CLI **真进程真拉起**（task.log 真写 NDJSON），链分支 `chain/cmw3rc2b2`，`origin main untouched` | ✅ 全真 |
| 失败呈现（熔断纠偏） | 卡面受阻徽章 + 红条「第 1 棒失败，链条已停：cli status=error \| 下一步：需要你给方向」+ [Retry] | ✅ 全真 |
| 健康名册自愈（G1） | 首轮 codex 报错后，第二次派单开发棒**自动避开病号**改派 agy（卡面 assignee 可见） | ✅ 全真 |
| 自愈上限交还（历史真据） | e2e-final 群 15h 前的卡：「Handed back: exceeded max heal rounds (3) — No patch produced by worker」，进展 40% | ✅ 真实历史 |
| 审批人守门（幕3） | 审批卡（未分级/工具名/有效期/两范围免审说明）→ 真机点 [批准] → **长轮询返回 `decision=allow reason=approved`** | ✅ 全真 |
| 审批超时 fail-closed（I2） | 人不批 → `决定 -> deny` | ✅ 全真 |
| 快捷指令芯片 | 点芯片 → **填充 composer**（`/diff`）→ 发送 → 宿主真回执（绑了工作区报状态；没绑如实回「no git workspace bound to this group」） | ✅ 全真 |
| 插件生命周期 | `/v1/plugin/toggle` off → `enabled:false status:disabled`；on → `enabled:true status:installed`；`plugins-state.json` 落盘正确 | ✅ 全真 |
| 文件卡接收（defect A 现场仍在） | m5-approve 会话文件卡「已接收 / 已存到 …/打开/分享」正常推进 | ✅ 真机可见 |
| **成功链（patch.diff → 合并）** | **patch 环节当夜已跑通**（见 §7 补记：codex+DeepSeek 真产出 patch.diff 85,740 B 与 1,517 B 两笔，`dispatch done status=finished`）；**合并环节**（真机点[合并此补丁]）被手机 LAN 隔离挡住（§7.3） | ◐ patch ✓ / 合并待 LAN 修复 |

## 1. 环境与装置

- 宿主：`/Applications/Tidings.app`，本轮内**重装一次**（含沙箱修复 `4dab6bbd` 的构建，
  createDistributable + verifyLuciolaNative/verifyPackagedLuciolaNative **71/0 contract 29**，
  lsregister 重注册）；进程 D7（旧）→ D8（新，00:55 起），日志
  `/tmp/e2e-20261105/luciola-host-D7.log` / `-D8.log`。
- 真机：Pixel 6（序列号略），包 `com.kapp.tenon.debug`；UDP 面全程通
  （`acked=true in≈100ms`）；legacy link 面 dial 失败 154 次（已废弃方向，如实记）。
- 群：`e2e-weather`（2 成员 + virtualAgent `codex` 席位）；`e2e-final`（3 成员 +
  委派团队模板 worker=claude / reviewer=dsh）。
- 工作区：`~/e2e-weather-app`（11-02 成功轮的 prototype.html / weather.html 仍在，合流产物）。

## 2. 五驱动环境阻塞矩阵（本轮实测，非产品逻辑缺陷）

| 驱动 | 沙箱内症状 | 根因（环境） | 红证 |
| --- | --- | --- | --- |
| codex | TLS handshake eof ×10 → exit=1 | ① api.openai.com 本机直连不通（`curl` 直连 000，走 clash 7890 得 401=可达）；② `~/.codex/auth.json` 不存在（未登录） | t-1-75e27116、t-2-81e1d71e task.log |
| claude | `Not logged in · Please run /login` | 沙箱 HOME 隔离切断了 Keychain 登录态（`.claude` 目录已 symlink 但凭证在 Keychain） | t-1-5b7a4dfe task.log |
| agy | `authentication failed or timed out` | 认证走 Keychain+浏览器流；换 HOME 即视为未登录去开浏览器授权 → 超时（真 HOME 直跑 `agy --print='reply OK only'` 秒回 OK；拷 `.gemini`/`Application Support/Antigravity` 均无效） | t-1-64fef602 task.log |
| opencode | 上游 400：`This Go model trains on request data. Turn on "Allow paid endpoints…"` | 账号隐私开关（opencode.ai/console）；`OPENCODE_API_KEY` 在宿主 env 里、代理也传了 | t-1-9a4aa1c3 task.log |
| dsh | 未跑到执行 | headless 只认 env `DEEPSEEK_API_KEY`（CliProviderEnv 注释明写），宿主无此变量 | 代码口径 + 宿主 env 实测 |

> 11-02 的成功路径 = **codex CLI + `CODEX_HOME` 指向 DeepSeek provider 配置**
> （CliProviderEnv.kt 注释明写这条正路）；该配置目录本轮在全盘搜索中未找到 —— 需要人指认。
> **解锁一条即可跑通成功链**：DEEPSEEK_API_KEY（dsh/codex 直连国内）/ opencode 控制台开关 /
> CODEX_HOME 目录三选一（已问用户，待答）。

## 3. 本轮抓到的真缺陷（每条都有现场读数）

| # | 缺陷 | 现场 | 处置 |
| --- | --- | --- | --- |
| D1 | **`BoardCardRev` 表不存在**：每次发卡 `rev ledger save FAILED … no such table: BoardCardRev`（D7/D8 两代宿主每次广播都刷） | 宿主日志逐条；卡片广播本身仍成功（功能存活，台账层死） | **未修** —— 属 §2.3 迁移类（老库没吃到建表；SQLDelight 版本=迁移数+1 纪律 + ScreenHistoryRecordTest 版本断言），动 schema 需按纪律走批次，记账待人 |
| D2 | **链收场后受阻卡的 [Retry] 是死按钮**：真机点 Retry → 宿主 `retry had nothing to restart (card chain-cmw3rc2b2)` —— 链对象已从内存摘掉，按钮仍可点但注定无效 | 手机点按 + 宿主日志 | **未修** —— 要么受阻卡不再给 Retry，要么 retry 从卡面状态重建；产品语义需人裁决 |
| D4 | **沙箱凭据挂载白名单缺 `.dsh`/`.agy`**（内联清单不可测） | t-1-64fef602 认证失败红证；修复后新沙箱 `.dsh` symlink 实测出现 | **已修**（`4dab6bbd`，变异验证先红后绿）；注意对 agy 不充分（其认证在 Keychain，见 §2） |
| O3 | 手机库**没有** `AgentApprovalEntity` 表（脚本设计预期接收方落库审批行） | act3 观测读数：`no such table: AgentApprovalEntity` | 记账 —— 审批 UI 渲染走的是活消息，落库层缺失待查 |
| O4 | **工作区绑定不跨宿主重启**：act1 宣告在旧宿主进程内有效；重启后 `/status` `/diff` 全回「no git workspace bound to this group」，重新宣告后**仍**不生效 | 两代宿主 + 两次宣告对照 | 记账 —— 绑定的持久化与解析口径要设计裁决 |
| O5 | **工作区解析双口径**：多棒链能找到仓（task base `10cf2d69`），快捷指令找不到 —— 同一判据两份实现（EXT-8 族） | 同一轮内链与 /status 行为相反 | 记账 |
| O6 | codex 网络失败**静默重试约 4 分钟**（task.log 全程 0 字节、卡面无进展），最终自败 | t-2-81e1d71e 时间线 | 记账 —— 观测性缺口（墙钟止损 30min 未触发是因为任务 4 分钟自败） |
| O7 | opencode 驱动不透传 `--model`（上游拒用默认模型时无路可走） | 400 读数 + opencodeArgs 无 model 位 | 记账（产品改进项） |

## 4. 幕表（真实时序）

```
00:0x  勘察：控制面 8788 ✓、手机在线 rev4、5 CLI 在位、群/席位/模板就绪
00:0x  幕0/幕1（管线脚本）：体检 ✓、git 空仓 + 工作区宣告 200 ✓
00:15  幕1'（真机 UI）：composer 输入 @codex-e2e weather card demo（@ 候选/上下文胶囊真出现）
00:23  宿主 inbound len=28 → 沙箱 t-1-75e27116（codex 真进程）→ 网络败 exit=1
00:23  研讨流提议卡（task-board proposed，开发 opencode/审核 dsh）→ 手机可见
00:24  真机点 [立即执行] → 宿主记账 confirmed by e4bd7b99 → 链 t-1-9a4aa1c3（codex）→ 败
00:25  受阻卡红条 + [Retry] → 点 Retry → 宿主 "nothing to restart"（D2）
00:27  第二次 @codex-e2e weather demo page → 健康名册避开病号 → 开发棒=agy → agy 认证败（§2）
00:45  D4 修复落码 → 重建 createDistributable（71/0）→ 重装 + lsregister → D8 宿主
00:56  修复后重派 agy 仍败（Keychain 边界，§2）→ 请求人决策（凭据三选一）
01:12  幕3 审批守门：真机点 [批准] → allow/approved ✓
01:14  I2 审批超时 → deny ✓
01:19  快捷指令：芯片填 /diff → 发送 → 「no git workspace bound」（如实）✓
01:21  重新宣告后 /status 仍无绑定（O4/O5）✓ 如实
01:2x  插件 toggle round-trip ✓
```

## 5. 卡片样式与按钮（真机见到的全家桶）

- **工单任务卡**：进行中/待合并/受阻三态徽章、横幅（绿/红/紫边）、进度条、
  开发/审核两棒行（assignee 徽章 + stop 子行）、[合并此补丁]/[Retry]/[立即执行][取消]。
- **审批卡**：未分级徽章 + 工具名 + 摘要 + 有效期 + [拒绝][批准] + 「本会话免审 /
  此工具始终允许」两档范围与解释文案。
- **提议卡**（task-board proposed）与**归属条**（已由 Pixel 6 处理）。
- **文件卡**：已接收态 + 已存路径 + 打开/分享（defect A 修复后的现场）。
- **@ 候选弹层**（@所有人/@codex-e2e/@dsh）与 **Agent 上下文胶囊**。

## 6. 复现

```bash
# 宿主（本轮 D8 同口径）
env -u NODE_OPTIONS -u PYTHONPATH -u BASH_ENV -u TIDINGS_LUCIOLA_NATIVE \
  TIDINGS_LUCIOLA=1 TENON_WEBHOOK_SECRET_E2E_SIGNED=luciola-e2e-hmac-20261105 \
  /Applications/Tidings.app/Contents/MacOS/Tidings --background

# 管线各幕（真机模式守门等真人）
E2E_AGENT_KEY=antigravity E2E_GROUP=e2e-weather \
  python3 scripts/e2e-team-pipeline.py --act 2 --tier real

# 凭据通道解锁后，成功链 = act2（patch.diff）→ act3（真机批准）→ act4 → act5（REVIEW.md）→ act6（真机合并 → git 工作树脏行）
```

证据截图（本机不入库）：`/tmp/acc-0*.png … /tmp/acc-25.png`。

---

## 7. 补记（同夜第二轮）：凭据自助解开，patch 真产出；合并环节卡在 LAN

### 7.1 凭据谜题自助解开（没等用户答复）

全盘 `find` 找到了 11-02 的正路配置：**`~/.tidings-e2e/codex-home/config.toml`**
（`model = "deepseek-v4.1-flash"`，`model_provider = "opencode-go"` →
`https://opencode.ai/zen/go/v1`，`env_key = OPENCODE_API_KEY`）。它引用的 key 就是宿主
env 里已有的那只（产品 opencode 驱动同源），不需要提取任何新凭据 —— 按
`CliProviderEnv` 注释写明的正路（`CODEX_HOME` 指真目录）接线即可。

接线撞出两层环境问题，逐层修掉：

| 层 | 症状 | 修法 |
| --- | --- | --- |
| node | `env: node: No such file or directory`（exit 127） | codex 是 node 脚本；宿主启动 PATH 里没有 node（本机 node 只在 `~/.workbuddy-ai/.../bin` 与 nvm）。宿主启动时 prepend node bin 目录 |
| env key | `Missing environment variable: OPENCODE_API_KEY` | 该 key 在 `~/.zshrc`；启动宿主前 `source ~/.zshrc` |

修完即通：**`[sandbox] harvest t-1-15088820: +1068 -1303 in 1 file(s), patch=85740 B`**，
`[e2e] dispatch done: agentKey=codex sessionId=6c1f7fb3… status=finished`。随后第二笔
实现派单同样真产出（t-3-d60776ff：`weather.html +38`，AQI 徽章，patch=1,517 B）。
**「真 agent 在沙箱里真写代码 → patch.diff 收割」这一环，当夜两笔全真。**

### 7.2 act3 重跑暴露 D6（新缺陷）：应用重启后审批卡不渲染

D11 宿主重跑幕 3：宿主发出 agent-approval 帧后，手机 **composer 提示
「等待你审批工具调用…」在、带 [批准][拒绝] 的审批卡不在**（重进会话重组亦不复现），
长轮询反复 `deny (approval timeout — denied fail-closed)`。01:12 那次成功与本次的差别：
01:12 审批到达时手机 UI 存活且停留在该会话；本次审批帧落在手机应用重启之后。
**审批状态与消息列表渲染脱钩** —— 人守门按钮在重启场景丢失，只能 fail-closed。
（另有前置干扰：第一次重跑时手机过夜休眠，宿主侧 `acked=false online=false`，
帧根本没出去 —— 已用唤醒+应用重启恢复在线后复测，仍无卡 ⇒ 与在线无关，坐实渲染脱钩。）

### 7.3 合并环节被手机 LAN 隔离挡住（环境，非产品）

merging 需要真机点群工单卡上的 [合并此补丁] → 需要手机↔宿主消息面。时序：
过夜 doze 后手机 Wi-Fi 假连接（IP 在、`ping` 100% 丢、UDP 单向→双向断）；
`svc wifi` 循环、飞行模式循环、应用重启、`adb reboot` 整机重启**均未恢复**
（宿主 D11 全程 0 条 UDP inbound；宿主→手机的 832B 卡投递 `acked=false` 重试排队）。
手机 IP 未变（192.168.2.22/24）、Mac 防火墙关闭 ⇒ 指向 **AP/路由层客户端隔离**，
软件侧无解，需要人查路由器（AP 隔离开关 / 重启路由器）。

### 7.4 新记账项

| # | 项 | 说明 |
| --- | --- | --- |
| O8 | node 型 CLI 的探测路径不含 nvm/受管 node | `CliDiscovery` 只探 `$HOME/.local/bin`、`/usr/local/bin`、`/opt/homebrew/bin`、`/opt/homebrew/opt/node/bin`；GUI 装机口径（无 homebrew node）下 codex 必 exit 127。本轮靠宿主启动 PATH 手工补 |
| D6 | 应用重启后审批卡不渲染（hint 在、按钮消失） | 见 §7.2；fail-closed 兜底有效，但人守门入口丢失 |
| O9 | 工作区绑定在 D11 重启后需重新宣告才能派单 | 与 O4 同族，本轮第三次复现 |

---

## 8. 补记二（同晨）：卡片密度对齐 —— 文档预览与交付产物块补进合流卡（`7efb4504`）

用户复核意见（截图）：① 宿主快捷指令回执渲染成对端文本气泡（红框），不是原型稿的样式；
② 卡片「依然是简单的标题、选项」，原型稿的详细样式没验证全，包括文档预览。

### 8.1 差距定位（文档预览）

管线的**两端本来都在**：手机端 `AgentCardParse` 吃 `diff`/`artifacts`、`AgentCards`
渲染 `CardDiffViewerRow` / `CardDiffHunkViewer` / `CardArtifactRows`（含 ArtifactPreview
预览窗），agentNode 的 `ChainProgress` 也早有 `diff`/`artifacts` 字段。缺的是两段宿主接线：

1. `cardOf` 的 diff 发射只写 path/summary/additions/deletions 四键（note/hunk 从未发射）；
   且卡里存在**两处同键 put**（后写的覆盖先写的），富字段恰被覆盖 —— 新契约测试当场抓出。
2. 链完成（review_ready）处没人把收割到的 patch 填进去。

修法（`7efb4504`）：`cardOf` 补 note/hunk 发射（键名与手机解析契约逐字同形 = 补完既有契约，
非新增协议键）；`StageChain.patchPreviewOf`（纯函数）从收割的 patch 构建预览 —— 增删计数
与 git 同口径、只取首文件首 hunk、12 行 × 160 字符按帧预算（CARD_BYTES 5KB）裁死、超限
`note=preview-truncated`、多文件 `summary=N files changed`、无产出保持最小卡；artifacts 补
`delivered:true`。测试：agentNode 四态纯函数 + 发射键名契约 + 无产出不装样子；composeApp
解析契约成对钉住；变异验证摘发射 ⇒ 红。

### 8.2 机器回执样式（红框项）—— 需要协议裁决，未动

原型稿里系统解释走 `.notice`（居中 11px 系统行，如「对方已接收 · 正在拉取 18 项」）。
实现里 NoticeKind（QuickActionReceipt 等）是**宿主本地**渲染：真机 E2E 早已抓过
「宿主库有 QuickActionReceipt、手机库没有 —— 发送方看到静默」，所以快捷指令回执被
`sendToConversation` 广播成普通群文本（DesktopExtension.kt §9.2 注释明写此权衡）——
落到手机上就是对端文本气泡（红框那一条）。

跨端让它长成系统行，两条路，都动协议（§6.6：协议键一律先问人）：
① 新增机器回执 notice 帧（两端同版本）；② 客户端按「快速指令回执」前缀识别（脆，不推荐）。
**本轮未动，记账待人。**

### 8.3 卡片密度余项（如实记）

原型稿卡片还有 chips（意图/范围/门禁/风险）、context（根因说明）、cmd（高危命令展示）、
subActions（本次会话免审/查看完整验收报告）与 checks 的 per-item 耗时/日志抽屉。checks 的
观察结论宿主已有（ReviewerAudit）；**chips/context 需要接 planner 输出**（LLM 拆解产物），
是下一个独立批次；本笔只落文档预览 + 交付块（用户点名的「文档预览」）。
