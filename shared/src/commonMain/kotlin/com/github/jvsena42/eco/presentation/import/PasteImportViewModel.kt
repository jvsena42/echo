package com.github.jvsena42.eco.presentation.import_flow

import com.github.jvsena42.eco.data.repository.ImportRepository
import com.github.jvsena42.eco.domain.model.ColumnRole
import com.github.jvsena42.eco.domain.model.ImportDraft
import com.github.jvsena42.eco.domain.model.Separator
import com.github.jvsena42.eco.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    mainScope: CoroutineScope? = null,
) {
    private val scope: CoroutineScope =
        mainScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(PasteImportUiState())
    val state: StateFlow<PasteImportUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<PasteImportEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<PasteImportEffect> = _effects.asSharedFlow()

    private var parseJob: Job? = null

    fun onTextChanged(text: String) {
        _state.update { it.copy(rawText = text, error = null) }
        if (text.isBlank()) {
            _state.update {
                it.copy(
                    detectedSeparator = null,
                    cardCount = 0,
                    previewCards = emptyList(),
                    isParsed = false,
                )
            }
            return
        }
        doParse(text)
    }

    private fun doParse(text: String) {
        parseJob?.cancel()
        parseJob = scope.launch {
            importRepository.parse(text)
                .onSuccess { draft ->
                    val mapping = draft.columnMapping.assignments
                    val frontIdx = mapping.indexOfFirst { it == ColumnRole.Front }.takeIf { it >= 0 } ?: 0
                    val backIdx = mapping.indexOfFirst { it == ColumnRole.Back }.takeIf { it >= 0 } ?: 1
                    _state.update {
                        it.copy(
                            detectedSeparator = draft.separator,
                            cardCount = draft.rows.size,
                            previewCards = draft.rows.take(3).map { row ->
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
                            previewCards = emptyList(),
                        )
                    }
                }
        }
    }

    fun onNextClick() {
        if (!_state.value.isParsed) return
        scope.launch { _effects.emit(PasteImportEffect.NavigatePublish) }
    }

    fun onCancelClick() {
        importRepository.clear()
        scope.launch { _effects.emit(PasteImportEffect.NavigateBack) }
    }

    fun onDispose() {
        parseJob?.cancel()
        scope.cancel()
    }

    companion object {
        private const val TAG = "Echo/PasteVM"
    }
}

data class PasteImportUiState(
    val rawText: String = "",
    val detectedSeparator: Separator? = null,
    val cardCount: Int = 0,
    val previewCards: List<PreviewCard> = emptyList(),
    val isParsed: Boolean = false,
    val error: String? = null,
)

data class PreviewCard(
    val front: String,
    val back: String,
)

sealed interface PasteImportEffect {
    data object NavigatePublish : PasteImportEffect
    data object NavigateBack : PasteImportEffect
}
