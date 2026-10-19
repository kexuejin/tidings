# 贡献指南 (Contributing Guide)

感谢你关注并有意参与「捎话 (Tidings)」的开源建设！本项目致力于构建一个零中心、无账号、局域网纯点对点的现代化传讯与 Agent 协同系统。

为了保证跨端一致性与高工程质量，我们实施严格的自动化门禁与代码规范。提交代码前请仔细阅读本指南。

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
├── agentNode/    # Headless Agent 协同服务与 WsGateway 验证网关
├── tools/        # 自动化质量门禁与辅助工具脚本
├── docs/         # 架构文档、设计契约与 ADR 决议
└── .github/      # CI 工作流与 issue/PR 模板
```

---

## 3. 门禁与质量检查清单

在提交 Pull Request 之前，必须在本地通过全量门禁检查。CI 工作流将严格核验以下项目：

### 核心测试与编译

在仓库根目录下执行：

```bash
# 1. 运行所有模块的自动化测试
./gradlew :core:allTests :composeApp:allTests :desktopApp:test :agentNode:test

# 2. 验证桌面端编译
./gradlew :desktopApp:compileKotlin

# 3. 运行 Android 宿主端 UI 截图门禁（保证原型与渲染像素对齐）
./gradlew :androidApp:validateDebugScreenshotTest
```

### 静态质量与设计规范门禁

```bash
# 4. 检查硬编码中文字面量（禁止在代码中硬编码中文，必须收敛至 Strings 资源）
./gradlew :cjkLiteralGate

# 5. 检查注释中的陈旧行号引用（禁止使用 X.kt:NN，重构会导致行号腐坏，请使用符号名）
./gradlew :staleCommentRefGate

# 6. 检查注释中的施工残留标记（禁止遗留时间戳、私人绝对路径、内网临时 IP 等）
./gradlew :constructionTraceGate

# 7. 设计契约与 Token 覆盖门禁
./gradlew :designSpecDrift :designTokenUsageGate :designStateSlotGate

# 8. 检查未使用的 Import 语句
python3 tools/unused-imports.py --check
```

*提示：如果有未使用的 import，可运行 `python3 tools/unused-imports.py --fix` 自动剔除。*

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
     - `agentNode: handle token authentication in WsGateway`
     - `composeApp: extract common NodePresence components`
3. **改完要重启什么**（只影响开发，不影响使用者）:
   - 桥（`dsh-tidings`）的适配器改动**要重启 DSH 宿主**才生效；热改桥不会生效。

4. **协议兼容性保证**:
   - 凡涉及 `PPE-1` 协议扩展与传输层修改，严禁破坏已有的消息信封（Envelope）与握手契约。
   - 协议改动须在 `PROTOCOL-RATIONALE.md` 中补充设计理由与对应的测试说明。
