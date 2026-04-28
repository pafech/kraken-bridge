package ch.fbc.krakenbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Symmetric two-button row used by setup screens: primary (filled) on the left,
 * secondary (outlined back/cancel) on the right. Pass null for [onSecondary] on
 * screens where there is no destination to go back to (e.g. first-launch
 * FeatureSelectionScreen) to render the primary action centred and alone.
 */
@Composable
fun ActionRow(
    primaryLabel: String,
    primaryIcon: ImageVector,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Top
    ) {
        ActionButton(
            label = primaryLabel,
            icon = primaryIcon,
            isPrimary = true,
            onClick = onPrimary
        )
        if (onSecondary != null && secondaryLabel != null) {
            Spacer(modifier = Modifier.width(48.dp))
            ActionButton(
                label = secondaryLabel,
                icon = BackArrowIcon,
                isPrimary = false,
                onClick = onSecondary
            )
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    isPrimary: Boolean,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val baseModifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
        val styledModifier = if (isPrimary) {
            baseModifier
                .shadow(elevation = 10.dp, shape = CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        } else {
            baseModifier.border(
                width = 1.5.dp,
                color = OceanTextMuted.copy(alpha = 0.55f),
                shape = CircleShape
            )
        }
        Box(
            modifier = styledModifier.clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isPrimary) Color.White else OceanTextMuted,
                modifier = Modifier.size(if (isPrimary) 36.dp else 28.dp)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (isPrimary)
                MaterialTheme.colorScheme.onBackground
            else
                OceanTextMuted
        )
    }
}

private val BackArrowIcon: ImageVector by lazy {
    ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(20f, 11f)
            horizontalLineTo(7.83f)
            lineToRelative(5.59f, -5.59f)
            lineTo(12f, 4f)
            lineToRelative(-8f, 8f)
            lineToRelative(8f, 8f)
            lineToRelative(1.41f, -1.41f)
            lineTo(7.83f, 13f)
            horizontalLineTo(20f)
            verticalLineToRelative(-2f)
            close()
        }
    }.build()
}
