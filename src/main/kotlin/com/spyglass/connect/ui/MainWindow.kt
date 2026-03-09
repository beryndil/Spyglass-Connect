package com.spyglass.connect.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import com.spyglass.connect.BuildConfig
import com.spyglass.connect.config.ConfigStore
import com.spyglass.connect.model.WorldInfo
import com.spyglass.connect.pairing.QrCodeGenerator
import com.spyglass.connect.server.EncryptionManager
import com.spyglass.connect.server.WebSocketServer
import kotlinx.coroutines.flow.StateFlow
import java.awt.Desktop
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.imageio.ImageIO
import javax.swing.JFileChooser

@Composable
fun MainWindow(
    worlds: List<WorldInfo>,
    worldsLoaded: Boolean,
    serverState: MutableState<WebSocketServer.ServerState>,
    connectedDevices: List<WebSocketServer.ConnectedDevice>,
    lanIp: String,
    serverPort: Int,
    deviceLogCount: StateFlow<Int>,
    onRefreshWorlds: () -> Unit,
    onCloseRequest: () -> Unit,
) {
    val windowIcon = remember {
        val stream = Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")
        if (stream != null) {
            BitmapPainter(ImageIO.read(stream).toComposeImageBitmap())
        } else null
    }

    Window(
        onCloseRequest = onCloseRequest,
        title = "Spyglass Connect",
        icon = windowIcon,
        state = WindowState(size = DpSize(520.dp, 750.dp)),
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Color(0xFF7CBD6B),
                surface = Color(0xFF111111),
                background = Color.Black,
                onSurface = Color(0xFFE0E0E0),
                onBackground = Color(0xFFE0E0E0),
            ),
        ) {
            var showSettings by remember { mutableStateOf(false) }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Header with settings toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                "Spyglass Connect",
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color(0xFFFFD700),
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                BuildConfig.VERSION_NAME,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            )
                        }
                        IconButton(onClick = { showSettings = !showSettings }) {
                            Icon(
                                if (showSettings) Icons.Filled.Close else Icons.Filled.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    // Server status
                    ServerStatusCard(serverState.value, connectedDevices, lanIp, serverPort)

                    val logCount by deviceLogCount.collectAsState()

                    if (showSettings) {
                        SettingsSection(onRefreshWorlds, logCount)
                    } else {
                        // Main content
                        val compatibleWorlds = worlds.filter { !it.isModded }
                        var worldsExpanded by remember { mutableStateOf(false) }

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            // QR Code — only show when server is running, worlds loaded, AND no phone connected
                            val isReady = serverState.value == WebSocketServer.ServerState.RUNNING && worldsLoaded
                            val hasConnectedDevice = connectedDevices.any { it.isPaired }
                            if (!hasConnectedDevice) {
                                item {
                                    Crossfade(
                                        targetState = isReady,
                                        animationSpec = tween(600),
                                    ) { ready ->
                                        if (ready) {
                                            QrCodeSection(lanIp, serverPort)
                                        } else {
                                            ChestLoadingAnimation(serverState.value, worldsLoaded)
                                        }
                                    }
                                }
                            }

                            // Detected Worlds accordion card
                            item {
                                DetectedWorldsCard(
                                    count = compatibleWorlds.size,
                                    expanded = worldsExpanded,
                                    worldsLoaded = worldsLoaded,
                                    onClick = { worldsExpanded = !worldsExpanded },
                                )
                            }

                            if (worldsExpanded) {
                                if (compatibleWorlds.isEmpty() && worldsLoaded) {
                                    item {
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        ) {
                                            Text(
                                                "No Minecraft worlds found.\nAdd save directories in Settings (gear icon).",
                                                modifier = Modifier.padding(16.dp),
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                            )
                                        }
                                    }
                                }

                                items(compatibleWorlds) { world ->
                                    WorldCard(world)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun SettingsSection(onRefreshWorlds: () -> Unit, logCount: Int = 0) {
    val config = remember { mutableStateOf(ConfigStore.load()) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Prism Launcher auto-detection
        item {
            Text(
                "Launcher Detection",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Prism Launcher",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Auto-detect Prism Launcher instances",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                    Switch(
                        checked = config.value.autoDetectPrismLauncher,
                        onCheckedChange = {
                            ConfigStore.setAutoDetectPrism(it)
                            config.value = ConfigStore.load()
                            onRefreshWorlds()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                        ),
                    )
                }
            }
        }

        // Custom save directories
        item {
            Spacer(Modifier.height(4.dp))
            Text(
                "Custom Save Directories",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Add paths for Pterodactyl servers, modded launchers, or any custom location",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }

        items(config.value.customSaveDirs.toList()) { dir ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        dir,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            ConfigStore.removeCustomDir(dir)
                            config.value = ConfigStore.load()
                            onRefreshWorlds()
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Remove",
                            tint = Color(0xFFF44336),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = {
                    val chooser = JFileChooser().apply {
                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        dialogTitle = "Select Minecraft saves directory"
                    }
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        val path = chooser.selectedFile.absolutePath
                        ConfigStore.addCustomDir(path)
                        config.value = ConfigStore.load()
                        onRefreshWorlds()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add Directory")
            }
        }

        // Info about what paths to add
        item {
            Spacer(Modifier.height(4.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Path tips",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "• Pterodactyl: /srv/daemon-data/<server>/saves\n• Modded: point to the folder containing world directories\n• Single world: you can also point directly to a world folder",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
        }

        // Device Logs section
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                "Device Logs",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "$logCount entries received this session",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }

        item {
            val logFile = File(System.getProperty("user.home"), ".spyglass-connect/device-logs/device.log")
            var logText by remember { mutableStateOf("") }

            LaunchedEffect(logCount) {
                logText = try {
                    if (logFile.exists()) logFile.readText() else ""
                } catch (_: Exception) { "" }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
            ) {
                if (logText.isBlank()) {
                    Text(
                        "No device logs this session",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                } else {
                    SelectionContainer {
                        Text(
                            logText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerStatusCard(
    state: WebSocketServer.ServerState,
    connectedDevices: List<WebSocketServer.ConnectedDevice>,
    lanIp: String,
    port: Int,
) {
    val (statusText, statusColor) = when (state) {
        WebSocketServer.ServerState.RUNNING -> "Running on $lanIp:$port" to Color(0xFF4CAF50)
        WebSocketServer.ServerState.STARTING -> "Starting..." to Color(0xFFFFC107)
        WebSocketServer.ServerState.ERROR -> "Error — check port $port" to Color(0xFFF44336)
        WebSocketServer.ServerState.STOPPED -> "Stopped" to Color(0xFF9E9E9E)
    }

    val pairedDevices = connectedDevices.filter { it.isPaired }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val statusIcon = when (state) {
                    WebSocketServer.ServerState.RUNNING -> Icons.Filled.CheckCircle
                    WebSocketServer.ServerState.ERROR -> Icons.Filled.Error
                    else -> Icons.Filled.Circle
                }
                Icon(
                    statusIcon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(16.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Server Status",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            if (pairedDevices.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                Spacer(Modifier.height(12.dp))
                pairedDevices.forEach { device ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Filled.PhoneAndroid,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            device.deviceName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Connected",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF4CAF50),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QrCodeSection(lanIp: String, port: Int) {
    val encryption = remember { EncryptionManager() }
    val nonce = remember { encryption.generateNonce() }

    val qrImage = remember(lanIp, port) {
        QrCodeGenerator.generate(
            ip = lanIp,
            port = port,
            publicKeyBase64 = encryption.getPublicKeyBase64(),
            nonce = nonce,
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Scan with Spyglass App",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Image(
                bitmap = qrImage.toComposeImageBitmap(),
                contentDescription = "QR code for pairing",
                modifier = Modifier.size(200.dp)
                    .background(Color.White, RoundedCornerShape(8.dp))
                    .padding(8.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Open Spyglass → Connect to PC → Scan QR",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun DetectedWorldsCard(
    count: Int,
    expanded: Boolean,
    worldsLoaded: Boolean,
    onClick: () -> Unit,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200),
    )

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.Public,
                contentDescription = null,
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Detected Worlds",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (!worldsLoaded) "Scanning..."
                    else "$count compatible ${if (count == 1) "world" else "worlds"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp).rotate(chevronRotation),
            )
        }
    }
}

@Composable
private fun DeviceLogsCard(logCount: Int) {
    val logDir = File(System.getProperty("user.home"), ".spyglass-connect/device-logs")
    val logFile = File(logDir, "device.log")

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = Color(0xFFFFC107),
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Device Logs",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "$logCount entries received this session",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            if (logFile.exists()) {
                OutlinedButton(
                    onClick = {
                        try {
                            Desktop.getDesktop().open(logFile)
                        } catch (_: Exception) {
                            try { Desktop.getDesktop().open(logDir) } catch (_: Exception) {}
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFC107)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text("Open Log", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun WorldCard(world: WorldInfo) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy  h:mm a") }
    val lastPlayed = if (world.lastPlayed > 0) {
        dateFormat.format(Date(world.lastPlayed))
    } else "Unknown"

    val isModded = world.isModded
    val moddedRed = Color(0xFFF44336)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.Public,
                contentDescription = null,
                tint = if (isModded) moddedRed else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        world.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (isModded) moddedRed else MaterialTheme.colorScheme.onSurface,
                    )
                    if (isModded && world.modLoader != null) {
                        Text(
                            world.modLoader,
                            style = MaterialTheme.typography.labelSmall,
                            color = moddedRed,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (isModded) "Modded — not supported"
                        else "${world.gameMode.replaceFirstChar { it.uppercase() }} • ${world.difficulty.replaceFirstChar { it.uppercase() }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isModded) moddedRed.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    if (!isModded && world.sourceLabel != "Default") {
                        Text(
                            world.sourceLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        )
                    }
                }
                Text(
                    lastPlayed,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }
        }
    }
}
