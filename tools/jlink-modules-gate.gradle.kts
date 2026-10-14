// SPDX-License-Identifier: GPL-3.0-only
//
// 打包镜像必须含代码用到的 JDK 模块（jlink module gate）。
//
// 由根 build.gradle.kts 通过 `apply(from = "tools/jlink-modules-gate.gradle.kts")` 引入：
//
//   :jlinkModulesGate          先 createDistributable，再核对镜像里的 JDK 模块
//   :jlinkModulesGateSelfTest  量具自检（12 例，含"把缺陷放回去必须判红"）
//
// 为什么需要它：jpackage 的运行时镜像只含一组默认模块，代码里用到而没在
// `nativeDistributions.modules(...)` 声明的 JDK 包在镜像里根本不存在。这类缺失
// **只在打包产物里出现、而且失败是静默的** —— ./gradlew :desktopApp:run 走宿主
// JDK 的全量模块，本地怎么跑都是绿的；镜像里缺模块时抛 NoClassDefFoundError，而
// 桌面端刻意在 DesktopExtension.syncOnce() 里 catch 了 Throwable（控制面是加装
// 能力，绑不上不该拖垮 UI）⇒ 异常被吞掉：界面正常、17878/17879 照常绑定，只有
// 8788 永远不监听。已实测过一次：装机版里缺 jdk.httpserver，手机永远看不到这台
// 机器，而所有日志都是干净的。
//
// 判据（实现见 tools/jlink-modules.py）：
//   required = jdeps 扫本仓库自己打进镜像的 jar（agentNode/composeApp/core/desktopApp）
//   present  = jimage list 读镜像的 lib/modules
//   要求 required ⊆ present
// 只扫自己的 jar：第三方 jar 里有大量走不到的可选 JDK 引用（实测 ktor-utils →
// java.management、kotlin-stdlib → jdk.unsupported、jbr-api → java.instrument，三者
// 镜像里都没有而应用工作正常），纳入判据就得挂一张永久豁免名单。
//
// 这是一条**单向不变式，不是棘轮**：镜像里有而代码没用的模块（java.sql 来自
// sqlite 驱动）不算问题，所以没有 Baseline 任务，也没有基线文件。

import org.gradle.api.GradleException
import java.io.File

val jlinkModulesTool = File(project.projectDir, "tools/jlink-modules.py")

// 镜像根目录由 compose desktop 插件固定产出；**产品名不参与判据** ——
// 改名是正常操作，门禁不该跟着改，tools/jlink-modules.py 会自己找出镜像，
// 多于一个就报出来让人选，不许猜。
val jlinkImageRoot = File(project.projectDir, "desktopApp/build/compose/binaries/main/app")

// jimage/jdeps 用跑这条门禁的那个 JDK：Gradle 守护进程自己的 java.home 必然有
// 这两个工具（打包用的也是它），比依赖外部的 $JAVA_HOME 稳。
fun jlinkJdkBin(): String = File(System.getProperty("java.home"), "bin").absolutePath

tasks.register("jlinkModulesGate") {
    group = "verification"
    description = "打包镜像必须含代码用到的 JDK 模块（缺了只在产物里静默失效）"
    // 依赖真实产物：否则改了 modules(...) 不重新打包，门禁查的是上一份镜像，
    // 会把"配置改了但产物是旧的"报成通过。
    dependsOn(":desktopApp:createDistributable")
    doLast {
        val proc = ProcessBuilder(
            "python3", jlinkModulesTool.absolutePath,
            "--image-root", jlinkImageRoot.absolutePath,
            "--jdk-bin", jlinkJdkBin(),
            "--check",
        ).directory(project.projectDir).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        if (out.isNotEmpty()) logger.lifecycle(out)
        if (proc.waitFor() != 0) {
            throw GradleException(
                "打包镜像缺 JDK 模块：代码用到了而 nativeDistributions.modules(...) 里没声明。\n" +
                    "改法：在 desktopApp/build.gradle.kts 的 nativeDistributions 里补上 modules(...)。\n" +
                    "为什么危险：jpackage 的默认模块集不含它，缺了只在打包产物里抛 " +
                    "NoClassDefFoundError，而桌面端刻意吞掉该异常 ⇒ 界面正常、8788 不监听。"
            )
        }
    }
}

tasks.register("jlinkModulesGateSelfTest") {
    group = "verification"
    description = "jlink 模块门禁的量具自检（12 例，含缺陷回归）"
    doLast {
        val proc = ProcessBuilder("python3", jlinkModulesTool.absolutePath, "--self-test")
            .directory(project.projectDir).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        if (out.isNotEmpty()) logger.lifecycle(out)
        if (proc.waitFor() != 0) throw GradleException("jlink 模块门禁的量具自检失败")
    }
}
