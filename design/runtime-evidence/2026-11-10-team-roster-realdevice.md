# 团队编制（声明与展示）真机验收 —— 2026-11-10

> 方案：`docs/team-creation-final-plan.md`（v1 只做声明与展示）；验收判据：方案 §8 与
> `ACCEPTANCE.md` §10e。本文是**文字证据**（截图不入库，按 `ACCEPTANCE §0` 口径）。
> 脱敏：设备序列号 → `<device-serial>`；家网段 → 192.0.2.x（RFC 5737）；真 nodeId /
> groupId / wsId → 同形状合成值。

## 0. 装置与产物

- 手机：Pixel 6（`<device-serial>`，Android 15，IP 192.0.2.22，debug 包 `com.kapp.tenon.debug`）
- 桌面：Mac（`<host>`，IP 192.0.2.82，`/Applications/Tenon.app`）
- 构建产物（合并 HEAD = `a820240e`，验收修复轮 = `16d4a0e6`）：
  - APK：`~/Downloads/relay-build-20261110/androidApp-debug.apk`
    sha256 = `fdf2e98c07ca52faab0cc29d5ee36bdb1ad4e76874417ab61b939293d7cf516a`
  - 桌面：`~/Downloads/relay-build-20261110/Tenon-1.0.0.dmg`
    sha256 = `c5b7c79dd44649b40afdff4c16935183312ac3fb981977d26dc308b4c1d8d364`
  - `/Applications/Tenon.app/Contents/MacOS/Tenon`
    sha256 = `117579002d247965916750fa8cae909e37d5261582aa0f0ba928967c2981f865`
- **新代码证明**（ACCEPTANCE §1b）：
  - APK dex：`TeamRosterDialog` / `setGroupTeamDeclaration` 命中 classes3/4/14.dex
    （classes4.dex 151 处）；compose 资源索引 `assets/composeResources/…/values/strings.commonMain.cvr`
    含 `team_roster_button`。
  - 桌面 jar：`app-jvm-…jar` 的 `RelayControllerGroupsComponentKt.class` 含
    `handleSetGroupTeamDeclaration`（3 处）；`composeApp-jvm-…jar` 含 `TeamRosterDialogKt`；
    资源索引（zh + en）含 `team_roster_button`。
  - 桌面按 §1b `lsregister -f -R -trusted` 重注册后 `open -a`。
- 装机：APK `install -r -d` Success（lastUpdateTime 2026-11-10 05:29 / 06:08 / 06:16 三轮）。

## 1. 验收方式（如实）

- 桌面端 UI 自动化被 macOS TCC 挡住（`osascript` 挂起、`screencapture` 拒绝；
  Computer Use 能力在本会话不可用）——**桌面 UI 动作无法自动化**。桌面写路径由
  `TeamDeclarationWritePathTest`（14 项，含双节点端到端）与合并树全量绿覆盖。
- 手机端到端走**真协议夹具** `scripts/e2e/e2e_team_roster.py`：夹具扮演一台电脑
  （普通成员宿主），以 `GroupMerge` 主权分支的线上形状逐 rev 驱动。**前提**：两端
  `experimentalLuciola` 临时关（legacy 发现路径回复 entry→alive；luciola 发现为
  原生信标协议，Python 夹具不复刻）。验收后两端已恢复 luciola 并复核链路。
- 判据工具：uiautomator dump + 截图目视 + `run-as` 读 `settings.json`（落库真值）与
  `diag/link.json`（closeCount）。**判定看落库与截图，不只看夹具输出**。

## 2. 逐项读数（夹具 `result PASS 37`，退出码 0）

### 2.1 建群底座（17 项全绿）

界面建群（更多 → 建群 → 群名 + 成员多选）：`canConfirm` 禁用/启用两态、info 帧回读
`rev=1`、`members={手机, 夹具}`、`ownerId=updatedBy=手机`、必填键齐全。

### 2.2 T1 成员宿主自声明席位（合并主权分支的线上证据）

| 步骤 | 期望 | 实际 | 判定 |
|---|---|---|---|
| 夹具（普通成员）发 rev2：本机席位（单兵 + defaultWsId） | 手机 apply（**旧版本此处整帧拒收**，即 P0-0.2 修的 F2） | ACK 回执；落库 `rev=2 updatedBy=夹具` | ✅ |
| 群信息页 | agent 行出现、副行 = `@<token>` | `@e2e-lead-2c5d56` 可见 | ✅ |
| 单兵态 | 无团队徽标 | 无 | ✅ |

