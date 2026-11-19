// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

/**
 * 内置 CLI 驱动的**身份卡**：一张纯数据表，回答「每家是什么」，不回答「它在不在」。
 *
 * ## 为什么要有它
 *
 * 新增一家 CLI 曾经要改六处（发现、装配、岗位档案、模型旗标、凭证白名单、UI 显示名），
 * 少改一处就能编译通过、测试全绿，只在真机派单时以「装了却看不见 / 配了模型不生效 /
 * 派单报 no cli driver」的形式暴露。这张表把其中**四处纯数据**收敛成单一来源：
 *
 * - [AgentProfiles] 的岗位档案（key / vendor / displayName / 岗位）从这里派生；
 * - [AgentProfiles] 的干活棒 / 审核棒优先序从这里派生；
 * - [DriverModelFlags] 的按次选模型能力从这里派生；
 * - UI 显示名与另两处装配面通过 `CliDriverCoverageGuardTest` 与本表对账。
 *
 * ## 真源在 JSON，本文件是只读投影面
 *
 * [builtins] 的数据行来自同包生成物 `DriverSpecTable.generated.kt`（`DO NOT EDIT`），它的
 * 真源是 `agent-contract/driver-specs.json`。构建期 `:driverSpecProject` 投影、`:driverSpecDrift`
 * 判漂移，校验（未知键拒 + [Spec] 的两条不变式 + 键集）前移到 codegen。本文件保留 [Spec]
 * 类型、枚举与 `init` 的 require 作**双保险**（外部直接构造也要挡），但**不许**再手写内置
 * 清单——那会重新制造两份真相。
 *
 * 发现路径、参数模板、凭证白名单的**值**仍是各自的代码与数据 —— 它们是行为与敏感值，
 * 不搬进身份卡；守卫只钉「每家在这些面上都有一个分支」，不搬动实现。
 *
 * ## 形状约束
 *
 * 本对象在 `:agent-contract`（纯接口、零 IO、不依赖任何上游模块），所以任何字段都必须是
 * 不可变纯数据。岗位枚举独立于 [AgentProfiles.Role] —— 后者是对外契约类型、引用面广，
 * 派生处做一次显式映射，避免为了一张表改动所有调用点。
 */
object CliDriverSpecs {

    /** 岗位：与 [AgentProfiles.Role] 同域，派生时显式映射。 */
    enum class Role { WORKER, REVIEWER, BOTH }

    /**
     * 按次指定模型走**哪种通道**。
     *
     * - [FLAG]：命令行旗标（codex `--model`、claude `--model`…）；
     * - [CONFIG_PATCH]：没有旗标，走 CLI 自己的配置覆盖通道（dsh 的 `--patch`）。
     *   **不是**「不支持」—— 用户照样能按次选模型，只是传参形态不同；
     * - [NONE]：真的不支持（zcode 只能在应用里选）。
     *
     * 为什么要分出来：只看 `modelFlag != null` 会把 dsh 判成不支持，而它其实支持 ——
     * 症状是界面上那一格被置灰、保存期被拒，用户配不了本可配的东西。
     */
    enum class ModelMechanism { FLAG, CONFIG_PATCH, NONE }

    /**
     * 沙箱网络策略（声明面）。值语义与 `OsSandboxGuard.NetworkPolicy` 对齐，但**刻意独立**：
     * 本模块是零 IO 契约层，不许依赖 agentNode 的执行类；派生处做一次显式映射。
     * OPEN 放行云端 API 与 loopback；另两档只留 loopback 或完全断网，喂给要连模型 API 的
     * 内置驱动会静默断网——代码生成时校验全集必须 OPEN。
     */
    enum class NetworkPolicy { OPEN, AIR_GAPPED, LOOPBACK }

    /**
     * `--sandbox` 取值的声明：环境变量名 / 合法值集合 / 默认值。只有需要该旗标的驱动
     * （当前仅 codex）才有，缺席即「这家没有沙箱模式开关」。
     */
    data class SandboxModeSpec(
        val envVar: String,
        val allowed: List<String>,
        val default: String,
    )

