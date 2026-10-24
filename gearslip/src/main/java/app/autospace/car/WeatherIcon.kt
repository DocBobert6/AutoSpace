package app.autospace.car

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

private val SUN = Color(0xFFFFB300)
private val MOON = Color(0xFFCFD8DC)
private val CLOUD = Color(0xFF90A4AE)
private val RAIN = Color(0xFF29B6F6)
private val SNOW = Color(0xFF81D4FA)
private val BOLT = Color(0xFFFFD54F)

/** Sun, moon, cloud, rain, snow, storm or fog for a WMO weather code. Drawn, so it needs no icon pack. */
@Composable
fun WeatherIcon(code: Int, isDay: Boolean, size: Dp = 28.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        when (code) {
            0 -> if (isDay) sun(center, this.size.minDimension * 0.5f) else moon(center, this.size.minDimension * 0.5f)
            1, 2 -> {
                if (isDay) sun(Offset(this.size.width * 0.38f, this.size.height * 0.36f), this.size.minDimension * 0.36f)
                else moon(Offset(this.size.width * 0.38f, this.size.height * 0.36f), this.size.minDimension * 0.36f)
                cloud(Offset(this.size.width * 0.06f, this.size.height * 0.34f), Size(this.size.width * 0.9f, this.size.height * 0.6f))
            }
            3 -> cloud(Offset(0f, this.size.height * 0.2f), Size(this.size.width, this.size.height * 0.7f))
            45, 48 -> fog()
            in 71..77, in 85..86 -> precip(SNOW, dots = true)
            in 95..99 -> storm()
            else -> precip(RAIN, dots = false)
        }
    }
}

private fun DrawScope.sun(c: Offset, r: Float) {
    drawCircle(SUN, r * 0.55f, c)
    val stroke = r * 0.14f
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        drawLine(
            SUN, c + Offset(cos(a).toFloat(), sin(a).toFloat()) * (r * 0.75f),
            c + Offset(cos(a).toFloat(), sin(a).toFloat()) * r, stroke, StrokeCap.Round,
        )
    }
}

private fun DrawScope.moon(c: Offset, r: Float) {
    val disc = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r * 0.62f)) }
    val bite = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c + Offset(r * 0.32f, -r * 0.22f), r * 0.55f)) }
    drawPath(Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, disc, bite), MOON)
}

private fun DrawScope.cloud(topLeft: Offset, s: Size) {
    val h = s.height
    drawCircle(CLOUD, h * 0.42f, topLeft + Offset(s.width * 0.3f, h * 0.55f))
    drawCircle(CLOUD, h * 0.55f, topLeft + Offset(s.width * 0.55f, h * 0.42f))
    drawCircle(CLOUD, h * 0.38f, topLeft + Offset(s.width * 0.78f, h * 0.62f))
    drawRoundRect(CLOUD, topLeft + Offset(s.width * 0.28f, h * 0.55f), Size(s.width * 0.52f, h * 0.45f),
        androidx.compose.ui.geometry.CornerRadius(h * 0.2f))
}

private fun DrawScope.precip(color: Color, dots: Boolean) {
    cloud(Offset(0f, 0f), Size(size.width, size.height * 0.62f))
    for (i in 0 until 3) {
        val x = size.width * (0.28f + 0.22f * i)
        val y = size.height * 0.72f
        if (dots) drawCircle(color, size.width * 0.045f, Offset(x, y + size.height * 0.08f))
        else drawLine(color, Offset(x, y), Offset(x - size.width * 0.06f, y + size.height * 0.2f), size.width * 0.07f, StrokeCap.Round)
    }
}

private fun DrawScope.storm() {
    cloud(Offset(0f, 0f), Size(size.width, size.height * 0.62f))
    val w = size.width
    val h = size.height
    val bolt = Path().apply {
        moveTo(w * 0.55f, h * 0.55f); lineTo(w * 0.36f, h * 0.82f); lineTo(w * 0.5f, h * 0.82f)
        lineTo(w * 0.42f, h * 1f); lineTo(w * 0.66f, h * 0.7f); lineTo(w * 0.52f, h * 0.7f); close()
    }
    drawPath(bolt, BOLT)
}

private fun DrawScope.fog() {
    val stroke = Stroke(width = size.height * 0.09f, cap = StrokeCap.Round)
    for (i in 0 until 4) {
        val y = size.height * (0.25f + 0.17f * i)
        val inset = if (i % 2 == 0) 0.1f else 0.2f
        drawLine(CLOUD, Offset(size.width * inset, y), Offset(size.width * (1f - inset), y), stroke.width, stroke.cap)
    }
}
