package com.github.jvsena42.loopky.presentation.importflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.anki.ApkgReader
import com.github.jvsena42.loopky.data.repository.ImportRepository
import com.github.jvsena42.loopky.domain.model.ImportDraft
import com.github.jvsena42.loopky.domain.model.Separator
import com.github.jvsena42.loopky.domain.model.frontBackOf
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
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
        startParse(fileName) { Result.success(LoadedFile(text)) }
    }

    /**
     * An Anki `.apkg`: a zip around a SQLite collection. Unpacked to the same tab-separated shape
     * a "Notes in Plain Text" export produces, so it feeds the same parser rather than a second
     * one.
     */
    fun onApkgLoaded(fileName: String, bytes: ByteArray) {
        // UnsupportedApkg, not NoCardsFound: this is a real .apkg that this build can't unpack
        // (zstd collection.anki21b). The two used to collapse into one message.
        startParse(fileName, loadFailure = BulkImportError.UnsupportedApkg) {
            ApkgReader.readNotes(bytes).map { LoadedFile(it.text, it.deckName) }
        }
    }

    private fun startParse(
        fileName: String,
        loadFailure: BulkImportError = BulkImportError.Unreadable,
        load: suspend () -> Result<LoadedFile>,
    ) {
        parseJob?.cancel()
        parseJob = viewModelScope.launch {
            _state.update { BulkImportUiState.Parsing(fileName) }
            // One runSuspendCatching rather than a recoverCatching/mapCatching chain: both of those
            // are inline, so their lambdas inherit this suspend context and would re-swallow the
            // cancellation the repository now rethrows. getOrElse does not catch, so the load's
            // own failure still gets tagged while a cancel passes straight through.
            runSuspendCatching {
                val loaded = load().getOrElse { throw FailedToLoad(loadFailure, it) }
                importRepository.parseBulk(
                    rawText = loaded.text,
                    suggestedTitle = suggestedTitleFor(loaded.deckName, fileName),
                ).getOrThrow()
            }
                .onSuccess { emitReady(fileName, it) }
                .onFailure { err ->
                    // A re-pick cancels a parse that may run for seconds; that now kills this
                    // coroutine outright, so only real failures reach here. Anything past the load
                    // is the parser's: it read fine, there was just nothing card-shaped in it.
                    val reason = (err as? FailedToLoad)?.reason ?: BulkImportError.NoCardsFound
                    Log.e(TAG, "bulk parse: FAILED — $reason — ${err.message}", err)
                    _state.update { BulkImportUiState.Error(reason) }
                }
        }
    }

    private fun emitReady(fileName: String, draft: ImportDraft) {
        // Both counts come off keptRows() so the summary and what actually publishes agree by
        // construction. Computing "skipped" independently is how they came to disagree: the screen
        // reported rows as dropped that publish still saw.
        val kept = importRepository.keptRows()
        val skipped = draft.rows.size - kept.size
        Log.d(TAG, "bulk parse: ${draft.rows.size} rows, $skipped skipped")
        _state.update {
            BulkImportUiState.Ready(
                fileName = fileName,
                separator = draft.separator,
                cardCount = kept.size,
                skippedCount = skipped,
                duplicatesCollapsed = draft.duplicatesCollapsed,
                truncatedCount = draft.truncated,
                // Sampled from the kept rows, not all of them: showing a card that is about to be
                // skipped is the one sample guaranteed to mislead.
                sample = kept.take(SAMPLE_SIZE).map { row ->
                    val (front, back) = draft.frontBackOf(row)
                    SampleCard(front = front, back = back)
                },
            )
        }
    }

    /**
     * The deck title to prefill the commit screen with.
     *
     * The `.apkg`'s own deck name wins over the file name: "Japanese Core 2000" is what the user
     * calls this deck, "japanese_core_2000_step_01.apkg" is what their file manager calls it.
     * Capped at [PublishDeckViewModel.TITLE_MAX_LENGTH] so the commit screen never opens with a
     * validation error already showing.
     */
    internal fun suggestedTitleFor(deckName: String?, fileName: String): String? =
        deckName?.trim()?.takeIf { it.isNotBlank() }?.take(PublishDeckViewModel.TITLE_MAX_LENGTH)
            ?: fileName.substringBeforeLast('.')
                .replace('_', ' ')
                .replace('-', ' ')
                .split(' ')
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
                ?.take(PublishDeckViewModel.TITLE_MAX_LENGTH)

    /** The platform layer could not turn the picked uri into text. */
    fun onFileReadFailed(reason: BulkImportError) {
        parseJob?.cancel()
        Log.e(TAG, "file read: FAILED — $reason")
        _state.update { BulkImportUiState.Error(reason) }
    }

    /** Reading the picked file's bytes, before its contents — or even its name — are known. */
    fun onFileReadStarted() {
        parseJob?.cancel()
        _state.update { BulkImportUiState.Reading }
    }

    /** Back to the picker without leaving the screen, so changing your mind isn't a restart. */
    fun onPickAnother() {
        parseJob?.cancel()
        _state.update { BulkImportUiState.Idle }
    }

    /**
     * Re-parse the file the user already picked with an explicit separator.
     *
     * The summary showed what the parser decided and, unlike paste, gave no way to disagree with
     * it — so a misdetected file could only be fixed by editing it outside the app. Re-parses from
     * the draft's own text, and re-passes its suggested title or the prefill would be lost.
     */
    fun onSeparatorOverride(separator: Separator) {
        val draft = importRepository.currentDraft() ?: return
        val fileName = (_state.value as? BulkImportUiState.Ready)?.fileName ?: return
        parseJob?.cancel()
        parseJob = viewModelScope.launch {
            _state.update { BulkImportUiState.Parsing(fileName) }
            importRepository
                .parseBulk(draft.rawText, separator, draft.suggestedTitle)
                .onSuccess { emitReady(fileName, it) }
                .onFailure { err ->
                    Log.e(TAG, "separator override: FAILED — ${err.message}", err)
                    _state.update { BulkImportUiState.Error(BulkImportError.NoCardsFound) }
                }
        }
    }

    /** One confirmation for the whole file — the point of the summary. */
    fun onConfirm() {
        _state.value as? BulkImportUiState.Ready ?: return
        viewModelScope.launch { _effects.emit(BulkImportEffect.Continue) }
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

    /**
     * Pulling the bytes off the provider. Separate from [Parsing] because the file's real name
     * comes from a `DISPLAY_NAME` query and so isn't known until the read is under way.
     */
    data object Reading : BulkImportUiState

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

    data class Error(val reason: BulkImportError) : BulkImportUiState
}

