// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 宿主本机的一个项目目录档案（项目目录决策稿 §3 的三层之一）。
 *
 * 它是**宿主本地**的事实：某个本机目录被主人登记成了「项目目录」，与任何 agent 无关、
 * 不进协议帧。agent 相关的那一层是 [AgentWorkspaceRegistry] 的工作区（协议 wsId），
 * 两层靠 [ProjectDirectoryResolver] 在执行点现算连接 —— 不在这里存映射。
 *
 * [path] 一律是 canonical path（`File.canonicalPath`），它是幂等登记的判据：
 * 同一个目录经软链、`..` 或相对路径再来登记一次，落到的还是同一条。
 */
data class HostDirectory(
    val dirId: String,
    val path: String,
    val displayName: String,
    val gitRemote: String? = null,
)

/**
 * 节点级目录白名单的一条：**允许** agent 看到哪个目录（默认 deny），以及是不是只读。
 *
 * 为什么默认 deny：目录档案只是「这台机器上有哪些项目」，不等于「agent 可以进去」；
 * 放行必须是显式动作。登记（[HostDirectoryStore.register]）本身就是显式动作，
 * 所以登记即纳入白名单为可读写 —— 除此之外没有第二条自动放行的路。
 */
data class WhitelistEntry(val dirId: String, val readOnly: Boolean)

/**
 * 宿主本地的项目目录档案 + 群项目指针 + 节点目录白名单（项目目录决策稿 §3/§5.1）。
 *
 * 三份数据一份文件（`host-directories.json`）的理由：它们是同一件事的三个侧面
 * （有哪些目录 / 哪个群用哪个 / 哪些目录放行），分开落盘只会让「改了一半」成为常态。
 *
 * ## 读不回来时是「空」，不是「全放行」
 *
 * 文件不存在 = 没登记过（正常）；损坏 / schemaVersion 不是本类认识的版本 = **整份不可信、
 * 按空处理**（与 `TeamExecutionStore` 同口径）。空档案的效果是**全拒**：白名单为空时
 * [whitelistAllows] 对任何目录都回 false —— 丢掉一份档案只会让 agent 暂时进不了目录
 * （方向收紧），反过来「解析失败当全放行」则是替人授权。落盘纪律与
 * [AgentToolApprovalStore] 逐字同源（写锁内 persist、tmp rename、0600）。
 *
 * 线程安全：控制面线程池与桌面面板都会读写，全部走 [rwLock]；改内存与落盘在**同一把
 * 写锁**里（并发写会互相覆盖，理由见 [AgentToolApprovalStore.persistLocked]）。
 */
class HostDirectoryStore(private val file: File) {

    private val rwLock = ReentrantReadWriteLock()
    private var dirs: MutableList<HostDirectory> = mutableListOf()
    private var pointers: MutableMap<String, String> = mutableMapOf()
    private var whitelist: MutableList<WhitelistEntry> = mutableListOf()

    /**
     * 「档案变了」的回调（与 [AgentToolApprovalStore.changeListeners] 同一套写法）。
     *
     * 写这份档案的人不都在盯着它的 UI：手机端在群信息里选了项目目录，宿主在控制面线程里
     * 落账；没有通知，桌面设置页开着也看不到新目录，用户会以为那一下没生效。
     * 回调在锁**外**触发，按注册顺序同步调用。
     */
    private val changeListeners = mutableListOf<() -> Unit>()

    init {
        load()
    }

    /** 注册一个「档案变了」的回调；返回值用于注销。 */
    fun addChangeListener(listener: () -> Unit): () -> Unit {
        synchronized(changeListeners) { changeListeners += listener }
        return { synchronized(changeListeners) { changeListeners -= listener } }
    }

    private fun notifyChanged() {
        val snapshotOfListeners = synchronized(changeListeners) { changeListeners.toList() }
        snapshotOfListeners.forEach { runCatching { it() } }
    }

    // ------------------------------------------------------------------ 读

    /** 全部目录档案（只读快照）。 */
    fun directories(): List<HostDirectory> = rwLock.read { dirs.toList() }

    /** 全部群项目指针（只读快照；键 = groupId）。 */
    fun groupPointers(): Map<String, String> = rwLock.read { pointers.toMap() }

    /** 某个群指向的项目目录；null = 没设过（群还没有项目目录）。 */
    fun pointerOf(groupId: String): String? =
        rwLock.read { if (groupId.isBlank()) null else pointers[groupId] }

    /** 按目录 id 取档案；null = 没有这一条（指针悬空 / id 拼错）。 */
    fun directoryOf(dirId: String): HostDirectory? {
        val id = dirId.trim()
        if (id.isEmpty()) return null
        return rwLock.read { dirs.firstOrNull { it.dirId == id } }
    }

    /** 目录白名单快照（只读）。 */
    fun whitelist(): List<WhitelistEntry> = rwLock.read { whitelist.toList() }

