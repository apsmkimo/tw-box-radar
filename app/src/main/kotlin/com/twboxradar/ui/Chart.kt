package com.twboxradar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import com.twboxradar.core.Bar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val Up = Color(0xFFC62828)
private val Down = Color(0xFF2E7D32)
private val BoxColor = Color(0xFF1565C0)

@Composable
fun CandleChart(
    bars: List<Bar>,
    top: Double?,
    bottom: Double?,
    modifier: Modifier = Modifier,
) {
    if (bars.isEmpty()) {
        Text("沒有可繪製的 K 線", modifier = modifier.padding(16.dp))
        return
    }
    var count by rememberSaveable(bars.size) { mutableIntStateOf(min(80, bars.size)) }
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(320.dp),
        ) {
            val show = bars.takeLast(count.coerceIn(20, bars.size))
            val volumeHeight = size.height * 0.22f
            val priceHeight = size.height - volumeHeight - 12f
            var maxPrice = show.maxOf { it.high }
            var minPrice = show.minOf { it.low }
            if (top != null) maxPrice = max(maxPrice, top)
            if (bottom != null) minPrice = min(minPrice, bottom)
            val span = (maxPrice - minPrice).takeIf { it > 0.0 } ?: 1.0
            fun y(price: Double): Float = ((maxPrice - price) / span).toFloat() * priceHeight
            val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
            if (top != null) {
                drawLine(BoxColor, Offset(0f, y(top)), Offset(size.width, y(top)), 2.5f, pathEffect = dash)
            }
            if (bottom != null) {
                drawLine(BoxColor, Offset(0f, y(bottom)), Offset(size.width, y(bottom)), 2.5f, pathEffect = dash)
            }
            val slot = size.width / show.size
            val bodyWidth = slot * 0.62f
            val maxVolume = show.maxOf { it.volume }.coerceAtLeast(1L)
            show.forEachIndexed { index, bar ->
                val center = slot * index + slot / 2f
                val rising = bar.close >= bar.open
                val color = if (rising) Up else Down
                drawLine(color, Offset(center, y(bar.high)), Offset(center, y(bar.low)), 2f)
                val openY = y(bar.open)
                val closeY = y(bar.close)
                drawRect(
                    color,
                    Offset(center - bodyWidth / 2f, min(openY, closeY)),
                    Size(bodyWidth, max(abs(closeY - openY), 1.5f)),
                )
                val volumePixels = bar.volume.toFloat() / maxVolume * volumeHeight
                drawRect(
                    color.copy(alpha = 0.4f),
                    Offset(center - bodyWidth / 2f, size.height - volumePixels),
                    Size(bodyWidth, volumePixels),
                )
            }
        }
        Text(
            "藍線為箱頂與箱底，下方為成交量。紅漲綠跌。顯示 ${count.coerceAtMost(bars.size)} 根",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (bars.size > 20) {
            Slider(
                value = count.coerceIn(20, bars.size).toFloat(),
                onValueChange = { count = it.toInt().coerceIn(20, bars.size) },
                valueRange = 20f..bars.size.toFloat(),
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}
