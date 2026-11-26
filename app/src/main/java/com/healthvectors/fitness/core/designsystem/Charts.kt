package com.healthvectors.fitness.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.healthvectors.fitness.core.util.Units

data class ChartPoint(val label: String, val value: Double)

/** Line chart drawn on a Canvas (no third-party chart library, keeps the app small and offline). */
@Composable
fun LineChart(points: List<ChartPoint>, modifier: Modifier = Modifier, description: String, emptyText: String = "Log at least two entries to see a trend") {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    if (points.size < 2) {
        Box(modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
            Text(emptyText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        return
    }
    val min = points.minOf { it.value }
    val max = points.maxOf { it.value }
    val range = (max - min).takeIf { it > 1e-9 } ?: 1.0
    Column(modifier.semantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Canvas(Modifier.weight(1f).height(160.dp)) {
                val pad = 8.dp.toPx()
                val w = size.width - 2 * pad
                val h = size.height - 2 * pad
                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                for (i in 0..2) {
                    val y = pad + h * i / 2
                    drawLine(grid, Offset(pad, y), Offset(size.width - pad, y), 1.dp.toPx(), pathEffect = dash)
                }
                fun at(i: Int) = Offset(pad + w * i / (points.size - 1), pad + h * (1 - ((points[i].value - min) / range)).toFloat())
                val path = Path().apply { points.indices.forEach { i -> at(i).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } } }
                val fill = Path().apply {
                    addPath(path); lineTo(at(points.lastIndex).x, size.height - pad); lineTo(at(0).x, size.height - pad); close()
                }
                drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.25f), line.copy(alpha = 0f))))
                drawPath(path, line, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                val last = at(points.lastIndex)
                drawCircle(line.copy(alpha = 0.25f), 9.dp.toPx(), last)
                drawCircle(line, 5.dp.toPx(), last)
            }
            Column(Modifier.height(160.dp).padding(start = 8.dp, top = 2.dp, bottom = 2.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(Units.fmt(max), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Units.fmt(min), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(points.first().label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(points.last().label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Bar chart with rounded bars; the last bar (this week, today) is drawn in full colour. */
@Composable
fun BarChart(bars: List<ChartPoint>, modifier: Modifier = Modifier, description: String) {
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val max = (bars.maxOfOrNull { it.value } ?: 0.0).takeIf { it > 0 } ?: 1.0
    Column(modifier.semantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            if (bars.isEmpty()) return@Canvas
            val slot = size.width / bars.size
            val barW = (slot * 0.56f).coerceAtMost(36.dp.toPx())
            val r = CornerRadius(barW / 2, barW / 2)
            bars.forEachIndexed { i, b ->
                val x = slot * i + (slot - barW) / 2
                drawRoundRect(track, Offset(x, 0f), Size(barW, size.height), r)
                val h = (size.height * (b.value / max)).toFloat()
                if (h > 0) drawRoundRect(
                    if (i == bars.lastIndex) color else color.copy(alpha = 0.45f),
                    Offset(x, size.height - h.coerceAtLeast(barW)), Size(barW, h.coerceAtLeast(barW)), r
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            bars.forEach { b ->
                Text(
                    b.label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Circular progress ring for the day's calories, with what is left in the middle. */
@Composable
fun CalorieRing(consumed: Double, target: Double, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 140.dp) {
    val over = target > 0 && consumed > target
    val color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val track = color.copy(alpha = 0.15f)
    val frac = if (target > 0) (consumed / target).coerceIn(0.0, 1.0).toFloat() else 0f
    val left = (target - consumed).toInt()
    Box(
        modifier.size(size).semantics { contentDescription = "${consumed.toInt()} of ${target.toInt()} calories eaten" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2
            val sz = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(stroke))
            if (frac > 0) drawArc(color, -90f, 360f * frac, false, Offset(inset, inset), sz, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Units.thousands(if (over) -left else left), style = MaterialTheme.typography.headlineMedium, color = if (over) color else MaterialTheme.colorScheme.onSurface)
            Text(if (over) "kcal over" else "kcal left", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
