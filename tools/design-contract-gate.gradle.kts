// SPDX-License-Identifier: GPL-3.0-only
//
// 设计契约门禁（design contract gates）。
//
// 由根 build.gradle.kts 通过 `apply(from = "tools/design-contract-gate.gradle.kts")`
// 引入，注册四个入口：
//
//   :designSpecDrift          spec 与代码逐键漂移 + 书写段引用完整性（默认入口，CI 跑它）
//   :designSpecProject        重新投影 design-spec.json（改了代码之后跑）
//   :designTokenUsageGate     token 消费率棘轮（采用面不许缩、字面量欠债不许涨）
//   :designTokenUsageBaseline 有意变更后重记消费率基线
//   :designStateSlotReport    状态槽覆盖报告（打印到构建日志）
//   :designStateSlotBaseline  有意变更后重记状态槽基线
//   :designConvergeReport     设计侧取值收敛提案（只读；打印/落文档，不改设计源）
//   :designConvergeGate       设计侧取值棘轮（只许收敛；已走变量的部分不许退回字面量）
//   :designConvergeBaseline   有意变更后重记设计侧取值基线
//   :designPreviewPairingGate     配对台账「Preview 核对」棘轮（新写进台账的跨域配对立刻红）
//   :designPreviewPairingBaseline 有意变更后重记配对核对基线
//   :designScreenDiffGate         逐屏取值差集棘轮（原型有的字号/间距/圆角/字重，代码这边有没有）
//   :designScreenDiffBaseline     有意变更后重记逐屏差集基线
//   :designLocaleParityGate       多语言资源对账棘轮（新加一个只写中文的键立刻红）
//   :designLocaleParityBaseline   有意变更后重记多语言基线
//   :designFormPairingGate        台账配对**形态**棘轮（桌面屏配手机帧 / 手机屏配桌面帧立刻红）
//   :designFormPairingBaseline    有意变更后重记形态配对基线
//   :designDpDebtReport       生成逐屏 .dp 欠债账（goal ④ 验收工件）
//   :designDpDebtGate         逐屏 .dp 欠债账不许与源码脱钩（产物是生成物，手改即红）
//   :designContractSelfTest   十二个工具的量具自检（已知答案必须报对）
//
// 为什么是这三个而不是像素比对
// ---------------------------
// 颜色一直精致，是因为它有 `check-design-palette.py` 逐屏对色**能判红**；间距同样有
// JSON、有 8 档，却全仓 0 处消费也无人报红。所以这里补的是"能判红"的三件事：
//   ① spec 与代码逐键一致（漂移立刻红，杜绝两套真相）；
//   ② 消费率棘轮（新代码绕开 token 写字面量立刻红）；
//   ③ 状态槽覆盖（分母是状态槽不是屏数，删掉一个预览就掉覆盖）；
//   ④ 配对台账核对（台账里那一格写的是"有预览"，可它可能渲染的是**别的屏**——
//      实测 111 条里 53 条如此；棘轮只许变少）。
// 刻意**不做**像素级间距/字阶测量：design-spec.json 里 majorRegionSimilarity 那套阈值
// 已被实测证伪（纯白图 vs 真实桌面屏 0.961，比同屏跨引擎 0.930 还高，276 对里 77.2%
// 通过），再叠一层无分辨力的量具只会制造假绿。
//
// 所有工具都假设**工作目录 = 仓库根目录、--root .**（Python 侧的路径拼接按
// 相对仓库根解析）。
//
// 根目录即 project.projectDir。

import org.gradle.api.GradleException
import java.io.File

val designRepoRoot: File = project.projectDir
val designPython: String = (project.findProperty("designPython") as? String) ?: "python3"

/**
 * 跑一个 Python 设计工具，返回退出码；输出原样进构建日志（门禁红了要能直接看到原因）。
 * 不吞异常：非零退出由调用方决定是抛 GradleException 还是只提示。
 */
