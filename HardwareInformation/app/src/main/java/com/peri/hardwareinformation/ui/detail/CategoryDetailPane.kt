package com.peri.hardwareinformation.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.peri.hardwareinformation.R
import com.peri.hardwareinformation.data.model.CategoryRef
import com.peri.hardwareinformation.data.model.Section
import com.peri.hardwareinformation.ui.CategoryState
import com.peri.hardwareinformation.ui.HighlightTarget
import com.peri.hardwareinformation.ui.component.SectionCard

/**
 * Sections flow into one column on a phone and two once the pane can afford [TWO_COLUMN_MIN_WIDTH],
 * capped at [CONTENT_MAX_WIDTH] overall.
 *
 * Two columns rather than one very wide one: a label/value table only ever fills the left part of
 * its row, so stretching a single column across a tablet leaves the card mostly empty and the whole
 * page marooned in grey. Splitting into columns of about 490dp keeps every value close to its label
 * and puts the width to work.
 *
 * The split is positional — sections in order, two at a time — never masonry. A shortest-column
 * packing would reorder sections as the window resizes, which is the one thing this app must not do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryDetailPane(
    ref: CategoryRef?,
    state: CategoryState?,
    showBack: Boolean,
    highlight: HighlightTarget?,
    rawValues: Boolean,
    onToggleRawValues: () -> Unit,
    onBack: () -> Unit,
    onCopy: (label: String, value: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = ref?.title ?: stringResource(R.string.app_name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            Icon(
                                // AutoMirrored: the arrow has to flip in right-to-left locales.
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                },
                actions = {
                    // Global, not per-category: half-raw pages would defeat the consistency the
                    // mode exists for.
                    IconToggleButton(checked = rawValues, onCheckedChange = { onToggleRawValues() }) {
                        Icon(
                            imageVector = Icons.Filled.Code,
                            contentDescription = stringResource(R.string.action_raw_values),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            contentAlignment = Alignment.TopCenter,
        ) {
            val bottom = padding.calculateBottomPadding()
            when (state) {
                null -> Message(stringResource(R.string.detail_empty))
                is CategoryState.Loading -> SkeletonList(bottom)
                is CategoryState.Failed -> Message(state.message)
                is CategoryState.Ready -> SectionList(
                    sections = state.sections,
                    // Collapse state belongs to the category, so switching categories resets it.
                    categoryId = ref?.id.orEmpty(),
                    bottomPadding = bottom,
                    highlight = highlight,
                    rawValues = rawValues,
                    onCopy = onCopy,
                )
            }
        }
    }
}

@Composable
private fun SectionList(
    sections: List<Section>,
    categoryId: String,
    bottomPadding: Dp,
    highlight: HighlightTarget?,
    rawValues: Boolean,
    onCopy: (String, String) -> Unit,
) {
    // Indices whose expansion differs from the section's default. Held as an immutable Set of Int
    // rather than a SnapshotStateMap because rememberSaveable's default saver can only put
    // Bundle-storable values away, and a snapshot map is not one.
    //
    // Keyed by index, not title: two sensors can share a name, and a title collision would
    // otherwise make both sections expand together.
    var toggled by rememberSaveable(categoryId) { mutableStateOf(emptySet<Int>()) }
    val listState = rememberLazyListState()

    BoxWithConstraints(
        Modifier
            .widthIn(max = CONTENT_MAX_WIDTH)
            .fillMaxHeight(),
    ) {
        val columns = if (maxWidth >= TWO_COLUMN_MIN_WIDTH) 2 else 1
        val rows = remember(sections, columns) { packSections(sections, columns) }

        // Arriving from a search result: open the section if it was collapsed, then bring it into
        // view. Keyed on the target's nonce so tapping the same result again replays it.
        LaunchedEffect(highlight, rows) {
            val target = highlight?.takeIf { it.categoryId == categoryId } ?: return@LaunchedEffect
            val index = sections.indexOfFirst { it.title == target.sectionTitle }
            if (index < 0) return@LaunchedEffect
            if (!sections[index].initiallyExpanded) toggled = toggled + index
            val rowIndex = rows.indexOfFirst { row -> row.any { it.index == index } }
            if (rowIndex >= 0) listState.animateScrollToItem(rowIndex)
        }

        LazyColumn(
            state = listState,
            contentPadding = contentPadding(bottomPadding),
            verticalArrangement = Arrangement.spacedBy(GAP),
        ) {
            items(rows.size) { rowIndex ->
                val row = rows[rowIndex]
                CardRow(fillers = if (row.spansRow()) 0 else columns - row.size) {
                    row.forEach { (index, section) ->
                        val expanded = if (index in toggled) {
                            !section.initiallyExpanded
                        } else {
                            section.initiallyExpanded
                        }
                        SectionCard(
                            section = section,
                            expanded = expanded,
                            // Collapsing is offered exactly where the collector asked for it — the
                            // repeated entities. A chevron on a section nobody folds is noise on
                            // every single card.
                            onToggle = if (section.initiallyExpanded) {
                                null
                            } else {
                                {
                                    toggled = if (index in toggled) {
                                        toggled - index
                                    } else {
                                        toggled + index
                                    }
                                }
                            },
                            onCopy = onCopy,
                            highlightedLabel = highlight
                                ?.takeIf { it.categoryId == categoryId }
                                ?.takeIf { it.sectionTitle == section.title }
                                ?.label,
                            rawValues = rawValues,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Placeholder cards sized and laid out like real ones. Collectors that enumerate cameras or codecs
 * take long enough to be visible, and a skeleton with the same geometry keeps the page from
 * reflowing when the data lands.
 */
