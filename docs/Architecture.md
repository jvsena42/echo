# Echo — Architecture

> **Status:** Draft v1 · **Scope:** Technical architecture for the Echo KMP app.
> **Reads alongside:** [`docs/specs.md`](./specs.md) · [`design/DESIGN_GUIDELINE.md`](../design/DESIGN_GUIDELINE.md)

---

## 1. Overview

Echo is a **Kotlin Multiplatform** flashcards app targeting iOS and Android. Business logic — domain models, repositories, use-cases, and ViewModels — lives in a single `shared` module (`commonMain`). Each platform renders its own native UI: **Jetpack Compose** on Android (`composeApp/androidMain`) and **SwiftUI** on iOS (`iosApp/`). Identity, social graph, tags, and published decks are backed by **Pubky**, accessed through a native binding layer built on top of `pubky-core-ffi-fork`.

The v1 product is defined by [`docs/specs.md`](./specs.md) (Paste-to-Import primary flow) and [`design/DESIGN_GUIDELINE.md`](../design/DESIGN_GUIDELINE.md) (screens, components, design system).

---

## 2. Guiding principles

1. **Share logic, not pixels.** Everything above the UI layer is shared Kotlin. Rendering, navigation, and platform ergonomics are native.
2. **Platform-native feel.** iOS gets HIG sheets, SF Pro, and `UIImpactFeedback`; Android gets Material 3, Roboto, and `HapticFeedbackConstants`. Same state, different skins.
3. **Offline-first parse and triage.** Paste-to-Import (spec §5) works with no network. Only commit/publish touches the homeserver.
4. **Pubky is the source of truth for published data.** The local store is a cache and an offline buffer — not a parallel database. There is no private-deck local-only path in v1 (spec §11).
5. **One ViewModel per screen, one StateFlow per ViewModel.** Screens are thin; state transitions live in shared code and are unit-testable.
6. **Expect/actual only at the edges.** Platform glue (Pubky FFI, TTS, haptics, clipboard, file I/O) is the only code with `expect`/`actual`. Everything else is pure `commonMain`.

---

## 3. Module layout

```
echo/
├── shared/                        ← KMP business logic
│   └── src/
│       ├── commonMain/            ← domain + data + presentation (VMs)
│       ├── commonTest/
│       ├── androidMain/           ← actuals: Pubky FFI (android), TTS, haptics
│       ├── androidUnitTest/
│       ├── iosMain/               ← actuals: Pubky FFI (ios), TTS, haptics
│       └── iosTest/
│
├── composeApp/                    ← Android app
│   └── src/androidMain/
│       ├── kotlin/.../ui/         ← Compose screens + navigation
│       ├── kotlin/.../di/         ← Koin Android module
│       └── kotlin/.../MainActivity.kt
│
└── iosApp/                        ← iOS app
    └── iosApp/
        ├── Views/                 ← SwiftUI screens
        ├── Navigation/            ← NavigationStack
        ├── DI/                    ← Koin bootstrap
        └── iosAppApp.swift        ← @main
```

**Dependency direction:**

```
          ┌──────────────────┐      ┌────────────────┐
          │ composeApp       │      │ iosApp         │
          │ (Compose + Nav)  │      │ (SwiftUI + NS) │
          └────────┬─────────┘      └────────┬───────┘
                   │                         │
                   └───────────┬─────────────┘
                               ▼
                         ┌───────────┐
                         │  shared   │
                         │ commonMain│
                         │  domain   │
                         │   data    │
                         │    VMs    │
                         └─────┬─────┘
                               ▼
                  androidMain / iosMain actuals
                               ▼
                   pubky-core-ffi-fork bindings
```

Platform UI modules depend on `shared`. `shared` depends only on Kotlin stdlib, Coroutines, SQLDelight, Koin, multiplatform-settings, and (via expect/actual) the Pubky FFI.

> **Open question — UI strategy.** The working assumption is fully native UI per platform. Compose Multiplatform UI is **not** used for screens. This is not yet final; revisit before the first screen ships. See §12.

---

## 4. Layered architecture inside `shared/commonMain`

### 4.1 Domain

Pure Kotlin. No framework imports.

