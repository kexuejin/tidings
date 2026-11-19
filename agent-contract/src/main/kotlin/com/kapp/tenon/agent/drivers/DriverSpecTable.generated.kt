// SPDX-License-Identifier: GPL-3.0-only

// DO NOT EDIT —— 由 `./gradlew :driverSpecProject` 从 agent-contract/driver-specs.json 投影生成。
// 手改会在 :driverSpecDrift 判红；要改规格请改 JSON 后重新投影。
package com.kapp.tenon.agent.drivers

/**
 * 内置 CLI 驱动数据行，由 JSON 真源投影。公开面 [CliDriverSpecs] 只读引用本表，
 * 枚举与 require 在 Kotlin 侧保留作双保险。行为声明（envKeys/networkPolicy/
 * sandboxMode/homeLinks）也在这里投影，生产代码经 [CliDriverSpecs] 的公开访问器消费。
 */
internal val driverSpecRows: List<CliDriverSpecs.Spec> = listOf(
    CliDriverSpecs.Spec(
        key = "codex",
        displayName = "Codex",
        vendor = "openai",
        role = CliDriverSpecs.Role.WORKER,
        supportsPerTaskModel = true,
        modelFlag = "--model",
        workerPriority = 1,
    ),
    CliDriverSpecs.Spec(
        key = "claude",
        displayName = "Claude Code",
        vendor = "anthropic",
        role = CliDriverSpecs.Role.WORKER,
        supportsPerTaskModel = true,
        modelFlag = "--model",
        workerPriority = 2,
        reviewerPriority = 3,
    ),
    CliDriverSpecs.Spec(
        key = "agy",
        displayName = "Antigravity",
        vendor = "google",
        role = CliDriverSpecs.Role.WORKER,
        supportsPerTaskModel = true,
        modelFlag = "--model",
        workerPriority = 4,
        reviewerPriority = 4,
    ),
    CliDriverSpecs.Spec(
        key = "opencode",
        displayName = "OpenCode",
        vendor = "opencode",
        role = CliDriverSpecs.Role.BOTH,
        supportsPerTaskModel = true,
        modelFlag = "--model",
        workerPriority = 3,
        reviewerPriority = 2,
    ),
    CliDriverSpecs.Spec(
        key = "dsh",
        displayName = "DeepSeek Harness",
        vendor = "deepseek",
        role = CliDriverSpecs.Role.BOTH,
        supportsPerTaskModel = true,
        modelMechanism = CliDriverSpecs.ModelMechanism.CONFIG_PATCH,
        shortName = "DeepSeek",
        workerPriority = 5,
        reviewerPriority = 1,
    ),
    CliDriverSpecs.Spec(
        key = "codebuddy",
        displayName = "CodeBuddy",
        vendor = "tencent",
        role = CliDriverSpecs.Role.BOTH,
        supportsPerTaskModel = true,
        modelFlag = "--model",
        workerPriority = 6,
        reviewerPriority = 5,
    ),
    CliDriverSpecs.Spec(
        key = "zcode",
        displayName = "ZCode",
        vendor = "zhipu",
        role = CliDriverSpecs.Role.BOTH,
        supportsPerTaskModel = false,
        workerPriority = 7,
        reviewerPriority = 6,
    ),
)

