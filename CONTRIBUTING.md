# 贡献指南 (Contributing Guide)

感谢你关注并有意参与「捎话 (Tidings)」的开源建设！本项目致力于构建一个零中心、无账号、局域网纯点对点的现代化传讯与 Agent 协同系统。

为了保证跨端一致性与高工程质量，我们实施严格的自动化门禁与代码规范。提交代码前请仔细阅读本指南。

> **动手改代码之前，先读 [AGENTS.md](AGENTS.md)。** 本指南讲「怎么提交」；
> AGENTS.md 讲「哪些做法在这个仓会判红、会静默丢数据、或者真机上翻过车」，
> 以及每一条的来历。**用 AI agent 干活时尤其重要** —— 那份文件就是写给它的约束。

---

## 1. 开发环境要求

- **JDK**: OpenJDK 21（推荐使用 Temurin 21）
- **Android SDK**: `compileSdk 36`，`buildToolsVersion 36.1.0`
- **Kotlin**: 2.1+ (Kotlin Multiplatform & Compose Multiplatform)
- **操作系统支持**: macOS / Linux (macOS 支持 iOS Simulator 构建与调试)
- **Python**: Python 3.9+ (用于运行未引入 import 检查脚本与辅助脚本)

---

## 2. 仓库结构说明

```
relay/
├── core/         # 纯 Kotlin 协议与传输核心（无 Compose 依赖）
├── composeApp/   # Compose Multiplatform 共享 UI（Android / iOS / Desktop 共用）
├── androidApp/   # Android 宿主外壳与截屏门禁
├── desktopApp/   # 自研桌面端外壳（基于 Compose Multiplatform）
├── agentNode/    # Headless Agent 协同服务（控制面 HTTP 底座）
├── tools/        # 自动化质量门禁与辅助工具脚本
├── docs/         # 架构文档、设计契约与 ADR 决议
└── .github/      # CI 工作流与 issue/PR 模板
```

---

## 3. 门禁与质量检查清单

**命令清单不在本文件里重复** —— 唯一权威是 [AGENTS.md](AGENTS.md) §1（测试九条 + 门禁十条）
与 README「质量门禁与验证」。三处同源（AGENTS.md §1 / README / `.github/workflows/ci.yml`），
本文件只是指路。

> 这里曾经抄过一份，抄的时候漏了 `:app:jvmTest`、`:cli:test`、`:agent-contract:test`、
> `:orchestration:test`，还写着「与 AGENTS.md §1 同口径」—— 于是 `:cli`（领域层零 Compose 的
> 可执行验收物）在一份「必须通过」的清单里消失了很久。**复制清单 = 制造第四个真相。**

要点（判据与「破了怎么办」见 AGENTS.md §2.1）：

- 测试与门禁都加 `--continue`，否则第一个红就停，你看到的「绿」只是没跑到。
- 判「这批能不能交」时加 `--max-workers=1`（并发会饿死异步用例，见 AGENTS.md §1）。
- 未使用的 import：`python3 tools/unused-imports.py --check`，修复用 `--fix`。
- 动了 UI / 设计 token / 截图夹具，另跑 `tools/design-contract-gate.gradle.kts` 里注册的设计门禁。

---

## 4. 提交规范

1. **分支命名**:
   - `feat/feature-name`: 新功能或协议增强
   - `fix/issue-description`: 缺陷修复
   - `refactor/scope`: 架构重构与代码收敛
   - `docs/doc-name`: 文档与治理说明
2. **Commit Message 风格**:
   - 保持清晰简洁，建议包含组件标签（例如 `core`, `agentNode`, `composeApp`, `docs`）：
     - `core: fix mutex deadlock in Messenger retry queue`
     - `agentNode: handle token authentication in ControlPlane`
     - `composeApp: extract common NodePresence components`
3. **改完要重启什么**（只影响开发，不影响使用者）:
   - 桥（`dsh-tidings`）的适配器改动**要重启 DSH 宿主**才生效；热改桥不会生效。

4. **协议兼容性保证**:
   - 凡涉及 `PPE-1` 协议扩展与传输层修改，严禁破坏已有的消息信封（Envelope）与握手契约。
   - 协议改动须在 `PROTOCOL-RATIONALE.md` 中补充设计理由与对应的测试说明。

---

## 5. 重写历史（只在必须时读）

> 推送是这个仓唯一**不可撤销**的操作（公开仓，历史一旦上去就删不掉）。日常提交看 §4 就够；
> 本节只在「历史本身不可评审」或「历史里有本机身份要摘掉」时才用。判据与推送前自检命令
> 在 [AGENTS.md](AGENTS.md) §7 与 §7.0。

### 5.1 内容不变的重写（重整提交）

只有在「历史本身不可评审」时才重写（提交太碎、混进了不该有的文件）。这类重写**不碰任何
字节**，只重新组织提交。三条硬要求：

1. **先做一份真备份**：`git bundle create ~/pre-rewrite-<日期>.bundle --all`。
   **`git branch backup/<日期> HEAD` 不算备份** —— filter-repo 重写**所有 ref**，那个分支会被
   一起映射到新提交上，备份跟着一起被改掉。备份必须是 `.git` 之外的独立文件。
