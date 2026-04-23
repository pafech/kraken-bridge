package ch.fbc.krakenbridge.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val OceanSurface = Color(0xFF0B3C5D)
val OceanDeep = Color(0xFF061A2A)
val OceanCard = Color(0xFF0E4461)
val OceanAqua = Color(0xFF4FC3F7)
val OceanAquaLight = Color(0xFF81D4FA)
val OceanText = Color(0xFFE1F5FE)

@Composable
fun KrakenBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = OceanAqua,
            secondary = OceanAquaLight,
            background = OceanDeep,
            surface = OceanCard,
            onPrimary = OceanDeep,
            onSecondary = OceanDeep,
            onBackground = OceanText,
            onSurface = OceanText
        ),
        content = content
    )
}