/** 每家子进程凭证注入白名单的变量名（只含名字，值绝不进投影）。 */
internal val driverEnvKeysByKey: Map<String, List<String>> = linkedMapOf(
    "codex" to listOf(
            "CODEX_HOME",
            "OPENCODE_API_KEY",
        ),
    "claude" to listOf(
            "ANTHROPIC_BASE_URL",
            "ANTHROPIC_API_KEY",
            "ANTHROPIC_AUTH_TOKEN",
            "ANTHROPIC_MODEL",
            "ANTHROPIC_SMALL_FAST_MODEL",
            "ANTHROPIC_CUSTOM_HEADERS",
        ),
    "agy" to emptyList(),
    "opencode" to listOf(
            "OPENCODE_API_KEY",
            "OPENCODE_CONFIG",
            "HTTPS_PROXY",
            "HTTP_PROXY",
            "ALL_PROXY",
            "NO_PROXY",
            "https_proxy",
            "http_proxy",
            "all_proxy",
            "no_proxy",
        ),
    "dsh" to listOf(
            "DEEPSEEK_API_KEY",
            "OPENCODE_API_KEY",
            "DSH_PERMISSION_MODE",
            "HTTPS_PROXY",
            "HTTP_PROXY",
            "ALL_PROXY",
            "NO_PROXY",
            "https_proxy",
            "http_proxy",
            "all_proxy",
            "no_proxy",
        ),
    "codebuddy" to listOf(
            "CODEBUDDY_API_KEY",
            "CODEBUDDY_AUTH_TOKEN",
            "CODEBUDDY_INTERNET_ENVIRONMENT",
            "CODEBUDDY_BASE_URL",
            "CODEBUDDY_IS_SANDBOX",
            "HTTPS_PROXY",
            "HTTP_PROXY",
            "ALL_PROXY",
            "NO_PROXY",
            "https_proxy",
            "http_proxy",
            "all_proxy",
            "no_proxy",
        ),
    "zcode" to listOf(
            "ZCODE_API_KEY",
            "ZHIPU_API_KEY",
            "ZCODE_BUILTIN_PROVIDER_CONFIG_FILE",
            "ZCODE_PERSONAL_PROVIDER_CONFIG_FILE",
            "ZCODE_HTTP_PROXY",
            "HTTPS_PROXY",
            "HTTP_PROXY",
            "ALL_PROXY",
            "NO_PROXY",
            "https_proxy",
            "http_proxy",
            "all_proxy",
            "no_proxy",
        ),
)

/** 每家沙箱网络策略（全集 OPEN：模型 API 必须出网，OPEN 已含 loopback）。 */
internal val driverNetworkPolicyByKey: Map<String, CliDriverSpecs.NetworkPolicy> = linkedMapOf(
    "codex" to CliDriverSpecs.NetworkPolicy.OPEN,
    "claude" to CliDriverSpecs.NetworkPolicy.OPEN,
    "agy" to CliDriverSpecs.NetworkPolicy.OPEN,
    "opencode" to CliDriverSpecs.NetworkPolicy.OPEN,
    "dsh" to CliDriverSpecs.NetworkPolicy.OPEN,
    "codebuddy" to CliDriverSpecs.NetworkPolicy.OPEN,
    "zcode" to CliDriverSpecs.NetworkPolicy.OPEN,
)

/** 需要 --sandbox 取值的驱动（当前仅 codex）：env 键 / 合法值 / 默认值。 */
internal val driverSandboxModeByKey: Map<String, CliDriverSpecs.SandboxModeSpec> = linkedMapOf(
    "codex" to CliDriverSpecs.SandboxModeSpec(
        envVar = "TENON_CODEX_SANDBOX",
        allowed = listOf(
                "read-only",
                "workspace-write",
                "danger-full-access",
            ),
        default = "workspace-write",
    ),
)

/** 每家可执行文件发现声明（机制在 CliDiscovery，值在这里）。 */
internal val driverDiscoveryByKey: Map<String, CliDriverSpecs.DiscoverySpec> = linkedMapOf(
    "codex" to CliDriverSpecs.DiscoverySpec(
        envVar = "TENON_CODEX_CLI",
        bin = "codex",
        extraBinRelative = null,
        extraPathDirs = emptyList(),
    ),
    "claude" to CliDriverSpecs.DiscoverySpec(
        envVar = "TENON_CLAUDE_CLI",
        bin = "claude",
        extraBinRelative = null,
        extraPathDirs = emptyList(),
    ),
    "agy" to CliDriverSpecs.DiscoverySpec(
        envVar = "TENON_AGY_CLI",
        bin = "agy",
        extraBinRelative = null,
        extraPathDirs = emptyList(),
    ),
    "opencode" to CliDriverSpecs.DiscoverySpec(
        envVar = "TENON_OPENCODE_CLI",
        bin = "opencode",
        extraBinRelative = null,
        extraPathDirs = listOf(
                ".opencode/bin",
            ),
    ),
    "dsh" to CliDriverSpecs.DiscoverySpec(
        envVar = "TENON_DSH_CLI",
        bin = "dsh",
        extraBinRelative = null,
        extraPathDirs = emptyList(),
    ),
    "codebuddy" to CliDriverSpecs.DiscoverySpec(
        envVar = "TENON_CODEBUDDY_CLI",
        bin = "codebuddy",
        extraBinRelative = "WorkBuddy.app/Contents/Resources/app.asar.unpacked/cli/bin/codebuddy",
        extraPathDirs = emptyList(),
    ),
    "zcode" to CliDriverSpecs.DiscoverySpec(
        envVar = "TENON_ZCODE_CLI",
        bin = "zcode",
        extraBinRelative = "ZCode.app/Contents/Resources/glm/zcode.cjs",
        extraPathDirs = emptyList(),
    ),
)

