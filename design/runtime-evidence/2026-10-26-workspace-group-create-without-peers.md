# 真机实测：工作区群在「没有任何在线设备」时能建出来（2026-10-26）

> **一句话结论：新构建的桌面端在 0 台在线设备时点「一键建群 / 共享」建群成功** —— 群成员只有自己 + 该工作区的 agent 入驻（`virtualAgents` 一条），不再出现「至少再选一个人」；旧构建的同一按钮会拒收并弹那句横幅。

对应提交 `c1e1eabd`（`fix(group): 工作区建群不再要求「至少再选一个人」`）。

## 0. 结论速览（可直接引用）

1. **修复前**：`handleCreateGroup` 对「除自己外 0 成员」一律 `GroupNeedsMember`；工作区建群对话框的成员来源只有 `state.peers.filter { it.online }`，对话框本身不是成员选择器 —— 单机（或对端全离线）时用户**必然**失败，且被指到一个做不到的动作。
2. **修复后（本次实测）**：同一条点击路径在 0 在线设备时建群成功。落库群元数据：`members=[self]`、`ownerId=self`、`rev=1`、`virtualAgents=[{agentId:DSH, hostNodeId:self, name:dsh-ds-bot, wsId:ws-e76a78ba85ef}]`；横幅换成新文案，并直接进入群聊（`1 位成员 · rev 1`）。
3. **普通建群判据没变**：会话列表那颗「建群」仍要求至少一名成员（单测 `aGroupOfOneIsRefused` 绿；反向验证见提交信息）。
4. **手机侧这次只证到「装上了、群同步正常」**：手机没有收到工作区宣告（`0 工作区`），且 compact 布局没有「关联工作区建群」入口 —— 手机侧的工作区建群路径没能走到（见 §5）。

## 1. 环境与隔离

| 项 | 值 |
|---|---|
| 桌面产物 | `:desktopApp:createDistributable`（源 = `c1e1eabd`），替换 `/Applications/Tidings.app` 后 `lsregister -f -R -trusted` |
| 新代码判据 | `/Applications/Tidings.app/Contents/app/app-jvm-*.jar` 里 `handleCreateWorkspaceGroup` 出现 **6** 次；`DesktopShellKt` 有 **3** 处 `invokeinterface DesktopPaneActions.createWorkspaceGroup`（`javap`） |
| 隔离运行 | `TIDINGS_DATA_DIR=/tmp/tidings-verify-data`（真实数据目录的**副本**），`settings.json` 端口改 `27878/27879`，避免与线上实例撞端口 |
| 真实数据 | **未被写入**：实测结束后 `~/Library/Application Support/捎话 Tidings/settings.json` 的 groups 仍是原来 3 个 |
| 手机 | Pixel 6 / Android 15，`com.kapp.tidings` release 由本源码构建，`adb install -r -d` 覆盖安装 |

## 2. 桌面：修复后实测（0 台在线设备）

驱动方式：AppleScript + System Events 读 Compose 的 AX 树，**按 description 找元素并 `perform action "AXPress"`**（不用坐标、不用截图）。

```
1) 工作区 → 「关联工作区建群」
2) 对话框里 DS Bot 一行标着「本地单机」→ 点「一键建群 / 共享」
```

读数（AX 树原文）：

```
AXStaticText | 「DS Bot 协同群」建好了。群里还没有其它设备 —— 对端上线后可从群信息里添加成员。
AXStaticText | DS Bot 协同群, 同事, 还没聊过，打个招呼, 0/1 人在线
AXStaticText | 1 位成员 · rev 1 · 群主 <host>
```

`settings.json` 里新增的那条（原样摘录，未改动）：

```json
{
  "name": "DS Bot 协同群",
  "groupId": "23dcaf2a-6be2-4228-be07-f6cb7d69485d",
  "members": ["a1b2c3d4-6e74-4a7c-aab1-20a1d1284cd6"],
  "ownerId": "a1b2c3d4-6e74-4a7c-aab1-20a1d1284cd6",
  "rev": 1,
  "description": "$HOME/.dsh/bot/workspace",
  "virtualAgents": [
    { "agentId": "DSH", "hostNodeId": "a1b2c3d4-6e74-4a7c-aab1-20a1d1284cd6",
      "name": "dsh-ds-bot", "wsId": "ws-e76a78ba85ef" }
  ]
}
```

三条不变量都在位上：`ownerId ∈ members`（少了自己对方整帧静默拒收）、`virtualAgents.hostNodeId = self`（@ 得到的宿主）、`wsId` 指回工作区行。

## 3. 手机：安装与现状

```
APK      androidApp/build/outputs/apk/release/androidApp-release.apk
sha256   53bf995c293c6f1eda45c2240aaec948725d1fc7e666521906afb50c92363ae2
dex      classes3.dex: createWorkspaceGroup×4, GroupCreatedWithoutMembers×3
安装     adb install -r -d → Success
         firstInstallTime 2026-10-25 13:39:51（数据保留）
         lastUpdateTime   2026-10-26 11:44:53（装的是新包）
```

运行读数：节点页 `3 个节点 · 1 在线`（桌面在线）；会话列表 `群聊 1`（`teahouse-mobile 协同群`，`1/5 人在线`）⇒ 群元数据同步正常。

工作区页：`1 台设备 · 0 Agent · 0 工作区 · 0 会话` —— 手机没收到任何 `agent-workspace` 宣告，因此**没有可建群的工作区行**。

## 4. 门禁与测试

### 4.1 测试矩阵（七项 test 任务，`--max-workers=1`）

