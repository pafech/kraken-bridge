package ch.fbc.krakenbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

// D-shape — flat side hugs the screen edge, the two inner corners round
// to a perfect half-circle (50% radius like CSS border-radius: 50%) so a
// circular icon nests inside snugly.
private val leftHandleShape = RoundedCornerShape(
    topStartPercent = 0,
    topEndPercent = 50,
    bottomEndPercent = 50,
    bottomStartPercent = 0
)

private val rightHandleShape = RoundedCornerShape(
    topStartPercent = 50,
    topEndPercent = 0,
    bottomEndPercent = 0,
    bottomStartPercent = 50
)

/** Pager navigation handle pinned to the vertical centre of a screen edge. */
@Composable
fun BoxScope.EdgeHandle(
    onLeft: Boolean,
    icon: ImageVector,
    onClick: () -> Unit
) {
    val shape = if (onLeft) leftHandleShape else rightHandleShape
    val tint = MaterialTheme.colorScheme.onBackground
    Box(
        modifier = Modifier
            .align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd)
            .size(52.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.10f))
            .border(width = 1.dp, color = tint.copy(alpha = 0.18f), shape = shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint.copy(alpha = 0.85f),
            modifier = Modifier.size(26.dp)
        )
    }
}
