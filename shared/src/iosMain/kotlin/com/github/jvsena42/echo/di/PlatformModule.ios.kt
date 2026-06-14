package com.github.jvsena42.echo.di

import com.github.jvsena42.echo.data.nexus.HttpFetcher
import com.github.jvsena42.echo.data.nexus.IosHttpFetcher
import com.github.jvsena42.echo.data.pubky.IosPubkyClientAdapter
import com.github.jvsena42.echo.data.pubky.PubkyClient
import com.github.jvsena42.echo.data.pubky.RawPubkyClient
import com.github.jvsena42.echo.data.storage.IosSecureSessionStore
import com.github.jvsena42.echo.data.storage.SecureSessionStore
import com.github.jvsena42.echo.platform.IosSpeaker
import com.github.jvsena42.echo.platform.Speaker
import com.github.jvsena42.echo.presentation.decks.DeckDetailViewModel
import com.github.jvsena42.echo.presentation.decks.DeckEditorViewModel
import com.github.jvsena42.echo.presentation.decks.DecksLibraryViewModel
import com.github.jvsena42.echo.presentation.decks.EditCardViewModel
import com.github.jvsena42.echo.presentation.discover.DiscoverViewModel
import com.github.jvsena42.echo.presentation.home.HomeViewModel
import com.github.jvsena42.echo.presentation.importflow.PasteImportViewModel
import com.github.jvsena42.echo.presentation.importflow.PublishDeckViewModel
import com.github.jvsena42.echo.presentation.onboarding.OnboardingViewModel
import com.github.jvsena42.echo.presentation.profile.FriendProfileViewModel
import com.github.jvsena42.echo.presentation.profile.ProfileViewModel
import com.github.jvsena42.echo.presentation.settings.SettingsViewModel
import com.github.jvsena42.echo.presentation.study.StudySessionViewModel
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import org.koin.mp.KoinPlatform

/**
 * Starts Koin for the iOS app. Swift supplies a [RawPubkyClient] (the dumb `[status, payload]`
 * pass-through implemented in `iosApp/iosApp/Pubky/IosPubkyClient.swift`); it is wrapped into
 * the shared [PubkyClient] contract by [IosPubkyClientAdapter] on the Kotlin side, because
 * `kotlin.Result` and suspend functions cannot be implemented from Swift.
 */
fun doInitKoin(rawPubkyClient: RawPubkyClient) {
    startKoin {
        modules(sharedModule, iosPlatformModule(rawPubkyClient))
    }
}

private fun iosPlatformModule(rawPubkyClient: RawPubkyClient): Module = module {
    single<PubkyClient> { IosPubkyClientAdapter(rawPubkyClient) }
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

    fun settingsViewModel(): SettingsViewModel = koin.get()

    fun pasteImportViewModel(): PasteImportViewModel = koin.get()

    fun publishDeckViewModel(): PublishDeckViewModel = koin.get()

    fun friendProfileViewModel(pubky: String): FriendProfileViewModel =
        koin.get { parametersOf(pubky) }
}
