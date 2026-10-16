// SPDX-License-Identifier: GPL-3.0-only

package app.relay.agent.drivers

/**
 * 驱动总线注册中心与路由器。
 *
 * 管理已注册的驱动并支持热切换与跨驱动能力汇聚。
 */
class DriverRegistry {
    private val drivers = mutableMapOf<String, AgentDriver>()
    private var defaultDriverId: String = "native"

    init {
        register(NativeRelayDriver())
        register(DsBotDriver())
        register(OpenAiDriver())
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

    suspend fun applyAclOverride(driverId: String?, enabledIds: Set<String>) {
        val target = getDriver(driverId) ?: return
        target.updateCapabilityAcl(enabledIds)
    }
}
