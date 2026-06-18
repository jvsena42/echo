# Echo — Architecture

> **Status:** Draft v1 · **Scope:** Technical architecture for the Echo KMP app.
> **Reads alongside:** [`docs/specs.md`](./specs.md) · [`design/DESIGN_GUIDELINE.md`](../design/DESIGN_GUIDELINE.md)

---

## 1. Overview

Echo is a **Kotlin Multiplatform** flashcards app targeting iOS and Android. Business logic — domain models, repositories, and ViewModels — lives in a single `shared` module (`commonMain`). Repositories own the business logic; there is no separate use-case layer. Each platform renders its own native UI: **Jetpack Compose** on Android (`composeApp/androidMain`) and **SwiftUI** on iOS (`iosApp/`). Identity, social graph, tags, and published decks are backed by **Pubky**, accessed through a native binding layer built on top of `pubky-core-ffi-fork`.

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

Platform UI modules depend on `shared`. `shared` depends only on Kotlin stdlib, Coroutines, kotlinx-serialization, Koin (+ the Koin ViewModel DSL), the multiplatform `androidx.lifecycle` ViewModel, Liftric KVault, and (via expect/actual) the Pubky FFI. SQLDelight and multiplatform-settings are **not** dependencies in v1 — see §8.

> **Note (v1 reality vs. earlier design).** This doc originally sketched a SQLDelight cache, multiplatform-settings, and SKIE. None are wired today: repositories are Pubky-only with an in-memory per-session cache, secrets persist via `SecureSessionStore` (KVault), and the Swift↔Flow bridge is still an open question. Sections below are annotated where they describe a *possible future* rather than the current build.

> **Open question — UI strategy.** The working assumption is fully native UI per platform. Compose Multiplatform UI is **not** used for screens. This is not yet final; revisit before the first screen ships. See §12.

---

## 4. Layered architecture inside `shared/commonMain`

### 4.1 Domain

Pure Kotlin. No framework imports.

- **Models:** `Deck`, `Card`, `CardContent` (text / image / audio variants — brief §8), `Tag`, `PubkyIdentity`, `ImportDraft`, `ParsedRow`, `SrsGrade` (Again/Hard/Good/Easy), `SrsState`, `StudyQueueItem`, `AppError`.

Business logic (parse, triage, publish, review, follow, sign-in/out) lives on repositories — see §4.2. There is no separate use-case layer.

### 4.2 Data (Repositories)

Repositories are the only layer that talks to Pubky, and they also **own the business logic**: parsing, triage, publishing, SRS grading, follow/unfollow, and session handling are all methods on the relevant repo. They expose **`Flow`s** for reads and suspend functions for writes. No UI state lives here. There is **no SQLDelight in v1** — each repo keeps an in-memory per-session cache fronting `PubkyClient`.

| Repository | Responsibilities | Backing |
|---|---|---|
| `IdentityRepository` | Current session, pubky, capabilities, `signInWithRing()` / `signOut()` (brief §9.1) | Pubky FFI + `SecureSessionStore` (KVault) |
| `DeckRepository` | CRUD + `publishDeck(deck, cards)` / fetch decks; enforces the "each side has at least one populated field" rule | Pubky FFI + in-memory cache |
| `CardRepository` | CRUD cards within a deck | Pubky FFI + in-memory cache |
| `ImportRepository` | `parsePaste(rawText, separator, mapping)` per spec §6/§7, `applyTriageDecisions(draft, decisions)`, in-memory drafts, dedupe | In-memory |
| `TagRepository` | Read/write Pubky tags on decks (brief §9.3); trending via Nexus | Pubky FFI + Nexus REST |
| `DiscoveryRepository` | Trending/followed tags, decks by followed users, `followUser()` / `unfollowUser()` (brief §9.4) | Pubky FFI |
| `SrsRepository` | Per-card SRS state, today's due queue, `reviewCard(cardId, grade)` | In-memory (v1) |
| `MediaRepository` | Image + audio blob storage for cards | Pubky FFI (blobs) + platform file I/O |

