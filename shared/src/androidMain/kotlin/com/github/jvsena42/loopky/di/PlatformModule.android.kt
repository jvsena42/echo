package com.github.jvsena42.loopky.di

import com.github.jvsena42.loopky.data.nexus.AndroidHttpFetcher
import com.github.jvsena42.loopky.data.nexus.HttpFetcher
import com.github.jvsena42.loopky.data.pubky.AndroidPubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.storage.AndroidSecureSessionStore
import com.github.jvsena42.loopky.data.storage.SecureSessionStore
import com.github.jvsena42.loopky.data.unsplash.UnsplashClient
import com.github.jvsena42.loopky.platform.AndroidMediaProcessor
import com.github.jvsena42.loopky.platform.AndroidSpeaker
import com.github.jvsena42.loopky.platform.AndroidSpeechRecognizer
import com.github.jvsena42.loopky.platform.MediaProcessor
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

fun androidPlatformModule(unsplashAccessKey: String): Module = module {
    single<PubkyClient> { AndroidPubkyClient() }
    single<HttpFetcher> { AndroidHttpFetcher() }
    single<SecureSessionStore> { AndroidSecureSessionStore(androidContext()) }
    single<Speaker> { AndroidSpeaker(androidContext()) }
    single<MediaProcessor> { AndroidMediaProcessor() }
    single<SpeechRecognizer> { AndroidSpeechRecognizer(androidContext()) }
    single { UnsplashClient(http = get(), accessKey = unsplashAccessKey) }
    factory {
        OnboardingViewModel(
            identityRepository = get(),
            pubkyRingInstallUrl = PUBKY_RING_PLAY_STORE_URL,
        )
    }
}

fun initKoinAndroid(
    unsplashAccessKey: String = "",
    appDeclaration: KoinAppDeclaration = {},
) {
    startKoin {
        appDeclaration()
        modules(sharedModule, androidPlatformModule(unsplashAccessKey))
    }
}
