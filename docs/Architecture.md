# Loopky — Architecture

> **Scope:** Technical architecture for the Loopky KMP app. Describes what is built, not what was planned; sections that describe a possible future say so.
> **Reads alongside:** [`docs/specs.md`](./specs.md)

---

## 1. Overview

Loopky is a **Kotlin Multiplatform** flashcards app targeting iOS and Android. Business logic — domain models, repositories, and ViewModels — lives in a single `shared` module (`commonMain`). Repositories own the business logic; there is no separate use-case layer. Each platform renders its own native UI: **Jetpack Compose** on Android (`composeApp/androidMain`) and **SwiftUI** on iOS (`iosApp/`). Identity, social graph, tags, and published decks are backed by **Pubky**, accessed through the UniFFI bindings that `pubky-core-ffi-fork` generates (§7).

**Android is feature-built end to end; iOS is wired but unproven.** Every surface described here runs on Android. The iOS app has its SwiftUI screens, a live Koin bootstrap and the Flow bridge, but has never been driven against a real homeserver — treat its behaviour as unverified rather than blocked.

Deck import — the flow the rest of the product hangs off — is specified in [`docs/specs.md`](./specs.md).

---

## 2. Guiding principles

1. **Share logic, not pixels.** Everything above the UI layer is shared Kotlin. Rendering, navigation, and platform ergonomics are native.
2. **Platform-native feel.** iOS gets HIG sheets, SF Pro, and `UIImpactFeedback`; Android gets Material 3, Roboto, and `HapticFeedbackConstants`. Same state, different skins.
3. **Parse and triage work offline.** Paste-to-Import (spec §5) needs no network; only publish touches the homeserver. The app as a whole is *not* offline-first — everything else reads through Pubky.
4. **Pubky is the source of truth for published data.** The cache is in-memory and per-session — not a parallel database, and not persisted (§8.1). There is no private-deck local-only path in v1 (spec §11).
5. **One ViewModel per screen, one StateFlow per ViewModel.** Screens are thin; state transitions live in shared code and are unit-testable.
6. **Platform glue only at the edges.** Pubky FFI, TTS, speech recognition, haptics, clipboard and file I/O are the only code with a platform half. Everything else is pure `commonMain`. Some of that glue is `expect`/`actual` and some is a plain interface bound per-platform in Koin (`Speaker`, `SpeechRecognizer`, `BackgroundTasks`, `PubkyRingPresence`) — the Koin form is preferred where the implementation needs platform context or lifecycle.

---

## 3. Module layout

```
loopky/
├── shared/                        ← KMP business logic
│   └── src/
│       ├── commonMain/            ← domain + data + presentation (VMs)
│       ├── commonTest/            ← the whole test suite
│       ├── jvmSharedMain/         ← the JVM family: JNA bindings, java.time/security/zip, HTTP
│       ├── androidMain/           ← TTS, speech, WorkManager, keystore vaults, Koin
│       ├── jvmMain/               ← desktop: file stores, ImageIO, no-op platform, libpubkycore
│       └── iosMain/               ← Pubky FFI adapter, TTS, speech, BGTaskScheduler, Koin
│
├── composeApp/                    ← Android app
│   └── src/androidMain/kotlin/.../
│       ├── ui/                    ← Compose screens + navigation
│       ├── LoopkyApp.kt           ← Application; starts Koin
│       └── MainActivity.kt        ← single activity, deeplink entry
│
├── cli/                           ← `loopky`, the headless client (§13)
│   └── src/main/kotlin/.../cli/   ← commands, --json schema, exit codes, terminal QR
│
└── iosApp/                        ← iOS app
    └── iosApp/
        ├── Views/                 ← SwiftUI screens
        ├── Navigation/            ← NavigationStack
        ├── DI/                    ← Koin bootstrap + the Flow→SwiftUI bridge
        ├── Pubky/                 ← IosPubkyClient.swift + generated pubkycore.swift
        ├── Frameworks/            ← PubkyCore.xcframework
        └── iOSApp.swift           ← @main
```

**Dependency direction:**

```
     ┌──────────────────┐  ┌────────────────┐  ┌──────────────┐
     │ composeApp       │  │ iosApp         │  │ cli          │
     │ (Compose + Nav)  │  │ (SwiftUI + NS) │  │ (no UI, §13) │
     └────────┬─────────┘  └────────┬───────┘  └──────┬───────┘
              │                     │                 │
              └──────────┬──────────┴─────────────────┘
                         ▼
                   ┌───────────┐
                   │  shared   │
                   │ commonMain│
                   │  domain   │
                   │   data    │
                   │    VMs    │
                   └─────┬─────┘
                         ▼
        jvmSharedMain / androidMain / jvmMain / iosMain actuals
                         ▼
             pubky-core-ffi-fork bindings
```

`:cli` consumes the same graph and stops at `data` — it resolves repositories from Koin and
treats `presentation/` as dead weight. That is the shape the no-use-case-layer rule pays off in:
a third consumer needed no new layer, only a platform module.

Platform UI modules depend on `shared`. `shared` depends only on Kotlin stdlib, Coroutines, kotlinx-serialization, Koin (+ the Koin ViewModel DSL), the multiplatform `androidx.lifecycle` ViewModel, Liftric KVault, and (via expect/actual) the Pubky FFI. SQLDelight and multiplatform-settings are **not** dependencies in v1 — see §8.

> **Note (v1 reality vs. earlier design).** This doc originally sketched a SQLDelight cache, multiplatform-settings, and SKIE. None were ever added: repositories are Pubky-only with an in-memory per-session cache, secrets persist via `SecureSessionStore` (KVault), and the Swift↔Flow bridge is hand-rolled (§9.2). Sections below are annotated where they describe a *possible future* rather than the current build.

> **UI strategy — settled.** Fully native UI per platform: Compose on Android, SwiftUI on iOS. Compose Multiplatform UI is **not** used for screens, and `composeApp` is Android-only despite the name.

---

## 4. Layered architecture inside `shared/commonMain`

### 4.1 Domain

Pure Kotlin. No framework imports.

- **Models:** `Deck`, `Card` (+ `CardSide`, `MediaRef`), `Tag`, `PubkyIdentity`, `ImportDraft`, `ParsedRow`, `SrsGrade` (Again/Hard/Good/Easy), `SrsState`, `StudySettings`, `AppError` (+ `ErrorReason`).

Business logic (parse, triage, publish, review, follow, sign-in/out) lives on repositories — see §4.2. There is no separate use-case layer.

### 4.2 Data (Repositories)

Repositories are the only layer that talks to Pubky, and they also **own the business logic**: parsing, triage, publishing, SRS grading, follow/unfollow, and session handling are all methods on the relevant repo. They expose **`Flow`s** for reads and suspend functions for writes. No UI state lives here. There is **no SQLDelight in v1** — each repo keeps an in-memory per-session cache fronting `PubkyClient`.

| Repository | Responsibilities | Backing |
|---|---|---|
| `IdentityRepository` | Current session, pubky, capabilities, `signInWithRing()` / `signOut()` (§7.8) | Pubky FFI + `SecureSessionStore` (KVault) |
| `DeckRepository` | CRUD + `publish(deck, cards)` / fetch decks; enforces the "each side has at least one populated field" rule. Also owns **deck** following — `followDeck()` / `unfollowDeck()` / `listFollowed()` — and `clone()` (§8.0). Deck follows live here rather than on `DiscoveryRepository` because `listFollowed()` merges with `listOwned()` behind one `changes` flow, `sync()` resolves a followed deck's author from the subscription, and `DiscoveryRepositoryImpl` already depends on this repo | Pubky FFI + in-memory cache |
| `CardRepository` | CRUD cards within a deck | Pubky FFI + in-memory cache |
| `ImportRepository` | `parse(rawText, separator)` per spec §6/§7 (col 1 → front, col 2 → back, extras dropped — spec §8), `setDecision()` / `keptRows()` triage, in-memory drafts, dedupe | In-memory |
| `TagRepository` | Read/write Pubky tags on any subject (deck or profile); the reserved `loopky-*` index labels via `putReservedTag`; deck-topic (`trendingDeckTags`), tagged-subject and tagger-count reads via Nexus (§7.7) | Pubky FFI + Nexus REST |
| `DiscoveryRepository` | Decks by followed **users**, `followUser()` / `unfollowUser()` — deck-level following is on `DeckRepository`, plus verified network-wide reads: `decksByTagGlobal()`, `loopkyUsers()` and `suggestedPeople()` | Pubky FFI + Nexus REST |
| `SrsRepository` | Per-card SRS state; the study queue (**due reviews then never-seen cards** — `isNew` is not `isDue`, §8.6), per-deck `DeckCounts`, `mastery()`, `review(card, grade)`, and today's `dailyProgress` | Pubky FFI + in-memory cache |
| `MediaRepository` | Image + audio blob storage for cards | Pubky FFI (blobs) + platform file I/O |
| `SettingsRepository` | The user's own study settings (`/pub/loopky/settings.json`) — the new-cards-per-day goal and the Hard/Good/Easy intervals (§8.6) | Pubky FFI + `AppPreferences` mirror |

All repositories are interfaces in `commonMain` with implementations in `commonMain` (`data/repository/impl/`); only the FFI- and file-touching parts drop into `androidMain`/`iosMain` actuals.

### 4.3 Presentation (ViewModels)

KMP ViewModels extend the multiplatform `androidx.lifecycle.ViewModel` and launch work in
`viewModelScope`. One per screen or sheet.

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

The shipped set, one package per surface under `presentation/`:

| Package | ViewModels |
|---|---|
| `onboarding/` | `OnboardingViewModel` |
| `signup/` | `SignupStartViewModel`, `PhoneVerificationViewModel`, `LightningVerificationViewModel`, `InviteCodeViewModel`, `LocalSignupViewModel` |
| `home/` | `HomeViewModel` |
| `decks/` | `DecksLibraryViewModel`, `DeckDetailViewModel`, `DeckEditorViewModel`, `EditCardViewModel` |
| `importflow/` | `PasteImportViewModel`, `TriageViewModel`, `PublishDeckViewModel`, `BulkImportViewModel` |
| `study/` | `StudySessionViewModel` |
| `discover/` | `DiscoverViewModel`, `SearchViewModel`, `TagBrowseViewModel` |
| `profile/` | `ProfileViewModel`, `FriendProfileViewModel`, `FollowListViewModel` |
| `settings/` | `SettingsViewModel` |
| `media/` | `ImageSheetViewModel` |

---

## 5. UI layer (per platform)

Both platforms consume the same VMs. Only rendering, navigation, and platform glue differ.

### 5.1 Android (`composeApp/androidMain`)

- **UI:** Jetpack Compose, Material 3 components styled by Loopky design tokens.
- **State:** `val ui by vm.state.collectAsStateWithLifecycle()` in each screen composable.
- **Navigation:** Jetpack Navigation Compose, through `NavController.navigateTo()` (`ui/nav/NavExt.kt`), which dedups the current destination. Four top-level tabs (Study / Decks / Discover / Profile) on a Material 3 Expressive `ShortNavigationBar`.
- **DI:** Koin Android, started in `LoopkyApp.onCreate` (the `Application`, not the activity — WorkManager can start the process without one). Screens resolve their VM via `koinViewModel()`, which scopes it to the nav backstack entry.
- **Platform glue:** `AVSpeechSynthesizer`'s Android counterpart is `android.speech.tts.TextToSpeech`; haptics via `HapticFeedbackConstants`; image picker via Activity Result APIs.

### 5.2 iOS (`iosApp/`)

- **UI:** SwiftUI, styled by Loopky design tokens mirrored in Swift.
- **State:** shared VMs exposed as `ObservableObject` wrappers over the hand-rolled Flow bridge — `IosFlowWatcher` on the Kotlin side, `FlowObserver` / `FlowEffectSink` on the Swift side. See §9.2.
- **Navigation:** `NavigationStack` per tab, `.sheet`/`.fullScreenCover` for Paste-to-Import and triage.
- **DI:** Koin started from `iOSApp.swift` via `doInitKoin(rawPubkyClient:)`, which hands in the Swift `IosPubkyClient`; VMs are handed to views via initializers.
- **Platform glue:** `AVSpeechSynthesizer` for TTS, `UIImpactFeedbackGenerator` for haptics, `PHPickerViewController` for images, `AVAudioRecorder` for audio cards.

### 5.3 Theming

Brand tokens are **hand-maintained in two places** and mirror each other — there is no token file
and no codegen:
- Android: `composeApp/.../ui/theme/LoopkyColors.kt`, applied through `LoopkyTheme`.
- iOS: `iosApp/iosApp/Views/LoopkyColor.swift`.
- The shared module does **not** hold a Compose theme.

A palette change therefore has to be made twice. That is the cost of native-per-platform UI; it is
small enough (one data class, one enum) that a generator has not earned its keep.

---

## 6. State flow — Paste-to-Import walkthrough

Ties spec §5 (UX flow) to code. Each arrow is an actual function call.

