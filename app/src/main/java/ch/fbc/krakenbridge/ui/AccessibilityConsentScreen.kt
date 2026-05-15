package ch.fbc.krakenbridge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Prominent-disclosure consent gate for the AccessibilityService, per Google
 * Play User Data Policy. Renders as a full-screen page (not a modal dialog)
 * so reviewers cannot miss it regardless of how they reach the AccessibilityService
 * — including enabling it directly from Android Settings → Accessibility
 * without ever tapping the Camera toggle in our UI.
 *
 * Two affirmative-action buttons:
 *   • "I agree"            → onAccept (persisted, screen never shown again)
 *   • "Decline and exit"   → onDecline (closes the app)
 *
 * There is intentionally no Back / outside / Home dismissal handler — the
 * gate sits at the root of the Activity, so back press leaves the app
 * entirely (which is *not* interpreted as consent). The disclosure does
 * not auto-dismiss; it sits indefinitely until the user picks one button.
 */
@Composable
fun AccessibilityConsentScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.Start
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Accessibility access",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Please read carefully before continuing.",
                fontSize = 14.sp,
                color = OceanTextMuted
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "What Kraken Dive Photo does",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Kraken Dive Photo uses Android's Accessibility Service to " +
                    "translate the Bluetooth button presses from your Kraken dive " +
                    "housing into taps, swipes, and system actions for your phone's " +
                    "camera and gallery apps while the phone is sealed inside the " +
                    "housing underwater.",
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "If you agree, the service will be able to:",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            BulletText(
                "Read on-screen content of the foreground camera or gallery app to " +
                    "locate buttons such as the shutter, mode switch, delete, and " +
                    "swipe targets."
            )
            BulletText(
                "Perform taps, swipes, and system actions (Back, Home) on your behalf " +
                    "in response to your housing's button presses."
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "What Kraken Dive Photo does NOT do",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            BulletText(
                "It does not collect, store, log, transmit, or share any screen " +
                    "content or personal data. Everything stays on this device."
            )
            BulletText("It does not record audio or capture screenshots.")
            BulletText(
                "It does not interact with apps outside your active camera or " +
                    "gallery session."
            )
            BulletText(
                "It does not change system settings or interact with other apps " +
                    "in the background."
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "How to revoke",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "You can revoke this access at any time from Android " +
                    "Settings → Accessibility, or by turning the Accessibility row " +
                    "off inside this app.",
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(36.dp))

            // Two equally-weighted action buttons. Each has a distinct filled
            // background AND a visible outline so the two consent options
            // read as equally affirmative (per Play policy: two clear
            // consent options, not a single primary CTA with a faded
            // dismiss). Neither button auto-acts on a timer and the screen
            // has no dismiss path other than these two — back press exits
            // the app, which we explicitly do NOT treat as consent.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onDecline,
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(min = 140.dp),
                    shape = RoundedCornerShape(percent = 50),
                    border = BorderStroke(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                    ),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text(
                        text = "Decline and exit",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Button(
                    onClick = onAccept,
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(min = 140.dp),
                    shape = RoundedCornerShape(percent = 50),
                    border = BorderStroke(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.55f)
                    ),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(
                        text = "I agree",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BulletText(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "•",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.width(20.dp)
        )
        Text(
            text = text,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}