- **Models:** `Deck`, `Card`, `CardContent` (text / image / audio variants — brief §8), `Tag`, `PubkyIdentity`, `ImportDraft`, `ParsedRow`, `SrsGrade` (Again/Hard/Good/Easy), `SrsState`, `StudyQueueItem`, `AppError`.
- **Use-cases** (one per verb, single public `invoke`):
  - `ParsePasteUseCase` — spec §6/§7. Takes raw text, returns `ImportDraft` with detected separator, column mapping, parsed rows, dedupe stats, and error flags.
  - `TriageCardsUseCase` — applies keep/discard/edit decisions from the swipe queue.
  - `PublishDeckUseCase` — persists cards locally, publishes the deck to Pubky, returns the new deck handle.
  - `ReviewCardUseCase` — applies an SRS grade, advances the queue.
  - `FollowUserUseCase`, `UnfollowUserUseCase` — Pubky follows (brief §9.4).
  - `SignInWithRingUseCase`, `SignOutUseCase` — deeplink session handling (brief §9.1).

### 4.2 Data (Repositories)

Repositories are the only layer that talks to SQLDelight and Pubky. They expose **`Flow`s** for reads and suspend functions for writes. No UI state lives here.

| Repository | Responsibilities | Backing |
|---|---|---|
| `IdentityRepository` | Current session, pubky, capabilities, sign-in/out | Pubky FFI + multiplatform-settings |
| `DeckRepository` | CRUD + publish/fetch decks | SQLDelight + Pubky FFI |
| `CardRepository` | CRUD cards within a deck | SQLDelight |
| `ImportRepository` | In-memory import drafts, parsing, dedupe | In-memory + `ParsePasteUseCase` |
| `TagRepository` | Read/write Pubky tags on decks (brief §9.3) | Pubky FFI |
| `DiscoveryRepository` | Trending/followed tags, decks by followed users | Pubky FFI |
| `SrsRepository` | Per-card SRS state, today's due queue | SQLDelight |
| `MediaRepository` | Image + audio blob storage for cards | Platform file I/O via expect/actual |

All repositories are interfaces in `commonMain`. Implementations are also in `commonMain` where possible; only the FFI- and file-touching parts drop into `androidMain`/`iosMain` actuals.

### 4.3 Presentation (ViewModels)

KMP ViewModels built on Coroutines. One per screen / sheet in brief §6 and spec §5.

```kotlin
class PasteImportViewModel(
    private val parsePaste: ParsePasteUseCase,
    private val importRepo: ImportRepository,
) {
    private val _state = MutableStateFlow(PasteImportUiState.Empty)
    val state: StateFlow<PasteImportUiState> = _state

    fun onTextChanged(text: String) { /* debounce + parse */ }
    fun onSeparatorOverride(sep: Separator) { /* re-parse */ }
    fun onColumnMappingChanged(mapping: ColumnMapping) { /* re-parse */ }
    fun onNextClicked() { /* emit nav event */ }
}
```

Rules:
- `UiState` is a sealed class or a single data class with nullable fields — never leak domain models raw.
- Events the UI fires are plain method calls. One-shot effects (navigation, haptics, toasts) are a separate `SharedFlow<UiEffect>`.
- No Android or iOS imports. No `@Composable`, no `ObservableObject`.

ViewModels that back brief §6 screens: `OnboardingVM`, `StudyQueueVM`, `StudySessionVM`, `DeckDetailVM`, `DeckEditorVM`, `DiscoverVM`, `ProfileVM`, `SettingsVM`. ViewModels that back spec §5 flows: `PasteImportVM`, `TriageVM`, `CommitDeckVM`.

---

## 5. UI layer (per platform)

Both platforms consume the same VMs. Only rendering, navigation, and platform glue differ.

### 5.1 Android (`composeApp/androidMain`)

- **UI:** Jetpack Compose, Material 3 components styled by Echo design tokens.
- **State:** `val ui by vm.state.collectAsStateWithLifecycle()` in each screen composable.
- **Navigation:** Jetpack Navigation Compose. One `NavHost` per top-level tab (Study / Decks / Discover / Profile), plus sheets for Paste-to-Import flows.
- **DI:** Koin Android, bootstrapped in `MainActivity`. Screens resolve their VM via `koinViewModel()` (or equivalent KMP helper).
- **Platform glue:** `AVSpeechSynthesizer`'s Android counterpart is `android.speech.tts.TextToSpeech`; haptics via `HapticFeedbackConstants`; image picker via Activity Result APIs.

### 5.2 iOS (`iosApp/`)

