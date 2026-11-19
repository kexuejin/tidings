// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 内置 CLI 驱动的**覆盖守卫**。
 *
 * [CliDriverSpecs] 是「每家驱动是什么」的单一来源。凡是列得出表的消费面，都必须与它对齐：
 * 少一家 = 装了却派不到 / 配了模型不生效；多一家 = 表里没有的野驱动没人管。两类都是
 * 编译通过、测试全绿、只在真机暴露的缺陷，所以在这里一次性钉死。
 *
 * 派生面（[AgentProfiles] / [DriverModelFlags]）直接读身份卡，本守卫钉「没有改回手写」；
 * 其余四处（凭证白名单、装配、发现、UI 显示名）是行为/敏感值，不搬进身份卡，用源码扫描
 * 比对其键集。判据用合成输入 + 固定源码路径，不读本机环境，过 envDependentTestGate。
 */
class CliDriverCoverageGuardTest {

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    /**
     * 身份卡真源守卫：`driver-specs.json` 是唯一真源，`builtins` 只能引用它的投影产物。
     *
     * 两类漂移都要红：
     * 1. JSON 与公开的 [CliDriverSpecs.keys] 不一致 —— 说明有人改了 JSON 没投影，或反过来；
     * 2. `builtins` 又长出一份手写清单 —— 重新制造两份真相，守卫就会对着假的那份全绿。
     *
     * 读的是仓库里的 JSON + Kotlin 源码（合成输入、固定路径），不读本机环境，过
     * envDependentTestGate。逐字节的 JSON↔生成物一致由 `:driverSpecDrift` 在构建期守。
     */
    @Test
    fun driverSpecJsonIsTheSingleSourceOfTruth() {
        val root = GuardSourceScan.scaffoldRoot()
        val jsonFile = java.io.File(root, "agent-contract/driver-specs.json")
        assertTrue(jsonFile.isFile, "身份卡真源缺失：agent-contract/driver-specs.json")
        val jsonText = jsonFile.readText()
        val jsonKeys = Regex("\"key\"\\s*:\\s*\"([a-z0-9]+)\"")
            .findAll(jsonText).map { it.groupValues[1] }.toSet()
        assertEquals(
            CliDriverSpecs.keys, jsonKeys,
            "driver-specs.json 的 key 集必须与 CliDriverSpecs.keys 严格相等（改真源要重投影）",
        )

        val specs = live("agent-contract/src/main/kotlin/com/kapp/tenon/agent/drivers/CliDriverSpecs.kt")
        assertTrue(
            Regex("""val builtins\s*:\s*List<Spec>\s*=\s*driverSpecRows""").find(specs) != null,
            "builtins 必须引用 JSON 投影产物 driverSpecRows（不许手写清单）",
        )
        // builtins 之后到 byKey 之间若有手写清单，这里不该出现 Spec(" 这种位置参数构造。
        val builtinsSlice = specs.substring(
            specs.indexOf("val builtins: List<Spec>"),
            specs.indexOf("val byKey:"),
        )
        assertTrue(
            Regex("Spec\\(\"([a-z0-9]+)").find(builtinsSlice) == null,
            "builtins 不许再手写清单（两份真相必然漂）—— 改 JSON 后跑 :driverSpecProject",
        )
    }

