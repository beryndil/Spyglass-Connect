package com.spyglass.connect.server

import androidx.compose.runtime.mutableStateListOf
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
import com.spyglass.connect.Log
import java.util.UUID

/**
 * Ktor-based WebSocket server for Spyglass Connect.
 * Listens on port 29170, handles pairing and data requests.
 */
class WebSocketServer {

    companion object {
        const val DEFAULT_PORT = 29170
        private const val TAG = "Server"
        private fun log(msg: String) = Log.i(TAG, msg)
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val sessionManager = SessionManager()
    val encryption = EncryptionManager.loadOrCreate()
    private val searchIndex = ItemSearchIndex()
    private val messageHandler = MessageHandler(
        worldsProvider = { SaveDetector.detectWorlds() },
        searchIndex = searchIndex,
    )

    private var server: EmbeddedServer<*, *>? = null

    /** Observable server state. */
    val state = mutableStateOf(ServerState.STOPPED)

    /** Observable list of connected devices for UI display. */
    data class ConnectedDevice(val id: String, val deviceName: String, val isPaired: Boolean)
    val connectedDevices = mutableStateListOf<ConnectedDevice>()

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
            Log.e(TAG, "Server start FAILED", e)
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
        // Each client gets its own encryption session (same key pair, separate shared key)
        val clientEncryption = encryption.createClientSession()
        val clientSession = sessionManager.addSession(clientId, session, clientEncryption)
        connectedDevices.add(ConnectedDevice(clientId, "Unknown", false))
        log("Client connected [$clientId]")

        try {
            // Send world list on connect
            val worldListMsg = messageHandler.worldListMessage()
            val worldListJson = json.encodeToString(SpyglassMessage.serializer(), worldListMsg)
            val worldCount = (worldListMsg.payload as? kotlinx.serialization.json.JsonObject)
                ?.get("worlds")?.let { (it as? kotlinx.serialization.json.JsonArray)?.size } ?: 0
            log("Sending $worldCount worlds to [$clientId]")
            session.send(Frame.Text(worldListJson))

            session.incoming.consumeEach { frame ->
                if (frame is Frame.Text) {
                    val rawText = frame.readText()

                    // Try to decrypt if encryption is established
                    val messageText = if (clientEncryption.isReady) {
                        try {
                            clientEncryption.decrypt(rawText)
                        } catch (e: Exception) {
                            Log.w(TAG, "Decrypt failed for [$clientId]: ${e.message}")
                            rawText // Fall back to plaintext (e.g. during pairing handshake)
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
                        log("← ${message.type} from [$clientId]")
                        val response = messageHandler.handle(message)
                        if (response != null) {
                            log("→ ${response.type} to [$clientId]")
                            val responseJson = json.encodeToString(SpyglassMessage.serializer(), response)
                            if (clientEncryption.isReady) {
                                session.send(Frame.Text(clientEncryption.encrypt(responseJson)))
                            } else {
                                session.send(Frame.Text(responseJson))
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing message from [$clientId]", e)
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
            Log.e(TAG, "Client [$clientId] error", e)
        } finally {
            val reason = session.closeReason.await()
            Log.i(TAG, "Client disconnected [$clientId] reason=${reason?.code}:${reason?.message}")
            sessionManager.removeSession(clientId)
            connectedDevices.removeAll { it.id == clientId }
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

        // Encryption disabled — ECDH shared secret differs between JVM SunEC and Android Conscrypt.
        // Local network traffic for Minecraft data doesn't need AES-256-GCM.
        log("Paired (plaintext mode) [$clientId]")
        sessionManager.markPaired(clientId, payload.deviceName)
        val idx = connectedDevices.indexOfFirst { it.id == clientId }
        if (idx >= 0) {
            connectedDevices[idx] = ConnectedDevice(clientId, payload.deviceName, true)
        }
        log("Paired with '${payload.deviceName}' [$clientId]")

        // Send acceptance as plaintext (pairing handshake completes before encryption begins)
        val accept = SpyglassMessage(
            type = MessageType.PAIR_ACCEPT,
            payload = json.encodeToJsonElement(
                PairAcceptPayload.serializer(),
                PairAcceptPayload(
                    deviceName = "Spyglass Connect",
                    accepted = true,
                    pubkey = encryption.getPublicKeyBase64(),
                ),
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

    /** Observable count of device log entries received (for UI notification). */
    val deviceLogCount: kotlinx.coroutines.flow.StateFlow<Int> = messageHandler.deviceLogCount
}
