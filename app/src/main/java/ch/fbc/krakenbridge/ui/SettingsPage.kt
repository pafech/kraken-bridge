package ch.fbc.krakenbridge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class FeaturePermission(
    val name: String,
    val isOn: Boolean,
    val onToggle: () -> Unit,
    val hint: String? = null
)

data class FeatureSection(
    val name: String,
    val description: String,
    val isLocked: Boolean,
    val isEnabled: Boolean,
    val onToggle: (Boolean) -> Unit,
    val permissions: List<FeaturePermission>,
    val hint: String? = null
)

/**
 * Single configuration surface. Each feature lists its toggle plus the
 * permissions it requires inline — tapping a row fires the corresponding
 * system dialog. Optional features that fail to acquire a required permission
 * revert to OFF (handled by the parent).
 *
 * Asymmetric horizontal padding keeps the right-side EdgeHandle (next-page
 * chevron) clear of the toggles. Sections are separated by a hairline
 * divider — no surface, since the Switch already conveys enabled state.
 */
@Composable
fun SettingsPage(
    sections: List<FeatureSection>,
    showReadyCta: Boolean = false,
    onReadyCtaClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 24.dp, end = 56.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Settings",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(20.dp))

        sections.forEachIndexed { index, section ->
            FeatureSectionRow(section)
            if (index < sections.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    thickness = 1.dp,
                    color = OceanTextMuted.copy(alpha = 0.18f)
                )
            }
        }

        // First-run "you're done with setup, start using it" CTA. Lives inline
        // so the EdgeHandle stays ambient navigation chrome instead of
        // doubling as a CTA. The parent persists "shown once" state, so the
        // button disappears for good after the first tap.
        AnimatedVisibility(
            visible = showReadyCta,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.height(28.dp))
                ReadyCtaPill(onClick = onReadyCtaClick)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun FeatureSectionRow(section: FeatureSection) {
    Column(modifier = Modifier.fillMaxWidth()) {
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
                section.hint?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        color = KrakenAmber,
                        lineHeight = 16.sp
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Switch(
                checked = section.isEnabled,
                onCheckedChange = if (section.isLocked) null else section.onToggle,
                enabled = !section.isLocked
            )
        }

        if (section.permissions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            section.permissions.forEachIndexed { index, perm ->
                PermissionInlineRow(perm)
                if (index < section.permissions.lastIndex) {
                    Spacer(modifier = Modifier.height(2.dp))
                }
            }
        }
    }
}

@Composable
private fun PermissionInlineRow(perm: FeaturePermission) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = perm.name,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
            perm.hint?.let {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = it,
                    fontSize = 12.sp,
                    color = OceanTextMuted,
                    lineHeight = 16.sp
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = perm.isOn,
            onCheckedChange = { perm.onToggle() }
        )
    }
}

@Composable
private fun ReadyCtaPill(onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.onBackground
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(percent = 50),
        color = Color.White.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.18f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Swipe to main screen",
                color = tint.copy(alpha = 0.85f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = ChevronRightIcon,
                contentDescription = null,
                tint = tint.copy(alpha = 0.85f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
