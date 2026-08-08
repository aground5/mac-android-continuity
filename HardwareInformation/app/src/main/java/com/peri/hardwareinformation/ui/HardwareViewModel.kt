package com.peri.hardwareinformation.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peri.hardwareinformation.data.CollectedCategory
import com.peri.hardwareinformation.data.HardwareRepository
import com.peri.hardwareinformation.data.SearchHit
import com.peri.hardwareinformation.data.ValueLabels
import com.peri.hardwareinformation.data.model.CategoryRef
import com.peri.hardwareinformation.data.model.Item
import com.peri.hardwareinformation.data.model.Section
import com.peri.hardwareinformation.data.model.Value
import com.peri.hardwareinformation.data.renderInventory
import com.peri.hardwareinformation.data.searchInventory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface CategoryState {
    data object Loading : CategoryState
    data class Ready(val sections: List<Section>) : CategoryState
    data class Failed(val message: String) : CategoryState
}

/**
 * Which row the detail pane should scroll to, expand and flash after a search result is tapped.
 *
 * [nonce] makes each request distinct so that tapping the same result twice replays the highlight —
 * without it the state would be unchanged and the animation would never re-fire.
 */
data class HighlightTarget(
    val categoryId: String,
    val sectionTitle: String,
    val label: String,
    val nonce: Long,
)

sealed interface SearchState {
    data object Closed : SearchState

    /** Search and export both need every category collected; this is that pass. */
    data class Indexing(val done: Int, val total: Int) : SearchState

    data class Results(val query: String, val hits: List<SearchHit>) : SearchState
}

class HardwareViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = HardwareRepository(application)

    val catalog: List<CategoryRef> = repository.catalog

    // ---- Raw / readable display mode ---------------------------------------------------------

    private val prefs = application.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    private val _rawValues = MutableStateFlow(prefs.getBoolean(PREF_RAW_VALUES, false))

    /** App-wide: every rendered value switches between its readable and raw form together. */
    val rawValues: StateFlow<Boolean> = _rawValues.asStateFlow()

    fun toggleRawValues() {
        val next = !_rawValues.value
        _rawValues.value = next
        prefs.edit().putBoolean(PREF_RAW_VALUES, next).apply()
    }

    private val states = mutableMapOf<String, StateFlow<CategoryState>>()

    /**
     * One [StateFlow] per category, created on first request and kept for the process lifetime.
     * Rotating, folding, or navigating away and back therefore never re-runs a collector.
     */
    fun state(id: String): StateFlow<CategoryState> = synchronized(states) {
        states.getOrPut(id) { buildState(id) }
    }

    private fun buildState(id: String): StateFlow<CategoryState> {
        val collector = repository.collector(id)
            ?: return constantState(CategoryState.Failed("Unknown category: $id"))

        val live = collector.live()
        return if (live == null) {
            flow<CategoryState> { emit(CategoryState.Ready(collector.sections())) }
                .failSoftly()
                .flowOn(Dispatchers.IO)
                // Lazily, not WhileSubscribed: a static snapshot is worth caching for good.
                .stateIn(viewModelScope, SharingStarted.Lazily, CategoryState.Loading)
        } else {
            live.map<List<Section>, CategoryState> { CategoryState.Ready(it) }
                .failSoftly()
                .flowOn(Dispatchers.IO)
                // Stop polling shortly after the category leaves the screen.
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(LIVE_STOP_TIMEOUT_MILLIS),
                    CategoryState.Loading,
                )
        }
    }

    // ---- Search and export -------------------------------------------------------------------
    //
    // Both need the whole tree, so they share one collection pass. null query means search is
    // closed; null inventory means the pass has not finished yet.

    private val query = MutableStateFlow<String?>(null)
    private val inventory = MutableStateFlow<List<CollectedCategory>?>(null)
    private val indexed = MutableStateFlow(0)
    private var indexJob: Job? = null

    val search: StateFlow<SearchState> =
        combine(query, inventory, indexed) { text, collected, done ->
            when {
                text == null -> SearchState.Closed
                collected == null -> SearchState.Indexing(done, catalog.size)
                else -> SearchState.Results(text, collected.searchInventory(text))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchState.Closed)

    private val _highlight = MutableStateFlow<HighlightTarget?>(null)
    val highlight: StateFlow<HighlightTarget?> = _highlight.asStateFlow()

    fun highlight(hit: SearchHit) {
        _highlight.value = HighlightTarget(
            categoryId = hit.categoryId,
            sectionTitle = hit.sectionTitle,
            label = hit.item.label,
            nonce = System.nanoTime(),
        )
    }

    fun openSearch() {
        if (query.value == null) query.value = ""
        startIndexing()
    }

    fun closeSearch() {
        query.value = null
    }

    fun onQueryChange(text: String) {
        query.value = text
    }

    /**
     * Runs every collector once and keeps the result. Idempotent, so opening search and exporting in
     * either order costs a single pass.
     */
    private fun startIndexing() {
        if (inventory.value != null || indexJob?.isActive == true) return
        indexJob = viewModelScope.launch(Dispatchers.IO) {
            val collected = catalog.mapIndexed { position, ref ->
                indexed.value = position
                CollectedCategory(
                    id = ref.id,
                    title = ref.title,
                    // A collector that throws contributes nothing rather than sinking the pass.
                    sections = runCatching { repository.collector(ref.id)?.sections() }
                        .getOrNull()
                        .orEmpty(),
                )
            }
            indexed.value = catalog.size
            inventory.value = collected
        }
    }

    /**
     * Writes the whole inventory to a file in the cache and returns it, or null if nothing could be
     * collected. Sharing the file rather than an intent extra keeps a thousand-row dump clear of the
     * Binder transaction limit.
     */
    suspend fun exportToCache(heading: String, labels: ValueLabels): File? {
        startIndexing()
        indexJob?.join()
        val collected = inventory.value ?: return null

        return withContext(Dispatchers.IO) {
            runCatching {
                val text = renderInventory(
                    heading = heading,
                    subtitle = listOf(deviceLine(collected), generatedLine()),
                    categories = collected,
                    labels = labels,
                )
                val directory = File(getApplication<Application>().cacheDir, EXPORT_DIRECTORY)
                directory.mkdirs()
                File(directory, EXPORT_FILE_NAME).apply { writeText(text) }
            }.getOrNull()
        }
    }

    /** Pulls the model and OS version back out of the collected tree for the file header. */
    private fun deviceLine(collected: List<CollectedCategory>): String {
        fun lookup(categoryId: String, sectionTitle: String, label: String): String? = collected
            .firstOrNull { it.id == categoryId }
            ?.sections
            ?.firstOrNull { it.title == sectionTitle }
            ?.items
            ?.firstOrNull { it.label == label }
            ?.value
            ?.let { it as? Value.Text }
            ?.text

        val model = lookup("device", "Identity", "Model")
        val android = lookup("system", "Android", "Version")
        val api = lookup("system", "Android", "API level")
        return listOfNotNull(
            model,
            android?.let { "Android $it" },
            api?.let { "API $it" },
        ).joinToString(" · ")
    }

    private fun generatedLine(): String =
        "Generated ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}"

    /** A single unreadable value must not blank a category; a thrown collector still shouldn't. */
    private fun Flow<CategoryState>.failSoftly(): Flow<CategoryState> = catch { cause ->
        emit(CategoryState.Failed(cause.message ?: cause::class.java.simpleName))
    }

    private fun constantState(value: CategoryState): StateFlow<CategoryState> =
        flow { emit(value) }.stateIn(viewModelScope, SharingStarted.Lazily, value)

    private companion object {
        const val LIVE_STOP_TIMEOUT_MILLIS = 5_000L
        const val EXPORT_DIRECTORY = "exports"
        const val EXPORT_FILE_NAME = "hardware-information.txt"
        const val PREFS_NAME = "settings"
        const val PREF_RAW_VALUES = "raw_values"
    }
}