    /**
     * 可执行文件发现的**声明**（机制——按目录搜索、可执行文件判定——留在 CliDiscovery 代码里）：
     * [envVar] 显式覆盖位、[bin] PATH 上的名字、[extraBinRelative] 应用包内的相对路径
     * （null = 没有内置副本）、[extraPathDirs] 相对 HOME 的额外搜索目录。
     */
    data class DiscoverySpec(
        val envVar: String,
        val bin: String,
        val extraBinRelative: String?,
        val extraPathDirs: List<String>,
    )

    /** 启动形态判定：这些后缀的 CLI 交给 node，路径作为首参。按路径后缀判定，不按驱动 key。 */
    data class LaunchSpec(val nodeExtensions: List<String>)

    /**
     * 模型目录探针的**声明**（机制——进程执行、输出解析函数——留在 `DriverModelCatalog`）。
     *
     * - [LIST]：跑一条 `argv` 把全部模型列出来，[parser] 解析成 id + 显示名；
     * - [CONFIG]：没有列表子命令（dsh），跑 `argv` 读**当前**值，再按 [catalogSource]
     *   从别家的目录推导候选（provider → 源驱动 + id 前缀）；
     * - [NONE]：没有可信列表通道（claude/codebuddy/zcode），不许带 argv/parser。
     *
     * [parser] 是**机制 id**（如 `two-column` / `codex-json` / `dsh-default`），codegen
     * 钉它在已注册集合内，守卫再钉代码侧注册表与集合严格相等——新增 parser 两头同步。
     */
    enum class ModelProbeKind { LIST, CONFIG, NONE }

    /** CONFIG 型探针的候选来源：provider → 源驱动目录里的 id 前缀。 */
    data class CatalogSource(val provider: String, val driver: String, val prefix: String)

    data class ModelProbeSpec(
        val kind: ModelProbeKind,
        val argv: List<String>,
        val parser: String?,
        val catalogSource: CatalogSource?,
    )

    /** 探测时限：目录缓存 TTL 与单次列表命令超时（毫秒）。 */
    data class ProbeLimits(val modelCatalogTtlMs: Long, val modelProbeTimeoutMs: Long)

    /**
     * 登录态探针声明（机制——判定顺序、退出码纪律、证据强度映射——留在 `CliAuthProbes`/
     * `AgyAuthProbe`/`DriverAuthJudgement`，表里只放声明 + 正则 + golden 夹具）。
     *
     * - [AuthProbeKind.CLI_CMD]：跑厂家只读 status 子命令，[signals] 有序（首个命中返回），
     *   `blank` 信号判空输出；[AuthProbeKind.CLI_MARKER]：跑 `models`，exit 0 = 已登录，
     *   [notAuthenticatedMarker] 命中 = 未登录（agy 实测形态）；
     * - [AuthProbeKind.FILE]：凭证文件/env 弱判据（dsh），只给 HINT；
     * - [AuthProbeKind.NONE]：没有可信判据（codebuddy/zcode）。
     *
     * [golden] 是脱敏的真实输出样本 + 期望 verdict，Codegen 校验结构覆盖多 verdict，
     * Kotlin 测试用**生产 classify** 逐条断言——改正则必红，且不依赖本机装没装 CLI。
     */
    enum class AuthProbeKind { CLI_CMD, CLI_MARKER, FILE, NONE }

    /** 登录态三态，中性命名打通 CLI_CMD 与 CLI_MARKER 两种判定（生产侧映射回各自枚举）。 */
    enum class AuthVerdict { AUTHENTICATED, NOT_AUTHENTICATED, UNKNOWN }

    /** 一条有序判定信号：regex 与 blank 二选一；UNKNOWN 是兜底，不做信号。 */
    data class AuthSignal(
        val verdict: AuthVerdict,
        val regex: String?,
        val ignoreCase: Boolean,
        val blank: Boolean,
    )

    /** 一条 golden 夹具：合成输入 + 期望 verdict。 */
    data class AuthGolden(val exitCode: Int, val text: String, val verdict: AuthVerdict)