/** 每家 argv 模板骨架；动态 token 由 CliArgv.render 按占位符替换。 */
internal val driverArgvByKey: Map<String, CliDriverSpecs.ArgvSpec> = linkedMapOf(
    "codex" to CliDriverSpecs.ArgvSpec(
        plain = listOf(
                "exec",
                "--json",
                "--sandbox",
                "\${sandbox}",
                "\${model}",
                "\${prompt}",
            ),
        sandbox = null,
        resumePlain = null,
        resumeSandbox = null,
    ),
    "claude" to CliDriverSpecs.ArgvSpec(
        plain = listOf(
                "-p",
                "\${prompt}",
                "--output-format",
                "stream-json",
                "--verbose",
                "\${model}",
            ),
        sandbox = listOf(
                "-p",
                "\${prompt}",
                "--output-format",
                "stream-json",
                "--verbose",
                "--permission-mode",
                "bypassPermissions",
                "\${model}",
            ),
        resumePlain = null,
        resumeSandbox = null,
    ),
    "agy" to CliDriverSpecs.ArgvSpec(
        plain = listOf(
                "-p",
                "\${prompt}",
                "--output-format",
                "stream-json",
                "\${model}",
            ),
        sandbox = listOf(
                "-p",
                "\${prompt}",
                "--output-format",
                "stream-json",
                "--mode",
                "auto",
                "--dangerously-skip-permissions",
                "\${model}",
            ),
        resumePlain = null,
        resumeSandbox = null,
    ),
    "opencode" to CliDriverSpecs.ArgvSpec(
        plain = listOf(
                "run",
                "--standalone",
                "--auto",
                "--format",
                "json",
                "\${model}",
                "\${prompt}",
            ),
        sandbox = null,
        resumePlain = null,
        resumeSandbox = null,
    ),
    "dsh" to CliDriverSpecs.ArgvSpec(
        plain = listOf(
                "\${patch}",
                "--profile",
                "headless",
                "--json",
                "\${prompt}",
            ),
        sandbox = null,
        resumePlain = listOf(
                "\${patch}",
                "--profile",
                "headless",
                "--json",
                "--session-id",
                "\${session}",
                "\${prompt}",
            ),
        resumeSandbox = null,
    ),
    "codebuddy" to CliDriverSpecs.ArgvSpec(
        plain = listOf(
                "-p",
                "\${prompt}",
                "--output-format",
                "stream-json",
                "--verbose",
                "\${model}",
            ),
        sandbox = listOf(
                "-p",
                "\${prompt}",
                "--output-format",
                "stream-json",
                "--verbose",
                "-y",
                "\${model}",
            ),
        resumePlain = listOf(
                "--resume",
                "\${session}",
                "-p",
                "\${prompt}",
                "--output-format",
                "stream-json",
                "--verbose",
                "\${model}",
            ),
        resumeSandbox = listOf(
                "--resume",
                "\${session}",
                "-p",
                "\${prompt}",
                "--output-format",
                "stream-json",
                "--verbose",
                "-y",
                "\${model}",
            ),
    ),
    "zcode" to CliDriverSpecs.ArgvSpec(
        plain = listOf(
                "-p",
                "\${prompt}",
                "--mode",
                "yolo",
                "--json",
                "--no-color",
            ),
        sandbox = null,
        resumePlain = null,
        resumeSandbox = null,
    ),
)

