package ch.fbc.krakenbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
 * Two equally-prominent buttons (see ConsentActionButtons):
 *   • "I agree" → onAccept (persisted, screen never shown again)
 *   • "Decline" → onDecline (dismisses the gate; the app stays open and
 *                 usable, the AccessibilityService simply stays off)
 *
 * There is intentionally no Back / outside / Home dismissal handler — the
 * gate sits at the root of the Activity, so a back press leaves the app
 * entirely, which is *not* interpreted as consent. The disclosure does not
 * auto-dismiss; it sits indefinitely until the user picks one button.
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
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.Start
        ) {
            // Same brand header as every other screen — carries the
            // statusBarsPadding() so content clears the system bars.
            AppHeader(modifier = Modifier.fillMaxWidth())

            Column(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "Accessibility access",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Kraken Dive Photo uses Android's Accessibility Service to " +
                        "turn your Kraken housing's Bluetooth button presses into taps, " +
                        "swipes, and system actions for your camera and gallery apps " +
                        "while the phone is sealed in the housing underwater.",
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "If you agree, it will:",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(6.dp))
                BulletText(
                    "Read the screen of the active camera or gallery app to find its " +
                        "buttons (shutter, mode, delete, swipe)."
                )
                BulletText("Perform taps, swipes, and Back/Home on your behalf.")

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "It will not:",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(6.dp))
                BulletText(
                    "Collect, store, or transmit any screen content or data — everything " +
                        "stays on this device."
                )
                BulletText("Record audio or screenshots, or interact with any other app.")

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Revoke this anytime in Android Settings → Accessibility, or " +
                        "with the Accessibility row in this app.",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = OceanTextMuted
                )

                Spacer(modifier = Modifier.height(28.dp))

                // Two equally-prominent consent buttons. Decline does not exit the
                // app — it dismisses the gate so the app stays usable with the
                // AccessibilityService simply left off; the disclosure re-appears on
                // the next launch (and whenever the user tries to enable the service)
                // until consent is given. Back press still leaves the app, which we
                // do NOT treat as consent.
                ConsentActionButtons(
                    onAccept = onAccept,
                    onDecline = onDecline
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
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