    /**
     * 取函数体：从签名起按**括号配对**收到与之配对的收尾大括号。
     *
     * 早先按「行首无缩进的 `}`」截断，只够用在类里独占一行的顶层函数上；换成
     * `= when (…) { … }` 这种表达式体后，`when` 的收尾也缩进、类收尾才是列 0，
     * 于是一路吃到文件末尾，把邻居函数也算了进来（守卫会因别人的表而变绿）。
     */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature).takeIf { it >= 0 }
            ?: throw AssertionError("找不到函数签名：$signature")
        var depth = 0
        for (i in start until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
        }
        throw AssertionError("函数体没有收尾大括号：$signature")
    }

    @Test
    fun derivedConsumersStillReadTheSpec() {
        val profiles = live("agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/AgentProfiles.kt")
        assertTrue("CliDriverSpecs.builtins" in profiles, "AgentProfiles.known 必须从身份卡派生")
        assertTrue("CliDriverSpecs.workerKeys" in profiles, "workerPreference 必须从身份卡派生，别再手写列表")
        assertTrue("CliDriverSpecs.reviewerKeys" in profiles, "reviewerPreference 必须从身份卡派生，别再手写列表")

        val flags = live("agent-contract/src/main/kotlin/com/kapp/tenon/agent/drivers/DriverModelFlags.kt")
        assertTrue("CliDriverSpecs.builtins" in flags, "DriverModelFlags 必须从身份卡派生，别再手写模型旗标表")
    }

    @Test
    fun providerEnvCoversEverySpecKey() {
        val code = live("agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/CliProviderEnv.kt")
        val keys = Regex("""cliDriverId\("([a-z0-9]+)"\)""").findAll(code)
            .map { it.groupValues[1] }.toSet()
        assertEquals(CliDriverSpecs.keys, keys, "CliProviderEnv.forDriver 必须与身份卡键集严格相等")
    }

    @Test
    fun registrationCoversEverySpecKey() {
        val code = live("agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/CliSubprocessDriver.kt")
        val keys = Regex("""cliDriverId\("([a-z0-9]+)"\)""").findAll(code)
            .map { it.groupValues[1] }.toSet()
        assertEquals(CliDriverSpecs.keys, keys, "cliDriverRegistrations 必须与身份卡键集严格相等")
    }

    @Test
    fun discoveryCoversEverySpecKey() {
        val code = live("agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/CliDiscovery.kt")
        val keys = Regex("fun resolve([A-Z][A-Za-z]*)Cli\\(").findAll(code)
            .map { it.groupValues[1].lowercase() }.toSet()
        assertEquals(CliDriverSpecs.keys, keys, "CliDiscovery 必须为每家内置 CLI 提供 resolve 入口")

        // 路由键必须是 driver key 字面量。写成 `CLAUDE_BIN ->` 时，判据其实是 JSON 的
        // `discovery.bin`：某家把 bin 改名（`agy` → `antigravity`）后这里静默匹配不到，
        // 症状是诊断面「没装」而装配面正常 —— bin 名回答「找什么文件」，key 回答「这是哪家」。
        val routeBody = bodyOf(code, "fun resolveFor(")
        val routeKeys = Regex("\"([a-z0-9]+)\" ->").findAll(routeBody)
            .map { it.groupValues[1] }.toSet()
        assertEquals(
            CliDriverSpecs.keys, routeKeys,
            "resolveFor 的 when 分支必须用 driver key 字面量，且与身份卡键集相等（不许拿 bin 名当 key）",
        )
        assertFalseRegExp(
            routeBody,
            "(CLAUDE|CODEX|AGY|OPENCODE|DSH|CODEBUDDY|ZCODE)_BIN ->",
            "resolveFor 不许用投影的 bin 名当 driver key 判据（改 JSON 会静默断路）",
        )
    }

    /**
     * 沙箱网络策略必须从真源来：`driver-specs.json` 的 `networkPolicy` 是唯一判据。
     *
     * 从前这里是「默认值必须是字面量 OPEN」——守的是实现参数，而 JSON 里的声明零消费：把某家
     * 改成 AIR_GAPPED 只会写进生成物、不影响任何一条命令（症状是「策略改了没反应」）。
     * 现在守两件事：
     * 1. 默认值读 [OsSandboxGuard.declaredPolicyOf]，装配点不许再写字面量档位；
     * 2. 且每家内置驱动当前都声明 OPEN（云 API 必备）——真有断网驱动时连这条断言和理由一起改。
     */
    @Test
    fun sandboxNetworkPolicyComesFromTheSpecProjection() {
        val code = live("agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/SandboxedCliDriver.kt")
        assertTrue(
            "OsSandboxGuard.declaredPolicyOf(driverId)" in code,
            "SandboxedCliDriver.networkPolicy 默认值必须读 JSON 声明（OsSandboxGuard.declaredPolicyOf）",
        )

        val registrations = live("agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/CliSubprocessDriver.kt")
        assertFalseRegExp(
            registrations,
            "networkPolicy\\s*=\\s*OsSandboxGuard\\.NetworkPolicy\\.",
            "装配点不许再写字面量网络策略（真源在 JSON 的 networkPolicy）",
        )

        // 三档映射的守卫是**编译期**的：declaredPolicyOf 的 when 没有 else，新增档位就编不过。
        for (key in CliDriverSpecs.keys) {
            assertEquals(
                CliDriverSpecs.NetworkPolicy.OPEN,
                CliDriverSpecs.networkPolicyOf(DriverKeys.cliDriverId(key)),
                "$key 当前声明为 OPEN（模型 API 必须出网）；改档位请连同这条断言与理由一起改",
            )
        }
    }

    /** 断言源码里不出现给定正则（守卫用，避免在别处重复 import assertFalse）。 */
    private fun assertFalseRegExp(code: String, regex: String, message: String) {
        assertTrue(Regex(regex).find(code) == null, message)
    }

    /**
     * 批1 行为声明面（envKeys / homeLinks / networkPolicy / sandboxMode）的真源守卫。
     *
     * 两类漂移都要红：
     * 1. 生产代码又长出第二份硬编码（白名单 listOf、挂载清单、sandbox 三值 when）——
     *    那会让 JSON 改了却不生效，守卫对着投影全绿、真机照旧错；
     * 2. JSON 里的声明与生产可读的投影脱钩（键集对不上身份卡）。
     */
    @Test
    fun behaviorDeclarationsAreReadFromTheSpecProjection() {
        val root = GuardSourceScan.scaffoldRoot()
        val json = java.io.File(root, "agent-contract/driver-specs.json").readText()

        // JSON 的 networkPolicy 必须每家**显式**声明（缺席 = 校验器会红，这里再钉一层）。
        // 具体档位是否合理由 sandboxNetworkPolicyComesFromTheSpecProjection 判。
        val policyCount = Regex("\"networkPolicy\"\\s*:\\s*\"[A-Z_]+\"").findAll(json).count()
        assertEquals(CliDriverSpecs.keys.size, policyCount, "每家都必须显式声明 networkPolicy")

        // sandboxHomeLinks 的 owner 必须归属身份卡或 shared（codegen 已校验，守卫钉生产读投影）。
        val sandbox = live("agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/WorktreeSandbox.kt")
        assertTrue(
            "CliDriverSpecs.sandboxHomeLinks" in sandbox,
            "SANDBOX_CREDENTIAL_ENTRIES 必须读 JSON 投影，不许再手写挂载清单",
        )
        assertFalseRegExp(
            sandbox,
            "\\\".gemini\\\"|\\\".codebuddy\\\"|\\\".zcode\\\"",
            "WorktreeSandbox 不许再出现手写挂载目录字面量（真源在 JSON）",
        )

        // CliProviderEnv：7 张白名单必须来自投影，不许回到硬编码 listOf("ANTHROPIC_...")。
        val env = live("agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/CliProviderEnv.kt")
        assertEquals(
            CliDriverSpecs.keys.size,
            Regex("""CliDriverSpecs\.envKeysOf\(DriverKeys\.cliDriverId\("([a-z0-9]+)"\)\)""")
                .findAll(env).map { it.groupValues[1] }.toSet().size,
            "CliProviderEnv 每家都必须从投影取白名单",
        )
        assertFalseRegExp(
            env,
            "\\\"ANTHROPIC_BASE_URL\\\"|\\\"DEEPSEEK_API_KEY\\\"|\\\"CODEBUDDY_API_KEY\\\"",
            "CliProviderEnv 不许再手写凭证白名单键（真源在 JSON）",
        )

        // codexSandboxMode 必须读投影的合法值/默认，不许再写死三值 when。
        val driver = live("agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/CliSubprocessDriver.kt")
        assertTrue(
            "CliDriverSpecs.sandboxModeOf" in driver,
            "codexSandboxMode 必须读 JSON 投影的 sandboxMode 声明",
        )
        assertFalseRegExp(
            driver,
            "\\\"danger-full-access\\\" -> raw|else -> \\\"workspace-write\\\"",
            "codexSandboxMode 不许再硬编码合法值/默认（真源在 JSON）",
        )

        // 投影访问器本身可用：每家键都能查到非空 or 空表（agy 合法空）。
        for (key in CliDriverSpecs.keys) {
            val driverId = DriverKeys.cliDriverId(key)
            // envKeysOf 对裸键与 driverId 都应返回同一张表（stripCliSuffix 幂等）。
            assertEquals(
                CliDriverSpecs.envKeysOf(key),
                CliDriverSpecs.envKeysOf(driverId),
                "$key 的 envKeysOf 对裸键与 driverId 必须一致",
            )
        }
    }

    /**
     * 发现参数与启动形态（批2）的真源守卫：CliDiscovery 的 env 名/bin 名/应用包路径/
     * node 后缀必须来自投影，不许回到硬编码清单。
     *
     * aliases 刻意不在本表也不在此守卫：它是身份地基（DriverKeys），已是单一真源，
     * 且 DriverKeySingleSourceGuardTest 钉死不许第二份规则（含 -cli 通用算法服务 native）。
     */
    @Test
    fun discoveryAndLaunchReadFromTheSpecProjection() {
        val disc = live("agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/CliDiscovery.kt")
        assertTrue(
            "CliDriverSpecs.discoveryOf" in disc && "CliDriverSpecs.launch" in disc,
            "CliDiscovery 必须从投影读发现参数与 launch 规则",
        )
        // 不许再出现硬编码的 env 名 / bin 名 / 应用包相对路径字面量。
        assertFalseRegExp(
            disc,
            "\\\"TENON_(CLAUDE|CODEX|AGY|OPENCODE|DSH|CODEBUDDY|ZCODE)_CLI\\\"",
            "CliDiscovery 不许手写 ENV_*_CLI 值（真源在 JSON）",
        )
        assertFalseRegExp(
            disc,
            "WorkBuddy\\.app/Contents|ZCode\\.app/Contents",
            "CliDiscovery 不许手写应用包相对路径（真源在 JSON）",
        )
        // 只查 launchOf 函数体：zcodeBundledProviderConfigOf 里的 .cjs 是配置路径推导，
        // 那是机制不是 launch 判定，合法。
        val launchBody = bodyOf(disc, "fun launchOf(cliPath: String)")
        assertTrue(
            "CliDriverSpecs.launch.nodeExtensions" in launchBody,
            "launchOf 必须按 JSON 投影的 nodeExtensions 判定，不许硬编码后缀",
        )
        assertFalseRegExp(
            launchBody,
            "\\\"\\.cjs\\\"|\\\"\\.js\\\"",
            "launchOf 不许硬编码 node 后缀（真源在 JSON 的 launch）",
        )

        // 投影访问器逐家可用，值与身份卡键集对齐。
        for (key in CliDriverSpecs.keys) {
            val d = CliDriverSpecs.discoveryOf(DriverKeys.cliDriverId(key))
                ?: throw AssertionError("$key 缺 discovery 投影")
            assertTrue(d.envVar.startsWith("TENON_"), "$key 的 discovery.envVar 形态不对：${d.envVar}")
            assertTrue(d.bin.isNotBlank(), "$key 的 discovery.bin 不许空")
        }
        assertTrue(
            CliDriverSpecs.launch.nodeExtensions.containsAll(listOf(".cjs", ".js")),
            "launch 投影必须含 .cjs/.js 两种 node 脚本形态",
        )
    }

    /**
     * argv 模板（批3）的真源守卫：生产装配必须从 JSON 投影取 token 骨架，
     * 不许再在 helper 里手写 `listOf("-p", prompt, ...)`——那是「模型旗标插哪」靠人记忆的旧世界。
     */
    @Test
    fun argvTemplatesAreRenderedFromTheSpecProjection() {
        val driver = live("agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/CliSubprocessDriver.kt")
        assertTrue(
            "CliArgv.render" in driver && "CliDriverSpecs.argvOf" in driver,
            "argv 必须经 CliArgv.render + CliDriverSpecs.argvOf 渲染，模板骨架唯一真源在 JSON",
        )
        // 不许再出现各家 argv 的静态前缀骨架（旧世界的手写 listOf）。
        for (hardcoded in listOf(
            "\\\"--output-format\\\", \\\"stream-json\\\"",
            "\\\"run\\\", \\\"--standalone\\\", \\\"--auto\\\"",
            "\\\"--profile\\\", \\\"headless\\\", \\\"--json\\\"",
            "\\\"--mode\\\", \\\"yolo\\\", \\\"--json\\\"",
        )) {
            assertFalseRegExp(driver, hardcoded, "CliSubprocessDriver 不许再手写 argv 骨架：$hardcoded")
        }

        // 投影访问器逐家可用；plain 模板恰好一个 ${prompt}，且有 sandbox 模板的驱动其 plain 无提权旗标。
        val privilege = setOf("--permission-mode", "bypassPermissions", "--dangerously-skip-permissions", "-y")
        for (key in CliDriverSpecs.keys) {
            val a = CliDriverSpecs.argvOf(DriverKeys.cliDriverId(key))
                ?: throw AssertionError("$key 缺 argv 投影")
            assertEquals(1, a.plain.count { it == "\${prompt}" }, "$key 的 plain 模板 prompt 次数")
            if (a.sandbox != null) {
                assertTrue(
                    a.plain.none { it in privilege },
                    "$key 的 plain（裸路径）模板不许含提权旗标：${a.plain}",
                )
            }
            // 有续跑模板就必须带 ${session}（codegen 也校验，这里对投影再钉一层）。
            for (resume in listOf(a.resumePlain, a.resumeSandbox)) {
                if (resume != null) {
                    assertTrue("\${session}" in resume, "$key 的续跑模板必须含 \${session}")
                }
            }
        }
    }

    /**
     * 模型探针声明（批4a）的真源守卫：每家必须从投影构造 spec，不许回到手写的
     * `mapOf("agy" to ModelProbeSpec(...))`——那是「改了 JSON 却不生效」的旧世界。
     *
     * 同时钉住 TTL / 超时来自 [CliDriverSpecs.probeLimits]，而不是散落的 const。
     */
    @Test
    fun modelProbesAreBuiltFromTheSpecProjection() {
        val catalog = live("agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/DriverModelCatalog.kt")
        assertTrue(
            "CliDriverSpecs.modelProbeOf" in catalog && "CliDriverSpecs.probeLimits" in catalog,
            "VERIFIED_SPECS / VERIFIED_CONFIG_SPECS / 时限必须从 JSON 投影构造，不许手写第二份",
        )
        // 反向钉子：不许再出现硬编码的 spec 构造（args = listOf("models") 那套）。
        assertFalseRegExp(
            catalog,
            "ModelProbeSpec\\(args\\s*=\\s*listOf|ConfigProbeSpec\\(args\\s*=\\s*listOf",
            "DriverModelCatalog 不许再手写探针 spec（真源在 JSON 的 modelProbe）",
        )
        assertFalseRegExp(
            catalog,
            "val DEFAULT_TTL_MS\\s*:\\s*Long\\s*=|val PROBE_TIMEOUT_MS\\s*:\\s*Long\\s*=",
            "探测时限不许再写死 const（真源在 JSON 的 probeLimits）",
        )

        // 投影逐家可用：LIST/CONFIG 必须有 parser，NONE 必须没有 argv/parser；catalogSource 指向内置驱动。
        val listParsers = mutableSetOf<String>()
        val configParsers = mutableSetOf<String>()
        for (key in CliDriverSpecs.keys) {
            val mp = CliDriverSpecs.modelProbeOf(DriverKeys.cliDriverId(key))
                ?: throw AssertionError("$key 缺 modelProbe 投影")
            when (mp.kind) {
                CliDriverSpecs.ModelProbeKind.LIST -> {
                    assertTrue(mp.argv.isNotEmpty(), "$key 的 LIST modelProbe.argv 不许空")
                    val parser = mp.parser ?: throw AssertionError("$key 的 LIST modelProbe 缺 parser")
                    listParsers += parser
                }
                CliDriverSpecs.ModelProbeKind.CONFIG -> {
                    assertTrue(mp.argv.isNotEmpty(), "$key 的 CONFIG modelProbe.argv 不许空")
                    val parser = mp.parser ?: throw AssertionError("$key 的 CONFIG modelProbe 缺 parser")
                    configParsers += parser
                    val src = mp.catalogSource
                        ?: throw AssertionError("$key 的 CONFIG modelProbe 缺 catalogSource")
                    assertTrue(
                        src.driver in CliDriverSpecs.keys,
                        "$key 的 catalogSource.driver=${src.driver} 不是内置驱动",
                    )
                    assertTrue(src.prefix.isNotBlank(), "$key 的 catalogSource.prefix 不许空")
                }
                CliDriverSpecs.ModelProbeKind.NONE -> {
                    assertTrue(mp.argv.isEmpty() && mp.parser == null, "$key 的 NONE modelProbe 不许带 argv/parser")
                }
            }
        }
        assertTrue(listParsers.isNotEmpty() && configParsers.isNotEmpty(), "LIST/CONFIG 两类都必须在表里有实例")
    }

    /**
     * parser 机制 id 两头对账：JSON 实际引用的 parser 集合必须==代码注册表键集。
     * 多一边都是静默失效——JSON 引用未注册 ⇒ 构造期 error；代码注册了 JSON 不用 ⇒ 死机制。
     * 这是「机制留代码、表里只放 id」这条纪律的机器判据。
     */
    @Test
    fun modelParsersAreRegisteredInCode() {
        val listParsers = mutableSetOf<String>()
        val configParsers = mutableSetOf<String>()
        for (key in CliDriverSpecs.keys) {
            val mp = CliDriverSpecs.modelProbeOf(key) ?: continue
            when (mp.kind) {
                CliDriverSpecs.ModelProbeKind.LIST -> mp.parser?.let { listParsers += it }
                CliDriverSpecs.ModelProbeKind.CONFIG -> mp.parser?.let { configParsers += it }
                CliDriverSpecs.ModelProbeKind.NONE -> Unit
            }
        }
        assertEquals(listParsers, MODEL_PARSERS.keys, "LIST parser：JSON 引用集必须==代码注册表键集")
        assertEquals(configParsers, CONFIG_PARSERS.keys, "CONFIG parser：JSON 引用集必须==代码注册表键集")
        // catlog 派生表与 JSON catalogSource 一致（PROVIDER_CATALOG_SOURCE 不许手写）。
        val expectedCatalog = CliDriverSpecs.builtins.mapNotNull { spec ->
            CliDriverSpecs.modelProbeOf(spec.key)?.catalogSource?.let { it.provider to it.driver }
        }.toMap()
        assertEquals(
            expectedCatalog,
            DriverModelCatalog.PROVIDER_CATALOG_SOURCE,
            "provider→源驱动映射必须从 JSON 的 catalogSource 派生",
        )
    }

    /**
     * 登录态探针声明（批4b）的真源守卫：生产不许再手写正则/可查询集/子命令骨架，
     * 全部经 CliDriverSpecs.authProbeOf 读 JSON 投影。三类形态每家都要有声明。
     */
    @Test
    fun authProbesAreDeclaredInTheSpecProjection() {
        val cli = live("agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/CliAuthProbes.kt")
        assertTrue(
            "CliDriverSpecs.authProbeOf" in cli,
            "CliAuthProbes 必须从 JSON 投影读 argv/signals/golden，不许手写第二份",
        )
        // 旧世界的手写正则与 QUERYABLE 集合一律不许回来。
        assertFalseRegExp(
            cli,
            "CODE_LOGGED_OUT|CODE_LOGGED_IN|CLAUDE_FALSE|CLAUDE_TRUE|OPENCODE_STORED",
            "CliAuthProbes 不许再手写正则常量（真源在 JSON 的 authProbe.signals）",
        )
        assertFalseRegExp(
            cli,
            "QUERYABLE\\s*=|setOf\\(\\\"codex\\\"|\\\"login\\\", \\\"status\\\"",
            "CliAuthProbes 不许再手写可查询集/子命令骨架（真源在 JSON 的 authProbe.argv）",
        )

        val agy = live("agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/AgyAuthProbe.kt")
        assertTrue(
            "CliDriverSpecs.authProbeOf" in agy,
            "AgyAuthProbe.classify 必须从投影读 marker/ignoreCase/exit 判据",
        )
        assertFalseRegExp(
            agy,
            "const val NOT_LOGGED_IN_MARKER",
            "未登录 marker 不许再写成硬编码 const（真源在 JSON 的 authProbe）",
        )
        val authProbes = live("agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/DriverAuthProbes.kt")
        assertTrue(
            "CliDriverSpecs.authProbeOf" in authProbes,
            "DriverAuthProbes.classify 必须从投影读 marker，不许引用硬编码常量",
        )

        // dsh 的 FILE 声明（env key + 凭证文件路径）必须与 DshCredentials 机制对账——
        // JSON 只放声明，解析 yaml 找 ref 的机制留在 DshCredentials，两边不许漂。
        val dsh = CliDriverSpecs.authProbeOf("dsh")
            ?: throw AssertionError("dsh 缺 authProbe 投影")
        assertEquals(
            listOf(DshCredentials.ENV_KEY), dsh.credentialKeys,
            "dsh 的 authProbe.credentialKeys 必须与 DshCredentials.ENV_KEY 一致",
        )
        assertEquals(
            DshCredentials.CREDENTIALS_FILE, dsh.credentialFile,
            "dsh 的 authProbe.credentialFile 必须与 DshCredentials 的凭证文件路径一致" +
                "（判定层按声明查文件、注入层按 const 解析，两条路径分叉就是「界面说没有、实际拿到了」）",
        )

        // 「只读 status 查询」的名单由 JSON 的 kind 决定，判定层的分派也必须跟着投影走：
        // 从前那里硬编码 `"claude", "codex", "opencode"`，新增一家 CLI_CMD 会静默落进
        // 兜底分支变成「永远未判定」，界面上看不出任何异常。
        val judgement = live("agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/DriverAuthJudgement.kt")
        assertTrue(
            "CliAuthProbes.supports(key)" in judgement,
            "DriverAuthJudgement 必须按投影路由 CLI_CMD（CliAuthProbes.supports），不许硬编码驱动名单",
        )
        assertFalseRegExp(
            judgement,
            "\\\"claude\\\", \\\"codex\\\", \\\"opencode\\\"",
            "DriverAuthJudgement 不许再硬编码只读查询的三家名单（真源在 JSON 的 authProbe.kind）",
        )
        // 判定层允许出现的「按驱动名分支」只有 zcode 一条（setting.json 弱信号投影表达不了）。
        // 用**等值白名单**而不是逐个反例：反例只拦得住已知的那两个名字，将来写
        // `key == "gemini"` 照样通过——症状正是本类要防的「新增驱动静默落进兜底」。
        val keyBranches = Regex("key\\s*==\\s*\"([a-z0-9]+)\"")
            .findAll(judgement).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf("zcode"), keyBranches,
            "判定层只允许 zcode 一条按键名分支；其余驱动必须走 authProbe.kind 路由" +
                "（要加 bespoke 分支，先给 JSON 一个 kind）",
        )
        for (key in CliDriverSpecs.keys) {
            val spec = CliDriverSpecs.authProbeOf(key) ?: continue
            if (spec.kind == CliDriverSpecs.AuthProbeKind.CLI_CMD) {
                assertTrue(CliAuthProbes.supports(key), "$key 声明为 CLI_CMD，supports() 必须为真")
            } else {
                assertFalse(CliAuthProbes.supports(key), "$key 不是 CLI_CMD，supports() 不许为真")
            }
        }

        // 「已登录也不足以画绿灯」的例外在 JSON 里声明，不在代码里按键名写 if（键名白名单见上）。
        assertTrue(
            "authenticatedIsHint" in judgement,
            "降级例外必须从投影读 authenticatedIsHint，不许按键名硬编码",
        )
        assertEquals(
            true, CliDriverSpecs.authProbeOf("opencode")?.authenticatedIsHint,
            "opencode 的 stored 只证明存过凭证 ⇒ authenticatedIsHint 必须为 true",
        )
        assertEquals(
            false, CliDriverSpecs.authProbeOf("codex")?.authenticatedIsHint,
            "codex 的 login status 是确定态 ⇒ authenticatedIsHint 必须为 false",
        )

        // FILE 型（凭证物位置完全已知）的判据也必须来自声明（键名白名单见上：只许 zcode）。
        assertTrue(
            "CliDriverSpecs.AuthProbeKind.FILE" in judgement && "hasDeclaredCredential" in judgement,
            "DriverAuthJudgement 必须按 authProbe.kind == FILE 判凭证物（判据读声明，不按驱动名分支）",
        )
        // HOME 读不到时不许折成 false（=「确定未登录」）：那会让界面指着其实存在的凭证文件
        // 要用户去配 key。nullable 是本仓「读不到 ≠ 没有」的落点。
        assertTrue(
            "fun hasDeclaredCredential(env: Map<String, String>, spec: CliDriverSpecs.AuthProbeSpec): Boolean?" in authProbes,
            "hasDeclaredCredential 必须返回 Boolean?（HOME 读不到 = 判不了，不是确定未登录）",
        )

        // CLI_MARKER 的纯判定必须按 key 取声明：恒读 agy 那份会让第二家 marker 驱动被 agy 的
        // 签名判一遍（假绿/假红都可能）。AgyAuthProbe 只做枚举折换，不另写判定顺序。
        assertTrue(
            "fun classify(driverKey: String" in authProbes,
            "DriverAuthProbes.classify 必须按 key 取声明（第二家 CLI_MARKER 不许套 agy 的 marker）",
        )
        assertTrue(
            "DriverAuthProbes.classify(DriverKeys.AGY" in agy,
            "AgyAuthProbe.classify 必须委托通用 classify，不许另写一份判定顺序",
        )
        // CLI_MARKER 的执行器目前只有 agy 一台（超时、线程泵、登录态记忆、invalidate 都在
        // AgyAuthProbe 里）。第二家声明这个 kind 时，判定层会拿注入的 agy 探针去跑它的命令 ——
        // 结论是 agy 的登录态。放开这条之前先把执行器抽成按 key 取 argv 的通用探针。
        val markerDrivers = CliDriverSpecs.keys.filter {
            CliDriverSpecs.authProbeOf(it)?.kind == CliDriverSpecs.AuthProbeKind.CLI_MARKER
        }
        assertEquals(
            setOf(DriverKeys.AGY), markerDrivers.toSet(),
            "CLI_MARKER 目前只有 agy 有执行器；新增需先抽象通用探针执行器（否则判定层会静默误判）",
        )
    }

    @Test
    fun uiDisplayNamesCoverEverySpecKey() {
        // 回落显示名只有一份手写表：`DriverBrand.displayNameOf`（名册读不到时才用得上）。
        // 从前 `formatDriverName` 里还抄了一份 `when`，两份表必然漂（`Codex CLI` vs `Codex`），
        // 所以那份抄件被删了 —— 这张表就是唯一的落点。
        // UI 显示名是通用函数，允许为历史/外部 agentKind 多留映射；但每家内置 CLI 必须有。
        val code = live("composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/theme/DriverBrand.kt")
        val body = bodyOf(code, "fun displayNameOf(driverKey: String): String = when")
        val keys = Regex("\"([a-z0-9]+)\" ->").findAll(body).map { it.groupValues[1] }.toSet()
        val missing = CliDriverSpecs.keys - keys
        assertTrue(missing.isEmpty(), "DriverBrand.displayNameOf 缺了内置 CLI 的回落名：$missing")

        // 反向钉子：`formatDriverName` 不许再长出第二份手写表（只许读名册 + 回落这一处）。
        val roster = live("composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/components/TeamRosterDialog.kt")
        val format = bodyOf(roster, "internal fun formatDriverName")
        assertTrue(
            "DriverBrand.displayNameOf" in format,
            "formatDriverName 必须回落到 DriverBrand.displayNameOf，别再手写第二份表",
        )
        assertTrue(
            Regex("\"([a-z0-9]+)\" ->").findAll(format).none(),
            "formatDriverName 里不许再出现手写键表（两份表必然漂）",
        )
    }
}
