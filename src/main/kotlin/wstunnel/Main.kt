package wstunnel

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import kotlinx.cli.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
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
    private val serverUrl by argument(ArgType.String, "serverUrl", "Server URL in format ws|wss://host[:port]")
    private val listen by option(ArgType.String, "listen", "l", "Listen port[|host[|id]]").multiple()
    private val forward by option(ArgType.String, "forward", "f", "Forward port[|host[|id]]").multiple()

    override fun execute() {
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
            fun deserialize(role: SocketRole, command: String): ListenForwardConfig {
                val params = command.split("|")

                val port = params.getOrNull(0)?.toIntOrNull()
                    ?: throw RuntimeException("Can't deserialize $command: valid port is required")

                val host = params.getOrNull(1) ?: "127.0.0.1"

                val id = params.getOrNull(2) ?: UUID.randomUUID().toString()

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
