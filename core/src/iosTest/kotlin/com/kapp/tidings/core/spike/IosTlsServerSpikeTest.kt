// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.spike

import platform.Network.nw_listener_cancel
import platform.Network.nw_listener_create
import platform.Network.nw_listener_get_port
import platform.Network.nw_listener_set_new_connection_handler
import platform.Network.nw_listener_set_queue
import platform.Network.nw_listener_set_state_changed_handler
import platform.Network.nw_listener_start
import platform.Network.nw_listener_state_ready
import platform.Network.NW_PARAMETERS_DEFAULT_CONFIGURATION
import platform.Network.nw_parameters_create_secure_tcp
import platform.Network.nw_tls_create_options
import platform.Security.sec_protocol_options_set_peer_authentication_required
import platform.Security.sec_protocol_options_set_verify_block
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_get_global_queue
import platform.posix.usleep
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IosTlsServerSpikeTest {

    @Test
    fun iosCanBuildTlsProtocolOptions() {
        val tls = nw_tls_create_options()
        assertNotNull(tls, "nw_tls_create_options 必须可用")
        sec_protocol_options_set_peer_authentication_required(tls, true)
    }

    @Test
    fun iosCanCreateAndStartATlsListener() {
        val queue = dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)
        var ready = false
        var boundPort: UShort = 0u

        val parameters = nw_parameters_create_secure_tcp(
            { options ->
                if (options != null) {
                    sec_protocol_options_set_peer_authentication_required(options, true)
                    sec_protocol_options_set_verify_block(options, { _, _, complete -> complete?.invoke(true) }, queue)
                }
            },
            NW_PARAMETERS_DEFAULT_CONFIGURATION,
        )
        assertNotNull(parameters, "带 TLS 配置块的 secure_tcp 参数必须能创建")

        val listener = nw_listener_create(parameters)
        assertNotNull(listener, "NWListener 必须能创建")
        nw_listener_set_queue(listener, queue)
        nw_listener_set_state_changed_handler(listener) { state, _ ->
            if (state == nw_listener_state_ready) {
                ready = true
                boundPort = nw_listener_get_port(listener)
            }
        }
        nw_listener_set_new_connection_handler(listener) { _ -> }
        nw_listener_start(listener)

        var waited = 0
        while (!ready && waited < 5_000) {
            usleep(50_000u)
            waited += 50
        }
        nw_listener_cancel(listener)

        assertTrue(ready, "TLS 监听器必须在 5s 内到达 ready（实际 ${waited}ms）")
        assertTrue(boundPort > 0u, "监听器必须真的绑到一个端口，实际 $boundPort")
    }
}
