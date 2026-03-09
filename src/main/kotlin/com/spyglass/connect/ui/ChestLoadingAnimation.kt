package com.spyglass.connect.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.unit.dp
import com.spyglass.connect.server.WebSocketServer
import kotlin.math.*

private enum class Phase { LOADING, COMPLETE, DONE }

private data class BurstParticle(
    val angle: Float,
    val speed: Float,
    val size: Float,
    val color: Color,
    var progress: Float = 0f,
)

@Composable
fun ChestLoadingAnimation(
    serverState: WebSocketServer.ServerState,
    worldsLoaded: Boolean,
    modifier: Modifier = Modifier,
) {
    // Progress mapping from real app state
    val rawProgress = when {
        worldsLoaded && serverState == WebSocketServer.ServerState.RUNNING -> 1f
        serverState == WebSocketServer.ServerState.RUNNING -> 0.5f
        serverState == WebSocketServer.ServerState.STARTING -> 0.2f
        serverState == WebSocketServer.ServerState.ERROR -> 0.1f
        else -> 0f
    }
    val progress by animateFloatAsState(rawProgress, tween(800))

    // Phase state machine
    var phase by remember { mutableStateOf(Phase.LOADING) }
    LaunchedEffect(rawProgress) {
        if (rawProgress >= 1f && phase == Phase.LOADING) phase = Phase.COMPLETE
    }

    // Animation time
    var time by remember { mutableFloatStateOf(0f) }
    var burstTime by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val startNanos = withFrameNanos { it }
        var lastNanos = startNanos
        while (true) {
            withFrameNanos { nanos ->
                val dt = (nanos - lastNanos) / 1_000_000_000f
                lastNanos = nanos
                time += dt
                if (phase == Phase.COMPLETE) {
                    burstTime += dt
                    if (burstTime > 1.5f) phase = Phase.DONE
                }
            }
        }
    }

    // Burst particles — created once on COMPLETE
    val burstParticles = remember { mutableStateListOf<BurstParticle>() }
    LaunchedEffect(phase) {
        if (phase == Phase.COMPLETE && burstParticles.isEmpty()) {
            val colors = listOf(
                Color(0xFF4CAF50), Color(0xFF8BC34A), Color(0xFFFFEB3B),
                Color(0xFF00BCD4), Color(0xFFFFD700),
            )
            repeat(24) { i ->
                burstParticles.add(
                    BurstParticle(
                        angle = (i * 15f) + (-5..5).random(),
                        speed = 80f + (0..60).random(),
                        size = 3f + (0..4).random(),
                        color = colors[i % colors.size],
                    )
                )
            }
        }
    }

    // Status text
    val statusMessage = when {
        serverState == WebSocketServer.ServerState.ERROR -> "Server error — check port"
        phase == Phase.DONE -> "Ready!"
        phase == Phase.COMPLETE -> "All set!"
        serverState == WebSocketServer.ServerState.STARTING -> "Starting server..."
        serverState == WebSocketServer.ServerState.RUNNING && !worldsLoaded -> "Scanning for worlds..."
        else -> "Getting ready..."
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Canvas(
                modifier = Modifier.fillMaxWidth().height(180.dp),
            ) {
                val w = size.width
                val h = size.height

                val chestW = 80f
                val chestH = 56f
                val lidH = 20f
                val cx = w / 2
                val cy = h * 0.42f

                // Idle glow behind chest
                val glowAlpha = 0.12f + 0.06f * sin(time * 2f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF4CAF50).copy(alpha = glowAlpha),
                            Color.Transparent,
                        ),
                        center = Offset(cx, cy),
                        radius = 100f,
                    ),
                    center = Offset(cx, cy),
                    radius = 100f,
                )

                // Orbiting diamonds (6)
                val orbitRadius = 70f
                val diamondSize = 8f
                val numDiamonds = 6
                for (i in 0 until numDiamonds) {
                    val baseAngle = (i * 360f / numDiamonds)
                    val angle = Math.toRadians((baseAngle + time * 60f).toDouble())
                    val dx = cx + orbitRadius * cos(angle).toFloat()
                    val dy = cy + orbitRadius * 0.5f * sin(angle).toFloat()

                    // Trail (3 fading copies)
                    for (t in 3 downTo 1) {
                        val trailAngle = Math.toRadians((baseAngle + (time * 60f) - t * 8f).toDouble())
                        val tdx = cx + orbitRadius * cos(trailAngle).toFloat()
                        val tdy = cy + orbitRadius * 0.5f * sin(trailAngle).toFloat()
                        val trailAlpha = 0.15f - t * 0.04f
                        val trailPath = Path().apply {
                            moveTo(tdx, tdy - diamondSize * 0.5f)
                            lineTo(tdx + diamondSize * 0.35f, tdy)
                            lineTo(tdx, tdy + diamondSize * 0.5f)
                            lineTo(tdx - diamondSize * 0.35f, tdy)
                            close()
                        }
                        drawPath(trailPath, Color(0xFF00BCD4).copy(alpha = trailAlpha))
                    }

                    // Diamond
                    val diamondPath = Path().apply {
                        moveTo(dx, dy - diamondSize)
                        lineTo(dx + diamondSize * 0.6f, dy)
                        lineTo(dx, dy + diamondSize)
                        lineTo(dx - diamondSize * 0.6f, dy)
                        close()
                    }
                    drawPath(diamondPath, Color(0xFF00BCD4))
                    drawPath(
                        diamondPath,
                        Color.White.copy(alpha = 0.3f),
                        style = Stroke(1f),
                    )
                }

                // Chest body
                val chestLeft = cx - chestW / 2
                val chestTop = cy - chestH / 2

                // Body shadow
                drawRoundRect(
                    color = Color(0xFF3E2723),
                    topLeft = Offset(chestLeft - 2, chestTop + 2),
                    size = Size(chestW + 4, chestH + 2),
                    cornerRadius = CornerRadius(4f),
                )

                // Body fill — brown gradient
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF8D6E4C), Color(0xFF6D4C3A)),
                        startY = chestTop,
                        endY = chestTop + chestH,
                    ),
                    topLeft = Offset(chestLeft, chestTop),
                    size = Size(chestW, chestH),
                    cornerRadius = CornerRadius(4f),
                )

                // Plank lines
                val plankColor = Color(0xFF5D3A2A).copy(alpha = 0.5f)
                for (py in 1..2) {
                    val lineY = chestTop + chestH * py / 3f
                    drawLine(plankColor, Offset(chestLeft + 4, lineY), Offset(chestLeft + chestW - 4, lineY), 1f)
                }

                // Edge highlight (top)
                drawLine(
                    Color.White.copy(alpha = 0.15f),
                    Offset(chestLeft + 2, chestTop + 1),
                    Offset(chestLeft + chestW - 2, chestTop + 1),
                    1.5f,
                )

                // Latch
                val latchW = 8f
                val latchH = 12f
                drawRoundRect(
                    color = Color(0xFFFFD700),
                    topLeft = Offset(cx - latchW / 2, chestTop - latchH / 2 + 2),
                    size = Size(latchW, latchH),
                    cornerRadius = CornerRadius(2f),
                )

                // Lid — rotates open when progress >= 0.7
                val lidOpenAngle = if (progress >= 0.7f) {
                    val lidProgress = ((progress - 0.7f) / 0.3f).coerceIn(0f, 1f)
                    -45f * lidProgress
                } else 0f

                withTransform({
                    translate(left = 0f, top = 0f)
                    // Rotate around top edge of chest
                    val pivotX = cx
                    val pivotY = chestTop
                    rotate(lidOpenAngle, Offset(pivotX, pivotY))
                }) {
                    // Lid shadow
                    drawRoundRect(
                        color = Color(0xFF3E2723),
                        topLeft = Offset(chestLeft - 2, chestTop - lidH),
                        size = Size(chestW + 4, lidH + 2),
                        cornerRadius = CornerRadius(4f),
                    )

                    // Lid fill
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color(0xFFA0845C), Color(0xFF8D6E4C)),
                            startY = chestTop - lidH,
                            endY = chestTop,
                        ),
                        topLeft = Offset(chestLeft, chestTop - lidH),
                        size = Size(chestW, lidH),
                        cornerRadius = CornerRadius(4f),
                    )

                    // Lid edge highlight
                    drawLine(
                        Color.White.copy(alpha = 0.2f),
                        Offset(chestLeft + 2, chestTop - lidH + 1),
                        Offset(chestLeft + chestW - 2, chestTop - lidH + 1),
                        1.5f,
                    )

                    // Lid plank line
                    drawLine(
                        plankColor,
                        Offset(chestLeft + 4, chestTop - lidH / 2),
                        Offset(chestLeft + chestW - 4, chestTop - lidH / 2),
                        1f,
                    )
                }

                // Light rays when lid opens
                if (lidOpenAngle < -5f) {
                    val rayAlpha = ((-lidOpenAngle - 5f) / 40f).coerceIn(0f, 0.4f)
                    val rayCount = 7
                    for (r in 0 until rayCount) {
                        val rayAngle = Math.toRadians((-140.0 + r * 10.0))
                        val rayLen = 50f + 20f * sin(time * 3f + r)
                        drawLine(
                            color = Color(0xFFFFD700).copy(alpha = rayAlpha * (0.5f + 0.5f * sin(time * 4f + r))),
                            start = Offset(cx, chestTop - lidH * 0.5f),
                            end = Offset(
                                cx + rayLen * cos(rayAngle).toFloat(),
                                chestTop - lidH * 0.5f + rayLen * sin(rayAngle).toFloat(),
                            ),
                            strokeWidth = 2f,
                            cap = StrokeCap.Round,
                        )
                    }
                }

                // Burst particles
                if (phase == Phase.COMPLETE || phase == Phase.DONE) {
                    burstParticles.forEach { p ->
                        val pProgress = (burstTime * p.speed / 80f).coerceIn(0f, 1f)
                        val pAlpha = (1f - pProgress).coerceIn(0f, 1f) * 0.8f
                        val dist = pProgress * p.speed
                        val pAngle = Math.toRadians(p.angle.toDouble())
                        val px = cx + dist * cos(pAngle).toFloat()
                        val py = cy + dist * sin(pAngle).toFloat()
                        drawCircle(
                            color = p.color.copy(alpha = pAlpha),
                            radius = p.size * (1f - pProgress * 0.5f),
                            center = Offset(px, py),
                        )
                    }
                }

                // XP bar below chest
                val barW = chestW * 1.5f
                val barH = 8f
                val barLeft = cx - barW / 2
                val barTop = cy + chestH / 2 + 16f
                val segmentCount = 20

                // Bar background
                drawRoundRect(
                    color = Color(0xFF1A1A1A),
                    topLeft = Offset(barLeft, barTop),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(4f),
                )
                drawRoundRect(
                    color = Color(0xFF333333),
                    topLeft = Offset(barLeft, barTop),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(4f),
                    style = Stroke(1f),
                )

                // Bar fill segments
                val segW = barW / segmentCount
                val filledSegments = (progress * segmentCount).toInt()
                for (s in 0 until filledSegments) {
                    val segLeft = barLeft + s * segW
                    val segColor = if (s < segmentCount / 2) {
                        Color(0xFF4CAF50)
                    } else {
                        Color(0xFF8BC34A)
                    }
                    drawRoundRect(
                        color = segColor,
                        topLeft = Offset(segLeft + 0.5f, barTop + 0.5f),
                        size = Size(segW - 1f, barH - 1f),
                        cornerRadius = CornerRadius(2f),
                    )
                }

                // Bar shine
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.1f),
                    topLeft = Offset(barLeft, barTop),
                    size = Size(barW, barH / 2),
                    cornerRadius = CornerRadius(4f),
                )
            }

            Text(
                statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}
