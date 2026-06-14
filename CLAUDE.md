# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Echo is a Kotlin Multiplatform flashcards app (iOS + Android) that fuses TinyCards-style playfulness, Anki-style spaced repetition, and Pubky-based decentralized identity/social graph. See `docs/specs.md` (Paste-to-Import primary flow), `design/DESIGN_GUIDELINE.md` (screens + design system), and `docs/Architecture.md` (technical architecture — this is the source of truth for module layout, layering, and open questions).

## Build & run

```shell
./gradlew :composeApp:assembleDebug     # Android debug build
./gradlew :shared:allTests              # shared KMP tests
./gradlew :shared:compileKotlinMetadata # fast commonMain compile check
./gradlew detektAll                     # lint Kotlin on all subprojects (detekt + compose rules)
./gradlew lintSwift                     # lint iOS Swift (SwiftLint; needs `brew install swiftlint`)
```

iOS: open `iosApp/` in Xcode and run. The `shared` module is consumed as a static framework (`baseName = "Shared"`, `isStatic = true`) — see `shared/build.gradle.kts`. **iOS is not yet runnable end-to-end**: `iOSApp.swift` has the Koin bootstrap commented out, blocked on `IosPubkyClient` conforming to the generated `PubkyClient` protocol once the Shared framework is built. The SwiftUI screens are drafted but VM-driven behaviour unlocks only after that.

Kotlin lint is detekt (`config/detekt/detekt.yml`, with `detekt-formatting` + `detekt-compose-rules`); run `./gradlew detektAll` (use `--auto-correct` to fix formatting findings). Swift lint is SwiftLint (`iosApp/.swiftlint.yml`, generated `pubkycore.swift` excluded); run `./gradlew lintSwift` or `swiftlint` from `iosApp/`. There are no unit tests beyond the default stub in `shared/src/commonTest`.

## Architecture

**Business logic is shared; UI is native per platform.** This is the core rule — internalize it before making changes.

- `shared/src/commonMain/kotlin/com/github/jvsena42/eco/` holds all cross-platform code:
  - `domain/model/` — pure Kotlin data classes (`Deck`, `Card`, `ImportDraft`, `SrsState`, `AppError`, etc.). No framework imports.
  - `data/repository/` — repository interfaces (all 8 in `Repositories.kt`: Identity, Deck, Card, Import, Media, Tag, Discovery, Srs), implementations under `data/repository/impl/`. **Repositories own the business logic** — parsing, triage, publishing, SRS grading, follow/unfollow, sign-in/out all live as methods on the relevant repo rather than in a separate use-case layer. **All 8 are implemented** (`IdentityRepositoryImpl`, `DeckRepositoryImpl`, `CardRepositoryImpl`, `ImportRepositoryImpl` — the paste parser, spec §6 rules + §9 edge cases —, `MediaRepositoryImpl`, `SrsRepositoryImpl`, `DiscoveryRepositoryImpl`, `TagRepositoryImpl`), plus `SessionRevalidatorImpl`. `TagRepositoryImpl` writes pubky-app-specs tag records to the homeserver and reads trending from the Nexus indexer (`data/nexus/NexusClient`, see Architecture.md §7.6). The impls are Pubky-only: they write/read through `PubkyClient` and hold an in-memory per-session cache. No SQLDelight yet — the app is not offline-first, Pubky is the single source of truth.
  - `data/pubky/` — `PubkyClient` interface + DTOs (`ManifestDto`, `CardDto`, `MediaRefDto` in `DeckDtos.kt`, `ProfileDto`) and path helpers (`PubkyPaths`, `Hashing`) that map between domain models and the on-homeserver JSON layout defined in `docs/Architecture.md §8.0`. `SessionProvider`/`MutableSessionProvider` is the tiny read-only abstraction repos use to author writes without depending on `IdentityRepository`. `SessionRevalidator` + `SessionRetry` + `SessionPayloadParser` handle expired-session retry.
  - `data/pubky/PubkyClient.kt` — the single interface that wraps `pubky-core-ffi-fork`. All Pubky calls must route through this. It is a **thin** 1:1 mirror of the FFI surface (keys, mnemonics, recovery, auth, records, DHT). Do not add deck/card concepts here — those belong in repositories. The `actual` impl is `AndroidPubkyClient` (androidMain); the iOS impl (`IosPubkyClient.swift`) is still a stub awaiting framework binding.
  - `data/storage/` — `SecureSessionStore` interface for persisting the signed-in `Session`, backed by the platform keystore via Liftric KVault (`AndroidSecureSessionStore` wraps EncryptedSharedPreferences; `IosSecureSessionStore` wraps Keychain). This resolves the secret-storage open question — see "Non-obvious rules" below.
  - `di/SharedModule.kt` — Koin graph binding repos, ViewModels, and `SessionProvider`; platforms override `PubkyClient` + `SecureSessionStore` via `PlatformModule.{android,ios}.kt`.
  - `presentation/` — KMP ViewModels, one per screen (`StateFlow<UiState>` + `SharedFlow<UiEffect>`). **Implemented** across `onboarding/` (`OnboardingViewModel` + UiState/Effect), `home/` (`HomeViewModel`), `decks/` (`DecksLibraryViewModel`, `DeckDetailViewModel`, `DeckEditorViewModel`, `EditCardViewModel`), `import/` (`PasteImportViewModel`, `PublishDeckViewModel`), and `profile/` (`ProfileViewModel`). Coroutines + Koin are wired (no longer blocked).
