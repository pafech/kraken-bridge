package ch.fbc.krakenbridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.fbc.krakenbridge.BuildConfig

@Composable
fun MainScreen(
    status: String,
    message: String,
    showHelpDialog: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenCamera: () -> Unit,
    onShowHelp: () -> Unit,
    onDismissHelp: () -> Unit
) {
    if (showHelpDialog) {
        HelpDialog(onDismiss = onDismissHelp)
    }
    val isConnected = status == "connected" || status == "ready"
    val isConnecting = status == "scanning" || status == "connecting" || status == "reconnecting"

    val statusColor = when (status) {
        "ready" -> Color(0xFF4CAF50)
        "connected" -> Color(0xFF8BC34A)
        "scanning", "connecting" -> Color(0xFFFFC107)
        "reconnecting" -> Color(0xFFFF9800)
        "error" -> Color(0xFFF44336)
        else -> Color(0xFF9E9E9E)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        WaveBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // Compact header — title shrinks so it doesn't compete with the
            // hero (status + primary CTA) for attention.
            Text(
                text = "Kraken Dive Photo",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                fontSize = 11.sp,
                color = OceanTextMuted.copy(alpha = 0.85f)
            )

            // Push hero block toward the optical centre (~40% from top).
            Spacer(modifier = Modifier.weight(1f))

            HeroSection(
                status = status,
                statusColor = statusColor,
                message = message,
                isConnected = isConnected,
                isConnecting = isConnecting,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onOpenCamera = onOpenCamera
            )

            Spacer(modifier = Modifier.weight(1.2f))

            // Secondary action — visually quiet so it never competes with
            // the primary connect/disconnect CTA.
            TextButton(
                onClick = onShowHelp,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = ListIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = OceanTextMuted
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Button Mapping",
                    fontSize = 14.sp,
                    color = OceanTextMuted
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HeroSection(
    status: String,
    statusColor: Color,
    message: String,
    isConnected: Boolean,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenCamera: () -> Unit
) {
    // Status and CTA share one column with tight spacing so the eye reads
    // them as a single unit (state → action), per Material 3 hero guidance.
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(20.dp),
            shape = CircleShape,
            color = statusColor
        ) {}

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = status.replaceFirstChar { it.uppercase() },
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = message,
            fontSize = 14.sp,
            color = OceanTextMuted,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        ActionButtons(
            isConnected = isConnected,
            isConnecting = isConnecting,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onOpenCamera = onOpenCamera
        )
    }
}

@Composable
private fun ActionButtons(
    isConnected: Boolean,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenCamera: () -> Unit
) {
    if (!isConnected && !isConnecting) {
        Button(
            onClick = onConnect,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Connect to Kraken", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    } else if (isConnecting) {
        Button(
            onClick = onDisconnect,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC107))
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.Black,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text("Cancel", fontSize = 18.sp, color = Color.Black)
        }
    } else {
        Button(
            onClick = onOpenCamera,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Open Camera", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onDisconnect,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Disconnect", fontSize = 15.sp, color = Color(0xFFFF8A80))
        }
    }
}

// Inline list icon — avoids pulling in material-icons-extended (4+ MB)
private val ListIcon: ImageVector by lazy {
    ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black)) {
            // Three horizontal lines with bullet points (Material "List" icon)
            moveTo(3f, 13f); lineTo(3f, 11f); lineTo(5f, 11f); lineTo(5f, 13f); close()
            moveTo(3f, 17f); lineTo(3f, 15f); lineTo(5f, 15f); lineTo(5f, 17f); close()
            moveTo(3f, 9f); lineTo(3f, 7f); lineTo(5f, 7f); lineTo(5f, 9f); close()
            moveTo(7f, 13f); lineTo(7f, 11f); lineTo(21f, 11f); lineTo(21f, 13f); close()
            moveTo(7f, 17f); lineTo(7f, 15f); lineTo(21f, 15f); lineTo(21f, 17f); close()
            moveTo(7f, 7f); lineTo(21f, 7f); lineTo(21f, 9f); lineTo(7f, 9f); close()
        }
    }.build()
}
