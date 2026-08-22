# Loopky — Architecture

> **Status:** Draft v1 · **Scope:** Technical architecture for the Loopky KMP app.
> **Reads alongside:** [`docs/specs.md`](./specs.md) · [`design/DESIGN_GUIDELINE.md`](../design/DESIGN_GUIDELINE.md)

---

## 1. Overview

Loopky is a **Kotlin Multiplatform** flashcards app targeting iOS and Android. Business logic — domain models, repositories, and ViewModels — lives in a single `shared` module (`commonMain`). Repositories own the business logic; there is no separate use-case layer. Each platform renders its own native UI: **Jetpack Compose** on Android (`composeApp/androidMain`) and **SwiftUI** on iOS (`iosApp/`). Identity, social graph, tags, and published decks are backed by **Pubky**, accessed through a native binding layer built on top of `pubky-core-ffi-fork`.

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
loopky/
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
| `DeckRepository` | CRUD + `publishDeck(deck, cards)` / fetch decks; enforces the "each side has at least one populated field" rule. Also owns **deck** following — `followDeck()` / `unfollowDeck()` / `listFollowed()` — and `clone()` (§8.0). Deck follows live here rather than on `DiscoveryRepository` because `listFollowed()` merges with `listOwned()` behind one `changes` flow, `sync()` resolves a followed deck's author from the subscription, and `DiscoveryRepositoryImpl` already depends on this repo | Pubky FFI + in-memory cache |
| `CardRepository` | CRUD cards within a deck | Pubky FFI + in-memory cache |
| `ImportRepository` | `parse(rawText, separator)` per spec §6/§7 (col 1 → front, col 2 → back, extras dropped — spec §8), `setDecision()` / `keptRows()` triage, in-memory drafts, dedupe | In-memory |
| `TagRepository` | Read/write Pubky tags on any subject (deck or profile — brief §9.3); the reserved `loopky-*` index labels via `putReservedTag`; deck-topic (`trendingDeckTags`), tagged-subject and tagger-count reads via Nexus (§7.7) | Pubky FFI + Nexus REST |
| `DiscoveryRepository` | Decks by followed **users**, `followUser()` / `unfollowUser()` (brief §9.4) — deck-level following is on `DeckRepository`, plus verified network-wide reads: `decksByTagGlobal()`, `loopkyUsers()` and `suggestedPeople()` | Pubky FFI + Nexus REST |
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

- **UI:** Jetpack Compose, Material 3 components styled by Loopky design tokens.
- **State:** `val ui by vm.state.collectAsStateWithLifecycle()` in each screen composable.
- **Navigation:** Jetpack Navigation Compose. One `NavHost` per top-level tab (Study / Decks / Discover / Profile), plus sheets for Paste-to-Import flows.
- **DI:** Koin Android, bootstrapped in `MainActivity`. Screens resolve their VM via `koinViewModel()` (or equivalent KMP helper).
- **Platform glue:** `AVSpeechSynthesizer`'s Android counterpart is `android.speech.tts.TextToSpeech`; haptics via `HapticFeedbackConstants`; image picker via Activity Result APIs.

### 5.2 iOS (`iosApp/`)

- **UI:** SwiftUI, styled by Loopky design tokens mirrored in Swift.
- **State:** shared VMs exposed as ObservableObject wrappers. The Kotlin→Swift Flow bridge is TBD (see §12) — working assumption is **SKIE**.
- **Navigation:** `NavigationStack` per tab, `.sheet`/`.fullScreenCover` for Paste-to-Import and triage.
- **DI:** Koin started from the Swift `@main` entry; VMs handed to views via initializers.
- **Platform glue:** `AVSpeechSynthesizer` for TTS, `UIImpactFeedbackGenerator` for haptics, `PHPickerViewController` for images, `AVAudioRecorder` for audio cards.

### 5.3 Theming

Design tokens (brief §11 deliverable) are authored as JSON and consumed both sides:
- Android: tokens generated into a Kotlin `LoopkyColors`/`LoopkyType` in `composeApp`.
- iOS: tokens generated into a Swift `LoopkyColors`/`LoopkyType` in `iosApp`.
- The shared module does **not** hold a Compose theme.

---

