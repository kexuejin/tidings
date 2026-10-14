// SPDX-License-Identifier: GPL-3.0-only

package app.relay.platform

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android mDNS 广播器：用 `NsdManager` 发布 `_relay._udp`。
 *
 * 上一版这里写着"手机端作为服务发现方，通常不需要广播"，于是它虽然被实现了却**一次都没被
 * 调用过**，自动发现因此端到端恒为零。现在是主路径：两端都广播，谁先到谁被看见。
 *
 * 发布是异步的 —— `registerService` 立即返回，成败只走 listener。所以 [start] 会挂起等
 * 回调，超时按失败算；立即返回 `true` 会让"系统其实拒绝了注册"看起来像成功。
 */
class AndroidMdnsAdvertiser(
    context: Context,
) : MdnsAdvertiser {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null

    override suspend fun start(serviceName: String, port: Int): Boolean {
        val nsd = nsdManager ?: run {
            Log.e(TAG, "设备没有 NSD_SERVICE，无法发布")
            return false
        }
        // 重复启动时先撤掉上一次：NsdManager 对同名服务会自己改名（"名字 (2)"），
        // 而对端看到的名字就不可预测了。
        stop()

        return try {
            val serviceInfo = NsdServiceInfo().apply {
                this.serviceName = serviceName
                this.serviceType = SERVICE_TYPE
                this.port = port
            }

            val outcome = withTimeoutOrNull(REGISTER_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val listener = object : NsdManager.RegistrationListener {
                        override fun onServiceRegistered(info: NsdServiceInfo) {
                            Log.d(TAG, "已发布：${info.serviceName} :$port")
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                            Log.e(TAG, "发布被拒：errorCode=$errorCode")
                            if (cont.isActive) cont.resume(false)
                        }

                        override fun onServiceUnregistered(info: NsdServiceInfo) {
                            Log.d(TAG, "已撤销发布：${info.serviceName}")
                        }

                        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                            Log.e(TAG, "撤销发布失败：errorCode=$errorCode")
                        }
                    }
                    registrationListener = listener
                    nsd.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
                }
            }

            if (outcome == null) {
                Log.e(TAG, "发布 ${REGISTER_TIMEOUT_MS}ms 没有回调，按失败算")
                false
            } else {
                outcome
            }
        } catch (e: Exception) {
            Log.e(TAG, "发布抛出异常", e)
            false
        }
    }

    override fun stop() {
        registrationListener?.let { listener ->
            try {
                nsdManager?.unregisterService(listener)
            } catch (e: Exception) {
                Log.e(TAG, "撤销发布抛出异常", e)
            }
        }
        registrationListener = null
    }

    private companion object {
        const val TAG = "AndroidMdnsAdvertiser"
        const val SERVICE_TYPE = "_relay._udp."
        const val REGISTER_TIMEOUT_MS = 5_000L
    }
}
