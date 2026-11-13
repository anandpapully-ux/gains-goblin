package com.healthvectors.fitness.core.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium)
}

/** Numeric text field; [decimal] allows a decimal point. */
@Composable
fun NumField(
    value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, decimal: Boolean = true, suffix: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v ->
            val ok = v.isEmpty() || (if (decimal) v.matches(Regex("\\d{0,4}([.,]\\d{0,2})?")) else v.matches(Regex("\\d{0,5}")))
            if (ok) onValueChange(v.replace(',', '.'))
        },
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier
    )
}

@Composable
fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                selected = o == selected, onClick = { onSelect(o) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size)
            ) { Text(label(o), maxLines = 1) }
        }
    }
}

@Composable
fun MacroBar(name: String, value: Double, target: Double, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, style = MaterialTheme.typography.labelMedium)
            Text("${value.toInt()} / ${target.toInt()} g", style = MaterialTheme.typography.labelMedium)
        }
        LinearProgressIndicator(
            progress = { if (target > 0) (value / target).coerceIn(0.0, 1.0).toFloat() else 0f },
            Modifier.fillMaxWidth().padding(top = 2.dp)
        )
    }
}
