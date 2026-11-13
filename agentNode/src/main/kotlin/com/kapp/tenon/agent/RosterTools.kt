// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.core.protocol.AgentDuties
import com.kapp.tenon.core.protocol.RosterWriteTier
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * agent 能调的本机工具（P3）。
 *
 * 一条工具 = 一个**动作名** + 参数表。参数表不是文档装饰：工具面的 400 文案直接由它
 * 生成（缺哪个、允许哪些取值），而 agent 是先读 `GET /v1/agent/tools` 再决定怎么调的
 * —— 两边共用一份，才不会出现「说明里写 X、实现要 Y」。
 *
 * ## 四个动作的分工
 *
 * - `employee.update`：改员工档案的名字 / 职责（第一段就通的链路，作为模板）；
 * - `team.roster.read`：读本机团队档案（**只读，不弹审批卡**）—— 动手前先看有谁；
 * - `team.create` / `team.update`：建队 / 改名换名册。**不含进群、解散**：那两件事
 *   动的是群里所有人的可见面（席位与 @ token），留给人在界面上一次做完。
 *
 * ## 为什么读动作也要登记在目录里
 *
 * agent 不会去猜一个没写下来的路由。把「能读」写进同一张表，它才知道**先读后写**；
 * 而读不需要审批（不改变世界），`mutates = false` 就是这一条的机器可读形式。
 */
object RosterTools {

    const val EMPLOYEE_UPDATE: String = "employee.update"
    const val TEAM_READ: String = "team.roster.read"
    const val TEAM_CREATE: String = "team.create"
    const val TEAM_UPDATE: String = "team.update"

    /** 参数形状。写进目录（`shape`），agent 据此决定拼字符串还是拼数组。 */
    enum class Shape {
        /** 单值字符串。 */
        TEXT,

        /** 有限取值域（见 [Arg.values]）。 */
        ENUM,

        /** 字符串数组。 */
        TEXT_LIST,

        /** 对象数组（每个元素是一个组员：`agentId` / `name` / `role`）。 */
        MEMBER_LIST,
    }

    data class Arg(
        val name: String,
        val description: String,
        val required: Boolean,
        val shape: Shape = Shape.TEXT,
        /** 有限取值域；null = 自由文本（长度仍受 `Limits` 约束）。 */
        val values: List<String>? = null,
    )

    data class Spec(
        val name: String,
        val description: String,
        val args: List<Arg>,
        /** 是否需要 `roster` 写授权（= 人会看到审批卡）。读取类工具不会。 */
        val mutates: Boolean,
    )

    val ALL: List<Spec> = listOf(
        Spec(
            name = EMPLOYEE_UPDATE,
            description = "改本机一份员工档案的名字 / 职责。改不了 @ token 与 agentId —— 那两样是身份。",
            args = listOf(
                Arg("employeeId", "档案键（emp-…），来自 GET /v1/agent/tools/employees", required = true),
                Arg("name", "新显示名（可含中文）", required = false),
                Arg("duty", "新职责", required = false, shape = Shape.ENUM, values = AgentDuties.ALL),
            ),
            mutates = true,
        ),
        Spec(
            name = TEAM_READ,
            description = "读本机团队档案（名字 / 领队 / 名册 / 在哪些群）。不写任何东西，不需要审批。",
            args = listOf(
                Arg("teamId", "只读这一份（tm-…）；不给 = 全部", required = false),
            ),
            mutates = false,
        ),
        Spec(
            name = TEAM_CREATE,
            description = "建一支团队档案（名字 + 领队 + 名册）。groupIds 可空 = 先建档、不进群；" +
                "进群与解散不开放给 agent（动的是群里所有人的可见面）。",
            args = listOf(
                Arg("name", "团队显示名（人看的，可含中文，不用作 @ token）", required = true),
                Arg("leaderAgentId", "领队的本机 agent 身份键（不是席位显示名）", required = true),
                Arg(
                    "members",
                    "名册：每个组员 {agentId, name, role}；name 是小写 ASCII slug（组员要能 @），role 是中文角色",
                    required = true,
                    shape = Shape.MEMBER_LIST,
                ),
                Arg("groupIds", "同时加进哪些群；不给 = 只建档（群里看不见、@ 不到）", required = false, shape = Shape.TEXT_LIST),
            ),
            mutates = true,
        ),
        Spec(
            name = TEAM_UPDATE,
            description = "改一支团队档案的名字 / 名册（改一次对所有引用群生效）。改不了 teamId 与领队。",
            args = listOf(
                Arg("teamId", "档案键（tm-…），来自 team.roster.read", required = true),
                Arg("name", "新团队名", required = false),
                Arg("members", "新名册（整表替换）；{agentId, name, role}", required = false, shape = Shape.MEMBER_LIST),
            ),
            mutates = true,
        ),
    )

    fun specOf(name: String?): Spec? = ALL.firstOrNull { it.name == name }

    /** 一次通过校验的调用。字段为 null = 这次不改。 */
    sealed interface Call {

