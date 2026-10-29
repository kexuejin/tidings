// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.drivers

/**
 * 驱动总线注册中心与路由器。
 *
 * 管理已注册的驱动并支持热切换与跨驱动能力汇聚。
 *
 * ## 能力 ACL 是宿主状态（M2 收编）
 *
 * [updateCapabilityAcl] / [isCapabilityAllowed] 落在本类（经 [DriverCapabilityAcl]），
 * 不在 [AgentDriver] 接口上：内置驱动经构造注入的谓词读它，插件驱动（ExternalPluginDriver）
 * 完全不接触它 —— ACL 调用不出宿主进程，更不会被透传到插件进程。
 */
class DriverRegistry(private val acl: DriverCapabilityAcl = DriverCapabilityAcl()) {

    private val drivers = mutableMapOf<String, AgentDriver>()

    init {
        // 内置驱动共享宿主 ACL：注入的是**谓词**（活的引用，按各自的 driverId 绑定），
        // 宿主名单一变，驱动下一次自报/门控立即看到新状态，不需要任何重新注册。
        register(NativeRelayDriver({ cap -> acl.allows("native", cap) }))
        register(DsBotDriver(aclAllows = { cap -> acl.allows("ds-bot", cap) }))
        register(OpenAiDriver(aclAllows = { cap -> acl.allows("openai", cap) }))
    }

    fun register(driver: AgentDriver) {
        synchronized(drivers) {
            drivers[driver.driverId] = driver
        }
    }

    fun getDriver(driverId: String?): AgentDriver? {
        synchronized(drivers) {
            return drivers[driverId ?: defaultDriverId] ?: drivers[defaultDriverId]
        }
    }

    fun listDrivers(): List<AgentDriver> {
        synchronized(drivers) {
            return drivers.values.toList()
        }
    }

    suspend fun listAllCapabilities(): List<DriverCapability> {
        val list = mutableListOf<DriverCapability>()
        val all = listDrivers()
        for (driver in all) {
            list.addAll(driver.listCapabilities())
        }
        return list
    }

    /** 宿主侧写一个驱动的能力允许名单（替换语义；手机端远程治理下发后即时生效）。 */
    fun updateCapabilityAcl(driverId: String, enabledCapabilityIds: Set<String>) {
        acl.update(driverId, enabledCapabilityIds)
    }

    /** 宿主侧查询：该驱动的这个能力当前是否被允许。 */
    fun isCapabilityAllowed(driverId: String, capabilityId: String): Boolean =
        acl.allows(driverId, capabilityId)

    private val defaultDriverId: String = "native"
}
