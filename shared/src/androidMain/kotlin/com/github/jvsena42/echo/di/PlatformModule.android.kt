package com.github.jvsena42.echo.di

import com.github.jvsena42.echo.data.nexus.AndroidHttpFetcher
import com.github.jvsena42.echo.data.nexus.HttpFetcher
import com.github.jvsena42.echo.data.pubky.AndroidPubkyClient
import com.github.jvsena42.echo.data.pubky.PubkyClient
import com.github.jvsena42.echo.data.storage.AndroidSecureSessionStore
import com.github.jvsena42.echo.data.storage.SecureSessionStore
import com.github.jvsena42.echo.platform.AndroidSpeaker
import com.github.jvsena42.echo.platform.Speaker
import com.github.jvsena42.echo.presentation.onboarding.OnboardingViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

/** Direct Play Store listing — opens the Play app on-device rather than a web redirect. */
private const val PUBKY_RING_PLAY_STORE_URL =
    "https://play.google.com/store/apps/details?id=to.pubky.ring"

val androidPlatformModule: Module = module {
    single<PubkyClient> { AndroidPubkyClient() }
    single<HttpFetcher> { AndroidHttpFetcher() }
    single<SecureSessionStore> { AndroidSecureSessionStore(androidContext()) }
    single<Speaker> { AndroidSpeaker(androidContext()) }
    factory {
        OnboardingViewModel(
            identityRepository = get(),
            pubkyRingInstallUrl = PUBKY_RING_PLAY_STORE_URL,
        )
    }
}

fun initKoinAndroid(appDeclaration: KoinAppDeclaration = {}) {
    startKoin {
        appDeclaration()
        modules(sharedModule, androidPlatformModule)
    }
}