@Composable
private fun SkeletonList(bottomPadding: Dp) {
    BoxWithConstraints(
        Modifier
            .widthIn(max = CONTENT_MAX_WIDTH)
            .fillMaxHeight(),
    ) {
        val columns = if (maxWidth >= TWO_COLUMN_MIN_WIDTH) 2 else 1
        val rows = (0 until SKELETON_CARDS).chunked(columns)
        LazyColumn(
            contentPadding = contentPadding(bottomPadding),
            verticalArrangement = Arrangement.spacedBy(GAP),
        ) {
            items(rows.size) { rowIndex ->
                CardRow(fillers = columns - rows[rowIndex].size) {
                    rows[rowIndex].forEach { card ->
                        SkeletonCard(card, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * [fillers] empty columns keep a short final row from stretching its single card across the whole
 * width. A row that deliberately spans passes zero.
 */
@Composable
private fun CardRow(
    fillers: Int,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(GAP),
        verticalAlignment = Alignment.Top,
    ) {
        content()
        repeat(fillers) {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun SkeletonCard(seed: Int, modifier: Modifier = Modifier) = Column(
    modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium)
        .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
) {
    repeat(SKELETON_ROWS_PER_CARD) { row ->
        Box(
            Modifier
                .fillMaxWidth(if ((row + seed) % 3 == 0) 0.65f else 0.9f)
                .height(16.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    MaterialTheme.shapes.extraSmall,
                ),
        )
    }
}

private fun contentPadding(bottomPadding: Dp) = PaddingValues(
    start = 16.dp,
    end = 16.dp,
    top = 8.dp,
    bottom = bottomPadding + 24.dp,
)

@Composable
private fun Message(text: String) = Box(
    Modifier
        .fillMaxSize()
        .padding(32.dp),
    contentAlignment = Alignment.Center,
) {
    Text(
        // bodyMedium, matching the list pane's own empty states — one body size across the app.
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val CONTENT_MAX_WIDTH = 1000.dp
private val TWO_COLUMN_MIN_WIDTH = 800.dp
private val GAP = 12.dp
private const val SKELETON_CARDS = 4
private const val SKELETON_ROWS_PER_CARD = 5
