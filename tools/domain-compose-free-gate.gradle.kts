// SPDX-License-Identifier: GPL-3.0-only
//
// 领域层零 Compose 依赖门禁（domain compose-free gate）。
//
// 由根 build.gradle.kts 通过 `apply(from = "tools/domain-compose-free-gate.gradle.kts")` 引入：
//
//   :domainComposeFreeGate   扫领域层源码，有 Compose import 即失败（默认入口）
//
// ## 为什么它是根任务，而不是 :composeApp 里的一条单元测试
//
// 它原本是 `DomainHasNoComposeTest`。那样有个致命缺陷：**跑它要先编译 `:app`**。
// 于是有人往领域层插一行 `import androidx.compose.*` 时，`:app:compileKotlinJvm` 先失败，
// 守卫根本没机会执行 —— 「守卫红了」是错觉，红的是编译器。
// 而一旦某个模块临时引来 Compose 依赖让编译通过，守卫仍旧是绿的（它从未运行）。
//
// 门禁只读源码，不需要任何编译产物。这条纪律与 `cjkLiteralGate` /
// `constructionTraceGate` / `staleCommentRefGate` 一致：几秒出结果，可单独调用。
//
// ## 它抓什么
//
// 三条：① 领域层任何 `import androidx.compose.*` / `org.jetbrains.compose.*`；
// ② **扫描根不存在**（读不到目录 ⇒ 空集合 ⇒ 「没有 offender」恒真，是假绿）；
// ③ 扫到的文件数低于下限（同一条假绿的第二种写法）。
// 外加「允许清单不许陈旧」—— 清单里的文件若已不再依赖 Compose，就要求缩小清单。

import java.io.File

val domainComposeFreeTool = File(project.projectDir, "tools/domain-compose-free.py")

tasks.register("domainComposeFreeGate") {
    group = "verification"
    description = "领域层（:app）不许 import Compose —— 换 UI 方案时这一层原样复用。"

    // 输入是**源码**，不是编译产物：改任何一个领域文件都应当重跑。
    inputs.files(
        project.fileTree("app/src") {
            include("**/*.kt")
            exclude("**/build/**")
        },
        project.fileTree("composeApp/src/commonMain/kotlin/com/kapp/tidings") {
            include("platform/*.kt", "navigation/*.kt")
        },
        domainComposeFreeTool,
    )
    // 门禁只读源码，没有输出可声明 —— 关掉 up-to-date 检查以免它被跳过。
    outputs.upToDateWhen { false }

    doLast {
        val result = try {
            val proc = ProcessBuilder(
                "python3", domainComposeFreeTool.absolutePath,
                "--root", project.projectDir.absolutePath,
            )
                .directory(project.projectDir)
                .redirectErrorStream(true)
                .start()
            val text = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            text.trim() to code
        } catch (e: Exception) {
            throw GradleException("领域层 Compose 门禁执行失败：\${e.message}")
        }
        val (text, code) = result
        if (text.isNotEmpty()) println(text)
        if (code != 0) {
            throw GradleException(
                "领域层 Compose 门禁失败。\n" +
                    "这条门禁守的是「换掉 UI 方案时领域层原样复用」：:app 不应用 Compose 插件、" +
                    "不声明 Compose 依赖，所以 import 了它按模块拆开就编译不过。\n" +
                    "改法：做成可注入的桥（见 RelayUiTexts 的形状），或列进 tools/domain-compose-free.py" +
                    " 的 INDIVIDUAL_ALLOWED 并写清理由。",
            )
        }
    }
}