### 2.3 T2 席位升为团队（3 名组员）

| 步骤 | 期望 | 实际 | 判定 |
|---|---|---|---|
| rev3：同席位 `entityType=team` + 3 组员 | apply | ACK；落库 `rev=3 entityType=team teamMembers=3` | ✅ |
| 群信息 agent 行 | 「团队 (3)」徽标 | 截图 `evidence-badge2.png`：`[智能体] [团队 (3)]` 并排 | ✅ |
| @ token | 不漂 | 副行仍是 `@e2e-lead-2c5d56` | ✅ |
| 会话列表 | 「团队 3 ▸」折叠药丸 | 行内可见（截图 `evidence-expanded.png`） | ✅ |
| 点药丸展开 | 标签行 + 3 成员行 | `@e2e-lead-2c5d56`、`3 个成员`、`coder 编码 / reviewer 审查 / tester 测试` | ✅ |
| 点成员行 coder | 进群会话 + 草稿预填 `@<leader>` | 输入框 `@e2e-lead-2c5d56`（leader token，不是组员名） | ✅ |

### 2.4 T3 越预算整帧拒收（P0-0.3 的线上证据）

| 步骤 | 期望 | 实际 | 判定 |
|---|---|---|---|
| rev4：9 名组员（> TEAM_MEMBERS_MAX=8） | 收端 `validateGroup` 整帧拒收 | **无回执**（发送侧等不到 ACK——§3 的时间形态） | ✅ |
| UI | rev 不动、界面无变化、不崩 | 徽标仍「团队 (3)」；第 9 名组员未出现 | ✅ |
| 反静默 | 不是 mapNotNull 丢条目 | 落库仍是 3 名（`m08` 不存在） | ✅ |

### 2.5 T4 关闭团队（同一写路径反向）

| 步骤 | 期望 | 实际 | 判定 |
|---|---|---|---|
| rev5：single + 空组员 | apply | ACK；落库 `rev=4`（第 4 个被接受的 rev）、`entityType` 缺省（=single）、组员空 | ✅ |
| 群信息 | 徽标消失、@token 保留 | 均成立 | ✅ |
| 会话列表 | 本会话行药丸消失 | 行内作用域判定通过 | ✅ |

### 2.6 回归

- 群会话行仍在、群名未被任何 rev 改动。
- `diag/link.json`：`closeCount` 全程 **0**（夹具断言 + 手工复核）。
- 其它群（含此前的 `teahouse-mobile 协同群`）零改动。

## 3. 验收抓到并当场修复的缺陷（1 条）

**「团队 (3)」徽标在窄屏完全不渲染**（commit `16d4a0e6`）：

- 现象：落库数据正确、折叠药丸正常、逻辑判据经临时探针实测 `seats=1 team=true`，
  但群信息 agent 行看不到徽标——布局缺陷（GroupMemberRow 的名字 Text 先按偏好
  宽度占满，徽标被挤出裁剪边界），单测全绿、桌面宽屏看不出，只有真机窄屏撞得到。
- 修法：名字 Text 加 `weight(1f, fill=false)`（徽标先量，名字拿剩余空间并按需省略）。
- 复核：截图 `[智能体] [团队 (3)]` 并排可见，长名字按需省略。

夹具自身同轮修了三处判据（行内作用域 / 越预算无回执断言方向 / diag 读取，commit
`ccfa996b`），复跑 `result PASS 37`。

## 4. 未在本机验证的项（如实）

- **桌面端「团队编制」对话框的 UI 动作**（开关/预设/保存按钮的真人点击路径）：TCC
  阻断自动化。桌面写路径由双节点单测覆盖；两端装机产物已按 §1b 核验新符号与资源。
- **成员宿主声明被旧版本拒收的对照**：需要一台旧构建对端；兼容性窗口已在
  `PROTOCOL-EXTENSION.md` §14.5 记录为已知限制。
- **输入侧拦截**（多席位切换 / >8 人不可加 / 冲突就地提示）：UI 校验由
  `TeamRosterDialogFormTest` 覆盖；真机侧本夹具只验收端整帧拒收。

## 5. 环境恢复

验收后两端 `experimentalLuciola` 已恢复 `true` 并重启；桌面 ↔ 手机链路复核：
`online=true linkConnected=true closeCount=0 carrier=plaintext`。