- **UI:** SwiftUI, styled by Echo design tokens mirrored in Swift.
- **State:** shared VMs exposed as ObservableObject wrappers. The Kotlin→Swift Flow bridge is TBD (see §12) — working assumption is **SKIE**.
- **Navigation:** `NavigationStack` per tab, `.sheet`/`.fullScreenCover` for Paste-to-Import and triage.
- **DI:** Koin started from the Swift `@main` entry; VMs handed to views via initializers.
- **Platform glue:** `AVSpeechSynthesizer` for TTS, `UIImpactFeedbackGenerator` for haptics, `PHPickerViewController` for images, `AVAudioRecorder` for audio cards.

### 5.3 Theming

Design tokens (brief §11 deliverable) are authored as JSON and consumed both sides:
- Android: tokens generated into a Kotlin `EchoColors`/`EchoType` in `composeApp`.
- iOS: tokens generated into a Swift `EchoColors`/`EchoType` in `iosApp`.
- The shared module does **not** hold a Compose theme.

---

## 6. State flow — Paste-to-Import walkthrough

Ties spec §5 (UX flow) to code. Each arrow is an actual function call.

```
┌─ User action ────────────┐   ┌─ Platform UI ────────────┐   ┌─ shared VMs/repos ─────────────┐   ┌─ Pubky / local ─┐
│ Taps "+" → Paste screen  │ → │ PasteImportScreen        │ → │ PasteImportVM.state = Empty    │   │                 │
│                          │   │                          │   │                                │   │                 │
│ Pastes text              │ → │ onTextChanged(text)      │ → │ ImportRepository.parse(text)   │   │                 │
│                          │   │                          │   │   → ParsePasteUseCase          │   │                 │
│                          │   │                          │   │ _state = Preview(draft)        │   │                 │
│                          │   │ collectAsState → redraw  │ ← │                                │   │                 │
│                          │   │ 3 flip-card previews     │   │                                │   │                 │
│                          │   │                          │   │                                │   │                 │
│ Overrides separator      │ → │ onSeparatorOverride(…)   │ → │ re-parse → Preview(draft')     │   │                 │
│                          │   │                          │   │                                │   │                 │
│ Taps Next                │ → │ nav → TriageScreen       │ → │ TriageVM(draft)                │   │                 │
│ Swipes keep/discard      │ → │ onSwipe(id, decision)    │ → │ TriageCardsUseCase             │   │                 │
│                          │   │                          │   │                                │   │                 │
│ Completes triage         │ → │ nav → CommitDeckScreen   │ → │ CommitDeckVM                   │   │                 │
│ Fills metadata, Publish  │ → │ onPublish(meta)          │ → │ PublishDeckUseCase             │   │                 │
│                          │   │                          │   │   → DeckRepository.publish()   │ → │ Pubky homeserver│
│                          │   │                          │   │   → local SQLDelight cache     │ → │ SQLDelight      │
│                          │   │ success screen + haptic  │ ← │ _state = Success(deck)         │   │                 │
│                          │   │                          │   │                                │   │                 │
│ Undo (within 10 s)       │ → │ onUndo()                 │ → │ DeckRepository.delete(deck)    │ → │ Pubky homeserver│
│                          │   │ nav ← paste screen       │ ← │ restore Preview(draft)         │   │                 │
└──────────────────────────┘   └──────────────────────────┘   └────────────────────────────────┘   └─────────────────┘
```

Every state listed in spec §10 maps to a single `PasteImportUiState` / `TriageUiState` / `CommitUiState` variant. The spec §10 state list is the acceptance checklist for these three VMs.

---

## 7. Pubky integration (OPEN QUESTION)

All Pubky calls go through a single `PubkyClient` interface in `commonMain`:

```kotlin
interface PubkyClient {
    suspend fun startSignIn(capabilities: List<Capability>): DeeplinkRequest
    suspend fun completeSignIn(callback: SignInCallback): Session
    suspend fun publishDeck(deck: Deck): PubkyUri
    suspend fun fetchDeck(uri: PubkyUri): Deck
    suspend fun putTag(target: PubkyUri, tag: Tag)
    suspend fun follow(target: PubkyIdentity)
    // …
}
```

Repositories depend on this interface. The implementation is provided via `expect`/`actual` and wraps `pubky-core-ffi-fork`.

**Binding mechanism — undecided:**

