package ch.fbc.krakenbridge.ui

import androidx.compose.foundation.layout.*
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
    // Show help dialog when requested
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
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        
        // Title
        Text(
            text = "Kraken Dive Photo",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            text = "Kraken Underwater Housing Control",
            fontSize = 16.sp,
            color = Color.Gray
        )

        Text(
            text = "v${BuildConfig.VERSION_NAME}",
            fontSize = 12.sp,
            color = Color.Gray.copy(alpha = 0.6f)
        )
        
        Spacer(modifier = Modifier.height(24.dp))

        // Status indicator
        StatusCard(status, statusColor, message)
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Help button
        OutlinedButton(
            onClick = onShowHelp,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                imageVector = ListIcon,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Button Mapping", fontSize = 16.sp)
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Action buttons
        ActionButtons(
            isConnected = isConnected,
            isConnecting = isConnecting,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onOpenCamera = onOpenCamera
        )
        
        Spacer(modifier = Modifier.height(16.dp))
    }
    }
}

@Composable
private fun StatusCard(
    status: String,
    statusColor: Color,
    message: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status dot
            Surface(
                modifier = Modifier.size(24.dp),
                shape = RoundedCornerShape(12.dp),
                color = statusColor
            ) {}
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = status.replaceFirstChar { it.uppercase() },
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium
            )
            
            Text(
                text = message,
                fontSize = 14.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }
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
            Text("Connect to Kraken", fontSize = 18.sp)
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onOpenCamera,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Open Camera", fontSize = 16.sp)
            }
            
            Button(
                onClick = onDisconnect,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
            ) {
                Text("Disconnect", fontSize = 16.sp)
            }
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
