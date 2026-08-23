# Android journey results

Run against the Loopky debug APK on the `emulator-5558` Pixel emulator with Pubky Ring
installed (staging identity), 2026-06-13.

## 01 — Onboarding / Pubky Ring auth — ✅ PASS

| Step | Result |
| --- | --- |
| Launch Loopky, onboarding shown with "Sign in with Pubky Ring" | PASSED |
| Tap sign in → Pubky Ring opens with the authorization prompt | PASSED — staging identity, relay `httprelay.pubky.app/inbox`, capabilities `/pub/loopky/:rw` + `/pub/pubky.app/:rw` |
| Approve in Ring | PASSED — "Authorization Successful" |
| Loopky completes sign-in | PASSED — token decrypted → session exchange → session saved → Home shown greeting the real pubky (`pk:x1kwaq`). Session persists across restarts. |

**Earlier blocker (now fixed):** sign-in failed instantly with "auth request expired".
Root cause was a panic in the pubky SDK's HTTPS client — its `icann_http` client used
reqwest's default rustls config (rustls-platform-verifier), which on Android panics
("Expect rustls-platform-verifier to be initialized") because the native verifier component
isn't present. The panic killed the auth-relay poller, surfacing as `RequestExpired`.
Fixed by making the SDK pin bundled webpki roots for ICANN TLS (pubky/pubky-core#430),
consumed via the FFI fork's `[patch]`. Surfaced by adding logcat tracing + a panic hook to
the FFI (`init_logging`).

**Auto-return caveat:** Loopky appends Ring's `x-success`/`x-cancel`/`x-error`/`x-source`
callbacks (→ `loopky://login-callback`, `MainActivity` = singleTask) so Ring re-opens Loopky
after approval. On the installed Ring build the success screen shows an "OK" button and does
not fire `openXSuccess`, so the user taps back to Loopky manually — a Ring-side issue, not Loopky.

## 02 — Paste-to-Import → triage → publish — ✅ PASS

Re-verified on `emulator-5554` 2026-06-17 after adding the triage step + card options.

| Step | Result |
| --- | --- |
| Decks tab → "Paste to import" | PASSED |
| Paste comma lines | PASSED — parser auto-detected "comma", live preview |
| Next → **Review cards** triage | PASSED — `triage_card`, `triage_progress` "1 of 3", keep advances "2 of 3 · 1 kept", keeping the last card advances to Publish |
| Publish screen → **Card Options** | PASSED — Listen (`publish_listen_toggle`) + Speak (`publish_speak_toggle`) both ON by default |
| Publish deck | PASSED — "Deck published! … Undo (7s)" + Done; `listen_enabled`/`speak_enabled` serialized into the manifest |
| Done → deck detail | PASSED — deck "Sky", Total 2 / Due 2, In Your Library |

## 03–06 — runnable

The study loop, discover, deck manage/delete, and profile/settings/sign-out journeys remain
runnable on the emulator.

## 07 — Triage edit — ⏳ scripted (not re-run)

`journeys/07-triage-edit.xml`: edit a draft card's front/back in triage, keep, publish, and
confirm the edit persisted. Scripted; drive with adb.

## 08 — Image select — ✅ PARTIAL PASS

`journeys/08-image-select.xml`. On `emulator-5554` 2026-06-17: the cover sheet opens from
`publish_cover_change` with `image_search_input` + `image_pick_gallery`; since
`UNSPLASH_ACCESS_KEY` is blank in `local.properties`, the web grid shows the gallery-only hint
("Search the web or pick from your gallery") as designed. The gallery path uses the system photo
picker and is a manual check. Sheet test-tags surface correctly (the sheet content sets
`testTagsAsResourceId` since `ModalBottomSheet` renders in a separate window).

Re-run on `emulator-5554` 2026-08-14 **with a real `UNSPLASH_ACCESS_KEY`** — the first time the
web grid path has actually been exercised. The grid populates, every cell shows its photographer
over a gradient scrim, and `image_credit` reads "Photos from Unsplash" before a selection and
"Photo by NIR HIMI on Unsplash" after one. Both links open Chrome at
`unsplash.com/@nirhimi?utm_source=loopky&utm_medium=referral` and
`unsplash.com/?utm_source=loopky&utm_medium=referral` respectively, and Done still applies the cover.
The `download_location` ping is covered by `UnsplashClientTest` (exact URL + auth header) rather
than observed on the wire.

## 09 — Speak study — ✅ PARTIAL PASS

`journeys/09-speak-study.xml`. On `emulator-5554` 2026-06-17: studying the speak-enabled "Sky"
deck shows the back-card `study_speak` button; tapping it raises the Android RECORD_AUDIO
permission dialog at the right time. This emulator image has no on-device speech recognition
service, so `SpeechRecognizer` reports unavailable and the flow returns to the card without
crashing — the Listening/Correct/Wrong outcome is a manual check on a device with Google speech.

## 10 — Listen/Speak fixes + design polish — ✅ PASS (re-run 2026-06-17, emulator-5554)

Verified after the Listen/Speak + fidelity fixes (RECORD_AUDIO revoked first to test the fresh
grant path):

| Step | Result |
| --- | --- |
| Study front card (`w1CAm`) | PASSED — word + "Tap card to reveal answer" only; **no Listen/Speak on the front** (superseded — see journey 12) |
| Reveal back (`aLoMj`) | PASSED — **"Listen"** pill (peach) + **"Speak"** pill (purple); names + colors now match the design (previously both read "Speak") |
| Tap **Speak** → mic permission | PASSED — "Allow Loopky to record audio?" dialog appears (the user-reported "permission not requested" bug is fixed) |
| Grant → recognition unavailable | PASSED — Toast "Speech recognition is unavailable on this device" shows instead of silently doing nothing |
| Paste preview (`MJ1SR`) | PASSED — bottom orange **Next** button shown once parsed |
| Publish (`yFOOS`) | PASSED — peach "N cards ready" badge with solid orange check; white card fields; **Listen/Speak option rows have leading icons** (peach headphones / purple mic) |
| Cover image sheet (`OQ2QL`) | PASSED — **Done** is now a pill (disabled-grey until a selection) |

Speech recognition itself still needs a device/emulator with Google speech for the
Correct/Wrong outcome.

## 11 — Listen/Speak use the deck's language — ✅ PASS (2026-08-22, emulator-5554)

The point of the change is which *language* the engines get, and the emulator's device locale is
`en-US`, so a Spanish deck is exactly the case that used to be wrong.

| Step | Result |
| --- | --- |
| Study a deck published before the language pair existed | PASSED — card back shows **neither** Listen nor Speak, though its manifest carries `listen_enabled: true`. No fallback to the phone's locale |
| Open that deck in the editor | PASSED — the **Card Options** block is present (it did not exist on this screen before); both toggles read **off**, matching what the deck actually does |
| Toggle Listen on | PASSED — the two language rows appear with the hint "Listen and Speak need to know what language each side is in, or the phone reads the card in your own accent." |
| Tap **Save** with no languages picked | PASSED — save refused, stays on the editor, "Pick a front and back language to use Listen or Speak." shown under the pickers |
| Open the Front language picker | PASSED — populated from the **engine's installed voices** (`tts.availableLanguages`), not the fallback list: Arabic → Bodo → … → Spanish (Spain) |
| Pick front `en-US`, back `es-ES`, Save | PASSED — returns to deck detail; reopening the editor shows both languages persisted, so they round-tripped through the manifest |
| Study the deck again, reveal the back | PASSED — **Listen** appears. Speak stayed absent, correctly: the editor had loaded `speak_enabled` folded through `speechReady`, so only Listen was turned on |
| Tap **Listen** | **PASSED — the acceptance test.** logcat: `GoogleTTSServiceImpl: Synthesis request for locale spa-ESP and name es-ES-language`, on a device whose locale is `en-US`. Before this change `setLanguage(Locale.getDefault())` made that `eng-USA` |
| Enable Speak too, save, restudy | PASSED — card back shows both **Listen** and **Speak** |

Two things this run did **not** prove:

- **The recognizer's language.** This emulator image still has no speech recognition service
  (journey 09), so `EXTRA_LANGUAGE` reaching the recognizer is covered by unit tests and the
  ViewModel effect assertions, not observed on the wire.
- **The `loopky-lang-*` tag records.** The saves logged no `syncTags` failures, which is the only
  positive signal the client emits, but the records were not read back off the homeserver. The
  write/diff behaviour is covered by `DeckRepositoryTagSyncTest`.

Unrelated pre-existing noise seen during the run: `listFollowed: 3uyducpmnylw unreadable — 404`.

## 12 — Listen/Speak on both card sides — ✅ PASS (2026-08-22, emulator-5554)

Restores the rule that both card faces carry a Speak button, which an earlier mockup had
overridden — so the journey 10 row above no longer holds.

| Step | Result |
| --- | --- |
| Study front card | PASSED — **Listen** (peach) and **Speak** (purple) both present, with "Tap card to reveal answer" still below the card |
| Tap **Listen** on the front | PASSED — logcat `Synthesis request for locale eng-USA`, the deck's **front** language |
| Reveal the back | PASSED — both buttons still present |
| Tap **Listen** on the back | PASSED — logcat `Synthesis request for locale spa-ESP`, the deck's **back** language |

So each side is read in its own language rather than one tag being fixed per deck.

Pronunciation practice on the front targets the front text (unit-tested: grading it against the
back would mark every front attempt wrong), but as with journeys 09/11 the recognizer itself is
unexercised on this emulator image.

---

## UI/UX gap pass — 2026-08-13, `emulator-5554`, signed in as `pk:rc3omr…b4re3o`

Drove every flow end to end (onboarding → paste → triage → triage-edit → image picker →
publish → deck detail → editor → card editor → study → discover → friend profile → profile
→ settings → delete → offline) and fixed what it turned up. Correction to the notes above:
03, 05 and 06 all execute today — they were listed as merely "runnable".

### Fixed and re-verified on device

| Symptom seen while driving | Fix |
| --- | --- |
| Publish a deck → Decks tab says "No decks yet" until the process restarts | `DeckRepository.changes` invalidation signal; the deck now appears immediately |
| Delete a deck → grid still lists it; tapping it lands on "Deck not found" with **no back button**, only a Retry that can never work | list refresh, a back control on the error state, and Retry hidden when it cannot succeed |
| wifi/data off → Home shows "Nothing to study yet — create or import a deck", Decks shows "No decks yet" | `listByAuthor` no longer swallows transport failures into an empty list; both now show an error with retry |
| Home showed the zero-decks empty state after finishing a session | new "all caught up" state carrying the next due time |
| Onboarding printed `HTTP transport error: error sending request for url (https://httprelay.pubky.app/inbox/…)` verbatim | `ErrorReason` + per-platform copy; raw text stays in logcat |
| Sign-in failed twice then succeeded on an identical third attempt | bounded retry on the auth-relay poll, transport failures only — **wrong fix, reverted in #59; see the 2026-08-17 pass below** |
| Paste "Next" with an empty box and Publish with an empty title: nothing happened, no message | real disabled styling, CTAs enabled so validation can speak, Publish CTA pinned above the fold |
| Both Share buttons did nothing | one `Context.shareText` helper |
| Editor drag handle did nothing when dragged | move up/down buttons + real reorder (needed the card-order fix first); the handle now drags too |
| Decks search icon and "Recent" label were inert | wired to a real filter and sort |
| Separator chip looked tappable and was not | override sheet (spec §5.2) |
| Discarding a card in triage was irreversible | undo, including for the last card |
| Speak fired the mic prompt cold; permanent denial left it inert forever | rationale dialog + "Open settings" path |
| "1 cards" everywhere | `plurals.xml` — the app had **zero** plural resources |
| "Detected: em-dash" shown for a plain hyphen | label reads "dash" (`Separator.EmDash` buckets em-dash, en-dash and `" - "`) |
| Settings showed "Homeserver: Unknown" | resolved from the pkarr record; Ring's session payload has no `homeserver` field |
| Own pubky in add-friend → a live Follow button | self-detection |

### Found by code review, not by driving

**Saving the deck editor destroyed every card image, every audio clip and the deck cover.**
It rebuilt each card as `CardSide(text = …)` with `coverImageRef = null` and republished over
the manifest. Only reproducible on a deck that already has media, which is why no journey hit
it — hence the new `11-deck-editor-media.xml`. Also fixed: card order was `sortedBy { it.id }`
over random ids, so deck detail and the editor listed cards arbitrarily and reorder could not
have persisted.

### Journeys added

- `10-offline-errors.xml` — failures must not render as an empty library.
- `11-deck-editor-media.xml` — regression guard for the media-destroying save.
- `12-dead-controls.xml` — sweep of the controls that used to do nothing.

`02`, `04`, `05` and `09` gained assertions for the list-refresh, self-follow, delete-copy and
permission-rationale fixes. `05` could not have passed before this pass regardless of app
behaviour: `deck_delete_confirm` **was** set in code, but `AlertDialog` renders in its own
window so the nav-host root's `testTagsAsResourceId` never reached it. Same fix applied to the
add-friend sheet and both sign-out dialogs.

### Still manual / environment-limited

- Speech recognition outcomes: this emulator image has no on-device recogniser, so
  `SpeechRecognizer` reports unavailable and only the permission path is testable here.
- The system photo picker in `08` remains a manual check.
- The Pubky stack intermittently fails on this emulator with
  `Expect rustls-platform-verifier to be initialized` (the upstream issue noted above). It
  clears after toggling the radios. It classifies as a generic error rather than "offline" on
  purpose — it is a TLS-stack fault, and telling the user to check their connection would be
  misleading since retrying does not help.

---

## 01 re-run — auth-relay failure — 2026-08-17, `emulator-5554` (#59)

Re-ran journey 01 to verify #59. **The relay-failure branch passed; the happy path could not be
re-verified from a script** — see the flakiness note below.

### Verified

| Step | Result |
| --- | --- |
| Sign out → onboarding, radios off, tap sign in | PASSED — `beginSignIn` succeeds (the auth URL is generated locally), Ring opens |
| Loopky surfaces the real cause | PASSED — `complete: FAILED — PubkyError: Auth approval failed: … HTTP transport error … httprelay.pubky.app/inbox/y1N6EOpH…`. **No** `awaitApproval: transport failure … retrying`, **no** `No auth flow in progress` |
| On-screen copy | PASSED — "Loopky signs you in through Pubky's authorisation relay, and it isn't responding. Try again in a moment." |
| One-tap restart from the error state | PASSED — a single tap logs `state=Starting, calling beginSignIn` and mints a new secret; Ring reopens |

### Why the retry could never have worked

`await_auth_approval` in the FFI does `AUTH_FLOW.lock().take()` and
`PubkyAuthFlow::await_approval(self)` consumes the flow, so the first poll — success or failure
— tears it down. The 2026-08-13 row above credits the retry with a recovery that actually came
from the user re-tapping (a fresh `beginSignIn`). Independently, the SDK **already** retries the
poll: logcat under tag `pubkycore` shows
`Http relay inbox channel polling attempt 1/2/3 failed`. The app-level retry was redundant as
well as broken.

### The emulator flakiness, and what it is not

Six scripted sign-ins between 07:23 and 07:35 failed identically, every one ~5.9s after
`beginSignIn`. Ruled out: DNS resolves (`34.65.156.171`, **no AAAA record**, so not an IPv6
fallback stall), ICMP reaches the host, Chrome on the same emulator loads the relay and reports
"Connection is secure", no rustls panic in logcat, and a full `adb reboot` did not clear it.
From the host the inbox long-poll holds open ≥25s.

Then a **manual** sign-in at 07:39 succeeded on the same network — matching the report that
failures are rarer when a human drives it. So it is not the network configuration and not the
app build; the difference is something about scripted repetition (six fresh inbox channels in
twelve minutes, each failure opening another). Unexplained, and worth suspecting relay-side or
emulator-NAT limits before suspecting Loopky.

> **Superseded 2026-08-20 — it is the pacing, not the repetition.** See the note below.

**Consequence for anyone running 01: signing out is a one-way door on the emulator whenever the
relay is in this state.** Journey 01 now carries that warning and covers the failure branch.

---

## 01 — the "scripted sign-in fails" flakiness, explained — 2026-08-20, `emulator-5554`

The 2026-08-17 pass above left this "unexplained" and pointed at scripted *repetition*. On a
fresh session today, driving sign-in two ways back to back on the same network gives a cleaner
discriminator: it is the **delay between tapping sign in and approving in Ring**.

| Run | Cadence from `beginSignIn` to Authorize | Result |
| --- | --- | --- |
| 1 — verification between every step (layout dump, then tap) | ~15s | FAILED — "the authorisation relay isn't responding" |
| 2 — three taps chained, `sleep 2` then `sleep 1`, nothing in between | ~3s | **PASSED first try** |

That also explains the manual/scripted split the 08-17 note recorded without accounting for it:
a human taps through in a couple of seconds, and a journey runner that verifies each step does
not. It is consistent with the prior session's ~20 slow failures followed by a fast loop
succeeding immediately, and it means **journey 01's happy path cannot be driven by a runner that
dumps the layout between actions** — 01 now says so, with the chain and the coordinates.

Not proven to be a relay-side timeout — two runs is a discriminator, not a mechanism, and the
FFI's own poll (`Http relay inbox channel polling attempt 1/2/3 failed`) sits between the tap
and the error. But the actionable half holds either way: chain the taps, verify afterwards, and
on failure retry the whole chain rather than waiting inside it.

---

## 14 — File import — 2026-08-17, `emulator-5554`, signed in as `pk:bzbjrj…yhjzpo` (#55)

New journey `14-file-import.xml`, driving the presentation-layer work in #55. Fixtures already on
the device: `/sdcard/Download/japanese_core.apkg` (800 notes) and `anki_export.txt` (1,200 cards).

### Verified

| Step | Result |
| --- | --- |
| Decks CTA | PASSED — centred, tucked under the paste card, reads "Import from Anki or a file". It was left-aligned and stranded by the 20 dp section gap, saying only "Or import a file" |
| `.apkg` → summary | PASSED — "800 cards parsed", sample rows, `bulk_repick` present |
| Detected separator | PASSED — `bulk_separator_chip` reads "Separator: tab". The string `bulk_detected_separator` had existed unused since #49 |
| Separator override | PASSED — the chip opens **paste's** sheet (`separator_option` ×9); choosing "comma" re-parses to "0 cards parsed · 800 skipped" and disables Import |
| Skipped rows genuinely dropped | PASSED — the comma case is the regression in miniature: it reports 0 importable and blocks, where before the count stayed at 800 and `publish` then failed on the first empty side |
| Title prefill from `.apkg` | PASSED — `publish_title` opened as **"Japanese Core"**, the collection's own deck name. The file-name fallback would have given lowercase "japanese core", so this is the `decks` table being read, not the filename |
| Title prefill from `.txt` | PASSED — "anki_export.txt" → "anki export" (extension stripped, underscore to space) |
| Determinate publish progress | PASSED — `publish_progress` bar plus `publish_progress_label` observed advancing "Publishing 0 of 800 cards…" → "Publishing 800 of 800 cards…", with `publish_cancel` beside it. Previously an indeterminate spinner for the whole upload |
| 800-card publish | PASSED — deck detail showed "Japanese Core", Cards 800 |
| 1,200-card publish | PASSED |
| Wrong file type | PASSED — a `.png` gives "That's not a text file" + "Pick an Anki .apkg, or a deck exported as plain text", with a retry that reopens the picker. It used to decode to U+FFFD soup and parse into plausible junk cards |

### Not exercisable from a script: cancel during publish

`publish_cancel` renders and is reachable, but **the cancel itself could not be driven here**.
Against this homeserver an 800-card publish completes in roughly a second and 1,200 in not much
more, so the tap lands after the success screen has already replaced the button — `android layout`
costs about as long as the whole upload. It is not a defect in the control; there is simply no
window on a deck this size.

Covered by unit test instead (`PublishDeckViewModelTest.cancellingAPublishSweepsThePartialDeck`,
using a new `FakeDeckRepository.publishGate` to hold a publish open): cancel sweeps the partial
deck, leaves `isPublishing`/`isCancelling` false, and sets **no** error — the last of those being
the real hazard, since `DeckRepositoryImpl.publish` is a `runCatching` and hands a cancellation
back as an ordinary failure. Re-check by hand on a 20k-card import, where the window is tens of
seconds.

### Notes

- The app shows onboarding for a beat on cold start before the persisted session resolves. Not a
  regression — worth knowing, because a layout dump taken too early reads as "signed out".
- The 429-flakiness and rustls faults recorded above did not recur in this session.

## 15 — Pubky links — ✅ PASS

Run against the debug APK on `emulator-5554`, signed in as Cosmic-Crystal-Panda
(`rc3omr…b4re3o`), following Silver-Otter-Sparrow (`bzbjrj…yhjzpo`), 2026-08-18.

| Step | Result |
| --- | --- |
| `VIEW pubky://<their-pubky>` | PASSED — Silver-Otter-Sparrow's profile, with Follow + Copy + Share and a People card reading Following 1 / Followers 1 |
| Their Following list | PASSED — opens from the stat column and lists Cosmic-Crystal-Panda; tapping the row opens that profile |
| `VIEW pubky://<own-pubky>` | PASSED — "You" badge, no Follow button, Copy and Share centered on their own row |
| `VIEW …/decks/<deckId>/manifest.json` | PASSED — deck detail for "Japanese Core", 800 cards |
| Cold start via link | PASSED — force-stopped, then the link restored the session and still landed on the profile. Back returns to Home, so the link opens on top of the tabs rather than instead of them |
| Share own profile | PASSED — share sheet carries `Cosmic-Crystal-Panda on Loopky` above `pubky://rc3omr…`, not a bare key |
| Share sheet → Loopky | PASSED — Loopky is a target for `text/plain` and opens the profile the message points at, which is the path that matters since chat clients leave `pubky://` unlinkified |
| Shared text with no address | PASSED — toast "No Loopky link in that text…" rather than opening silently on whatever screen was already there |
| Deck link pasted into add-friend | PASSED — opens the **deck**. Previously the sheet sliced the URI by hand and could only ever reach its author |
| Free text pasted into add-friend | PASSED — inline `add_friend_error` instead of a tap that does nothing |

### Not exercised here

Deep link **while signed out**: the pending link is held until the nav host leaves onboarding, so
it should open right after sign-in. Not driven on device — this account stays signed in and
re-auth through Ring is flaky on the emulator (see journey 01).

### Pre-existing, unrelated to this change

`decksFromFollowing` logs `listByAuthor failed for pubkybzbjrj9a…hjzpo — unexpected 'pubky' prefix
in user id`. One follow record on this account's homeserver is keyed with a stray `pubky` prefix in
front of the z32 id, so it can never resolve; `following()` passes it through verbatim. Harmless to
the strips (the other followee still loads) but it inflates the follow count and spams the log.
Worth a separate fix — either dropping ids that are not shaped like a pubky when parsing the
follows listing, or finding whatever wrote that record.