fun runDesignTool(script: String, vararg args: String): Int {
    val scriptFile = File(project.projectDir, "tools/$script")
    if (!scriptFile.isFile) {
        throw GradleException("设计工具不存在：${scriptFile.path}（门禁脚本与工具必须成对提交）")
    }
    val cmd = listOf(designPython, scriptFile.path) + args
    logger.lifecycle("  \$ ${cmd.joinToString(" ")}  (cwd=${designRepoRoot.path})")
    val proc = ProcessBuilder(cmd)
        .directory(designRepoRoot)
        .redirectErrorStream(true)
        .start()
    val output = proc.inputStream.bufferedReader().readText()
    if (output.isNotBlank()) {
        output.trimEnd().lineSequence().forEach { logger.lifecycle("  | $it") }
    }
    return proc.waitFor()
}

fun designWorkspaceNote(): String {
    val head = try {
        ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(designRepoRoot).redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText().trim().ifEmpty { "unknown" }
    } catch (_: Exception) {
        "unknown"
    }
    val dirty = try {
        val proc = ProcessBuilder("git", "status", "--porcelain")
            .directory(designRepoRoot).redirectErrorStream(true).start()
        if (proc.inputStream.bufferedReader().readText().isBlank()) "clean" else "dirty"
    } catch (_: Exception) {
        "unknown"
    }
    return "HEAD=$head ($dirty)"
}

tasks.register("designSpecDrift") {
    group = "verification"
    description = "设计契约：spec 的 tokens/usage 必须与代码逐键一致，书写段引用必须完整"
    doLast {
        logger.lifecycle("设计契约门禁 · spec↔code 漂移")
        logger.lifecycle("  工作树: ${designWorkspaceNote()}")
        val code = runDesignTool("design-spec-project.py", "--check", "--root", ".")
        if (code != 0) {
            throw GradleException(
                "spec 与代码漂移或书写段不完整（退出码 $code）。改了代码就跑 " +
                    "`:designSpecProject` 重投影；书写段要人来改。"
            )
        }
    }
}

tasks.register("designSpecProject") {
    group = "verification"
    description = "设计契约：从代码重新投影 design-spec.json（改代码后跑，然后 review diff）"
    doLast {
        logger.lifecycle("设计契约 · 重新投影 design-spec.json")
        val code = runDesignTool("design-spec-project.py", "--write", "--root", ".")
        if (code != 0) throw GradleException("投影失败（退出码 $code）")
    }
}

tasks.register("designTokenUsageGate") {
    group = "verification"
    description = "设计契约：token 消费率棘轮（采用面只许扩大、字面量欠债只许减少）"
    doLast {
        logger.lifecycle("设计契约门禁 · token 消费率棘轮")
        logger.lifecycle("  工作树: ${designWorkspaceNote()}")
        val code = runDesignTool("design-token-usage.py", "--check", "--root", ".")
        if (code != 0) {
            throw GradleException(
                "token 消费率棘轮违规（退出码 $code）：新代码请用 MaterialTheme.colorScheme/" +
                    "typography/shapes/RelayElevations/LocalAppSpacing.current，不要写字面量。"
            )
        }
    }
}

tasks.register("designTokenUsageBaseline") {
    group = "verification"
    description = "设计契约：重记 token 消费率基线（真的还了债/新增了 token 采用之后才跑）"
    doLast {
        logger.lifecycle("设计契约 · 重记 token 消费率基线")
        // 第二十个洞：欠债上涨/采用面缩小/新增档外半径时必须给理由，
        // 否则 --baseline 就是擦红灯的后门（另两个棘轮工具都有这道守卫，只有这里没有）。
        val reason = (project.findProperty("tokenUsageBaselineReason") as String?)?.trim().orEmpty()
        val extra = if (reason.isEmpty()) listOf<String>() else listOf("--reason", reason)
        val code = runDesignTool("design-token-usage.py", "--baseline", "--root", ".", *extra.toTypedArray())
        if (code != 0) {
            throw GradleException("重记 token 消费率基线失败（退出码 $code）：若确有欠债上涨要接受，" +
                "请加 -PtokenUsageBaselineReason=\"...\" 说明是哪一维、为什么。")
        }
    }
}

tasks.register("designStateSlotReport") {
    group = "verification"
    description = "设计契约：打印状态槽覆盖报告（分母=状态槽，不是屏数）"
    doLast {
        logger.lifecycle("设计契约 · 状态槽覆盖报告")
        val code = runDesignTool("design-state-slot-report.py", "--root", ".")
        if (code != 0) throw GradleException("状态槽报告失败（退出码 $code）")
    }
}

