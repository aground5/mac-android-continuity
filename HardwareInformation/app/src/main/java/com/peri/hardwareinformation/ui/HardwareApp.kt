package com.peri.hardwareinformation.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peri.hardwareinformation.R
import com.peri.hardwareinformation.data.ValueLabels
import com.peri.hardwareinformation.ui.about.ABOUT_DESTINATION
import com.peri.hardwareinformation.ui.about.AboutPane
import com.peri.hardwareinformation.ui.about.sendFeedback
import com.peri.hardwareinformation.ui.catalog.CategoryListPane
import com.peri.hardwareinformation.ui.detail.CategoryDetailPane
import java.io.File
import kotlinx.coroutines.launch

/**
 * The single navigation surface. `NavigableListDetailPaneScaffold` decides how many panes fit and
 * handles back itself, so there is no width branching and no navigation graph to keep in sync:
 *
 *  - under 600dp: one pane, list then detail
 *  - 840dp and up: both panes side by side, list fixed, detail capped and centred
 *
 * Medium widths (600–839dp) stay single-pane by default; passing a custom `PaneScaffoldDirective`
 * here is the one place to change that.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun HardwareApp(viewModel: HardwareViewModel = viewModel()) {
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.copied)
    val exportHeading = stringResource(R.string.app_name)
    val exportTitle = stringResource(R.string.export_share_title)
    val exportFailed = stringResource(R.string.export_failed)
    val valueLabels = rememberValueLabels()

    val search by viewModel.search.collectAsStateWithLifecycle()
    val highlight by viewModel.highlight.collectAsStateWithLifecycle()
    val rawValues by viewModel.rawValues.collectAsStateWithLifecycle()
    var exporting by remember { mutableStateOf(false) }
    var attaching by remember { mutableStateOf(false) }
    val feedbackAddress = stringResource(R.string.feedback_email)
    val feedbackSubject = stringResource(R.string.feedback_subject)
    val noEmailApp = stringResource(R.string.feedback_no_email_app)

    val selectedId = navigator.currentDestination
        ?.takeIf { it.pane == ListDetailPaneScaffoldRole.Detail }
        ?.contentKey
    val selectedRef = remember(selectedId) {
        viewModel.catalog.firstOrNull { it.id == selectedId }
    }
    val listVisible =
        navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Expanded

    val onCopy: (String, String) -> Unit = { label, value ->
        context.copyToClipboard(label, value, copiedMessage)
    }

    // Search is a mode over the list pane, not a destination, so the scaffold's own back handling
    // knows nothing about it. Without this, back from an open search skips straight past it.
    BackHandler(enabled = search !is SearchState.Closed) { viewModel.closeSearch() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        NavigableListDetailPaneScaffold(
            navigator = navigator,
            modifier = Modifier.fillMaxSize(),
            listPane = {
                AnimatedPane {
                    CategoryListPane(
                        catalog = viewModel.catalog,
                        search = search,
                        valueLabels = valueLabels,
                        rawValues = rawValues,
                        selectedId = selectedId,
                        showSelection = listVisible && selectedId != null,
                        exporting = exporting,
                        onSelect = { id ->
                            scope.launch {
                                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id)
                            }
                        },
                        onSelectHit = { hit ->
                            // Remember the row first, then navigate: the detail pane reads the
                            // target as it composes and scrolls straight to it.
                            viewModel.highlight(hit)
                            scope.launch {
                                navigator.navigateTo(
                                    ListDetailPaneScaffoldRole.Detail,
                                    hit.categoryId,
                                )
                            }
                        },
                        onOpenSearch = viewModel::openSearch,
                        onCloseSearch = viewModel::closeSearch,
                        onQueryChange = viewModel::onQueryChange,
                        onOpenAbout = {
                            scope.launch {
                                navigator.navigateTo(
                                    ListDetailPaneScaffoldRole.Detail,
                                    ABOUT_DESTINATION,
                                )
                            }
                        },
                        onExport = {
                            if (exporting) return@CategoryListPane
                            exporting = true
                            scope.launch {
                                val file = viewModel.exportToCache(exportHeading, valueLabels)
                                exporting = false
                                if (file == null) {
                                    Toast.makeText(context, exportFailed, Toast.LENGTH_SHORT).show()
                                } else {
                                    context.shareFile(file, exportTitle, exportFailed)
                                }
                            }
                        },
                    )
                }
            },
            detailPane = {
                AnimatedPane {
                    if (selectedId == ABOUT_DESTINATION) {
                        AboutPane(
                            showBack = !listVisible,
                            rawValues = rawValues,
                            attaching = attaching,
                            onBack = { scope.launch { navigator.navigateBack() } },
                            onCopy = onCopy,
                            onSendFeedback = { attachInventory ->
                                if (attaching) return@AboutPane
                                if (!attachInventory) {
                                    context.sendFeedback(
                                        feedbackAddress,
                                        feedbackSubject,
                                        attachment = null,
                                        failureMessage = noEmailApp,
                                    )
                                    return@AboutPane
                                }
                                attaching = true
                                scope.launch {
                                    val file = viewModel.exportToCache(exportHeading, valueLabels)
                                    attaching = false
                                    if (file == null) {
                                        Toast.makeText(context, exportFailed, Toast.LENGTH_SHORT)
                                            .show()
                                    } else {
                                        context.sendFeedback(
                                            feedbackAddress,
                                            feedbackSubject,
                                            attachment = file,
                                            failureMessage = noEmailApp,
                                        )
                                    }
                                }
                            },
                        )
                        return@AnimatedPane
                    }
                    // The flow is created once per category and cached in the ViewModel, so this
                    // survives rotation, folding, and navigating away and back.
                    val state = selectedId?.let { id ->
                        val flow = remember(id) { viewModel.state(id) }
                        val value by flow.collectAsStateWithLifecycle()
                        value
                    }
                    CategoryDetailPane(
                        ref = selectedRef,
                        state = state,
                        showBack = !listVisible,
                        highlight = highlight,
                        rawValues = rawValues,
                        onToggleRawValues = viewModel::toggleRawValues,
                        onBack = { scope.launch { navigator.navigateBack() } },
                        onCopy = onCopy,
                    )
                }
            },
        )
    }
}

/** Resolved once so the clipboard, the search results and the export all word values identically. */
@Composable
private fun rememberValueLabels(): ValueLabels {
    val yes = stringResource(R.string.value_yes)
    val no = stringResource(R.string.value_no)
    val needsPermission = stringResource(R.string.unavailable_needs_permission)
    val osRestricted = stringResource(R.string.unavailable_os_restricted)
    val notSupported = stringResource(R.string.unavailable_not_supported)
    val readFailed = stringResource(R.string.unavailable_read_failed)
    return remember(yes, no, needsPermission, osRestricted, notSupported, readFailed) {
        ValueLabels(yes, no, needsPermission, osRestricted, notSupported, readFailed)
    }
}

private fun Context.copyToClipboard(label: String, value: String, confirmation: String) {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    // Android 13 and up shows its own copy confirmation, so a Toast there would double up.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, confirmation, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Hands the file to the system share sheet. The user picks the destination there — the app never
 * sends anything anywhere on its own.
 */
private fun Context.shareFile(file: File, title: String, failureMessage: String) {
    val uri = runCatching {
        FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }.getOrNull()
    if (uri == null) {
        Toast.makeText(this, failureMessage, Toast.LENGTH_SHORT).show()
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(send, title))
}
