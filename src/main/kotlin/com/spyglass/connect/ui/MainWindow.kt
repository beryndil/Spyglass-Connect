package com.spyglass.connect.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import com.spyglass.connect.config.ConfigStore
import com.spyglass.connect.model.WorldInfo
import com.spyglass.connect.pairing.QrCodeGenerator
import com.spyglass.connect.server.EncryptionManager
import com.spyglass.connect.server.WebSocketServer
import java.text.SimpleDateFormat
import java.util.Date
import javax.imageio.ImageIO
import javax.swing.JFileChooser

@Composable
fun MainWindow(
    worlds: List<WorldInfo>,
    worldsLoaded: Boolean,
    serverState: MutableState<WebSocketServer.ServerState>,
    lanIp: String,
    serverPort: Int,
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
                        Text(
                            "Spyglass Connect",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        IconButton(onClick = { showSettings = !showSettings }) {
                            Icon(
                                if (showSettings) Icons.Filled.Close else Icons.Filled.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    // Server status
                    ServerStatusCard(serverState.value, lanIp, serverPort)

                    if (showSettings) {
                        SettingsSection(onRefreshWorlds)
                    } else {
                        // Main content
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            // QR Code — only show when server is running AND worlds are loaded
                            val isReady = serverState.value == WebSocketServer.ServerState.RUNNING && worldsLoaded
                            item {
                                if (isReady) {
                                    QrCodeSection(lanIp, serverPort)
                                } else {
                                    LoadingSection(serverState.value, worldsLoaded)
                                }
                            }

                            // Worlds section
                            item {
                                Text(
                                    "Detected Worlds (${worlds.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }

                            if (worlds.isEmpty() && worldsLoaded) {
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

                            items(worlds) { world ->
                                WorldCard(world)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingSection(serverState: WebSocketServer.ServerState, worldsLoaded: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
            )
            val message = when {
                serverState == WebSocketServer.ServerState.STARTING -> "Starting server..."
                serverState == WebSocketServer.ServerState.ERROR -> "Server error — check port"
                !worldsLoaded -> "Scanning for worlds..."
                else -> "Getting ready..."
            }
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun SettingsSection(onRefreshWorlds: () -> Unit) {
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
    }
}

@Composable
private fun ServerStatusCard(
    state: WebSocketServer.ServerState,
    lanIp: String,
    port: Int,
) {
    val (statusText, statusColor) = when (state) {
        WebSocketServer.ServerState.RUNNING -> "Running on $lanIp:$port" to Color(0xFF4CAF50)
        WebSocketServer.ServerState.STARTING -> "Starting..." to Color(0xFFFFC107)
        WebSocketServer.ServerState.ERROR -> "Error — check port $port" to Color(0xFFF44336)
        WebSocketServer.ServerState.STOPPED -> "Stopped" to Color(0xFF9E9E9E)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.Circle,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(12.dp),
            )
            Column {
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
private fun WorldCard(world: WorldInfo) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy  h:mm a") }
    val lastPlayed = if (world.lastPlayed > 0) {
        dateFormat.format(Date(world.lastPlayed))
    } else "Unknown"

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
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    world.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "${world.gameMode.replaceFirstChar { it.uppercase() }} • ${world.difficulty.replaceFirstChar { it.uppercase() }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    if (world.sourceLabel != "Default") {
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