- **Option A — UniFFI.** The Rust fork emits UniFFI bindings; Android links `.so` + generated Kotlin, iOS links an XCFramework + generated Swift. `commonMain` defines `expect class PubkyClient`; `androidMain` and `iosMain` actuals delegate to the generated bindings. Upside: one source of truth in Rust, less handwritten glue. Downside: depends on UniFFI support in the fork.
- **Option B — Handwritten expect/actual.** `commonMain` defines the interface; each platform writes its own actual against whatever the fork exposes natively. Upside: no UniFFI dependency. Downside: two implementations to keep in sync.

**Decision criteria** (to resolve before wiring DI):
1. What does `pubky-core-ffi-fork` already ship? (UniFFI? JNI? Swift wrappers?)
2. iOS linking model — static XCFramework vs dynamic?
3. Rust client thread-safety — is a single instance safe across coroutines?
4. Where do session secrets live on each platform? (Keychain / EncryptedSharedPreferences)
5. Deeplink re-entry model — how does the Rust client receive the `echo://login-callback` payload?

**Action:** confirm with the owner of `pubky-core-ffi-fork` before adding Pubky deps to `shared`. Until then, repositories run against a `FakePubkyClient` in tests and development.

---

## 8. Data model & persistence

### 8.1 SQLDelight schema (sketch)

```
Deck(
  id TEXT PRIMARY KEY,          -- local uuid
  pubky_uri TEXT UNIQUE,        -- null until published
  author_pubky TEXT NOT NULL,
  title TEXT NOT NULL,
  description TEXT,
  cover_image_path TEXT,
  created_at INTEGER NOT NULL,
  last_studied_at INTEGER
)

Card(
  id TEXT PRIMARY KEY,
  deck_id TEXT NOT NULL REFERENCES Deck(id),
  front TEXT NOT NULL,
  back TEXT NOT NULL,
  image_path TEXT,
  audio_path TEXT,
  position INTEGER NOT NULL
)

Tag(
  value TEXT PRIMARY KEY         -- Pubky tag label
)

DeckTag(
  deck_id TEXT NOT NULL REFERENCES Deck(id),
  tag_value TEXT NOT NULL REFERENCES Tag(value),
  PRIMARY KEY (deck_id, tag_value)
)

SrsState(
  card_id TEXT PRIMARY KEY REFERENCES Card(id),
  due_at INTEGER NOT NULL,
  interval_days INTEGER NOT NULL,
  ease_factor REAL NOT NULL,
  repetitions INTEGER NOT NULL,
  last_grade INTEGER
)

ImportSession(
  id TEXT PRIMARY KEY,
  raw_text TEXT NOT NULL,
  separator TEXT,
  created_at INTEGER NOT NULL
)

Session(
  pubky TEXT PRIMARY KEY,
  session_secret TEXT NOT NULL,
  capabilities TEXT NOT NULL,
  homeserver TEXT NOT NULL
)
```

### 8.2 multiplatform-settings

Non-relational prefs: theme override, TTS voice per language, onboarding progress, last-seen snackbar timestamps. Session secret is stored here only if the platform Keychain/Keystore is not accessible via FFI — otherwise use the secure store.

### 8.3 Source of truth

- **Published decks:** Pubky homeserver is canonical. SQLDelight caches the last fetched copy for offline reads.
- **Study progress (SRS):** SQLDelight is canonical; not yet synced to Pubky in v1.
- **Import drafts:** in-memory only — each paste is a fresh canvas (spec §4 story 5).
- **Private decks:** out of scope for v1 (spec §11). If spec §13 Q1 flips, local-only decks become a first-class SQLDelight row with `pubky_uri = NULL`.

---

## 9. Cross-cutting concerns

### 9.1 Dependency injection

Koin, single graph shared across platforms.

```
shared/commonMain:
  domainModule        ← use-cases
  dataModule          ← repositories
  presentationModule  ← ViewModels
  platformModule      ← expect fun platformModule(): Module

shared/androidMain:
  actual platformModule() { PubkyClient, TtsEngine, Haptics, FileStore }

shared/iosMain:
  actual platformModule() { PubkyClient, TtsEngine, Haptics, FileStore }
```

Android bootstraps Koin in `MainActivity.onCreate`. iOS bootstraps in the `@main` `App` initializer and hands VMs to SwiftUI views via initializers.

### 9.2 Async

