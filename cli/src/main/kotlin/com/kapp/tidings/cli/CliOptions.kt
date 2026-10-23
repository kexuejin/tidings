// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.cli

/**
 * 命令行参数。手写而不是引一个解析库：这里只有八个开关，而引一个库会让
 * 「这个模块到底依赖了什么」这条验收问题多出一个答案。
 */
internal class CliOptions(
    val help: Boolean,
    val link: Boolean,
    val listen: Boolean,
    val peer: String?,
    val sendTo: String?,
    val text: String?,
    val inbox: Boolean,
    val peers: Boolean,
    val since: Long,
    val limit: Int,
    val data: String,
    val udpPort: Int?,
    val tcpPort: Int?,
) {
    companion object {
        val USAGE: String = """
            relay-cli - a Tidings client with no UI.

            It exists to prove the domain layer is UI-free: this program compiles against
            :app only, so if it builds and runs, no Compose type is reachable from here.

              --listen [--ttl SEC]      stay alive so another node can reach it
              --peer HOST:PORT          register a manual node (repeatable)
              --link                    print link readings (phase, addrs, pin, peers)
              --send <peerId> <text>    send one message and wait for the receipt
              --inbox [--since MS] [--limit N]   print messages
              --peers                   list known peers
              --data <dir>              data directory (default: ~/.relay-cli)
              --udp-port N              UDP port (default: the product's default)
              --tcp-port N              TCP port (default: udp + 1)
              -h, --help
        """.trimIndent() + "\n"

        fun parse(args: Array<String>): CliOptions {
            var help = false
            var link = false
            var listen = false
            var ttlMs = 60_000L
            val peersTo = mutableListOf<String>()
            var sendTo: String? = null
            var text: String? = null
            var inbox = false
            var showPeers = false
            var since = 0L
            var limit = 50
            var data = System.getProperty("user.home") + "/.relay-cli"
            var udpPort: Int? = null
            var tcpPort: Int? = null
            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "-h", "--help" -> help = true
                    "--link" -> link = true
                    "--listen" -> listen = true
                    "--ttl" -> { ttlMs = (args.getOrNull(i + 1)?.toLongOrNull() ?: 60L) * 1000L; i += 1 }
                    "--inbox" -> inbox = true
                    "--peers" -> showPeers = true
                    "--peer" -> { args.getOrNull(i + 1)?.let { peersTo += it }; i += 1 }
                    "--send" -> {
                        sendTo = args.getOrNull(i + 1)
                        text = args.getOrNull(i + 2)
                        i += 2
                    }
                    "--since" -> { since = args.getOrNull(i + 1)?.toLongOrNull() ?: 0L; i += 1 }
                    "--limit" -> { limit = args.getOrNull(i + 1)?.toIntOrNull() ?: 50; i += 1 }
                    "--data" -> { data = args.getOrNull(i + 1) ?: data; i += 1 }
                    "--udp-port" -> { udpPort = args.getOrNull(i + 1)?.toIntOrNull(); i += 1 }
                    "--tcp-port" -> { tcpPort = args.getOrNull(i + 1)?.toIntOrNull(); i += 1 }
                }
                i += 1
            }
            return CliOptions(
                help = help,
                link = link,
                listen = listen,
                peer = null,
                sendTo = sendTo,
                text = text,
                inbox = inbox,
                peers = showPeers,
                since = since,
                limit = limit,
                data = data,
                udpPort = udpPort,
                tcpPort = tcpPort,
            ).also { it.peersToAdd = peersTo.toList(); it.ttlMs = ttlMs }
        }
    }

    /** 要登记的邻居（`host:port`）。解析推迟到 main 里，因为要报错给用户看。 */
    var peersToAdd: List<String> = emptyList()
        internal set

    /** `--listen` 的存活时长；默认 60 秒，够一次手工对拍。 */
    var ttlMs: Long = 60_000L
        internal set
}
