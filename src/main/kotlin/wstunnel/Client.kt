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

class Client(
    private val serverUrl: String,
    private val role: SocketRole,
    private val id: String,
    private val port: Int,
    private val host: String,
) {
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

    private val webSocketHttpRequest: HttpRequestBuilder.() -> Unit = {
        val listenConfig = ListenConfig(role, id).encode()
        url.takeFrom(Url(serverUrl))
        header("X-TUN-CONF", listenConfig)
    }

    private suspend fun DefaultClientWebSocketSession.awaitConnectionEstablished() {
        val initialFrame = incoming.receive()

        val connectionEstablished = initialFrame is Frame.Text
                && initialFrame.readText().decode() is ConnectionEstablished

        if (!connectionEstablished) throw ConnectionClosedException
    }

    private suspend fun forwardWebSocketToSocket(socket: Socket, webSocket: DefaultWebSocketSession) {
        val output = socket.openWriteChannel(autoFlush = true)

        while (true) {
            when (val frame = webSocket.incoming.receive()) {
                is Frame.Binary -> {
                    val bytes = frame.readBytes()
                    output.writeFully(bytes, 0, bytes.size)
                }
                is Frame.Close -> throw ConnectionClosedException
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
            if (readCount == -1) throw ConnectionClosedException
            webSocket.outgoing.send(Frame.Binary(true, ByteBuffer.wrap(buffer, 0, readCount)))
        }
    }

    private suspend fun listen() {
        coroutineScope {
            while (isActive) {
                val triggerNewConnection = CompletableDeferred<Unit>()

                launch {
                    try {
                        client.webSocket(webSocketHttpRequest) {
                            coroutineScope {
                                awaitConnectionEstablished()

                                val socket = aSocket(ActorSelectorManager(Dispatchers.IO)).tcp().connect(host, port)

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
            val forward = aSocket(ActorSelectorManager(Dispatchers.IO)).tcp().bind(InetSocketAddress(host, port))

            while (isActive) {
                val socket = forward.accept()

                launch {
                    try {
                        client.webSocket(webSocketHttpRequest) {
                            coroutineScope {
                                awaitConnectionEstablished()

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
        when (role) {
            SocketRole.Listen -> listen()
            SocketRole.Forward -> forward()
        }
    }

    private object ConnectionClosedException : Throwable("", null, false, false)
}
