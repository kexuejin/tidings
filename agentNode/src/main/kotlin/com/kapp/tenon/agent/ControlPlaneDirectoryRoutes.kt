// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 宿主本地项目目录的四条路由（项目目录决策稿 §5.1）：目录档案 / 群指针 / 节点白名单。
 *
 * 数据真源是 [HostDirectoryStore]（本机落盘）；这些路由只做形状检查与转发，
 * 不缓存、不裁剪 —— 面板读到什么，档案里就是什么。写入成功统一回**整份快照**
 * （GET 的形状），让调用方一次拿到收敛后的视图，不必自己拼装。
 *
 * 四条路由都只服务**本机面板**（回环控制面），不产生任何协议帧 —— 档案、指针、
 * 白名单都是宿主本地事实，与对端无关。
 */

/** 宿主目录台账的只读快照（GET 与三写路由共用的响应形状）。 */
private fun snapshotJson(store: HostDirectoryStore): kotlinx.serialization.json.JsonObject =
    buildJsonObject {
        putJsonArray("directories") {
            store.directories().forEach { d ->
                addJsonObject {
                    put("dirId", d.dirId)
                    put("path", d.path)
                    put("displayName", d.displayName)
                    // 可空直接交给 put：JsonNull 由 kotlinx 的可空重载自己写。
                    put("gitRemote", d.gitRemote)
                }
            }
        }
        putJsonObject("groupPointers") {
            store.groupPointers().forEach { (groupId, dirId) -> put(groupId, dirId) }
        }
        putJsonArray("whitelist") {
            store.whitelist().forEach { w ->
                addJsonObject {
                    put("dirId", w.dirId)
                    put("readOnly", w.readOnly)
                }
            }
        }
    }

/** `GET /v1/host/directories` —— 目录档案 + 群指针 + 白名单的整份快照。 */
internal fun ControlPlane.hostDirectories(exchange: HttpExchange) {
    respond(exchange, 200, snapshotJson(hostDirectoryStore))
}

/**
 * `POST /v1/host/directories` —— 登记一个项目目录（幂等：同 canonical path 不换 dirId）。
 *
 * body `{path, displayName?, gitRemote?}`。登记即纳入白名单为可读写（显式动作即授权）；
 * 回整份快照。
 */
internal fun ControlPlane.hostDirectoryRegister(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val path = obj["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (path.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "path is required (an absolute host directory)"))
        return
    }
    val displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull
    val gitRemote = obj["gitRemote"]?.jsonPrimitive?.contentOrNull
    runCatching { hostDirectoryStore.register(path, displayName, gitRemote) }
        .fold(
            onSuccess = { respond(exchange, 200, snapshotJson(hostDirectoryStore)) },
            onFailure = {
                respond(exchange, 400, errorJson("bad-request", it.message ?: "path is not registerable"))
            },
        )
}

/**
 * `POST /v1/host/group-directory` —— 设置 / 清除一个群的项目目录指针。
 *
 * body `{groupId, dirId?}`：`dirId` 缺省或 null = 清除该群的指针；给出时必须指向
 * 已登记的目录（悬空指针在保存这一刻就拒，不留给执行点）。
 */
    // ⚠ 这条 HTTP 入口只写指针、**不触发席位投影重建与广播**（那是域层
    // `RelayController.handleSetGroupProjectDirectory` 的职责：解析结果要写回
    // group.info 才算全群共识）。仅诊断/测试使用；产品路径一律走域层入口，
    // 否则执行判据读 store、对端显示读声明，两头各说各话。
internal fun ControlPlane.hostGroupDirectory(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val groupId = obj["groupId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (groupId.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "groupId is required"))
        return
    }
    val dirId = when (val raw = obj["dirId"]) {
        null, is JsonNull -> null
        is JsonPrimitive -> raw.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        else -> {
            respond(exchange, 400, errorJson("bad-request", "dirId must be a string or null"))
            return
        }
    }
    if (!hostDirectoryStore.setGroupPointer(groupId, dirId)) {
        respond(exchange, 400, errorJson("bad-request", "unknown dirId: the directory must be registered first"))
        return
    }
    respond(
        exchange,
        200,
        buildJsonObject {
            put("ok", true)
            put("groupId", groupId)
            put("dirId", dirId)
        },
    )
}

/**
 * `POST /v1/host/whitelist` —— 放行 / 收回一个目录，或改它的读写档。
 *
 * body `{dirId, allowed, readOnly?}`。`allowed` 必须是布尔字面量（字符串是假数据，
 * 400，不猜）；`readOnly` 缺省 false（可读写档）。白名单默认 deny：没有条目的目录
 * 一律不放行。
 */
internal fun ControlPlane.hostWhitelist(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val dirId = obj["dirId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (dirId.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "dirId is required"))
        return
    }
    val allowed = boolOrNull(obj["allowed"])
    if (allowed == null) {
        respond(exchange, 400, errorJson("bad-request", "allowed must be a boolean literal"))
        return
    }
    val readOnly = if (obj.containsKey("readOnly")) boolOrNull(obj["readOnly"]) else false
    if (readOnly == null) {
        respond(exchange, 400, errorJson("bad-request", "readOnly must be a boolean literal"))
        return
    }
    if (!hostDirectoryStore.setWhitelist(dirId, readOnly, allowed)) {
        respond(exchange, 400, errorJson("bad-request", "unknown dirId: register the directory first"))
        return
    }
    respond(
        exchange,
        200,
        buildJsonObject {
            put("ok", true)
            put("dirId", dirId)
            put("allowed", allowed)
            put("readOnly", readOnly)
        },
    )
}

/**
 * 「这个群的项目目录，对这个 agent 解析出哪个 wsId」的**公开回环入口**（决策稿 §3.1）。
 *
 * 装配方（DesktopExtension）经 [com.kapp.tenon.domain.HostDirectoryPort.resolveForGroup]
 * 把它递给域层：指针变化 / 入驻 agent / 重新解析时，域层用它把本机席位的执行投影
 * （`defaultWsId` + `allowedWorkspaces`）写回群声明。null = 没设指针 / 指针悬空 /
 * 该 agent 未宣告这条路径（[DirectoryResolution.NotExposed]）—— 调用方按「未暴露」
 * 处理，不猜。（曾住在 ControlPlane 类体内；为守住控制面单文件行数预算挪到这里，
 * 判据不变、仍是 directoryResolver 单源。）
 */
fun ControlPlane.resolveGroupDirectoryWsId(groupId: String, agentKey: String): String? {
    val dirId = hostDirectoryStore.pointerOf(groupId) ?: return null
    return when (
        val r = directoryResolver.resolve(agentKey, dirId) { hostDirectoryStore.directoryOf(it) }
    ) {
        is DirectoryResolution.Resolved -> r.wsId
        else -> null
    }
}
