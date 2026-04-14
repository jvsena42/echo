# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Echo is a Kotlin Multiplatform flashcards app (iOS + Android) that fuses TinyCards-style playfulness, Anki-style spaced repetition, and Pubky-based decentralized identity/social graph. See `docs/specs.md` (Paste-to-Import primary flow), `design/DESIGN_GUIDELINE.md` (screens + design system), and `docs/Architecture.md` (technical architecture — this is the source of truth for module layout, layering, and open questions).

## Build & run

```shell
./gradlew :composeApp:assembleDebug     # Android debug build
./gradlew :shared:allTests              # shared KMP tests
./gradlew :shared:compileKotlinMetadata # fast commonMain compile check
```

iOS: open `iosApp/` in Xcode and run. The `shared` module is consumed as a static framework (`baseName = "Shared"`, `isStatic = true`) — see `shared/build.gradle.kts`.

There is no lint command configured yet. There are no unit tests beyond the default stub in `shared/src/commonTest`.

## Architecture

**Business logic is shared; UI is native per platform.** This is the core rule — internalize it before making changes.

- `shared/src/commonMain/kotlin/com/github/jvsena42/eco/` holds all cross-platform code:
  - `domain/model/` — pure Kotlin data classes (`Deck`, `Card`, `ImportDraft`, `SrsState`, `AppError`, etc.). No framework imports.
  - `domain/usecase/` — single-verb use-case interfaces (`ParsePasteUseCase`, `PublishDeckUseCase`, `ReviewCardUseCase`, …).
  - `data/repository/` — repository interfaces at the top level, implementations under `data/repository/impl/`. Current impls (`DeckRepositoryImpl`, `CardRepositoryImpl`, `MediaRepositoryImpl`) are Pubky-only: they write/read through `PubkyClient` and hold an in-memory per-session cache. No SQLDelight yet — the app is not offline-first, Pubky is the single source of truth.
  - `data/pubky/` — `PubkyClient` interface + DTOs (`ManifestDto`, `CardDto`, `MediaRefDto`) and path helpers (`PubkyPaths`) that map between domain models and the on-homeserver JSON layout defined in `docs/Architecture.md §8.0`. `SessionProvider` is the tiny read-only abstraction repos use to author writes without depending on `IdentityRepository`.
  - `data/pubky/PubkyClient.kt` — the single interface that wraps `pubky-core-ffi-fork`. All Pubky calls must route through this. It is a **thin** 1:1 mirror of the FFI surface (keys, mnemonics, recovery, auth, records, DHT). Do not add deck/card concepts here — those belong in repositories.
  - `presentation/` — KMP ViewModels (one per screen, `StateFlow<UiState>` + `SharedFlow<UiEffect>`). Currently empty; blocked on adding Coroutines + Koin dependencies.
- `shared/src/{android,ios}Main/` — `expect`/`actual` platform glue only (Pubky FFI, TTS, haptics, file I/O). Nothing else lives here.
- `composeApp/src/androidMain/` — Android app. Compose screens in `ui/`, Koin in `di/`, `MainActivity` as entry point. Uses Jetpack Navigation Compose.
- `iosApp/iosApp/` — iOS app. SwiftUI screens in `Views/`, `NavigationStack` in `Navigation/`, Koin bootstrap in `DI/`. Compose Multiplatform UI is **not** used for iOS screens.

### Non-obvious rules

- **Do not add Compose Multiplatform UI code for iOS screens.** The working assumption (see `docs/Architecture.md §12` open question #1) is native SwiftUI on iOS. `composeApp` is Android-only despite the name.
- **ViewModels live in `shared/commonMain`, not in platform modules.** Both Compose and SwiftUI screens consume the same VMs. No `@Composable` or `ObservableObject` in shared code.
- **Pubky is the source of truth for published decks.** The app is not offline-first in v1 — repos talk directly to `PubkyClient` and keep only an in-memory cache for the session. A persistent SQLDelight cache may come later. There are no private/local-only decks in v1 (spec §11).
- **Homeserver layout is fixed.** Decks published under `/pub/echo/decks/{deckId}/{manifest.json, cards/{cardId}.json, media/{sha256}.{ext}}`. Manifest + one record per card + blob-per-media, sync driven by `updated_at`. Full schemas in `docs/Architecture.md §8.0`. Binary media is Base64-encoded on the wire because the FFI `put` takes a `String`.
- **Paste-to-Import is the v1 primary import flow.** Every other import source (AI, OCR, URL) listed in spec §14 must reuse the same `TriageVM` → `CommitDeckVM` spine. Don't build parallel commit flows.
- **Parser rules are prescriptive.** `ParsePasteUseCase` must follow the exact rule order in spec §6 and the edge-case table in spec §9. Use them as the test matrix.
- **Pubky bindings are UniFFI-generated and checked in.** Android: `shared/src/androidMain/kotlin/uniffi/pubkycore/pubkycore.kt` + `shared/src/androidMain/jniLibs/`. iOS: `iosApp/iosApp/Frameworks/PubkyCore.xcframework` + `iosApp/iosApp/Pubky/pubkycore.swift`. Regeneration steps live in `docs/Architecture.md §7.4`; do not edit the generated files.
- **Secret key / session storage is still unresolved** (Architecture.md §7.5). Don't wire multiplatform-settings or any ad-hoc storage for secrets until that decision is made.
- The project is in early scaffolding. The `Greeting`/`Platform`/`App`/`MainActivity` stubs from the KMP template are still present and used by the running app — leave them in place until the first real screen replaces them.

### Package

Root package is `com.github.jvsena42.eco`. Android namespace is `com.github.jvsena42.eco` (app) and `com.github.jvsena42.eco.shared` (library).

## Where to read before starting work

- `docs/Architecture.md` — always. §4 (shared layering), §6 (Paste-to-Import state flow), §7 (Pubky open question), §12 (open questions blocking feature work).
- `docs/specs.md` §5–§10 — for any import/triage/commit work.
- `design/DESIGN_GUIDELINE.md` §6–§8 — for any screen or component work.