        data class EmployeeUpdate(
            val employeeId: String,
            val name: String?,
            val duty: String?,
        ) : Call

        data class TeamRead(val teamId: String?) : Call

        data class TeamCreate(val draft: RosterTeamDraft) : Call

        data class TeamUpdate(
            val teamId: String,
            val name: String?,
            val members: List<RosterMember>?,
        ) : Call
    }

    /**
     * 校验参数。返回 `Call` 或**人话原因**（直接进 400 响应）。
     *
     * 判据与写路径同源：`duty` 取值走 `AgentDuties.normalize` 之后比对是否原样，
     * 空名/超长/重名交给写路径（它会回 `NAME_INVALID` / `ROSTER_INVALID`），
     * 这里只挡「结构不对」—— 同一条规则写两遍必然漂。
     */
    fun parseCall(spec: Spec, args: JsonObject?): Result<Call> = when (spec.name) {
        EMPLOYEE_UPDATE -> parseEmployeeUpdate(spec, args)
        TEAM_READ -> Result.success(Call.TeamRead(text(args, "teamId")))
        TEAM_CREATE -> parseTeamCreate(args)
        TEAM_UPDATE -> parseTeamUpdate(args)
        else -> Result.failure(IllegalArgumentException("不认识的 tool「" + spec.name + "」"))
    }

    private fun parseEmployeeUpdate(spec: Spec, args: JsonObject?): Result<Call> {
        val employeeId = text(args, "employeeId")
            ?: return Result.failure(IllegalArgumentException("缺少 employeeId（" + spec.name + " 的必填参数）"))
        val name = text(args, "name")
        val rawDuty = text(args, "duty")
        val duty = rawDuty?.also { raw ->
            // 域外值不许被「悄悄折回 assistant」—— 那会让 agent 以为设了、其实没设。
            if (AgentDuties.normalize(raw) != raw) {
                return Result.failure(
                    IllegalArgumentException(
                        "duty 只能是 " + AgentDuties.ALL.joinToString(" / ") + "；收到「" + raw + "」",
                    ),
                )
            }
        }
        if (name == null && duty == null) {
            return Result.failure(IllegalArgumentException("name 与 duty 至少要给一个（否则这条调用什么都没改）"))
        }
        return Result.success(Call.EmployeeUpdate(employeeId, name, duty))
    }

    private fun parseTeamCreate(args: JsonObject?): Result<Call> {
        val name = text(args, "name")
            ?: return Result.failure(IllegalArgumentException("缺少 name（团队显示名）"))
        val leader = text(args, "leaderAgentId")
            ?: return Result.failure(IllegalArgumentException("缺少 leaderAgentId（领队的本机身份键）"))
        val members = members(args, "members").getOrElse { e ->
            return Result.failure(e)
        }
        if (members == null || members.isEmpty()) {
            return Result.failure(IllegalArgumentException("members 不能为空：一支没有组员的队伍就是单兵"))
        }
        val groupIds = textList(args, "groupIds").getOrElse { e ->
            return Result.failure(e)
        }
        return Result.success(
            Call.TeamCreate(
                RosterTeamDraft(
                    name = name,
                    leaderAgentId = leader,
                    members = members,
                    groupIds = groupIds,
                ),
            ),
        )
    }

    private fun parseTeamUpdate(args: JsonObject?): Result<Call> {
        val teamId = text(args, "teamId")
            ?: return Result.failure(IllegalArgumentException("缺少 teamId（来自 team.roster.read）"))
        val name = text(args, "name")
        val members = members(args, "members").getOrElse { e ->
            return Result.failure(e)
        }
        if (name == null && members == null) {
            return Result.failure(IllegalArgumentException("name 与 members 至少要给一个（否则这条调用什么都没改）"))
        }
        return Result.success(Call.TeamUpdate(teamId, name, members))
    }