tasks.register("designStateSlotGate") {
    group = "verification"
    description = "设计契约：状态槽覆盖棘轮（已覆盖的状态槽不许掉回未覆盖）"
    doLast {
        logger.lifecycle("设计契约门禁 · 状态槽覆盖棘轮")
        val code = runDesignTool("design-state-slot-report.py", "--check", "--root", ".")
        if (code != 0) {
            throw GradleException("状态槽覆盖缩小（退出码 $code）：补回对应屏的 @Preview，或有意收缩时重记基线。")
        }
    }
}

tasks.register("designStateSlotBaseline") {
    group = "verification"
    description = "设计契约：重记状态槽覆盖基线（有意收缩或有新证据之后才跑）"
    doLast {
        // 缩小必须带理由（第七个量具洞）：不给理由时工具会 FAIL，
        // 想有意缩小就 -PslotBaselineReason="为什么"。
        val reason = (project.findProperty("slotBaselineReason") as String?)?.trim().orEmpty()
        val extra = if (reason.isEmpty()) listOf<String>() else listOf("--reason", reason)
        val code = runDesignTool("design-state-slot-report.py", "--baseline", "--root", ".", *extra.toTypedArray())
        if (code != 0) {
            throw GradleException("重记状态槽基线失败（退出码 $code）：若因有意收缩，请加 " +
                "-PslotBaselineReason=\"...\" 说明是哪条判据变严、影响哪些单元。")
        }
    }
}

tasks.register("designContractSelfTest") {
    group = "verification"
    description = "设计契约：十一个工具的量具自检（喂已知缺陷，必须报红；喂良性变更，必须不报）"
    doLast {
        logger.lifecycle("设计契约 · 量具自检")
        var failures = 0
        // 所有工具统一以仓库根目录为基准：
        // 契约四件套 + 多语言对账用 --root .，
        // 收敛/账本/图标工具用 --repo（相对仓库根，cwd 已是仓库根），
        // 配对核对与逐屏差集工具用 --root .。
        val rootTools = listOf("design-spec-project.py", "design-token-usage.py",
                               "design-state-slot-report.py", "design-locale-parity.py")
        val repoTools = listOf("design-converge.py", "fidelity-ledger.py",
                               "design-icon-map.py", "design-icon-vectorize.py")
        // 第四组：同样叫 --root、同样相对仓库根
        val repoToolsWithRootFlag = listOf("design-preview-pairing.py", "design-screen-diff.py",
                                           "design-dp-debt-report.py", "design-form-pairing.py")
        for (script in rootTools) {
            if (runDesignTool(script, "--self-test", "--root", ".") != 0) failures++
        }
        for (script in repoTools) {
            if (runDesignTool(script, "--self-test", "--repo", ".") != 0) failures++
        }
        for (script in repoToolsWithRootFlag) {
            if (runDesignTool(script, "--self-test", "--root", ".") != 0) failures++
        }
        if (failures > 0) throw GradleException("量具自检失败 $failures 项 —— 门禁本身不可信")
    }
}

/**
 * 参考图重复检测：两张帧名不同、图却是同一张 ⇒ 重复帧在冒充两张证据。
 *
 * 为什么单开一把：帧名、量具、配对表、状态槽**全都看不出**这件事 ——
 * `DeskConversationsPaneEmptyPreview` 曾因夹具传错（`sessions` 给了非空）
 * 与 `DeskConversationsPaneNoSelectionPreview` 逐字节相同；
 * `NodesStartingPreview` 曾在 `NodePhase.Starting` 分支被删后与
 * `NodesEmptyPhonePreview` 逐字节相同。两例都是**帧名不同、量具全绿、
 * 配对表各占一行**，只有比哈希能发现。判据：全量 reference PNG 两两互异。
 */
tasks.register("designDupFrameGate") {
    group = "verification"
    description = "设计契约：参考图两两互异（重复帧 = 两张证据其实是同一张）"
    doLast {
        logger.lifecycle("设计契约门禁 · 参考图重复")
        val code = runDesignTool("render-preview.py", "--dup")
        if (code != 0) {
            throw GradleException("参考图里有重复组（退出码 $code）：帧名不同、图是同一张，" +
                "说明其中一张的夹具已不再与被测屏的分支对应。" +
                "修夹具，或删掉那张已无独立证据价值的帧（连同它的 reference PNG）。")
        }
    }
}

