package ch.fbc.krakenbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class PermissionGroupState(
    val name: String,
    val reason: String,
    val isGranted: Boolean
)

@Composable
fun PermissionScreen(
    groups: List<PermissionGroupState>,
    batteryOptimizationExempt: Boolean,
    accessibilityEnabled: Boolean,
    displayOverlayGranted: Boolean,
    onContinue: () -> Unit
) {
    val rows = groups + listOf(
        PermissionGroupState(
            name = "Battery",
            reason = "Keep the BLE connection alive during your dive",
            isGranted = batteryOptimizationExempt
        ),
        PermissionGroupState(
            name = "Accessibility",
            reason = "Control camera apps via housing buttons",
            isGranted = accessibilityEnabled
        ),
        PermissionGroupState(
            name = "Display",
            reason = "Keep screen reachable underwater without lockscreen",
            isGranted = displayOverlayGranted
        )
    )

    Box(modifier = Modifier.fillMaxSize()) {
        WaveBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Title — same size and placement as MainScreen so the screens
            // feel like one app, not two.
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "Kraken Dive Photo",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(36.dp))

            // Subtitle: what this screen is about.
            Text(
                text = "App Permissions",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Kraken Dive Photo needs access to a few system features to work properly underwater.",
                fontSize = 14.sp,
                color = OceanTextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(28.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                rows.forEachIndexed { index, row ->
                    PermissionRow(
                        name = row.name,
                        reason = row.reason,
                        isGranted = row.isGranted
                    )
                    if (index < rows.lastIndex) {
                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Round Allow CTA — matches the hero-circle language from
            // MainScreen at a smaller scale.
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .shadow(elevation = 10.dp, shape = CircleShape)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onContinue),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = CheckIcon,
                    contentDescription = "Allow",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Allow",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun PermissionRow(
    name: String,
    reason: String,
    isGranted: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = CircleShape,
            color = if (isGranted)
                KrakenGreen.copy(alpha = 0.20f)
            else
                MaterialTheme.colorScheme.surface
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (isGranted) {
                    Icon(
                        imageVector = CheckIcon,
                        contentDescription = null,
                        tint = KrakenGreen,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(8.dp),
                        shape = CircleShape,
                        color = OceanTextMuted.copy(alpha = 0.6f)
                    ) {}
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = reason,
                fontSize = 14.sp,
                color = OceanTextMuted,
                lineHeight = 18.sp
            )
        }
    }
}
