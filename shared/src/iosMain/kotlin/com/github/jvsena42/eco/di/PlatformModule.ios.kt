package com.github.jvsena42.eco.di

import com.github.jvsena42.eco.data.nexus.HttpFetcher
import com.github.jvsena42.eco.data.nexus.IosHttpFetcher
import com.github.jvsena42.eco.data.pubky.PubkyClient
import com.github.jvsena42.eco.data.storage.IosSecureSessionStore
import com.github.jvsena42.eco.data.storage.SecureSessionStore
import com.github.jvsena42.eco.platform.IosSpeaker
import com.github.jvsena42.eco.platform.Speaker
import com.github.jvsena42.eco.presentation.decks.DeckDetailViewModel
import com.github.jvsena42.eco.presentation.decks.DeckEditorViewModel
import com.github.jvsena42.eco.presentation.decks.DecksLibraryViewModel
import com.github.jvsena42.eco.presentation.decks.EditCardViewModel
import com.github.jvsena42.eco.presentation.discover.DiscoverViewModel
import com.github.jvsena42.eco.presentation.home.HomeViewModel
import com.github.jvsena42.eco.presentation.import_flow.PasteImportViewModel
import com.github.jvsena42.eco.presentation.import_flow.PublishDeckViewModel
import com.github.jvsena42.eco.presentation.onboarding.OnboardingViewModel
import com.github.jvsena42.eco.presentation.profile.FriendProfileViewModel
import com.github.jvsena42.eco.presentation.profile.ProfileViewModel
import com.github.jvsena42.eco.presentation.study.StudySessionViewModel
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import org.koin.mp.KoinPlatform

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
    single<HttpFetcher> { IosHttpFetcher() }
    single<SecureSessionStore> { IosSecureSessionStore() }
    single<Speaker> { IosSpeaker() }
}

/** Resolver helper for SwiftUI — avoids depending on Koin Swift bridges in v1. */
object IosDependencies {
    private val koin: Koin get() = KoinPlatform.getKoin()

    fun onboardingViewModel(): OnboardingViewModel = koin.get()

    fun homeViewModel(): HomeViewModel = koin.get()

    fun decksLibraryViewModel(): DecksLibraryViewModel = koin.get()

    fun deckDetailViewModel(deckId: String): DeckDetailViewModel =
        koin.get { parametersOf(deckId) }

    fun deckEditorViewModel(deckId: String?): DeckEditorViewModel =
        koin.get { parametersOf(deckId) }

    fun editCardViewModel(deckId: String, cardId: String): EditCardViewModel =
        koin.get { parametersOf(deckId, cardId) }

    fun studySessionViewModel(deckId: String?): StudySessionViewModel =
        koin.get { parametersOf(deckId) }

    fun discoverViewModel(): DiscoverViewModel = koin.get()

    fun profileViewModel(): ProfileViewModel = koin.get()

    fun pasteImportViewModel(): PasteImportViewModel = koin.get()

    fun publishDeckViewModel(): PublishDeckViewModel = koin.get()

    fun friendProfileViewModel(pubky: String): FriendProfileViewModel =
        koin.get { parametersOf(pubky) }
}
