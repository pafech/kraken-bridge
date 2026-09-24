package ch.fbc.krakenbridge.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties

/**
 * In-flow prominent disclosure, shown right before the app opens the system
 * Accessibility settings (Google Play User Data Policy). Back and tap-outside
 * do not dismiss it; any dismissal counts as a decline.
 */
@Composable
fun AccessibilityDisclosureDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDecline,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        ),
        // Default textContentColor is onSurfaceVariant, which our
        // darkColorScheme doesn't define — Material 3 falls back to a
        // low-contrast grey on the dark surface. Pin title + body to
        // onSurface (OceanText, ~12:1 on OceanCard) so the disclosure
        // text is fully legible.
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text("Allow Accessibility access?") },
        text = {
            Text(
                "Kraken Dive Photo uses Android's Accessibility Service to translate " +
                    "your housing's Bluetooth button presses into taps and swipes in " +
                    "your phone's camera and gallery apps while you're diving.\n\n" +
                    "If you allow it, the service will be able to:\n" +
                    "• Read on-screen content of the foreground camera or gallery app " +
                    "to locate buttons (shutter, mode switch, delete, swipe targets).\n" +
                    "• Perform taps and swipes on your behalf.\n\n" +
                    "What Kraken Dive Photo does NOT do:\n" +
                    "• It does not collect, store, log, or transmit any screen content " +
                    "or personal data. Everything stays on this device.\n" +
                    "• It does not record audio or capture screenshots.\n" +
                    "• It does not interact with apps outside your active camera or " +
                    "gallery session.\n\n" +
                    "You can revoke this access at any time from Android Settings → " +
                    "Accessibility, or by turning the Accessibility row off in this app."
            )
        },
        // Both consent options live in the confirmButton slot as one
        // full-width row of two equally-prominent buttons (see
        // ConsentActionButtons). dismissButton is intentionally omitted so
        // Material doesn't render a third, lower-emphasis control — the only
        // negative option is the equally-weighted "Decline" button.
        confirmButton = {
            ConsentActionButtons(
                onAccept = onAccept,
                onDecline = onDecline,
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}
