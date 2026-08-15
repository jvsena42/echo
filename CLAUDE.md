# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Loopky is a Kotlin Multiplatform flashcards app (iOS + Android) that fuses TinyCards-style playfulness, Anki-style spaced repetition, and Pubky-based decentralized identity/social graph. See `docs/specs.md` (Paste-to-Import primary flow), `design/DESIGN_GUIDELINE.md` (screens + design system), and `docs/Architecture.md` (technical architecture — this is the source of truth for module layout, layering, and open questions).

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

- `shared/src/commonMain/kotlin/com/github/jvsena42/loopky/` holds all cross-platform code:
  - `domain/model/` — pure Kotlin data classes (`Deck`, `Card`, `ImportDraft`, `SrsState`, `AppError`, etc.). No framework imports.
  - `data/repository/` — repository interfaces (all 8 in `Repositories.kt`: Identity, Deck, Card, Import, Media, Tag, Discovery, Srs), implementations under `data/repository/impl/`. **Repositories own the business logic** — parsing, triage, publishing, SRS grading, follow/unfollow, sign-in/out all live as methods on the relevant repo rather than in a separate use-case layer. **All 8 are implemented** (`IdentityRepositoryImpl`, `DeckRepositoryImpl`, `CardRepositoryImpl`, `ImportRepositoryImpl` — the paste parser, spec §6 rules + §9 edge cases —, `MediaRepositoryImpl`, `SrsRepositoryImpl`, `DiscoveryRepositoryImpl`, `TagRepositoryImpl`), plus `SessionRevalidatorImpl`. `TagRepositoryImpl` writes pubky-app-specs tag records to the homeserver and reads trending from the Nexus indexer (`data/nexus/NexusClient`, see Architecture.md §7.6). The impls are Pubky-only: they write/read through `PubkyClient` and hold an in-memory per-session cache. No SQLDelight yet — the app is not offline-first, Pubky is the single source of truth.
  - `data/pubky/` — `PubkyClient` interface + DTOs (`ManifestDto`, `CardDto`, `MediaRefDto` in `DeckDtos.kt`, `ProfileDto`) and path helpers (`PubkyPaths`, `Hashing`) that map between domain models and the on-homeserver JSON layout defined in `docs/Architecture.md §8.0`. `SessionProvider`/`MutableSessionProvider` is the tiny read-only abstraction repos use to author writes without depending on `IdentityRepository`. `SessionRevalidator` + `SessionRetry` + `SessionPayloadParser` handle expired-session retry.
  - `data/pubky/PubkyClient.kt` — the single interface that wraps `pubky-core-ffi-fork`. All Pubky calls must route through this. It is a **thin** 1:1 mirror of the FFI surface (keys, mnemonics, recovery, auth, records, DHT). Do not add deck/card concepts here — those belong in repositories. The `actual` impl is `AndroidPubkyClient` (androidMain); the iOS impl (`IosPubkyClient.swift`) is still a stub awaiting framework binding.
  - `data/storage/` — `SecureSessionStore` interface for persisting the signed-in `Session`, backed by the platform keystore via Liftric KVault (`AndroidSecureSessionStore` wraps EncryptedSharedPreferences; `IosSecureSessionStore` wraps Keychain). This resolves the secret-storage open question — see "Non-obvious rules" below.
  - `di/SharedModule.kt` — Koin graph binding repos, ViewModels, and `SessionProvider`; platforms override `PubkyClient` + `SecureSessionStore` via `PlatformModule.{android,ios}.kt`.
  - `presentation/` — KMP ViewModels, one per screen, each extending the multiplatform `androidx.lifecycle.ViewModel` (`viewModelScope`) and exposing `StateFlow<UiState>` + `SharedFlow<UiEffect>` (see "Coding conventions" below). **Implemented** across `onboarding/` (`OnboardingViewModel` + UiState/Effect), `home/` (`HomeViewModel`), `decks/` (`DecksLibraryViewModel`, `DeckDetailViewModel`, `DeckEditorViewModel`, `EditCardViewModel`), `import/` (`PasteImportViewModel`, `PublishDeckViewModel`), and `profile/` (`ProfileViewModel`). Coroutines + Koin are wired (no longer blocked).
- `shared/src/{android,ios}Main/` — `expect`/`actual` platform glue only (Pubky FFI, TTS, haptics, file I/O). Nothing else lives here.
- `composeApp/src/androidMain/` — Android app. Compose screens in `ui/`, Koin in `di/`, `MainActivity` as entry point. Uses Jetpack Navigation Compose.
- `iosApp/iosApp/` — iOS app. SwiftUI screens in `Views/`, `NavigationStack` in `Navigation/`, Koin bootstrap in `DI/`. Compose Multiplatform UI is **not** used for iOS screens.

### Non-obvious rules

