package wstunnel

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.*
import io.ktor.client.features.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.*
import mu.KotlinLogging
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.time.seconds

class Client(private val config: Config) {
    private val logger = KotlinLogging.logger {}

    private val client = HttpClient(CIO) {
        install(WebSockets)

        engine {
            maxConnectionsCount = Int.MAX_VALUE

            endpoint {
                maxConnectionsPerRoute = Int.MAX_VALUE
                pipelineMaxSize = Int.MAX_VALUE
                connectTimeout = 30.seconds.toLongMilliseconds()
                keepAliveTime = 15.seconds.toLongMilliseconds()
            }
        }
    }

    private fun webSocketHttpRequest(clientType: Config.ClientType): HttpRequestBuilder.() -> Unit {
        return {
            val connectType = when (clientType) {
                is Config.ClientType.Listen -> ConnectConfig.Type.Listen
                is Config.ClientType.Forward -> ConnectConfig.Type.Forward
                is Config.ClientType.DynamicListen -> ConnectConfig.Type.DynamicListen
                is Config.ClientType.DynamicForward -> ConnectConfig.Type.DynamicForward(
                    clientType.remoteHost,
                    clientType.remotePort
                )
            }

            val connectConfig = ConnectConfig(config.id, connectType)

            url.takeFrom(Url(config.serverUrl))
            header("X-TUN-CONF", connectConfig.encode())
        }
    }

    private suspend fun DefaultClientWebSocketSession.awaitConnectionEstablished(): ConnectionEstablished {
        val initialFrame = incoming.receive()

        if (initialFrame !is Frame.Text) {

            throw ConnectionClosedException()
        }

        val msg = initialFrame.readText().decode()

        if (msg !is ConnectionEstablished) {

            throw ConnectionClosedException()
        }

        return msg
    }

    private suspend fun forwardWebSocketToSocket(socket: Socket, webSocket: DefaultWebSocketSession) {
        val output = socket.openWriteChannel(autoFlush = true)

        while (true) {
            when (val frame = webSocket.incoming.receive()) {
                is Frame.Binary -> {
                    val bytes = frame.readBytes()
                    output.writeFully(bytes, 0, bytes.size)
                }
                is Frame.Close -> throw ConnectionClosedException(frame.readReason())
                else -> {
                    // ignore other frames
                }
            }
        }
    }

    private suspend fun forwardSocketToWebSocket(socket: Socket, webSocket: DefaultWebSocketSession) {
        val input = socket.openReadChannel()
        val buffer = ByteArray(8192)

        while (true) {
            val readCount = input.readAvailable(buffer, 0, buffer.size)
            if (readCount == -1) throw ConnectionClosedException()
            webSocket.outgoing.send(Frame.Binary(true, ByteBuffer.wrap(buffer, 0, readCount)))
        }
    }

