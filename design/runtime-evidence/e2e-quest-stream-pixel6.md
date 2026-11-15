# 支线日志流（Phase 1）+ 便签派发（Phase 2）真机验收 —— Pixel 6 / Mac

日期：2026-11-15（feat/quest-roadmap 合并 main 后构建）
设备：Pixel 6（com.kapp.tenon.debug）/ Mac（worktree 桌面构建，Tenon.app）

## 判据与读数

1. **链路**：全程 closeCount = 0（多轮装重/重启/派单期间），carrier = tls，linkConnected = true。
2. **便签派发 → quest-dispatch 帧 → 宿主 QuestRunner**：手机「支线派单」发指令 →
   宿主 QuestLedger 出行（RUNNING，worktree `tenon-quests/<questId>` 创建、分支
   `quest/<questId>`）→ 宿主 assistant 会话出 assistant_quest 卡（quest_id 落库）。
3. **quest-notice 回执 → 便签原位翻面**：手机便签行 quest_id = qb90853754020、
   agent_state = accept（RUNNING），UI 出「进行中」卡（进入作战室/取消按钮）——
   一任务一卡，无第二条（§8.1）。
4. **作战室终端输出面板**（Phase 1 核心）：进入作战室出「终端输出」面板，实时滚动
   宿主 CLI 输出（agy-cli 的 OAuth 提示全文可见）——stream-sub → 宿主环形缓冲回放+
   实时 → stream-chunk → 面板。链路走 TLS 长连接（LinkClient 上行/LinkServer 下行）。
5. **降级形态**（构建缺原生库时实录）：面板如实显示「日志流不可用」，无假流。
   该形态同时暴露并驱动修复了三件事（见下）。

## 验收中暴露并修复的缺陷（全部有修+测试）

1. **宿主老库血统跳过 quest 迁移**（self-heal 补列补表，SchemaSelfHealTest 钉住）。
2. **quest-dispatch 上行无链路通路**：手机拨号侧 LinkClient 无发送出口 → sendEnvelope
   补上；quest-dispatch/notice 改走 sendControlFrame 三路层级，不入离线队列。
3. **回执目标竞态**：wireNoticeTargets 按 questId 在 dispatch 返回后登记，首条 RUNNING
   通知必然落空 → 改按 clientToken 于 dispatch 前登记（通知全量携带 clientToken）。
4. **手机侧便签行无 senderId** → 流宿主解析加 questPairNodes 兜底（与便签上行的
   宿主选择同一条链）。

## 遗留（本轮不修，已记录）

- agy-cli 的 Google OAuth 登录过期 → CLI exit=1（面板实时可见），属于 agent 侧账号状态。
- FAILED quest 的 task.log 随清场丢失 → 失败原因不可追溯（建议终态摘要带日志尾部）。
- luciola-unavailable 在缺原生库的构建上有横幅、界面上无原因（可后续加 reason 展示）。
