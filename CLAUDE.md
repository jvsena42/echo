# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Loopky is a Kotlin Multiplatform flashcards app (iOS + Android) that fuses TinyCards-style playfulness, Anki-style spaced repetition, and Pubky-based decentralized identity/social graph. See `docs/specs.md` (the deck-import spec) and `docs/Architecture.md` (technical architecture — this is the source of truth for module layout, layering, and open questions).

## Build & run

```shell
./gradlew :composeApp:assembleDebug     # Android debug build
./gradlew :shared:allTests              # shared KMP tests
./gradlew :shared:compileKotlinMetadata # fast commonMain compile check
./gradlew detektAll                     # lint Kotlin on all subprojects (detekt + compose rules)
./gradlew lintSwift                     # lint iOS Swift (SwiftLint; needs `brew install swiftlint`)
```

iOS: open `iosApp/` in Xcode and run. The `shared` module is consumed as a static framework (`baseName = "Shared"`, `isStatic = true`) — see `shared/build.gradle.kts`. **iOS is wired but unverified end-to-end**: `iOSApp.swift` starts Koin via `doInitKoin(rawPubkyClient:)`, handing in the Swift `IosPubkyClient` — a dumb `[status, payload]` pass-through, since `kotlin.Result` and suspend functions cannot be implemented from Swift — which `IosPubkyClientAdapter` wraps into the shared `PubkyClient` contract on the Kotlin side. The SwiftUI screens and the `IosFlowWatcher`/`FlowObserver` state bridge are in place; what is missing is anyone having run the thing against a real homeserver, so treat iOS behaviour as unproven rather than blocked.

Kotlin lint is detekt (`config/detekt/detekt.yml`, with `detekt-formatting` + `detekt-compose-rules`); run `./gradlew detektAll` (use `--auto-correct` to fix formatting findings). Swift lint is SwiftLint (`iosApp/.swiftlint.yml`, generated `pubkycore.swift` excluded); run `./gradlew lintSwift` or `swiftlint` from `iosApp/`. `shared/src/commonTest` holds a real suite (~680 tests across targets): repository tests over a `FakePubkyClient`, ViewModel tests over `FakeRepositories`, and parser/scheduler tests. Run `./gradlew :shared:allTests`.

## Architecture

**Business logic is shared; UI is native per platform.** This is the core rule — internalize it before making changes.

- `shared/src/commonMain/kotlin/com/github/jvsena42/loopky/` holds all cross-platform code:
  - `domain/model/` — pure Kotlin data classes (`Deck`, `Card`, `ImportDraft`, `SrsState`, `AppError`, etc.). No framework imports.
  - `data/repository/` — repository interfaces (all 9 in `Repositories.kt`: Identity, Deck, Card, Import, Media, Tag, Discovery, Srs, Settings), implementations under `data/repository/impl/`. **Repositories own the business logic** — parsing, triage, publishing, SRS grading, follow/unfollow, sign-in/out all live as methods on the relevant repo rather than in a separate use-case layer. **All 9 are implemented** (`IdentityRepositoryImpl`, `DeckRepositoryImpl`, `CardRepositoryImpl`, `ImportRepositoryImpl` — the paste parser, spec §6 rules + §9 edge cases —, `MediaRepositoryImpl`, `SrsRepositoryImpl`, `DiscoveryRepositoryImpl`, `TagRepositoryImpl`, `SettingsRepositoryImpl`), plus `SessionRevalidatorImpl`. `TagRepositoryImpl` writes pubky-app-specs tag records to the homeserver and reads trending, tagged subjects and tagger counts from the Nexus indexer (`data/nexus/NexusClient`, see Architecture.md §7.6). **Which namespace a tag record goes in depends on its subject** — a profile subject in `/pub/pubky.app/tags/`, a deck manifest in `/pub/loopky/tags/` — because that is what decides whether and how Nexus indexes it; read Architecture.md §7.7 before touching tag writes. The impls are Pubky-only: they write/read through `PubkyClient` and hold an in-memory per-session cache. No SQLDelight yet — the app is not offline-first, Pubky is the single source of truth.
  - `data/pubky/` — `PubkyClient` interface + DTOs (`ManifestDto`, `CardDto`, `MediaRefDto` in `DeckDtos.kt`, `ProfileDto`) and path helpers (`PubkyPaths`, `Hashing`) that map between domain models and the on-homeserver JSON layout defined in `docs/Architecture.md §8.0`. `SessionProvider`/`MutableSessionProvider` is the tiny read-only abstraction repos use to author writes without depending on `IdentityRepository`. `SessionRevalidator` + `SessionRetry` + `SessionPayloadParser` handle expired-session retry.
  - `data/pubky/PubkyClient.kt` — the single interface that wraps `pubky-core-ffi-fork`. All Pubky calls must route through this. It is a **thin** 1:1 mirror of the FFI surface (keys, mnemonics, recovery, auth, records, DHT). Do not add deck/card concepts here — those belong in repositories. The `actual` impl is `AndroidPubkyClient` (androidMain); the iOS impl (`IosPubkyClient.swift`) is still a stub awaiting framework binding.
  - `data/storage/` — `SecureSessionStore` interface for persisting the signed-in `Session`, backed by the platform keystore via Liftric KVault (`AndroidSecureSessionStore` wraps EncryptedSharedPreferences; `IosSecureSessionStore` wraps Keychain). This resolves the secret-storage open question — see "Non-obvious rules" below. Non-secret preferences use the separate `AppPreferences` (SharedPreferences / NSUserDefaults) in the same package — don't put a plain setting through the keystore, or a secret through `AppPreferences`.
  - `di/SharedModule.kt` — Koin graph binding repos, ViewModels, and `SessionProvider`; platforms override `PubkyClient` + `SecureSessionStore` via `PlatformModule.{android,ios}.kt`.
  - `presentation/` — KMP ViewModels, one per screen, each extending the multiplatform `androidx.lifecycle.ViewModel` (`viewModelScope`) and exposing `StateFlow<UiState>` + `SharedFlow<UiEffect>` (see "Coding conventions" below). **Implemented** across `onboarding/` (`OnboardingViewModel` + UiState/Effect), `home/` (`HomeViewModel`), `decks/` (`DecksLibraryViewModel`, `DeckDetailViewModel`, `DeckEditorViewModel`, `EditCardViewModel`), `import/` (`PasteImportViewModel`, `PublishDeckViewModel`), and `profile/` (`ProfileViewModel`). Coroutines + Koin are wired (no longer blocked).
