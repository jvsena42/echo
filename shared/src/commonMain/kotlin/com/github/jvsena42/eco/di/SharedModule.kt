package com.github.jvsena42.eco.di

import com.github.jvsena42.eco.data.pubky.MutableSessionProvider
import com.github.jvsena42.eco.data.pubky.SessionProvider
import com.github.jvsena42.eco.data.pubky.SessionRevalidator
import com.github.jvsena42.eco.data.repository.CardRepository
import com.github.jvsena42.eco.data.repository.DeckRepository
import com.github.jvsena42.eco.data.repository.IdentityRepository
import com.github.jvsena42.eco.data.repository.ImportRepository
import com.github.jvsena42.eco.data.repository.MediaRepository
import com.github.jvsena42.eco.data.repository.impl.CardRepositoryImpl
import com.github.jvsena42.eco.data.repository.impl.ImportRepositoryImpl
import com.github.jvsena42.eco.data.repository.impl.DeckRepositoryImpl
import com.github.jvsena42.eco.data.repository.impl.IdentityRepositoryImpl
import com.github.jvsena42.eco.data.repository.impl.MediaRepositoryImpl
import com.github.jvsena42.eco.data.repository.impl.SessionRevalidatorImpl
import com.github.jvsena42.eco.presentation.decks.DeckDetailViewModel
import com.github.jvsena42.eco.presentation.decks.DeckEditorViewModel
import com.github.jvsena42.eco.presentation.decks.DecksLibraryViewModel
import com.github.jvsena42.eco.presentation.decks.EditCardViewModel
import com.github.jvsena42.eco.presentation.home.HomeViewModel
import com.github.jvsena42.eco.presentation.import_flow.PasteImportViewModel
import com.github.jvsena42.eco.presentation.import_flow.PublishDeckViewModel
import com.github.jvsena42.eco.presentation.onboarding.OnboardingViewModel
import com.github.jvsena42.eco.presentation.profile.ProfileViewModel
import org.koin.core.parameter.parametersOf
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

    single<SessionRevalidator> { SessionRevalidatorImpl(get(), get(), get()) }

    single<CardRepository> { CardRepositoryImpl(get(), get(), get()) }
    single<DeckRepository> { DeckRepositoryImpl(get(), get(), get(), get()) }
    single<MediaRepository> { MediaRepositoryImpl(get(), get(), get()) }
    single<ImportRepository> { ImportRepositoryImpl() }

    factory { OnboardingViewModel(identityRepository = get()) }
    factory { HomeViewModel(identityRepository = get(), deckRepository = get()) }
    factory { DecksLibraryViewModel(deckRepository = get(), identityRepository = get()) }
    factory { params -> DeckDetailViewModel(deckId = params.get(), deckRepository = get(), cardRepository = get(), identityRepository = get()) }
    factory { params -> DeckEditorViewModel(deckId = params.getOrNull(), deckRepository = get(), cardRepository = get(), identityRepository = get()) }
    factory { params -> EditCardViewModel(deckId = params.get(0), cardId = params.get(1), cardRepository = get(), deckRepository = get(), mediaRepository = get()) }
    factory { PasteImportViewModel(importRepository = get()) }
    factory { PublishDeckViewModel(importRepository = get(), deckRepository = get(), identityRepository = get()) }
    factory { ProfileViewModel(identityRepository = get(), deckRepository = get()) }
}
