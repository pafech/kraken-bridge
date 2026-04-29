package ch.fbc.krakenbridge.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class ConnectionPhase { Idle, Busy, Ready }

@Composable
fun MainScreen(
    features: ch.fbc.krakenbridge.Features,
    status: String,
    message: String,
    showHelpDialog: Boolean,
    bluetoothEnabled: Boolean,
    airplaneModeOn: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleBluetooth: () -> Unit,
    onToggleAirplaneMode: () -> Unit,
    onShowHelp: () -> Unit,
    onDismissHelp: () -> Unit,
    onOpenSettings: () -> Unit
) {
    if (showHelpDialog) {
        HelpDialog(features = features, onDismiss = onDismissHelp)
    }
    val phase = when (status) {
        "scanning", "connecting", "reconnecting" -> ConnectionPhase.Busy
        "connected", "ready" -> ConnectionPhase.Ready
        else -> ConnectionPhase.Idle
    }
    var btFlashTrigger by remember { mutableStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        WaveBackground()

        // Top — app identity. Status bar inset + extra clearance for the
        // front-facing camera punch-hole.
        Text(
            text = "Kraken Dive Photo",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 32.dp)
        )

        // Settings gear — re-opens the FeatureSelectionScreen so the user
        // can toggle Gallery / Dive Mode without reinstalling.
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 24.dp, end = 12.dp)
        ) {
            Icon(
                imageVector = SettingsIcon,
                contentDescription = "Settings",
                tint = OceanTextMuted,
                modifier = Modifier.size(28.dp)
            )
        }

        // Centre — the hero. Big circle anchors the screen visually and
        // physically. Tap = action (connect / cancel). State word and
        // info sit directly below in descending visual weight.
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatusChip(
                    icon = if (bluetoothEnabled) BluetoothIcon else BluetoothDisabledIcon,
                    isOk = bluetoothEnabled,
                    contentDescription = if (bluetoothEnabled) "Bluetooth on" else "Bluetooth off",
                    flashKey = btFlashTrigger,
                    onClick = onToggleBluetooth
                )
                StatusChip(
                    icon = if (airplaneModeOn) AirplaneActiveIcon else AirplaneInactiveIcon,
                    isOk = airplaneModeOn,
                    contentDescription = if (airplaneModeOn) "Airplane mode on" else "Airplane mode off",
                    onClick = onToggleAirplaneMode
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            HeroCircle(
                phase = phase,
                enabled = bluetoothEnabled,
                onTap = {
                    when {
                        !bluetoothEnabled -> btFlashTrigger++
                        phase == ConnectionPhase.Idle -> onConnect()
                        else -> onDisconnect()
                    }
                }
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Fixed-height slot keeps the centered Column's total height
            // constant across phases — otherwise the Ready state's 3-line
            // sub-info pushes the hero circle visibly upward.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = status.replaceFirstChar { it.uppercase() },
                        fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = subInfo(phase, status, message),
                        fontSize = 14.sp,
                        color = OceanTextMuted,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                }
            }
        }

        // Bottom — help link. navigationBarsPadding lifts it clear of the
        // gesture bar; +16dp gives visual breathing room above that.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TextButton(onClick = onShowHelp) {
                Icon(
                    imageVector = ListIcon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Button Mapping",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}

// Sub-info logic: prefer service messages when they carry information
// beyond the status word (errors, "Limited photo access…"); otherwise
// fall back to per-phase guidance.
private fun subInfo(phase: ConnectionPhase, status: String, message: String): String {
    val carriesNewInfo = message.isNotBlank() &&
        !message.lowercase().startsWith(status.lowercase())
    if (carriesNewInfo) return message
    return when (phase) {
        ConnectionPhase.Idle -> "Tap the circle to connect"
        ConnectionPhase.Busy -> "Searching for Kraken — tap to cancel"
        ConnectionPhase.Ready -> "Press the Shutter button (red)\non the Kraken to open Camera\n— tap to disconnect"
    }
}

@Composable
private fun HeroCircle(
    phase: ConnectionPhase,
    enabled: Boolean,
    onTap: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = when {
            !enabled -> KrakenDisabled
            phase == ConnectionPhase.Idle -> MaterialTheme.colorScheme.primary
            phase == ConnectionPhase.Busy -> KrakenAmber
            else -> KrakenGreen
        },
        animationSpec = spring(
            dampingRatio = 0.9f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "circleColor"
    )

    // Idle breath — slow scale modulation invites the user to tap.
    // Always declared so the transition is stable across phase changes;
    // the scale value collapses to 1f outside Idle so the breath only
    // shows on the disconnected screen.
    val breathTransition = rememberInfiniteTransition(label = "idleBreath")
    val breathProgress by breathTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathProgress"
    )
    val circleScale = if (phase == ConnectionPhase.Idle && enabled) {
        0.96f + 0.08f * breathProgress  // 0.96 → 1.04
    } else {
        1f
    }

    Box(
        modifier = Modifier.size(260.dp),
        contentAlignment = Alignment.Center
    ) {
        if (phase == ConnectionPhase.Busy) {
            PulseRings(color = KrakenAmber)
        }
        if (phase == ConnectionPhase.Ready) {
            ConnectedGlow(color = KrakenGreen)
        }

        Box(
            modifier = Modifier
                .size(180.dp)
                .scale(circleScale)
                .shadow(elevation = 10.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(containerColor)
                .clickable(onClick = onTap),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = ConnectionLinkIcon,
                contentDescription = when (phase) {
                    ConnectionPhase.Idle -> "Connect"
                    ConnectionPhase.Busy -> null
                    ConnectionPhase.Ready -> "Connected — tap to disconnect"
                },
                tint = if (phase == ConnectionPhase.Busy) {
                    Color.Black.copy(alpha = 0.85f)
                } else {
                    Color.White
                },
                modifier = Modifier.size(80.dp)
            )
        }
    }
}