```
┌─ User action ────────────┐   ┌─ Platform UI ────────────┐   ┌─ shared VMs/repos ─────────────┐   ┌─ Pubky / local ─┐
│ Taps "+" → Paste screen  │ → │ PasteScreen              │ → │ PasteImportViewModel: Empty    │   │                 │
│                          │   │                          │   │                                │   │                 │
│ Pastes text              │ → │ onTextChanged(text)      │ → │ ImportRepository.parse()       │   │                 │
│                          │   │                          │   │ _state = Preview(draft)        │   │                 │
│                          │   │                          │   │                                │   │                 │
│                          │   │ collectAsState → redraw  │ ← │                                │   │                 │
│                          │   │ 3 flip-card previews     │   │                                │   │                 │
│                          │   │                          │   │                                │   │                 │
│ Overrides separator      │ → │ onSeparatorOverride(…)   │ → │ re-parse → Preview(draft')     │   │                 │
│                          │   │                          │   │                                │   │                 │
│ Taps Next                │ → │ nav → TriageScreen       │ → │ TriageViewModel(draft)         │   │                 │
│ Swipes keep/discard      │ → │ onSwipe(id, decision)    │ → │ ImportRepository.setDecision() │   │                 │
│                          │   │                          │   │                                │   │                 │
│ Completes triage         │ → │ nav → PublishDeckScreen  │ → │ PublishDeckViewModel           │   │                 │
│ Fills metadata, Publish  │ → │ onPublish(meta)          │ → │ DeckRepository.publish()       │ → │ Pubky homeserver│
│                          │   │                          │   │   → in-memory session cache    │   │                 │
│                          │   │ success screen + haptic  │ ← │ _state = Success(deck)         │   │                 │
│                          │   │                          │   │                                │   │                 │
│ Undo (within 10 s)       │ → │ onUndo()                 │ → │ DeckRepository.delete(deck)    │ → │ Pubky homeserver│
│                          │   │ nav ← paste screen       │ ← │ restore Preview(draft)         │   │                 │
└──────────────────────────┘   └──────────────────────────┘   └────────────────────────────────┘   └─────────────────┘
```

Every state listed in spec §10 maps to a single `PasteImportUiState` / `TriageUiState` / `PublishDeckUiState` variant. The spec §10 state list is the acceptance checklist for these three VMs.

Bulk file import (`BulkImportViewModel`) rejoins this flow at the publish step, skipping triage for a summary screen — spec §5.4.

---

## 7. Pubky integration

**Decision:** Loopky consumes the UniFFI-generated bindings shipped by `pubky-core-ffi-fork` directly. No handwritten FFI, no cinterop. The fork's `build_android.sh` and `build_ios.sh` produce the artifacts we check in; we don't call them from Gradle (yet).

### 7.1 Shared interface

