package com.spyglass.connect.server

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.spyglass.connect.minecraft.ItemSearchIndex
import com.spyglass.connect.model.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import com.spyglass.connect.BuildConfig
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
    @Volatile private var worldsProvider: () -> List<WorldInfo> = { emptyList() }
    private val handlerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val messageHandler = MessageHandler(
        worldsProvider = { worldsProvider() },
        searchIndex = searchIndex,
        scope = handlerScope,
        onWorldChanged = { changes ->
            invalidateCache(changes)
            notifyWorldChanged("", changes.toList())
        },
    )

    /** Set the worlds provider (called from Compose to wire in-memory state). */
    fun setWorldsProvider(provider: () -> List<WorldInfo>) {
        worldsProvider = provider
    }

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
        server?.stop(200, 500)
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

                        // Reject data requests from unpaired sessions
                        val currentSession = sessionManager.getSession(clientId)
                        if (currentSession?.isPaired != true) {
                            log("Rejecting ${message.type} from unpaired [$clientId]")
                            val error = SpyglassMessage(
                                type = MessageType.ERROR,
                                requestId = message.requestId,
                                payload = json.encodeToJsonElement(
                                    ErrorPayload.serializer(),
                                    ErrorPayload(ErrorCode.NOT_PAIRED, "Not paired"),
                                ),
                            )
                            val errorJson = json.encodeToString(SpyglassMessage.serializer(), error)
                            if (clientEncryption.isReady) {
                                session.send(Frame.Text(clientEncryption.encrypt(errorJson)))
                            } else {
                                session.send(Frame.Text(errorJson))
                            }
                            return@consumeEach
                        }

                        // Check capability for this request type
                        val requiredCapability = requestTypeToCapability(message.type)
                        if (requiredCapability != null && !currentSession.negotiatedCapabilities.contains(requiredCapability)) {
                            log("Capability '$requiredCapability' not negotiated for ${message.type} from [$clientId]")
                            val error = SpyglassMessage(
                                type = MessageType.ERROR,
                                requestId = message.requestId,
                                payload = json.encodeToJsonElement(
                                    ErrorPayload.serializer(),
                                    ErrorPayload(ErrorCode.CAPABILITY_UNSUPPORTED, "Capability not supported: $requiredCapability"),
                                ),
                            )
                            val errorJson = json.encodeToString(SpyglassMessage.serializer(), error)
                            session.send(Frame.Text(clientEncryption.encrypt(errorJson)))
                            return@consumeEach
                        }

                        // Handle normal messages — dispatch to IO to avoid blocking WebSocket event loop
                        log("← ${message.type} from [$clientId]")
                        val response = withContext(Dispatchers.IO) {
                            messageHandler.handle(message) { intermediate ->
                                val intermediateJson = json.encodeToString(SpyglassMessage.serializer(), intermediate)
                                if (clientEncryption.isReady) {
                                    session.send(Frame.Text(clientEncryption.encrypt(intermediateJson)))
                                } else {
                                    session.send(Frame.Text(intermediateJson))
                                }
                            }
                        }
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
                                ErrorPayload(ErrorCode.PARSE_ERROR, "Failed to process message: ${e.message}")
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
        log("Pair request from '${payload.deviceName}' [$clientId] (protocol v${payload.protocolVersion}, min v${payload.minCompatibleVersion})")

        // Protocol version check — reject incompatible clients with actionable message
        if (payload.protocolVersion < ProtocolInfo.MIN_COMPATIBLE_VERSION) {
            log("Rejecting [$clientId]: client protocol v${payload.protocolVersion} < min v${ProtocolInfo.MIN_COMPATIBLE_VERSION}")
            val reject = SpyglassMessage(
                type = MessageType.PAIR_ACCEPT,
                payload = json.encodeToJsonElement(
                    PairAcceptPayload.serializer(),
                    PairAcceptPayload(
                        deviceName = "Spyglass Connect",
                        accepted = false,
                        protocolVersion = ProtocolInfo.PROTOCOL_VERSION,
                        minCompatibleVersion = ProtocolInfo.MIN_COMPATIBLE_VERSION,
                        rejectionReason = "Spyglass Connect requires protocol version ${ProtocolInfo.MIN_COMPATIBLE_VERSION}+. Update your Spyglass app.",
                    ),
                ),
            )
            session.send(Frame.Text(json.encodeToString(SpyglassMessage.serializer(), reject)))
            return
        }
        if (ProtocolInfo.PROTOCOL_VERSION < payload.minCompatibleVersion) {
            log("Rejecting [$clientId]: our protocol v${ProtocolInfo.PROTOCOL_VERSION} < client min v${payload.minCompatibleVersion}")
            val reject = SpyglassMessage(
                type = MessageType.PAIR_ACCEPT,
                payload = json.encodeToJsonElement(
                    PairAcceptPayload.serializer(),
                    PairAcceptPayload(
                        deviceName = "Spyglass Connect",
                        accepted = false,
                        protocolVersion = ProtocolInfo.PROTOCOL_VERSION,
                        minCompatibleVersion = ProtocolInfo.MIN_COMPATIBLE_VERSION,
                        rejectionReason = "Your Spyglass app requires a newer desktop version. Update Spyglass Connect.",
                    ),
                ),
            )
            session.send(Frame.Text(json.encodeToString(SpyglassMessage.serializer(), reject)))
            return
        }

        // Derive shared encryption key from client's ECDH public key
        clientEncryption.deriveSharedKey(payload.pubkey)
        log("Encryption established [$clientId]")

        // Negotiate capabilities: intersection of both sides, or all if legacy v2 client
        val clientCapabilities = payload.capabilities.toSet()
        val negotiated = if (clientCapabilities.isEmpty()) {
            // Legacy v2 client — assume all supported
            Capability.ALL
        } else {
            clientCapabilities.intersect(Capability.ALL)
        }
        log("Negotiated capabilities [$clientId]: $negotiated")

        sessionManager.markPaired(clientId, payload.deviceName, negotiated)
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
                    protocolVersion = ProtocolInfo.PROTOCOL_VERSION,
                    minCompatibleVersion = ProtocolInfo.MIN_COMPATIBLE_VERSION,
                    appVersion = BuildConfig.VERSION_NAME,
                    platform = "desktop",
                    capabilities = Capability.ALL.toList(),
                ),
            ),
        )
        val acceptJson = json.encodeToString(SpyglassMessage.serializer(), accept)
        session.send(Frame.Text(acceptJson))

        // Send world list encrypted after pairing (gated behind pairing in v3)
        if (negotiated.contains(Capability.WORLD_LIST)) {
            val worldListMsg = messageHandler.worldListMessage()
            val worldListJson = json.encodeToString(SpyglassMessage.serializer(), worldListMsg)
            val worldCount = (worldListMsg.payload as? kotlinx.serialization.json.JsonObject)
                ?.get("worlds")?.let { (it as? kotlinx.serialization.json.JsonArray)?.size } ?: 0
            log("Sending $worldCount worlds to [$clientId] (encrypted)")
            session.send(Frame.Text(clientEncryption.encrypt(worldListJson)))
        }
    }

    /** Map request message types to the capability they require. */
    private fun requestTypeToCapability(type: String): String? = when (type) {
        MessageType.SELECT_WORLD -> Capability.WORLD_LIST
        MessageType.REQUEST_PLAYER_LIST -> Capability.PLAYER_DATA
        MessageType.REQUEST_PLAYER -> Capability.PLAYER_DATA
        MessageType.REQUEST_CHESTS -> Capability.CHEST_CONTENTS
        MessageType.REQUEST_STRUCTURES -> Capability.STRUCTURE_LOCATIONS
        MessageType.REQUEST_MAP -> Capability.MAP_RENDER
        MessageType.SEARCH_ITEMS -> Capability.SEARCH_ITEMS
        MessageType.REQUEST_STATS -> Capability.PLAYER_STATS
        MessageType.REQUEST_ADVANCEMENTS -> Capability.PLAYER_ADVANCEMENTS
        MessageType.REQUEST_PETS -> Capability.PETS_LIST
        MessageType.DEVICE_LOG -> Capability.DEVICE_LOG
        else -> null
    }

    /** Notify all connected clients of a world change + push player data if relevant. */
    suspend fun notifyWorldChanged(worldName: String, categories: List<String>) {
        // Push player data proactively when player/level files changed
        if (categories.contains("player") || categories.contains("level")) {
            pushPlayerData()
        }

        val payload = WorldChangedPayload(worldName, categories)
        val message = SpyglassMessage(
            type = MessageType.WORLD_CHANGED,
            payload = json.encodeToJsonElement(WorldChangedPayload.serializer(), payload),
        )
        val messageJson = json.encodeToString(SpyglassMessage.serializer(), message)
        sessionManager.broadcast(messageJson, requiredCapability = Capability.WORLD_CHANGED)
    }

    /** Invalidate cached data (when file watcher detects changes). */
    fun invalidateCache(categories: Set<String> = emptySet()) {
        messageHandler.invalidateCache(categories)
    }

    /** Push player data to all connected clients (server-initiated, no requestId). */
    private suspend fun pushPlayerData() {
        val message = messageHandler.buildPlayerDataPush() ?: return
        val messageJson = json.encodeToString(SpyglassMessage.serializer(), message)
        Log.i(TAG, "Pushing PLAYER_DATA to clients")
        sessionManager.broadcast(messageJson, requiredCapability = Capability.PLAYER_DATA)
    }

    /** Observable count of device log entries received (for UI notification). */
    val deviceLogCount: kotlinx.coroutines.flow.StateFlow<Int> = messageHandler.deviceLogCount
}