    private suspend fun listen() {
        config.clientType as Config.ClientType.Listen

        coroutineScope {
            while (isActive) {
                val triggerNewConnection = CompletableDeferred<Unit>()

                launch {
                    try {
                        client.webSocket(webSocketHttpRequest(config.clientType)) {
                            coroutineScope {
                                val connectionEstablished = awaitConnectionEstablished()

                                if (connectionEstablished.type !is ConnectionEstablished.Type.Forward) {
                                    val closeReason = CloseReason(
                                        CloseReason.Codes.CANNOT_ACCEPT,
                                        "Forward must be sent"
                                    )

                                    throw ConnectionClosedException(closeReason)
                                }

                                val socket = aSocket(ActorSelectorManager(Dispatchers.IO)).tcp()
                                    .connect(config.clientType.host, config.clientType.port)

                                triggerNewConnection.complete(Unit)

                                // Waiting for error/disconnection to close connection to listening socket
                                launch {
                                    try {
                                        delay(Long.MAX_VALUE)
                                    } finally {
                                        socket.dispose()
                                    }
                                }

                                launch { forwardWebSocketToSocket(socket, this@webSocket) }
                                launch { forwardSocketToWebSocket(socket, this@webSocket) }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ClientRequestException) {
                        throw e
                    } catch (e: NoTransformationFoundException) {
                        throw e
                    } catch (e: IllegalArgumentException) {
                        throw e
                    } catch (e: ConnectionClosedException) {
                        if (logger.isDebugEnabled) {
                            logger.debug("Closing client connection: ${e.reason ?: "Unknown reason"}.")
                        }
                    } catch (e: Throwable) {
                        if (logger.isDebugEnabled) {
                            logger.debug("Listen error: ${e.message}")
                        }
                    } finally {
                        triggerNewConnection.complete(Unit)
                    }
                }

                triggerNewConnection.await()
            }
        }
    }

    private suspend fun forward() {
        coroutineScope {
            config.clientType as Config.ClientType.Forward

            val forward = aSocket(ActorSelectorManager(Dispatchers.IO)).tcp()
                .bind(InetSocketAddress(config.clientType.host, config.clientType.port))

            while (isActive) {
                val socket = forward.accept()

                launch {
                    try {
                        client.webSocket(webSocketHttpRequest(config.clientType)) {
                            coroutineScope {
                                val connectionEstablished = awaitConnectionEstablished()

                                if (connectionEstablished.type !is ConnectionEstablished.Type.Listen) {
                                    val closeReason = CloseReason(
                                        CloseReason.Codes.CANNOT_ACCEPT,
                                        "Listen must be sent"
                                    )

                                    throw ConnectionClosedException(closeReason)
                                }

                                launch { forwardWebSocketToSocket(socket, this@webSocket) }
                                launch { forwardSocketToWebSocket(socket, this@webSocket) }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ClientRequestException) {
                        throw e
                    } catch (e: NoTransformationFoundException) {
                        throw e
                    } catch (e: IllegalArgumentException) {
                        throw e
                    } catch (e: ConnectionClosedException) {
                        if (logger.isDebugEnabled) {
                            logger.debug("Closing client connection: ${e.reason ?: "Unknown reason"}.")
                        }
                    } catch (e: Throwable) {
                        if (logger.isDebugEnabled) {
                            logger.debug("Forward error: ${e.message}")
                        }
                    } finally {
                        socket.dispose()
                    }
                }
            }
        }
    }

    private suspend fun dynamicListen() {
        config.clientType as Config.ClientType.DynamicListen

        coroutineScope {
            while (isActive) {
                val triggerNewConnection = CompletableDeferred<Unit>()

                launch {
                    try {
                        client.webSocket(webSocketHttpRequest(config.clientType)) {
                            coroutineScope {
                                val connectionEstablished = awaitConnectionEstablished()

                                if (connectionEstablished.type !is ConnectionEstablished.Type.DynamicForward) {
                                    val closeReason = CloseReason(
                                        CloseReason.Codes.CANNOT_ACCEPT,
                                        "Dynamic forward must be sent"
                                    )

                                    throw ConnectionClosedException(closeReason)
                                }

                                val socket = aSocket(ActorSelectorManager(Dispatchers.IO)).tcp()
                                    .connect(connectionEstablished.type.host, connectionEstablished.type.port)

                                triggerNewConnection.complete(Unit)

                                // Waiting for error/disconnection to close connection to listening socket
                                launch {
                                    try {
                                        delay(Long.MAX_VALUE)
                                    } finally {
                                        socket.dispose()
                                    }
                                }

                                launch { forwardWebSocketToSocket(socket, this@webSocket) }
                                launch { forwardSocketToWebSocket(socket, this@webSocket) }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ClientRequestException) {
                        throw e
                    } catch (e: NoTransformationFoundException) {
                        throw e
                    } catch (e: IllegalArgumentException) {
                        throw e
                    } catch (e: ConnectionClosedException) {
                        if (logger.isDebugEnabled) {
                            logger.debug("Closing client connection: ${e.reason ?: "Unknown reason"}.")
                        }
                    } catch (e: Throwable) {
                        if (logger.isDebugEnabled) {
                            logger.debug("Listen error: ${e.message}")
                        }
                    } finally {
                        triggerNewConnection.complete(Unit)
                    }
                }

                triggerNewConnection.await()
            }
        }
    }

    private suspend fun dynamicForward() {
        coroutineScope {
            config.clientType as Config.ClientType.DynamicForward

            val forward = aSocket(ActorSelectorManager(Dispatchers.IO)).tcp()
                .bind(InetSocketAddress(config.clientType.localHost, config.clientType.localPort))

            while (isActive) {
                val socket = forward.accept()

                launch {
                    try {
                        client.webSocket(webSocketHttpRequest(config.clientType)) {
                            coroutineScope {
                                val connectionEstablished = awaitConnectionEstablished()

                                if (connectionEstablished.type !is ConnectionEstablished.Type.DynamicListen) {
                                    val closeReason = CloseReason(
                                        CloseReason.Codes.CANNOT_ACCEPT,
                                        "Dynamic listen must be sent"
                                    )

                                    throw ConnectionClosedException(closeReason)
                                }

                                launch { forwardWebSocketToSocket(socket, this@webSocket) }
                                launch { forwardSocketToWebSocket(socket, this@webSocket) }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ClientRequestException) {
                        throw e
                    } catch (e: NoTransformationFoundException) {
                        throw e
                    } catch (e: IllegalArgumentException) {
                        throw e
                    } catch (e: ConnectionClosedException) {
                        if (logger.isDebugEnabled) {
                            logger.debug("Closing client connection: ${e.reason ?: "Unknown reason"}.")
                        }
                    } catch (e: Throwable) {
                        if (logger.isDebugEnabled) {
                            logger.debug("Forward error: ${e.message}")
                        }
                    } finally {
                        socket.dispose()
                    }
                }
            }
        }
    }

    suspend fun start() {
        when (config.clientType) {
            is Config.ClientType.Listen -> listen()
            is Config.ClientType.Forward -> forward()
            is Config.ClientType.DynamicListen -> dynamicListen()
            is Config.ClientType.DynamicForward -> dynamicForward()
        }
    }


    data class Config(
        val serverUrl: String,
        val id: String,
        val clientType: ClientType,
    ) {
        sealed class ClientType {
            data class Listen(
                val host: String,
                val port: Int,
            ) : ClientType()

            data class Forward(
                val host: String,
                val port: Int,
            ) : ClientType()

            object DynamicListen : ClientType()

            data class DynamicForward(
                val localHost: String,
                val localPort: Int,
                val remoteHost: String,
                val remotePort: Int,
            ) : ClientType()
        }
    }

    private data class ConnectionClosedException(val reason: CloseReason? = null) : Throwable(null, null, false, false)
}