- `shared/src/{android,ios}Main/` — `expect`/`actual` platform glue only (Pubky FFI, TTS, haptics, file I/O). Nothing else lives here.
- `composeApp/src/androidMain/` — Android app. Compose screens in `ui/`, Koin in `di/`, `MainActivity` as entry point. Uses Jetpack Navigation Compose.
- `iosApp/iosApp/` — iOS app. SwiftUI screens in `Views/`, `NavigationStack` in `Navigation/`, Koin bootstrap in `DI/`. Compose Multiplatform UI is **not** used for iOS screens.

### Non-obvious rules

- **Do not add Compose Multiplatform UI code for iOS screens.** The working assumption (see `docs/Architecture.md §12` open question #1) is native SwiftUI on iOS. `composeApp` is Android-only despite the name.
- **ViewModels live in `shared/commonMain`, not in platform modules.** Both Compose and SwiftUI screens consume the same VMs. No `@Composable` or `ObservableObject` in shared code.
- **Always import symbols; never reference them fully-qualified inline.** Add an `import` at the top of the file (e.g. `import androidx.compose.ui.graphics.Color`) and use the short name, rather than writing `androidx.compose.ui.graphics.Color` inline in a type or call. Applies to both Kotlin and Swift.
- **Native-first UI.** Prefer native platform components — **Material 3 Expressive** (`ShortNavigationBar`, `Scaffold`, `TopAppBar`, etc.; opt in with `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`) on Android, and native SwiftUI / Liquid Glass on iOS — over bespoke custom Composables/Views, **even if it diverges from the Pencil design**, so the app feels platform-native. Apply Echo brand tokens (accent, type, radii) *to* native components rather than rebuilding chrome from primitives; build fully custom only where Echo's identity needs it and no native equivalent exists (e.g. the study card flip). The custom `EchoTabBar` pill has been replaced by a Material 3 Expressive `ShortNavigationBar` (Android) and a native `TabView`/`UITabBar` (iOS). See `design/DESIGN_GUIDELINE.md §4`.
- **Pubky is the source of truth for published decks.** The app is not offline-first in v1 — repos talk directly to `PubkyClient` and keep only an in-memory cache for the session. A persistent SQLDelight cache may come later. There are no private/local-only decks in v1 (spec §11).
- **Homeserver layout is fixed.** Decks published under `/pub/echo/decks/{deckId}/{manifest.json, cards/{cardId}.json, media/{sha256}.{ext}}`. Manifest + one record per card + blob-per-media, sync driven by `updated_at`. Full schemas in `docs/Architecture.md §8.0`. Binary media is written raw via the FFI's `put_bytes_with_session`; reads come back Base64-encoded from the FFI transport and are decoded in `MediaRepositoryImpl`.
- **Paste-to-Import is the v1 primary import flow.** The implemented spine is `PasteImportViewModel` (parse + live preview) → `PublishDeckViewModel` (commit to Pubky). Every other import source (AI, OCR, URL) listed in spec §14 must reuse this same spine. Don't build parallel commit flows.
- **Parser rules are prescriptive.** The paste parser (on `ImportRepository`) must follow the exact rule order in spec §6 and the edge-case table in spec §9. Use them as the test matrix.
- **No use-case layer.** Don't introduce `*UseCase` interfaces or a `domain/usecase/` package. If a piece of logic doesn't fit any existing repo, extend the most relevant repo or add a new one — keep the surface area flat.
- **Pubky bindings are UniFFI-generated and checked in.** Android: `shared/src/androidMain/kotlin/uniffi/pubkycore/pubkycore.kt` + `shared/src/androidMain/jniLibs/`. iOS: `iosApp/iosApp/Frameworks/PubkyCore.xcframework` + `iosApp/iosApp/Pubky/pubkycore.swift`. Regeneration steps live in `docs/Architecture.md §7.4`; do not edit the generated files.
- **Session storage is resolved** via `SecureSessionStore` (Liftric KVault → Android Keystore / iOS Keychain). Persist the signed-in `Session` only through this interface — do not wire multiplatform-settings or ad-hoc storage for secrets. (Architecture.md §7.5 may still read as "unresolved"; the code is the source of truth here.)
- **The Android app is real and feature-built; iOS is drafted but not yet wired.** Onboarding → home → decks → paste-import → publish → profile all work on Android (Compose screens in `composeApp/src/androidMain/.../ui/`, nav in `ui/nav/`, DI in `di/`). The leftover `Greeting`/`Platform` template stubs still exist in `shared` but are no longer the running UI. iOS SwiftUI screens exist in `iosApp/iosApp/Views/` but are inert until the Koin bootstrap in `iOSApp.swift` is enabled (see Build & run).

### Package

Root package is `com.github.jvsena42.echo`. Android namespace is `com.github.jvsena42.echo` (app) and `com.github.jvsena42.echo.shared` (library).

## Where to read before starting work

- `docs/Architecture.md` — always. §4 (shared layering), §6 (Paste-to-Import state flow), §7 (Pubky open question), §12 (open questions blocking feature work).
- `docs/specs.md` §5–§10 — for any import/triage/commit work.
- `design/DESIGN_GUIDELINE.md` §6–§8 — for any screen or component work.
