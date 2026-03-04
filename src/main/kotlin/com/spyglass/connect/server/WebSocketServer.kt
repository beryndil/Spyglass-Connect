package com.spyglass.connect.server

import androidx.compose.runtime.mutableStateOf
import com.spyglass.connect.minecraft.ItemSearchIndex
import com.spyglass.connect.minecraft.SaveDetector
import com.spyglass.connect.model.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.json.Json
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Ktor-based WebSocket server for Spyglass Connect.
 * Listens on port 29170, handles pairing and data requests.
 */
class WebSocketServer {

    companion object {
        const val DEFAULT_PORT = 29170
        private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")
        private fun log(msg: String) = println("[${LocalTime.now().format(timeFmt)}] $msg")
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val sessionManager = SessionManager()
    val encryption = EncryptionManager()
    private val searchIndex = ItemSearchIndex()
    private val messageHandler = MessageHandler(
        worldsProvider = { SaveDetector.detectWorlds() },
        searchIndex = searchIndex,
    )

    private var server: EmbeddedServer<*, *>? = null

    /** Observable server state. */
    val state = mutableStateOf(ServerState.STOPPED)

    enum class ServerState { STOPPED, STARTING, RUNNING, ERROR }

    /** Start the WebSocket server. */
    fun start(port: Int = DEFAULT_PORT) {
        state.value = ServerState.STARTING
        try {
            server = embeddedServer(Netty, port = port) {
                install(WebSockets) {
                    pingPeriod = 15.seconds
                    timeout = 30.seconds
                    maxFrameSize = Long.MAX_VALUE
                    masking = false
                }
                routing {
                    webSocket("/ws") {
                        handleClientConnection(this)
                    }
                }
            }.also {
                it.start(wait = false)
            }
            log("Server started on port $port")
            state.value = ServerState.RUNNING
        } catch (e: Exception) {
            log("Server start FAILED: ${e.message}")
            state.value = ServerState.ERROR
        }
    }

    /** Stop the server. */
    fun stop() {
        server?.stop(1000, 2000)
        server = null
        state.value = ServerState.STOPPED
    }

    /** Handle a new WebSocket client connection. */
    private suspend fun handleClientConnection(session: DefaultWebSocketServerSession) {
        val clientId = UUID.randomUUID().toString().take(8)
        // Use the server's encryption (whose public key is in the QR code) so ECDH keys match
        val clientEncryption = encryption
        val clientSession = sessionManager.addSession(clientId, session, clientEncryption)
        log("Client connected [$clientId]")

        try {
            // Send world list on connect
            val worldListJson = json.encodeToString(SpyglassMessage.serializer(), messageHandler.worldListMessage())
            session.send(Frame.Text(worldListJson))

            session.incoming.consumeEach { frame ->
                if (frame is Frame.Text) {
                    val rawText = frame.readText()

                    // Try to decrypt if encryption is established
                    val messageText = if (clientEncryption.isReady) {
                        try {
                            clientEncryption.decrypt(rawText)
                        } catch (_: Exception) {
                            rawText // Fall back to plaintext during pairing
                        }
                    } else {
                        rawText
                    }

                    try {
                        val message = json.decodeFromString(SpyglassMessage.serializer(), messageText)

                        // Handle pairing messages specially
                        if (message.type == MessageType.PAIR_REQUEST) {
                            handlePairRequest(clientId, clientEncryption, message, session)
                            return@consumeEach
                        }

                        // Handle normal messages
                        val response = messageHandler.handle(message)
                        if (response != null) {
                            val responseJson = json.encodeToString(SpyglassMessage.serializer(), response)
                            if (clientEncryption.isReady) {
                                session.send(Frame.Text(clientEncryption.encrypt(responseJson)))
                            } else {
                                session.send(Frame.Text(responseJson))
                            }
                        }
                    } catch (e: Exception) {
                        val error = SpyglassMessage(
                            type = MessageType.ERROR,
                            payload = json.encodeToJsonElement(
                                ErrorPayload.serializer(),
                                ErrorPayload("parse_error", "Failed to process message: ${e.message}")
                            ),
                        )
                        session.send(Frame.Text(json.encodeToString(SpyglassMessage.serializer(), error)))
                    }
                }
            }
        } catch (e: Exception) {
            log("Client [$clientId] error: ${e.message}")
        } finally {
            log("Client disconnected [$clientId]")
            sessionManager.removeSession(clientId)
        }
    }

    /** Handle a pairing request from a phone. */
    private suspend fun handlePairRequest(
        clientId: String,
        clientEncryption: EncryptionManager,
        message: SpyglassMessage,
        session: WebSocketSession,
    ) {
        val payload = json.decodeFromJsonElement(PairRequestPayload.serializer(), message.payload)
        log("Pair request from '${payload.deviceName}' [$clientId]")

        // Derive shared key from phone's public key
        clientEncryption.deriveSharedKey(payload.pubkey)
        sessionManager.markPaired(clientId, payload.deviceName)
        log("Paired with '${payload.deviceName}' [$clientId]")

        // Send acceptance as plaintext (pairing handshake completes before encryption begins)
        val accept = SpyglassMessage(
            type = MessageType.PAIR_ACCEPT,
            payload = json.encodeToJsonElement(
                PairAcceptPayload.serializer(),
                PairAcceptPayload(deviceName = "Spyglass Connect", accepted = true),
            ),
        )
        val acceptJson = json.encodeToString(SpyglassMessage.serializer(), accept)
        session.send(Frame.Text(acceptJson))
    }

    /** Notify all connected clients of a world change. */
    suspend fun notifyWorldChanged(worldName: String, categories: List<String>) {
        val payload = WorldChangedPayload(worldName, categories)
        val message = SpyglassMessage(
            type = MessageType.WORLD_CHANGED,
            payload = json.encodeToJsonElement(WorldChangedPayload.serializer(), payload),
        )
        val messageJson = json.encodeToString(SpyglassMessage.serializer(), message)
        sessionManager.broadcast(messageJson)
    }

    /** Invalidate cached data (when file watcher detects changes). */
    fun invalidateCache() {
        messageHandler.invalidateCache()
    }
}
