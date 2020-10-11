package wstunnel

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import kotlinx.cli.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import wstunnel.module.ssh.SshServer
import java.util.*

private class ServerCommand : Subcommand("server", "Server related options") {
    private val port by option(ArgType.Int, "port", "p").default(80)
    private val host by option(ArgType.String, "host", "a").default("0.0.0.0")

    override fun execute() {
        val server = Server(host, port)
        server.start()

        Runtime.getRuntime().addShutdownHook(Thread {
            server.stop()
        })

        Thread.currentThread().join()
    }
}

private class ClientCommand : Subcommand("client", "Client related options") {
    private val server by option(ArgType.String, "serverUrl", "S", "Server URL in format ws|wss://host[:port]")
    private val listen by option(ArgType.String, "listen", "l", "Listen port[|host[|id]]").multiple()
    private val forward by option(ArgType.String, "forward", "f", "Forward port[|host[|id]]").multiple()
    private val listenSshd by option(ArgType.String, "listen-sshd", "lsshd", "Listen sshd [port[|host[|id]]]")

    override fun execute() {
        runBlocking(Dispatchers.Default) {
            try {
                coroutineScope {
                    val serverUrl = server
                        ?: System.getenv("WSTUNNEL_SERVER")
                        ?: throw RuntimeException("Server is not defined")

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
                            val sshd = SshServer()
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
                val params = command.split("|")

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

fun main(args: Array<String>) {
    try {
        val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext

        val rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
        val curPackageLogger = loggerContext.getLogger("wstunnel")

        rootLogger.level = Level.OFF
        curPackageLogger.level = Level.OFF

        val parser = ArgParser("ws-tunnel")
        parser.subcommands(ServerCommand(), ClientCommand())
        parser.parse(args)
    } catch (e: Throwable) {
        System.err.println(e.message)
        kotlin.system.exitProcess(1)
    }
}