    /**
     * 目录白名单判定（默认 deny）。
     *
     * 白名单里没有这个 dirId ⇒ false，无论要求什么权限；`readOnly = true` 的条目在
     * `requireWrite = true` 时也不放行。判据缺一角时**只能更严**：这里的 false 的代价是
     * 一次执行被拒，true 错了代价是 agent 写了不该写的目录。
     */
    fun whitelistAllows(dirId: String, requireWrite: Boolean): Boolean {
        val id = dirId.trim()
        if (id.isEmpty()) return false
        return rwLock.read {
            val entry = whitelist.firstOrNull { it.dirId == id } ?: return@read false
            !requireWrite || !entry.readOnly
        }
    }

    // ------------------------------------------------------------------ 写

    /**
     * 登记一个项目目录（幂等）。canonical path 已存在 ⇒ 返回既有条目，**不换 dirId、
     * 不动白名单** —— 重入不是重新授权（如果这条目录刚被用户从白名单摘掉，
     * 一次重入不该把它悄悄放回来）；gitRemote / displayName 也不覆盖，改档案走显式更新。
     *
     * dirId 用 sha256(canonicalPath) 的前 12 位十六进制（不用 SecureRandom）：同一目录
     * 无论何时何进程登记，得到的 id 相同 —— 测试可断言、日志可对账；档案量是个位数，
     * 48 位空间里的撞库概率可忽略。幂等本身仍由 canonical path 查重保证，id 确定性是
     * 叠加的保险而不是唯一防线。
     *
     * 新登记**自动纳入白名单为可读写**（显式动作即授权）：用户指名登记一个目录，
     * 就是为了让 agent 能用它 —— 登记完还要再手动放行一次的流程只会教人把白名单
     * 整个当摆设。
     *
     * @throws IllegalArgumentException path 为空白（调用方应先 400，而不是把当前目录登记进去）。
     */
    fun register(path: String, displayName: String?, gitRemote: String? = null): HostDirectory {
        val trimmed = path.trim()
        require(trimmed.isNotEmpty()) { "path must not be blank" }
        val canonical = File(trimmed).canonicalPath
        var created: HostDirectory? = null
        rwLock.write {
            val existing = dirs.firstOrNull { it.path == canonical }
            if (existing != null) return@write
            val dir = HostDirectory(
                dirId = mintDirId(canonical),
                path = canonical,
                displayName = displayName?.trim()?.takeIf { it.isNotEmpty() }
                    ?: File(canonical).name.ifBlank { canonical },
                gitRemote = gitRemote?.trim()?.takeIf { it.isNotEmpty() },
            )
            dirs += dir
            whitelist += WhitelistEntry(dir.dirId, readOnly = false)
            persistLocked()
            created = dir
        }
        if (created != null) notifyChanged()
        return created ?: rwLock.read { dirs.first { it.path == canonical } }
    }

    /**
     * 设置 / 清除一个群的项目目录指针。
     *
     * [dirId] 为 null = 清除（幂等：本来就没设也回 true）；非空时必须指向已登记的目录。
     * 返回 false = 群 id 空白或 dirId 不存在，**什么都没改** —— 悬空指针会让执行点
     * 在解析时才发现「档案没了」，把错误前移到保存这一刻。
     */
    fun setGroupPointer(groupId: String, dirId: String?): Boolean {
        val group = groupId.trim()
        if (group.isEmpty()) return false
        var changed = false
        val ok = rwLock.write {
            var admitted = false
            if (dirId == null) {
                // 清除幂等：本来就没设也回 true —— false 只留给非法参数（群 id 空白）。
                admitted = true
                if (pointers.remove(group) != null) changed = true
            } else {
                val id = dirId.trim()
                if (id.isNotEmpty() && dirs.any { it.dirId == id }) {
                    admitted = true
                    if (pointers[group] != id) changed = true
                    pointers[group] = id
                }
            }
            // 改内存与落盘在**同一把写锁**里（并发写会互相覆盖）。
            if (changed) persistLocked()
            admitted
        }
        if (ok && changed) notifyChanged()
        return ok
    }

    /**
     * 放行 / 收回一个目录的白名单资格。[allowed] = false 即收回（无论此前状态）。
     * 目录不存在 ⇒ false（先有档案，再谈放行）。幂等：同值重设不落盘、不通知。
     */
    fun setWhitelist(dirId: String, readOnly: Boolean, allowed: Boolean): Boolean {
        val id = dirId.trim()
        if (id.isEmpty()) return false
        var changed = false
        val ok = rwLock.write {
            if (dirs.none { it.dirId == id }) {
                false
            } else {
                val before = whitelist.toList()
                val after = if (allowed) {
                    before.filter { it.dirId != id } + WhitelistEntry(id, readOnly)
                } else {
                    before.filter { it.dirId != id }
                }
                if (after != before) {
                    whitelist = after.toMutableList()
                    changed = true
                    persistLocked()
                }
                true
            }
        }
        if (ok && changed) notifyChanged()
        return ok
    }