All repositories are interfaces in `commonMain` with implementations in `commonMain` (`data/repository/impl/`); only the FFI- and file-touching parts drop into `androidMain`/`iosMain` actuals.

### 4.3 Presentation (ViewModels)

KMP ViewModels extend the multiplatform `androidx.lifecycle.ViewModel` and launch work in
`viewModelScope`. One per screen / sheet in brief §6 and spec §5.

```kotlin
class PasteImportViewModel(
    private val importRepo: ImportRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PasteImportUiState.Empty)
    val state: StateFlow<PasteImportUiState> = _state.asStateFlow()

    fun onTextChanged(text: String) {
        viewModelScope.launch { _state.update { /* debounce + parse */ } }
    }
    fun onSeparatorOverride(sep: Separator) { /* re-parse */ }
    fun onColumnMappingChanged(mapping: ColumnMapping) { /* re-parse */ }
    fun onNextClicked() { /* emit nav effect on _effects */ }
}
```

Rules:
- Extend `androidx.lifecycle.ViewModel`; use `viewModelScope` (cancels in `onCleared()`). Do not
  hand-roll a `CoroutineScope`/`onDispose()`.
- Mutate state with `_state.update { }`, never `_state.value = …`.
- `UiState` is a `sealed interface` (modes) or a single data class with nullable fields — never leak domain models raw.
- Events the UI fires are plain method calls. One-shot effects (navigation, haptics, toasts) are a separate `SharedFlow<UiEffect>`.
- No UI-framework imports beyond the multiplatform `androidx.lifecycle.ViewModel`. No `@Composable`, no `ObservableObject`.

