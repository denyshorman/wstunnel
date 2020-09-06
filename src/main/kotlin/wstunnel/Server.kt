package wstunnel

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import kotlin.collections.HashMap
import kotlin.streams.toList
import kotlin.time.minutes
import kotlin.time.seconds
import kotlin.time.toJavaDuration

class Server(
    host: String = "0.0.0.0",
    port: Int = 80,
) {
    private val logger = KotlinLogging.logger {}

    private val mutex = Mutex()
    private val listen =
        HashMap<String, LinkedList<Pair<DefaultWebSocketServerSession, CompletableDeferred<DefaultWebSocketServerSession>>>>()
    private val forward =
        HashMap<String, LinkedList<Pair<DefaultWebSocketServerSession, CompletableDeferred<DefaultWebSocketServerSession>>>>()

    private val server = embeddedServer(CIO, port, host) {
        install(WebSockets) {
            pingPeriod = 10.seconds.toJavaDuration()
            timeout = 5.seconds.toJavaDuration()
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }

        routing {
            val currDir = FileSystems.getDefault().getPath("").toAbsolutePath()
            val currDirFiles = Files.walk(currDir, 1).filter { Files.isRegularFile(it) }.toList()
            val currProgramPath = if (currDirFiles.size == 1) {
                currDirFiles.first()
            } else {
                val currProgramPathStr = System.getenv("WSTUNNEL_BINARY_PATH")
                if (currProgramPathStr == null) null else Paths.get(currProgramPathStr)
            }

            if (currProgramPath == null) {
                get("download") {
                    call.response.status(HttpStatusCode.NotFound)
                    call.respondText("Can't find wstunnel binary. Please define WSTUNNEL_BINARY_PATH environment variable.")
                }
            } else {
                get("download") {
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment.withParameter(
                            ContentDisposition.Parameters.FileName,
                            currProgramPath.fileName.toString()
                        ).toString()
                    )

                    call.respondFile(currProgramPath.toFile())
                }
            }

            webSocket("/") {
                val wsId = UUID.randomUUID()

                try {
                    coroutineScope {
                        if (logger.isDebugEnabled) logger.debug("Client $wsId connected")

                        val msg = call.request.header("X-TUN-CONF").deserializeProtocol()

                        if (msg !is ListenConfig) {
                            val closeReason = CloseReason(
                                CloseReason.Codes.PROTOCOL_ERROR,
                                "Initial configuration is expected"
                            )

                            throw ConnectionClosedException(closeReason)
                        }

                        if (logger.isDebugEnabled) logger.debug("Client $wsId wants to ${msg.connType} on ${msg.id}")

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

                            if (logger.isDebugEnabled) {
                                logger.debug("Client $wsId awaiting for another socket...")
                            }

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
                            if (awaitingConnList0.size == 0) {
                                awaitConn0.remove(msg.id)
                            }

                            mutex.unlock()

                            socketPair.second.complete(this@webSocket)
                            socketPair.first
                        }

                        if (logger.isDebugEnabled) {
                            logger.debug("Client $wsId received a connection. Start forwarding data...")
                        }

                        this@webSocket.send(Frame.Text(ConnectionEstablished.serialize()))

                        launch(start = CoroutineStart.UNDISPATCHED) {
                            val closeReason = otherSocket.closeReason.await()
                            throw ConnectionClosedException(closeReason)
                        }

                        forwardData(this@webSocket, otherSocket)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ConnectionClosedException) {
                    if (logger.isDebugEnabled) {
                        logger.debug("Closing client $wsId connection: ${e.reason}.")
                    }

                    if (e.reason == null) close() else close(e.reason)
                } catch (e: Throwable) {
                    if (logger.isDebugEnabled) {
                        logger.debug("Closing client $wsId connection: ${e.message}.")
                    }
                } finally {
                    if (logger.isDebugEnabled) {
                        logger.debug("Client $wsId connection has been closed.")
                    }
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
            gracePeriodMillis = 2.seconds.toLongMilliseconds(),
            timeoutMillis = 5.minutes.toLongMilliseconds(),
        )
    }

    private data class ConnectionClosedException(val reason: CloseReason? = null) : Throwable(null, null, false, false)
}