@Composable
private fun ConnectedGlow(color: Color) {
    val transition = rememberInfiniteTransition(label = "glow")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowProgress"
    )

    val baseSize = 180.dp
    // Inner halo: tight to the circle, brighter, breathes a small distance.
    val innerSize: Dp = baseSize + 8.dp + (12.dp * progress)
    val innerAlpha = (0.55f - 0.35f * progress).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .size(innerSize)
            .alpha(innerAlpha)
            .border(width = 2.dp, color = color, shape = CircleShape)
    )

    // Outer halo: wider radius, softer, breathes further.
    val outerSize: Dp = baseSize + 22.dp + (30.dp * progress)
    val outerAlpha = (0.30f - 0.25f * progress).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .size(outerSize)
            .alpha(outerAlpha)
            .border(width = 1.dp, color = color, shape = CircleShape)
    )
}

@Composable
private fun PulseRings(color: Color) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val ringDuration = 2200
    val staggerOffsets = listOf(0, 730, 1460)
    val baseSize = 180.dp
    val maxExtra = 70.dp

    staggerOffsets.forEach { offset ->
        val progress by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = ringDuration, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
                initialStartOffset = StartOffset(offsetMillis = offset)
            ),
            label = "ring$offset"
        )
        val size: Dp = baseSize + (maxExtra * progress)
        val alpha = ((1f - progress) * 0.55f).coerceIn(0f, 1f)

        Box(
            modifier = Modifier
                .size(size)
                .alpha(alpha)
                .border(width = 2.dp, color = color, shape = CircleShape)
        )
    }
}

// flashKey: incrementing it pulses the chip so a parent can redirect
// attention — used when the user taps a disabled Connect button while
// BT is off.
@Composable
private fun StatusChip(
    icon: ImageVector,
    isOk: Boolean,
    contentDescription: String,
    flashKey: Int = 0,
    onClick: () -> Unit
) {
    val tint = if (isOk) KrakenGreen else KrakenRed
    val bg = tint.copy(alpha = 0.18f)

    val pulse = remember { Animatable(1f) }
    LaunchedEffect(flashKey) {
        if (flashKey > 0) {
            pulse.snapTo(1f)
            pulse.animateTo(1.25f, tween(140))
            pulse.animateTo(1f, tween(220))
            pulse.animateTo(1.18f, tween(140))
            pulse.animateTo(1f, tween(220))
        }
    }

    Box(
        modifier = Modifier
            .size(52.dp)
            .scale(pulse.value)
            .clip(CircleShape)
            .background(bg)
            .border(width = 1.dp, color = tint.copy(alpha = 0.55f), shape = CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(26.dp)
        )
    }
}

