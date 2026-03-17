package com.spyglass.connect.server

import com.spyglass.connect.Log
import io.ktor.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Track connected WebSocket clients and their encryption state.
 */
class SessionManager {

    data class ClientSession(
        val id: String,
        val session: WebSocketSession,
        val encryption: EncryptionManager,
        val deviceName: String = "Unknown",
        var isPaired: Boolean = false,
        val negotiatedCapabilities: Set<String> = emptySet(),
    )

    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, ClientSession>()

    /** Register a new client session. */
    suspend fun addSession(id: String, session: WebSocketSession, encryption: EncryptionManager): ClientSession {
        val clientSession = ClientSession(id = id, session = session, encryption = encryption)
        mutex.withLock {
            sessions[id] = clientSession
        }
        return clientSession
    }

    /** Remove a client session. */
    suspend fun removeSession(id: String) {
        mutex.withLock {
            sessions.remove(id)
        }
    }

    /** Get all active sessions. */
    suspend fun activeSessions(): List<ClientSession> {
        return mutex.withLock { sessions.values.toList() }
    }

    /** Get a specific session. */
    suspend fun getSession(id: String): ClientSession? {
        return mutex.withLock { sessions[id] }
    }

    /** Mark a session as paired with negotiated capabilities. */
    suspend fun markPaired(id: String, deviceName: String, negotiatedCapabilities: Set<String>) {
        mutex.withLock {
            sessions[id]?.let {
                sessions[id] = it.copy(
                    isPaired = true,
                    deviceName = deviceName,
                    negotiatedCapabilities = negotiatedCapabilities,
                )
            }
        }
    }

    /** Get count of connected clients. */
    suspend fun connectionCount(): Int = mutex.withLock { sessions.size }

    /** Forcefully close and remove a client session. */
    suspend fun disconnectClient(id: String) {
        val session = mutex.withLock { sessions.remove(id) }
        if (session != null) {
            try {
                session.session.close(CloseReason(CloseReason.Codes.NORMAL, "Disconnected by server"))
            } catch (_: Exception) { /* already closed */ }
            Log.i("Session", "Force-disconnected [$id] (${session.deviceName})")
        }
    }

    /** Send an encrypted message to all paired sessions that support the given capability. */
    suspend fun broadcast(message: String, requiredCapability: String? = null) {
        val active = activeSessions().filter {
            it.isPaired && (requiredCapability == null || it.negotiatedCapabilities.contains(requiredCapability))
        }
        for (client in active) {
            try {
                if (client.encryption.isReady) {
                    client.session.send(Frame.Text(client.encryption.encrypt(message)))
                } else {
                    client.session.send(Frame.Text(message))
                }
            } catch (e: Exception) {
                Log.w("Session", "Broadcast failed for [${client.id}]: ${e.message}")
                removeSession(client.id)
            }
        }
    }
}