/**
 * Why an import failed, in terms the user can act on.
 *
 * A single message string collapsed "you picked a photo", "the file wouldn't open" and "the parser
 * found no cards" into one line — usually a parser message shown to someone who had simply picked
 * the wrong file. Deliberately not `ErrorReason`: every member of that is network/session/auth, and
 * extending it would force edits to exhaustive `when`s eight unrelated screens depend on.
 */
enum class BulkImportError {
    /** The provider wouldn't open it, or the read failed part-way. */
    Unreadable,

    /** Past [the picker's memory backstop]; the user should export without media. */
    TooLarge,

    /** Not UTF-8 text — a photo or a PDF, most often. */
    NotText,

    /** A real `.apkg`, but one this build can't unpack (zstd `collection.anki21b`). */
    UnsupportedApkg,

    /** Read and parsed fine; there was simply nothing card-shaped in it. */
    NoCardsFound,

    Unknown,
}

data class SampleCard(val front: String, val back: String)

/** What a picked file yielded: its text, plus the deck name if the source knew one. */
private data class LoadedFile(val text: String, val deckName: String? = null)

/** Marks a failure as coming from reading the file rather than from parsing its contents. */
private class FailedToLoad(val reason: BulkImportError, cause: Throwable) : Exception(cause)

sealed interface BulkImportEffect {
    /**
     * Proceed to the shared commit screen. Carries no payload: the suggested title travels on the
     * draft, alongside every other part of this handoff.
     */
    data object Continue : BulkImportEffect

    data object NavigateBack : BulkImportEffect
}