See the **Coding conventions** section in `CLAUDE.md` for the full prescriptive ruleset (this doc points there to avoid re-drift).

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
│ Pastes text              │ → │ onTextChanged(text)      │ → │ ImportRepository.parsePaste()  │   │                 │
│                          │   │                          │   │ _state = Preview(draft)        │   │                 │
│                          │   │                          │   │                                │   │                 │
│                          │   │ collectAsState → redraw  │ ← │                                │   │                 │
│                          │   │ 3 flip-card previews     │   │                                │   │                 │
│                          │   │                          │   │                                │   │                 │
│ Overrides separator      │ → │ onSeparatorOverride(…)   │ → │ re-parse → Preview(draft')     │   │                 │
│                          │   │                          │   │                                │   │                 │
│ Taps Next                │ → │ nav → TriageScreen       │ → │ TriageVM(draft)                │   │                 │
│ Swipes keep/discard      │ → │ onSwipe(id, decision)    │ → │ ImportRepository.applyTriage…  │   │                 │
│                          │   │                          │   │                                │   │                 │
│ Completes triage         │ → │ nav → CommitDeckScreen   │ → │ CommitDeckVM                   │   │                 │
│ Fills metadata, Publish  │ → │ onPublish(meta)          │ → │ DeckRepository.publishDeck()   │ → │ Pubky homeserver│
│                          │   │                          │   │   → in-memory session cache    │   │                 │
│                          │   │ success screen + haptic  │ ← │ _state = Success(deck)         │   │                 │
│                          │   │                          │   │                                │   │                 │
│ Undo (within 10 s)       │ → │ onUndo()                 │ → │ DeckRepository.delete(deck)    │ → │ Pubky homeserver│
│                          │   │ nav ← paste screen       │ ← │ restore Preview(draft)         │   │                 │
└──────────────────────────┘   └──────────────────────────┘   └────────────────────────────────┘   └─────────────────┘
```

Every state listed in spec §10 maps to a single `PasteImportUiState` / `TriageUiState` / `CommitUiState` variant. The spec §10 state list is the acceptance checklist for these three VMs.

---

## 7. Pubky integration

**Decision:** Echo consumes the UniFFI-generated bindings shipped by `pubky-core-ffi-fork` directly. No handwritten FFI, no cinterop. The fork's `build_android.sh` and `build_ios.sh` produce the artifacts we check in; we don't call them from Gradle (yet).

### 7.1 Shared interface

`com.github.jvsena42.echo.data.pubky.PubkyClient` in `shared/commonMain` is a **thin** Kotlin interface that mirrors the FFI surface one-for-one. It hides the `List<String>` `[status, payload]` convention behind `Result<String>` but does **not** introduce deck/card concepts — higher-level domain operations live in the repositories layer. The interface groups calls into: keys & mnemonics, recovery files, auth/sessions (including the Pubky Ring-style `startAuthFlow` / `awaitAuthApproval` / `parseAuthUrl` flow), records (secret-key and session variants), DHT resolution, and network switching.

### 7.2 Android wiring

- UniFFI-generated `pubkycore.kt` is checked in at `shared/src/androidMain/kotlin/uniffi/pubkycore/pubkycore.kt` (package `uniffi.pubkycore`).
- Native libraries live at `shared/src/androidMain/jniLibs/{arm64-v8a,armeabi-v7a,x86,x86_64}/libpubkycore.so`. AGP picks them up automatically and merges them into the APK.
- JNA is required by the generated bindings and declared as an `@aar` dependency on `androidMain` (see `libs.versions.toml` → `jna`).
- `AndroidPubkyClient` (`shared/src/androidMain/kotlin/com/github/jvsena42/echo/data/pubky/AndroidPubkyClient.kt`) is the `PubkyClient` implementation. Blocking FFI calls are dispatched to `Dispatchers.IO`.

### 7.3 iOS wiring

- `PubkyCore.xcframework` lives at `iosApp/iosApp/Frameworks/PubkyCore.xcframework`.
- UniFFI-generated `pubkycore.swift` lives at `iosApp/iosApp/Pubky/pubkycore.swift`.
- `iosApp/iosApp/Pubky/IosPubkyClient.swift` is the Swift implementation that will conform to the Kotlin `PubkyClient` protocol (KMP exposes Kotlin interfaces as Swift protocols).
- **Xcode wiring the user must do once:** add `PubkyCore.xcframework` to the iosApp target ("Frameworks, Libraries, and Embedded Content" → "Embed & Sign"), add `pubkycore.swift` and `IosPubkyClient.swift` to the target, then enable the commented `import Shared` + protocol conformance in `IosPubkyClient.swift` once the shared framework has been built once.

### 7.4 Regenerating bindings

Run the fork's build scripts, then re-copy the outputs:

```shell
cd ../pubky-core-ffi-fork
./build_android.sh
./build_ios.sh
# then, from echo/
cp  ../pubky-core-ffi-fork/bindings/android/pubkycore.kt \
    shared/src/androidMain/kotlin/uniffi/pubkycore/pubkycore.kt
cp -R ../pubky-core-ffi-fork/bindings/android/jniLibs/. \
      shared/src/androidMain/jniLibs/
cp -R ../pubky-core-ffi-fork/bindings/ios/PubkyCore.xcframework \
      iosApp/iosApp/Frameworks/
cp  ../pubky-core-ffi-fork/bindings/ios/pubkycore.swift \
    iosApp/iosApp/Pubky/pubkycore.swift
```

A future Gradle task can automate this; not worth building until the fork stabilises.

### 7.5 Session & key storage

**Resolved.** The signed-in `Session` persists through the `SecureSessionStore` interface
(`data/storage/`), backed by Liftric KVault: `AndroidSecureSessionStore` wraps
Keystore-backed EncryptedSharedPreferences, `IosSecureSessionStore` wraps the Keychain.
Secrets never touch multiplatform-settings or ad-hoc storage.

### 7.6 Nexus indexer (global reads)

Global questions a single homeserver cannot answer — trending tags, prefix search — are
served by the Pubky Nexus REST API (`data/nexus/NexusClient`, default base
`https://nexus.staging.pubky.app`). The HTTP layer is the one-method `HttpFetcher`
interface with per-platform impls (HttpURLConnection / NSURLSession) so shared stays free
of an HTTP client dependency. Writes stay on the homeserver: deck tags are mirrored as
pubky-app-specs tag records (`/pub/pubky.app/tags/{id}`, id derived via the FFI
`create_tag_id`) which Nexus indexes network-wide.

---

## 8. Data model & persistence

### 8.0 Homeserver layout (canonical)

Published decks live under the author's pubky, one record per card plus a manifest plus media blobs. The homeserver is the source of truth; an in-memory per-session cache fronts it (see §8.3). A persistent SQLDelight cache is a possible future addition (§8.1).

**Path layout:**

```
/pub/echo/decks/{deckId}/manifest.json
/pub/echo/decks/{deckId}/cards/{cardId}.json
/pub/echo/decks/{deckId}/media/{sha256}.{ext}
```

- `{deckId}` and `{cardId}` are UUIDv4, generated client-side.
- `{sha256}` is the hex digest of the blob; acts as a content address and enables per-deck dedupe.
- `.ext` is informational; MIME is carried in the card's media ref.

**`manifest.json`:**

```json
{
  "schema_version": 1,
  "deck_id": "uuid",
  "author_pubky": "pk:...",
  "title": "Spanish A1",
  "description": "Greetings and basics",
  "cover_image_ref": { "path": "media/abc123.jpg", "mime": "image/jpeg", "sha256": "abc123" },
  "tags": ["spanish", "a1"],
  "created_at": 1739000000000,
  "updated_at": 1739000500000,
  "listen_enabled": true,
  "speak_enabled": true,
  "cards": [
    { "id": "uuid-1", "updated_at": 1739000100000 },
    { "id": "uuid-2", "updated_at": 1739000200000 }
  ]
}
```

- `cards[]` order **is** the study order.
- Manifest `updated_at` bumps on any deck-metadata change or any card add/remove/reorder. A per-card edit bumps the card record and its entry in the manifest.
- `listen_enabled` / `speak_enabled` are deck-level study opt-ins (TTS playback of the back / pronunciation practice). Both default `true`; manifests written before these fields existed decode to `true`. Additive — schema stays `1`.
- A media ref (`cover_image_ref`, `image_ref`, `audio_ref`) may instead carry a `"url"` field for a **web image** (e.g. an Unsplash photo). When `url` is set, `path`/`sha256` are empty (`""`) and no blob is stored on the homeserver; the client loads the remote URL directly.

**`cards/{cardId}.json`:**

```json
{
  "schema_version": 1,
  "id": "uuid-1",
  "deck_id": "uuid",
  "updated_at": 1739000100000,
  "front": {
    "text": "hola",
    "image_ref": null,
    "audio_ref": { "path": "media/deadbeef.m4a", "mime": "audio/mp4", "sha256": "deadbeef", "duration_ms": 820 }
  },
  "back": {
    "text": "hello",
    "image_ref": { "path": "media/cafef00d.jpg", "mime": "image/jpeg", "sha256": "cafef00d", "width": 512, "height": 512 },
    "audio_ref": null
  }
}
```

- A side must have at least one populated field; enforced in `DeckRepository.publishDeck()`.
- Media refs are relative to the deck path and resolved against `/pub/echo/decks/{deckId}/`.

**Sync algorithm (client side):**

On deck open:
1. `GET manifest.json`.
2. Diff `cards[]` against the local cache by `(id, updated_at)`.
3. For each entry whose remote `updated_at` is newer: `GET cards/{id}.json`.
4. For each local ID missing from the remote manifest: delete locally.
5. For each referenced media `sha256` not in the local blob cache: `GET media/{sha256}.{ext}`.

On local edit:
1. Write/overwrite the card record with a new `updated_at`.
2. Update the manifest entry's `updated_at` (and reorder/add/remove if needed).
3. PUT manifest.

No cross-record transactions. A momentarily stale manifest vs a newer card record is tolerated — the next sync reconciles. Last-write-wins; no tombstones, no conflict resolution in v1.

### 8.1 SQLDelight schema (NOT adopted in v1 — future sketch)

> **Status:** not in the build. v1 has no SQLDelight dependency and no local relational store —
> repos cache in memory for the session and re-fetch from Pubky. The schema below is kept only as a
> sketch for if/when a persistent offline cache is added (see §12 #3). Until then it is aspirational,
> not a description of the running app.

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

### 8.2 Preferences & secrets

multiplatform-settings is **not** wired in v1. Secrets — the signed-in `Session` — persist only
through `SecureSessionStore` (Liftric KVault → Android Keystore-backed EncryptedSharedPreferences /
iOS Keychain; see §7.5). Non-secret prefs (theme override, TTS voice, onboarding progress) are not
yet persisted; add multiplatform-settings only if/when one is needed, and never for secrets.

### 8.3 Source of truth

- **Published decks:** Pubky homeserver is canonical. An in-memory per-session cache holds the last
  fetched copy; nothing is persisted to disk in v1.
- **Study progress (SRS):** in-memory in v1; not synced to Pubky (see §12 #6).
- **Import drafts:** in-memory only — each paste is a fresh canvas (spec §4 story 5).
- **Private decks:** out of scope for v1 (spec §11). If spec §13 Q1 flips, local-only decks would need
  a persistent store (the §8.1 SQLDelight sketch) with `pubky_uri = NULL`.

---

## 9. Cross-cutting concerns

### 9.1 Dependency injection

Koin, single graph shared across platforms.

```
shared/commonMain:
  dataModule          ← repositories (own business logic)
  presentationModule  ← ViewModels
  platformModule      ← expect fun platformModule(): Module

shared/androidMain:
  actual platformModule() { PubkyClient, TtsEngine, Haptics, FileStore }

shared/iosMain:
  actual platformModule() { PubkyClient, TtsEngine, Haptics, FileStore }
```

ViewModels are bound with Koin's `viewModel { }` DSL (`org.koin.core.module.dsl.viewModel`, from
`koin-core-viewmodel`) in `SharedModule.kt`; repositories stay `single { }`. Android resolves VMs in
composables via `koinViewModel()` (`koin-compose-viewmodel`), which scopes them to the nav/backstack
lifecycle. Android bootstraps Koin in `MainActivity.onCreate`; iOS bootstraps in the `@main` `App`
initializer and hands VMs to SwiftUI views via initializers.

### 9.2 Async

Kotlin Coroutines + Flow everywhere. ViewModels launch in `viewModelScope`; all public repository
methods are `suspend` or return `Flow`. The Swift↔Flow bridge is **not yet wired** — SKIE is the
working assumption (see §12 #2) but no bridge dependency is in the build today, which is part of why
the iOS app is still inert.

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

Repository methods return `Result<T, AppError>` (Arrow `Either` or handwritten — decide at first use). ViewModels map errors to user-facing banners/toasts/snackbars per brief §7.

### 9.4 Accessibility

Shared VMs expose semantic labels (e.g. `"Card 1 of 3 preview, front: hola"`) as strings on the state. Platform UIs wire them into VoiceOver / TalkBack. Reduce-motion and dynamic-type handling live in the platform UI (spec §12, brief §10).

### 9.5 Logging

Reserve a `Logger` interface in `commonMain` with no-op default. Platform actuals can plug into Logcat / `os_log`. Telemetry is out of scope for v1.

---

## 10. Testing strategy

- **`commonTest`** — the important tier.
  - `ImportRepository.parsePaste()`: one test per rule in spec §6, plus every edge case in spec §9.
  - Repositories against a `FakePubkyClient` (no SQLDelight to fake in v1 — the cache is in-memory).
  - ViewModels with [Turbine](https://github.com/cashapp/turbine) asserting state sequences for every spec §10 state. Drive the `viewModelScope` with `Dispatchers.setMain(testDispatcher)` (kotlinx-coroutines-test) rather than injecting a scope.
- **Android UI** — Compose UI tests (`composeApp/androidUnitTest` or `androidInstrumentedTest`) for Paste → Triage → Commit and Study session.
- **iOS UI** — XCTest snapshot tests for the same flows.
- **Integration** — a minimal smoke target that exercises the real `pubky-core-ffi-fork` against a test homeserver; kept separate from the unit suite.

---

## 11. Build & tooling

- **Gradle** with version catalog (`gradle/libs.versions.toml`). Kotlin, AGP, and Compose versions already pinned in the scaffold.
- **Plugins (actual):** `org.jetbrains.kotlin.multiplatform`, `com.android.library`/`com.android.application`, `org.jetbrains.kotlin.plugin.serialization`, the Compose Multiplatform + Compose-compiler plugins (Android-only Compose), and `io.gitlab.arturbosch.detekt`. Koin is a runtime dependency (no plugin). **No `app.cash.sqldelight` plugin** — SQLDelight is not adopted (§8.1).
- **iOS framework packaging:** `shared` is consumed as a static framework (`baseName = "Shared"`, `isStatic = true`) per `shared/build.gradle.kts`; an XCFramework / SPM packaging step can come later.
- **SKIE** is **not** in the build yet (pending §12 #2); it would plug into the `shared` Gradle build once the Swift↔Flow bridge is chosen.
- **CI:** run `commonTest`, Android unit + Compose tests, iOS unit + snapshot tests per PR.

---

## 12. Open questions

Pulled forward from spec §13 plus architecture-specific items.

1. **UI strategy final call.** Working assumption: fully native UI per platform. Compose Multiplatform UI is not used. Confirm with design + eng leads before the first screen ships.
2. **Swift ↔ Flow bridge.** SKIE vs KMP-NativeCoroutines. SKIE is the working assumption; revisit if it blocks iOS builds.
3. **Multi-module split timing.** Single `shared` module for v1; split into `:core / :data / :domain / :feature-*` if build times or ownership boundaries require it.
4. **Private decks.** If spec §13 Q1 flips in favor of private decks, `DeckRepository` gains a local-only write path and `pubky_uri` stays `NULL` until the user opts in.
5. **Secret key & session storage** (§7.5). ~~Needs a decision~~ **Resolved**: `SecureSessionStore` via Liftric KVault (Keystore-backed EncryptedSharedPreferences on Android, Keychain on iOS).
6. **SRS sync.** v1 keeps SRS local. If we ever want cross-device study, `SrsRepository` gains a Pubky-backed write path.
7. **AI / OCR / URL import** (spec §14) — all reuse `TriageVM` + `CommitDeckVM`. No architectural change needed, only new `ImportRepository` entry points and screens.
8. **Binding regeneration automation.** Today the fork's `build_android.sh` / `build_ios.sh` are run manually and artifacts are copied in (§7.4). A Gradle task can automate this once the fork API stabilises.

---

## 13. References

- [`docs/specs.md`](./specs.md) — Paste-to-Import product spec.
- [`design/DESIGN_GUIDELINE.md`](../design/DESIGN_GUIDELINE.md) — design system and screen brief.
- `pubky-core-ffi-fork` — local sibling repo at `../../../pubky-core-ffi-fork`.
- Pubky Ring deeplink contract — brief §9.1.

---

*End of architecture doc. Update alongside spec and design-brief revisions; do not let it drift.*
