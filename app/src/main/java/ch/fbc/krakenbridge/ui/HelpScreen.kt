package ch.fbc.krakenbridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.fbc.krakenbridge.Features

@Composable
fun HelpScreen(features: Features) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(top = 80.dp, start = 24.dp, end = 24.dp, bottom = 24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Button Mapping",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(28.dp))

        SectionLabel(text = "Camera Mode", color = KrakenGreen)
        Spacer(modifier = Modifier.height(12.dp))
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
            Spacer(modifier = Modifier.height(24.dp))
            SectionLabel(text = "Gallery Mode", color = OceanAquaLight)
            Spacer(modifier = Modifier.height(12.dp))
            HelpRow("Plus (+)", "Next photo/video")
            HelpRow("Minus (-)", "Previous photo/video")
            HelpRow("OK", "Delete photo/video")
            HelpRow("Back / Fn / Shutter", "Return to Camera")
        }
    }
}

@Composable
private fun SectionLabel(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        color = color,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Start
    )
}

@Composable
fun HelpRow(button: String, action: String, muted: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = button,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = if (muted) {
                OceanTextMuted.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.onBackground
            },
            modifier = Modifier.weight(1f)
        )
        Text(
            text = action,
            fontSize = 15.sp,
            color = if (muted) OceanTextMuted.copy(alpha = 0.5f) else OceanTextMuted,
            modifier = Modifier.weight(1.5f),
            textAlign = TextAlign.End
        )
    }
}
