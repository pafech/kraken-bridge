package ch.fbc.krakenbridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.fbc.krakenbridge.Features

@Composable
fun HelpDialog(
    features: Features,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = {
            Text(
                text = "Button Mapping",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = "Camera Mode",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color(0xFF4CAF50)
                )
                Spacer(modifier = Modifier.height(8.dp))
                HelpRow("Shutter (red)", "Take photo / Record video")
                HelpRow("Fn", "Toggle Photo ↔ Video mode")
                HelpRow("Plus (+)", "Focus closer")
                HelpRow("Minus (-)", "Focus farther")
                HelpRow("OK", "Auto-focus (center)")
                if (features.gallery) {
                    HelpRow("Back", "Switch to Gallery")
                } else {
                    HelpRow("Back", "Unmapped — enable Gallery in Settings", muted = true)
                }

                if (features.gallery) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Gallery Mode",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color(0xFF2196F3)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    HelpRow("Plus (+)", "Next photo/video")
                    HelpRow("Minus (-)", "Previous photo/video")
                    HelpRow("OK", "Delete photo/video")
                    HelpRow("Back / Fn / Shutter", "Return to Camera")
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
fun HelpRow(button: String, action: String, muted: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = button,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (muted) OceanTextMuted.copy(alpha = 0.55f) else Color.Unspecified,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = action,
            fontSize = 14.sp,
            color = if (muted) OceanTextMuted.copy(alpha = 0.55f) else OceanTextMuted,
            modifier = Modifier.weight(1.5f),
            textAlign = TextAlign.End
        )
    }
}