    private fun text(args: JsonObject?, key: String): String? =
        args?.get(key)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 字符串数组：非数组 = 报错（把 `"groupIds":"g1"` 读成「没给」会静默建成一支不进群的队），
     * 空串元素丢掉。
     */
    private fun textList(args: JsonObject?, key: String): Result<List<String>> {
        val node = args?.get(key) ?: return Result.success(emptyList())
        val array = node as? JsonArray
            ?: return Result.failure(IllegalArgumentException(key + " 必须是字符串数组"))
        return Result.success(
            array.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } },
        )
    }

    /**
     * 组员数组。null = 没给这个参数；非空结果里的每一行三个字段都必须在
     * （缺字段的 entry 会以「第 N 行的 agentId 为空」的措辞拒绝，而不是静默跳过）。
     */
    private fun members(args: JsonObject?, key: String): Result<List<RosterMember>?> {
        val node = args?.get(key) ?: return Result.success(null)
        val array = node as? JsonArray
            ?: return Result.failure(IllegalArgumentException(key + " 必须是数组（每项 {agentId, name, role}）"))
        val rows = mutableListOf<RosterMember>()
        array.forEachIndexed { index, element ->
            val obj = element as? JsonObject
                ?: return Result.failure(IllegalArgumentException(key + "[" + index + "] 必须是对象"))
            val agentId = text(obj, "agentId")
            val name = text(obj, "name")
            val role = text(obj, "role")
            val missing = listOf(
                "agentId" to agentId,
                "name" to name,
                "role" to role,
            ).filter { it.second == null }.map { it.first }
            if (missing.isNotEmpty()) {
                return Result.failure(
                    IllegalArgumentException(
                        key + "[" + index + "] 缺 " + missing.joinToString(" / ") + "（三个字段都要给）",
                    ),
                )
            }
            rows += RosterMember(agentId = agentId!!, name = name!!, role = role!!)
        }
        return Result.success(rows)
    }

    /**
     * 给 agent 的**用法简报**（目录里一并返回，也贴进沙箱 agent 的能力说明）。
     *
     * 由 [ALL] 生成而不是手写常量：动作名改一次、简报跟着改，不会出现「说明里还在教
     * 一个已经改名或删掉的动作」。
     */
    fun brief(): String = buildString {
        append("写编制 / 改档案走本机控制面，别直接改本机的 settings.json：")
        ALL.forEach { spec ->
            append("\n- `").append(spec.name).append("`（").append(if (spec.mutates) "写" else "读").append("）")
            append(spec.description)
        }
        append("\n先 GET /v1/agent/tools 看参数表，再 POST /v1/agent/tools 调用")
        append("（MCP 宿主可用 tenon_roster_tools / tenon_roster_call）。")
        append("写动作默认要机主在手机或桌面上点同意；超时按拒绝处理，什么都没改。")
    }
}

/**
 * roster 写面的档位（P3 授权域）。
 *
 * - `request`（**默认**）：每次写都弹一张审批卡，机主点了同意才执行；
 * - `auto`：机主已经明确放行，直接执行，但仍受硬上限（只能改本机档案、只能改名字/职责/名册）。
 *
 * ## 为什么档位存在一个文件、而且没有 HTTP 写入口
 *
 * 「授权只由人/设备写」。控制面绑回环 + token，而拿到 token 的本地进程也包括 agent 自己
 * —— 给一条 `PUT /v1/agent/tools/policy` 等于让它给自己放行。所以档位只读文件：
 * 机主的设置界面（`RosterWriteSection` → 桌面 `DesktopTrustedDevicesPanel`）写它，
 * agent 只能读。
 *
 * 文件读不出来（坏 JSON / 未知取值）一律按 `request` 运行，并把 `loadError` 如实报给
 * 调用方 —— 与 `AgentGrantStore` 同一条 fail-closed 口径。
 */
class RosterWritePolicy(private val file: File) {

    /**
     * 档位取值——**指向 `:core` 的同一份常量**（界面层也要用它，抄两份必然漂：
     * 界面写 `auto`、这里认 `automatic` 时，用户点了「直接执行」而宿主仍按 request 跑）。
     */
    object Tiers {
        const val REQUEST: String = RosterWriteTier.REQUEST
        const val AUTO: String = RosterWriteTier.AUTO
        val VALUES: List<String> = RosterWriteTier.VALUES
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cachedTier: String? = null

    @Volatile
    private var loadError: String? = null

    /** 当前档位（未知/坏文件 ⇒ `request`）。 */
    fun tier(): String {
        ensureLoaded()
        return cachedTier ?: Tiers.REQUEST
    }

    /** 读盘失败的如实记录；null = 这次启动读到了合法档位或文件不存在（默认 request）。 */
    fun error(): String? {
        ensureLoaded()
        return loadError
    }

    /** 仅设置界面用：写档位并落盘。agent 侧没有调用点。 */
    fun setTier(next: String): Boolean {
        if (next !in Tiers.VALUES) return false
        val text = buildJsonObject { put("tier", next) }.toString()
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(text)
            cachedTier = next
            loadError = null
            true
        }.getOrElse { false }
    }

    private fun ensureLoaded() {
        if (cachedTier != null) return
        val (tier, error) = readFromDisk()
        cachedTier = tier
        loadError = error
    }

    private fun readFromDisk(): Pair<String, String?> {
        if (!file.isFile) return RosterWritePolicy.Tiers.REQUEST to null
        return runCatching {
            val obj = json.parseToJsonElement(file.readText()) as? JsonObject
                ?: return@runCatching RosterWritePolicy.Tiers.REQUEST to "roster-write.json 不是对象；按 request 运行"
            val raw = obj["tier"]?.jsonPrimitive?.contentOrNull
            when {
                raw == null -> RosterWritePolicy.Tiers.REQUEST to "roster-write.json 没有 tier；按 request 运行"
                raw !in RosterWritePolicy.Tiers.VALUES ->
                    RosterWritePolicy.Tiers.REQUEST to "roster-write.json 的 tier=\"" + raw + "\" 不认识；按 request 运行"
                else -> raw to null
            }
        }.getOrElse { e ->
            RosterWritePolicy.Tiers.REQUEST to "roster-write.json 读不出来（" + (e.message ?: "未知错误") + "）；按 request 运行"
        }
    }
}
