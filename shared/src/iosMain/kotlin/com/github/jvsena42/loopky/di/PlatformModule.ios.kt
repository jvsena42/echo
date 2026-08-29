package com.github.jvsena42.loopky.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.homegate.HomegateClient
import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.data.nexus.HttpFetcher
import com.github.jvsena42.loopky.data.nexus.IosHttpFetcher
import com.github.jvsena42.loopky.data.nexus.NexusClient
import com.github.jvsena42.loopky.data.pubky.IosPubkyClientAdapter
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.RawPubkyClient
import com.github.jvsena42.loopky.data.storage.AppPreferences
import com.github.jvsena42.loopky.data.storage.IosAppPreferences
import com.github.jvsena42.loopky.data.storage.IosLocalKeyStore
import com.github.jvsena42.loopky.data.storage.IosPendingReviewStore
import com.github.jvsena42.loopky.data.storage.IosSecureSessionStore
import com.github.jvsena42.loopky.data.storage.IosSignupTokenStore
import com.github.jvsena42.loopky.data.storage.IosStudyProgressStore
import com.github.jvsena42.loopky.data.storage.IosUnsplashKeyStore
import com.github.jvsena42.loopky.data.storage.LocalKeyStore
import com.github.jvsena42.loopky.data.storage.PendingReviewStore
import com.github.jvsena42.loopky.data.storage.SecureSessionStore
import com.github.jvsena42.loopky.data.storage.SignupTokenStore
import com.github.jvsena42.loopky.data.storage.StudyProgressStore
import com.github.jvsena42.loopky.data.storage.UnsplashKeyStore
import com.github.jvsena42.loopky.data.unsplash.UnsplashClient
import com.github.jvsena42.loopky.platform.BackgroundTasks
import com.github.jvsena42.loopky.platform.IosBackgroundTasks
import com.github.jvsena42.loopky.platform.IosPubkyRingPresence
import com.github.jvsena42.loopky.platform.IosSpeaker
import com.github.jvsena42.loopky.platform.PubkyRingPresence
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
import kotlinx.coroutines.cancel
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
 *
 * [unsplashFallbackKey] is the build-time Unsplash key — a fallback behind whatever the user
 * saves in Settings, and never shown to them. Blank is fine; web image search then reports that a
 * key is needed instead of failing silently.
 *
 * [nexusBaseUrl] is the Pubky Nexus indexer, which differs between environments — Swift passes
 * staging under `#if DEBUG` and production otherwise. No default, so a release build cannot
 * silently fall back to staging (#42).
 *
 * [pubkyEnvironmentName] is a [PubkyEnvironment] name, picked the same `#if DEBUG` way. It decides
 * which Homegate mints signup tokens *and* which homeserver those tokens are valid on — an
 * unrecognised name resolves to production, because a token minted against the wrong environment
 * is rejected and, being single-use, is gone.
 */
fun doInitKoin(
    rawPubkyClient: RawPubkyClient,
    nexusBaseUrl: String,
    unsplashFallbackKey: String,
    pubkyEnvironmentName: String,
) {
    val environment = PubkyEnvironment.fromNameOrProduction(pubkyEnvironmentName)
    startKoin {
        modules(
            sharedModule,
            iosPlatformModule(rawPubkyClient, nexusBaseUrl, unsplashFallbackKey, environment),
        )
    }
    // BGTaskScheduler rejects a handler registered after the app has finished launching, so this
    // cannot be deferred to the first schedule (#53).
    (KoinPlatform.getKoin().get<BackgroundTasks>() as? IosBackgroundTasks)?.register()
}

private fun iosPlatformModule(
    rawPubkyClient: RawPubkyClient,
    nexusBaseUrl: String,
    unsplashFallbackKey: String,
    pubkyEnvironment: PubkyEnvironment,
): Module = module {
    single<PubkyClient> { IosPubkyClientAdapter(rawPubkyClient) }
    single<HttpFetcher> { IosHttpFetcher() }
    single { NexusClient(http = get(), baseUrl = nexusBaseUrl) }
    single { pubkyEnvironment }
    single { HomegateClient(http = get(), baseUrl = pubkyEnvironment.homegateBaseUrl) }
    single<SecureSessionStore> { IosSecureSessionStore() }
    single<AppPreferences> { IosAppPreferences() }
    single<PendingReviewStore> { IosPendingReviewStore() }
    single<StudyProgressStore> { IosStudyProgressStore() }
    single<UnsplashKeyStore> { IosUnsplashKeyStore() }
    single<SignupTokenStore> { IosSignupTokenStore() }
    single<LocalKeyStore> { IosLocalKeyStore() }
    single { UnsplashClient(http = get(), keyStore = get(), fallbackKey = unsplashFallbackKey) }
    single<Speaker> { IosSpeaker() }
    single<PubkyRingPresence> { IosPubkyRingPresence() }
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

    /**
     * Tear a ViewModel down when its SwiftUI view goes away.
     *
     * SwiftUI has no `ViewModelStore`, and androidx's own `ViewModel.clear()` is internal — so
     * neither is exported to Objective-C and a Swift screen has no way to release the VM it
     * resolved. Cancelling `viewModelScope` is the part that matters: it is what stops in-flight
     * repository work, and it is what `clear()` does before invoking `onCleared()`.
     *
     * Call it from `.onDisappear`. Swift screens must not reuse the instance afterwards.
     */
    fun clear(viewModel: ViewModel) {
        viewModel.viewModelScope.cancel()
    }
}