> **Bulk operations.** `PubkyClient` has no batch/multi-put primitive, and all writes are sequential per operation. Publishing, deck listing, the `delete()` sweep and chunk reads all want bounded concurrency (#43 §4). Since the interface is a deliberate 1:1 FFI mirror, that stays a *repository-level* concern unless the FFI grows bulk calls. Two prerequisites before turning concurrency on: `MutableSessionProvider.value` is a plain non-atomic `var` read on every retry attempt, and `SessionRevalidatorImpl` serializes revalidation without coalescing it. Every `list()` call site now pages through `cursor`/`limit` (`data/repository/impl/PubkyPaging.kt`); the deck listing also asks for `shallow`.

> **One authenticated write is one request — but only since #105, and only because the fork caches the session.** Every `*_with_session` FFI call used to begin with `restore_session`, whose cookie path ends in a full `/session` round trip. A record write therefore cost **two** HTTP requests: a revalidation nobody asked for, then the write. A 9,263-card deck delete is ~100 records, so ~200 requests, half of them overhead — and publish paid the same tax per chunk. The overhead was also what saturated the homeserver's rate limiter, which forced the sweeps down to a concurrency of 2 and serialized them; the two effects compounded. `src/session_cache.rs` in the fork now imports a session once per secret and hands out clones, so both sweeps run at the ordinary [`MAX_IN_FLIGHT`] width again.
>
> Two things worth not re-deriving. **True batching is not available**: the homeserver's DELETE route handles exactly one entry and the SDK exposes only `storage().delete(path)`, so a recursive delete needs homeserver *plus* SDK *plus* FFI work. And **the reuse is safe because revalidating first was never what made a write safe** — the homeserver checks the session on the write itself and answers 401 when it will not accept it, so an expiry is caught either way; the fork drops the cached session on a 401/403, re-imports, and retries once, which is why `isSessionExpired()` still sees the same `"Failed to import session: …"` wording it classifies on.

`com.github.jvsena42.loopky.data.pubky.PubkyClient` in `shared/commonMain` is a **thin** Kotlin interface that mirrors the FFI surface one-for-one. It hides the `List<String>` `[status, payload]` convention behind `Result<String>` but does **not** introduce deck/card concepts — higher-level domain operations live in the repositories layer. The interface groups calls into: keys & mnemonics, recovery files, auth/sessions (including the Pubky Ring-style `startAuthFlow` / `awaitAuthApproval` / `parseAuthUrl` flow), records (secret-key and session variants), DHT resolution, and network switching.

### 7.2 Android wiring

- UniFFI-generated `pubkycore.kt` is checked in at `shared/src/androidMain/kotlin/uniffi/pubkycore/pubkycore.kt` (package `uniffi.pubkycore`).
- Native libraries live at `shared/src/androidMain/jniLibs/{arm64-v8a,armeabi-v7a,x86,x86_64}/libpubkycore.so`. AGP picks them up automatically and merges them into the APK.
- JNA is required by the generated bindings and declared as an `@aar` dependency on `androidMain` (see `libs.versions.toml` → `jna`).
- `AndroidPubkyClient` (`shared/src/androidMain/kotlin/com/github/jvsena42/loopky/data/pubky/AndroidPubkyClient.kt`) is the `PubkyClient` implementation. Blocking FFI calls are dispatched to `Dispatchers.IO`.

### 7.3 iOS wiring

- `PubkyCore.xcframework` lives at `iosApp/iosApp/Frameworks/PubkyCore.xcframework`.
- UniFFI-generated `pubkycore.swift` lives at `iosApp/iosApp/Pubky/pubkycore.swift`.
- `iosApp/iosApp/Pubky/IosPubkyClient.swift` conforms to **`RawPubkyClient`**, not `PubkyClient` — a dumb pass-through returning the FFI's native `[status, payload]` arrays, because `kotlin.Result` and suspend functions cannot be implemented from Swift. `IosPubkyClientAdapter` (`shared/iosMain/.../data/pubky/`) wraps it into the shared `PubkyClient` contract on the Kotlin side and does the threading. Binary payloads cross the boundary Base64-encoded and are decoded in the Swift layer so blobs land raw on the homeserver.
- The Xcode target already embeds `PubkyCore.xcframework` and compiles `pubkycore.swift` + `IosPubkyClient.swift`; `iOSApp.swift` hands the client to Koin via `doInitKoin(rawPubkyClient:)`.

**Unproven, not unwired.** Nobody has driven the iOS app against a real homeserver, so treat its behaviour as unverified rather than blocked.

### 7.4 Regenerating bindings

Run the fork's build scripts, then re-copy the outputs:

`build_android.sh` needs `ANDROID_NDK_HOME` — it strips the libraries with the NDK's own
`llvm-strip`, because cargo-ndk 4.x dropped the stripping it used to do by default and each
unstripped ABI is ~40% larger.

```shell
cd ../pubky-core-ffi-fork
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.1.12297006   # or whichever NDK you have
./build_android.sh
./build_ios.sh
# then, from loopky/
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

**A key Loopky holds itself** goes in the same secrets vault, through `LocalKeyStore` (#147).
Three reasons it is not a field on the stored `Session`: the key exists *before* a session does (on
the generated-phrase screen `signUp` has not run yet); `KeyCustody` — who holds the key, and which
backups are done — is asked constantly by surfaces that must never see a secret, and deriving it
from a session blob would mean decrypting the key to answer a question about it; and handing
custody to Ring later becomes a flag transition rather than a schema migration of a blob every
install already holds. Only the custody is cached in memory, so the secret is resident for as long
as a caller needs it rather than for the life of the process.

**Signing out clears that key**, because a signed-out device holding a secret key is a credential
nobody is watching. `signOut(force)` *refuses* with `UnbackedUpLocalKey` when nothing has been
backed up, so the UI is forced to raise a confirm before the only copy of an identity is destroyed
— the guard is in the repository rather than only in the dialog, so no later caller can sign out
silently. A key restored from a phrase or file counts as already backed up: the user proved they
hold it by signing in with it.

Non-secret user preferences go through a *separate* interface in the same package,
`AppPreferences` — `AndroidAppPreferences` over `SharedPreferences`, `IosAppPreferences` over
`NSUserDefaults`. Deliberately not the same door: `SecureSessionStore` pays keystore/keychain
costs for the session, and a boolean the user flipped in Settings has no business sharing it.
Device-local for v1 where that is the right answer: `shareOnPubky` (#39) and the debug-only
`pubkyEnvironment` are properties of *this install*.

**Study settings took the other route.** The new-cards-per-day goal and the Hard/Good/Easy
intervals live in a `/pub/loopky/settings.json` record instead (`SettingsRepository`, §8.6), because
they decide `dueAt` values — and the review state those produce already syncs, so two devices
holding different intervals would write state computed from different rules. `AppPreferences` keeps
a **mirror** of the last-known-good copy (`cachedStudySettings`) so an offline session still
schedules with the user's own numbers; that copy is a read cache and never authorizes a write.

Today's study tally is device-local again, through `StudyProgressStore` alongside
`PendingReviewStore` — it is a motivational counter, and reconciling "cards introduced today"
across devices would cost a round trip to answer a question nobody would notice getting slightly
wrong.

### 7.6 Nexus indexer (global reads)

Global questions a single homeserver cannot answer — trending tags, prefix search, "which
decks carry this tag", "how many people follow this deck", "who else uses Loopky" — are
served by the Pubky Nexus REST API (`data/nexus/NexusClient`). **Which deployment it reads is
part of `PubkyEnvironment`, not a wire of its own** (#205): staging and production index separate
networks, and a mismatched indexer does not error — it answers normally, for the other network, so
discovery, tags, followers, search and every avatar simply come back empty while sign-in, publish
and study keep working over pkarr. Fusing it with the gate, the homeserver and the web client makes
that unconfigurable. The single escape hatch is `LOCAL_NEXUS_BASE_URL` in `local.properties`, named
for the one legitimate case — a Nexus running on your own machine (#58) — and blank on release. The
HTTP layer is the small `HttpFetcher` interface with
per-platform impls (HttpURLConnection / NSURLSession) so shared stays free of an HTTP client
dependency. It has two entry points: `get()`, where any non-2xx is a failure, and `send()`,
where **the status is data**. The second exists for gated APIs that answer 403 for "not in
your region", 408 for "ask again" and 429 with the retry window in the body — verdicts the
caller has to read rather than catch (§7.8).

Writes stay on the homeserver: tags are written as pubky-app-specs tag records (id derived
via the FFI `create_tag_id`) which Nexus indexes network-wide. **Which namespace the record
goes in decides how — and whether — it is indexed at all; see §7.7.**

Reads in use today:

| Call | Endpoint | Used for |
|---|---|---|
| `searchTagsByPrefix` | `/v0/search/tags/by_prefix/{prefix}` | tag autocomplete (no caller yet — but it *does* reach deck labels, unlike the hot list) |
| `resourcesByTag` | `/v0/stream/resources?app=loopky&tags=…&sorting=taggers_count` | global deck browse **and** client-side deck-tag topics |
| `resourceByUri` | `/v0/resource/by-uri` | per-deck tagger counts ("N followers") |
| `usersByProfileTag` | `/v0/search/users/by_tags?tags=…` | the `loopky-user` directory — **404 on prod until it redeploys, see §7.7 point 9** |
| `postAuthorsByTag` | `/v0/search/posts/by_tag/{label}` | deck-announcement authors, the directory's second source |
| `userTaggers` | `/v0/user/{id}/taggers/{label}` | verifying a self-tag |
| `searchUsersByName` | `/v0/search/users/by_name/{prefix}` | the people half of search — a name *prefix*, lexicographic, not a substring |
| `searchUsersById` | `/v0/search/users/by_id/{prefix}` | the same, by pubky prefix (Nexus rejects fewer than 3 chars) |
| `followers` | `/v0/user/{id}/followers` | who follows someone — the one direction a homeserver cannot answer |

**Deck titles are not indexed anywhere.** A manifest is a record on a homeserver and nothing
crawls its contents, so `DiscoveryRepository.searchDecks` matches titles against manifests the
client has actually fetched: the global browse sample (cached for the session, ~30 manifest reads)
widened by an exact `resourcesByTag` read when the query is a single word. A deck outside both is
not findable by title until something indexes titles — the same ceiling as #58 for topics.

`hotTags` (`/v0/tags/hot`) was removed in #26. It returns pubky.app *social* labels — live it hands
back `pubky` and `bitcoin` — and point 3 below makes it structurally impossible for a deck label to
appear there, so it could never serve deck discovery. `TagRepository.trendingDeckTags` replaces it
by aggregating labels client-side over one `resourcesByTag` page.

Everything read back is **untrusted** — anyone can write any label on any URI — so
`DiscoveryRepositoryImpl` verifies before returning: a deck URI must parse as a manifest,
its tagger must be its author, and the manifest must actually fetch; a user must have
self-tagged and have a resolvable profile. Counts come from `taggers_count` (distinct
taggers) and are approximate: fine to display, never to gate on.

### 7.7 Tag indexing: what Nexus does and does not index

Non-obvious and invisible from Loopky's side — a rejected tag produces no error anywhere,
it simply never appears. Verified against `pubky-nexus` and `pubky-app-specs` sources and
live against staging (issue #40).

**1. Two ingest paths, chosen by the tag record's own namespace.** A record at
`/pub/pubky.app/tags/{id}` is re-parsed against the pubky.app URI grammar and accepted only
if its subject is a pubky.app **post or profile**
(`nexus-watcher/src/events/handlers/tag.rs:34-52`, `pubky-app-specs/src/uri_parser.rs:128-171`).
A record under any *other* `/pub/{app}/tags/{id}` takes the universal-tag path
(`nexus-watcher/src/events/handlers/universal_tag.rs:94-138`) and files the subject as a
generic **resource**, whatever URI it is.

| Subject | Record must live at | Indexed as | Readable via |
|---|---|---|---|
| `pubky://{id}/pub/pubky.app/profile.json` | `/pub/pubky.app/tags/{id}` | user | `/v0/user/{id}/tags`, `/v0/user/{id}/taggers/{label}`, `/v0/search/users/by_tags`, `/v0/tags/hot` |
| `pubky://{author}/pub/loopky/decks/{id}/manifest.json` | `/pub/loopky/tags/{id}` | resource | `/v0/stream/resources?app=loopky`, `/v0/resource/by-uri` |

`TagRepositoryImpl` routes on the subject for exactly this reason.

**2. `app` is the record's path segment, verbatim.** Nothing about it derives from the
subject URI. Loopky resources are `app=loopky` because records live at `/pub/loopky/tags/`;
`app=loopky.app` would match nothing. (Live check: `?app=mapky.app` returns mapky's
resources, `?app=mapky` returns none.)

**3. Resource tags never trend.** `/v0/tags/hot` and `/v0/tags/taggers/{label}` are
restricted to `Post|User` targets (`nexus-common/src/db/graph/queries/get.rs:614-640`) — and the
latter is unusable even for those, see point 9 — so
**deck labels — including `loopky-deck` — cannot appear in the trending row**. Only
`loopky-user`, a profile tag, can. Trending over deck tags therefore has to be aggregated
client-side from `/v0/stream/resources?app=loopky&sorting=taggers_count` — which is what
`TagRepository.trendingDeckTags` does (#26): one request, labels grouped and ranked by how many
distinct decks carry them. The stream returns each resource's *whole* label list with per-label
tagger counts, which is what makes this possible from a single call.

**4. A deck is also labelled with each language it declares.** Picking a language in the
publish flow or the deck editor adds an ordinary tag — `"spanish"` for `es-ES` — to the deck's
tag list, so `/v0/stream/resources?app=loopky` can answer "Spanish decks" for someone with no
follow relationship to the author. Three details:

- **An ordinary label, not a reserved one.** It lives in `Deck.tags` and goes out through the same
  `syncTags` diff as any topic, which is what puts it in `trendingDeckTags` and tag browse — both
  filter the `loopky-` namespace out, on purpose. The author can drop it like any other tag; the
  manifest's `front_lang`/`back_lang` stay the source of truth for *speech*, so a dropped label
  costs discoverability, never Listen or Speak.
- **Base subtag, named not coded, plus the umbrella.** `es-ES` and `es-MX` are both `"spanish"` —
  the region picks a voice, and splitting the label by it would halve a search for Spanish decks.
  `LanguageTags` holds the subtag → English name table (an unknown subtag labels under itself
  rather than going untagged) and adds `"language"` alongside, so a browser who wants language
  decks at all does not have to guess which of seventy language labels to open first. A deck that
  has declared nothing gets neither.
- **Retyping swaps the label.** `LanguageTags.retag` drops what the previous pair contributed as
  it adds the new pair's, or a deck retyped from Spanish to French stays listed as Spanish
  forever. It runs in the ViewModel, on the pick, so the tag chips show what will be published.

**5. Announcement posts (#39) are how deck topics reach the global index.** A post is a `Post`
target where a manifest can only be a resource, so `DiscoveryRepository.announceDeck` writes the
deck's topics **and `loopky-deck` onto the post**, on top of the manifest tags. A deck is
therefore labelled in both graphs, each for what it can do: the post tags reach
`/v0/search/posts/by_tag/{label}`, `/v0/tags/hot` and every other app's feed; the manifest tags
are how Loopky finds its own decks and keep working when announcing is switched off. **Post
subjects route to the pubky.app namespace**, alongside profiles — a post tag under
`/pub/loopky/tags/` does reach the same handler today, but only because `sync_put_resource`
delegates `Post|User` subjects back to `sync_put`, which is an implementation detail rather than
a contract.

The post itself is a public write to the user's social feed on their behalf, which is why it sits
behind #39's per-action consent prompt and default-on switch.

Verified against staging: `/v0/search/posts/by_tag/kanjitest` and `/by_tag/loopky-deck` both
return the announcement, while the manifest's own tags still resolve from `/v0/resource/by-uri`.

Two things the post record has to get right, both silent when wrong:

- **The embed kind must be `link`, never `short`.** Nexus reads a short embed as a *repost* and
  makes the embedded URI a dependency that must already be an indexed post
  (`nexus-common/src/models/post/relationships.rs:117-121`). A deck manifest never is, so such a
  post parks in the retry queue and is never indexed.
- **The deck cover goes in the body, not in `attachments`.** pubky.app resolves a post's
  attachments strictly as pubky.app **file records** — it calls `FileController.getMetadata` on
  each URI and builds an image URL from the returned file id — so any other URI renders nothing at
  all (`PostAttachments.tsx`). What it *does* render is the first `http(s)` link in the **content**:
  it runs that through an OpenGraph probe and, when the response is an image content-type, shows
  the image inline (`GenericPreview.tsx`, `detectMediaType`). So the cover travels as a plain URL
  in the body. Nothing linkifies `pubky://`, so the cover is always the first link found whatever
  order the body is in.
- **A homeserver-blob cover cannot be shown on the web at all.** Only a web (Unsplash) cover has an
  `http(s)` URL; a gallery upload has only a `pubky://` one, which the OpenGraph probe — an
  ordinary HTTP fetch — cannot follow. Showing those means giving the cover a pubky.app **blob +
  file record**, and a blob's id is Crockford-base32 of blake3 over its bytes, strictly validated
  on ingest (`PubkyAppBlob::create_id`, `HashId::validate_id`). Neither platform ships blake3, the
  FFI exposes only `create_tag_id`, and there is no Kotlin Multiplatform blake3 on Maven Central —
  so this is blocked on an FFI addition, not on a few lines of Kotlin.
- **Nothing makes the `pubky://` URI clickable on the web, so do not try again.** pubky.app
  renders post content as markdown and neither path linkifies it: remark-gfm's autolink literals
  cover only `http(s)`, `www.` and `mailto`, and a CommonMark autolink (`<pubky://…>`) survives
  the parse only to have its `href` blanked by react-markdown 10's `defaultUrlTransform`, which
  permits `https?|ircs?|mailto|xmpp` and nothing else. No public HTTPS gateway maps a `pubky://`
  record to a browsable page either. What *is* clickable in pubky.app is `#hashtags` (→ its tag
  search) and `pk:`/`pubky` + 52 chars (→ a profile, rendered as `@DisplayName`) — neither of
  which is a substitute for the deck link.
- **Post ids are timestamp-derived, not content-derived.** `TimestampId::create_id` is
  Crockford-base32 of the 8 big-endian bytes of a microsecond Unix timestamp — always 13 chars —
  and `validate_id` only checks the length, the decode, and that the time is after 2024-10-01 and
  under two hours ahead. The FFI exposes no helper (`create_tag_id` only), so `PostIds` mints them
  in pure Kotlin. That is safe here in a way hand-rolling a tag id would not be: a tag id has to
  match a blake3 derivation byte for byte.

**6. The universal path does not validate or sanitize.** Unlike the pubky.app path it checks
neither the tag id against the body nor the label's casing
(`nexus-watcher/src/events/handlers/universal_tag.rs:62`). `TagRepositoryImpl.sanitizeLabel`
is therefore load-bearing for deck tags — drop it and labels fragment by case.

**7. Before #40, deck tags were written to `/pub/pubky.app/tags/` and silently dropped.** If
old decks are missing from the indexer, that is why, not indexer lag.

**8. Two similar hashes, different encodings.** Tag id =
Crockford-base32(blake3(`"{uri}:{label}"`)[..16]), 26 chars, from the FFI. Nexus
`resource_id` = lowercase-hex(blake3(normalized_uri)[..16]), 32 chars. Easy to confuse.

**9. `/v0/tags/taggers/{label}` is "taggers of a *hot* tag", and is not usable for a directory —
ever.** This entry previously read "only surfaces labels with real traction", which was wrong in
the way that matters: it described a *not yet*, and the answer never arrives (#134).

Without a `user_id`, that endpoint does not touch the graph. It loads the hot-tags Redis cache
(`HOT_TAGS_CACHE_PREFIX`, key `POST_HOT_TAGS/Taggers/{timeframe}`), does a plain
`taggers_hash_map.get(label)`, and returns `Ok(None)` → `[]` on a miss
(`nexus-common/src/models/tag/global.rs:96-135`). The map only ever holds labels that made the hot
list, so the 200 + empty array is indistinguishable from "nobody has used this label". Measured on
prod: the `tagged_type=User` hot list bottoms out at **66 tagged users**, `loopky-user` has 2, and
`bitkit-user` (3) reads empty too — every app-directory label hits this, and the threshold rises as
the network grows. The reach-scoped branch (`?user_id=…&reach=…`) does hit the graph but is
hardcoded to `MATCH (reach)-[tag:TAGGED]->(tagged:Post)`
(`nexus-common/src/db/graph/queries/get.rs:549`), so it cannot see a profile tag at any count.
Both branches are dead ends for a profile-tag directory, for different reasons; filed upstream as
pubky/pubky-nexus#1036. `/v0/stream/users` is no help either — it has no tag filter at all.

**What replaces it.** `/v0/search/users/by_tags?tags=loopky-user` (pubky/pubky-nexus#1030) *is* the
graph query, and returns the whole directory. It is **live on staging and 404s on prod**, which
reports the same version string on both — prod is on commit `e6fc8616` (2026-07-16), staging
`958121ea` (2026-08-22). So `DiscoveryRepository.loopkyUsers` unions three sources and verifies
every candidate with `/v0/user/{id}/taggers/loopky-user`, which has always worked:

1. `TagRepository.usersTagged` → `by_tags`. **Throws on failure** where the other tag reads return
   empty, so the caller can tell "this indexer has no such query" from "no Loopky users" — that
   confusion is the whole bug. Contributes nothing on prod today, and fixes prod without a Loopky
   release the day it redeploys.
2. Deck owners, off the `loopky-deck` resource stream global browse already reads — the author is
   the manifest URI's owner, so this costs no extra parse.
3. Announcement post authors, off `/v0/search/posts/by_tag/loopky-deck` — a `post_key` is
   `{author}:{post_id}`, so the authors fall out of the index with no post fetched.

2 and 3 recovered 2 of 2 real prod accounts against 0 of 2 before. They stay after prod redeploys:
they reach a published author whose profile self-tag never made it, and 1 reaches an account that
has never published — which neither 2 nor 3 can. **Known ceiling:** a user who self-tagged but has
published nothing stays invisible on an indexer without `by_tags`. Nothing client-side reaches
them; that needs the deploy, or Loopky's own index (#58).

Note the label *is* discoverable by prefix search (`/v0/search/tags/by_prefix/loopky` returns it) —
it was never the index that was missing it.

---

### 7.8 Homegate (getting an account in the first place)

A homeserver running `signup_mode = token_required` — which is what Synonym's does — will not
create an account without a **signup token**. Loopky obtains one through **Homegate**
(`data/homegate/HomegateClient`): SMS verification, a small Lightning payment, or a hand-issued
invite code. All three end in a `SignupGrant`.

Three things about this are load-bearing:

**The token and the homeserver are one value.** A token is only redeemable on the homeserver
whose Homegate issued it, and it is **single-use with no expiry** — so spending one on the wrong
server does not fail temporarily, it destroys something the user paid for. `PubkyEnvironment`
pairs the gate URL with its homeserver so the two cannot be configured apart, and `PendingSignup`
carries the homeserver alongside the token so a mid-flow environment change cannot misdirect a
token that already exists. The environment's `defaultHomeserver` is a fallback for the invite-code
path only, which makes no Homegate call; whenever Homegate answers, its `homeserverPubky` wins.

**The token is persisted the moment it exists.** `SignupTokenStore` writes it inside the same
suspend call that received it — before any UI sees it — because by then the user has already spent
an SMS attempt or paid sats. It lives in the **secrets** vault, not the session one: a token is
spent before there is a session and must survive signing out.

**Loopky redeems the token itself, and that is the only path.**
`IdentityRepository.createLocalAccount` mints a key inside the FFI and calls
`signUp(secretKey, homeserver, token)` directly, so the token is redeemed here and Loopky holds the
key (`LocalKeyStore`, §7.5). **This overturns the invariant this section used to state** — "Loopky
never holds a secret key" no longer describes signup at all. It was always a design choice rather
than an FFI limit: `sign_up` has taken a secret key all along.

Signup is therefore one screen deep: the human check (`SignupStartViewModel` + the three method
screens `PhoneVerificationViewModel`, `LightningVerificationViewModel`, `InviteCodeViewModel`) and
one terminal step, `LocalSignupViewModel`. **Nothing has to be installed to get through it**, and
nothing is gated on Pubky Ring.

**Pubky Ring is offered after the account exists, not before it.** #147 introduced the local path
beside a Ring one, chosen by a `TokenRedeemer` nav argument decided at the door the user came
through: Ring minted the key and redeemed the token over the auth relay (`beginSignUp` rewriting
the sign-in deeplink into its signup form via `asSignupUrl`), Loopky did it locally, and
`SignupStartViewModel` gated every method on `PubkyRingPresence` whenever Ring was the spender.
That is gone. It cost a Ring install gate in front of the only way into the app, and — because the
spender was a route argument — an "create an account in Loopky instead" link that navigated to a
byte-identical second copy of the same screen. Ring is now reached from the **backup step**
(`BackupRingViewModel`, straight after the account is created) and the Settings nag, where it is a
*second copy* of a key that already exists rather than a prerequisite to satisfy.

**That offer is a backup, not a custody transfer, and the distinction is load-bearing.**
`ringExportUrl` builds a `pubkyring://` import link and `onExportConfirmed` records
`BackupMethod.PubkyRing`; the key stays in `LocalKeyStore` and custody stays `KeyCustody.Loopky`,
which is what `backup_ring_subtitle` tells the user ("Loopky keeps its own copy, and your recovery
phrase still works"). What it buys over a written phrase is a live copy in an app the user can
sign other Pubky apps in with. Calling it a custody change would describe the Ring-custody option
this section just removed — nothing anywhere in the app moves a key *out* of Loopky. If the Ring
redemption path is ever wanted back, it belongs behind that offer, not in front of Homegate.

`platform/PubkyRingPresence` survives for those callers: a Koin-bound platform interface (like
`BackgroundTasks`) that owns the install URL, since that differs per platform. It probes
`pubkyauth://signin`, which makes it dependent on two pieces of manifest configuration that report
"not installed" on *every* device when missing: the Android `<queries>` entry for the `pubkyauth`
scheme, and `pubkyauth` in the iOS `LSApplicationQueriesSchemes`. **`canImportKey()` is a second,
separate probe** against `pubkyring://` — Ring registers both schemes, a device can answer yes to
one and no to the other, and each needs its own manifest entry. Answering the export screen's
question with the auth probe would report Ring missing on a device that has it.

Two consequences worth knowing before touching this:

- **Retrying is safe and is the intended recovery.** `LocalSignupViewModel.onRetryClick` re-reads
  the *stored* token rather than asking Homegate for another; going back to Homegate would charge
  the user twice for something they already hold.
- **A session coming back does not prove the token was spent on the right key.** The repository
  asserts the pubky that comes back **is the key it registered**, and a mismatch is a failure to
  surface rather than a session to keep. Only then is the token cleared.
- **The key is stored before it is registered.** If `signUp` fails half way, the key is still
  there, so a retry registers the *same* pubky. Minting again on retry is precisely the Pubky Ring
  behaviour (`signupAction.ts` reuses a key only when the signup token matches) that strands a
  user's first identity — and `registerHeldKey` exists so the adopt-a-held-key path never mints at
  all.

## 8. Data model & persistence

### 8.0 Homeserver layout (canonical)

Published decks live under the author's pubky as a manifest, a set of **card chunk** records, and media blobs. The homeserver is the source of truth; an in-memory per-session cache fronts it (see §8.3). There is no persistent cache (§8.1).

Cards are batched rather than stored one record per card, and the manifest carries no card index. See §8.4 for why, and for the numbers that forced it.

**Path layout:**

```
/pub/loopky/decks/{deckId}/manifest.json      — deck metadata + chunk table
/pub/loopky/decks/{deckId}/cards/{n}.json     — up to CHUNK_SIZE cards per record
/pub/loopky/decks/{deckId}/media/{sha256}.{ext}
/pub/loopky/srs/{authorPubky}/{deckId}/{n}.json        — your review state, chunked (see §8.3)
/pub/loopky/subscriptions/{authorPubky}/{deckId}.json  — a deck you follow (see below)
/pub/loopky/settings.json                              — your study settings (see §8.6)
```

- `{deckId}` and `{cardId}` are UUIDv4, generated client-side.
- `{n}` is the chunk ordinal, `0`-based. It starts sequential, but **compaction leaves gaps** (§8.4) — anything walking the table must read `chunks[].n`, never `0 until chunks.size`.
- SRS records live **outside** `/decks/` and are keyed by the deck's author: your review state for someone else's deck was never the owner's data. Author-scoping also stops two authors whose decks share a `deckId` colliding in your `srs/` tree. Chunked for the same reason cards are: one record per card made a studied 20k-card deck ~40,000 records. Written by `PubkyPaths.srsChunk` (#43 §2).
- Subscriptions live on the **follower's** homeserver, author-keyed for the same reason SRS is. A record's *existence* means "I follow this deck"; see the schema below.
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
  "type_enabled": false,
  "front_lang": "en-US",
  "back_lang": "es-ES",
  "card_count": 20000,
  "chunks": [
    { "n": 0, "count": 100, "updated_at": 1739000100000 },
    { "n": 1, "count": 100, "updated_at": 1739000200000 }
  ],
  "source": {
    "kind": "clone",
    "uri": "pubky://pk:other/pub/loopky/decks/uuid/manifest.json",
    "origin_id": null,
    "imported_at": 1739000000000
  }
}
```

- **There is no card index.** Membership is the union of the chunks. The manifest is bounded: a 20k-card deck's manifest is ~3 KB, against ~1.46 MB when it listed every card.
- `card_count` is denormalized so a deck tile renders from the manifest alone — it used to be `cardIndex.size`, which meant downloading 20,000 entries to draw one tile.
- `chunks[].updated_at` is the sync unit. A follower diffs it against their cache and re-fetches only the chunks that moved: one chunk GET when the owner edits one card, rather than re-reading a full index.
- `source` records provenance — one block rather than a field per origin, so clone / import / the AI-OCR-URL sources in spec §14 don't each churn the schema. `kind` is `original` | `clone` | `import`. Absent on decks written before it existed.
- Study order is **not** manifest position; it is the card's own `ord` (see below).
- Manifest `updated_at` bumps on any deck-metadata change or any card add/remove/reorder.
- `media_rehost_cursor` / `media_rehosted` track the deferred media re-host (#53) and are meaningless unless `source.kind` is `clone`. Additive — schema stays `1`, and a manifest written before they existed decodes to `0` / `false`, so an older clone is simply swept once. See §8.0's clone paragraph for why the cursor is load-bearing.
- `listen_enabled` / `speak_enabled` are deck-level study opt-ins (TTS playback of the back / pronunciation practice). Both default `true`; manifests written before these fields existed decode to `true`. Additive — schema stays `1`.
- `type_enabled` is the third study opt-in (#115): the reader types the answer instead of reading it off the flipped card. Additive, schema stays `1`, and it departs from its two neighbours in both directions on purpose. It defaults **`false`**, and a manifest written before the field decodes `false` too — that manifest says nothing about whether its author wanted the mode, and off is the reading that cannot surprise anyone studying it. And it is **not** gated on the language pair below: a string comparison has no engine to substitute a locale into, so requiring a declared pair would withhold the one assisted mode from exactly the decks — every import predating the pair — that have no other. The flip is not blocked either; the card turns as it always has, and the input simply occupies the back where the answer will go. Matching lives in `AnswerMatcher` (`domain/model/`), which typing reads at `AnswerStrictness.Strict` — accents count, since typing them is the point — while `SpeakMatcher` is its `Lenient` view, because a recognizer never had accents to get wrong. Only a **correct** Check reveals the back: a wrong or near-miss answer reports itself and leaves the reader answering with their text still in the field, so one typo does not cost the card, and `TypePhase.Answering` carries the rejected attempt (`TypeMiss`) rather than there being a phase for it. Give up is the escape. Neither the match outcome nor Give up ever chooses an SRS grade.
- **`front_lang` / `back_lang` are what make the two *speech* opt-ins mean anything, and a deck without them offers neither feature** (typing is unaffected — see `type_enabled` above). BCP-47 tags for the language of each card side. The OS speech engines — Android `TextToSpeech` / `SpeechRecognizer`, iOS `AVSpeechSynthesizer` — fall back to the **reader's** device locale when handed no language, so an undeclared Spanish deck is read aloud with English phonetics on an English phone and the spoken reply is transcribed by an English model. That is a wrong answer dressed as a working feature, which is why `Deck.speechReady` gates the buttons rather than any locale being guessed. Absent on manifests written before the pair existed, and additive — schema stays `1` — so those decks simply go quiet until their author sets a pair in the deck editor. Both sides are required, because Listen reads whichever side is facing the user and the two are routinely different languages.
- A media ref (`cover_image_ref`, `image_ref`, `audio_ref`) may instead carry a `"url"` field for a **web image** (e.g. an Unsplash photo). When `url` is set, `path`/`sha256` are empty (`""`) and no blob is stored on the homeserver; the client loads the remote URL directly.
- **A `url` ref does not have to come from Unsplash.** The image sheet's one text field is both the search box and the address box: `ImageLink.parse` (`domain/model/`) decides which, and text carrying an `http(s)` scheme becomes a remote ref exactly like a grid pick. Three things that path gets right and a reimplementation would not:

  1. **Done is gated on the preview loading**, where a grid photo needs only to be highlighted. The preview loads through the same Coil stack that will later draw the card, so a copied *page* address — the usual mistake, since that is what "copy link" gives you on a results page — is refused here instead of being saved as a card face that can never render. A host that refuses to serve the app fails here too, and that is the same answer.
  2. **A Google Images result address is unwrapped.** "Copy image address" on a result often hands over `google.com/imgres?imgurl=<the image>&…`, which is a viewer page. `imgurl` is unwrapped and percent-decoded — except escapes standing for a space or a control character, which stay spelled as escapes, because `%20` decoded is a string that is no longer a URL. Only `imgurl` is unwrapped: a generic `url=` would misread an image proxy's own address.
  3. **A `data:` URI is decoded and uploaded as a blob**, not stored as a ref. Thumbnails on a results page are routinely inline, and a `data:` URI in a manifest is an image nobody else can fetch.

  The **Paste** button beside "From gallery" is how a copied *address* reaches the field. **An image copied as bytes — Chrome's "Copy image" — is identified and refused without the provider being touched**, and that restraint is load-bearing rather than fussy: a clip from another app is a handle to *its* content provider, so `getType`, `openInputStream` and `coerceToText` are each IPC into a process that may do arbitrary work to answer. `coerceToText` is the trap — on a `content:` uri it does not format the uri, it opens and reads the stream — and calling it on a Chrome image clip on the main thread froze the app outright. Only `ClipDescription` and the item's uri are consulted, both local metadata that arrived with the clip; the read is off the main thread and time-bounded regardless, because `primaryClip` is itself a binder call. Taking those bytes is issue #168, and whatever does it must keep every provider call off the main thread and must not fall back to reading the clip as text.

  The clipboard is read only on that button press, never speculatively — from Android 12 it raises the system's "pasted from clipboard" toast, which is the right disclosure for a press and wrong for anything else.

  The Unsplash credit line is hidden while a link preview is up: crediting a photographer under someone's pasted address attributes an image that is not theirs.
- **Unsplash licensing.** Their API guidelines are licensing terms, and breaching them costs API access. Loading the remote `url` rather than re-hosting the bytes satisfies the hotlinking rule; the image picker credits the photographer on every grid cell and links both them and unsplash.com with `?utm_source=loopky&utm_medium=referral`; and `UnsplashClient.trackDownload` pings `links.download_location` when a pick is committed. **Known gap:** a media ref carries no attribution fields, so a published deck cover or card face displays its Unsplash photo uncredited. Closing that means adding `author_name` / `author_url` to `MediaRefDto` (additive, schema stays `1` — `ignoreUnknownKeys` keeps older manifests readable) and rendering a credit wherever a remote image is shown.
- **The Unsplash access key is the user's, not the build's.** `UnsplashKeyStore` (`data/storage/`, KVault → Android Keystore / iOS Keychain, under its own `loopky.secrets` service so signing out cannot clear it) holds a key the user enters in Settings; `BuildConfig.UNSPLASH_ACCESS_KEY_OBF` — XOR+Base64 obfuscated, unwrapped by `data/unsplash/UnsplashKeyObfuscation.kt`, which raises the cost of scraping it from the APK without pretending to be a secret — is only a fallback behind it. `UnsplashClient` resolves the key per call rather than capturing it at construction, and `isConfigured` is a `Flow` so a key saved in Settings reaches an image sheet that is already open.

  **Neither key is ever displayed.** The built-in one is only admitted to exist ("Using Loopky's shared key"); a user's own comes back as `maskedKeySuffix` — four characters, never more. The draft key is a parameter to `SettingsViewModel.onSaveUnsplashKey`, deliberately *not* a `SettingsUiState` field, so it cannot survive in a `StateFlow` or a state dump. The key stays in the `Authorization` header and must never move to a `client_id=` query param: URLs land in `HttpError` messages and logcat. Settings calls `SecureScreen()`, which sets `FLAG_SECURE` on release builds only.

  Failures are typed as `UnsplashError` — `MissingKey`, `InvalidKey` (401), `RateLimited` (403), `Unavailable` — rather than a message string, because the first three are fixed by changing the key and the image sheet offers a route to the field for exactly those. `UnsplashException`'s message is the error name and nothing else; it used to be the full request URL, rendered verbatim to the user.

**`cards/{n}.json`:**

```json
{
  "schema_version": 1,
  "deck_id": "uuid",
  "chunk": 0,
  "cards": [ /* CardDto, up to CHUNK_SIZE of them */ ]
}
```

Each entry in `cards[]`:

```json
{
  "schema_version": 1,
  "id": "uuid-1",
  "deck_id": "uuid",
  "updated_at": 1739000100000,
  "ord": 3000,
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

- `ord` is the study order, sparse with a stride of 1000 (`ORD_STRIDE`). A card inserted between two neighbours takes the midpoint, so an insert rewrites one chunk instead of renumbering every following card — which, chunked, would mean rewriting every chunk in the deck. `ordBetween` returns null when two neighbours are adjacent and the caller must renumber.
- **Chunk assignment is sequential-append**: a new card goes to the last chunk with room, tracked by `count`. Hash-partitioning on card id would be stable under every mutation but would produce CHUNK_SIZE-many near-empty records for a 50-card deck. A delete leaves the chunk short; **holes are tolerated deliberately** — closing one would mean rewriting every following chunk. They are folded away off the critical path by `compactDeck` (§8.4).
- `CHUNK_SIZE` (100) is the single knob for the storage/write trade-off, and readers derive the chunk count from the manifest rather than assuming it, so it can be retuned with no migration. It is deliberately conservative: **the homeserver's per-record ceiling is undocumented** and has not been measured.
- A side must have at least one populated field; enforced in `DeckRepository.publish()`.
- **Cards carry no tags.** Tags are deck-level and live in `manifest.json`; there is no per-card tag field and no plan for one in v1 (spec §8).
- Media refs are relative to the deck path and resolved against `/pub/loopky/decks/{deckId}/`.

**`subscriptions/{authorPubky}/{deckId}.json`** — following someone else's deck (#33):

```json
{
  "schema_version": 1,
  "deck_uri": "pubky://pk:other/pub/loopky/decks/uuid/manifest.json",
  "author_pubky": "pk:other",
  "deck_id": "uuid",
  "followed_at": 1739000000000,
  "last_seen_updated_at": 1739000500000
}
```

- The relationship is carried by the record's **existence**, like a pubky.app follow. It lives on the *follower's* homeserver, so listing your subscriptions is one `list()` on `subscriptions/`.
- Loopky's own namespace, not pubky.app's: a follow there means "I follow this *user*", and there is no ecosystem primitive for following a single deck.
- `author_pubky` is the load-bearing field. `DeckRepository.sync` reads the manifest from the **owner's** homeserver, which it cannot locate from a deck id alone — this is what makes a followed deck syncable.
- `last_seen_updated_at` is the manifest `updated_at` at the last open, so the library can tell "the author published changes" from "you have already seen them". `0` until first open.
- **Keeping a deck is what earns review state.** `SrsRepository.review` rejects a deck you neither own nor follow, and deck detail offers Follow / Clone in place of Study for one you are only browsing. Otherwise grading from Discover would write SRS records under a deck absent from both the library and the due queue — progress the user can neither see nor resume.
- Following writes `loopky-followed` on the deck's manifest, so "N people follow this" falls out of the indexer's tagger count (§7.7). Unfollowing removes the record and that label — **and nothing else**: review state is yours, not the author's, and re-following must not reset your progress (§8.3).
- **Cloning is the other half of #33 and stores nothing here.** A clone is a full copy under your own pubky with a new `deck_id`, new card ids, and `source.kind = "clone"`; it never receives the original's updates. New card ids are what keep SRS state from bleeding between an original and its copy. Card media is copied **by reference** — each ref keeps the source author's blob in `uri` rather than re-uploading it, so cloning an Anki-sized deck costs card records rather than hundreds of MB. `MediaRepository.rehost` copies a blob under the clone's own path; because refs are content-addressed the digest is unchanged, so the swap is invisible. **Re-hosting happens on first fetch (#65):** `MediaRepository.get` emits a `PinnedBlob` once it has served a still-pinned ref, and `DeckRepository.rehostBlob` copies the blob and rewrites every ref carrying that sha — card sides through the chunk+manifest pair, the cover through the manifest. Three things that path has to get right: it is **ownership-guarded** at both the signal and the write, so a *followed* deck's blobs are never copied under your pubky at a deckId you cannot edit; it is **cache-only**, because there is no sha→card index and locating the card otherwise would cost ~200 chunk reads on a 20k-card deck; and it writes with `touchDeck = false`, since re-hosting changes no content and bumping `updated_at` would tell every follower the author published changes. A failed copy leaves the ref dangling rather than clearing it — a 404 today may be an outage tomorrow — and is not retried for the rest of the session. **Blobs the user never looks at are handled by the deferred sweep (#53):** `DeckRepository.rehostPendingMedia` walks the deck's chunks from a persisted cursor, copying every ref still pinned, at most `DEFAULT_REHOST_CHUNK_BUDGET` chunks per call. Two additive manifest fields carry the progress — `media_rehost_cursor` and `media_rehosted` — because re-host progress is *deck* state, not device state: it should survive a reinstall and hold across the user's devices. **The cursor is what makes the pass resumable**, and the reason is not obvious: a re-hosted ref loses its `uri`, but a chunk with nothing pinned in it is never rewritten, so without a cursor a budgeted run restarts at chunk 0 and a deck with more chunks than the budget never reaches its tail. The budget is counted in **chunks, not blobs** — a 200-chunk deck with media in ten of them still costs 200 reads to find them. The chunk record is the durable unit, written as each chunk is processed; the manifest is patched every `REHOST_MANIFEST_BATCH` chunks and once at the end. A **deleted origin** (`isNotFound()`) counts as *missing* and does not block completion — treating it as a failure would re-sweep the deck forever, on every device, for a blob that is not coming back — while a transient failure keeps it pending. Scheduling goes through the `BackgroundTasks` seam (§9.6), triggered after a clone and from `listOwned()` as a self-heal.

**Sync algorithm (client side):**

On deck open:
1. `GET manifest.json`.
2. Diff `chunks[]` against the local cache by `(n, updated_at)`.
3. For each chunk whose remote `updated_at` is newer: `GET cards/{n}.json`.
4. Rebuild the deck's cache entry from what was read. Deletions need no separate step — a card the author removed is simply absent from every chunk. *(The old step 4 diffed a card index and issued homeserver DELETEs for the difference, so a stale local cache could delete a live card.)*
5. Media is **not** prefetched. Blobs are fetched lazily when a card is displayed; bulk-prefetching every referenced blob is untenable for an Anki-sized deck with hundreds of MB of audio.

On single-card edit:
1. Rewrite the one chunk holding the card.
2. Patch that chunk's `updated_at` and `count` in the manifest, and `card_count`.
3. PUT manifest.

Both writes are owned by `DeckRepository.upsertCard` / `deleteCard` rather than split across repositories — splitting them is what previously let a card record and the manifest drift apart permanently. The chunk is written before the manifest: a chunk the manifest does not yet describe is invisible, whereas a manifest pointing at a chunk that was never written is a broken deck.

Locating the chunk that holds a card uses the mapping `CardRepository` records when it reads or writes a chunk. Falling back to scanning every chunk would cost 200 requests on a 20k-card deck — the very cost chunking exists to remove.

No cross-record transactions. A momentarily stale manifest vs a newer chunk is tolerated — the next sync reconciles. Last-write-wins; no tombstones, no conflict resolution in v1.

### 8.1 No local database

v1 has **no SQLDelight dependency and no local relational store**. Repositories cache in memory for
the session and re-fetch from Pubky; the homeserver is the source of truth (§8.3). This doc once
carried a full candidate schema for one — it described nothing that was ever built, so it is gone.

The question is not closed, just unforced. What would force it is cross-device SRS (§12 #1, #3): a
local DB as the SRS source of truth is the endgame, and it would reverse "Pubky is the source of
truth" for that one slice.

### 8.2 Preferences & secrets

multiplatform-settings is **not** wired, and no longer needs to be. Secrets — the signed-in
`Session` — persist only through `SecureSessionStore` (Liftric KVault → Android Keystore-backed
EncryptedSharedPreferences / iOS Keychain; see §7.5). Non-secret device-local state goes through
`AppPreferences` (SharedPreferences / NSUserDefaults) for flat values and through the small
structured stores beside it — `PendingReviewStore` for the unflushed-review journal,
`StudyProgressStore` for today's tally. Never a secret through either.

A preference that has to reach a second device does **not** belong in any of them; see
`/pub/loopky/settings.json` in §7.5 and §8.6.

### 8.3 Source of truth

- **Published decks:** Pubky homeserver is canonical. An in-memory per-session cache holds the last
  fetched copy; nothing is persisted to disk in v1.
- **Study progress (SRS):** Pubky-backed and canonical, under `/pub/loopky/srs/{authorPubky}/{deckId}/` — **on your own homeserver, for any deck, including decks you do not own**. An in-memory session cache fronts it. *(This row previously read "in-memory in v1; not synced to Pubky", which had been untrue since `SrsStateDto` landed.)*
- **Decks you follow but do not own:** the owner's homeserver is canonical for the deck content (manifest, chunks, media) — you cannot write it, and every `DeckRepository` write is ownership-checked. Your own homeserver is canonical for your review state over it, and for the subscription record itself (`/pub/loopky/subscriptions/{authorPubky}/{deckId}.json`, §8.0). Unfollowing removes the subscription, not the SRS state.
- **Import drafts:** in-memory only — each paste is a fresh canvas (spec §4 story 5).
- **Private decks:** out of scope for v1 (spec §11). They would need a local-only write path and a
  persistent store to hold decks that have no homeserver record (§8.1).

---

### 8.4 Deck-scale limits

Sizing targets are **Anki-proportional**: real decks (Core 2k/6k, kanji decks, Ultimate Geography) run 2k–50k cards with hundreds of MB of media.

What the chunked layout buys at 20k cards, against one-record-per-card:

| | Per-card records | Chunked (CHUNK_SIZE 100) |
|---|---|---|
| `manifest.json` | ~1.46 MB | ~3 KB |
| Publish requests | 20,001 | ~201 |
| Deck open requests | 20,000 | ~200 |
| One card edit uploads | ~1.46 MB | ~63 KB |
| Deck grid, 5 decks | ~7.3 MB | ~15 KB |

The trade-off is deliberate: editing one card now rewrites a ~63 KB chunk instead of a ~200 byte record. Publish-once / open-often / edit-rarely makes that overwhelmingly the right side.

**Measured:**

- **The homeserver rate-limits concurrent writes.** Publishing a 1,200-card deck with 8 requests in flight fails partway with `429 Too Many Requests`. `MAX_IN_FLIGHT` is therefore **4**, and the session-authenticated write helpers retry a 429 with exponential backoff (bounded, so a genuinely unavailable homeserver still fails rather than hanging). A 429 is transient and the request well-formed, so it must never reach the user. With both in place, the same import publishes cleanly in well under a minute.
- **An interrupted publish is recoverable.** The failed run above left the deck listed and openable, reading "900 due · 1200 cards" — the marker manifest doing its job. Re-running the publish repairs it, since chunk PUTs are idempotent overwrites.

**Still unmeasured and load-bearing:**

1. **Homeserver per-record maximum.** Not documented in this repo or the FFI. This is what should really set `CHUNK_SIZE`; 100 is a conservative guess. If a chunk write is ever rejected for size, that constant is the single knob.
2. **Whether the FFI itself is concurrency-safe**, as distinct from the server's rate limit — is there connection pooling, and is parallel use of a single client sound? Bounded concurrency works in practice; the guarantee is unstated.
3. ~~**`list()` behaviour on large directories.**~~ **Resolved (2026-08-22).** The homeserver's `DEFAULT_LIST_LIMIT` is **100** records and `DEFAULT_MAX_LIST_LIMIT` **1000** (`pubky-homeserver/src/constants.rs`), so an unpaged `list()` silently returns only the first hundred — which lost whole decks out of a library, since the listing is flat and one deck is a manifest plus a chunk record per 100 cards plus every blob. All call sites now page. `shallow` is also **confirmed honoured** by the deployed homeserver: a five-deck library answered `entries=7 decks=7` in a single request, one entry per deck directory with its trailing slash. Paging is kept underneath it anyway, so a homeserver that ignores the flag still yields a complete listing.
4. **Real latency at 20k cards.** ~200 chunk GETs on deck open is untested at that size; 1,200 cards (12 chunks) is comfortable.

**The deck editor is paged** (#52). It reads one chunk record at a time as the list scrolls, and shows the manifest's `card_count` in the header rather than the length of what it happens to have read. Two rules follow, and both are load-bearing:

- **Saving an existing deck writes only the manifest.** The card list is a window, so rebuilding the deck's cards from it would delete everything not paged in. Renaming a 20k-card deck is one write, not ~201.
- **Card changes are written when they happen**, not on Save. Adding a card hands over to the card editor (`upsertCard`); moving one goes through `DeckRepository.moveCard`.

`moveCard` is what makes reordering affordable at that size. Order lives on `Card.ord`, and `chunk n` owns exactly `[n · CHUNK_SIZE · ORD_STRIDE, (n+1) · CHUNK_SIZE · ORD_STRIDE)` — a private slice of the ord line — so the landing chunk is renumbered inside its own range and no other chunk moves. A move is one chunk write plus the manifest, or two when it crosses a boundary; the **landing** chunk is written first, so a failure between the two leaves the card in both records rather than in neither. Destination positions come from `CardChunking.positionAt`, arithmetic over the manifest's chunk counts, so locating position 12,345 costs no reads.

Drag-to-reorder is therefore offered only while the whole deck fits in one page (`DRAG_REORDER_LIMIT`, 100). Above that the row's position number opens "move to position…", which can target a position the list has never loaded.

**Deletes leave holes, and a background pass folds them away (#51).** A card is assigned to a chunk by sequential append, and deleting one shrinks that chunk's `count` rather than resequencing every card after it — closing the hole in place would rewrite every following chunk, which is the write amplification the layout exists to remove. Holes never cost correctness (`card_count` is summed from the per-chunk counts, membership is the union of the chunks); they cost density, so a deck that imported 20k and deleted 15k opens with the request count of a 20k-card deck.

`DeckRepository.compactDeck` reclaims that, off the critical path:

- **One pair at a time.** `CardChunking.mergeTarget` finds the first two *neighbours in the sorted chunk table* whose cards fit in one record; the pair is folded into the lower-numbered one, renumbered inside its own slice of the ord line, and the other is dropped. Order survives because nothing sorts between them, and no chunk outside the pair moves.
- **It converges.** Every merge strictly shrinks the table, and the pass stops when no two neighbours fit — which is the record count the cards actually need. Merging up to a full `CHUNK_SIZE` is what stops it thrashing: a chunk left at 99 can only pair with an almost-empty neighbour, so a delete/add cycle does not re-trigger it.
- **Landing record, manifest, then the source record.** Every other chunk write here pairs "chunk, then manifest", but a merge *removes* a record: emptying it before the manifest drops its entry would leave a window where the manifest points at a chunk that 404s. This order only ever over-counts, and a card in both records reads as one because membership is keyed by id. A failure on the last step orphans a record the manifest cannot reach — swept by the next full publish or by `delete`.
- **Never on the delete path.** `deleteCard` and `listOwned` only *ask* for a pass, through `BackgroundTasks.scheduleDeckCompaction` (§9.6). Compacting inline would cost a merge per card in a bulk delete and rewrite records followers have cached, in the middle of the user's edit. `updated_at` is not bumped and no `changes` is emitted — nothing user-visible changed — but the per-chunk stamps are, which is what makes a follower re-fetch the folded pair.
- **Budgeted at `DEFAULT_COMPACTION_MERGE_BUDGET` merges per call**, and resumable with no cursor: each pass re-derives the next merge from the manifest, so an interrupted run simply picks up where the table now is.

A merge leaves a gap in the chunk numbering. Nothing downstream assumes the table is contiguous — `positionAt`, `appendTarget` and study order all read it in `n` order — but a full republish has to delete stale records **by number**, not by counting the previous chunks, or the high-numbered survivor is orphaned.

**Known gap:** a deck published before the chunk table existed has no page boundaries to walk, so the editor still reads it whole (small by construction — the layout landed before any Anki-sized import could).

### 8.5 Storage quota (507)

The homeserver enforces a **per-user storage quota** and answers **507 Insufficient Storage** on any write that would exceed it. The limit is set by the signup token at redemption, so it is a property of *how the account was approved*, falling back to the server's default; the free tier is advertised as **1 GB**. Every file also costs **256 bytes of metadata** on top of its content, which Loopky pays a lot of, since it writes many small records.

The body is plain text — `"Disk space quota exceeded"` — and the server builds a 507 in **two** places (a pre-flight check against `used_bytes`, and the storage layer's own quota error), so the wording reaching the FFI is not one fixed string. `isQuotaExceeded` therefore matches three independent substrings plus the status code, and the status code is matched as a **token**, not as a substring: every failure message carries a `pubky://` URL and ids are random alphanumerics, so a bare `"507" in msg` would classify an unrelated error as a full disk.

**507 is terminal, and that is what makes it different from everything else here.** A 429 and a transport error are transient and worth retrying; this one succeeds only after the user deletes something. Four consequences, all load-bearing:

- **`withWriteRetry` returns on it** before the rate-limit branch can claim it, and `toErrorReason` classifies it ahead of `isRateLimited`/`isNetworkFailure`. Nothing they match collides with the 507 body today, but "quota" is the word a future *bandwidth* limit will also reach for.
- **The background workers stop rather than back off.** `MediaRehostWorker` and `DeckCompactionWorker` return `Result.failure()`, not `Result.retry()` — WorkManager's exponential backoff against a permanent condition never converges. `DeckMediaSweeper` also ends the pass on the first 507 instead of trying every remaining blob, banking its progress first.
- **Re-hosting is itself a quota consumer.** Cloning a media-heavy deck pins its blobs to the source author and the sweep later copies each one under your pubky, so the mechanism that fills the quota is the one that then retries against it.
- **Compaction cannot dig you out.** A merge writes the landing chunk *before* emptying the source (above), so it temporarily *grows* usage — at a full quota the one job that would reclaim space is the one that cannot run.

**There is no client-facing way to read usage or quota.** The tenant router exposes only `GET /session` and `GET|PUT|DELETE /{*path}`; `storage_used` and `storage_quota` live behind the password-gated admin API. So Loopky cannot render a storage meter or warn before the wall — 507 handling is necessarily *reactive* until upstream exposes an unprivileged `used_bytes`/`quota_bytes`, which is what would turn this from an error state into a progress bar. Worth asking for.

**Still unmeasured:**

1. **How close 1 GB actually is.** Images are compressed (1024px max, JPEG q80 — call it 100–200 KB each), but **audio is not compressed at all**: `putAudio` writes raw bytes and `MediaProcessor` only handles images, so an Anki deck's native audio goes up untouched. Dedupe is **per-deck**, not per-user — blobs live under `decks/{deckId}/media/`, so the same asset in two decks is stored twice and a clone re-hosts its own copy. `MediaRepositoryImpl`'s own header already says an Anki deck with audio runs to hundreds of MB, which is the same order as the entire quota. The number wants a real probe: publish a realistic Anki import with audio and images, then read `storage_used` off the admin API against a test homeserver. Until then no copy should promise a deck count.
2. **Bandwidth quota is separate from storage quota**, enforced from the same signup token (`rate_read`/`rate_write`; the free tier's 1 MB/s is presumably it). Its rejection status is untraced, and a large publish could plausibly trip it — a *different* error wanting different handling.
3. **Tenant routes cap request bodies at 100 MB**, which is a real upper bound on a single media blob and belongs alongside the `CHUNK_SIZE` question above.

### 8.6 Study settings, and what counts as "due"

Two decisions from #101 that anything touching the scheduler has to know.

**A never-seen card is not overdue.** `SrsState?.isDue` used to return `true` for a card with no
state, so every counter in the app read a freshly imported deck as entirely overdue: a 1669-card
Anki import opened on `Start studying · 1669 due`. `isNew()` is now the separate question, and
counts come back as a `DeckCounts(due, new)` pair. Consequences worth keeping straight:

- The queue holds both and is **uncapped**. It serves due reviews first (soonest first), then
  never-seen cards in study order. Nothing in the queue-building path may consult the daily goal.
- "All caught up" requires `due == 0 && new == 0`. Due alone would congratulate someone who has
  never opened the deck.
- Home headlines `due + min(new, goal − newCardsToday)` — the day's intent, not the backlog. That is
  presentation only; the queue behind it is still everything.

**Grading is fixed-interval, not SM-2.** A grade schedules the card for `now + the days configured
for that grade` — Hard, Good and Easy each mean exactly one number, on the first review and on the
fiftieth. Only `Again` is fixed in code (`<10m`, a same-session retry rather than a date).

This replaced compounding growth (`interval × ease`), which made the settings screen a liar from the
second review onwards: a 3-day Good setting produced 8 days on review two and 20 on review three,
while the button said 3. `SrsState.easeFactor` and `repetitions` are still tracked — they record how
a card has gone, and growth cannot return without them — but nothing reads them for an interval.
The trade is deliberate and worth stating: intervals never lengthen as a card is learned, so a
well-known card keeps coming back on its Easy interval rather than drifting out to months.

Changing a setting is **not retroactive**. An already-scheduled card keeps the `dueAt` it was given
and picks the new number up at its next grade; nothing rewrites stored state.

**Study settings are a synced record, not a preference.** `/pub/loopky/settings.json` holds the
new-cards-per-day goal (default 20) and the Hard/Good/Easy intervals (1/3/7). They are on the
homeserver because they decide `dueAt` values, and review state already syncs. The record's keys are
still spelled `first_hard_days` / `first_good_days` / `first_easy_days` — a historical spelling from
when they were first-review-only, kept because renaming them would silently reset every existing
record to the defaults on read. `SettingsRepository` guards three things:

- `studySettings` is a `StateFlow` carrying a `SettingsOrigin` — `Defaults`, `Cached` (the
  `AppPreferences` mirror) or `Remote`. `update()` **refuses unless the origin is `Remote`**, at the
  repository rather than only in the UI. The failure that gate prevents is silent: a read fails, the
  flow holds defaults, one tap in Settings overwrites the user's real intervals.
- A **404 is a successful read** — every new account has no record. Treating absent as unread would
  leave Settings permanently disabled on first run.
- The record is written **whole**, and `update()` patches its JSON rather than re-encoding a decoded
  DTO, so a section added by a newer client is not dropped by `ignoreUnknownKeys` on the way in and
  gone for good on the way out.

Reviews are **buffered in memory and flushed per affected chunk**, on `SrsRepository`'s own
app-scoped coroutine scope rather than the ViewModel's: `viewModelScope` is cancelled in
`onCleared()`, so a flush kicked off as the study screen goes away would be killed before it
finished. It also flushes every N reviews, so a crash costs a few cards rather than a session.

`SrsRepositoryImpl.review()` calls `ensureLoaded()` itself rather than trusting that a queue was
built first. It is single-flight, so after the first load it costs a field read, and it turns an
ordering that merely happens to hold into an invariant — a grade scheduled from stale defaults would
not be noticed by anyone.

**The goal is a goal.** Reaching it raises a dismissible banner and nothing else; no method
withholds cards. Copy that calls it a limit describes a feature Loopky does not have.

**Mastered % is partial credit against a moving line.** Each card contributes
`min(intervalDays / threshold, 1)`, where the threshold is `StudySettings.maturityThresholdDays` =
the **longest of the three configured intervals**.

The threshold has to be reachable, and under fixed intervals that is the whole constraint: no card
can ever sit further out than the largest setting, so the old formula (`max(21, longest + 1)`)
would pin `masteryShare` below 100% and make `isFullyMastered` return false forever, on every deck,
with nothing reporting it — at 1/2/7 it asked for 21 days no card could reach. What the number now
means in practice is "this card is as far out as this scheduler can put it", i.e. normally that you
last graded it Easy; `Again` zeroes `intervalDays`, so a lapse drops it back to nothing, which is
what keeps it honest. "Fully mastered" is still the exact every-card-is-mature test, never
`share == 1f`: summing thirds lands on 0.99999994.

### 8.7 Studying a deck both ways

`Deck.reverseEnabled` (#46 §2). A fourth study opt-in beside Listen, Speak and Type, off by
default, ungated by the language pair — swapping two sides has no engine to substitute a locale
into. What it changes is the *session*, and the constraint that shapes all of it is below.

**One card, one review state, two presentations.** A reverse is never a second card record.
Importing one would double a deck's storage and chunk count for content already present and leave
two records that drift apart the moment either is edited. So the card list is unchanged and
`StudySessionViewModel` expands it into `StudyPresentation`s, each naming a card and which way
round it is asked.

**There is no reverse due date, and there cannot be a cheap one.** Review state is keyed by
`card_id` alone inside the `srs/{author}/{deckId}/{n}.json` chunks (§8.0), so scheduling the two
directions apart would mean a direction-aware key across `SrsStateDto`, `PendingReview` **and**
`SrsRepositoryImpl.StateKey`. A stored "show the reverse in a minute" is also wrong on its own
terms: close the app before it comes round and it is overdue forever. "Shortly after" is therefore
a position in the queue — roughly five presentations behind the card — and nothing about the
pairing is persisted.

**The pair is graded once, from its weaker direction.** Both directions share the one state, so
something has to decide what the pair is worth, and "a card is only as learned as its weaker
direction" is the same rule an Anki import takes for a note with two scheduled rows. Mechanically:

- the forward half is written as it happens, so a session abandoned before the reverse keeps it —
  this is why the grade is not deferred;
- a reverse graded **worse** re-schedules the card through `SrsRepository.reviewFrom`, from the
  state the pair *started* at. Applying it on top of the forward result would compound two reviews
  out of one;
- a reverse graded equal or better writes nothing; the forward result already stands;
- `reviewFrom` deliberately skips `recordStudied`. The pair is one review of one card, and counting
  it twice would double the day's tally and mis-classify the card as new a second time.

`previewIntervals` takes a matching `cap`, so on the reverse half the four buttons say what they
will actually schedule. Two of them reading the same interval is the honest outcome; a label
promising more than its button delivers is not.

**Everything that asks "which side is this?" has to ask the presentation.** `promptSide` /
`answerSide` on the ViewModel exist for that, and the language tags swap with them. Reading
`card.front` / `card.back` directly is how a reversed card gets typed-checked against the side it
just showed you, or read aloud in the wrong language — the exact failure `speechReady` exists to
prevent, arriving by another route.

**The gap shrinks to the daily goal, and that is not the capping §8.6 forbids.** At a
`newCardsPerDayGoal` of 2, a five-card gap puts every reverse past the point the reader stops, so
they drill one direction only. `reverseGapFor` clamps it. The goal decides **placement**; the queue
is still every card, uncapped, and nothing is withheld.

**Cards that cannot be asked backwards are skipped silently** (`Card.isReversible` — both sides
must carry something), the same fallback typing takes for a back no answer could match. Session
length therefore is not always double, and `position` stays unique per presentation, which the
study screen's advance transition keys on.

An `.apkg` whose dominant note type has two or more templates arrives with the opt-in on — a
suggestion at the commit screen, like the title and tags, not a schema change. See §7.4 for how
the collection is read.

## 9. Cross-cutting concerns

### 9.1 Dependency injection

Koin, single graph shared across platforms.

```
shared/commonMain:
  dataModule          ← repositories (own business logic)
  presentationModule  ← ViewModels
  platformModule      ← expect fun platformModule(): Module

shared/androidMain / shared/iosMain:
  actual platformModule() {
    PubkyClient, HttpFetcher, NexusClient, HomegateClient, UnsplashClient,
    SecureSessionStore, AppPreferences, SignupTokenStore, UnsplashKeyStore,
    PendingReviewStore, StudyProgressStore,
    Speaker, SpeechRecognizer, MediaProcessor, BackgroundTasks, PubkyRingPresence,
  }
```

ViewModels are bound with Koin's `viewModel { }` DSL (`org.koin.core.module.dsl.viewModel`, from
`koin-core-viewmodel`) in `SharedModule.kt`; repositories stay `single { }`. Android resolves VMs in
composables via `koinViewModel()` (`koin-compose-viewmodel`), which scopes them to the nav/backstack
lifecycle. Android starts Koin in `LoopkyApp.onCreate` — the `Application`, so a WorkManager-started
process has the graph too (§9.6); iOS starts it from `iOSApp.swift` and hands VMs to SwiftUI views
via initializers.

### 9.2 Async

Kotlin Coroutines + Flow everywhere. ViewModels launch in `viewModelScope`; all public repository
methods are `suspend` or return `Flow`. The Swift↔Flow bridge **is wired**, hand-rolled rather than
SKIE: `IosFlowWatcher` (`shared/iosMain/util/`) exposes a `Flow` as a callback Swift can subscribe
to, and `FlowObserver` / `FlowEffectSink` (`iosApp/DI/`) wrap it as an `ObservableObject`. Generics
erase across the ObjC bridge, so values arrive as `Any` and are cast to the concrete `UiState` /
`Effect` type the framework exports.

**Not SKIE**, and not pending it: Kotlin 2.3.x predates SKIE support, so the bridge was hand-rolled
instead. Worth revisiting only if SKIE catches up *and* the erased-generics casting becomes a
burden.

### 9.3 Error handling

```kotlin
sealed class AppError {
    data object Network : AppError()
    data object Unauthorized : AppError()
    data class Parse(val reason: String) : AppError()
    data class Pubky(val code: String, val message: String) : AppError()
    data class Unknown(val cause: Throwable) : AppError()
}
```

Repository methods return plain Kotlin **`Result<T>`** — no Arrow, no custom either type. Classifying
*why* a failure happened (transient vs. terminal, and which message to show) is `ErrorReason`
(`domain/model/`), which is what `isQuotaExceeded` / `isRateLimited` / `isNetworkFailure` feed; §8.5
turns on getting that ordering right. ViewModels map the reason to banners, toasts and snackbars.

Any `runCatching` over **suspending** code must be `runSuspendCatching` (`util/Coroutines.kt`) —
plain `runCatching` catches `Throwable` and so swallows `CancellationException`, turning "the caller
went away" into an ordinary failure.

### 9.4 Accessibility

Shared VMs expose semantic labels (e.g. `"Card 1 of 3 preview, front: hola"`) as strings on the state. Platform UIs wire them into VoiceOver / TalkBack. Reduce-motion and dynamic-type handling live in the platform UI (spec §12).

### 9.5 Logging

`util/Log.kt` is an `expect object Log` with `d`/`w`/`e` and a `debugEnabled` switch; the actuals go to Logcat on Android and `println` on iOS — **not** `NSLog`, whose `%@` formatting segfaults on Kotlin/Native strings. Telemetry is out of scope.

### 9.6 Background work

`platform/BackgroundTasks` is the seam for work that must outlive the foreground. A plain interface
bound per platform via Koin, **not** `expect`/`actual` — the repo has zero `expect class` /
`expect interface`, and both implementations wrap a stateful platform scheduler rather than a
top-level function. The job identifier is a shared `internal const` so the two sides cannot drift.

Two jobs use it: the media re-host sweep (#53) and chunk compaction (#51, §8.4). Deliberately separate rather than two stages of one job — they qualify different decks (clones vs. heavily edited decks), and folding them together would let a media-heavy sweep that keeps hitting its budget starve compaction indefinitely.

| | Android | iOS |
|---|---|---|
| Mechanism | `WorkManager` unique `OneTimeWorkRequest` (`KEEP`) | `BGProcessingTaskRequest` via `BGTaskScheduler` |
| Constraints | `UNMETERED` + battery-not-low | `requiresNetworkConnectivity` |
| Retry | `Result.retry()`, exponential backoff | re-submits itself on completion *and* expiry |
| Registration | none — `work-runtime` merges its own `InitializationProvider` | `register()` from `doInitKoin`, before launch completes |
| Manifest | none | `Info.plist`: `BGTaskSchedulerPermittedIdentifiers` + `UIBackgroundModes: [processing]` |

Two things that are easy to get wrong:

- **A WorkManager-started process has Koin but no session.** `LoopkyApp.onCreate` runs, so the graph
  and `RustlsInit` are up, but `loadPersistedSession()` is only ever called from ViewModels. A worker
  must call it first or every write fails on "Not signed in", silently and forever.
- **Do not add `Configuration.Provider`** without also removing `WorkManagerInitializer` from the
  merged manifest — that initialises WorkManager twice. The default `WorkerFactory` is enough
  because workers resolve from Koin rather than through their constructors.

The iOS implementation is **written but unverified** — the iOS app has never been driven against a
real homeserver.

---

## 10. Testing strategy

**`commonTest` is the whole automated suite** — 73 files, ~680 tests across targets, run with
`./gradlew :shared:allTests`. There is no Compose UI test tier, no iOS test target, and no
integration smoke target; earlier drafts of this doc listed all three as though they existed.

- `ImportRepository.parse()` — one test per rule in spec §6, plus every edge case in spec §9. The
  spec is written to be usable as the test matrix; keep it that way.
- Repositories against a `FakePubkyClient` (`commonTest/.../testing/`). Nothing to fake below it —
  the cache is in-memory.
- ViewModels over `FakeRepositories`, asserting state sequences. `kotlin-test` +
  `kotlinx-coroutines-test` only — **Turbine is not a dependency**. Drive `viewModelScope` with
  `Dispatchers.setMain(testDispatcher)` rather than injecting a scope.
- Scheduler and parser units (`SrsScheduler`, `CardChunking`, `SpeakMatcher`, `LanguageTags`) are
  pure functions and tested directly.

**End-to-end is manual, and scripted.** `journeys/*.xml` holds 19 numbered journeys — onboarding and
Ring auth, paste import, the study loop, discovery, deck management, offline errors, signup — driven
on a device or emulator with `android-cli` (`android run`, `adb shell input tap`, `android layout` to
assert on text). Results and their dates are recorded in `journeys/RESULTS.md`, including the
failures and what caused them. This is the tier that catches what unit tests cannot: a green
`assembleDebug` says nothing about what the screen renders.

Two things the journeys have repeatedly caught and unit tests did not: Ring sign-in flakiness on the
emulator, and SRS flush failures that only appear when the network goes away mid-session.

---

## 11. Build & tooling

- **Gradle** with version catalog (`gradle/libs.versions.toml`).
- **Plugins (actual):** `org.jetbrains.kotlin.multiplatform`, `org.jetbrains.kotlin.jvm` (`:cli` only), `com.android.library`/`com.android.application`, `org.jetbrains.kotlin.plugin.serialization`, the Compose Multiplatform + Compose-compiler plugins (Android-only Compose), `application` (`:cli`), and `io.gitlab.arturbosch.detekt`. Koin is a runtime dependency (no plugin). **No `app.cash.sqldelight` plugin** — SQLDelight is not adopted (§8.1).
- **iOS framework packaging:** `shared` is consumed as a static framework (`baseName = "Shared"`, `isStatic = true`) per `shared/build.gradle.kts`; an XCFramework / SPM packaging step can come later.
- **Notable runtime dependencies** beyond the ones §3 lists: Coil 3 (`coil-compose`, `coil-network-okhttp`) for images, `androidx-navigation-compose`, `androidx-core-splashscreen`, `play-services-code-scanner` for the Ring QR scan, `androidx.work:work-runtime-ktx` (§9.6), `com.google.zxing:core` (the tablet sign-in panel and the CLI's terminal QR), `org.xerial:sqlite-jdbc` (the desktop `.apkg` reader only — Android uses platform SQLite), and JNA for the UniFFI bindings. **SKIE is not in the build and is not planned** — the Swift↔Flow bridge is hand-rolled (§9.2).
- **`:cli` packaging:** `./gradlew :cli:installDist` produces `cli/build/install/loopky/bin/loopky`; `:cli:distTar` produces a tarball. Both need a JRE 17 on the target machine, which is short of the goal — see §13.10.
- **Lint:** detekt with `detekt-formatting` + `detekt-compose-rules` (`config/detekt/detekt.yml`) via `./gradlew detektAll`; SwiftLint via `./gradlew lintSwift` (`iosApp/.swiftlint.yml`, generated `pubkycore.swift` excluded).
- **CI** (`.github/workflows/ci.yml`), on PR and push to `main`: `detektAll`, then `:shared:testDebugUnitTest :composeApp:testDebugUnitTest`, then `:composeApp:assembleDebug`. **No iOS job** — there is no test target and no `xcodebuild` step, so iOS breakage is caught only by building locally.

---

## 12. Open questions

Genuinely open items only. Three questions this list used to carry — the UI strategy, the Swift↔Flow
bridge, and secret storage — are settled and are recorded where they belong: §3 and §5, §9.2, and
§7.5 respectively. The UI question in particular gated "before the first screen ships", a gate that
passed roughly ninety Compose screens ago.

1. **Multi-module split timing.** Single `shared` module for v1; split into `:core / :data / :domain / :feature-*` if build times or ownership boundaries require it. Relatedly, **SRS at Anki scale is what forces the persistent-cache (SQLDelight) question** (§8.1): a local DB as SRS source of truth is the endgame for #43 §2, and reverses "Pubky is the source of truth" for that one slice.
2. **Private decks.** Not a v1 feature (spec §11). Adding them means a local-only write path on `DeckRepository` and a persistent store for decks with no homeserver record (§8.1) — the visibility toggle is the small half.
3. **Cross-device SRS conflicts.** Review state is last-write-wins like everything else, so two devices studying the same deck in one day can lose each other's grades. Nothing handles it, and the fix is the local DB in #1 rather than anything cheaper. How the state itself is stored is *not* open — chunked and author-scoped, see §8.0 and the flush rules below.
4. **AI / OCR / URL import** (spec §14) — all reuse `TriageViewModel` + `PublishDeckViewModel`, or skip triage for the bulk summary screen as `BulkImportViewModel` does. No architectural change needed, only new `ImportRepository` entry points and screens.
5. **Binding regeneration automation.** Today the fork's `build_android.sh` / `build_ios.sh` are run manually and artifacts are copied in (§7.4). A Gradle task can automate this once the fork API stabilises.
6. **Local-key custody handover.** Exporting a key to Pubky Ring (§7.5) currently *copies* it — Loopky keeps its own copy and its session, and the UI says so. Whether "exported to Ring" should eventually mean dropping the local key and reverting to Ring-authorised sessions is deliberately unanswered: it is the strongest end state, but Ring gives no confirmation that the import succeeded, so a device that dropped its key on a failed export would be locked out. `LocalKeyStore` is shaped so this becomes a flag transition rather than a rewrite.
7. **Cookie vs grant for local auth.** `signIn`/`signUp` bind to the FFI's cookie variants because the grant flow fails against Synonym's staging homeserver — `export_grant_session_secret` writes outside `/pub/`, which it refuses with a 403 (§7.8). Upstream marks the cookie flow deprecated, so this is a hold, not a destination; it needs a homeserver that accepts the grant flow before it can move. Tracked with #130.
8. **Password-manager backup** needs a domain serving `assetlinks.json` and `apple-app-site-association` before either platform can start — #150. **iOS parity** for the whole local-key surface is #149; iOS has no signup screens at all today, so that is a build-out rather than an addition.

---

## 13. The headless client (`:cli`)

`loopky` is a terminal binary that creates and manages decks against the same homeserver layout
the two apps use (#54). The point is not a nicer keyboard UX: it is that **an agent can drive
Loopky**. Agents already write good flashcards; until this, the only way into a Loopky deck was a
phone screen.

### 13.1 Why it is a module here and not a rewrite

Three options were on the table: a JVM target on `shared`, a Rust CLI straight on the `pubky`
crate, or a Python CLI on the existing bindings. The last two are the same trade — skip the FFI and
the JVM entirely, at the cost of **reimplementing the deck protocol**, which is no longer just a
schema. `DeckRepositoryImpl.publish` writes an `incomplete: true` marker manifest *first* (§8.0),
then the chunks at `MAX_IN_FLIGHT = 4`, then sweeps trailing chunks when a deck shrinks, then
rewrites the manifest without the marker, then mirrors tags best-effort. Add the spec §6 parser,
the `.apkg` reader and SRS, and it is most of `data/repository/impl` in a second language that can
drift — and the marker ordering and the stale-chunk sweep are precisely where a second
implementation silently produces decks the Android app cannot open.

So: a `jvm()` target, and the CLI inherits `MAX_IN_FLIGHT`, `SessionRetry`'s 429 backoff, the
chunked layout and the parser for free.

What it cost was **not one `actual`**: five trivial ones, an `ApkgReader` on a JDBC driver, and a
`jvmPlatformModule` answering for ~15 bindings. Most are cheap for a headless client, but they all
have to exist — `:shared` is one Koin graph and a missing binding is a start-up failure.

### 13.2 Capabilities: `/pub/loopky/:rw`, and nothing else

The apps request `DEFAULT_CAPABILITIES` (`/pub/loopky/:rw,/pub/pubky.app/:rw`). The CLI requests
the first half only, and the guarantee that buys is **structural rather than a flag somebody
remembered to set**: an agent session cannot write a post, a follow or a profile edit under any
bug or any prompt injection, because it was never handed the capability.

It costs less than it first looked, because the premise that deck tagging needs the pubky.app
namespace is wrong. `TagRepositoryImpl` routes on the *subject*: a deck manifest goes to
`PubkyPaths.loopkyTag` (`/pub/loopky/tags/`), and only a profile or post subject goes to
`PubkyPaths.tag` (§7.7). So `deck create --tag …` and the language tags a declared pair puts on a
deck both work unchanged.

What is actually given up is the pubky.app-native writes a headless deck tool has no business
making: **announcing** (#39 — a post), tagging a *profile*, and editing `profile.json`. Reading
pubky.app is dropped too, and that is scope rather than capability — public `/pub/` records are
world-readable and no capability was gating them. One visible consequence: `whoami` reports pubky,
homeserver and environment and **not a display name**, because that lives in
`/pub/pubky.app/profile.json`.

The announce confirmation is therefore gone **by construction**, not behind a `--no-announce`
flag. There is nothing to confirm.

### 13.3 `--json` is the verification channel

An agent cannot look at a screenshot to check that the picture it attached is the picture it
meant. Reviewing candidate images by eye is what caught 15 of 27 state-map searches returning the
wrong kind of map, several book covers with the answer printed on them, and a food photo that was
a YouTube thumbnail of a presenter's face. What replaces that is reads that **echo back what was
actually stored** — fronts, backs, image refs, tags — so intent can be diffed against result
without rendering anything.

That makes `--json` an API surface with compatibility obligations, so it is versioned from the
first release (`"schema": 1`). Fields may be added; a field's meaning may not change under the
same version.

Two fields ride on **every** result, success and failure alike: `environment` and `indexer`. The
reason is sharp. A Nexus read aimed at the wrong network does not error — it answers *normally,
for the other network*, so everything comes back empty. An agent that writes a deck tag, reads it
back, gets `[]` and concludes the write failed will retry, against the very requirement that says
a repeated `card add` must be detectable, with no signal anywhere that the read was aimed at the
wrong world. A silent empty is the one failure mode this design cannot absorb, so the answer
always says which network answered.

### 13.4 Exit codes, and why session expiry has its own

| Code | Meaning |
| --- | --- |
| 0 | ok |
| 1 | internal |
| 2 | usage |
| 3 | not signed in |
| 4 | **session expired** |
| 5 | network |
| 6 | not found |
| 7 | storage full (507 — terminal, never retried; §8.5) |
| 8 | environment mismatch |
| 9 | bad input |

4 is the reason this table is not three rows long. The homeserver session dies after roughly an
hour and nothing renews it (#165): writes start failing, reads keep working, and from outside it
is indistinguishable from a network wobble. An agent told the wrong one either retries a dead
session forever or abandons a working network. Told which it is, it can stop, say so, and resume
after a human runs `loopky login` — which is what makes `--resume` worth having at all.

Classification goes through `Throwable.toErrorReason()`, the same classifier the apps use, rather
than matching messages in the CLI: it already knows that "this pubky published no homeserver
record" and "the DHT did not answer" arrive through one call and are not the same thing.

There is **no `expires_at`** to report, and `whoami` says so by omission. The FFI's session payload
carries a pubky, a secret, a homeserver and capabilities — no expiry — so a client cannot plan
around the wall, only discover it. `whoami` therefore *asks*: it revalidates and reports
`session_live`, which is the honest substitute and the thing to check before starting an hour-long
import rather than forty cards in.

### 13.5 Sessions: `LOOPKY_SESSION`, and a 0600 file

`LOOPKY_SESSION` is read **before** the stored session. That ordering is the requirement, not a
convenience: a cloud agent's sandbox is recreated per task, so `$XDG_CONFIG_HOME/loopky` is gone
every run, there is no "log in once", and nobody is watching that terminal to scan a QR code. The
variable is the only way in on such a box.

The secret alone is not a `Session` — the pubky, homeserver and capabilities are not in it. So
`IdentityRepository.adoptSession` trades it for the real thing via `revalidateSession`, which
proves it is still live in the same round trip. An injected session is deliberately **not
persisted**: it is the caller's, for this process, and writing it to `$XDG_CONFIG_HOME` would
leave a container's credential behind on the machine whose own session it was standing in for.

On disk it is a **mode-0600 file, as the default rather than the fallback**. libsecret is usually
present on a desktop Linux and usually absent on the headless box an agent runs on, so making a
keyring the default and the file the fallback would fail exactly where the tool is meant to work.
The trade-off is stated in `--help`, not only here: a session secret on that machine is protected
by file permissions and nothing else. What is stored is a capability-scoped, expiring session,
never a secret key — that never leaves Pubky Ring. macOS gets the same file store today; the
Keychain is the right answer on that row and is that row's remaining work.

### 13.6 Which network, and why it must be said out loud

The apps pick the network from the build type — debug is staging, release is production, pinned
and un-overridable (#42). A binary has neither a build variant nor a Settings screen, and the
sandbox it runs in has no `local.properties`. So `--env` / `LOOPKY_ENV`, **defaulting to
production**, for the same reason `PubkyEnvironment.fromNameOrProduction` falls back that way: an
unrecognised value must not quietly point at a network the user does not publish to.

One value, never three flags. `PubkyEnvironment` exists so the Homegate, the default homeserver
and the indexer cannot be configured apart, and there is deliberately no `--nexus-url` beside a
`--homegate-url`.

A session and an environment that disagree is an **error at startup**, code 8, not a warning. The
homeserver half would work either way — pkarr resolves both networks — and only the
indexer-backed reads would be silently wrong, which is §13.3's failure mode. The check fires only
when the session sits on the *other* environment's known default homeserver, which is a fact; it
is not an equality check against `defaultHomeserver`, which would refuse a legitimate self-hosted
one.

### 13.7 Resumability and idempotence

Both are v1 requirements rather than polish, and for one reason: the hourly expiry means an
agent's normal recovery is to re-run the command.

- **`card add` is idempotent by front/back**, image included. Text alone would collapse two cards
  asking the same question about different pictures — a flags deck is exactly that. Rows that
  match are *reported* as skipped, so a caller can tell "already there" from "did nothing".
- **`import --resume` checkpoints against the deck on the homeserver**, not a local cursor file.
  The deck already records exactly which cards arrived, it survives the sandbox being thrown away,
  and it cannot disagree with reality the way a cursor can. It costs one deck read. It is matched
  on the deck's **title**, which is why `--title` is mandatory and never derived from a filename:
  a resumed run has to name the same deck it named the first time.
- **Batch mutation, not just batch creation.** `card edit --from-file` exists for the same reason
  the surface steers agents away from `card add` in a loop, with more force: editing is what you
  do *after* an import, and it is the shape an agent naturally reaches for. One card write is one
  chunk read-modify-write plus a manifest patch plus a `/session` round trip (#105) either way;
  what a batch saves is the process start, the sign-in and the deck read, which is most of the
  wall clock.

### 13.8 The import format, and the image columns

`loopky import` runs the **app's own parser** — `ImportRepository.parseBulk`, spec §6 separator
rules and §9 edge cases, with the file-sized caps. A deck imported here and a deck imported on a
phone are split identically, which is what "the Android app opens it without a repair step" rests
on.

A tab-separated file with **image columns** takes the other entry point:

```
front <TAB> back <TAB> front_image_url <TAB> back_image_url
```

Splitting that on tabs is reading a format this client defines, not reimplementing §6; the rows
then go through `parseBulkNotes`, which is the same body minus the splitting step. That entry
point exists because dedupe *renumbers* rows, so a picture cannot be re-attached afterwards by the
index it was read at.

**Two tests decide whether a file is in that format, and both are needed**: every non-blank line
has three or more tab fields, *and* every non-empty image column holds an `http(s)` URL. The first
keeps a stray third column from being read as a format. The second keeps a three-column Anki
export — Front / Back / Example sentence, a very common shape — from publishing every card with
`MediaRef.Image(url = "una manzana roja")`, which both apps then try to load as a picture while
the column's real content is lost and `--json` reports success. Every app-side constructor of a
remote image ref takes its URL from a picker; this is the first path where an arbitrary string
reaches one. A file failing either test falls through to the shared parser — the right answer for
`import`, where the file is text somebody wants imported, and the wrong one for
`card add --from-file`, where the four-column TSV is what the user explicitly asked for and a
non-URL is an error.

The columns are there from day one because they close the gap neither bulk path can express: both
`.apkg` and TSV carry an image only when a field is *nothing but* that image
(`AnkiField.imageSrc`), so "this side has text **and** a picture" had no representation, and ~190
pictures had to go through the card editor by hand even though the decks imported in one shot.
Since #167 an image can be a remote ref — a URL, no bytes on the wire, no media quota — which is
what makes fixing it cheap.

### 13.9 Login on a machine with no phone in it

`beginSignIn(returnToApp = false)`. The mobile flow appends Ring's `x-success`/`x-cancel`/
`x-error` callbacks so Ring can deeplink back into the app; a CLI must not, because there is no app
to return to and a dangling `x-success` pointing at `loopky://` bounces the user into Loopky on
their phone after a desktop login. Nothing else about the flow changes: the session still arrives
over the relay in phase 2.

The URL is printed as a terminal QR (half-blocks, black-on-white **explicitly** — a terminal's own
theme is unknown and frequently dark, and drawing modules in the default foreground produces an
inverted code no scanner will read), with `--qr-out FILE` for a PNG and `--url-only` for a box
whose output is going into a log.

Two constraints worth knowing. `AUTH_FLOW` in the FFI is a **single global slot**
(`static AUTH_FLOW: Mutex<Option<…>>`), so there is one in-flight auth per process — fine for a
CLI invocation, relevant the moment anyone wraps this in a daemon serving several agents. And
`awaitAuthApproval` *takes* that slot, so a failed poll consumes it and there is no in-place
retry: recovering means running `loopky login` again, which mints a new secret and a new code. The
CLI says that rather than looping, because a retry loop would silently invalidate the code already
on screen.

### 13.10 Native library and packaging

`libpubkycore` ships **inside the jar**, under JNA's own resource layout
(`linux-x86-64/libpubkycore.so`, `darwin-aarch64/libpubkycore.dylib`), which `Native.load` extracts
at runtime. Nobody installing the CLI fetches a native library by hand or sets
`-Djna.library.path`. Built by `build_desktop.sh` in the fork; see
`shared/src/jvmMain/resources/README.md`.

`UniffiPubkyClientJvmTest` is what proves a row actually loads. The 1,271 shared tests run against
`FakePubkyClient` and pass identically on a machine where the directory is empty, the architecture
is wrong, or the file is one level off — none of which surfaces until the first homeserver call,
as an ordinary-looking transport error.

**What ships today is `installDist` / `distTar`: a jar plus a start script, needing a JRE 17.**
That is honestly short of the target. "How it reaches an agent" is the primary constraint, not a
footnote: for a cloud sandbox — non-root as often as not, ephemeral, configured by a setup script
— `curl -fsSL … -o ~/.local/bin/loopky && chmod +x` is one line and needs no root, where a
third-party apt repo is four privileged commands and a GPG keyring. "Install a JDK" is exactly
what a one-line install cannot be. The remaining work is GraalVM `native-image` (single binary,
~20–40 ms start; kotlinx.serialization is compile-time and fine, but **JNA is the sharp edge**,
since native-image needs explicit handling of the very library extraction above), with a `jlink`
tarball as the fallback. A container image is the other half, and for a sandbox that lets you pick
one it beats any install step.

Two smaller gaps, named so they are decisions rather than omissions. The tarball carries **both**
native rows, so a Linux box hauls 11 MB of macOS dylib it will never load; splitting the
distribution per OS is straightforward and has not been done. And **Windows** is out of scope for
v1 — it is the same three rows (`win32-x86-64/pubkycore.dll`, a DPAPI session store, a UTF-8
console for the QR) and nothing in the design blocks it, but no part of the agent workload that
motivated this runs there.

### 13.11 Shape for a second front end

The chat-only audience — Claude's analysis tool, ChatGPT's code interpreter — has no shell and no
network egress, so a client needing a homeserver, a relay and DHT resolution cannot function there
under *any* packaging. For that audience a remote MCP server is not a later evolution of the CLI;
it is a **parallel channel** and the only integration that exists.

The command layer is therefore shaped to be wrapped: one function per command, taking plain values
and returning its `--json` shape, with **no logic in the arg parser**. `dispatch` is a `when` over
verbs and nothing else. An MCP server should be a binding over those functions rather than a
second implementation of them.

Whether such a server is hosted — and therefore holds users' session secrets, with everything that
implies — or runs on the user's own machine over stdio is open. Finding 1 pushes the same way for
a different reason: a process that outlives one command is the natural place to renew a session
before it expires, which a per-invocation CLI can never be.

### 13.12 Still open

- **Can Loopky run behind an allowlist proxy at all?** A hard blocker for cloud sandboxes, not a
  polish item. Homegate and Nexus are fixed hosts, but the homeserver is resolved from its pubky
  over pkarr and served under a pkarr-derived certificate, so the data host is **not a fixed
  name** — and pkarr's DHT path is UDP, which an HTTP-only proxy blocks outright. Somebody has to
  establish whether the relay fallback is enough to run the whole client over HTTP to a known set
  of hosts. If it is, document the list; if it is not, the container image with unrestricted
  egress is the only cloud-sandbox story and that should be said out loud.
- **Its own repo, and the `core` / `presentation` split.** Starting in-tree is cheaper while
  `shared` is moving; the trigger for extracting is the first *out-of-tree* consumer, which needs
  a published artifact — and that artifact is the module boundary. The seam is real and these two
  consumers name it: the CLI wants repositories and treats VMs as dead weight, while the watch
  (#73) wants `presentation/`.

---

## 14. References

- [`docs/specs.md`](./specs.md) — Paste-to-Import product spec.
- `pubky-core-ffi-fork` — local sibling repo at `../pubky-core-ffi-fork`.
- Pubky Ring deeplink contract — §7.8.

---

*End of architecture doc. Update it alongside the code; do not let it drift.*