    data class AuthProbeSpec(
        val kind: AuthProbeKind,
        val argv: List<String>,
        val signals: List<AuthSignal>,
        /** CLI_MARKER：exit 0 视为已登录（codegen 校验 authenticatedOn 只能是 EXIT_ZERO）。 */
        val authenticatedOnExitZero: Boolean,
        val notAuthenticatedMarker: String?,
        val markerIgnoreCase: Boolean,
        val credentialKeys: List<String>,
        val credentialFile: String?,
        /**
         * true = 即便信号判定「已登录」也不足以画绿灯（opencode 的 `stored` 只证明存过凭证）。
         * 映射层据此把 AUTHENTICATED 降成 UNKNOWN/HINT，不再按驱动名硬编码例外。
         */
        val authenticatedIsHint: Boolean,
        val golden: List<AuthGolden>,
    )

    /**
     * argv 模板（每个元素要么字面量、要么 `${占位符}`）。四份模板对应首跳/续跳 × 裸路径/沙箱：
     * - [plain] 首跳裸路径；[sandbox] 首跳沙箱（缺省 = 与 plain 相同）；
     * - [resumePlain] / [resumeSandbox] 续跳（null = 该驱动无续跑能力）。
     *
     * 占位符由 [CliArgv.render] 按名替换成动态 token 列表，`${prompt}` 恰好一次由 codegen 校验，
     * 裸路径模板不含提权旗标也由 codegen 校验。机制（模型 patch 写盘、session 正则提取、
     * codex 沙箱模式）留在代码，只把 token 骨架与位置放进真源。
     */
    data class ArgvSpec(
        val plain: List<String>,
        val sandbox: List<String>?,
        val resumePlain: List<String>?,
        val resumeSandbox: List<String>?,
    )

    /**
     * 一家内置 CLI 的身份。
     *
     * [supportsPerTaskModel] 与 [modelFlag] 必须一致：支持却没旗标会在派生时给出空能力，
     * 变成「配了模型却静默跑默认」——那正是 [DriverModelFlags] 要防的缺陷；不支持却带旗标
     * 则相反，会把按次选模型开放给其实做不到的驱动。构造期就拒绝这两种不一致。
     */
    data class Spec(
        val key: String,
        val displayName: String,
        val vendor: String,
        val role: Role,
        val supportsPerTaskModel: Boolean,
        val modelFlag: String? = null,
        /**
         * 传参通道。默认按 [modelFlag] 推导：有旗标 = [ModelMechanism.FLAG]，否则
         * [ModelMechanism.NONE]；dsh 这类「支持但走配置覆盖」的显式给 [ModelMechanism.CONFIG_PATCH]。
         */
        val modelMechanism: ModelMechanism? = null,
        /**
         * 盘面/下拉里的**短名**（方案 §3.3/§3.9）：null = 取 [displayName] 的第一个词。
         * `DeepSeek Harness` 这类词组名需要显式短名，其余单词条自然取整词。
         */
        val shortName: String? = null,
        /** 干活棒优先序（数字小者优先）；null = 不参与自动组队。 */
        val workerPriority: Int? = null,
        /** 审核棒优先序（数字小者优先）；null = 不参与自动审核。 */
        val reviewerPriority: Int? = null,
    ) {
        /** 实际通道：显式值优先，否则由旗标是否存在推导。 */
        val effectiveModelMechanism: ModelMechanism
            get() = modelMechanism
                ?: if (modelFlag != null) ModelMechanism.FLAG else ModelMechanism.NONE

        init {
            require(key.isNotBlank()) { "driver spec key must not be blank" }
            // 「支持」与「有通道」必须一致；旗标只属于 FLAG 通道。
            require(supportsPerTaskModel == (effectiveModelMechanism != ModelMechanism.NONE)) {
                "$key: supportsPerTaskModel and modelMechanism must agree (NONE means unsupported)"
            }
            require((modelFlag != null) == (effectiveModelMechanism == ModelMechanism.FLAG)) {
                "$key: modelFlag only belongs to FLAG mechanism"
            }
        }
    }

    /**
     * 七家内置 CLI。顺序即档案登记顺序；优先序单独由 [workerPriority]/[reviewerPriority] 给出，
     * 不靠列表位置 —— 读表的人不必从排列顺序推断行为。
     *
     * 数据行来自 JSON 真源的投影，不许在此手写（见 object KDoc「真源在 JSON」）。
     */
    val builtins: List<Spec> = driverSpecRows

    val byKey: Map<String, Spec> = builtins.associateBy { it.key }