2. **用树哈希证明内容零变化**，不要靠肉眼：`git rev-parse <备份>^{tree}` 必须等于
   `git rev-parse HEAD^{tree}`（备份在 bundle 里就先
   `git fetch ~/pre-rewrite.bundle 'refs/heads/main:refs/backup/main'` 再比）。
   但它**只证明末端**：中间提交理论上仍可能被改（改了又改回来）。实际上只关心末端就够了
   —— 测试与门禁只跑末端，所以此时**不必重跑测试冒充「又验了一次」**。
   这句话的边界必须写清，否则下一个人会拿它给脱敏重写背书（见 §5.2）。
3. 用 `git push --force-with-lease`，不要 `--force`：远端若被别人动过应当拒绝，而不是把
   别人的提交冲掉。

重写后把 bundle 留着，等验收通过再由人删。另外 filter-repo 会**顺手删掉 `origin` remote**
（防误推），跑完要重新 `git remote add origin …` 才能推 —— 别以为那是网络故障。

### 5.2 内容必须变的重写（脱敏）

把真机序列号、家网段、个人路径从历史里摘掉只能靠改 blob —— 于是 §5.1 那三条**全部不够**。
下面每一条都是踩过的：

1. **`--blob-callback` 给的文件内容是「函数体」，不是「函数定义」。** 写成
   `def blob_callback(blob):` 会创建一个**永远不会被调用**的嵌套函数：filter-repo 正常结束、
   退出码 0、一个字节都没改。先用两条提交的临时仓验一遍替换真的生效，再动真仓。
2. **按判据扫，不要按样例扫。** 第一遍只替换了手边看到的那段地址，真正的家网段（一整条
   `/64` 加几条主机地址）原样留着 —— 因为「我看到的样本」不等于「全集」。先按前缀把全集
   列出来（`git grep -nE "(2408|2409|240e):"`），再逐条判定要不要改。
3. **内容变了 ⇒ 必须重跑测试与门禁。** 这跟 §5.1 正好相反：脱敏改注释与文档，
   `constructionTraceGate`、`cjkLiteralGate` 都可能因此变红；树哈希也必然全变，证明不了任何事。
4. **所有文档里的短 SHA 立刻失效。** 重写后每个 `<旧短 SHA>` 都指向不存在的对象，`git show`
   直接落空。用 `.git/filter-repo/commit-map`（旧 SHA → 新 SHA）逐条重映射；映射不上的
   （更早的重写留下的死引用）**删掉引用或换成符号名**，别留着让人查不到。
   实测本仓一次脱敏重写后有 **77 处**短 SHA 引用失效。
   **这条也管本文自己**：拿一个真 SHA 当反例写进文档，等于在文档里留一条新的死引用 ——
   要写就写 `<旧短 SHA>` 这种形状。
5. **`--force --tags` 会把本地备份标签一起推上去。** 用 `--force-with-lease` 且只推分支；
   备份标签推上去等于把旧历史又公开一遍 —— 白重写。
6. **force-push 不能召回旧对象。** 它只移动 ref。旧提交在 GitHub 上仍可按 SHA 访问，直到
   删库重建或联系 GitHub Support 做 GC。所以这条纪律的价值是**别让它上去**，不是「上去之后
   还能补救」。
7. **`--blob-callback` 只改内容，一个字都不碰提交说明。** 序列号、内网 IP 经常也写在提交
   信息里（「真机 <serial> 的 APK sha256 …」），blob 回调看不见它们。要挂
   `--message-callback`（它必须 `return message`）。**而且你自己的提交信息也是泄露面**：
   写「把 `2408:…` 换成文档段」这种说明，等于在历史里又留一份 —— 本仓干过两次。

### 5.3 已经公开之后怎么查（树 + 全历史）

重写之后 `origin/main..HEAD` 是空的，AGENTS.md §7.0 的 ① ② 查不到任何东西。这时要查的是
**已经公开的那一份**，判据也不同：只查「必须为零」的形态。

```bash
# 树：个人绝对路径 / 公网 IPv6 前缀（2408 / 2409 / 240e 是中国大陆常见分配段）。
# 第二个 grep 排除文档里的占位用户名（me / demo / ci 那一类）。
# 注意这两条命令**自己也会命中自己**：注释里照抄一个真路径或真前缀，守卫就红了 ——
# 所以下面刻意不写实例（这正是 AGENTS.md §4 说的「守卫被自己的注释绊倒」）。
git grep -nE "/Users/[a-z][a-z0-9._-]*/|/Volumes/[A-Za-z]|(^|[^0-9a-f])(2408|2409|240e):[0-9a-f]{4}" \
  | grep -vE "/Users/(me|demo|ci|x|a|work|workspace|someone|you|user)/"

# 全历史：每个提交的 `+` 行扫一遍（150 条约 3 秒）。空输出才是干净。
git log --all -p 2>/dev/null \
  | grep -nE "^\+[^+].*(/Users/[a-z][a-z0-9._-]*/|/Volumes/[A-Za-z]|(2408|2409|240e):[0-9a-f]{4})"

# 最后一步不可省：从公开仓深 1 克隆再查一遍。本地 .git 里还留着 filter-repo 的 refs，
# 本地查绿不等于远端绿。
git clone --depth 1 <公开地址> /tmp/audit && git -C /tmp/audit grep -nE "<同上>"
```

`192.168.x.x` **故意不在上面三条里**：代码里本来就有一批家网段（理由与清单见
`docs/privacy-scan-2026-10-29.md` §4）。把它加进「必须为零」的检查，守卫会永远红。
