package com.spyglass.connect

import androidx.compose.runtime.*
import androidx.compose.ui.window.application
import com.spyglass.connect.config.ConfigStore
import com.spyglass.connect.minecraft.SaveDetector
import com.spyglass.connect.model.WorldInfo
import com.spyglass.connect.pairing.LanHelper
import com.spyglass.connect.pairing.MdnsPublisher
import com.spyglass.connect.server.WebSocketServer
import com.spyglass.connect.ui.MainWindow
import com.spyglass.connect.ui.SystemTray
import com.spyglass.connect.watcher.WorldWatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import javax.swing.JOptionPane

private const val TAG = "App"
private const val LOCK_PORT = 29171 // one above the WebSocket port

private fun acquireLock(): ServerSocket? {
    return try {
        ServerSocket(LOCK_PORT, 1, InetAddress.getLoopbackAddress())
    } catch (_: Exception) {
        null // port already bound = another instance running
    }
}

fun main() {
    Log.i(TAG, "Starting Spyglass Connect")
    val lock = acquireLock()
    if (lock == null) {
        Log.w(TAG, "Another instance detected on port $LOCK_PORT")
        val choice = JOptionPane.showConfirmDialog(
            null,
            "Spyglass Connect is already running.\nDo you want to stop it and start a new instance?",
            "Spyglass Connect",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE,
        )
        if (choice != JOptionPane.YES_OPTION) return

        // Send shutdown signal to the existing instance
        try {
            java.net.Socket(InetAddress.getLoopbackAddress(), LOCK_PORT).close()
        } catch (_: Exception) { /* best effort */ }

        // Poll for the old instance to release the port (up to 2s)
        var retryLock: ServerSocket? = null
        for (attempt in 1..20) {
            Thread.sleep(100)
            retryLock = acquireLock()
            if (retryLock != null) break
        }
        if (retryLock == null) {
            JOptionPane.showMessageDialog(
                null,
                "Could not stop the previous instance. Please close it manually.",
                "Spyglass Connect",
                JOptionPane.ERROR_MESSAGE,
            )
            return
        }
        Log.i(TAG, "Took over from previous instance")
        startApp(retryLock)
        return
    }

    Log.i(TAG, "Acquired instance lock on port $LOCK_PORT")
    startApp(lock)
}

private fun startApp(lock: ServerSocket) {
    // Listen for shutdown signal from a new instance
    Thread({
        try {
            lock.accept().close() // blocks until a connection arrives
            System.exit(0)       // triggers shutdown hooks
        } catch (_: Exception) { /* socket closed on normal exit */ }
    }, "instance-lock").apply { isDaemon = true; start() }
    val server = WebSocketServer()
    server.setWorldsProvider { emptyList() } // placeholder until Compose state is ready
    val mdns = MdnsPublisher()
    val watcher = arrayOfNulls<WorldWatcher>(1) // holder for shutdown hook access

    // Ensure cleanup on any exit (window close, kill signal, etc.)
    Runtime.getRuntime().addShutdownHook(Thread {
        watcher[0]?.stop()
        mdns.stop()
        server.stop()
    })

    application {
        val scope = rememberCoroutineScope()
        val lanIp = remember { LanHelper.detectLanIp() }
        val worlds = remember { mutableStateListOf<WorldInfo>() }
        var worldsLoaded by remember { mutableStateOf(false) }
        var refreshTrigger by remember { mutableStateOf(0) }
        var windowVisible by remember { mutableStateOf(true) }

        // Wire the worlds list into the server so it reads from in-memory state
        LaunchedEffect(worlds) {
            server.setWorldsProvider { worlds.toList() }
        }

        val worldWatcher = remember {
            WorldWatcher(scope) { categories ->
                server.invalidateCache(categories)
                server.notifyWorldChanged("", categories.toList())
            }.also { watcher[0] = it }
        }

        // Detect Minecraft worlds on startup and on config change
        LaunchedEffect(refreshTrigger) {
            worldsLoaded = false
            withContext(Dispatchers.IO) {
                val local = SaveDetector.detectWorlds()
                val remote = SaveDetector.detectRemoteWorlds()
                worlds.clear()
                worlds.addAll(local + remote)
            }
            worldsLoaded = true
        }

        // Start WebSocket server and mDNS in parallel
        LaunchedEffect(Unit) {
            launch(Dispatchers.IO) { server.start() }
            launch(Dispatchers.IO) { mdns.start(WebSocketServer.DEFAULT_PORT, lanIp) }
        }

        // Watch first world's directory for changes
        LaunchedEffect(worlds.firstOrNull()) {
            val firstWorld = worlds.firstOrNull() ?: return@LaunchedEffect
            val worldPath = firstWorld.sourcePath
            if (worldPath.isNotEmpty()) {
                val worldDir = java.io.File(worldPath)
                if (worldDir.isDirectory) {
                    worldWatcher.watch(worldDir)
                }
            }
        }

        val shutdown: () -> Unit = {
            watcher[0]?.stop()
            mdns.stop()
            server.stop()
            exitApplication()
        }

        val hideToTray: () -> Unit = {
            windowVisible = false
        }

        SystemTray(
            serverState = server.state,
            onShowWindow = { windowVisible = true },
            onQuit = shutdown,
        )

        MainWindow(
            worlds = worlds,
            worldsLoaded = worldsLoaded,
            serverState = server.state,
            connectedDevices = server.connectedDevices,
            lanIp = lanIp,
            serverPort = WebSocketServer.DEFAULT_PORT,
            deviceLogCount = server.deviceLogCount,
            onRefreshWorlds = { refreshTrigger++ },
            onCloseRequest = {
                val config = ConfigStore.load()
                if (config.closeToTray) hideToTray() else shutdown()
            },
            onMinimize = {
                val config = ConfigStore.load()
                if (config.minimizeToTray) hideToTray()
            },
            windowVisible = windowVisible,
        )
    }
}