/** 启动形态判定：node 后缀。 */
internal val driverLaunch: CliDriverSpecs.LaunchSpec = CliDriverSpecs.LaunchSpec(
    nodeExtensions = listOf(
            ".cjs",
            ".js",
        ),
)

/** 每家模型目录探针声明；parser 是机制 id，函数实现在 DriverModelCatalog 注册。 */
internal val driverModelProbeByKey: Map<String, CliDriverSpecs.ModelProbeSpec> = linkedMapOf(
    "codex" to CliDriverSpecs.ModelProbeSpec(
        kind = CliDriverSpecs.ModelProbeKind.LIST,
        argv = listOf(
                "debug",
                "models",
            ),
        parser = "codex-json",
        catalogSource = null,
    ),
    "claude" to CliDriverSpecs.ModelProbeSpec(
        kind = CliDriverSpecs.ModelProbeKind.NONE,
        argv = emptyList(),
        parser = null,
        catalogSource = null,
    ),
    "agy" to CliDriverSpecs.ModelProbeSpec(
        kind = CliDriverSpecs.ModelProbeKind.LIST,
        argv = listOf(
                "models",
            ),
        parser = "two-column",
        catalogSource = null,
    ),
    "opencode" to CliDriverSpecs.ModelProbeSpec(
        kind = CliDriverSpecs.ModelProbeKind.LIST,
        argv = listOf(
                "models",
            ),
        parser = "two-column",
        catalogSource = null,
    ),
    "dsh" to CliDriverSpecs.ModelProbeSpec(
        kind = CliDriverSpecs.ModelProbeKind.CONFIG,
        argv = listOf(
                "--profile",
                "headless",
                "--dump-config",
            ),
        parser = "dsh-default",
        catalogSource = CliDriverSpecs.CatalogSource(
            provider = "dsh-opencode-go",
            driver = "opencode",
            prefix = "opencode-go/",
        ),
    ),
    "codebuddy" to CliDriverSpecs.ModelProbeSpec(
        kind = CliDriverSpecs.ModelProbeKind.NONE,
        argv = emptyList(),
        parser = null,
        catalogSource = null,
    ),
    "zcode" to CliDriverSpecs.ModelProbeSpec(
        kind = CliDriverSpecs.ModelProbeKind.NONE,
        argv = emptyList(),
        parser = null,
        catalogSource = null,
    ),
)

/** 探测时限：目录缓存 TTL 与单次列表命令超时（毫秒）。 */
internal val driverProbeLimits: CliDriverSpecs.ProbeLimits = CliDriverSpecs.ProbeLimits(
    modelCatalogTtlMs = 600000L,
    modelProbeTimeoutMs = 20000L,
)