## 6. State flow — Paste-to-Import walkthrough

Ties spec §5 (UX flow) to code. Each arrow is an actual function call.

```
┌─ User action ────────────┐   ┌─ Platform UI ────────────┐   ┌─ shared VMs/repos ─────────────┐   ┌─ Pubky / local ─┐
│ Taps "+" → Paste screen  │ → │ PasteImportScreen        │ → │ PasteImportVM.state = Empty    │   │                 │
│                          │   │                          │   │                                │   │                 │
│ Pastes text              │ → │ onTextChanged(text)      │ → │ ImportRepository.parse()       │   │                 │
│                          │   │                          │   │ _state = Preview(draft)        │   │                 │
│                          │   │                          │   │                                │   │                 │
│                          │   │ collectAsState → redraw  │ ← │                                │   │                 │
│                          │   │ 3 flip-card previews     │   │                                │   │                 │
│                          │   │                          │   │                                │   │                 │
│ Overrides separator      │ → │ onSeparatorOverride(…)   │ → │ re-parse → Preview(draft')     │   │                 │
│                          │   │                          │   │                                │   │                 │
│ Taps Next                │ → │ nav → TriageScreen       │ → │ TriageVM(draft)                │   │                 │
│ Swipes keep/discard      │ → │ onSwipe(id, decision)    │ → │ ImportRepository.setDecision() │   │                 │
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

**Decision:** Loopky consumes the UniFFI-generated bindings shipped by `pubky-core-ffi-fork` directly. No handwritten FFI, no cinterop. The fork's `build_android.sh` and `build_ios.sh` produce the artifacts we check in; we don't call them from Gradle (yet).

### 7.1 Shared interface

> **Bulk operations.** `PubkyClient` has no batch/multi-put primitive, and all writes are sequential per operation. Publishing, deck listing, the `delete()` sweep and chunk reads all want bounded concurrency (#43 §4). Since the interface is a deliberate 1:1 FFI mirror, that stays a *repository-level* concern unless the FFI grows bulk calls. Two prerequisites before turning concurrency on: `MutableSessionProvider.value` is a plain non-atomic `var` read on every retry attempt, and `SessionRevalidatorImpl` serializes revalidation without coalescing it. Every `list()` call site now pages through `cursor`/`limit` (`data/repository/impl/PubkyPaging.kt`); the deck listing also asks for `shallow`.

`com.github.jvsena42.loopky.data.pubky.PubkyClient` in `shared/commonMain` is a **thin** Kotlin interface that mirrors the FFI surface one-for-one. It hides the `List<String>` `[status, payload]` convention behind `Result<String>` but does **not** introduce deck/card concepts — higher-level domain operations live in the repositories layer. The interface groups calls into: keys & mnemonics, recovery files, auth/sessions (including the Pubky Ring-style `startAuthFlow` / `awaitAuthApproval` / `parseAuthUrl` flow), records (secret-key and session variants), DHT resolution, and network switching.

### 7.2 Android wiring

- UniFFI-generated `pubkycore.kt` is checked in at `shared/src/androidMain/kotlin/uniffi/pubkycore/pubkycore.kt` (package `uniffi.pubkycore`).
- Native libraries live at `shared/src/androidMain/jniLibs/{arm64-v8a,armeabi-v7a,x86,x86_64}/libpubkycore.so`. AGP picks them up automatically and merges them into the APK.
- JNA is required by the generated bindings and declared as an `@aar` dependency on `androidMain` (see `libs.versions.toml` → `jna`).
- `AndroidPubkyClient` (`shared/src/androidMain/kotlin/com/github/jvsena42/loopky/data/pubky/AndroidPubkyClient.kt`) is the `PubkyClient` implementation. Blocking FFI calls are dispatched to `Dispatchers.IO`.

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

Non-secret user preferences go through a *separate* interface in the same package,
`AppPreferences` — `AndroidAppPreferences` over `SharedPreferences`, `IosAppPreferences` over
`NSUserDefaults`. Deliberately not the same door: `SecureSessionStore` pays keystore/keychain
costs for the session, and a boolean the user flipped in Settings has no business sharing it.
Device-local for v1; a preference that has to survive a reinstall belongs in a
`/pub/loopky/settings.json` record, and this interface is the seam that would move. First
tenant is `shareOnPubky` (#39).

### 7.6 Nexus indexer (global reads)

Global questions a single homeserver cannot answer — trending tags, prefix search, "which
decks carry this tag", "how many people follow this deck", "who else uses Loopky" — are
served by the Pubky Nexus REST API (`data/nexus/NexusClient`, default base
`https://nexus.staging.pubky.app`). The HTTP layer is the small `HttpFetcher` interface with
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
| `taggersOfLabel` | `/v0/tags/taggers/{label}` | the `loopky-user` directory — **empty in practice, see §7.7 point 8** |
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
| `pubky://{id}/pub/pubky.app/profile.json` | `/pub/pubky.app/tags/{id}` | user | `/v0/user/{id}/tags`, `/v0/tags/taggers/{label}`, `/v0/tags/hot` |
| `pubky://{author}/pub/loopky/decks/{id}/manifest.json` | `/pub/loopky/tags/{id}` | resource | `/v0/stream/resources?app=loopky`, `/v0/resource/by-uri` |