/**
 * 逐屏 `.dp` 欠债账（goal ④「逐屏迁移或登记偏差」的验收工件）。
 *
 * 为什么需要它：`design-token-usage` 只给一个总数（`spacing: 514`）。总数回答不了
 * 「哪一屏、哪一行、这一处该迁还是该登记」——只盯总数的迁移无法验收，数掉了几点，
 * 分不清是迁掉的还是量具变钝了。这张账把每个站点摊开，并回答「属于哪一屏 / 在不在
 * AppSpacing 档上 / 在不在写范围内 / 本轮对它做了什么」。
 */
tasks.register("designDpDebtReport") {
    group = "verification"
    description = "设计契约：生成逐屏 .dp 欠债账（goal ④ 验收工件）"
    doLast {
        logger.lifecycle("设计契约 · 逐屏 .dp 欠债账")
        val code = runDesignTool("design-dp-debt-report.py", "--root", ".",
            "--markdown", "docs/plans/2026-10-11-relay-dp-debt-ledger.md")
        if (code != 0) throw GradleException("生成逐屏 .dp 欠债账失败（退出码 $code）")
    }
}

tasks.register("designDpDebtGate") {
    group = "verification"
    description = "设计契约：逐屏 .dp 欠债账与源码一致（生成物，手改即红）"
    doLast {
        logger.lifecycle("设计契约门禁 · 逐屏 .dp 欠债账")
        val code = runDesignTool("design-dp-debt-report.py", "--root", ".",
            "--check", "--markdown", "docs/plans/2026-10-11-relay-dp-debt-ledger.md")
        if (code != 0) {
            throw GradleException("逐屏 .dp 欠债账与源码脱钩（退出码 $code）：" +
                "这是生成物，数字不许手改；改了代码/迁移了站点就重跑 :designDpDebtReport。")
        }
    }
}

tasks.register("designIconMapReport") {
    group = "verification"
    description = "设计契约：图标映射表（原型 stroke symbol ↔ 代码图标，逐条带状态）"
    doLast {
        logger.lifecycle("设计契约 · 图标映射表")
        val code = runDesignTool("design-icon-map.py", "--repo", ".", "--dry",
            "--markdown", "docs/plans/2026-10-10-relay-icon-mapping.md",
            "--json", "design/icon-mapping.json")
        if (code != 0) throw GradleException("生成图标映射表失败（退出码 $code）")
    }
}

/**
 * 图标棘轮：原型 sprite 的每个 symbol 都必须有归宿（复用/重绘/替代），**不许有"缺"**。
 * 红了代表新加了一个原型图标却没有对应资源，或某个 vector 被删掉了。
 */
tasks.register("designIconGate") {
    group = "verification"
    description = "设计契约：图标棘轮（62 个原型 symbol 全部有归宿，任何状态降级即报红）"
    doLast {
        logger.lifecycle("设计契约门禁 · 图标棘轮")
        val code = runDesignTool("design-icon-map.py", "--repo", ".", "--check",
            "--baseline", "tools/design-icon-baseline.json")
        if (code != 0) {
            throw GradleException("图标映射出现缺口（退出码 $code）：" +
                "跑 :designIconMapReport --write-baseline 前先补齐资源，不要直接改基线。")
        }
    }
}

tasks.register("designIconBaseline") {
    group = "verification"
    description = "设计契约：重记图标映射基线（有意新增/移除图标之后才跑）"
    doLast {
        val code = runDesignTool("design-icon-map.py", "--repo", ".",
            "--write-baseline", "--baseline", "tools/design-icon-baseline.json")
        if (code != 0) throw GradleException("重记图标基线失败（退出码 $code）")
    }
}

tasks.register("designConvergeReport") {
    group = "verification"
    description = "设计契约：设计侧取值收敛提案（只读，不改原型；--markdown 落评审文档）"
    doLast {
        logger.lifecycle("设计契约 · 设计侧收敛提案（只读）")
        val code = runDesignTool("design-converge.py", "--repo", ".", "--root", ".",
            "--markdown", "docs/plans/2026-10-09-relay-design-convergence-proposal.md")
        if (code != 0) throw GradleException("生成收敛提案失败（退出码 $code）")
    }
}

