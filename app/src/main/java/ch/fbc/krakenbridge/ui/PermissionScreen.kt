package ch.fbc.krakenbridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        )
    )

    Box(modifier = Modifier.fillMaxSize()) {
        WaveBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(40.dp))

                Text(
                    text = "App Permissions",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Kraken Dive Photo needs access to a few system features to work properly underwater.",
                    fontSize = 14.sp,
                    color = OceanTextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(modifier = Modifier.height(32.dp))

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

            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "Continue",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
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
                Color(0xFF4CAF50).copy(alpha = 0.20f)
            else
                MaterialTheme.colorScheme.surface
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (isGranted) {
                    Text(
                        text = "✓",
                        fontSize = 18.sp,
                        color = Color(0xFF4CAF50),
                        fontWeight = FontWeight.Bold
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
                fontSize = 13.sp,
                color = OceanTextMuted,
                lineHeight = 18.sp
            )
        }
    }
}