- **常规串行轮：BUILD SUCCESSFUL**（`/tmp/matrix-final.log`）——这是判「这批能不能交」的口径。
- 强制重跑第一轮（`--rerun-tasks`）：`:app:jvmTest` 673 例中 **1 例红** ——
  `RelayControllerAgentSessionTest.crossPeerSameSessionIdIsolationAndDeduplication`（握手等待超时，`IllegalStateException`）。该例**单跑绿**、`:app:jvmTest` 全量 `--rerun-tasks` **再跑也绿**；`udpPort = 0` 走临时端口，属等待窗口不够的形态。
- 强制重跑第二轮（`--rerun-tasks`）：`:core:jvmTest` 632 例中 **1 例红**，而且是**另一条** ——
  `LuciolaDiscoveryEngineTest.peerGoesOfflineAfterPeerStops`；该例**单跑绿**。
- **稳定门禁 `:testStabilityGate`（同一套测试连跑 3 轮）：BUILD SUCCESSFUL（4m39s）** —— `/tmp/stability.log`。

判读：两轮强制重跑各红一条、且**不是同一条**、单跑都绿、稳定门禁三轮全绿 —— 这是 `--rerun-tasks` 期间全量重编译把机器压满导致的等待窗口抖动（AGENTS §4 记的同一形态），**不是本次改动引入的回归**；常规串行轮与稳定门禁都绿。

### 4.2 门禁（18 项）

| 门禁 | 结果 |
|---|---|
| cjkLiteral / constructionTrace / staleCommentRef / domainComposeFree | 绿 |
| designSpecDrift / designTokenUsage / designStateSlot / designLocaleParity | 绿 |
| jlinkModules / designScreenDiff / designIcon / designConverge / designFormPairing / designDupFrame | 绿 |
| designDpDebt | **红过一次 → 重跑 `:designDpDebtReport` 后绿** |
| envDependentTestGate | **红（既有）** |
| designPreviewPairingGate | **红（既有）** |
| `tools/unused-imports.py --check` | **红（既有）** |

三条既有红都不是本提交引入，证据是**父提交 `1b5922c4` 里同样存在、且本提交没碰那些文件**：

```
git show 1b5922c4:core/src/jvmTest/.../LuciolaSecureChannelTest.kt        | grep -c 'delay('   → 12
git show 1b5922c4:desktopApp/src/test/.../DesktopLuciolaHostTest.kt       | grep -c 'delay('   → 2
git show 1b5922c4:core/src/jvmTest/.../NodeRuntimeExtraDiscoveryFactoryTest.kt | grep assertFalse → 1
git log -S onExperimentalLuciolaChange -- .../SettingsDataSubpage.kt      → 9b3f399f（luciola 批次）
git diff --name-only 1b5922c4 c1e1eabd | grep -iE 'settings|preview'     → 无
```

`designDpDebtGate` 校验的是**生成物** `docs/plans/2026-10-11-relay-dp-debt-ledger.md`（`docs/plans/` 在 `.gitignore` 里）。本次改动让 `Common.kt` 等文件行数位移，账本随之漂移，重跑生成即一致 —— 这一条不需要进提交。

## 5. 诚实边界（这条证据证不到什么）

1. **量具踩坑**：第一轮我用 AppleScript 的 `click e` 点 Compose 按钮 —— 它**不触发** onClick，于是得到「新构建仍弹旧横幅」的假阴性；改成 `perform action "AXPress"` 后立刻成功。先证明量具能动，再相信读数。
2. **手机侧没走到工作区建群**：手机没有工作区宣告（`0 工作区`），且 compact 布局里没有「关联工作区建群」入口（该入口目前只在两栏布局的会话侧栏接线）。本次只证明「手机装了新包、群同步正常」，**不证明**手机侧工作区建群可用。
3. **旧构建不能靠 `TIDINGS_DATA_DIR` 隔离**：实测旧包（2026-10-25 构建）忽略该变量、直接打开真实数据目录（差点污染用户数据）。原因已查明：该变量由 `d467365a`（2026-10-26，luciola 批次）才引入，旧包里根本没有这条分支。以后拿旧包做 A/B 必须换设备或先备份库。
4. **没测多设备入群**：0 成员时按设计不会发 `group.info`；把成员加进来之后 `virtualAgents` 能否同步给对端，本次未实测。

## 6. 复现命令

```bash
# 1) 构建并替换桌面端（源 = 被测提交）
./gradlew :desktopApp:createDistributable
# 退出旧实例后替换 /Applications/Tidings.app，再注册（否则图标/缓存不刷新）
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister \
  -f -R -trusted /Applications/Tidings.app

# 2) 隔离数据目录跑一个实例（复制真实数据、改端口）
cp -R "$HOME/Library/Application Support/捎话 Tidings/." /tmp/tidings-verify-data/
# settings.json: udpPort=27878, tcpPort=27879
TIDINGS_DATA_DIR=/tmp/tidings-verify-data \
  /Applications/Tidings.app/Contents/MacOS/Tidings

# 3) 手机：覆盖安装（同包名同签名，数据保留）
./gradlew :androidApp:assembleRelease
adb -s <serial> install -r -d androidApp/build/outputs/apk/release/androidApp-release.apk
```

## 7. 证据来源

| 文件 | 内容 |
|---|---|
| `/tmp/matrix-final.log` | 七项 test 任务串行结果 |
| `/tmp/matrix-forced.log` / `/tmp/matrix-forced2.log` | `--rerun-tasks` 两轮（各 1 例 flake，**不同用例**，单跑均绿） |
| `/tmp/stability.log` | `:testStabilityGate` 三轮结果（绿） |
| `/tmp/gates.log` | 18 项门禁逐项输出 |
| `/tmp/tidings-verify-data/settings.json` | §2 那条群元数据 |

（截图不入库：AX 文本读数已足够复现，见 §2。）