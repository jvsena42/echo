package com.github.jvsena42.echo.di

import com.github.jvsena42.echo.data.nexus.NexusClient
import com.github.jvsena42.echo.data.pubky.MutableSessionProvider
import com.github.jvsena42.echo.data.pubky.SessionProvider
import com.github.jvsena42.echo.data.pubky.SessionRevalidator
import com.github.jvsena42.echo.data.repository.CardRepository
import com.github.jvsena42.echo.data.repository.DeckRepository
import com.github.jvsena42.echo.data.repository.DiscoveryRepository
import com.github.jvsena42.echo.data.repository.IdentityRepository
import com.github.jvsena42.echo.data.repository.ImportRepository
import com.github.jvsena42.echo.data.repository.MediaRepository
import com.github.jvsena42.echo.data.repository.SrsRepository
import com.github.jvsena42.echo.data.repository.TagRepository
import com.github.jvsena42.echo.data.repository.impl.CardRepositoryImpl
import com.github.jvsena42.echo.data.repository.impl.DiscoveryRepositoryImpl
import com.github.jvsena42.echo.data.repository.impl.ImportRepositoryImpl
import com.github.jvsena42.echo.data.repository.impl.DeckRepositoryImpl
import com.github.jvsena42.echo.data.repository.impl.IdentityRepositoryImpl
import com.github.jvsena42.echo.data.repository.impl.MediaRepositoryImpl
import com.github.jvsena42.echo.data.repository.impl.SessionRevalidatorImpl
import com.github.jvsena42.echo.data.repository.impl.SrsRepositoryImpl
import com.github.jvsena42.echo.data.repository.impl.TagRepositoryImpl
import com.github.jvsena42.echo.presentation.discover.DiscoverViewModel
import com.github.jvsena42.echo.presentation.profile.FriendProfileViewModel
import com.github.jvsena42.echo.presentation.study.StudySessionViewModel
import com.github.jvsena42.echo.presentation.decks.DeckDetailViewModel
import com.github.jvsena42.echo.presentation.decks.DeckEditorViewModel
import com.github.jvsena42.echo.presentation.decks.DecksLibraryViewModel
import com.github.jvsena42.echo.presentation.decks.EditCardViewModel
import com.github.jvsena42.echo.presentation.home.HomeViewModel
import com.github.jvsena42.echo.presentation.import_flow.PasteImportViewModel
import com.github.jvsena42.echo.presentation.import_flow.PublishDeckViewModel
import com.github.jvsena42.echo.presentation.onboarding.OnboardingViewModel
import com.github.jvsena42.echo.presentation.profile.ProfileViewModel
import com.github.jvsena42.echo.presentation.settings.SettingsViewModel
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

/**
 * Shared commonMain Koin module. Platform modules must additionally provide bindings for
 * [com.github.jvsena42.echo.data.pubky.PubkyClient] and
 * [com.github.jvsena42.echo.data.storage.SecureSessionStore].
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
    single<DeckRepository> { DeckRepositoryImpl(get(), get(), get(), get(), get()) }
    single<MediaRepository> { MediaRepositoryImpl(get(), get(), get()) }
    single<ImportRepository> { ImportRepositoryImpl() }
    single<SrsRepository> { SrsRepositoryImpl(get(), get(), get(), get(), get()) }
    single<DiscoveryRepository> { DiscoveryRepositoryImpl(get(), get(), get(), get()) }

    single { NexusClient(http = get()) }
    single<TagRepository> {
        TagRepositoryImpl(pubky = get(), session = get(), revalidator = get(), nexus = get())
    }

    factory { OnboardingViewModel(identityRepository = get()) }
    factory { HomeViewModel(identityRepository = get(), deckRepository = get(), srsRepository = get()) }
    factory { DecksLibraryViewModel(deckRepository = get(), identityRepository = get()) }
    factory { params ->
        DeckDetailViewModel(
            deckId = params.get(0),
            authorPubky = params.values.getOrNull(1) as? String,
            deckRepository = get(),
            cardRepository = get(),
            identityRepository = get(),
            srsRepository = get(),
        )
    }
    factory { params -> StudySessionViewModel(deckId = params.getOrNull(), srsRepository = get(), deckRepository = get()) }
    factory { params -> DeckEditorViewModel(deckId = params.getOrNull(), deckRepository = get(), cardRepository = get(), identityRepository = get()) }
    factory { params -> EditCardViewModel(deckId = params.get(0), cardId = params.get(1), cardRepository = get(), deckRepository = get(), mediaRepository = get()) }
    factory { PasteImportViewModel(importRepository = get()) }
    factory { PublishDeckViewModel(importRepository = get(), deckRepository = get(), identityRepository = get()) }
    factory { ProfileViewModel(identityRepository = get(), deckRepository = get()) }
    factory { params -> SettingsViewModel(identityRepository = get(), appVersion = params.getOrNull() ?: "") }
    factory { DiscoverViewModel(discoveryRepository = get(), tagRepository = get()) }
    factory { params ->
        FriendProfileViewModel(
            targetPubky = params.get(),
            identityRepository = get(),
            discoveryRepository = get(),
            deckRepository = get(),
        )
    }
}
