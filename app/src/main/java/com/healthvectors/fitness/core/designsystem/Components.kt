package com.healthvectors.fitness.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------------------------------------------
// Layout building blocks shared by every screen: cards, grouped lists, section headers, stat tiles and form sheets.

/** The standard content card: a flat, rounded tonal surface. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    padding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = MaterialTheme.shapes.large
    val inner: @Composable () -> Unit = { Column(Modifier.padding(padding), content = content) }
    if (onClick != null) Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, color = color, content = inner)
    else Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = color, content = inner)
}

/** Rows in one rounded group, separated by inset dividers (settings-style lists). */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(content = content)
    }
}

@Composable
fun GroupDivider(inset: Dp = 16.dp) =
    HorizontalDivider(Modifier.padding(start = inset), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

/** Shape for item [i] of [n] when a group is laid out as separate LazyColumn items. */
fun groupItemShape(i: Int, n: Int, radius: Dp = 24.dp): Shape = when {
    n == 1 -> RoundedCornerShape(radius)
    i == 0 -> RoundedCornerShape(topStart = radius, topEnd = radius)
    i == n - 1 -> RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
    else -> RectangleShape
}

/** One item of a grouped list in a LazyColumn: rounded at the ends, divider above all but the first. */
@Composable
fun GroupItem(i: Int, n: Int, dividerInset: Dp = 16.dp, content: @Composable () -> Unit) {
    Column(Modifier.clip(groupItemShape(i, n)).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        if (i > 0) GroupDivider(dividerInset)
        content()
    }
}

/** A small, coloured section label with an optional action on the right. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (action != null && onAction != null) TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(action) }
    }
}

/** A tappable settings or menu row. */
@Composable
fun SettingRow(
    title: String, modifier: Modifier = Modifier, subtitle: String? = null, icon: ImageVector? = null,
    onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
fun SwitchSetting(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, icon: ImageVector? = null) {
    SettingRow(title, subtitle = subtitle, icon = icon, onClick = { onChange(!checked) }) { Switch(checked, onChange) }
}

/** A labelled control inside a group (a segmented row, slider or field under a heading). */
@Composable
fun GroupField(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

/** A number with a caption, for summary rows. */
@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, icon: ImageVector? = null, tint: Color = MaterialTheme.colorScheme.primary) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (icon != null) Icon(icon, null, Modifier.size(20.dp).padding(bottom = 2.dp), tint = tint)
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** An icon in a tinted circle, used as the leading visual of cards and rows. */
@Composable
fun IconBadge(
    icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 40.dp,
    container: Color = MaterialTheme.colorScheme.primaryContainer, content: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    Box(modifier.size(size).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.5f), tint = content)
    }
}

/** A small rounded label ("Today", "Current", "PR"). */
@Composable
fun Pill(
    text: String, modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.primary, content: Color = MaterialTheme.colorScheme.onPrimary
) {
    Text(
        text, modifier.clip(CircleShape).background(container).padding(horizontal = 10.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1
    )
}

/** A big tappable tile with an icon, title and caption (quick actions and tools). */
@Composable
fun ActionTile(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 6.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconBadge(icon, Modifier.padding(bottom = 8.dp), size = 72.dp)
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) Box(Modifier.padding(top = 12.dp)) { action() }
    }
}

/**
 * A bottom sheet holding a form: title, optional explanation, fields, and one full-width primary button.
 * Used instead of dialogs for anything with more than one field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormSheet(
    title: String, onDismiss: () -> Unit, primaryLabel: String, onPrimary: () -> Unit,
    subtitle: String? = null, primaryEnabled: Boolean = true, content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.statusBarsPadding()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
            Button(onClick = onPrimary, enabled = primaryEnabled, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp)) { Text(primaryLabel) }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Inputs

/** Numeric text field; [decimal] allows a decimal point. */
@Composable
fun NumField(
    value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, decimal: Boolean = true, suffix: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> if (numOk(v, decimal)) onValueChange(v.replace(',', '.')) },
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier
    )
}

fun numOk(v: String, decimal: Boolean) =
    v.isEmpty() || (if (decimal) v.matches(Regex("\\d{0,4}([.,]\\d{0,2})?")) else v.matches(Regex("\\d{0,5}")))

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

// ---------------------------------------------------------------------------------------------------------------
// Nutrition visuals

/** One colour per macro, used everywhere macros appear. */
object MacroColors {
    val protein @Composable get() = MaterialTheme.colorScheme.primary
    val carbs @Composable get() = MaterialTheme.colorScheme.tertiary
    val fat @Composable get() = MaterialTheme.colorScheme.secondary
    @Composable fun of(name: String) = when (name) { "Protein" -> protein; "Carbs" -> carbs; else -> fat }
}

@Composable
fun MacroBar(name: String, value: Double, target: Double, modifier: Modifier = Modifier) {
    val color = MacroColors.of(name)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text("${value.toInt()}", style = MaterialTheme.typography.labelLarge)
            Text(" / ${target.toInt()} g", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { if (target > 0) (value / target).coerceIn(0.0, 1.0).toFloat() else 0f },
            Modifier.fillMaxWidth().height(8.dp), color = color, trackColor = color.copy(alpha = 0.18f),
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {}
        )
    }
}
