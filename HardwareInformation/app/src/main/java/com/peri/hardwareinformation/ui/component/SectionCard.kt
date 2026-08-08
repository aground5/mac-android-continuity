package com.peri.hardwareinformation.ui.component

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.peri.hardwareinformation.R
import com.peri.hardwareinformation.data.model.Section

/**
 * A section is the only grouping primitive. Categories with many repeated entities (sensors,
 * cameras, CPU cores) emit many collapsed sections rather than a bespoke table, which is why the
 * detail pane needs no special cases.
 *
 * Grouping is carried by the card and by space alone — rows have no dividers. The value column
 * already lines the rows up, and thirty sensors' worth of hairlines is noise, not structure.
 */
@Composable
fun SectionCard(
    section: Section,
    expanded: Boolean,
    /** Null for sections the collector declared always-open, so the chevron never lies. */
    onToggle: (() -> Unit)?,
    onCopy: (label: String, value: String) -> Unit,
    /** Label of the one row to flash, when this card is the target of a search jump. */
    highlightedLabel: String? = null,
    rawValues: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            // A step above the page in both light and dark, so the card reads as a group at a
            // glance rather than dissolving into the background.
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        BoxWithConstraints {
            // Capped fraction, not a bare fraction: see InfoRow's header for the reasoning.
            val labelWidth = minOf(maxWidth * LABEL_FRACTION, LABEL_MAX_WIDTH)
            Column(Modifier.animateContentSize()) {
                SectionHeader(
                    title = section.title,
                    itemCount = section.items.size,
                    expanded = expanded,
                    onToggle = onToggle,
                )
                if (expanded) {
                    section.items.forEach { item ->
                        InfoRow(
                            item = item,
                            labelWidth = labelWidth,
                            onCopy = onCopy,
                            highlighted = item.label == highlightedLabel,
                            rawValues = rawValues,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    itemCount: Int,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
) {
    val base = if (onToggle == null) Modifier else Modifier.clickable(onClick = onToggle)
    Row(
        base
            .fillMaxWidth()
            .padding(
                start = 16.dp,
                end = 12.dp,
                top = 12.dp,
                bottom = if (expanded) 6.dp else 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            // Bold is the only weight that separates a heading from its rows here; colouring every
            // header with the accent turned the page into noise and stole the accent's meaning.
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = true),
        )
        if (onToggle != null) {
            if (!expanded) {
                Text(
                    text = itemCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.action_collapse else R.string.action_expand,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
            )
        }
    }
}

private const val LABEL_FRACTION = 0.4f
private val LABEL_MAX_WIDTH = 200.dp
