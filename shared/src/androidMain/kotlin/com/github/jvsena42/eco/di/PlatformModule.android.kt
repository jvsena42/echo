package com.github.jvsena42.eco.di

import com.github.jvsena42.eco.data.pubky.AndroidPubkyClient
import com.github.jvsena42.eco.data.pubky.PubkyClient
import com.github.jvsena42.eco.data.storage.AndroidSecureSessionStore
import com.github.jvsena42.eco.data.storage.SecureSessionStore
import com.github.jvsena42.eco.presentation.onboarding.OnboardingViewModel
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
    single<SecureSessionStore> { AndroidSecureSessionStore(androidContext()) }
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
