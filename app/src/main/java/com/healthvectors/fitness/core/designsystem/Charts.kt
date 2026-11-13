package com.healthvectors.fitness.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.healthvectors.fitness.core.util.Units

data class ChartPoint(val label: String, val value: Double)

/** Simple line chart drawn on a Canvas (no third-party chart library, keeps the app small and offline). */
@Composable
fun LineChart(points: List<ChartPoint>, modifier: Modifier = Modifier, description: String) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    if (points.size < 2) {
        Box(modifier.height(160.dp), contentAlignment = Alignment.Center) {
            Text("Log at least two entries to see a trend", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val min = points.minOf { it.value }
    val max = points.maxOf { it.value }
    val range = (max - min).takeIf { it > 1e-9 } ?: 1.0
    Column(modifier.semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            val pad = 8.dp.toPx()
            val w = size.width - 2 * pad
            val h = size.height - 2 * pad
            for (i in 0..3) {
                val y = pad + h * i / 3
                drawLine(grid, Offset(pad, y), Offset(size.width - pad, y), 1.dp.toPx())
            }
            val path = Path()
            points.forEachIndexed { i, p ->
                val x = pad + w * i / (points.size - 1)
                val y = pad + h * (1 - ((p.value - min) / range)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, line, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            points.forEachIndexed { i, p ->
                val x = pad + w * i / (points.size - 1)
                val y = pad + h * (1 - ((p.value - min) / range)).toFloat()
                drawCircle(line, 4.dp.toPx(), Offset(x, y))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(points.first().label, style = MaterialTheme.typography.labelSmall)
            Text("${Units.fmt(min)} - ${Units.fmt(max)}", style = MaterialTheme.typography.labelSmall)
            Text(points.last().label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun BarChart(bars: List<ChartPoint>, modifier: Modifier = Modifier, description: String) {
    val color = MaterialTheme.colorScheme.primary
    val max = (bars.maxOfOrNull { it.value } ?: 0.0).takeIf { it > 0 } ?: 1.0
    Column(modifier.semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            if (bars.isEmpty()) return@Canvas
            val slot = size.width / bars.size
            val barW = slot * 0.6f
            bars.forEachIndexed { i, b ->
                val h = (size.height * (b.value / max)).toFloat()
                drawRect(color, Offset(slot * i + (slot - barW) / 2, size.height - h), Size(barW, h))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            bars.forEach { b ->
                Text(b.label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/** Circular progress ring used for the daily calorie summary. */
@Composable
fun CalorieRing(consumed: Double, target: Double, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val over = consumed > target
    val color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val frac = if (target > 0) (consumed / target).coerceIn(0.0, 1.0).toFloat() else 0f
    Box(
        modifier.size(150.dp).semantics {
            contentDescription = "${consumed.toInt()} of ${target.toInt()} calories eaten"
        },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2
            val sz = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(stroke))
            drawArc(color, -90f, 360f * frac, false, Offset(inset, inset), sz, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${consumed.toInt()}", style = MaterialTheme.typography.headlineMedium)
            Text("/ ${target.toInt()} kcal", style = MaterialTheme.typography.labelMedium)
        }
    }
}
