package ch.fbc.krakenbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The two consent buttons for the AccessibilityService prominent disclosure,
 * shared by the full-screen gate (AccessibilityConsentScreen) and the in-flow
 * dialog (AccessibilityDisclosureDialog) so both surfaces present an identical,
 * policy-compliant choice.
 *
 * Google Play's prominent-disclosure policy requires two clear options — one to
 * grant consent, one to decline. The v141 rejection was triggered by a decline
 * option that read as "no choice offered" because it was visually too faint.
 * Both buttons here are therefore *filled* and equally sized: the decline button
 * is never a ghost outline or low-emphasis text button. They differ only in
 * colour and label, never in visual weight, so neither option can be mistaken
 * for the only one on offer.
 *
 * Dismissing the surface (back press / tap-away) is the caller's concern and is
 * deliberately not wired here — navigating away must never count as consent.
 */
@Composable
fun ConsentActionButtons(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    acceptLabel: String = "I agree",
    declineLabel: String = "Decline"
) {
    val pillShape = RoundedCornerShape(percent = 50)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onDecline,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp),
            shape = pillShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onBackground,
                contentColor = MaterialTheme.colorScheme.background
            )
        ) {
            Text(text = declineLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Button(
            onClick = onAccept,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp),
            shape = pillShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(text = acceptLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
