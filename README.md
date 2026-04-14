# Echo

A mobile flashcards app that fuses Duolingo TinyCards' playfulness, Anki's spaced-repetition, and Pubky's decentralized identity and social graph. iOS + Android, built with Kotlin Multiplatform.

- **Product spec:** [`docs/specs.md`](./docs/specs.md) — Paste-to-Import, the primary v1 flow.
- **Design brief:** [`design/DESIGN_GUIDELINE.md`](./design/DESIGN_GUIDELINE.md) — screens, components, design system.
- **Architecture:** [`docs/Architecture.md`](./docs/Architecture.md) — module layout, layering, state flow, open questions.

---

## Architecture at a glance

Echo is a Kotlin Multiplatform project with **shared business logic** and **native UIs per platform**:

- `shared/` — KMP module holding domain models, repositories (which own the business logic), and ViewModels. Platform glue (Pubky FFI, TTS, haptics) via `expect`/`actual`.
- `composeApp/` — Android app. Jetpack Compose screens, Jetpack Navigation, Koin DI.
- `iosApp/` — iOS app. SwiftUI screens, `NavigationStack`, Koin bootstrap.

Identity, tags, follows, and published decks are backed by [Pubky](https://pubky.org) via `pubky-core-ffi-fork` (binding mechanism — UniFFI vs handwritten expect/actual — still TBD; see Architecture §7).

### Module layout

```
echo/
├── shared/
│   └── src/
│       ├── commonMain/kotlin/com/github/jvsena42/eco/
│       │   ├── domain/        # models (pure Kotlin)
│       │   ├── data/          # repositories (own business logic) + PubkyClient
│       │   └── presentation/  # ViewModels (StateFlow-based)
│       ├── androidMain/       # actuals: Pubky FFI, TTS, haptics
│       └── iosMain/           # actuals: Pubky FFI, TTS, haptics
│
├── composeApp/src/androidMain/kotlin/com/github/jvsena42/eco/
│   ├── ui/                    # Compose screens + navigation
│   ├── di/                    # Koin Android module
│   └── MainActivity.kt
│
└── iosApp/iosApp/
    ├── Views/                 # SwiftUI screens
    ├── Navigation/            # NavigationStack
    ├── DI/                    # Koin bootstrap
    └── iOSApp.swift
```

### Stack

| Concern | Choice |
|---|---|
| UI (Android) | Jetpack Compose + Material 3 |
| UI (iOS) | SwiftUI + NavigationStack |
| Shared logic | Kotlin Multiplatform (commonMain) |
| DI | Koin |
| Async | Coroutines + Flow (Swift bridge: SKIE, TBD) |
| Local storage | SQLDelight + multiplatform-settings |
| Identity / social | Pubky (`pubky-core-ffi-fork`) |
| Navigation | Per-platform native |

---

## Build and run

### Android

```shell
./gradlew :composeApp:assembleDebug
```

Or use the run configuration from your IDE's toolbar.

### iOS

Open [`/iosApp`](./iosApp) in Xcode and run, or use the run widget in your IDE.

### Tests

```shell
./gradlew :shared:allTests
```

Unit tests for parsers, repositories, and ViewModels live in `shared/src/commonTest`.

---

## Project status

v1 is in early scaffolding. The current skeleton establishes package boundaries and interfaces per `docs/Architecture.md`; concrete implementations (SQLDelight schema, Pubky FFI wiring, first ViewModels) are the next step.

See [`docs/Architecture.md §12`](./docs/Architecture.md) for open questions that block feature work.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html).