tasks.register("designConvergeGate") {
    group = "verification"
    description = "设计契约：设计侧取值棘轮（取值种数只许收敛；已走变量的部分不许退回字面量）"
    doLast {
        logger.lifecycle("设计契约门禁 · 设计侧取值棘轮")
        val code = runDesignTool("design-converge.py", "--repo", ".", "--root", ".", "--check")
        if (code != 0) {
            throw GradleException("设计侧取值上涨（退出码 $code）：先看输出里的 sha256 是否已变（原型在被改），" +
                "确认是自己造成的之后收敛回档位，或有意变更时跑 :designConvergeBaseline。")
        }
    }
}

tasks.register("designConvergeBaseline") {
    group = "verification"
    description = "设计契约：重记设计侧取值基线（有意收敛/扩档之后才跑）"
    doLast {
        val code = runDesignTool("design-converge.py", "--repo", ".", "--root", ".", "--write-baseline")
        if (code != 0) throw GradleException("重记设计侧取值基线失败（退出码 $code）")
    }
}

/**
 * 配对台账棘轮：台账「现有 Preview」列里的每个函数名，都要真的渲染本行的屏级 Composable。
 *
 * 为什么需要它：这一列长期是手写的，实测 111 条里 53 条在说谎（41 行），而且**看不出来**——
 * `d-search-empty`（无结果）列的是 `DeskSearchSuccessDarkPreview`（成功），
 * `d-viewscreen`（查看方）漏掉了真实存在的 `Viewing` 帧。没有棘轮时，
 * 下一个人照样能再写一条渲染别屏的帧进去，而报告只会显示"这一行有 Preview"。
 */
tasks.register("designPreviewPairingGate") {
    group = "verification"
    description = "设计契约：配对台账 Preview 核对棘轮（新写的跨域配对立刻红，历史欠债只许变少）"
    doLast {
        logger.lifecycle("设计契约门禁 · 配对台账 Preview 核对棘轮")
        val code = runDesignTool("design-preview-pairing.py", "--root", ".", "--check")
        if (code != 0) {
            throw GradleException("配对台账里新出现了渲染别屏的 Preview（退出码 $code）：" +
                "要么把台账那一格改成真正渲染本屏的帧，要么先补出这样一帧；" +
                "确属要接受（例如落点被重命名）再跑 :designPreviewPairingBaseline -PpairingBaselineReason=\"...\"。")
        }
    }
}

tasks.register("designPreviewPairingBaseline") {
    group = "verification"
    description = "设计契约：重记配对核对基线（有意接受更差判定时必须给理由）"
    doLast {
        val reason = (project.findProperty("pairingBaselineReason") as String?)?.trim().orEmpty()
        val extra = if (reason.isEmpty()) listOf<String>() else listOf("--reason", reason)
        val code = runDesignTool("design-preview-pairing.py", "--root", ".", "--baseline", *extra.toTypedArray())
        if (code != 0) {
            throw GradleException("重记配对核对基线失败（退出码 $code）：若确有更差的判定要接受，" +
                "请加 -PpairingBaselineReason=\"...\" 说明是哪一行、为什么。")
        }
    }
}

tasks.register("designScreenDiffGate") {
    group = "verification"
    description = "设计契约：逐屏取值差集棘轮（原型声明过的字号/间距/圆角/字重，代码这边只能越来越少没对上）"
    doLast {
        logger.lifecycle("设计契约门禁 · 逐屏取值差集棘轮")
        val code = runDesignTool("design-screen-diff.py", "--root", ".", "--all", "--check")
        if (code != 0) {
            throw GradleException("逐屏差集比基线更大（退出码 $code）：原型有的取值在代码这边又多缺了一档。" +
                "先确认是代码真缺，还是落点格指错了文件——落点指错时差集会整屏换一批值；" +
                "确属要接受再跑 :designScreenDiffBaseline -PscreenDiffBaselineReason=\"...\"。")
        }
    }
}

