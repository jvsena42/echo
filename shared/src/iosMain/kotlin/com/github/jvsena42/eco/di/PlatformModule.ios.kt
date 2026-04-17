package com.github.jvsena42.eco.di

import com.github.jvsena42.eco.data.pubky.PubkyClient
import com.github.jvsena42.eco.data.storage.IosSecureSessionStore
import com.github.jvsena42.eco.data.storage.SecureSessionStore
import com.github.jvsena42.eco.presentation.decks.DeckDetailViewModel
import com.github.jvsena42.eco.presentation.decks.DeckEditorViewModel
import com.github.jvsena42.eco.presentation.decks.DecksLibraryViewModel
import com.github.jvsena42.eco.presentation.decks.EditCardViewModel
import com.github.jvsena42.eco.presentation.home.HomeViewModel
import com.github.jvsena42.eco.presentation.onboarding.OnboardingViewModel
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

/**
 * Starts Koin for the iOS app. Swift supplies the [PubkyClient] instance (implemented in
 * Swift in `iosApp/iosApp/Pubky/IosPubkyClient.swift`) because the concrete type crosses the
 * Kotlin/Swift interop boundary and cannot be constructed from Kotlin.
 */
fun doInitKoin(pubkyClient: PubkyClient) {
    startKoin {
        modules(sharedModule, iosPlatformModule(pubkyClient))
    }
}

private fun iosPlatformModule(pubkyClient: PubkyClient): Module = module {
    single<PubkyClient> { pubkyClient }
    single<SecureSessionStore> { IosSecureSessionStore() }
}

/** Resolver helper for SwiftUI — avoids depending on Koin Swift bridges in v1. */
object IosDependencies {
    fun onboardingViewModel(): OnboardingViewModel =
        org.koin.core.context.GlobalContext.get().get()

    fun homeViewModel(): HomeViewModel =
        org.koin.core.context.GlobalContext.get().get()

    fun decksLibraryViewModel(): DecksLibraryViewModel =
        org.koin.core.context.GlobalContext.get().get()

    fun deckDetailViewModel(deckId: String): DeckDetailViewModel =
        org.koin.core.context.GlobalContext.get().get { parametersOf(deckId) }

    fun deckEditorViewModel(deckId: String?): DeckEditorViewModel =
        org.koin.core.context.GlobalContext.get().get { parametersOf(deckId) }

    fun editCardViewModel(deckId: String, cardId: String): EditCardViewModel =
        org.koin.core.context.GlobalContext.get().get { parametersOf(deckId, cardId) }
}
