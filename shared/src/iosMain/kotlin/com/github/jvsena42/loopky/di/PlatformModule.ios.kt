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
import com.github.jvsena42.loopky.data.repository.MediaRepository
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
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.platform.BackgroundTasks
import com.github.jvsena42.loopky.platform.IosBackgroundTasks
import com.github.jvsena42.loopky.platform.IosMediaProcessor
import com.github.jvsena42.loopky.platform.IosPubkyRingPresence
import com.github.jvsena42.loopky.platform.IosSpeaker
import com.github.jvsena42.loopky.platform.MediaProcessor
import com.github.jvsena42.loopky.platform.PubkyRingPresence
import com.github.jvsena42.loopky.platform.Speaker
import com.github.jvsena42.loopky.presentation.backup.BackupFileViewModel
import com.github.jvsena42.loopky.presentation.backup.BackupPhraseViewModel
import com.github.jvsena42.loopky.presentation.backup.BackupQuizViewModel
import com.github.jvsena42.loopky.presentation.backup.BackupRingViewModel
import com.github.jvsena42.loopky.presentation.backup.BackupStartViewModel
import com.github.jvsena42.loopky.presentation.decks.DeckDetailViewModel
import com.github.jvsena42.loopky.presentation.decks.DeckEditorViewModel
import com.github.jvsena42.loopky.presentation.decks.DecksLibraryViewModel
import com.github.jvsena42.loopky.presentation.decks.EditCardViewModel
import com.github.jvsena42.loopky.presentation.discover.DiscoverViewModel
import com.github.jvsena42.loopky.presentation.discover.SearchViewModel
import com.github.jvsena42.loopky.presentation.discover.TagBrowseViewModel
import com.github.jvsena42.loopky.presentation.home.HomeViewModel
import com.github.jvsena42.loopky.presentation.identity.UnregisteredKeyViewModel
import com.github.jvsena42.loopky.presentation.importflow.BulkImportViewModel
import com.github.jvsena42.loopky.presentation.importflow.PasteImportViewModel
import com.github.jvsena42.loopky.presentation.importflow.PublishDeckViewModel
import com.github.jvsena42.loopky.presentation.importflow.TriageViewModel
import com.github.jvsena42.loopky.presentation.media.ImageSheetViewModel
import com.github.jvsena42.loopky.presentation.onboarding.OnboardingViewModel
import com.github.jvsena42.loopky.presentation.profile.FollowListViewModel
import com.github.jvsena42.loopky.presentation.profile.FollowSource
import com.github.jvsena42.loopky.presentation.profile.FriendProfileViewModel
import com.github.jvsena42.loopky.presentation.profile.ProfileViewModel
import com.github.jvsena42.loopky.presentation.restore.RestoreFileViewModel
import com.github.jvsena42.loopky.presentation.restore.RestorePhraseViewModel
import com.github.jvsena42.loopky.presentation.settings.SettingsViewModel
import com.github.jvsena42.loopky.presentation.signup.InviteCodeViewModel
import com.github.jvsena42.loopky.presentation.signup.LightningVerificationViewModel
import com.github.jvsena42.loopky.presentation.signup.LocalSignupViewModel
import com.github.jvsena42.loopky.presentation.signup.PhoneVerificationViewModel
import com.github.jvsena42.loopky.presentation.signup.SignupStartViewModel
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
    single<MediaProcessor> { IosMediaProcessor() }
    single<PubkyRingPresence> { IosPubkyRingPresence() }
    single<BackgroundTasks> { IosBackgroundTasks(identityProvider = { get() }, decksProvider = { get() }) }
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

    /**
     * @param authorPubky the deck's author when it is someone else's, `null` for your own.
     *   `DeckDetailViewModel` only fetches a remote deck when this is non-null, so dropping it
     *   makes every deck opened from Discover fail as `NotFound`.
     */
    fun deckDetailViewModel(deckId: String, authorPubky: String?): DeckDetailViewModel =
        koin.get { parametersOf(deckId, authorPubky) }

    fun deckEditorViewModel(deckId: String?): DeckEditorViewModel =
        koin.get { parametersOf(deckId) }

    fun editCardViewModel(deckId: String, cardId: String): EditCardViewModel =
        koin.get { parametersOf(deckId, cardId) }

    /**
     * The study loop, and — with [isPreview] — the sample of a deck nobody has kept.
     *
     * A preview grades nothing and needs no session, so it carries the deck's author: its cards
     * are read from *their* homeserver rather than from a library the reader does not have.
     */
    fun studySessionViewModel(
        deckId: String?,
        isPreview: Boolean = false,
        previewAuthorPubky: String? = null,
    ): StudySessionViewModel =
        koin.get { parametersOf(deckId, isPreview, previewAuthorPubky) }

    fun discoverViewModel(): DiscoverViewModel = koin.get()

    fun searchViewModel(): SearchViewModel = koin.get()

    fun tagBrowseViewModel(tag: String): TagBrowseViewModel = koin.get { parametersOf(tag) }

    fun profileViewModel(): ProfileViewModel = koin.get()

    /**
     * @param appVersion the bundle's short version string. The Koin binding reads it from
     *   `params.getOrNull() ?: ""`, so resolving with no parameters left the About row blank.
     */
    fun settingsViewModel(appVersion: String): SettingsViewModel =
        koin.get { parametersOf(appVersion) }

    fun pasteImportViewModel(): PasteImportViewModel = koin.get()

    /** Bulk file import. Resolvable now that `MediaProcessor` has an iOS binding. */
    fun bulkImportViewModel(): BulkImportViewModel = koin.get()

    fun triageViewModel(): TriageViewModel = koin.get()

    fun imageSheetViewModel(): ImageSheetViewModel = koin.get()

    /**
     * Blob bytes for a stored media ref, so SwiftUI can draw a picture that is not a web URL.
     *
     * `authorPubky` is the *deck's* author, not the signed-in user: resolving against the session
     * makes media on any deck you do not own unreachable, because it looks for the blob under your
     * own pubky. Returns null rather than throwing — a picture that will not load is not worth
     * failing a card over.
     */
    suspend fun mediaBytes(authorPubky: String, deckId: String, ref: MediaRef): ByteArray? =
        koin.get<MediaRepository>().get(authorPubky, deckId, ref).getOrNull()

    fun publishDeckViewModel(): PublishDeckViewModel = koin.get()

    fun friendProfileViewModel(pubky: String): FriendProfileViewModel =
        koin.get { parametersOf(pubky) }

    /**
     * Both parameters are mandatory — the Koin binding reads them with `params.get()`, not
     * `getOrNull()`, so a missing one throws rather than defaulting.
     */
    fun followListViewModel(pubky: String, source: FollowSource): FollowListViewModel =
        koin.get { parametersOf(pubky, source) }

    // ---- Identity: signup, backup, restore (#149) ----

    fun signupStartViewModel(): SignupStartViewModel = koin.get()

    fun inviteCodeViewModel(): InviteCodeViewModel = koin.get()

    fun phoneVerificationViewModel(): PhoneVerificationViewModel = koin.get()

    fun lightningVerificationViewModel(): LightningVerificationViewModel = koin.get()

    /**
     * @param registerHeldKey true when the account is being minted for a key Loopky *already*
     *   holds — the unregistered-key path — rather than a fresh one.
     */
    fun localSignupViewModel(registerHeldKey: Boolean): LocalSignupViewModel =
        koin.get { parametersOf(registerHeldKey) }

    fun backupStartViewModel(): BackupStartViewModel = koin.get()

    fun backupPhraseViewModel(): BackupPhraseViewModel = koin.get()

    fun backupQuizViewModel(): BackupQuizViewModel = koin.get()

    fun backupFileViewModel(): BackupFileViewModel = koin.get()

    fun backupRingViewModel(): BackupRingViewModel = koin.get()

    fun restorePhraseViewModel(): RestorePhraseViewModel = koin.get()

    fun restoreFileViewModel(): RestoreFileViewModel = koin.get()

    /**
     * @param custody what holds this key — a `KeyCustody`, which decides whether the screen offers
     *   to register it or only to check the phrase again.
     */
    fun unregisteredKeyViewModel(pubky: String, custody: KeyCustody): UnregisteredKeyViewModel =
        koin.get { parametersOf(pubky, custody) }

    /**
     * Tear a ViewModel down when its SwiftUI view goes away.
     *
     * SwiftUI has no `ViewModelStore`, and androidx's own `ViewModel.clear()` is internal — so
     * neither is exported to Objective-C and a Swift screen has no way to release the VM it
     * resolved. Cancelling `viewModelScope` is the part that matters: it is what stops in-flight
     * repository work, and it is what `clear()` does before invoking `onCleared()`.
     *
     * Cancelling is only half of androidx's `clear()`; the other half is `onCleared()`, which
     * cannot be called from here — it is `protected` in Kotlin. Swift can reach it, so screens go
     * through `ViewModel.release()` (`ViewModelRelease.swift`) rather than calling this directly.
     *
     * Swift screens must not reuse the instance afterwards.
     */
    fun clear(viewModel: ViewModel) {
        viewModel.viewModelScope.cancel()
    }
}