// Inline Bluetooth icon — keeps us off material-icons-extended (4+ MB).
private val BluetoothIcon: ImageVector by lazy {
    ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            // Outer Bluetooth silhouette
            moveTo(17.71f, 7.71f)
            lineTo(12f, 2f)
            lineTo(11f, 2f)
            lineTo(11f, 9.59f)
            lineTo(6.41f, 5f)
            lineTo(5f, 6.41f)
            lineTo(10.59f, 12f)
            lineTo(5f, 17.59f)
            lineTo(6.41f, 19f)
            lineTo(11f, 14.41f)
            lineTo(11f, 22f)
            lineTo(12f, 22f)
            lineTo(17.71f, 16.29f)
            lineTo(13.41f, 12f)
            close()
            // Upper triangle fill
            moveTo(13f, 5.83f)
            lineTo(15.17f, 8f)
            lineTo(13f, 10.17f)
            close()
            // Lower triangle fill
            moveTo(13f, 13.83f)
            lineTo(15.17f, 16f)
            lineTo(13f, 18.17f)
            close()
        }
    }.build()
}

// Inline bluetooth_disabled icon (Material Symbols).
// Keeps us off material-icons-extended (4+ MB).
private val BluetoothDisabledIcon: ImageVector by lazy {
    ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            // Top-right partial bluetooth glyph
            moveTo(13f, 5.83f)
            lineToRelative(1.88f, 1.88f)
            lineToRelative(-1.06f, 1.06f)
            lineToRelative(1.41f, 1.41f)
            lineTo(19.71f, 7.7f)
            lineTo(14f, 2f)
            horizontalLineToRelative(-1f)
            verticalLineToRelative(4.17f)
            lineToRelative(1f, 1f)
            verticalLineTo(5.83f)
            close()
            // Body + diagonal slash + lower-left segments
            moveTo(5.41f, 4f)
            lineTo(4f, 5.41f)
            lineTo(10.59f, 12f)
            lineTo(5f, 17.59f)
            lineTo(6.41f, 19f)
            lineTo(11f, 14.41f)
            verticalLineTo(22f)
            horizontalLineToRelative(1f)
            lineToRelative(4.29f, -4.29f)
            lineToRelative(2.3f, 2.3f)
            lineToRelative(1.41f, -1.41f)
            lineTo(5.41f, 4f)
            close()
            // Lower triangle remnant
            moveTo(13f, 18.17f)
            verticalLineToRelative(-3.76f)
            lineToRelative(1.88f, 1.88f)
            lineTo(13f, 18.17f)
            close()
        }
    }.build()
}

// Inline check icon — keeps us off material-icons-extended (4+ MB).
// Internal so PermissionScreen can reuse for its Allow CTA.
internal val CheckIcon: ImageVector by lazy {
    ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(9f, 16.17f)
            lineTo(4.83f, 12f)
            lineTo(3.41f, 13.41f)
            lineTo(9f, 19f)
            lineTo(21f, 7f)
            lineTo(19.59f, 5.59f)
            close()
        }
    }.build()
}