- **Do not add Compose Multiplatform UI code for iOS screens.** The working assumption (see `docs/Architecture.md §12` open question #1) is native SwiftUI on iOS. `composeApp` is Android-only despite the name.
- **ViewModels live in `shared/commonMain`, not in platform modules.** Both Compose and SwiftUI screens consume the same VMs. No `@Composable` or `ObservableObject` in shared code.
- **Always import symbols; never reference them fully-qualified inline.** Add an `import` at the top of the file (e.g. `import androidx.compose.ui.graphics.Color`) and use the short name, rather than writing `androidx.compose.ui.graphics.Color` inline in a type or call. Applies to both Kotlin and Swift.
- **Native-first UI.** Prefer native platform components — **Material 3 Expressive** (`ShortNavigationBar`, `Scaffold`, `TopAppBar`, etc.; opt in with `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`) on Android, and native SwiftUI / Liquid Glass on iOS — over bespoke custom Composables/Views, **even if it diverges from the Pencil design**, so the app feels platform-native. Apply Loopky brand tokens (accent, type, radii) *to* native components rather than rebuilding chrome from primitives; build fully custom only where Loopky's identity needs it and no native equivalent exists (e.g. the study card flip). The custom `LoopkyTabBar` pill has been replaced by a Material 3 Expressive `ShortNavigationBar` (Android) and a native `TabView`/`UITabBar` (iOS). See `design/DESIGN_GUIDELINE.md §4`.
- **Pubky is the source of truth for published decks.** The app is not offline-first in v1 — repos talk directly to `PubkyClient` and keep only an in-memory cache for the session. A persistent SQLDelight cache may come later. There are no private/local-only decks in v1 (spec §11).
- **Homeserver layout is fixed.** Decks published under `/pub/echo/decks/{deckId}/{manifest.json, cards/{cardId}.json, media/{sha256}.{ext}}`. Manifest + one record per card + blob-per-media, sync driven by `updated_at`. Full schemas in `docs/Architecture.md §8.0`. Binary media is written raw via the FFI's `put_bytes_with_session`; reads come back Base64-encoded from the FFI transport and are decoded in `MediaRepositoryImpl`.
- **Paste-to-Import is the v1 primary import flow.** The implemented spine is `PasteImportViewModel` (parse + live preview) → `PublishDeckViewModel` (commit to Pubky). Every other import source (AI, OCR, URL) listed in spec §14 must reuse this same spine. Don't build parallel commit flows.
- **Parser rules are prescriptive.** The paste parser (on `ImportRepository`) must follow the exact rule order in spec §6 and the edge-case table in spec §9. Use them as the test matrix.
- **No use-case layer.** Don't introduce `*UseCase` interfaces or a `domain/usecase/` package. If a piece of logic doesn't fit any existing repo, extend the most relevant repo or add a new one — keep the surface area flat.
- **Pubky bindings are UniFFI-generated and checked in.** Android: `shared/src/androidMain/kotlin/uniffi/pubkycore/pubkycore.kt` + `shared/src/androidMain/jniLibs/`. iOS: `iosApp/iosApp/Frameworks/PubkyCore.xcframework` + `iosApp/iosApp/Pubky/pubkycore.swift`. Regeneration steps live in `docs/Architecture.md §7.4`; do not edit the generated files.
- **Session storage is resolved** via `SecureSessionStore` (Liftric KVault → Android Keystore / iOS Keychain). Persist the signed-in `Session` only through this interface — do not wire multiplatform-settings or ad-hoc storage for secrets. (Architecture.md §7.5 may still read as "unresolved"; the code is the source of truth here.)
- **The Android app is real and feature-built; iOS is drafted but not yet wired.** Onboarding → home → decks → paste-import → publish → profile all work on Android (Compose screens in `composeApp/src/androidMain/.../ui/`, nav in `ui/nav/`, DI in `di/`). The leftover `Greeting`/`Platform` template stubs still exist in `shared` but are no longer the running UI. iOS SwiftUI screens exist in `iosApp/iosApp/Views/` but are inert until the Koin bootstrap in `iOSApp.swift` is enabled (see Build & run).

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

- **Always use atomic commits.** Each commit should capture one logical, self-contained change.
  Don't bundle unrelated changes (e.g. a feature plus a refactor plus a formatting sweep) into a
  single commit — split them so each commit can be reviewed and reverted independently.
- **Don't run lint/build after every small edit.** Builds and lint (`./gradlew detektAll`,
  `assembleDebug`, etc.) are slow — run them at the end of a plan or at strategic checkpoints, not
  continuously. Verify at those points, then commit.
- Write focused, descriptive commit messages that explain the change and its rationale.
- **Use commit history as context when investigating why a change was made.** Before changing or
  reverting code, check `git log`/`git blame` (e.g. `git log -p <file>`, `git blame -L`) — the commit
  message often records the rationale and avoids re-introducing a bug a prior commit fixed.

## Where to read before starting work

- `docs/Architecture.md` — always. §4 (shared layering), §6 (Paste-to-Import state flow), §7 (Pubky open question), §12 (open questions blocking feature work).
- `docs/specs.md` §5–§10 — for any import/triage/commit work.
- `design/DESIGN_GUIDELINE.md` §6–§8 — for any screen or component work.
