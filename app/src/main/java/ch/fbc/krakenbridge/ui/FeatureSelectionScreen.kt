package ch.fbc.krakenbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.fbc.krakenbridge.Features

@Composable
fun FeatureSelectionScreen(
    initial: Features,
    onContinue: (Features) -> Unit,
    onCancel: (() -> Unit)? = null
) {
    var gallery by remember(initial) { mutableStateOf(initial.gallery) }
    var diveMode by remember(initial) { mutableStateOf(initial.diveMode) }

    Box(modifier = Modifier.fillMaxSize()) {
        WaveBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "Kraken Dive Photo",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(36.dp))

            Text(
                text = "Choose Features",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Pick the features you want. We will only request permissions for what you enable.",
                fontSize = 14.sp,
                color = OceanTextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                FeatureCard(
                    name = "Camera",
                    description = "Capture photos and videos via the housing shutter button.",
                    isEnabled = true,
                    isLocked = true,
                    onToggle = {}
                )
                Spacer(modifier = Modifier.height(14.dp))
                FeatureCard(
                    name = "Gallery",
                    description = "Browse and delete dive photos using the housing buttons. Needs access to your photos and videos.",
                    isEnabled = gallery,
                    isLocked = false,
                    onToggle = { gallery = !gallery }
                )
                Spacer(modifier = Modifier.height(14.dp))
                FeatureCard(
                    name = "Dive Mode",
                    description = "Keep the screen on and dim it during the dive. Without this, your screen may turn off and the lockscreen may engage — you cannot unlock the phone underwater. Some banking apps refuse to run while this is enabled.",
                    isEnabled = diveMode,
                    isLocked = false,
                    onToggle = { diveMode = !diveMode }
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            Box(
                modifier = Modifier
                    .size(88.dp)
                    .shadow(elevation = 10.dp, shape = CircleShape)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable {
                        onContinue(Features(gallery = gallery, diveMode = diveMode))
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = CheckIcon,
                    contentDescription = "Continue",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Continue",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )

            if (onCancel != null) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onCancel) {
                    Text(
                        text = "Cancel",
                        color = OceanTextMuted
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun FeatureCard(
    name: String,
    description: String,
    isEnabled: Boolean,
    isLocked: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isLocked, onClick = onToggle),
        shape = RoundedCornerShape(14.dp),
        color = if (isEnabled)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f)
        else
            MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (isLocked) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = OceanTextMuted.copy(alpha = 0.18f)
                        ) {
                            Text(
                                text = "Required",
                                fontSize = 11.sp,
                                color = OceanTextMuted,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = description,
                    fontSize = 13.sp,
                    color = OceanTextMuted,
                    lineHeight = 18.sp
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Switch(
                checked = isEnabled,
                onCheckedChange = if (isLocked) null else { _ -> onToggle() },
                enabled = !isLocked
            )
        }
    }
}
