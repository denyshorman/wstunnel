package wstunnel

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class Server(
    host: String = "0.0.0.0",
    port: Int = 8080,
) {
    private val logger = KotlinLogging.logger {}

    private val mutex = Mutex()
    private val listen =
        HashMap<String, LinkedList<Pair<DefaultWebSocketServerSession, CompletableDeferred<DefaultWebSocketServerSession>>>>()
    private val forward =
        HashMap<String, LinkedList<Pair<DefaultWebSocketServerSession, CompletableDeferred<DefaultWebSocketServerSession>>>>()

    private val server = embeddedServer(CIO, port, host) {
        install(WebSockets) {
            pingPeriod = 10.seconds
            timeout = 5.seconds
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }

        routing {
            val compiledBinaryPath = compiledBinaryPath()

            if (compiledBinaryPath == null) {
                get("download") {
                    call.response.status(HttpStatusCode.NotFound)
                }
            } else {
                get("download") {
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment.withParameter(
                            ContentDisposition.Parameters.FileName,
                            compiledBinaryPath.fileName.toString(),
                        ).toString(),
                    )

                    call.respondFile(compiledBinaryPath.toFile())
                }
            }

            webSocket("/") {
                val wsId = UUID.randomUUID()

                try {
                    coroutineScope {
                        logger.debug { "Client $wsId connected" }

                        val msg = call.request.header("X-TUN-CONF")?.decode()

                        if (msg !is ListenConfig) {
                            val closeReason = CloseReason(
                                CloseReason.Codes.PROTOCOL_ERROR,
                                "Initial configuration is expected"
                            )

                            throw ConnectionClosedException(closeReason)
                        }

                        logger.debug { "Client $wsId wants to ${msg.connType} on ${msg.id}" }

                        val awaitConn0: HashMap<String, LinkedList<Pair<DefaultWebSocketServerSession, CompletableDeferred<DefaultWebSocketServerSession>>>>
                        val awaitConn1: HashMap<String, LinkedList<Pair<DefaultWebSocketServerSession, CompletableDeferred<DefaultWebSocketServerSession>>>>

                        when (msg.connType) {
                            SocketRole.Listen -> {
                                awaitConn0 = forward
                                awaitConn1 = listen
                            }
                            SocketRole.Forward -> {
                                awaitConn0 = listen
                                awaitConn1 = forward
                            }
                        }

                        mutex.lock()

                        val awaitingConnList0 = awaitConn0[msg.id]
                        val socketPair = awaitingConnList0?.removeFirst()

                        val otherSocket = if (socketPair == null) {
                            var awaitConnList1 = awaitConn1[msg.id]

                            if (awaitConnList1 == null) {
                                awaitConnList1 = LinkedList()
                                awaitConn1[msg.id] = awaitConnList1
                            }

                            val otherWebSocketDeferred = CompletableDeferred<DefaultWebSocketServerSession>()
                            awaitConnList1.add(Pair(this@webSocket, otherWebSocketDeferred))

                            mutex.unlock()

                            logger.debug { "Client $wsId awaiting for another socket..." }

                            try {
                                select {
                                    otherWebSocketDeferred.onAwait { it }

                                    this@webSocket.incoming.onReceive { frame ->
                                        when (frame) {
                                            is Frame.Close -> throw ConnectionClosedException(frame.readReason())
                                            else -> throw ConnectionClosedException()
                                        }
                                    }
                                }
                            } catch (e: Throwable) {
                                withContext(NonCancellable) {
                                    mutex.withLock {
                                        awaitConn1.remove(msg.id)
                                    }
                                }

                                throw e
                            }
                        } else {
                            if (awaitingConnList0.isEmpty()) {
                                awaitConn0.remove(msg.id)
                            }

                            mutex.unlock()

                            socketPair.second.complete(this@webSocket)
                            socketPair.first
                        }

                        logger.debug { "Client $wsId received a connection. Start forwarding data..." }

                        this@webSocket.send(Frame.Text(ConnectionEstablished.encode()))

                        launch(start = CoroutineStart.UNDISPATCHED) {
                            val closeReason = otherSocket.closeReason.await()
                            throw ConnectionClosedException(closeReason)
                        }

                        forwardData(this@webSocket, otherSocket)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ConnectionClosedException) {
                    logger.debug { "Closing client $wsId connection: ${e.reason}." }

                    if (e.reason == null) close() else close(e.reason)
                } catch (e: Throwable) {
                    logger.debug { "Closing client $wsId connection: ${e.message}." }
                } finally {
                    logger.debug { "Client $wsId connection has been closed." }
                }
            }
        }
    }

    private suspend fun forwardData(
        incomingSocket: DefaultWebSocketServerSession,
        outgoingSocket: DefaultWebSocketServerSession,
    ) {
        while (true) {
            when (val frame = incomingSocket.incoming.receive()) {
                is Frame.Binary -> outgoingSocket.outgoing.send(frame)
                is Frame.Close -> throw ConnectionClosedException(frame.readReason())
                else -> {
                    // Ignore other frames
                }
            }
        }
    }

    fun start() {
        server.start(wait = false)
    }

    fun stop() {
        server.stop(
            gracePeriodMillis = 2.seconds.inWholeMilliseconds,
            timeoutMillis = 5.minutes.inWholeMilliseconds,
        )
    }

    private data class ConnectionClosedException(val reason: CloseReason? = null) : Throwable(null, null, false, false)
}
