package com.github.jvsena42.loopky.presentation.signup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.repository.SignupRepository
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

/** A hand-issued invite code — the fallback when SMS and Lightning are not offered here. */
class InviteCodeViewModel(
    private val signupRepository: SignupRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(InviteCodeUiState())
    val state: StateFlow<InviteCodeUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<InviteCodeEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<InviteCodeEffect> = _effects.asSharedFlow()

    private var submitJob: Job? = null

    fun onCodeChange(code: String) {
        // Clearing the error as they type keeps a stale "that code is wrong" from sitting under a
        // code they have since corrected.
        _state.update { it.copy(code = code, error = null) }
    }

    fun onSubmit() {
        if (submitJob?.isActive == true) return
        submitJob = viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            signupRepository.redeemInviteCode(_state.value.code)
                .onSuccess {
                    _state.update { it.copy(isSubmitting = false) }
                    _effects.emit(InviteCodeEffect.NavigateToHandoff)
                }
                .onFailure { err ->
                    Log.e(TAG, "onSubmit: FAILED — ${err.message}", err)
                    _state.update { it.copy(isSubmitting = false, error = err.toSignupError()) }
                }
        }
    }

    private companion object {
        const val TAG = "Loopky/InviteCodeVM"
    }
}

data class InviteCodeUiState(
    val code: String = "",
    val isSubmitting: Boolean = false,
    val error: SignupError? = null,
) {
    /** Length only — the shape is checked by the repository, which owns the format. */
    val canSubmit: Boolean get() = code.isNotBlank() && !isSubmitting
}

sealed interface InviteCodeEffect {
    data object NavigateToHandoff : InviteCodeEffect
}
