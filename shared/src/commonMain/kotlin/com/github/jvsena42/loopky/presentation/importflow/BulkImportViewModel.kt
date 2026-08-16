package com.github.jvsena42.loopky.presentation.importflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.anki.ApkgReader
import com.github.jvsena42.loopky.data.repository.ImportRepository
import com.github.jvsena42.loopky.domain.model.Separator
import com.github.jvsena42.loopky.domain.model.frontBackOf
import com.github.jvsena42.loopky.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Bulk file import: parse a whole exported deck and show a **summary**, not a swipe queue.
 *
 * spec §5.4's triage queue is card-at-a-time by design, which is right for a 40-line paste and
 * wrong for a 20,000-card Anki export — nobody swipes through one. This screen reports what was
 * parsed, shows a sample, and takes a single confirmation. It then hands off to the same
 * [PublishDeckViewModel] commit flow that paste uses; the spine every import source shares is
 * parse → preview → commit, not the queue.
 */
class BulkImportViewModel(
    private val importRepository: ImportRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<BulkImportUiState>(BulkImportUiState.Idle)
    val state: StateFlow<BulkImportUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<BulkImportEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<BulkImportEffect> = _effects.asSharedFlow()

    private var parseJob: Job? = null

    /** [fileName] is shown as the default deck name; [text] is the file's contents. */
    fun onFileLoaded(fileName: String, text: String) {
        startParse(fileName) { Result.success(text) }
    }

    /**
     * An Anki `.apkg`: a zip around a SQLite collection. Unpacked to the same tab-separated shape
     * a "Notes in Plain Text" export produces, so it feeds the same parser rather than a second
     * one.
     */
    fun onApkgLoaded(fileName: String, bytes: ByteArray) {
        startParse(fileName) {
            ApkgReader.readNotes(bytes).map { it.text }
        }
    }

    private fun startParse(fileName: String, load: suspend () -> Result<String>) {
        parseJob?.cancel()
        parseJob = viewModelScope.launch {
            _state.update { BulkImportUiState.Parsing(fileName) }
            load()
                .mapCatching { text -> importRepository.parseBulk(text).getOrThrow() }
                .onSuccess { draft ->
                    val skipped = draft.rows.count { row ->
                        val (front, back) = draft.frontBackOf(row)
                        front.isBlank() || back.isBlank()
                    }
                    Log.d(TAG, "bulk parse: ${draft.rows.size} rows, $skipped skipped")
                    _state.update {
                        BulkImportUiState.Ready(
                            fileName = fileName,
                            separator = draft.separator,
                            cardCount = draft.rows.size - skipped,
                            skippedCount = skipped,
                            duplicatesCollapsed = draft.duplicatesCollapsed,
                            truncatedCount = draft.truncated,
                            sample = draft.rows.take(SAMPLE_SIZE).map { row ->
                                val (front, back) = draft.frontBackOf(row)
                                SampleCard(front = front, back = back)
                            },
                        )
                    }
                }
                .onFailure { err ->
                    Log.e(TAG, "bulk parse: FAILED — ${err.message}", err)
                    _state.update {
                        BulkImportUiState.Error(err.message ?: "Could not read that file.")
                    }
                }
        }
    }

    fun onFileReadFailed(message: String) {
        _state.update { BulkImportUiState.Error(message) }
    }

    /** One confirmation for the whole file — the point of the summary. */
    fun onConfirm() {
        val ready = _state.value as? BulkImportUiState.Ready ?: return
        viewModelScope.launch { _effects.emit(BulkImportEffect.Continue(ready.fileName)) }
    }

    fun onCancel() {
        importRepository.clear()
        viewModelScope.launch { _effects.emit(BulkImportEffect.NavigateBack) }
    }

    companion object {
        private const val TAG = "Loopky/BulkImportVM"

        /** Enough to see the parse worked without pretending the user reviews them all. */
        private const val SAMPLE_SIZE = 3
    }
}

sealed interface BulkImportUiState {
    data object Idle : BulkImportUiState

    data class Parsing(val fileName: String) : BulkImportUiState

    data class Ready(
        val fileName: String,
        val separator: Separator,
        val cardCount: Int,
        /** Rows missing a front or a back; they are dropped rather than queued for editing. */
        val skippedCount: Int,
        val duplicatesCollapsed: Int,
        /** Rows past the parser's cap. Reported rather than dropped silently. */
        val truncatedCount: Int,
        val sample: List<SampleCard>,
    ) : BulkImportUiState {
        val canImport: Boolean get() = cardCount > 0
    }

    data class Error(val message: String) : BulkImportUiState
}

data class SampleCard(val front: String, val back: String)

sealed interface BulkImportEffect {
    /** Proceed to the shared commit screen, seeded with the file's name as the deck title. */
    data class Continue(val suggestedTitle: String) : BulkImportEffect

    data object NavigateBack : BulkImportEffect
}