Kotlin Coroutines + Flow everywhere. All public repository and use-case methods are `suspend` or return `Flow`. Swift consumes these via **SKIE** (working assumption — see §12); `@Published` wrappers are generated per VM.

### 9.3 Error handling

```kotlin
sealed class AppError {
    data object Network : AppError()
    data object Unauthorized : AppError()
    data class Parse(val reason: ParseFailure) : AppError()
    data class Pubky(val code: String, val message: String) : AppError()
    data class Unknown(val cause: Throwable) : AppError()
}
```

Use-cases return `Result<T, AppError>` (Arrow `Either` or handwritten — decide at first use). ViewModels map errors to user-facing banners/toasts/snackbars per brief §7.

### 9.4 Accessibility

Shared VMs expose semantic labels (e.g. `"Card 1 of 3 preview, front: hola"`) as strings on the state. Platform UIs wire them into VoiceOver / TalkBack. Reduce-motion and dynamic-type handling live in the platform UI (spec §12, brief §10).

### 9.5 Logging

Reserve a `Logger` interface in `commonMain` with no-op default. Platform actuals can plug into Logcat / `os_log`. Telemetry is out of scope for v1.

---

## 10. Testing strategy

- **`commonTest`** — the important tier.
  - `ParsePasteUseCase`: one test per rule in spec §6, plus every edge case in spec §9.
  - Repositories against a `FakePubkyClient` and an in-memory SQLDelight driver.
  - ViewModels with [Turbine](https://github.com/cashapp/turbine) asserting state sequences for every spec §10 state.
- **Android UI** — Compose UI tests (`composeApp/androidUnitTest` or `androidInstrumentedTest`) for Paste → Triage → Commit and Study session.
- **iOS UI** — XCTest snapshot tests for the same flows.
- **Integration** — a minimal smoke target that exercises the real `pubky-core-ffi-fork` against a test homeserver; kept separate from the unit suite.

---

## 11. Build & tooling

- **Gradle** with version catalog (`gradle/libs.versions.toml`). Kotlin, AGP, and Compose versions already pinned in the scaffold.
- **Plugins:** `org.jetbrains.kotlin.multiplatform`, `com.android.application`, `app.cash.sqldelight`, `io.insert-koin` (runtime only), Compose Multiplatform plugin for the Android-only Compose dependency.
- **iOS framework packaging:** `shared` publishes an XCFramework via the KMP `XCFramework` Gradle task; `iosApp` consumes it via SPM or direct embedding.
- **SKIE** (pending §12 decision) plugs into the `shared` Gradle build.
- **CI:** run `commonTest`, Android unit + Compose tests, iOS unit + snapshot tests per PR.

---

## 12. Open questions

Pulled forward from spec §13 plus architecture-specific items.

1. **UI strategy final call.** Working assumption: fully native UI per platform. Compose Multiplatform UI is not used. Confirm with design + eng leads before the first screen ships.
2. **Pubky FFI binding mechanism.** UniFFI vs handwritten expect/actual (§7). Decision criteria listed; needs confirmation with the fork owner.
3. **Swift ↔ Flow bridge.** SKIE vs KMP-NativeCoroutines. SKIE is the working assumption; revisit if it blocks iOS builds.
4. **Multi-module split timing.** Single `shared` module for v1; split into `:core / :data / :domain / :feature-*` if build times or ownership boundaries require it.
5. **Private decks.** If spec §13 Q1 flips in favor of private decks, `DeckRepository` gains a local-only write path and `pubky_uri` stays `NULL` until the user opts in.
6. **Session secret storage.** Keychain / Keystore via FFI vs multiplatform-settings. Depends on §7.
7. **SRS sync.** v1 keeps SRS local. If we ever want cross-device study, `SrsRepository` gains a Pubky-backed write path.
8. **AI / OCR / URL import** (spec §14) — all reuse `TriageVM` + `CommitDeckVM`. No architectural change needed, only new use-cases and entry screens.

---

## 13. References

- [`docs/specs.md`](./specs.md) — Paste-to-Import product spec.
- [`design/DESIGN_GUIDELINE.md`](../design/DESIGN_GUIDELINE.md) — design system and screen brief.
- `pubky-core-ffi-fork` — local sibling repo at `../../../pubky-core-ffi-fork`.
- Pubky Ring deeplink contract — brief §9.1.

---

*End of architecture doc. Update alongside spec and design-brief revisions; do not let it drift.*
