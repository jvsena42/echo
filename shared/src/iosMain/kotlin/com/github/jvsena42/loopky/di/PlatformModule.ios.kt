package com.github.jvsena42.loopky.di

import com.github.jvsena42.loopky.data.nexus.HttpFetcher
import com.github.jvsena42.loopky.data.nexus.IosHttpFetcher
import com.github.jvsena42.loopky.data.pubky.IosPubkyClientAdapter
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.RawPubkyClient
import com.github.jvsena42.loopky.data.storage.AppPreferences
import com.github.jvsena42.loopky.data.storage.IosAppPreferences
import com.github.jvsena42.loopky.data.storage.IosSecureSessionStore
import com.github.jvsena42.loopky.data.storage.SecureSessionStore
import com.github.jvsena42.loopky.platform.BackgroundTasks
import com.github.jvsena42.loopky.platform.IosBackgroundTasks
import com.github.jvsena42.loopky.platform.IosSpeaker
import com.github.jvsena42.loopky.platform.Speaker
import com.github.jvsena42.loopky.presentation.decks.DeckDetailViewModel
import com.github.jvsena42.loopky.presentation.decks.DeckEditorViewModel
import com.github.jvsena42.loopky.presentation.decks.DecksLibraryViewModel
import com.github.jvsena42.loopky.presentation.decks.EditCardViewModel
import com.github.jvsena42.loopky.presentation.discover.DiscoverViewModel
import com.github.jvsena42.loopky.presentation.discover.SearchViewModel
import com.github.jvsena42.loopky.presentation.discover.TagBrowseViewModel
import com.github.jvsena42.loopky.presentation.home.HomeViewModel
import com.github.jvsena42.loopky.presentation.importflow.PasteImportViewModel
import com.github.jvsena42.loopky.presentation.importflow.PublishDeckViewModel
import com.github.jvsena42.loopky.presentation.onboarding.OnboardingViewModel
import com.github.jvsena42.loopky.presentation.profile.FriendProfileViewModel
import com.github.jvsena42.loopky.presentation.profile.ProfileViewModel
import com.github.jvsena42.loopky.presentation.settings.SettingsViewModel
import com.github.jvsena42.loopky.presentation.study.StudySessionViewModel
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
    // BGTaskScheduler rejects a handler registered after the app has finished launching, so this
    // cannot be deferred to the first schedule (#53).
    (KoinPlatform.getKoin().get<BackgroundTasks>() as? IosBackgroundTasks)?.register()
}

private fun iosPlatformModule(rawPubkyClient: RawPubkyClient): Module = module {
    single<PubkyClient> { IosPubkyClientAdapter(rawPubkyClient) }
    single<HttpFetcher> { IosHttpFetcher() }
    single<SecureSessionStore> { IosSecureSessionStore() }
    single<AppPreferences> { IosAppPreferences() }
    single<Speaker> { IosSpeaker() }
    single<BackgroundTasks> { IosBackgroundTasks(identity = get(), decks = get()) }
}

/** Resolver helper for SwiftUI — avoids depending on Koin Swift bridges in v1. */
// One function per ViewModel by construction, so the count tracks the number of screens rather
// than any complexity here; splitting it would only move the same list into two files.
@Suppress("TooManyFunctions")
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

    fun searchViewModel(): SearchViewModel = koin.get()

    fun tagBrowseViewModel(tag: String): TagBrowseViewModel = koin.get { parametersOf(tag) }

    fun profileViewModel(): ProfileViewModel = koin.get()

    fun settingsViewModel(): SettingsViewModel = koin.get()

    fun pasteImportViewModel(): PasteImportViewModel = koin.get()

    fun publishDeckViewModel(): PublishDeckViewModel = koin.get()

    fun friendProfileViewModel(pubky: String): FriendProfileViewModel =
        koin.get { parametersOf(pubky) }
}
