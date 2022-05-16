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
    private val server by option(ArgType.String, "serverUrl", "S", "Server URL ws[s]://host[:port]")
    private val listen by option(ArgType.String, "listen", "l", "Listen [host:]port[/id]").multiple()
    private val forward by option(ArgType.String, "forward", "f", "Forward [host:]port/id").multiple()
    private val dynamicListen by option(ArgType.String, "dynamic-listen", "ll", "Dynamic Listen [id]")
    private val dynamicForward by option(
        ArgType.String,
        "dynamic-forward",
        "ff",
        "Dynamic Forward [localHost:]localPort/[remoteHost:]remotePort/id"
    ).multiple()

    override fun execute(): Unit = runBlocking(Dispatchers.Default) {
        try {
            coroutineScope {
                val serverUrl = server
                    ?: System.getenv("WSTUNNEL_SERVER")
                    ?: throw RuntimeException("Server is not defined")

                val listenConf = listen.map { it.decodeListenConfig().toClientConfig(serverUrl) }
                val forwardConf = forward.map { it.decodeForwardConfig().toClientConfig(serverUrl) }
                val dynamicListenConf = dynamicListen?.decodeDynamicListenConfig()?.toClientConfig(serverUrl)
                val dynamicForwardConf =
                    dynamicForward.map { it.decodeDynamicForwardConfig().toClientConfig(serverUrl) }

                val configs = (listenConf.asSequence()
                        + forwardConf.asSequence()
                        + if (dynamicListenConf == null) emptySequence() else sequenceOf(dynamicListenConf)
                        + dynamicForwardConf.asSequence()
                        )

                configs.forEach { config ->
                    launch {
                        println(config)
                        Client(config).start()
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


    //region Config classes
    private data class ListenConfig(
        val host: String? = null,
        val port: Int,
        val id: String? = null,
    )

    private data class ForwardConfig(
        val host: String? = null,
        val port: Int,
        val id: String,
    )

    private data class DynamicListenConfig(
        val id: String,
    )

    private data class DynamicForwardConfig(
        val localHost: String? = null,
        val localPort: Int,
        val remoteHost: String? = null,
        val remotePort: Int,
        val id: String,
    )
    //endregion

    //region Config Converters
    private val defaultHost: String = "localhost"
    private fun defaultId(): String = UUID.randomUUID().toString()

    private fun ListenConfig.toClientConfig(serverUrl: String): Client.Config {
        return Client.Config(
            serverUrl,
            id ?: defaultId(),
            Client.Config.ClientType.Listen(
                host ?: defaultHost,
                port,
            ),
        )
    }

    private fun ForwardConfig.toClientConfig(serverUrl: String): Client.Config {
        return Client.Config(
            serverUrl,
            id,
            Client.Config.ClientType.Forward(
                host ?: defaultHost,
                port,
            ),
        )
    }

    private fun DynamicListenConfig.toClientConfig(serverUrl: String): Client.Config {
        return Client.Config(
            serverUrl,
            id,
            Client.Config.ClientType.DynamicListen,
        )
    }

    private fun DynamicForwardConfig.toClientConfig(serverUrl: String): Client.Config {
        return Client.Config(
            serverUrl,
            id,
            Client.Config.ClientType.DynamicForward(
                localHost ?: defaultHost,
                localPort,
                remoteHost ?: defaultHost,
                remotePort,
            ),

            )
    }
    //endregion

    //region Decoder Patterns
    private val ListenConfigPattern = """^(?:(.+?):)?(\d+)(?:/(.+?))?$""".toRegex()
    private val ForwardConfigPattern = """^(?:(.+?):)?(\d+)/(.+?)$""".toRegex()
    private val DynamicForwardConfigPattern = """^(?:(.+?):)?(\d+)/(?:(.+?):)?(\d+)/(.+?)$""".toRegex()
    //endregion

    //region Decoders
    private fun String.decodeHost(): String {
        // TODO: Add validation
        return this
    }

    private fun String.decodePort(): Int {
        // TODO: Add validation
        return this.toInt(radix = 10)
    }

    private fun String.decodeId(): String {
        // TODO: Add validation
        return this
    }

    private fun String.decodeListenConfig(): ListenConfig {
        val match = ListenConfigPattern.matchEntire(this)
            ?: throw RuntimeException("Can't parse $this")

        return ListenConfig(
            match.groups[1]?.value?.decodeHost(),
            match.groups[2]!!.value.decodePort(),
            match.groups[3]?.value?.decodeId(),
        )
    }

    private fun String.decodeForwardConfig(): ForwardConfig {
        val match = ForwardConfigPattern.matchEntire(this)
            ?: throw RuntimeException("Can't parse $this")

        return ForwardConfig(
            match.groups[1]?.value?.decodeHost(),
            match.groups[2]!!.value.decodePort(),
            match.groups[3]!!.value.decodeId(),
        )
    }

    private fun String.decodeDynamicListenConfig(): DynamicListenConfig {
        return DynamicListenConfig(
            this.decodeId(),
        )
    }

    private fun String.decodeDynamicForwardConfig(): DynamicForwardConfig {
        val match = DynamicForwardConfigPattern.matchEntire(this)
            ?: throw RuntimeException("Can't parse $this")

        return DynamicForwardConfig(
            match.groups[1]?.value?.decodeHost(),
            match.groups[2]!!.value.decodePort(),
            match.groups[3]?.value?.decodeHost(),
            match.groups[4]!!.value.decodePort(),
            match.groups[5]!!.value.decodeId(),
        )
    }
    //endregion
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