- `shared/src/{android,ios}Main/` — platform glue only (Pubky FFI, TTS, speech recognition, haptics, file I/O). Nothing else lives here. Some is `expect`/`actual`; some is a plain interface bound per-platform in Koin (`Speaker`, `SpeechRecognizer`, `BackgroundTasks`, `PubkyRingPresence`), which is the right form when the implementation needs platform context or lifecycle.
- `composeApp/src/androidMain/` — Android app. Compose screens in `ui/`, Koin in `di/`, `MainActivity` as entry point. Uses Jetpack Navigation Compose.
- `iosApp/iosApp/` — iOS app. SwiftUI screens in `Views/`, `NavigationStack` in `Navigation/`, Koin bootstrap in `DI/`. Compose Multiplatform UI is **not** used for iOS screens.

### Non-obvious rules

- **Do not add Compose Multiplatform UI code for iOS screens.** The working assumption (see `docs/Architecture.md §12` open question #1) is native SwiftUI on iOS. `composeApp` is Android-only despite the name.
- **ViewModels live in `shared/commonMain`, not in platform modules.** Both Compose and SwiftUI screens consume the same VMs. No `@Composable` or `ObservableObject` in shared code.
- **Always import symbols; never reference them fully-qualified inline.** Add an `import` at the top of the file (e.g. `import androidx.compose.ui.graphics.Color`) and use the short name, rather than writing `androidx.compose.ui.graphics.Color` inline in a type or call. Applies to both Kotlin and Swift.
- **Native-first UI.** Prefer native platform components — **Material 3 Expressive** (`ShortNavigationBar`, `Scaffold`, `TopAppBar`, etc.; opt in with `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`) on Android, and native SwiftUI / Liquid Glass on iOS — over bespoke custom Composables/Views, so the app feels platform-native. Apply Loopky brand tokens (accent, type, radii) *to* native components rather than rebuilding chrome from primitives; build fully custom only where Loopky's identity needs it and no native equivalent exists (e.g. the study card flip). The custom `LoopkyTabBar` pill has been replaced by a Material 3 Expressive `ShortNavigationBar` (Android) and a native `TabView`/`UITabBar` (iOS).
- **Announcing a deck is opt-in, per action.** `DiscoveryRepository.announceDeck` writes a `pubky.app` post so a create/follow/clone reaches the user's followers, gated by `AppPreferences.shareOnPubky` (default on) *and* a confirm prompt each time. Off means never asked and never posted — the gate is on the write itself, not only in the ViewModels. **"Share" here means announcing, never visibility**: published decks are public either way (spec §11), and copy that blurs the two describes a privacy control Loopky does not have. Announcing is best-effort: a failed post must never roll back the deck, follow or clone. See Architecture.md §7.7 for the two things the post record has to get right.
- **Pubky is the source of truth for published decks.** The app is not offline-first in v1 — repos talk directly to `PubkyClient` and keep only an in-memory cache for the session. A persistent SQLDelight cache may come later. There are no private/local-only decks in v1 (spec §11).
- **Homeserver layout is fixed.** Decks published under `/pub/loopky/decks/{deckId}/{manifest.json, cards/{n}.json, media/{sha256}.{ext}}`. Cards are stored in **chunk records** (~100 per record, `CHUNK_SIZE` in `DeckDtos.kt`), and the manifest carries a chunk table + `card_count` — **not** a per-card index. Study order is the card's own sparse `ord`, not manifest position. Sync diffs `chunks[].updated_at`. Single-card writes go through `DeckRepository.upsertCard`/`deleteCard`, which own the chunk write and the manifest patch together — never write a chunk without patching the manifest. Full schemas in `docs/Architecture.md §8.0`. Binary media is written raw via the FFI's `put_bytes_with_session`; reads come back Base64-encoded from the FFI transport and are decoded in `MediaRepositoryImpl`.
- **Card deletes leave holes; a background pass folds them away, and it leaves gaps in the chunk numbering.** `deleteCard` shrinks a chunk's `count` rather than resequencing every later card, so `DeckRepository.compactDeck` reclaims the density off the critical path — one pair of neighbouring chunks at a time, asked for via `BackgroundTasks.scheduleDeckCompaction`, never performed inline. Two traps: a merge is the one chunk write that goes **landing record → manifest → source record** (every other one is chunk-then-manifest), because emptying the source first would leave the manifest pointing at a 404; and folding a pair drops its higher `n`, so the chunk table is **not contiguous** — anything that walks it must read `chunks[].n`, never `0 until chunks.size`. See Architecture.md §8.4.
- **A deck's manifest is written whole, so per-deck writes are serialized.** The manifest carries the entire chunk table, making every write a read-modify-write of the whole record. `DeckRepositoryImpl` guards this with a per-deck `Mutex` (`withDeckWrite`); anything named `…Locked` assumes the caller holds it, and `kotlinx` `Mutex` is **not reentrant**, so take the lock at exactly one level — the public entry point. Never patch the manifest from a `Deck` a caller captured earlier: go through `patchDeckLocked`, which re-reads it inside the lock. A dropped chunk entry orphans the chunk record, and the cards in it leave the deck with nothing reporting an error.
- **A clone's media un-pins itself opportunistically.** Cloning pins card media to the source author's blobs (`absolutizedTo`) rather than re-uploading hundreds of MB. `MediaRepository.get` emits `pinnedFetches` after serving a still-pinned ref, and `DeckRepository.rehostBlob` copies the blob under the clone and rewrites every ref carrying that sha. Both ends are **ownership-guarded** — a *followed* deck's blobs must never be copied under your pubky at a `deckId` you cannot edit. The write-back is the feature: without it the ref keeps its `uri` and every session re-copies the same blob. Re-host writes pass `touchDeck = false` (no `updated_at` bump, no `changes` emission) because nothing user-visible changed. A dangling origin is left dangling, never written into the card. The blobs nobody opens are swept by `rehostPendingMedia`, resumable via a `media_rehost_cursor` on the manifest — **not** derivable from the refs, since a chunk with nothing pinned is never rewritten. See Architecture.md §8.0.
- **507 Insufficient Storage is terminal, and every caller has to treat it that way.** The homeserver enforces a per-user quota (1GB free tier) and refuses writes over it. `isQuotaExceeded`/`ErrorReason.StorageFull` classify it *ahead* of the transient classifiers, `withWriteRetry` never retries it, and the background workers return `Result.failure()` rather than `Result.retry()` — a WorkManager backoff chain against a full disk never converges. Two traps: re-hosting and compaction both *consume* quota, so they cannot dig you out of one; and there is no client-readable usage endpoint, so nothing can warn before the wall. Read Architecture.md §8.5 before touching write error handling.

- **Background work goes through `platform/BackgroundTasks`.** A plain Koin-bound interface, not `expect`/`actual` — WorkManager on Android (`shared/androidMain`, because `PlatformModule.android.kt` binds it and `:shared` cannot see `:composeApp`), `BGTaskScheduler` on iOS. Two traps: a WorkManager-started process has Koin but **no session** (`loadPersistedSession()` is only called from ViewModels, so a worker must call it first or every write fails on "Not signed in"), and don't add a `Configuration.Provider` without removing `WorkManagerInitializer` from the merged manifest. The iOS side is written but unverified. See Architecture.md §9.6.
- **A never-seen card is not "due", and the daily goal never withholds one.** `isDue` requires a
  review state; `isNew()` is the separate question, and counts come back as `DeckCounts(due, new)`.
  The queue serves due reviews before never-seen cards and is **uncapped** — nothing in the
  queue-building path may consult `newCardsPerDayGoal`, because reaching it is announced, never
  enforced. Copy calling it a limit describes a feature Loopky does not have. Study settings
  (goal + Hard/Good/Easy first intervals) are a **synced record** at `/pub/loopky/settings.json`,
  not an `AppPreferences` value, because they decide `dueAt`s and review state already syncs;
  `SettingsRepository.update` refuses unless the record has actually been read this session, at the
  repository rather than only in the UI. Read Architecture.md §8.6 before touching any of it.
- **Listen and Speak are inert without a declared language pair, and that is deliberate — but
  the gate covers those two only.** A deck
  carries `frontLang`/`backLang` (BCP-47) beside the `listenEnabled`/`speakEnabled` opt-ins, and
  `Deck.speechReady` gates both features on having them. It does **not** gate the third opt-in,
  `typeEnabled` (see the next bullet). The OS engines fall back to the
  **reader's** device locale when given no language, so an undeclared Spanish deck is read aloud
  in an English accent and the spoken reply is graded by an English model — a wrong answer that
  looks like a working feature. Never reintroduce a locale default anywhere in this path: the
  author declares the pair, or the buttons do not appear. Consequences to know: the opt-ins
  default **off** when authoring, since turning one on obliges the author to pick languages; the
  deck editor carries the whole block, because a deck published before the pair existed has no
  other way to gain one; and picking a language also puts an **ordinary** tag on the deck
  (`"spanish"` for `es-ES`, plus the `"language"` umbrella — `LanguageTags`, base subtag, swapped
  when the pair changes), because a reserved label cannot trend or be browsed — Architecture.md
  §7.7 point 4.
- **Typing is the third study opt-in, and the one that needs no language pair.** `Deck.typeEnabled`
  puts an input on the card *back* — under the prompt label, in the space the answer will occupy —
  instead of handing the answer over. Everything the mode adds (input, miss line, Check, Give up,
  the "Correct!" note) lives **on the card**; the grade row and flip hint below it are untouched. Four things not to undo. It is deliberately outside
  `speechReady`: a string comparison has no engine to substitute the reader's locale into, and
  gating it would withhold the one assisted mode from exactly the decks (every import predating the
  pair) that have no other. It defaults **off** everywhere, legacy manifests included, unlike
  listen/speak — a manifest written before the field says nothing about its author's intent. The
  **flip is never blocked**: tapping turns the card as it always has, which is why `answerHidden`
  and `gradesAvailable` sit beside `revealed` on the study state, and why anything acting on "the
  side facing the user" (Listen, Speak) must ask `answerVisible` rather than `revealed` — or it
  reads out the answer the card is withholding. **Only a correct Check opens the card** — a wrong
  or near-miss answer says so and leaves you answering, with what you typed still in the field,
  because handing the answer over on the first slip turns one typo into a lost card and "check the
  accents" is a hint to fix what you wrote, not a verdict; Give up is the escape, and it is always
  right there under Check. And **nothing in the flow picks a grade**: the outcome is reported,
  Give up reports nothing at all, and all four SRS buttons stay equally available — an escape hatch that pre-selects Again is a punishment wearing an
  escape hatch's label. Matching is `AnswerMatcher` with an `AnswerStrictness`: typing is `Strict`
  (accents count — typing them is the point), `SpeakMatcher` is the `Lenient` view. Cards with no
  back text (an image-only Anki answer) or no prompt fall back to tap-to-reveal.
- **A parenthesized aside is never part of the answer, in any of the three modes.** `"hello
  (formal)"` is a card asking for `"hello"`: the bracket is an editorial note about which sense is
  meant. `AnswerMatcher.stripParentheticals` drops it inside `matches`/`isTypable`, so typing and
  Speak inherit it, and `StudySessionViewModel.onSpeak` drops it before the TTS effect — an engine
  handed the note reads it out as a word. Punctuation stripping alone does **not** cover this: it
  removes the brackets and leaves `formal` in the target. Two things not to undo — a text that is
  *entirely* parenthesized comes back untouched (`matches` refuses an empty target, so stripping
  would make the card unanswerable), and what is *shown* keeps the aside: `SpeakResult.expected`
  and the speak prompt quote the card as written, because the note is the context worth seeing.
- **Paste-to-Import is the v1 primary import flow.** The implemented spine is `PasteImportViewModel` (parse + live preview) → `PublishDeckViewModel` (commit to Pubky). Every other import source (AI, OCR, URL) listed in spec §14 must reuse this same spine. Don't build parallel commit flows.
- **Parser rules are prescriptive.** The paste parser (on `ImportRepository`) must follow the exact rule order in spec §6 and the edge-case table in spec §9. Use them as the test matrix.
- **No use-case layer.** Don't introduce `*UseCase` interfaces or a `domain/usecase/` package. If a piece of logic doesn't fit any existing repo, extend the most relevant repo or add a new one — keep the surface area flat.
- **Pubky bindings are UniFFI-generated and checked in.** Android: `shared/src/androidMain/kotlin/uniffi/pubkycore/pubkycore.kt` + `shared/src/androidMain/jniLibs/`. iOS: `iosApp/iosApp/Frameworks/PubkyCore.xcframework` + `iosApp/iosApp/Pubky/pubkycore.swift`. Regeneration steps live in `docs/Architecture.md §7.4`; do not edit the generated files.
- **Session storage is resolved** via `SecureSessionStore` (Liftric KVault → Android Keystore / iOS Keychain). Persist the signed-in `Session` only through this interface — do not wire multiplatform-settings or ad-hoc storage for secrets.
- **The Android app is real and feature-built; iOS is wired but unproven.** Onboarding → home → decks → paste-import → publish → profile all work on Android (Compose screens in `composeApp/src/androidMain/.../ui/`, nav in `ui/nav/`, DI in `di/`). The leftover `Greeting`/`Platform` template stubs still exist in `shared` but are no longer the running UI. iOS has its SwiftUI screens (`iosApp/iosApp/Views/`), a live Koin bootstrap, and the `IosFlowWatcher`/`FlowObserver` state bridge — but nobody has driven it against a real homeserver, so nothing there is verified (see Build & run).

### Package

Root package is `com.github.jvsena42.loopky`. Android namespace is `com.github.jvsena42.loopky` (app) and `com.github.jvsena42.loopky.shared` (library).

## Coding conventions

Prescriptive rules, adapted from the sibling Bitkit apps' `AGENTS.md` to Loopky's
shared-logic / native-UI split. These are the canonical conventions — `docs/Architecture.md`
points here rather than restating them.

### Shared (Kotlin · `shared/commonMain`)

- **ViewModels extend `androidx.lifecycle.ViewModel`** (the multiplatform JetBrains build) and
  launch work in `viewModelScope`. Do **not** hand-roll a `CoroutineScope`/`SupervisorJob` or an
  `onDispose()` — `viewModelScope` cancels in `onCleared()`. Never use `GlobalScope`; never
  `runBlocking` in suspend code.
- **State:** expose `val state: StateFlow<UiState> = _state.asStateFlow()`. **ALWAYS mutate with
  `_state.update { … }`; NEVER `_state.value = …`** (atomic read-modify-write). Reading
  `_state.value` is fine.
- **Effects:** one-shot effects (navigation, haptics, toasts, clipboard) go through a
  `MutableSharedFlow(extraBufferCapacity = 4)` exposed as `SharedFlow`, separate from state.
- **UiState shape:** `sealed interface` for screens with distinct modes (Loading/Empty/Content/Error);
  a single `data class` with nullable fields otherwise. Keep `UiState`/`Effect`/small helper data
  classes in the same file, after the ViewModel. (Annotate with `@Immutable` only in the *Android*
  layer — shared `commonMain` has no Compose dependency.)
- **Errors:** prefer `runCatching { … }.onSuccess { }.onFailure { }` / `Result` over try/catch; map
  domain `AppError` into the UI state. Prefer `requireNotNull(x) { "…" }` over `!!`.
- **Cancellation:** any `runCatching` whose block calls **suspending** code must be
  `runSuspendCatching` (`util/Coroutines.kt`) — plain `runCatching` catches `Throwable`, so it
  swallows `CancellationException` and turns "the caller went away" into an ordinary failure.
  Same for `mapCatching`/`recoverCatching` over a suspending lambda: fold them into one
  `runSuspendCatching { … }` instead. Plain `runCatching` stays right for pure, synchronous blocks
  (JSON decoding, an `Intent` launch) — they can't observe cancellation. A ViewModel should never
  need an `if (err is CancellationException) return@onFailure` guard; if one looks necessary, a
  suspending `runCatching` upstream is the actual bug.
- **DI:** bind ViewModels with Koin's `viewModel { }` DSL (`org.koin.core.module.dsl.viewModel`) in
  `SharedModule.kt`; repositories stay `single { }`.
- **Imports:** always import; never inline fully-qualified names (Kotlin and Swift).

### Android (Compose · `composeApp`)

- **Stateful/stateless split:** a `…Route` composable resolves the VM via `koinViewModel()` (NOT
  `koinInject`), collects state with `collectAsStateWithLifecycle()`, and consumes effects in a
  `LaunchedEffect`; it delegates to a stateless `…Screen(state, callbacks)`. Pass `viewModel::method`
  references down — never the ViewModel itself.
- **No manual VM disposal.** With `koinViewModel()` + `viewModelScope`, drop the old
  `DisposableEffect { onDispose { viewModel.onDispose() } }` blocks.
- **`modifier: Modifier = Modifier`** is the first optional parameter and is passed **last** at call sites.
- **Navigation goes through `NavController.navigateTo()`** (`ui/nav/NavExt.kt`), which dedups the
  current destination — never raw `navController.navigate(...)`.
- **Immutable collections (recommended, not yet adopted):** prefer `ImmutableList`/`persistentListOf()`
  for `UiState` list fields and Compose params, and annotate `UiState`/token data classes `@Immutable`.
  `kotlinx.collections.immutable` is not yet a dependency — treat this as the target when touching state.
- No hardcoded user-facing strings — use string resources.

### iOS (SwiftUI · `iosApp`)

- **Consume the shared KMP ViewModels.** Do **not** introduce iOS-side `@Observable` business-logic
  objects (unlike bitkit-ios) — Loopky shares its VMs. Bridge `StateFlow`/`SharedFlow` → SwiftUI per the
  Architecture §9.2 decision; call the VM's generated `clear()` on disappear (there is no `onDispose()`).
- Reuse the project's text/components instead of raw `Text().font().foregroundColor()` chains; use
  `.task` (not `.onAppear`) for async tied to a view's lifetime; mutate state on `@MainActor`; use
  self-documenting names (`isLoadingDecks`, not `loading`); comment only non-obvious "why".

## Git

- **Branch off `main` before touching anything.** Never commit onto `main` — start a
  `feat/…` / `fix/…` branch first, even for a one-line change.
- **Finish the work by opening a PR.** Push the branch and `gh pr create` against `main`; the
  change isn't delivered while it only exists locally.
- **Always use atomic commits.** Each commit should capture one logical, self-contained change.
  Don't bundle unrelated changes (e.g. a feature plus a refactor plus a formatting sweep) into a
  single commit — split them so each commit can be reviewed and reverted independently.
- **Don't run lint/build after every small edit.** Builds and lint (`./gradlew detektAll`,
  `assembleDebug`, etc.) are slow — run them at the end of a plan or at strategic checkpoints, not
  continuously. Verify at those points, then commit.
- **Verify UI changes on a device with `android-cli`, not just by building.** `android run --apks
  composeApp/build/outputs/apk/debug/composeApp-debug.apk`, drive the screen with `adb shell input
  tap`, read the result with `android layout` (flat JSON list, fastest way to assert on text) and
  `android screen capture -o <file>` for the look of it. A green `assembleDebug` says nothing about
  what the screen renders.
- Write focused, descriptive commit messages that explain the change and its rationale.
- **Use commit history as context when investigating why a change was made.** Before changing or
  reverting code, check `git log`/`git blame` (e.g. `git log -p <file>`, `git blame -L`) — the commit
  message often records the rationale and avoids re-introducing a bug a prior commit fixed.

## Where to read before starting work

- `docs/Architecture.md` — always. §4 (shared layering), §7 (Pubky, Nexus tag indexing, Homegate signup), §8 (homeserver layout, chunking, quota, SRS), §12 (what is still open).
- `docs/specs.md` §5–§10 — for any import/triage/commit work; §6 and §9 are the parser test matrix.