    /**
     * 删除一个目录档案，**级联**清掉指向它的群指针与白名单条目。
     *
     * 级联不是可选优化：留下的悬空指针会让「群还挂着项目目录、目录却查无此事」成为
     * 界面上的常态假账；留下的白名单条目则相反 —— 档案没了条目还在，重新登记
     * （幂等不成立，会铸新 id）时可能意外继承旧授权。两条都当场清干净。
     */
    fun remove(dirId: String): Boolean {
        val id = dirId.trim()
        if (id.isEmpty()) return false
        var removed = false
        rwLock.write {
            val before = dirs.size
            dirs.removeAll { it.dirId == id }
            removed = dirs.size < before
            if (removed) {
                pointers = pointers.filterValues { it != id }.toMutableMap()
                whitelist.removeAll { it.dirId == id }
                persistLocked()
            }
        }
        if (removed) notifyChanged()
        return removed
    }

    // ------------------------------------------------------------------ 持久化

    private fun load() {
        if (!file.isFile) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return
        val version = (root["schemaVersion"] as? JsonPrimitive)?.longOrNull
        if (version != SCHEMA_VERSION) return
        val dirArr = root["directories"] as? JsonArray ?: return
        val parsedDirs = dirArr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val dirId = o.str("dirId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val path = o.str("path")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val displayName = o.str("displayName")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            HostDirectory(dirId, path, displayName, o.str("gitRemote")?.takeIf { it.isNotBlank() })
        }
        // 结构非法 = 整份不可信：目录条目有一条拆不出来，就按全空处理（fail-closed，
        // 与 TeamExecutionStore.loadLocked 同口径）。半个档案比没有档案更难排查。
        if (parsedDirs.size != dirArr.size) return
        val pointerObj = root["groupPointers"] as? JsonObject ?: return
        val parsedPointers = pointerObj.entries.mapNotNull { (group, value) ->
            val dirId = (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            group to dirId
        }
        // 指针悬空（指向已不存在的目录）只丢那一条：下游判据是 directoryOf = null ⇒
        // 「群还没有项目目录」，悬空指向不可能变成一次执行。
        val dirIds = parsedDirs.map { it.dirId }.toSet()
        val whitelistArr = root["whitelist"] as? JsonArray ?: return
        val parsedWhitelist = whitelistArr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val dirId = o.str("dirId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val readOnly = (o["readOnly"] as? JsonPrimitive)?.booleanOrNull ?: return@mapNotNull null
            WhitelistEntry(dirId, readOnly)
        }
        if (parsedWhitelist.size != whitelistArr.size) return
        rwLock.write {
            dirs = parsedDirs.toMutableList()
            pointers = parsedPointers.filter { it.second in dirIds }.toMap().toMutableMap()
            whitelist = parsedWhitelist.filter { it.dirId in dirIds }.toMutableList()
        }
    }

    /** 落盘。**调用方必须已持有写锁**（与 [AgentToolApprovalStore.persistLocked] 同一约定）。 */
    private fun persistLocked() {
        val json = buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("directories", buildJsonArray {
                dirs.forEach { d ->
                    add(buildJsonObject {
                        put("dirId", JsonPrimitive(d.dirId))
                        put("path", JsonPrimitive(d.path))
                        put("displayName", JsonPrimitive(d.displayName))
                        d.gitRemote?.let { put("gitRemote", JsonPrimitive(it)) }
                    })
                }
            })
            put("groupPointers", buildJsonObject {
                pointers.forEach { (group, dirId) -> put(group, JsonPrimitive(dirId)) }
            })
            put("whitelist", buildJsonArray {
                whitelist.forEach { w ->
                    add(buildJsonObject {
                        put("dirId", JsonPrimitive(w.dirId))
                        put("readOnly", JsonPrimitive(w.readOnly))
                    })
                }
            })
        }
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.absolutePath + ".tmp")
            tmp.writeText(json.toString())
            // 0600：这份文件是**访问策略**（哪些目录放行、哪个群指向哪个项目），同机其它
            // 用户能写它就等于能给自己放行目录。rename 不继承源权限，所以两个文件都要设。
            tmp.setReadable(false, false)
            tmp.setReadable(true, true)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        }
    }

    /**
     * dirId = `"dir-" + sha256(canonicalPath) 前 6 字节的 12 位小写十六进制`。
     * 确定性选型（而非随机数）的理由见 [register] 的 KDoc。
     */
    private fun mintDirId(canonical: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.encodeToByteArray())
        val hex = digest.take(DIR_ID_BYTES).joinToString("") { "%02x".format(it) }
        return DIR_ID_PREFIX + hex
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        /** 装配缺省（与其它宿主本地台账同目录同生命周期）。 */
        fun defaultFile(): File = File(com.kapp.tenon.agent.plugins.HostDirs.default(), "host-directories.json")

        private const val SCHEMA_VERSION: Long = 1L
        private const val DIR_ID_PREFIX = "dir-"
        private const val DIR_ID_BYTES = 6
    }
}
