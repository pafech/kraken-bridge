package com.krakenbridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HelpDialog(onDismiss: () -> Unit) {
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
                // Camera Mode
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
                HelpRow("Back", "Switch to Gallery")
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Gallery Mode
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
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
fun HelpRow(button: String, action: String) {
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
            modifier = Modifier.weight(1f)
        )
        Text(
            text = action,
            fontSize = 14.sp,
            color = Color.Gray,
            modifier = Modifier.weight(1.5f),
            textAlign = TextAlign.End
        )
    }
}
