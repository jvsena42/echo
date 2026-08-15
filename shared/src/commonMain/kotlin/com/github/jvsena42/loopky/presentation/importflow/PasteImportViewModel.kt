package com.github.jvsena42.loopky.presentation.importflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.repository.ImportRepository
import com.github.jvsena42.loopky.domain.model.ColumnRole
import com.github.jvsena42.loopky.domain.model.Separator
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

class PasteImportViewModel(
    private val importRepository: ImportRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PasteImportUiState())
    val state: StateFlow<PasteImportUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<PasteImportEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<PasteImportEffect> = _effects.asSharedFlow()

    private var parseJob: Job? = null

    fun onTextChanged(text: String) {
        _state.update { it.copy(rawText = text, error = null, validation = null) }
        if (text.isBlank()) {
            _state.update {
                it.copy(
                    detectedSeparator = null,
                    cardCount = 0,
                    incompleteCardCount = 0,
                    previewCards = emptyList(),
                    isParsed = false,
                )
            }
            return
        }
        doParse(text)
    }

    /**
     * Override the detected separator (spec §5.2 "tap to change"). The chip already looked
     * tappable but had no handler; this re-parses the same text with the chosen delimiter.
     */
    fun onSeparatorOverride(separator: Separator) {
        _state.update { it.copy(separatorOverride = separator.takeIf { s -> s != Separator.Auto }) }
        val text = _state.value.rawText
        if (text.isNotBlank()) doParse(text)
    }

    private fun doParse(text: String) {
        parseJob?.cancel()
        parseJob = viewModelScope.launch {
            importRepository.parse(text, _state.value.separatorOverride)
                .onSuccess { draft ->
                    val mapping = draft.columnMapping.assignments
                    val frontIdx = mapping.indexOfFirst { it == ColumnRole.Front }.takeIf { it >= 0 } ?: 0
                    val backIdx = mapping.indexOfFirst { it == ColumnRole.Back }.takeIf { it >= 0 } ?: 1
                    val incompleteCount = draft.rows.count { row ->
                        row.fields.getOrElse(frontIdx) { "" }.isBlank() ||
                            row.fields.getOrElse(backIdx) { "" }.isBlank()
                    }
                    _state.update {
                        it.copy(
                            detectedSeparator = draft.separator,
                            cardCount = draft.rows.size,
                            incompleteCardCount = incompleteCount,
                            previewCards = draft.rows.take(PREVIEW_CARD_COUNT).map { row ->
                                PreviewCard(
                                    front = row.fields.getOrElse(frontIdx) { "" },
                                    back = row.fields.getOrElse(backIdx) { "" },
                                )
                            },
                            isParsed = true,
                            error = null,
                        )
                    }
                    Log.d(TAG, "parse: ${draft.rows.size} cards, separator=${draft.separator}")
                }
                .onFailure { err ->
                    _state.update {
                        it.copy(
                            error = err.message ?: "Parse failed.",
                            isParsed = false,
                            cardCount = 0,
                            incompleteCardCount = 0,
                            previewCards = emptyList(),
                        )
                    }
                }
        }
    }

    fun onNextClick() {
        val s = _state.value
        // Say why instead of silently swallowing the tap: with an empty box the button used
        // to look enabled and do nothing at all.
        val validation = when {
            s.rawText.isBlank() -> PasteValidation.EmptyInput
            !s.isParsed || s.cardCount == 0 -> PasteValidation.NoCardsParsed
            else -> null
        }
        if (validation != null) {
            _state.update { it.copy(validation = validation) }
            return
        }
        _state.update { it.copy(validation = null) }
        viewModelScope.launch { _effects.emit(PasteImportEffect.NavigatePublish) }
    }

    fun onCancelClick() {
        importRepository.clear()
        viewModelScope.launch { _effects.emit(PasteImportEffect.NavigateBack) }
    }

    companion object {
        private const val TAG = "Loopky/PasteVM"
        private const val PREVIEW_CARD_COUNT = 3
    }
}

data class PasteImportUiState(
    val rawText: String = "",
    val detectedSeparator: Separator? = null,
    val cardCount: Int = 0,
    val incompleteCardCount: Int = 0,
    val previewCards: List<PreviewCard> = emptyList(),
    val isParsed: Boolean = false,
    val error: String? = null,
    /** Non-null when the user picked a delimiter instead of trusting auto-detection. */
    val separatorOverride: Separator? = null,
    /** Why Next can't advance yet; cleared as soon as the text parses. */
    val validation: PasteValidation? = null,
) {
    /** Preview is shown only once at least one parsed card has both a non-blank front and back. */
    val hasPreviewableCard: Boolean
        get() = previewCards.any { it.front.isNotBlank() && it.back.isNotBlank() }

    /** No usable front/back delimiter was found, so every line collapsed into a single column. */
    val noPatternDetected: Boolean
        get() = isParsed && detectedSeparator == Separator.SingleColumn

    /** True when text was parsed but at least one card is missing a front or back. */
    val hasIncompleteCards: Boolean
        get() = isParsed && incompleteCardCount > 0
}

data class PreviewCard(
    val front: String,
    val back: String,
)

sealed interface PasteImportEffect {
    data object NavigatePublish : PasteImportEffect
    data object NavigateBack : PasteImportEffect
}

/** Why the paste screen can't advance, so the CTA can explain itself. */
enum class PasteValidation { EmptyInput, NoCardsParsed }