/** 每家登录态探针声明：argv/正则信号/golden 夹具/marker；判定逻辑在 CliAuthProbes/AgyAuthProbe。 */
internal val driverAuthProbeByKey: Map<String, CliDriverSpecs.AuthProbeSpec> = linkedMapOf(
    "codex" to CliDriverSpecs.AuthProbeSpec(
        kind = CliDriverSpecs.AuthProbeKind.CLI_CMD,
        argv = listOf(
                "\${cliPath}",
                "login",
                "status",
            ),
        authenticatedOnExitZero = false,
        signals = listOf(
            CliDriverSpecs.AuthSignal(
                verdict = CliDriverSpecs.AuthVerdict.NOT_AUTHENTICATED,
                regex = "not\\s+logged\\s+in",
                ignoreCase = true,
                blank = false,
            ),
            CliDriverSpecs.AuthSignal(
                verdict = CliDriverSpecs.AuthVerdict.AUTHENTICATED,
                regex = "logged\\s+in",
                ignoreCase = true,
                blank = false,
            ),
        ),
        notAuthenticatedMarker = null,
        markerIgnoreCase = false,
        authenticatedIsHint = false,
        credentialKeys = emptyList(),
        credentialFile = null,
        golden = listOf(
            CliDriverSpecs.AuthGolden(
                exitCode = 0,
                text = "Not logged in\n",
                verdict = CliDriverSpecs.AuthVerdict.NOT_AUTHENTICATED,
            ),
            CliDriverSpecs.AuthGolden(
                exitCode = 1,
                text = "Not logged in\n",
                verdict = CliDriverSpecs.AuthVerdict.NOT_AUTHENTICATED,
            ),
            CliDriverSpecs.AuthGolden(
                exitCode = 0,
                text = "Logged in as user@example.com\n",
                verdict = CliDriverSpecs.AuthVerdict.AUTHENTICATED,
            ),
            CliDriverSpecs.AuthGolden(
                exitCode = 0,
                text = "some noise",
                verdict = CliDriverSpecs.AuthVerdict.UNKNOWN,
            ),
        ),
    ),
    "claude" to CliDriverSpecs.AuthProbeSpec(
        kind = CliDriverSpecs.AuthProbeKind.CLI_CMD,
        argv = listOf(
                "\${cliPath}",
                "auth",
                "status",
            ),
        authenticatedOnExitZero = false,
        signals = listOf(
            CliDriverSpecs.AuthSignal(
                verdict = CliDriverSpecs.AuthVerdict.NOT_AUTHENTICATED,
                regex = "\"loggedIn\"\\s*:\\s*false",
                ignoreCase = false,
                blank = false,
            ),
            CliDriverSpecs.AuthSignal(
                verdict = CliDriverSpecs.AuthVerdict.AUTHENTICATED,
                regex = "\"loggedIn\"\\s*:\\s*true",
                ignoreCase = false,
                blank = false,
            ),
        ),
        notAuthenticatedMarker = null,
        markerIgnoreCase = false,
        authenticatedIsHint = false,
        credentialKeys = emptyList(),
        credentialFile = null,
        golden = listOf(
            CliDriverSpecs.AuthGolden(
                exitCode = 0,
                text = "{\"loggedIn\": false, \"authMethod\": \"none\"}",
                verdict = CliDriverSpecs.AuthVerdict.NOT_AUTHENTICATED,
            ),
            CliDriverSpecs.AuthGolden(
                exitCode = 1,
                text = "{\"loggedIn\": false}",
                verdict = CliDriverSpecs.AuthVerdict.NOT_AUTHENTICATED,
            ),
            CliDriverSpecs.AuthGolden(
                exitCode = 0,
                text = "{\"loggedIn\": true, \"authMethod\": \"oauth\"}",
                verdict = CliDriverSpecs.AuthVerdict.AUTHENTICATED,
            ),
            CliDriverSpecs.AuthGolden(
                exitCode = 0,
                text = "{\"status\": \"ok\"}",
                verdict = CliDriverSpecs.AuthVerdict.UNKNOWN,
            ),
        ),
    ),
    "agy" to CliDriverSpecs.AuthProbeSpec(
        kind = CliDriverSpecs.AuthProbeKind.CLI_MARKER,
        argv = listOf(
                "\${cliPath}",
                "models",
            ),
        authenticatedOnExitZero = true,
        signals = emptyList(),
        notAuthenticatedMarker = "not logged into antigravity",
        markerIgnoreCase = true,
        authenticatedIsHint = false,
        credentialKeys = emptyList(),
        credentialFile = null,
        golden = listOf(
            CliDriverSpecs.AuthGolden(
                exitCode = 0,
                text = "gemini-3.8-flash-high	Gemini 3.8 Flash (High)\n",
                verdict = CliDriverSpecs.AuthVerdict.AUTHENTICATED,
            ),
            CliDriverSpecs.AuthGolden(
                exitCode = 1,
                text = "error getting token source: You are not logged into Antigravity.",
                verdict = CliDriverSpecs.AuthVerdict.NOT_AUTHENTICATED,
            ),
            CliDriverSpecs.AuthGolden(
                exitCode = 1,
                text = "You Are Not Logged Into ANTIGRAVITY.",
                verdict = CliDriverSpecs.AuthVerdict.NOT_AUTHENTICATED,
            ),
            CliDriverSpecs.AuthGolden(
                exitCode = 1,
                text = "connection refused",
                verdict = CliDriverSpecs.AuthVerdict.UNKNOWN,
            ),
            CliDriverSpecs.AuthGolden(
                exitCode = 2,
                text = "network unreachable",
                verdict = CliDriverSpecs.AuthVerdict.UNKNOWN,
            ),
            CliDriverSpecs.AuthGolden(
                exitCode = -1,
                text = "",
                verdict = CliDriverSpecs.AuthVerdict.UNKNOWN,
            ),
        ),
    ),
    "opencode" to CliDriverSpecs.AuthProbeSpec(
        kind = CliDriverSpecs.AuthProbeKind.CLI_CMD,
        argv = listOf(
                "\${cliPath}",
                "auth",
                "list",
            ),
        authenticatedOnExitZero = false,
        signals = listOf(
            CliDriverSpecs.AuthSignal(
                verdict = CliDriverSpecs.AuthVerdict.AUTHENTICATED,
                regex = "stored",
                ignoreCase = true,
                blank = false,
            ),
            CliDriverSpecs.AuthSignal(
                verdict = CliDriverSpecs.AuthVerdict.NOT_AUTHENTICATED,
                regex = null,
                ignoreCase = false,
                blank = true,
            ),
        ),
        notAuthenticatedMarker = null,
        markerIgnoreCase = false,
        authenticatedIsHint = true,
        credentialKeys = emptyList(),
        credentialFile = null,
        golden = listOf(
            CliDriverSpecs.AuthGolden(
                exitCode = 0,
                text = "OpenCode Go  OpenCode Go  stored\n",
                verdict = CliDriverSpecs.AuthVerdict.AUTHENTICATED,
            ),
            CliDriverSpecs.AuthGolden(
                exitCode = 0,
                text = "",
                verdict = CliDriverSpecs.AuthVerdict.NOT_AUTHENTICATED,
            ),
            CliDriverSpecs.AuthGolden(
                exitCode = 0,
                text = "some providers but no creds",
                verdict = CliDriverSpecs.AuthVerdict.UNKNOWN,
            ),
        ),
    ),
    "dsh" to CliDriverSpecs.AuthProbeSpec(
        kind = CliDriverSpecs.AuthProbeKind.FILE,
        argv = emptyList(),
        authenticatedOnExitZero = false,
        signals = emptyList(),
        notAuthenticatedMarker = null,
        markerIgnoreCase = false,
        authenticatedIsHint = false,
        credentialKeys = listOf(
                "DEEPSEEK_API_KEY",
            ),
        credentialFile = ".dsh/.credentials.yaml",
        golden = emptyList(),
    ),
    "codebuddy" to CliDriverSpecs.AuthProbeSpec(
        kind = CliDriverSpecs.AuthProbeKind.NONE,
        argv = emptyList(),
        authenticatedOnExitZero = false,
        signals = emptyList(),
        notAuthenticatedMarker = null,
        markerIgnoreCase = false,
        authenticatedIsHint = false,
        credentialKeys = emptyList(),
        credentialFile = null,
        golden = emptyList(),
    ),
    "zcode" to CliDriverSpecs.AuthProbeSpec(
        kind = CliDriverSpecs.AuthProbeKind.NONE,
        argv = emptyList(),
        authenticatedOnExitZero = false,
        signals = emptyList(),
        notAuthenticatedMarker = null,
        markerIgnoreCase = false,
        authenticatedIsHint = false,
        credentialKeys = emptyList(),
        credentialFile = null,
        golden = emptyList(),
    ),
)

/** 沙箱 HOME 挂载白名单：扁平有序，顺序即投影顺序。 */
internal val driverSandboxHomeLinks: List<String> = listOf(
    ".codex", // — codex
    ".claude", // — claude
    ".claude.json", // — claude
    ".gemini", // — agy: Google 登录态缓存，agy 驱动需要
    ".agy", // — agy
    ".dsh", // — dsh
    ".codebuddy", // — codebuddy
    ".zcode", // — zcode
    ".npm", // — shared: npm 缓存，node 形态驱动复用
    ".nvm", // — shared: node 版本管理
    ".gitconfig", // — shared: git 提交身份，子进程推代码需要
)
