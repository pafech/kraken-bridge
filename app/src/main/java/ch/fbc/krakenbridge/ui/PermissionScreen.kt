package ch.fbc.krakenbridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
    onGrantGroup: (String) -> Unit,
    onGrantBattery: () -> Unit,
    onContinue: () -> Unit
) {
    val allGranted = groups.all { it.isGranted } && batteryOptimizationExempt

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "Kraken Bridge",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Grant these permissions before your dive — you won't be able to do it underwater.",
            fontSize = 14.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        groups.forEach { group ->
            PermissionRow(
                name = group.name,
                reason = group.reason,
                isGranted = group.isGranted,
                onGrant = { onGrantGroup(group.name) }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        PermissionRow(
            name = "Battery",
            reason = "Keep the BLE connection alive during your dive",
            isGranted = batteryOptimizationExempt,
            onGrant = onGrantBattery
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onContinue,
            enabled = allGranted,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Continue", fontSize = 18.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun PermissionRow(
    name: String,
    reason: String,
    isGranted: Boolean,
    onGrant: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted)
                Color(0xFF1B5E20).copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = reason,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }

            if (isGranted) {
                Text(
                    text = "✓",
                    fontSize = 20.sp,
                    color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.Bold
                )
            } else {
                OutlinedButton(
                    onClick = onGrant,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Grant")
                }
            }
        }
    }
}
