package com.github.jvsena42.eco.di

import com.github.jvsena42.eco.data.pubky.MutableSessionProvider
import com.github.jvsena42.eco.data.pubky.SessionProvider
import com.github.jvsena42.eco.data.repository.CardRepository
import com.github.jvsena42.eco.data.repository.DeckRepository
import com.github.jvsena42.eco.data.repository.IdentityRepository
import com.github.jvsena42.eco.data.repository.MediaRepository
import com.github.jvsena42.eco.data.repository.impl.CardRepositoryImpl
import com.github.jvsena42.eco.data.repository.impl.DeckRepositoryImpl
import com.github.jvsena42.eco.data.repository.impl.IdentityRepositoryImpl
import com.github.jvsena42.eco.data.repository.impl.MediaRepositoryImpl
import com.github.jvsena42.eco.presentation.onboarding.OnboardingViewModel
import org.koin.dsl.module

/**
 * Shared commonMain Koin module. Platform modules must additionally provide bindings for
 * [com.github.jvsena42.eco.data.pubky.PubkyClient] and
 * [com.github.jvsena42.eco.data.storage.SecureSessionStore].
 */
val sharedModule = module {
    single { MutableSessionProvider() }
    single<SessionProvider> { get<MutableSessionProvider>() }

    single<IdentityRepository> {
        IdentityRepositoryImpl(
            pubky = get(),
            sessionStore = get(),
            sessionProvider = get(),
        )
    }

    single<CardRepository> { CardRepositoryImpl(get(), get()) }
    single<DeckRepository> { DeckRepositoryImpl(get(), get(), get()) }
    single<MediaRepository> { MediaRepositoryImpl(get(), get()) }

    factory { OnboardingViewModel(identityRepository = get()) }
}
