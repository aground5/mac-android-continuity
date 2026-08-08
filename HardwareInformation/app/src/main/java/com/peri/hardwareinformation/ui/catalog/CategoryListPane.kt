package com.peri.hardwareinformation.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.peri.hardwareinformation.R
import com.peri.hardwareinformation.data.SearchHit
import com.peri.hardwareinformation.data.ValueLabels
import com.peri.hardwareinformation.data.model.CategoryRef
import com.peri.hardwareinformation.data.plainText
import com.peri.hardwareinformation.ui.SearchState
import com.peri.hardwareinformation.ui.about.ABOUT_DESTINATION

/**
 * Renders instantly: the catalog is built from collector ids and titles, so opening the app never
 * waits on a hardware read.
 *
 * The pane doubles as the search surface. With a thousand-odd rows across fourteen categories,
 * browsing alone stops being enough, and search needs somewhere to live that does not steal the
 * detail pane.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryListPane(
    catalog: List<CategoryRef>,
    search: SearchState,
    valueLabels: ValueLabels,
    rawValues: Boolean,
    selectedId: String?,
    /** Selection is only meaningful while both panes are on screen. */
    showSelection: Boolean,
    exporting: Boolean,
    onSelect: (String) -> Unit,
    onSelectHit: (SearchHit) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onExport: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        // Must fill the pane: the scaffold's own container colour is what covers the pane
        // background, so a content-sized scaffold leaves a seam below the last item.
        modifier = modifier.fillMaxSize(),
        topBar = {
            if (search is SearchState.Closed) {
                SearchPill(exporting = exporting, onOpenSearch = onOpenSearch, onExport = onExport)
            } else {
                SearchTopBar(
                    query = (search as? SearchState.Results)?.query.orEmpty(),
                    onQueryChange = onQueryChange,
                    onClose = onCloseSearch,
                )
            }
        },
    ) { padding ->
        val contentPadding = PaddingValues(
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding() + 8.dp,
        )
        when (search) {
            SearchState.Closed -> CategoryList(
                catalog = catalog,
                selectedId = selectedId,
                showSelection = showSelection,
                contentPadding = contentPadding,
                onSelect = onSelect,
                onOpenAbout = onOpenAbout,
            )

            is SearchState.Indexing -> Progress(
                text = stringResource(R.string.search_indexing, search.done, search.total),
                topPadding = padding.calculateTopPadding(),
            )

            is SearchState.Results -> when {
                search.query.isBlank() -> Message(
                    text = stringResource(R.string.search_hint_body),
                    topPadding = padding.calculateTopPadding(),
                )

                search.hits.isEmpty() -> Message(
                    text = stringResource(R.string.search_no_results, search.query),
                    topPadding = padding.calculateTopPadding(),
                )

                else -> SearchResults(
                    hits = search.hits,
                    valueLabels = valueLabels,
                    rawValues = rawValues,
                    contentPadding = contentPadding,
                    onSelect = onSelectHit,
                )
            }
        }
    }
}

/**
 * The pane's only chrome, replacing a full app bar (Material's docked search bar pattern). The
 * pill's label is the search affordance and export rides along as its trailing icon, so both
 * actions stay visible — a gesture would leave no hint that either exists.
 */
@Composable
private fun SearchPill(exporting: Boolean, onOpenSearch: () -> Unit, onExport: () -> Unit) {
    Surface(
        onClick = onOpenSearch,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                // The label beside it already reads as "search".
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.search_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
            )
            IconButton(onClick = onExport, enabled = !exporting) {
                if (exporting) {
                    CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = stringResource(R.string.action_export),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    TopAppBar(
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                // TextField's textStyle defaults to LocalTextStyle, and TopAppBar's title slot
                // provides titleLarge — so without this the query and its placeholder render at
                // 22sp bold, twice the size of the results underneath.
                textStyle = MaterialTheme.typography.bodyLarge,
                placeholder = {
                    Text(
                        text = stringResource(R.string.search_hint),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // Stripped back so the field reads as part of the bar rather than a control in it.
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        },
        actions = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, stringResource(R.string.action_close_search))
            }
        },
    )
}

@Composable
private fun CategoryList(
    catalog: List<CategoryRef>,
    selectedId: String?,
    showSelection: Boolean,
    contentPadding: PaddingValues,
    onSelect: (String) -> Unit,
    onOpenAbout: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        items(catalog, key = CategoryRef::id) { ref ->
            CategoryItem(
                title = ref.title,
                icon = ref.icon,
                selected = showSelection && ref.id == selectedId,
                onClick = { onSelect(ref.id) },
            )
        }
        // Below a divider rather than inside the list: About is not one of the categories, and the
        // export and the search index both agree with that. A rule and the last position say so
        // without spending any of the pane's chrome on another icon.
        item {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            CategoryItem(
                title = stringResource(R.string.about_title),
                icon = Icons.Outlined.Info,
                selected = showSelection && selectedId == ABOUT_DESTINATION,
                onClick = onOpenAbout,
            )
        }
    }
}

@Composable
private fun CategoryItem(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = {
            Icon(
                imageVector = icon,
                // The title beside it already names the destination.
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        colors = if (selected) {
            ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                headlineColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else {
            ListItemDefaults.colors()
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SearchResults(
    hits: List<SearchHit>,
    valueLabels: ValueLabels,
    rawValues: Boolean,
    contentPadding: PaddingValues,
    onSelect: (SearchHit) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        itemsIndexed(hits) { _, hit ->
            // ListItem's slot defaults are 11 / 16 / 14sp, all regular — three sizes and no weight
            // contrast. Every style here is set explicitly so a result reads on the app's own scale:
            // one body size throughout, bold on the label because bold is how this app marks a
            // heading, and a single quiet 12sp step for the breadcrumb.
            ListItem(
                // Breadcrumb first: a match is useless if you cannot tell where it came from.
                overlineContent = {
                    Text(
                        text = "${hit.categoryTitle}  ›  ${hit.sectionTitle}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                headlineContent = {
                    Text(
                        text = hit.item.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                supportingContent = {
                    Text(
                        text = hit.item.value.plainText(valueLabels, rawValues),
                        // Identical to a value inside the detail pane, because it is one.
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier.clickable { onSelect(hit) },
            )
        }
    }
}

@Composable
private fun Progress(text: String, topPadding: Dp) = Box(
    Modifier
        .fillMaxSize()
        .padding(top = topPadding, start = 32.dp, end = 32.dp),
    contentAlignment = Alignment.Center,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator()
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Message(text: String, topPadding: Dp) = Box(
    Modifier
        .fillMaxSize()
        .padding(top = topPadding, start = 32.dp, end = 32.dp),
    contentAlignment = Alignment.Center,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