    val keys: Set<String> = byKey.keys

    /** 干活棒优先序；由 spec 的优先级字段排序得出，不在别处另写一份列表。 */
    val workerKeys: List<String> =
        builtins.filter { it.workerPriority != null }.sortedBy { it.workerPriority }.map { it.key }

    /** 审核棒优先序；同样来自 spec。 */
    val reviewerKeys: List<String> =
        builtins.filter { it.reviewerPriority != null }.sortedBy { it.reviewerPriority }.map { it.key }

    /** 支持按次选模型的档案键（[DriverModelFlags] 的能力来源）。 */
    val modelCapableKeys: Set<String> =
        builtins.filter { it.supportsPerTaskModel }.map { it.key }.toSet()

    // ── 行为声明面：真源同样是 driver-specs.json，由生成物投影，经这里只读暴露 ──

    /**
     * 行为面查表的唯一入口：生成物表按裸键 `spec.key` 索引，调用方常传 driverId
     * （`codex-cli`），这里用 [DriverKeys.stripCliSuffix] 归一（幂等，裸键原样返回）。
     */
    private fun bareKey(idOrKey: String): String = DriverKeys.stripCliSuffix(idOrKey)

    /** 该 driver 的子进程凭证白名单（变量名，永不含值）；认不出 = 空集（不猜）。 */
    fun envKeysOf(driverId: String): List<String> = driverEnvKeysByKey[bareKey(driverId)].orEmpty()

    /**
     * 该 driver 的沙箱网络策略。缺席**不兜底**：生成物按 drivers 全量投影，查不到只可能是
     * 传了不认识的键、或生成物被手改 —— 两者都该响亮失败。兜底 OPEN 是往「放行出网」的方向
     * 松：一旦将来引入 AIR_GAPPED 驱动，漏投影会静默变成放开，而症状是「认证超时」。
     */
    fun networkPolicyOf(driverId: String): NetworkPolicy {
        val key = bareKey(driverId)
        // 缺席有三种可能：驱动 id 拼错 / 投影漏了一家 / 测试夹具自造了 id。三条都写进消息——
        // 这个函数是 `SandboxedCliDriver` 默认参数的一部分，会在构造某家驱动时立刻求值，
        // 只说「JSON 缺 X」会让夹具作者去翻真源找一个根本不打算存在的驱动。
        return driverNetworkPolicyByKey[key] ?: error(
            "driver-specs.json has no networkPolicy for '$key' (driverId='$driverId'). " +
                "Check the driver id spelling; for a test fixture with a synthetic id, " +
                "pass networkPolicy explicitly to SandboxedCliDriver.",
        )
    }

    /** 该 driver 的 `--sandbox` 声明；null = 这家没有沙箱模式开关。 */
    fun sandboxModeOf(driverId: String): SandboxModeSpec? = driverSandboxModeByKey[bareKey(driverId)]

    /** 该 driver 的可执行文件发现声明；null = 身份卡里有但没配发现（生成物漏投影）。 */
    fun discoveryOf(driverId: String): DiscoverySpec? = driverDiscoveryByKey[bareKey(driverId)]

    /** 启动形态判定规则（node 后缀）。 */
    val launch: LaunchSpec get() = driverLaunch

    /** 该 driver 的 argv 模板；null = 身份卡里有但没配 argv（生成物漏投影）。 */
    fun argvOf(driverId: String): ArgvSpec? = driverArgvByKey[bareKey(driverId)]

    /** 该 driver 的模型目录探针声明；null = 生成物漏投影。 */
    fun modelProbeOf(driverId: String): ModelProbeSpec? = driverModelProbeByKey[bareKey(driverId)]

    /** 探测时限（目录缓存 TTL + 单次列表命令超时）。 */
    val probeLimits: ProbeLimits get() = driverProbeLimits

    /** 该 driver 的登录态探针声明；null = 生成物漏投影。 */
    fun authProbeOf(driverId: String): AuthProbeSpec? = driverAuthProbeByKey[bareKey(driverId)]

    /** 沙箱 HOME 挂载白名单（扁平有序），生产直接读。 */
    val sandboxHomeLinks: List<String> get() = driverSandboxHomeLinks
}