`TagRepositoryImpl` routes on the subject for exactly this reason.

**2. `app` is the record's path segment, verbatim.** Nothing about it derives from the
subject URI. Loopky resources are `app=loopky` because records live at `/pub/loopky/tags/`;
`app=loopky.app` would match nothing. (Live check: `?app=mapky.app` returns mapky's
resources, `?app=mapky` returns none.)

**3. Resource tags never trend.** `/v0/tags/hot` and `/v0/tags/taggers/{label}` are
restricted to `Post|User` targets (`nexus-common/src/db/graph/queries/get.rs:614-640`), so
**deck labels — including `loopky-deck` — cannot appear in the trending row**. Only
`loopky-user`, a profile tag, can. Trending over deck tags therefore has to be aggregated
client-side from `/v0/stream/resources?app=loopky&sorting=taggers_count` — which is what
`TagRepository.trendingDeckTags` does (#26): one request, labels grouped and ranked by how many
distinct decks carry them. The stream returns each resource's *whole* label list with per-label
tagger counts, which is what makes this possible from a single call.

**4. Announcement posts (#39) are how deck topics reach the global index.** A post is a `Post`
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

**5. The universal path does not validate or sanitize.** Unlike the pubky.app path it checks
neither the tag id against the body nor the label's casing
(`nexus-watcher/src/events/handlers/universal_tag.rs:62`). `TagRepositoryImpl.sanitizeLabel`
is therefore load-bearing for deck tags — drop it and labels fragment by case.

**6. Before #40, deck tags were written to `/pub/pubky.app/tags/` and silently dropped.** If
old decks are missing from the indexer, that is why, not indexer lag.

**7. Two similar hashes, different encodings.** Tag id =
Crockford-base32(blake3(`"{uri}:{label}"`)[..16]), 26 chars, from the FFI. Nexus
`resource_id` = lowercase-hex(blake3(normalized_uri)[..16]), 32 chars. Easy to confuse.

**8. `/v0/tags/taggers/{label}` only surfaces labels with real traction.** Probed against staging
while building #26: a `loopky-user` self-tag was written, ingested, and visible two days later on
both `/v0/user/{id}` and `/v0/user/{id}/taggers/loopky-user` — yet `/v0/tags/taggers/loopky-user`
returned `[]`, while busy labels (`synonym`, `bitcoin`, `test`) returned their taggers. So the
`loopky-user` directory reads empty on a young network even though every account is tagged
correctly, and `DiscoveryRepository.loopkyUsers` cannot be the only source of suggested people.
`suggestedPeople` unions it with the authors of globally-browsable decks, which works from the
first published deck. Note the label *is* discoverable by prefix search
(`/v0/search/tags/by_prefix/loopky` returns it) — it is the graph query that filters it out, not
the index.

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

**Redemption is Pubky Ring's job.** `IdentityRepository.beginSignUp` rewrites the sign-in deeplink
the FFI mints into its signup form (`asSignupUrl`), because `start_auth_flow` hardcodes
`AuthFlowKind::SignIn` and the fork exposes no override — the same workaround pubky-app uses. Ring
mints the key, redeems the token, and authorises back over the same relay, so Loopky never holds a
secret key and the post-approval path is the one sign-in already uses.

**Ring is checked before Homegate is asked anything.** Because only Ring can redeem a token, and
because getting one costs an SMS attempt or sats, `SignupStartViewModel` consults
`platform/PubkyRingPresence` *before* `SignupRepository.availability()` — the first Homegate call
of the flow. With Ring missing the screen offers an install prompt and every method is disabled,
so nothing is ever minted that the device cannot spend. The presence check is a Koin-bound
platform interface (like `BackgroundTasks`), and it owns the install URL too, since that also
differs per platform. It probes `pubkyauth://signin`, which makes it dependent on two pieces of
manifest configuration that report "not installed" on *every* device when missing: the Android
`<queries>` entry for the `pubkyauth` scheme, and `pubkyauth` in the iOS
`LSApplicationQueriesSchemes`. `SignupHandoffViewModel` keeps its own check — Ring can be removed
mid-flow — but it is now the backstop rather than the place users find out.

Two consequences worth knowing before touching this:

- **Retrying is safe and is the intended recovery.** Ring keys the pubky it minted off the token,
  so re-sending the same token reuses that key. Going back to Homegate instead would create a
  second identity and charge the user twice.
- **A session coming back does not prove the token was spent.** If Ring's signup fails while it
  holds another already-signed-up pubky, it quietly authorises that one and returns a valid
  session with the token unredeemed. So the token is cleared only when the homeserver we landed on
  is the one the signup targeted.

## 8. Data model & persistence

### 8.0 Homeserver layout (canonical)

Published decks live under the author's pubky as a manifest, a set of **card chunk** records, and media blobs. The homeserver is the source of truth; an in-memory per-session cache fronts it (see §8.3). A persistent SQLDelight cache is a possible future addition (§8.1).

Cards are batched rather than stored one record per card, and the manifest carries no card index. See §8.4 for why, and for the numbers that forced it.

**Path layout:**

```
/pub/loopky/decks/{deckId}/manifest.json      — deck metadata + chunk table
/pub/loopky/decks/{deckId}/cards/{n}.json     — up to CHUNK_SIZE cards per record
/pub/loopky/decks/{deckId}/media/{sha256}.{ext}
/pub/loopky/srs/{authorPubky}/{deckId}/{cardId}.json   — your review state (see §8.3)
/pub/loopky/subscriptions/{authorPubky}/{deckId}.json  — a deck you follow (see below)
```

- `{deckId}` and `{cardId}` are UUIDv4, generated client-side.
- `{n}` is the chunk ordinal, `0`-based and sequential.
- SRS records live **outside** `/decks/` and are keyed by the deck's author: your review state for someone else's deck was never the owner's data. Author-scoping also stops two authors whose decks share a `deckId` colliding in your `srs/` tree. *(The move to author-scoped, chunked SRS is #43 §2; the path above is the target, and `PubkyPaths.srs` still writes the deck-nested form today.)*
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
- A media ref (`cover_image_ref`, `image_ref`, `audio_ref`) may instead carry a `"url"` field for a **web image** (e.g. an Unsplash photo). When `url` is set, `path`/`sha256` are empty (`""`) and no blob is stored on the homeserver; the client loads the remote URL directly.
- **Unsplash licensing.** Their API guidelines are licensing terms, and breaching them costs API access. Loading the remote `url` rather than re-hosting the bytes satisfies the hotlinking rule; the image picker credits the photographer on every grid cell and links both them and unsplash.com with `?utm_source=loopky&utm_medium=referral`; and `UnsplashClient.trackDownload` pings `links.download_location` when a pick is committed. **Known gap:** a media ref carries no attribution fields, so a published deck cover or card face displays its Unsplash photo uncredited. Closing that means adding `author_name` / `author_url` to `MediaRefDto` (additive, schema stays `1` — `ignoreUnknownKeys` keeps older manifests readable) and rendering a credit wherever a remote image is shown.
- **The Unsplash access key is the user's, not the build's.** `UnsplashKeyStore` (`data/storage/`, KVault → Android Keystore / iOS Keychain, under its own `loopky.secrets` service so signing out cannot clear it) holds a key the user enters in Settings; `BuildConfig.UNSPLASH_ACCESS_KEY` is only a fallback behind it. `UnsplashClient` resolves the key per call rather than capturing it at construction, and `isConfigured` is a `Flow` so a key saved in Settings reaches an image sheet that is already open.

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
- **Chunk assignment is sequential-append**: a new card goes to the last chunk with room, tracked by `count`. Hash-partitioning on card id would be stable under every mutation but would produce CHUNK_SIZE-many near-empty records for a 50-card deck. A delete leaves the chunk short; **holes are tolerated deliberately** — closing one would mean rewriting every following chunk. Compaction is a later concern.
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
- **Study progress (SRS):** Pubky-backed and canonical, under `/pub/loopky/srs/{authorPubky}/{deckId}/` — **on your own homeserver, for any deck, including decks you do not own**. An in-memory session cache fronts it. *(This row previously read "in-memory in v1; not synced to Pubky", which had been untrue since `SrsStateDto` landed.)*
- **Decks you follow but do not own:** the owner's homeserver is canonical for the deck content (manifest, chunks, media) — you cannot write it, and every `DeckRepository` write is ownership-checked. Your own homeserver is canonical for your review state over it, and for the subscription record itself (`/pub/loopky/subscriptions/{authorPubky}/{deckId}.json`, §8.0). Unfollowing removes the subscription, not the SRS state.
- **Import drafts:** in-memory only — each paste is a fresh canvas (spec §4 story 5).
- **Private decks:** out of scope for v1 (spec §11). If spec §13 Q1 flips, local-only decks would need
  a persistent store (the §8.1 SQLDelight sketch) with `pubky_uri = NULL`.

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
methods are `suspend` or return `Flow`. The Swift↔Flow bridge **is wired**, hand-rolled rather than
SKIE: `IosFlowWatcher` (`shared/iosMain/util/`) exposes a `Flow` as a callback Swift can subscribe
to, and `FlowObserver` / `FlowEffectSink` (`iosApp/DI/`) wrap it as an `ObservableObject`. Generics
erase across the ObjC bridge, so values arrive as `Any` and are cast to the concrete `UiState` /
`Effect` type the framework exports. See §12 #2 for why not SKIE.

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

- **`commonTest`** — the important tier.
  - `ImportRepository.parse()`: one test per rule in spec §6, plus every edge case in spec §9.
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
2. **Swift ↔ Flow bridge.** ~~SKIE vs KMP-NativeCoroutines~~ **Resolved**: neither. Kotlin 2.3.x predates SKIE support, so the bridge is hand-rolled — `IosFlowWatcher` on the Kotlin side, `FlowObserver` on the Swift side (§9.2). Revisit if SKIE catches up and the erased-generics casting becomes a burden.
3. **Multi-module split timing.** Single `shared` module for v1; split into `:core / :data / :domain / :feature-*` if build times or ownership boundaries require it. Relatedly, **SRS at Anki scale is what forces the persistent-cache (SQLDelight) question** (§8.1): a local DB as SRS source of truth is the endgame for #43 §2, and reverses "Pubky is the source of truth" for that one slice.
4. **Private decks.** If spec §13 Q1 flips in favor of private decks, `DeckRepository` gains a local-only write path and `pubky_uri` stays `NULL` until the user opts in.
5. **Secret key & session storage** (§7.5). ~~Needs a decision~~ **Resolved**: `SecureSessionStore` via Liftric KVault (Keystore-backed EncryptedSharedPreferences on Android, Keychain on iOS).
6. **SRS at Anki scale.** ~~v1 keeps SRS local~~ ~~one record per card~~ **Resolved (#43 §2).** Review state is chunked and author-scoped at `/pub/loopky/srs/{authorPubky}/{deckId}/{n}.json`, with reviews buffered in memory and flushed per affected chunk. The flush lives on `SrsRepository`'s own app-scoped coroutine scope, not the ViewModel — `viewModelScope` is cancelled in `onCleared()`, so a flush started as the study screen goes away would be killed before finishing. It also flushes every N reviews, so a crash costs a few cards rather than a session. A local DB as SRS source of truth (see #3) remains the endgame if cross-device conflict handling is ever wanted.
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
