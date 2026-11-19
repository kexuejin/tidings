// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import com.kapp.tenon.agent.CarrierHold
import kotlin.system.exitProcess

/**
 * `driver doctor` 的命令入口（`./gradlew :agentNode:driverDoctor`）。
 *
 * ## 为什么住在驱动库这一层
 *
 * 它要问的东西全在这一层：发现（`CliDiscovery`）、登录态判定（`DriverAuthJudgement`）、
 * 模型目录（`DriverModelCatalog`）、凭证白名单（`CliProviderEnv`）、
 * 沙箱 HOME 清单（`WorktreeSandboxExecutor`），以及**生产装配出的 argv**
 * （`cliDriverRegistrations` 产出的实例）。放在这里，别的产品把驱动库拿走时，
 * 自检能力也跟着走 —— 这正是「库」与「一个正好没人引用的模块」的区别。
 *
 * 编排层不是没被考虑过：那里有「只许一处 `runBlocking`」「只许一处 `println`」两条守卫，
 * 都是为线上日志与阻塞边界立的规矩。为了一个诊断命令去开第三个例外，比把入口放对层更糟。
 *
 * ## 为什么可以有一个 `main`
 *
 * `:agentNode` 曾经有过常驻 CLI 进程并被弃用 —— 理由是「那个进程没有看护者，开机无人拉起」。
 * 本入口不是常驻进程：跑一趟、打印、退出，没有生命周期，也就不存在没人看护的问题。
 *
 * ## 用法
 *
 * ```
 * ./gradlew :agentNode:driverDoctor                  # 沙箱分支（生产默认）+ 人读版
 * ./gradlew :agentNode:driverDoctor --args="--json"  # JSON（契约见 DriverDoctor.SCHEMA_VERSION）
 * ./gradlew :agentNode:driverDoctor --args="--plain" # 裸路径分支（对照用）
 * ./gradlew :agentNode:driverDoctor --args="--strict"# 有 ERROR 时退出码 1（可进 CI）
 * ```
 */
fun main(args: Array<String>) {
    val flags = args.filter { it.startsWith("--") }.toSet()
    val unknown = flags - SUPPORTED_FLAGS
    if (unknown.isNotEmpty()) {
        System.err.println("unknown flag(s): " + unknown.joinToString(", "))
        System.err.println("usage: driverDoctor [--json] [--plain] [--strict]")
        exitProcess(2)
    }

    val env = System.getenv()
    // 生产默认是沙箱分支（桌面宿主总传 sandbox），所以缺省看沙箱；
    // --plain 用于对照裸路径 —— 两条分支的 argv 本来就不同。
    val sandboxed = "--plain" !in flags

    // `main` 是同步的，而探测全是挂起的：按住主线程等它跑完。走 CarrierHold 而不是裸
    // `runBlocking` —— 后者正是本仓明令只许存在一处的写法（按住多久没人知道）。
    val report = CarrierHold.hold("driverDoctor", HOLD_BUDGET_MS) {
        val drivers = cliDriverRegistrations(
            sandbox = if (sandboxed) WorktreeSandboxExecutor(WorktreeSandboxExecutor.defaultRoot()) else null,
            env = env,
            // 装配期的提示行走 stderr：stdout 要留给报告本身，否则 `--json` 不是可解析的 JSON
            // （提示行会先印出来，任何消费方都得先做文本剥离）。
            hint = { System.err.println(it) },
        )
        val probe = object : DriverDoctor.Probe by DriverDoctor.MachineProbe(env) {
            // 登录态：与界面同一份判据（DriverAuthJudgement），不在这里另写一套。
            override suspend fun authOf(key: String, cliPath: String?): DriverDoctor.AuthView? {
                val snapshot = DriverAuthJudgement.snapshotWithDefaults(key, env)
                return DriverDoctor.AuthView(snapshot.state, snapshot.evidence)
            }
        }
        DriverDoctor.diagnose(drivers = drivers, probe = probe, sandboxed = sandboxed)
    }

    println(if ("--json" in flags) DriverDoctor.renderJson(report) else DriverDoctor.renderText(report))
    if ("--strict" in flags && report.errorCount > 0) exitProcess(1)
}

private val SUPPORTED_FLAGS: Set<String> = setOf("--json", "--plain", "--strict")

/**
 * 整轮自检的持线程预算。
 *
 * 逐家探测是串行的（模型目录每家最多 20s、登录态 5s、版本 5s），七家全慢是分钟级；
 * 取 3 分钟作为「按契约最多按这么久」的上界。超了只是打一行 `[RELAY-HOLD]` ——
 * 那说明某一家的只读命令卡住了，正是需要看见的信息。
 */
private const val HOLD_BUDGET_MS: Long = 3L * 60_000