// Inline settings gear icon (Material Symbols Outlined, centered at 12,12).
// Keeps us off material-icons-extended (4+ MB). EvenOdd fill renders the
// inner circle as a hole through the gear, matching the system Settings glyph.
private val SettingsIcon: ImageVector by lazy {
    ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            // Outer gear silhouette
            moveTo(19.14f, 12.94f)
            curveToRelative(0.04f, -0.3f, 0.06f, -0.61f, 0.06f, -0.94f)
            curveToRelative(0f, -0.32f, -0.02f, -0.64f, -0.07f, -0.94f)
            lineToRelative(2.03f, -1.58f)
            curveToRelative(0.18f, -0.14f, 0.23f, -0.41f, 0.12f, -0.61f)
            lineToRelative(-1.92f, -3.32f)
            curveToRelative(-0.12f, -0.22f, -0.37f, -0.29f, -0.59f, -0.22f)
            lineToRelative(-2.39f, 0.96f)
            curveToRelative(-0.5f, -0.38f, -1.03f, -0.7f, -1.62f, -0.94f)
            lineTo(14.4f, 2.81f)
            curveToRelative(-0.04f, -0.24f, -0.24f, -0.41f, -0.48f, -0.41f)
            horizontalLineToRelative(-3.84f)
            curveToRelative(-0.24f, 0f, -0.43f, 0.17f, -0.47f, 0.41f)
            lineTo(9.25f, 5.35f)
            curveTo(8.66f, 5.59f, 8.12f, 5.92f, 7.63f, 6.29f)
            lineTo(5.24f, 5.33f)
            curveToRelative(-0.22f, -0.08f, -0.47f, 0f, -0.59f, 0.22f)
            lineTo(2.74f, 8.87f)
            curveTo(2.62f, 9.08f, 2.66f, 9.34f, 2.86f, 9.48f)
            lineToRelative(2.03f, 1.58f)
            curveTo(4.84f, 11.36f, 4.8f, 11.69f, 4.8f, 12f)
            reflectiveCurveToRelative(0.02f, 0.64f, 0.07f, 0.94f)
            lineToRelative(-2.03f, 1.58f)
            curveToRelative(-0.18f, 0.14f, -0.23f, 0.41f, -0.12f, 0.61f)
            lineToRelative(1.92f, 3.32f)
            curveToRelative(0.12f, 0.22f, 0.37f, 0.29f, 0.59f, 0.22f)
            lineToRelative(2.39f, -0.96f)
            curveToRelative(0.5f, 0.38f, 1.03f, 0.7f, 1.62f, 0.94f)
            lineToRelative(0.36f, 2.54f)
            curveToRelative(0.05f, 0.24f, 0.24f, 0.41f, 0.48f, 0.41f)
            horizontalLineToRelative(3.84f)
            curveToRelative(0.24f, 0f, 0.44f, -0.17f, 0.47f, -0.41f)
            lineToRelative(0.36f, -2.54f)
            curveToRelative(0.59f, -0.24f, 1.13f, -0.56f, 1.62f, -0.94f)
            lineToRelative(2.39f, 0.96f)
            curveToRelative(0.22f, 0.08f, 0.47f, 0f, 0.59f, -0.22f)
            lineToRelative(1.92f, -3.32f)
            curveToRelative(0.12f, -0.22f, 0.07f, -0.47f, -0.12f, -0.61f)
            lineTo(19.14f, 12.94f)
            close()
            // Inner circle (hole) — centered exactly at (12, 12)
            moveTo(12f, 15.6f)
            curveToRelative(-1.98f, 0f, -3.6f, -1.62f, -3.6f, -3.6f)
            reflectiveCurveToRelative(1.62f, -3.6f, 3.6f, -3.6f)
            reflectiveCurveToRelative(3.6f, 1.62f, 3.6f, 3.6f)
            reflectiveCurveTo(13.98f, 15.6f, 12f, 15.6f)
            close()
        }
    }.build()
}

// Inline list icon — keeps us off material-icons-extended (4+ MB).
private val ListIcon: ImageVector by lazy {
    ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(3f, 13f); lineTo(3f, 11f); lineTo(5f, 11f); lineTo(5f, 13f); close()
            moveTo(3f, 17f); lineTo(3f, 15f); lineTo(5f, 15f); lineTo(5f, 17f); close()
            moveTo(3f, 9f); lineTo(3f, 7f); lineTo(5f, 7f); lineTo(5f, 9f); close()
            moveTo(7f, 13f); lineTo(7f, 11f); lineTo(21f, 11f); lineTo(21f, 13f); close()
            moveTo(7f, 17f); lineTo(7f, 15f); lineTo(21f, 15f); lineTo(21f, 17f); close()
            moveTo(7f, 7f); lineTo(21f, 7f); lineTo(21f, 9f); lineTo(7f, 9f); close()
        }
    }.build()
}

