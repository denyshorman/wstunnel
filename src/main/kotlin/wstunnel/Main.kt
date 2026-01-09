package wstunnel

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import wstunnel.module.ssh.SshServer
import java.util.*

private class ServerCommand : CliktCommand(name = "server") {
    private val port by option("-p", "--port", envvar = "WSTUN_SERVER_PORT", help = "Port to listen on").int().default(8080)
    private val host by option("-a", "--host", envvar = "WSTUN_SERVER_HOST", help = "Host address to bind to").default("0.0.0.0")

    override fun run() {
        val server = Server(host, port)
        server.start()

        Runtime.getRuntime().addShutdownHook(Thread {
            server.stop()
        })

        Thread.currentThread().join()
    }
}

private class ClientCommand : CliktCommand(name = "client") {
    private val serverUrl by option("-S", "--serverUrl", envvar = "WSTUN_SERVER_URL", help = "Server URL in format ws|wss://host[:port]").required()
    private val listen by option("-l", "--listen", help = "Listen port[;host[;id]]").multiple()
    private val forward by option("-f", "--forward", help = "Forward port[;host[;id]]").multiple()
    private val listenSshd by option("--lsshd", "--listen-sshd", envvar = "WSTUN_LISTEN_SSHD", help = "Listen sshd [port[;host[;id]]]")
    private val sshdLogin by option("--sshd-login", envvar = "WSTUN_SSHD_LOGIN", help = "SSH server login username")
    private val sshdPassword by option("--sshd-password", envvar = "WSTUN_SSHD_PASSWORD", help = "SSH server login password")

    override fun run() {
        runBlocking(Dispatchers.Default) {
            try {
                coroutineScope {
                    val listenConf = listen.map { ListenForwardConfig.deserialize(SocketRole.Listen, it) }
                    val forwardConf = forward.map { ListenForwardConfig.deserialize(SocketRole.Forward, it) }

                    val configs = listenConf.asSequence() + forwardConf.asSequence()

                    configs.forEach { config ->
                        launch {
                            println("${config.role} ${config.host} ${config.port} ${config.id}")
                            Client(serverUrl, config.role, config.id, config.port, config.host).start()
                        }
                    }

                    if (listenSshd != null) {
                        val config = ListenForwardConfig.deserialize(SocketRole.Listen, listenSshd!!)

                        launch {
                            val sshd = SshServer(sshdLogin, sshdPassword)
                            sshd.start(config.host, config.port)

                            launch {
                                try {
                                    delay(Long.MAX_VALUE)
                                } finally {
                                    sshd.stop()
                                }
                            }

                            println("ListenSSHD ${config.host} ${sshd.port} ${config.id}")
                            Client(serverUrl, config.role, config.id, sshd.port, config.host).start()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                System.err.println(e.message)
                kotlin.system.exitProcess(1)
            }
        }
    }

    private data class ListenForwardConfig(
        val role: SocketRole,
        val host: String,
        val port: Int,
        val id: String,
    ) {
        companion object {
            fun deserialize(
                role: SocketRole,
                command: String,
                defaultPort: Int? = null,
                defaultHost: String = "127.0.0.1",
                defaultId: String = UUID.randomUUID().toString(),
            ): ListenForwardConfig {
                val params = command.split(";")

                val port = params.getOrNull(0)?.toIntOrNull()
                    ?: defaultPort
                    ?: throw RuntimeException("Can't deserialize $command: valid port is required")

                val host = params.getOrNull(1) ?: defaultHost

                val id = params.getOrNull(2) ?: defaultId

                return ListenForwardConfig(role, host, port, id)
            }
        }
    }
}

private class WsTunnel : CliktCommand(name = "wstunnel") {
    override fun run() = Unit
}

fun main(args: Array<String>) {
    try {
        val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext

        val rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
        val curPackageLogger = loggerContext.getLogger("wstunnel")

        rootLogger.level = Level.OFF
        curPackageLogger.level = Level.OFF

        WsTunnel()
            .subcommands(ServerCommand(), ClientCommand())
            .main(args)
    } catch (e: Throwable) {
        System.err.println(e.message)
        kotlin.system.exitProcess(1)
    }
}
