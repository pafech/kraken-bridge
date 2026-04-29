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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class PermissionState { Granted, Pending, NeedsSettings }

data class FeaturePermission(
    val name: String,
    val state: PermissionState,
    val onTap: () -> Unit
)

data class FeatureSection(
    val name: String,
    val description: String,
    val isLocked: Boolean,
    val isEnabled: Boolean,
    val onToggle: (Boolean) -> Unit,
    val permissions: List<FeaturePermission>
)

/**
 * Single configuration surface. Each feature card shows its own toggle plus
 * the permissions it requires inline — tapping a row fires the corresponding
 * system dialog. Optional features that fail to acquire a required permission
 * revert to OFF (handled by the parent).
 */
@Composable
fun SettingsPage(sections: List<FeatureSection>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(80.dp))

        Text(
            text = "Settings",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(28.dp))

        sections.forEachIndexed { index, section ->
            FeatureSectionCard(section)
            if (index < sections.lastIndex) {
                Spacer(modifier = Modifier.height(14.dp))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun FeatureSectionCard(section: FeatureSection) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (section.isEnabled)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f)
        else
            MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = section.name,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (section.isLocked) {
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
                        text = section.description,
                        fontSize = 13.sp,
                        color = OceanTextMuted,
                        lineHeight = 18.sp
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Switch(
                    checked = section.isEnabled,
                    onCheckedChange = if (section.isLocked) null else section.onToggle,
                    enabled = !section.isLocked
                )
            }

            if (section.permissions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                section.permissions.forEachIndexed { index, perm ->
                    PermissionInlineRow(perm)
                    if (index < section.permissions.lastIndex) {
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionInlineRow(perm: FeaturePermission) {
    val accent = when (perm.state) {
        PermissionState.Granted -> KrakenGreen
        PermissionState.NeedsSettings -> KrakenAmber
        PermissionState.Pending -> OceanTextMuted
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = perm.state != PermissionState.Granted, onClick = perm.onTap)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(10.dp),
            shape = CircleShape,
            color = if (perm.state == PermissionState.Pending)
                accent.copy(alpha = 0.45f)
            else
                accent
        ) {}
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = perm.name,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        if (perm.state != PermissionState.Granted) {
            Text(
                text = if (perm.state == PermissionState.NeedsSettings)
                    "Open Settings"
                else
                    "Tap to grant",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = accent
            )
        }
    }
}