// Inline airplanemode_active icon (Material Symbols).
private val AirplaneActiveIcon: ImageVector by lazy {
    ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(21f, 16f)
            verticalLineToRelative(-2f)
            lineToRelative(-8f, -5f)
            verticalLineTo(3.5f)
            curveTo(13f, 2.67f, 12.33f, 2f, 11.5f, 2f)
            reflectiveCurveTo(10f, 2.67f, 10f, 3.5f)
            verticalLineTo(9f)
            lineToRelative(-8f, 5f)
            verticalLineToRelative(2f)
            lineToRelative(8f, -2.5f)
            verticalLineTo(19f)
            lineToRelative(-2f, 1.5f)
            verticalLineTo(22f)
            lineToRelative(3.5f, -1f)
            lineToRelative(3.5f, 1f)
            verticalLineToRelative(-1.5f)
            lineTo(13f, 19f)
            verticalLineToRelative(-5.5f)
            close()
        }
    }.build()
}

// Airplane glyph with a diagonal strikethrough — visually mirrors the
// way BluetoothDisabledIcon crosses the BT glyph for the "off" state.
private val AirplaneInactiveIcon: ImageVector by lazy {
    ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        // Same airplane silhouette as AirplaneActiveIcon
        path(fill = SolidColor(Color.Black)) {
            moveTo(21f, 16f)
            verticalLineToRelative(-2f)
            lineToRelative(-8f, -5f)
            verticalLineTo(3.5f)
            curveTo(13f, 2.67f, 12.33f, 2f, 11.5f, 2f)
            reflectiveCurveTo(10f, 2.67f, 10f, 3.5f)
            verticalLineTo(9f)
            lineToRelative(-8f, 5f)
            verticalLineToRelative(2f)
            lineToRelative(8f, -2.5f)
            verticalLineTo(19f)
            lineToRelative(-2f, 1.5f)
            verticalLineTo(22f)
            lineToRelative(3.5f, -1f)
            lineToRelative(3.5f, 1f)
            verticalLineToRelative(-1.5f)
            lineTo(13f, 19f)
            verticalLineToRelative(-5.5f)
            close()
        }
        // Diagonal strike from top-right to bottom-left (parallelogram bar).
        path(fill = SolidColor(Color.Black)) {
            moveTo(20f, 2.5f)
            lineTo(21.5f, 4f)
            lineTo(4f, 21.5f)
            lineTo(2.5f, 20f)
            close()
        }
    }.build()
}

// Chain-link icon (Material Symbols `link`) — used on the hero circle as
// the "establish connection" visual. Distinct from the BT glyph so the
// connection action and the BT-status chip don't read as the same thing.
private val ConnectionLinkIcon: ImageVector by lazy {
    ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(3.9f, 12f)
            curveToRelative(0f, -1.71f, 1.39f, -3.1f, 3.1f, -3.1f)
            horizontalLineToRelative(4f)
            verticalLineTo(7f)
            horizontalLineTo(7f)
            curveToRelative(-2.76f, 0f, -5f, 2.24f, -5f, 5f)
            reflectiveCurveToRelative(2.24f, 5f, 5f, 5f)
            horizontalLineToRelative(4f)
            verticalLineToRelative(-1.9f)
            horizontalLineTo(7f)
            curveToRelative(-1.71f, 0f, -3.1f, -1.39f, -3.1f, -3.1f)
            close()
            moveTo(8f, 13f)
            horizontalLineToRelative(8f)
            verticalLineToRelative(-2f)
            horizontalLineTo(8f)
            verticalLineToRelative(2f)
            close()
            moveTo(17f, 7f)
            horizontalLineToRelative(-4f)
            verticalLineToRelative(1.9f)
            horizontalLineToRelative(4f)
            curveToRelative(1.71f, 0f, 3.1f, 1.39f, 3.1f, 3.1f)
            reflectiveCurveToRelative(-1.39f, 3.1f, -3.1f, 3.1f)
            horizontalLineToRelative(-4f)
            verticalLineTo(17f)
            horizontalLineToRelative(4f)
            curveToRelative(2.76f, 0f, 5f, -2.24f, 5f, -5f)
            reflectiveCurveToRelative(-2.24f, -5f, -5f, -5f)
            close()
        }
    }.build()
}
