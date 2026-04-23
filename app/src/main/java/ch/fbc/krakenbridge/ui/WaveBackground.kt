package ch.fbc.krakenbridge.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import kotlin.math.sin

private data class WaveLayer(
    val baseYFraction: Float,
    val amplitude: Float,
    val wavelength: Float,
    val periodMs: Int,
    val color: Color,
    val reversed: Boolean
)

@Composable
fun WaveBackground(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "waves")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    val layers = listOf(
        WaveLayer(
            baseYFraction = 0.72f,
            amplitude = 28f,
            wavelength = 520f,
            periodMs = 12_000,
            color = OceanAquaLight.copy(alpha = 0.10f),
            reversed = false
        ),
        WaveLayer(
            baseYFraction = 0.80f,
            amplitude = 22f,
            wavelength = 380f,
            periodMs = 9_000,
            color = OceanAqua.copy(alpha = 0.18f),
            reversed = true
        ),
        WaveLayer(
            baseYFraction = 0.88f,
            amplitude = 18f,
            wavelength = 260f,
            periodMs = 7_000,
            color = OceanSurface.copy(alpha = 0.55f),
            reversed = false
        )
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(OceanSurface, OceanDeep)
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            layers.forEach { layer ->
                val baseY = h * layer.baseYFraction
                val direction = if (layer.reversed) -1f else 1f
                val path = Path().apply {
                    moveTo(0f, h)
                    lineTo(0f, baseY)
                    var x = 0f
                    while (x <= w) {
                        val y = baseY + layer.amplitude *
                            sin((x / layer.wavelength) * 2f * PI.toFloat() + direction * phase)
                        lineTo(x, y)
                        x += 4f
                    }
                    lineTo(w, h)
                    close()
                }
                drawPath(path = path, color = layer.color)
            }
        }
    }
}
