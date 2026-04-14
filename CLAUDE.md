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
  - `data/repository/` — repository **interfaces only**. Implementations will be added here later, backed by SQLDelight + `PubkyClient`.
  - `data/pubky/PubkyClient.kt` — the single interface that wraps `pubky-core-ffi-fork`. All Pubky calls must route through this. Binding mechanism (UniFFI vs handwritten expect/actual) is an open question — do not pick one without user confirmation.
  - `presentation/` — KMP ViewModels (one per screen, `StateFlow<UiState>` + `SharedFlow<UiEffect>`). Currently empty; blocked on adding Coroutines + Koin dependencies.
- `shared/src/{android,ios}Main/` — `expect`/`actual` platform glue only (Pubky FFI, TTS, haptics, file I/O). Nothing else lives here.
- `composeApp/src/androidMain/` — Android app. Compose screens in `ui/`, Koin in `di/`, `MainActivity` as entry point. Uses Jetpack Navigation Compose.
- `iosApp/iosApp/` — iOS app. SwiftUI screens in `Views/`, `NavigationStack` in `Navigation/`, Koin bootstrap in `DI/`. Compose Multiplatform UI is **not** used for iOS screens.

### Non-obvious rules

- **Do not add Compose Multiplatform UI code for iOS screens.** The working assumption (see `docs/Architecture.md §12` open question #1) is native SwiftUI on iOS. `composeApp` is Android-only despite the name.
- **ViewModels live in `shared/commonMain`, not in platform modules.** Both Compose and SwiftUI screens consume the same VMs. No `@Composable` or `ObservableObject` in shared code.
- **Pubky is the source of truth for published decks.** SQLDelight is a cache + offline buffer, not a parallel database. There are no private/local-only decks in v1 (spec §11).
- **Paste-to-Import is the v1 primary import flow.** Every other import source (AI, OCR, URL) listed in spec §14 must reuse the same `TriageVM` → `CommitDeckVM` spine. Don't build parallel commit flows.
- **Parser rules are prescriptive.** `ParsePasteUseCase` must follow the exact rule order in spec §6 and the edge-case table in spec §9. Use them as the test matrix.
- **Session secret storage** depends on the Pubky FFI binding decision — don't wire multiplatform-settings for secrets until §7 of Architecture.md is resolved.
- The project is in early scaffolding. The `Greeting`/`Platform`/`App`/`MainActivity` stubs from the KMP template are still present and used by the running app — leave them in place until the first real screen replaces them.

### Package

Root package is `com.github.jvsena42.eco`. Android namespace is `com.github.jvsena42.eco` (app) and `com.github.jvsena42.eco.shared` (library).

## Where to read before starting work

- `docs/Architecture.md` — always. §4 (shared layering), §6 (Paste-to-Import state flow), §7 (Pubky open question), §12 (open questions blocking feature work).
- `docs/specs.md` §5–§10 — for any import/triage/commit work.
- `design/DESIGN_GUIDELINE.md` §6–§8 — for any screen or component work.
