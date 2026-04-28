package ch.fbc.krakenbridge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One row in the permission walkthrough.
 *
 * - [isGranted] — green check, no further action needed.
 * - [needsSettings] — the system can no longer be re-prompted (permanently
 *   denied, or partial media access). Row gets a "Tap to open Settings" link
 *   and the user must grant from the system app-info screen.
 * - [hint] — extra one-line hint shown under the reason (e.g. "Pick 'Allow
 *   all' so dive photos appear" before the user has chosen).
 */
data class PermissionRowState(
    val name: String,
    val reason: String,
    val isGranted: Boolean,
    val needsSettings: Boolean = false,
    val hint: String? = null
)

@Composable
fun PermissionScreen(
    rows: List<PermissionRowState>,
    onContinue: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onBack: () -> Unit
) {
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
                    PermissionRow(row = row, onOpenAppSettings = onOpenAppSettings)
                    if (index < rows.lastIndex) {
                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            ActionRow(
                primaryLabel = "Allow",
                primaryIcon = CheckIcon,
                onPrimary = onContinue,
                secondaryLabel = "Back",
                onSecondary = onBack
            )

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun PermissionRow(
    row: PermissionRowState,
    onOpenAppSettings: () -> Unit
) {
    val rowModifier = if (row.needsSettings) {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onOpenAppSettings)
    } else {
        Modifier.fillMaxWidth()
    }
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = CircleShape,
            color = when {
                row.isGranted -> KrakenGreen.copy(alpha = 0.20f)
                row.needsSettings -> KrakenAmber.copy(alpha = 0.25f)
                else -> MaterialTheme.colorScheme.surface
            }
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (row.isGranted) {
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
                        color = if (row.needsSettings)
                            KrakenAmber
                        else
                            OceanTextMuted.copy(alpha = 0.6f)
                    ) {}
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = row.reason,
                fontSize = 14.sp,
                color = OceanTextMuted,
                lineHeight = 18.sp
            )
            if (row.hint != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = row.hint,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = KrakenAmber,
                    lineHeight = 17.sp
                )
            }
            if (row.needsSettings) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Tap to open Settings",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