tasks.register("designScreenDiffBaseline") {
    group = "verification"
    description = "设计契约：重记逐屏差集基线（有意接受更大的差集时必须给理由）"
    doLast {
        val reason = (project.findProperty("screenDiffBaselineReason") as String?)?.trim().orEmpty()
        val extra = if (reason.isEmpty()) listOf<String>() else listOf("--reason", reason)
        val code = runDesignTool("design-screen-diff.py", "--root", ".", "--all", "--baseline", *extra.toTypedArray())
        if (code != 0) {
            throw GradleException("重记逐屏差集基线失败（退出码 $code）：若确有更大的差集要接受，" +
                "请加 -PscreenDiffBaselineReason=\"...\" 说明是哪一屏、为什么。")
        }
    }
}

tasks.register("designFormPairingGate") {
    group = "verification"
    description = "设计契约：台账配对**形态**棘轮（桌面屏只配手机帧 / 手机屏只配桌面帧立刻红）"
    doLast {
        logger.lifecycle("设计契约门禁 · 台账配对形态")
        val code = runDesignTool("design-form-pairing.py", "--root", ".", "--check")
        if (code != 0) {
            throw GradleException("台账配对的形态比基线更差（退出码 $code）：某个 `d-` 开头的屏只配了手机帧，" +
                "或某个 `m-` 开头的屏只配了桌面帧，或台账里的帧名在截图源集里找不到。" +
                "这三件事量具/截图/单测都不会报——配对层拿一张**别的形态**的帧去顶，" +
                "并排板不开图看不出来，而核对列会照旧写 ✅（本轮实测 5 行：d-reqscreen / d-sharescreen / " +
                "d-viewscreen / d-settings-data / d-emoji）。补真帧，不要改判据；" +
                "确属要接受再跑 :designFormPairingBaseline -PformPairingBaselineReason=\"...\"。")
        }
    }
}

tasks.register("designFormPairingBaseline") {
    group = "verification"
    description = "设计契约：重记台账形态配对基线（有意接受形态不符时必须给理由）"
    doLast {
        val reason = (project.findProperty("formPairingBaselineReason") as String?)?.trim().orEmpty()
        val extra = if (reason.isEmpty()) listOf<String>() else listOf("--reason", reason)
        val code = runDesignTool("design-form-pairing.py", "--root", ".", "--baseline", *extra.toTypedArray())
        if (code != 0) {
            throw GradleException("重记形态配对基线失败（退出码 $code）：若确有形态不符要暂时接受，" +
                "请加 -PformPairingBaselineReason=\"...\" 说明是哪个屏、哪张帧、为什么。")
        }
    }
}

tasks.register("designLocaleParityGate") {
    group = "verification"
    description = "设计契约：多语言资源对账棘轮（默认语言有的键，各语言目录里只能越来越少没对上）"
    doLast {
        logger.lifecycle("设计契约门禁 · 多语言资源对账")
        val code = runDesignTool("design-locale-parity.py", "--root", ".", "--check")
        if (code != 0) {
            throw GradleException("多语言资源比基线更差（退出码 $code）：默认语言里有的键，某个语言目录里没有。" +
                "Compose Resources 找不到键**不报错、不崩**，而是静默回落成默认语言——英文用户看到中文，" +
                "而所有截图帧都写死 locale=\"zh-rCN\"，这条路根本照不到，门禁/截图/单测会全绿。" +
                "补翻译，不要删键；确属要接受再跑 :designLocaleParityBaseline -PlocaleParityBaselineReason=\"...\"。")
        }
    }
}

tasks.register("designLocaleParityBaseline") {
    group = "verification"
    description = "设计契约：重记多语言基线（有意接受缺失/多余/含中文/空串时必须给理由）"
    doLast {
        val reason = (project.findProperty("localeParityBaselineReason") as String?)?.trim().orEmpty()
        val extra = if (reason.isEmpty()) listOf<String>() else listOf("--reason", reason)
        val code = runDesignTool("design-locale-parity.py", "--root", ".", "--baseline", *extra.toTypedArray())
        if (code != 0) {
            throw GradleException("重记多语言基线失败（退出码 $code）：若确有未翻译的键要暂时接受，" +
                "请加 -PlocaleParityBaselineReason=\"...\" 说明是哪个语言、哪个键、为什么。")
        }
    }
}
