# E2E 终验：干净群会话 · 发起 → 执行 → 完成 → 最终合并（2026-11-01 深夜）

## 判据（用户口径）

> 最终验证的通过标准应为：在一个干净的群会话环境中，从任务发起开始，到任务完成并进行
> 最终合并为止，整个流程是一个完整、可交互的端到端验证，而非通过打补丁或局部修补的方式完成。

## 环境口径（全部干净起点，无历史兼容）

| 项 | 值 |
| --- | --- |
| 被验代码 | `main` @ `e047f4b3`（会话事件流全新方案），工作树 clean |
| 部署包 | `/Applications/Tidings.app`（22:48 构建 = HEAD 内容；class 按字节核验含「没能合并这次改动」「sessionTitleFor」） |
| 宿主 | 23:02 从终端重启，`TIDINGS_CODEX_CLI=/tmp/fake-codex-slow`（确定性桩：24 s 后写 hello.txt + 追加 seed.txt），控制面 :8788 |
| 工作区 | `/tmp/e2e-final/ws-clean`（全新 git 仓，基线提交 `dbace62`）；`POST /v1/agent/workspace` 宣告 `ws-e2e-final`，手机 ACK（23:03） |
| 群 | 手机 UI 建群 **e2e-final**（`092332c7-…`，成员 = Pixel 6 + kedeMac-mini-97，23:06，零历史）；席位 `codex-final`（agentId=codex, defaultWsId=ws-e2e-final）经 settings.json rev2 声明 |
| 手机 | Pixel 6（`e4bd7b99-…`），com.kapp.tenon.debug，全程 adb 驱动 |

## 成功路径读数（发起 → 执行 → 完成 → 合并落盘）

1. **发起**（23:25:19）：群内发送 `@codex-final run again`（@ 胶囊逐字绑定，帧带
   `agentIntent{agentId:codex, wsId:ws-e2e-final}`）。
2. **受理**（23:25:19）：宿主沙箱 `t-1-b2c0093b`；手机出「进行中」卡：
   *ws-clean 正在处理 / 进展 5% / 停止按钮*（截图 s15）——可交互中断点在场。
3. **完成**（23:26:31）：卡原位翻「待合并 · ws-clean 改好了，等你合并 · +2 −0 · 2 个文件」；
   同屏产物卡 `patch.diff 306 B` 状态**免接收**：「已接收 · 已存到 …/downloads/patch.diff」+
   打开/分享，无接收/拒绝决策点（截图 s11）。
4. **最终合并**（23:26:40）：点「合并此补丁」→ 卡记「已由 Pixel 6 处理」→ 宿主日志
   `[RELAY-INVOKE] patch merged into workspace by e4bd7b99… (card diff-t-1-b2c0093b)` →
   **工作区落盘核验**：`git status` = `M seed.txt` + `?? hello.txt`，内容 `<p>journey 232530</p>`。

## 冲突路径读数（同一干净会话内）

1. **发起**（23:29）：`@codex-final try conflict` → 沙箱 `t-2-e89dbee9` → 待合并卡（s16）。
2. **合并受阻**：点「合并此补丁」→ **同一张卡原位翻**：
   药丸「受阻」· 标题「没能合并这次改动」· 红横幅「补丁要新建的文件在工作区里已经有了。
   先把它挪开或提交，再点一次合并」· 保留「合并此补丁」按钮（可重试）（截图 s17）。
3. 宿主日志：`patch merge FAILED (card diff-t-2-e89dbee9): error: hello.txt: already exists in working directory`；
   工作区保持第一次合并后的状态，**未被污染**；失败卡落在**派单群**（take6 修复复验 ✓）。

## 会话洁净度（新显示方案判据）

全程群内只有：用户消息 ×3、任务卡 ×2、免接收产物卡 ×2。
**零**「⚠️ Agent 异常」「✅ stop」「error: …」气泡，**零**杂凑 divider（s13/s17 全屏取证）。

## 本轮发现（记录，不在验证轮内修）

- **首派静默丢失**：23:10:55 第一单（`@codex-final hello.txt`）宿主收到、落库，但零回声。
  根因判定：手机时钟快 13 s，换算后**派单帧与 rev2 群 meta 同一真实秒到达**；派单帧先处理时
  `handleInboundAgentIntent` 里 `seat == null → return` 是静默口（席位已在路上但尚未入 state）。
  用户侧表现 = 消息已发出、界面无任何反馈。第二单起完全正常。建议：seat==null 时落
  「席位同步中」横幅或短延迟重投（与「失败如实」纪律一致）。
- **观察 1**：合并成功后卡片停在「待合并 + 已由 X 处理」（设计即如此：成功只落盘+记决策，
  只有冲突才发新卡）；若要「已合并」终态药丸属新增产品项。
- **观察 2**：本轮任务分段线未出现——divider 消毒后无假标题（目标达成），任务标题分段线
  是否该出现（如「run again」）属可选产品项。

## 证据文件

截图 `/tmp/e2e-final/s10–s17`（working / review_ready / merged / blocked）、
宿主日志 `/tmp/journey/host-final.log`、桩沙箱 `/tmp/tenon-ws/artifacts/t-1-b2c0093b`、
`t-2-e89dbee9`、工作区 `/tmp/e2e-final/ws-clean`。
