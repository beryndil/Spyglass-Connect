package com.spyglass.connect.ui

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.rememberTrayState
import com.spyglass.connect.server.WebSocketServer
import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * System tray icon for Spyglass Connect.
 * Uses the Spyglass app icon.
 */
@Composable
fun ApplicationScope.SystemTray(
    serverState: MutableState<WebSocketServer.ServerState>,
    onShowWindow: () -> Unit,
    onQuit: () -> Unit,
) {
    val trayState = rememberTrayState()

    val tooltip = when (serverState.value) {
        WebSocketServer.ServerState.RUNNING -> "Spyglass Connect — Running"
        WebSocketServer.ServerState.STARTING -> "Spyglass Connect — Starting..."
        WebSocketServer.ServerState.ERROR -> "Spyglass Connect — Error"
        WebSocketServer.ServerState.STOPPED -> "Spyglass Connect — Stopped"
    }

    val icon = remember {
        val stream = Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")
        if (stream != null) {
            val raw = ImageIO.read(stream)
            // Linux system trays don't support transparency — use dark background
            val size = java.awt.SystemTray.getSystemTray().trayIconSize
            val s = maxOf(size.width, size.height, 24)
            val img = BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = java.awt.Color.BLACK
            g.fillRect(0, 0, s, s)
            g.drawImage(raw, 0, 0, s, s, null)
            g.dispose()
            BitmapPainter(img.toComposeImageBitmap())
        } else {
            // Fallback: simple green circle
            val img = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.color = java.awt.Color(0x4CAF50)
            g.fillOval(2, 2, 28, 28)
            g.dispose()
            BitmapPainter(img.toComposeImageBitmap())
        }
    }

    Tray(
        state = trayState,
        icon = icon,
        tooltip = tooltip,
        onAction = onShowWindow,
        menu = {
            Item("Show", onClick = onShowWindow)
            Separator()
            Item("Quit", onClick = onQuit)
        },
    )
}
