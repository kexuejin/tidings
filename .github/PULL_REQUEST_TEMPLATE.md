## 变更说明 (Summary of Changes)
<!-- 请简要总结本次 Pull Request 所包含的改动内容以及解决的问题。 -->

## 关联 Issue (Related Issues)
<!-- 关联的 Issue 编号，例如: Closes #12, Fixes #34 -->

## 改动类型 (Type of Change)
- [ ] 缺陷修复 (Bug fix)
- [ ] 新功能 (New feature)
- [ ] 协议增强/变动 (Protocol PPE-1 update)
- [ ] 架构重构 (Code refactoring & convergence)
- [ ] 文档与质量门禁 (Documentation / Gates / CI)

## 质量验证清单 (Quality Checklist)
在发起 PR 之前，请确认已在本地执行并通过了以下门禁测试：
- [ ] `./gradlew :core:allTests :composeApp:allTests :desktopApp:test :agentNode:test` 全量跑绿
- [ ] `./gradlew :desktopApp:compileKotlin` 编译无错
- [ ] `./gradlew :cjkLiteralGate` 无新增未抽取的 CJK 硬编码字面量
- [ ] `./gradlew :staleCommentRefGate` 无新增陈旧行号引用 (`X.kt:NN`)
- [ ] `./gradlew :constructionTraceGate` 无新增施工痕迹
- [ ] `python3 tools/unused-imports.py --check` 无未使用的 import 语句
