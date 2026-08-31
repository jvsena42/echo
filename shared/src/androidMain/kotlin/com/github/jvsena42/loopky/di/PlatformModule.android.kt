package com.github.jvsena42.loopky.di

import com.github.jvsena42.loopky.data.homegate.HomegateClient
import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.data.nexus.AndroidHttpFetcher
import com.github.jvsena42.loopky.data.nexus.HttpFetcher
import com.github.jvsena42.loopky.data.nexus.NexusClient
import com.github.jvsena42.loopky.data.pubky.AndroidPubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.storage.AndroidAppPreferences
import com.github.jvsena42.loopky.data.storage.AndroidLocalKeyStore
import com.github.jvsena42.loopky.data.storage.AndroidPendingReviewStore
import com.github.jvsena42.loopky.data.storage.AndroidSecureSessionStore
import com.github.jvsena42.loopky.data.storage.AndroidSignupTokenStore
import com.github.jvsena42.loopky.data.storage.AndroidStudyProgressStore
import com.github.jvsena42.loopky.data.storage.AndroidUnsplashKeyStore
import com.github.jvsena42.loopky.data.storage.AppPreferences
import com.github.jvsena42.loopky.data.storage.LocalKeyStore
import com.github.jvsena42.loopky.data.storage.PendingReviewStore
import com.github.jvsena42.loopky.data.storage.SecureSessionStore
import com.github.jvsena42.loopky.data.storage.SignupTokenStore
import com.github.jvsena42.loopky.data.storage.StudyProgressStore
import com.github.jvsena42.loopky.data.storage.UnsplashKeyStore
import com.github.jvsena42.loopky.data.unsplash.UnsplashClient
import com.github.jvsena42.loopky.platform.AndroidBackgroundTasks
import com.github.jvsena42.loopky.platform.AndroidMediaProcessor
import com.github.jvsena42.loopky.platform.AndroidPasswordManagerPresence
import com.github.jvsena42.loopky.platform.AndroidPubkyRingPresence
import com.github.jvsena42.loopky.platform.AndroidSpeaker
import com.github.jvsena42.loopky.platform.AndroidSpeechRecognizer
import com.github.jvsena42.loopky.platform.BackgroundTasks
import com.github.jvsena42.loopky.platform.MediaProcessor
import com.github.jvsena42.loopky.platform.PasswordManagerPresence
import com.github.jvsena42.loopky.platform.PubkyRingPresence
import com.github.jvsena42.loopky.platform.Speaker
import com.github.jvsena42.loopky.platform.SpeechRecognizer
import com.github.jvsena42.loopky.presentation.onboarding.OnboardingViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

/** Direct Play Store listing — opens the Play app on-device rather than a web redirect. */
private const val PUBKY_RING_PLAY_STORE_URL =
    "https://play.google.com/store/apps/details?id=to.pubky.ring"

fun androidPlatformModule(
    unsplashFallbackKey: String,
    nexusBaseUrl: String,
    pubkyEnvironment: PubkyEnvironment,
): Module = module {
    single<PubkyClient> { AndroidPubkyClient() }
    single<HttpFetcher> { AndroidHttpFetcher() }
    single<SecureSessionStore> { AndroidSecureSessionStore(androidContext()) }
    single<AppPreferences> { AndroidAppPreferences(androidContext()) }
    single<PendingReviewStore> { AndroidPendingReviewStore(androidContext()) }
    single<StudyProgressStore> { AndroidStudyProgressStore(androidContext()) }
    single<UnsplashKeyStore> { AndroidUnsplashKeyStore(androidContext()) }
    single<SignupTokenStore> { AndroidSignupTokenStore(androidContext()) }
    single<LocalKeyStore> { AndroidLocalKeyStore(androidContext()) }
    single<Speaker> { AndroidSpeaker(androidContext()) }
    single<MediaProcessor> { AndroidMediaProcessor() }
    single<SpeechRecognizer> { AndroidSpeechRecognizer(androidContext()) }
    single<BackgroundTasks> { AndroidBackgroundTasks(androidContext()) }
    single<PasswordManagerPresence> { AndroidPasswordManagerPresence() }

    single<PubkyRingPresence> {
        AndroidPubkyRingPresence(androidContext(), installUrl = PUBKY_RING_PLAY_STORE_URL)
    }
    single { NexusClient(http = get(), baseUrl = nexusBaseUrl) }
    single { pubkyEnvironment }
    single { HomegateClient(http = get(), baseUrl = pubkyEnvironment.homegateBaseUrl) }
    single { UnsplashClient(http = get(), keyStore = get(), fallbackKey = unsplashFallbackKey) }
    factory {
        OnboardingViewModel(
            identityRepository = get(),
            ringPresence = get(),
            pubkyRingInstallUrl = PUBKY_RING_PLAY_STORE_URL,
        )
    }
}

/**
 * [unsplashFallbackKey] is the build-time Unsplash key. It is only a fallback: a key the user
 * saves in Settings takes precedence, and this one is never shown to them.
 *
 * [nexusBaseUrl] has no default on purpose: the indexer is the one endpoint that differs between
 * environments, and a release build must never fall back to the staging network (#42). The app
 * passes `BuildConfig.NEXUS_BASE_URL`, which the build type picks.
 *
 * [pubkyEnvironment] likewise, and for a sharper reason: it decides which Homegate mints signup
 * tokens and which homeserver those tokens are valid on. A token is single-use, so one minted
 * against the wrong environment is rejected and gone. Resolved once here rather than read live,
 * because [HomegateClient] is a singleton that captures its base URL when constructed — a debug
 * build's Settings override therefore applies on the next launch, which that screen states.
 */
fun initKoinAndroid(
    unsplashFallbackKey: String = "",
    nexusBaseUrl: String,
    pubkyEnvironment: PubkyEnvironment,
    appDeclaration: KoinAppDeclaration = {},
) {
    startKoin {
        appDeclaration()
        modules(sharedModule, androidPlatformModule(unsplashFallbackKey, nexusBaseUrl, pubkyEnvironment))
    }
}
