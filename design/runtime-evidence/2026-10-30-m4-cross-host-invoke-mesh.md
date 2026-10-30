# M4 · P2P 分布式网格 —— 跨端派单网道读数

- 日期：2026-10-30（M4 收尾轮，接手会话完成）
- 产物：`~/Downloads/relay-build-2026-10-30-m4/`（`Tidings.app` + `androidApp-debug.apk`，
  sha256 见该目录 `sha256.txt`；桌面最终二进制
  `10d069496fb257665aefb8e3e9ee0bb6485b6cc3215eeab830222ace8da6c504`）
- 装机核对：桌面替换 `/Applications/Tidings.app` 后按流程 lsregister 重注册；
  桌面 `agentNode-*.jar` 内 grep 到 `AgentInvokeDispatcher.class`，`app-jvm-*.jar` 内
  `AgentInvokeExecutor` / `AgentInvokeLeaseTable`；手机 APK 解包 dex grep 到
  `dispatchCrossHostInvoke`（10 处）/ `AgentInvokeLease`（17 处）—— 装的是 M4 代码。
- 拓扑如实记账：**本会话只有一台桌面 + 一台手机**。双桌面跨端帧路径由 Layer 4 双节点
  仿真覆盖（`TwoNodeAgentInvokeTest`，真 UDP/TCP 回环）；真机环节降级为
  「桌面自派（发起方 = 宿主）+ 手机 @agent 完整回路」，未做双机验收，不做包装。

## Layer 4 · 双节点仿真（:app:jvmTest，`TwoNodeAgentInvokeTest`，3 轮连跑全绿）

- 跨端派单到达：A 派单 → `agent-invoke` 帧点对点到 B → 执行器逐字拿到 prompt →
  结果帧回传 → 租约翻 Done、摘要随帧回传。
- 零刷屏（核心断言）：两端群存储本回路新增行**全部是看板卡**（`card != null`），
  无一行纯文本；「没产生」与「被判坏帧删掉」可区分 —— B 执行器捕获到 prompt 证明
  任务真到达，若它走过群文本，合法帧必落库，与断言矛盾。看板卡两端同信封 id，
  署名宿主是 Leader。
- 断连超时：宿主停机后派单，租约在 `deadlineMs` 到期原子翻 Timeout
  （翻转时刻 ≥ 期限），超时卡带 [重试]/[降级为本地执行]。
- 审批防重跨端回路：B 首个判定生效并 rev+1 广播全群；A 晚到点击幂等丢弃
  （判定恰好一次、无第 4 行）；再点不同动作正常生效（丢弃 ≠ 消息卡住）。
- 反向验证两路：摘 `handleOnAgentInvoke` 接线 ⇒ 跨端与审批两条红；
  摘 `startLeaseTicker` ⇒ 超时条红。恢复全绿。

## `/v1/agent/invoke` 路由（真桌面、真打包）

- 缺字段 ⇒ 400 `bad-request`；群里没有这个 agent ⇒ 404 `agent-not-found`；
  自派（发起方 = 宿主同机）⇒ 200 + invokeId，随后租约翻 Done、看板卡进群、
  插件台账出现对应会话（`state=done`）。
- 路由对账裁定（追踪器 M4）：新开路由成立，`/v1/agent/run` 原语义未动。

## @agent 完整回路（M2 遗留复测 —— 本轮完成）

前置配置（如实记账，均为宿主主权动作的等价物）：

- 手机（Pixel 6，debug 包）加入桌面自有的 teahouse-mobile 群：停机改桌面
  `settings.json`（成员 + rev 12 + 昵称），群主权限矩阵内的变更，备份留存
  `settings.json.backup-before-m4-smoke.json`。
- `dsh-echo-bridge` 插件（echo 实现的副本，leader agentId = `dsh`）放入插件目录，
  宿主自动拉起并注册 `online` —— 给已有的 DSH 席位一个真实执行器。
- 手机执行权：`PUT /v1/agent/exec-devices {agentKey:"DSH", allowed:true}`
  （§6.3 可信设备面板的同一条 HTTP）。

最终干净一轮（08:04，两台设备都装最终构建）：

- 手机胶囊唤起（键入 @ 逐字符，`@dsh-teahouse-mobile · 新任务`），发送
  「@dsh-teahouse-mobile  m four all green」。
- 桌面：授权放行（GrantedDevice 档）→ 执行桥 → 插件执行（台账 `state=done`，
  会话标题 = **剥掉 @ 之后的任务话原文**，prompt 逐字到达）→ 卡片进群。
- 手机：卡片「Echo task 第 1 版 进展 100%」渲染（M3 卡片引擎），**无任何
  「没有执行」横幅**；群流零跨端中间文本（桌面侧本轮只新增 1 行看板卡）。
- 桌面 FTS 不变量顺带复核：`MessageFts_docsize` 7860 ≡ `MessageEntity` 7860。

## 冒烟抓到并当场修复的缺陷（各有反向验证，均红→绿）

1. **pluginForAgent 用 slug 当身份匹配器**（`cf153c2e`）：席位 `DSH` 被
   slugForSenderId 抹成空串 ⇒ 执行桥答 no-plugin，卡片不上群、台账零条，三处全静默。
   修：身份匹配换 core 的 `sameAgent`（与协议层同一条精度容忍）。真机复现：修前
   HTTP 200 + invokeId 但零会话零卡片；修后全通。`PluginForAgentMatchTest` 4 条。
2. **@ 意图回路的 prompt 丢失**（`e79d5252`）：意图帧只带目标，组件不收正文 ⇒
   插件收到空串（真机读数：会话标题为空）。修：正文随调用进组件，剥 @ 即 prompt。
   `AgentIntentPromptAndGrantBridgeTest`。
3. **「没有执行」横幅与执行各说各话**（`e79d5252` + `48c41f96`）：宿主侧横幅路径
   硬编码 `grantAllows=null`，与执行路径的台账不同源 ⇒ 卡片与否认同屏；发送侧自检
   又对远端宿主的授权越权代言。修：宿主横幅消费执行同一份台账；带意图的发送跳过
   发送侧本地自检（授权归宿主）。真机取证：修前装机横幅先出（截图），修后同流程零横幅。

## 如实记录的边界

- 双桌面跨端（A 派单 → B 执行）的真机验收**未做**：只有一台桌面。Layer 4 仿真覆盖
  协议路径；自派读数覆盖 HTTP 入口 + 域层租约 + 执行桥 + 看板卡的真实打包链路。
- 跨端意图被宿主**拒绝**时，发起端目前没有回执（宿主拒绝只落宿主本机台账）——
  发起端要看到「为什么没执行」需要一条宿主 → 发起方的拒绝回执，留待后续里程碑。
- 发送侧横幅的无条件化在双节点装置上出现 30s 挂起（与横幅块和乐观发送的交互
  有关，未深查）—— 守卫本身由真机红/绿两态取证，装置上的时序疑点如实留档。
- 冒烟对真机数据做了一处停机配置注入（群成员 + rev），已完成并保留备份；
  桌面授权台账新增一条 `DSH`/手机的执行授权与插件目录 `dsh-echo-bridge`，
  均为产品配置而非代码改动，验收后可由机主在面板里回收。
