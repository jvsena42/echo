package com.github.jvsena42.eco.presentation.onboarding

import com.github.jvsena42.eco.domain.model.Session

sealed interface OnboardingUiState {
    /** Initial/resting state — CTA enabled. */
    data object Idle : OnboardingUiState

    /** Calling `startAuthFlow`, no deeplink yet. CTA disabled, spinner on button. */
    data object Starting : OnboardingUiState

    /** Deeplink dispatched; waiting for Pubky Ring to POST back via the relay. */
    data object AwaitingApproval : OnboardingUiState

    /** Parsing the callback + persisting session. Full-screen progress overlay acceptable. */
    data object Verifying : OnboardingUiState

    /** Terminal success — the VM will also emit [OnboardingEffect.NavigateHome] once. */
    data class Success(val session: Session) : OnboardingUiState

    /** Sign-in failed; show message + retry CTA. */
    data class Error(val message: String) : OnboardingUiState
}
