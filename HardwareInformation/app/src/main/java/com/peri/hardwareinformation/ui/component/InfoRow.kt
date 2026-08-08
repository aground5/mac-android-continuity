package com.peri.hardwareinformation.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.peri.hardwareinformation.R
import com.peri.hardwareinformation.data.model.Item
import com.peri.hardwareinformation.data.model.UnavailableReason
import com.peri.hardwareinformation.data.model.Value
import kotlinx.coroutines.delay

/**
 * The entire UI vocabulary: five value kinds, one row anatomy. There is deliberately no
 * per-category layout anywhere in the app, which is what keeps every screen predictable.
 *
 * Two layout decisions carry the readability of the whole app:
 *
 * 1. The value is **left-aligned in a column**, so values form a straight vertical line the eye can
 *    run down. An earlier version spread label and value to opposite edges; that reads fine at 360dp
 *    but at 640dp it leaves the values ragged and unscannable.
 * 2. The label column is [labelWidth], computed by [SectionCard] as `min(40% of width, 200dp)`.
 *    A pure fraction opens a needless gulf between a short label and its value on a wide window —
 *    space should bind a label to its value, not separate them — while a fixed dp starves the value
 *    column on a narrow one. Capping the fraction satisfies both without a width branch.
 *
 * Every row copies on tap or long-press. Uniform behaviour is the point: rows that look identical
 * must act identically, so there is no such thing here as a row that looks tappable and isn't.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InfoRow(
    item: Item,
    labelWidth: Dp,
    onCopy: (label: String, value: String) -> Unit,
    /** Flashes the row once, to land the eye after a jump in from search. */
    highlighted: Boolean = false,
    /** Show each value's machine form instead of the readable one, where a distinct one exists. */
    rawValues: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val copyText = when (val value = item.value) {
        is Value.Text -> value.shown(rawValues)
        is Value.LongText -> value.text
        is Value.Tokens -> value.shown(rawValues).joinToString(", ")
        is Value.Flag -> if (rawValues) value.value.toString() else stringResource(value.labelRes())
        is Value.Metric -> value.shownSummary(rawValues)
        is Value.Unavailable -> stringResource(value.reason.labelRes())
    }
    val copy = { onCopy(item.label, copyText) }

    val base = modifier
        .fillMaxWidth()
        .combinedClickable(onClick = copy, onLongClick = copy)
        .background(flashColour(highlighted))
        .padding(horizontal = 16.dp, vertical = 7.dp)

    when (val value = item.value) {
        // A fingerprint has no business in a 60% column.
        is Value.LongText -> LongTextRow(base, item.label, value.text)
        is Value.Text -> SplitRow(base, item.label, labelWidth) { ValueText(value.shown(rawValues)) }
        is Value.Tokens -> SplitRow(base, item.label, labelWidth) {
            TokenPills(value.shown(rawValues))
        }

        is Value.Flag -> SplitRow(base, item.label, labelWidth) {
            // In raw mode the literal booleans are the point — code, not prose.
            ValueText(if (rawValues) value.value.toString() else stringResource(value.labelRes()))
        }

        is Value.Metric -> SplitRow(base, item.label, labelWidth) {
            MetricValue(value, rawValues)
        }
        is Value.Unavailable -> SplitRow(base, item.label, labelWidth) {
            Text(
                text = stringResource(value.reason.labelRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The one row anatomy. See the file header for why the value column is left-aligned and capped. */
@Composable
private fun SplitRow(
    modifier: Modifier,
    label: String,
    labelWidth: Dp,
    value: @Composable () -> Unit,
) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .width(labelWidth)
                .padding(end = 12.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            value()
        }
    }
}

@Composable
private fun ValueText(text: String) = Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurface,
)

@Composable
private fun LongTextRow(modifier: Modifier, label: String, text: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            // Slash-delimited identifiers are read character by character, not as prose.
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(
                if (expanded) R.string.action_show_less else R.string.action_show_more,
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            // Underlined, not just coloured: colour alone is invisible to many colour-blind users.
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TokenPills(tokens: List<String>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tokens.forEach { token ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.extraSmall,
            ) {
                Text(
                    text = token,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun MetricValue(metric: Value.Metric, rawValues: Boolean) {
    ValueText(metric.shownSummary(rawValues))
    LinearProgressIndicator(
        progress = { metric.used.toFloat() / metric.total.toFloat() },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun Value.Text.shown(raw: Boolean): String = if (raw) this.raw ?: text else text

private fun Value.Tokens.shown(raw: Boolean): List<String> = if (raw) this.raw ?: tokens else tokens

private fun Value.Metric.shownSummary(raw: Boolean): String =
    if (raw) rawSummary ?: summary else summary

/**
 * Snaps to the accent tint, holds, then fades out. A single pulse rather than a persistent
 * background: the point is to catch the eye once, not to leave the row looking selected.
 */
@Composable
private fun flashColour(highlighted: Boolean): Color {
    var lit by remember { mutableStateOf(false) }
    LaunchedEffect(highlighted) {
        if (!highlighted) return@LaunchedEffect
        lit = true
        delay(FLASH_HOLD_MILLIS)
        lit = false
    }
    val colour by animateColorAsState(
        targetValue = if (lit) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        animationSpec = tween(if (lit) FLASH_IN_MILLIS else FLASH_OUT_MILLIS),
        label = "rowFlash",
    )
    return colour
}

private fun Value.Flag.labelRes(): Int =
    if (value) R.string.value_yes else R.string.value_no

private fun UnavailableReason.labelRes(): Int = when (this) {
    UnavailableReason.NEEDS_PERMISSION -> R.string.unavailable_needs_permission
    UnavailableReason.OS_RESTRICTED -> R.string.unavailable_os_restricted
    UnavailableReason.NOT_SUPPORTED -> R.string.unavailable_not_supported
    UnavailableReason.READ_FAILED -> R.string.unavailable_read_failed
}

private const val COLLAPSED_LINES = 3
private const val FLASH_IN_MILLIS = 120
private const val FLASH_HOLD_MILLIS = 850L
private const val FLASH_OUT_MILLIS = 650
