package com.github.jvsena42.eco.presentation.onboarding

sealed interface OnboardingEffect {
    /** Open the Pubky Ring app to approve sign-in. Platform uses system deeplink APIs. */
    data class OpenDeeplink(val url: String) : OnboardingEffect

    /** Navigate to the install page for Pubky Ring (store listing). */
    data class OpenInstallPage(val url: String) : OnboardingEffect

    /** Onboarding complete — pop onboarding and navigate to the main app. */
    data object NavigateHome : OnboardingEffect
}
