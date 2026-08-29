# Journey results

Android runs first, then an iOS section at the foot of the file.

## Android

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

Re-run on `emulator-5554` 2026-08-28 for the pasted-link/pasted-image work, with a key
configured this time:

| Step | Result |
| --- | --- |
| Sheet shows the field, "From gallery" **and** "Paste" (`image_paste`) | PASSED |
| Typed address → grid replaced by `image_link_preview`, credit line gone, Done enabled once drawn | PASSED — `images.unsplash.com/photo-…` rendered in the pane |
| Typed address that is not an image (`example.com/not-an-image`) | PASSED — `image_link_error` shown, `image_sheet_done` stayed disabled |
| `image_link_clear` → field empties, grid and credit line come back | PASSED |
| Paste with an address on the clipboard | PASSED — address landed in the field and previewed; Done committed it as the card's front image |
| Paste with plain text on the clipboard | PASSED — stayed a search term, grid kept, no preview |
| Front pick must not carry into the back sheet | PASSED after the fix — back sheet opens empty, grid back, Done disabled. It failed before it: the sheet's ViewModel is the screen's, and the front's address was still there, committable |

**Pasting an image as bytes is out of scope for now — issue #168.** Reading a Chrome "Copy
image" clip froze the app: `ClipData.Item.coerceToText` on a `content:` uri opens and reads the
stream rather than formatting the uri, and it was being called on the main thread. Such a clip
is now identified from local clip metadata alone and refused with a message, with no provider
call at all. The bytes path itself could not be exercised here in any case — Chrome's "Copy
image" wedged this emulator four times, including once with Loopky not in the foreground and
Paste never pressed, so the AVD and not the app is what fails there. The empty-clipboard notice
is also unverified: `cmd clipboard clear` does not exist on this image.

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
| Study front card | PASSED — word + "Tap card to reveal answer" only; **no Listen/Speak on the front** (superseded — see journey 12) |
| Reveal back | PASSED — **"Listen"** pill (peach) + **"Speak"** pill (purple); names + colors now match the design (previously both read "Speak") |
| Tap **Speak** → mic permission | PASSED — "Allow Loopky to record audio?" dialog appears (the user-reported "permission not requested" bug is fixed) |
| Grant → recognition unavailable | PASSED — Toast "Speech recognition is unavailable on this device" shows instead of silently doing nothing |
| Paste preview (`MJ1SR`) | PASSED — bottom orange **Next** button shown once parsed |
| Publish | PASSED — peach "N cards ready" badge with solid orange check; white card fields; **Listen/Speak option rows have leading icons** (peach headphones / purple mic) |
| Cover image sheet | PASSED — **Done** is now a pill (disabled-grey until a selection) |

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

---

## #147 — Local keys, recovery restore, and backup (2026-08-26)

Driven on the Pixel_9 emulator against **staging**. Sign-out between runs via
`adb shell pm clear com.github.jvsena42.loopky`.

| Check | Result |
|---|---|
| Third door present on onboarding | PASSED — `onboarding_restore` renders below the two existing entry points |
| Restore with a valid phrase | PASSED — signs in and lands on home |
| One DHT lookup per attempt | PASSED — was two (~3s each); the pre-flight answer is now passed into sign-in |
| Bitcoin-seed warning | PASSED — permanent inline block above the field, not dismissible |
| Valid phrase with no account | PASSED — "This key has no account yet", derived pubky shown, "Check my recovery phrase again" ranked above "Register this key" |
| Ring-missing → local route | PASSED — `signup_create_locally_option` unlocks every method with Ring **disabled**, and prices are quoted |
| Fiat quote | PASSED — `Pay ₿ 10 (≈ US$0.01) once` on staging |
| Price test tags | FIXED — `signup_method_*_price` never existed; the tag was written as a literal `$testTag_price` |
| Landscape onboarding | FIXED — the third button pushed the policy consent (which gates the primary button) off the bottom of the bounded two-pane panel, clipping in silence. The panel now scrolls |
| Medium width (700dp) | PASSED — stacked layout, every control reachable |

### Found only on device

**The grant auth flow does not work against Synonym's staging homeserver.** A first test key
happened to succeed, so it was briefly believed verified; a second produced
`403 Forbidden - Writing to directories other than '/pub/' is forbidden` from the grant flow's
`export_grant_session_secret`. `signIn`/`signUp` now bind to the **cookie** variants, which sign in
cleanly and answer an unregistered pubky with an honest 404. Revisit with #130.

**A pkarr homeserver record can outlive the account it points at.** `getHomeserver` answered
*Registered* for a key the homeserver then 404'd at signin — so the pre-flight cannot rule this out,
and the 404 has to be classified as "no account" on the restore path. It was rendering
`ErrorReason.NotFound`, whose copy is *"This deck no longer exists"*: deck copy, on a
recovery-phrase screen.

### Not exercised

**A real local signup end-to-end.** It needs a signup token, and the three ways to get one cost an
SMS attempt, sats, or an invite code — none of which were available in this session. Everything up
to the token is verified (the gate flip, prices, method selection); the redemption step itself,
the backup screens, and the sign-out guard are covered by unit tests only. Journey 22 is written to
be run with a real invite code.

**The tablet AVD.** Landscape and Medium width were exercised by rotating and resizing the phone,
which covers the Expanded and Medium code paths. `Pixel_Tablet` itself was not booted.

### Sign-in options restyled (2026-08-26)

Driven on the Pixel_9 (phone) and **Pixel_Tablet** emulators.

| Check | Result |
|---|---|
| Phone portrait | PASSED — filled "Continue with Pubky Ring" above outlined "Use a recovery phrase or file", marks aligned, both labels complete |
| Tablet landscape (1280×800dp, Expanded) | PASSED — two-pane; buttons, notice, create link and consent all visible with room to spare |
| Tablet portrait (800×1280dp, Medium) | PASSED — stacked; everything visible |
| Landscape **phone** (Expanded width, ~445dp height) | Content exceeds the bounded panel and is reached by scrolling. The consent checkbox gates the primary button, so this is the one width where the screen can look finished while the orange button is disabled |

**Side-by-side buttons were tried and reverted.** At any panel width this layout can spare from the
hero, "Continue with Pubky Ring" and "Use a recovery phrase or file" truncate to "Continue with
Pubky" and "Use a recovery". A clipped label reads as the whole label, which is worse than a
scroll.

`emu rotate` is what actually rotates the tablet — `settings put system user_rotation` did not take
on it, and `wm size` overrides did not reach the app either.

---

# iOS

First iOS runs ever recorded. Driven on the **iPhone 17 simulator (iOS 26.5)** via
`xcodebuildmcp`, signed in against the **real staging homeserver**, 2026-08-29.

## How sign-in works on a simulator

Pubky Ring cannot be installed on a simulator, so `pubkyauth://` has nowhere to go and the
deeplink path is a dead end there. Sign in by **scanning the QR from a real phone**: tap "Sign in
with Pubky Ring" and the QR sheet is raised automatically, because `ringInstalledHere` is false.
The relay poll underneath is the same one the deeplink uses.

Without this, nothing needing a session could be verified on iOS at all — which is why the QR
landed in the same PR rather than waiting for the iPad work (#173).

## Before this run, iOS did not build

`main` did not compile: `onDispose()` had been removed from the shared ViewModels, `onSignInClick`
had gained a mandatory parameter, and `ErrorMessages.swift` was written against camelCased Kotlin
enum spellings that never existed. Once compiling, it **segfaulted on every launch** from a Koin
cycle (`BackgroundTasks → DeckRepository → BackgroundTasks`) that only iOS entered. Treat every
result below as new ground.

## What passed

| Flow | Result |
| --- | --- |
| Sign in — QR scanned from a phone | ✅ PASS — relay poll → session → Home greeting the real pubky. Survives reinstall (Keychain). |
| Paste-to-Import — parse | ✅ PASS — 2 colon lines → "Detected: colon", live preview, Next enabled. Comma input the shared rules decline reports "single column" with both warnings and Next stays disabled. |
| Publish | ✅ PASS — title → Publish → "Undo (1s)" → Done → deck detail: "Spanish basics", 2 cards, 2 new, both rows, You badge. Written to the homeserver. |
| Edit a card | ✅ PASS — change the back → Save → deck detail reads "Hola / hello there" back from the homeserver. |
| Deck editor — study options | ✅ PASS — Listen/Speak/Type/Both directions all present; enabling Type saves to the manifest. |
| Study loop | ✅ PASS — "1 of 2" → reveal → Again <10m / Hard 1d / Good 3d / Easy 7d → grade both → Done. Deck detail then reads 0 due, 0 new, **43% mastered**, read back from the homeserver. |
| Type the answer | ✅ PASS — wrong answer reports "Not quite — try again", keeps the card shut, leaves the typed text in the field, offers no grade; correct answer opens the card with "Correct!" and all four grades, none pre-selected. Give up opens the card and reports nothing. |
| Listen | ✅ PASS (audible check only) — plays through `SpeechSpeaker` in the deck's declared language. Works without a Kotlin `IosSpeaker`. |
| Settings | ✅ PASS — real pubky and homeserver; raising Good 3d → 5d writes the synced record and the study screen's button then reads "Good · 5d". Restored to 3d afterwards. |
| Profile | ✅ PASS — kfezy1, 1 deck / 2 cards / 0 due, people chips, edit sheet opens. |
| Someone else's deck from Discover | ✅ PASS — this is the `authorPubky` fix. Opens with cover, description, tags, 24 cards and the card list; it resolved to `NotFound` before. |
| Friend profile | ✅ PASS — Discover → person → 6 decks, 17 cards, 1 following, 1 follower, Follow button, deck grid. |
| Follow list | ✅ PASS — Profile → Following → self-addressed empty state. |

## Not exercised, and why

- **Sign out** and **delete account** — both were built and left untapped on purpose: the session
  was needed for the rest of the run, and re-authenticating needs a phone to scan with.
- **Follow / unfollow** — writes to a real social graph; not exercised on someone's live account.
- **Search** — the sheet opens and accepts a query, but a people search against the staging Nexus
  indexer sat spinning and never returned. Not investigated; `SearchScreen` predates this work.
- **Speak** — no `SpeechRecognizer` binding on iOS, and `Info.plist` has no microphone or
  speech-recognition usage strings. The button is hidden rather than shown-and-inert.
- **Background tasks** — `BGProcessingTaskRequest.submit` throws on a simulator.
- **iPad / any width but a phone** — tracked in #173.

## Known noise

`fetchProfile: FAILED — 404` is logged at error level with a stack trace on every sign-in for an
account with no `pubky.app` profile. Harmless and handled, but it was the only `E/` line in an
otherwise clean session and it buries anything that matters. Filed as #174.

## Anki `.apkg` import — ✅ PASS (2026-08-29)

Read end to end on the iPhone 17 simulator against the real homeserver, from a fixture carrying
four notes, a two-template (reversed) note type, a `0x1F`-nested deck name, a protobuf deck
description, and Anki's legacy `collection.anki2` stub alongside the real `collection.anki21`.

| Step | Result |
| --- | --- |
| Open the file | PASSED — zip parsed by `ZipReader` over the system zlib, collection read through the SQLite cinterop |
| Prefer the real collection | PASSED — `collection.anki21` chosen over the legacy stub |
| Parse | PASSED — "4 cards parsed · Separator: tab" |
| Field names | PASSED — "Fields: Spanish → English", read from the `fields` table |
| Deck name | PASSED — "Spanish Nouns", the **root** of `Spanish Nouns␟Beginner`, not the leaf |
| Deck description | PASSED — "Basic Spanish nouns.", decoded from the protobuf `decks.kind` blob |
| Reversible detection | PASSED — two templates, so "Both directions" arrived **on** as a suggestion |
| Suggested tags | PASSED — `#spanish` `#animals`, from the note tags |
| Triage → publish | PASSED — Approve all → Publish → Undo window → deck detail: 4 cards, 4 new, all four rows |

**Two crashes were found and fixed on this path**, both on the first real file:

1. `IosAnkiDb.open` kept the connection handle as the `CPointerVar` it was written into, which
   `memScoped` frees on exit — so every later call read freed memory and `close` segfaulted.
   Covered now by `IosAnkiDbTest`, which fails with signal 11 if the bug is reintroduced.
2. `String(format:)` with Android's `%1$s`. Java's `%s` is a **C string** on iOS, so a Swift
   `String` passed to it faults in `strlen`. Nine ported strings carried it. Every `%d` was also
   widened to `%lld`, since a Swift `Int` is 64-bit.

The second is a whole class of bug, and the catalog is now audited for it rather than trusted —
see the driving notes.

**Not reached by the picker.** The Files picker runs out of process, so the file-choosing tap
cannot be automated. The run above went through "Open with" instead (`simctl openurl` with a
`file://` URL), which the app now handles — Android has the same entry point via `ACTION_VIEW` /
`ACTION_SEND`. The picker itself was exercised by hand.

## Driving notes

- `ui-automation type-text` into a SwiftUI `TextEditor` drops characters. Some of that was ours —
  a field bound through the ViewModel round-trips every keystroke and races the next, now fixed —
  but the tool is still lossy. Prefer `--replace-existing`, re-read the element ref between steps
  (it changes), and use `key-press --key-code 40` for newlines; multi-line `--text` is rejected.
- **Format strings ported from Android are a crash, not a typo.** Java's `%1$s` is a C string on
  iOS and faults in `strlen`; a Swift `Int` handed to `%d` reads the wrong width. Neither the
  compiler nor SwiftLint sees either. Check every `%s` becomes `%@` and every `%d` becomes `%lld`
  when porting a string, and check the *argument order* — `edit_card_context` puts its counts
  before its title, and getting that wrong is also a segfault.
- A bare `.onTapGesture` is invisible to both VoiceOver and the automation snapshot. The study
  card and Discover's person tile both needed an explicit accessibility action before they could
  be driven — worth checking on anything new that is tappable but not a `Button`.


## #149 — Signup, backup and restore on iOS (2026-08-29)

The identity cluster had **no iOS counterpart at all** before this run: fourteen screens, none of
them ported. Driven on the **iPhone 17 simulator (iOS 26.5)** against the real staging homeserver.

| Screen | Result |
| --- | --- |
| Onboarding → Create account | ✅ PASS — after the nav fix below; the method picker shows live Homegate pricing ("Pay ₿ 10 (≈ US$0.01) once") |
| SMS — number entry | ✅ PASS — "Send code" appears only once the number validates. **No code was sent**: a bogus number would burn a real SMS attempt against the homeserver |
| Lightning | ✅ PASS — live invoice created; QR on a white plate, BOLT11 shown, "Open in wallet" / "Copy invoice", "Waiting for payment…" |
| Invite code | ✅ PASS — renders, uppercase field, submit gated. **Not redeemed** — no code to hand |
| Local signup (terminal step) | ⚪ NOT REACHED — needs a spent token, so it needs a real SMS/sats/invite |
| Restore with a phrase | ✅ PASS — derived the key, signed in, landed on Home. See the flake note below |
| Restore with a file | 🟡 PARTIAL — the screen renders and "Choose file" opens the picker, which runs out of process and cannot be automated (same limit as `.apkg`). The decrypt path is unexercised on iOS |
| Backup menu | ✅ PASS — phrase card ticked ✓ Done for a restored key, Ring card says "Not installed — we'll take you to it", "I'll do this later" present |
| Recovery phrase | ✅ PASS — twelve words blurred, reveal pill over the grid, Continue disabled until revealed |
| Confirm quiz | ✅ PASS — Word 4 / 7 / 10; a wrong answer says so and **clears every selection**; a right one returns to the menu |
| Encrypted file | ✅ PASS to the picker — passphrase masked, strength reads "Strong", exporter opens with the blob and a `.pkarr` name. The confirmed-write branch needs the out-of-process picker |
| Export to Pubky Ring | ✅ PASS — not-installed path, "Install Pubky Ring", and the subtitle that says Loopky keeps its own copy |
| Backup from Settings | ✅ PASS — the permanent "Back up your account" row appears for a Loopky-held key and is **absent** for the Ring-held account, which is what `holdsOwnKey` is for |
| Profile backup nag | ✅ PASS by absence — no nag for a Ring-held key, and none for a restored one (already backed up) |
| Sign out | ✅ PASS — confirm dialog, then back to onboarding |

### Three bugs found by driving it, all invisible to the build

1. **Every push in the signed-out flow was dropped.** `identityPath` was appended to inside a
   `NavigationStack` with no path binding, so "Create account" and "Use a recovery phrase or file"
   did nothing at all. Split into two stacks — the signed-in side needs
   `navigationDestination(item:)`, the signed-out side needs a path.
2. **Disabled buttons looked enabled.** A `ButtonStyle` does not react to `.disabled()` on its
   own, so every disabled primary in the app rendered at full strength and invited a tap it would
   swallow.
3. **A passphrase field could not be typed into.** The accessibility identifier sat on the row
   around the field rather than the field, so automation resolved it and typed into nothing. Worth
   remembering: an identifier on a container is not a text target.

### One unexplained failure, recorded rather than closed

The **first** restore-with-phrase attempt on a fresh install returned "That's not a valid recovery
phrase". Two later attempts with the identical phrase, on the same build, signed in. Diagnostic
logging showed `validate_mnemonic_phrase` answering `true`, so the phrase was never the problem
and the failure is downstream of it. Not reproduced since; do not assume it is fixed.

### Not exercised, and why

- Anything needing a **spent signup token** — local signup, and therefore the "a key nobody has a
  copy of" state that the Profile nag and the unbacked sign-out warning are written for.
- The **confirmed-write** halves of file backup and file restore, and the **Ring-installed** half
  of Ring export: all three end in an out-of-process system UI the automation cannot reach.

## #113 — closing the iOS parity audit (2026-08-29)

The last of what #113 listed as Android-only. Driven on the **iPhone 17 simulator (iOS 26.5)**
against the real staging homeserver, signed in by restoring a recovery phrase — no Pubky Ring scan
needed any more, which is what made a sign-out cheap enough to test guest mode at all.

| Flow | Result |
| --- | --- |
| Cold start with no session | ✅ PASS — lands in the browsing shell: Discover, no tab bar, trending tags and decks loading |
| Guest → deck detail | ✅ PASS — Follow, Clone and "Try these cards" all present without an account |
| Guest → Follow | ✅ PASS — "Keep this deck" prompt, naming what signing in unlocks, with a real CTA |
| Guest → study preview | ✅ PASS — 10 sampled cards, images, "Next card" in place of the grade row |
| Explicit sign out | ✅ PASS — lands on onboarding, **not** the browsing shell |
| Tag chip → tag browse | ✅ PASS — `#brasil` lists six decks with covers, counts and authors |
| Follow a deck (signed in) | ✅ PASS — pill flips, announce prompt offers Share / Don't ask again / Not now, post goes out |
| Speak | ✅ PASS to the microphone — button appears only on a deck with a declared pair, permission prompts read correctly, the sheet listens. A simulator has no useful audio in, so no transcription was graded |
| Deck library search + sort | ✅ PASS — filtering hides non-matches and says so; the header echoes the chosen sort |
| Today | ✅ PASS — headlines the study target, shows the goal tally, and the caught-up card replaces the hero when nothing is due or unseen |
| Avatars | ✅ PASS — real profile pictures on Discover, initials where none is set |
| QR scan in search | ⚪ NOT REACHED — a simulator has no camera |
| Drag-to-reorder | ⚪ NOT REACHED — the automation cannot express a long-press drag; the VoiceOver move actions beside it are the reachable half |

### Six bugs found by driving it, none visible to a build

1. **Every push on the signed-in side was dropped.** `navigationDestination(item:)` drives one
   destination at a time, so assigning a new route while one was on screen left it in place —
   deck detail → study, → preview and → clone all did nothing, silently. It is a path now.
2. **Tag chips were not controls.** An `onTapGesture` is invisible to VoiceOver *and* to the
   automation snapshot, so the chips were reachable by a finger and by nothing else.
3. **Avatars never resolved.** A pubky.app profile stores its picture as a `pubky://` file URI
   that `AsyncImage` cannot fetch and fails silently on. `avatarDisplayUrl` has been sitting in
   `commonMain` for exactly this, with a KDoc naming `AsyncImage`, and iOS never called it.
4. **Today told you to stop.** The hero showed the bare due count, so a library with nothing
   overdue and unseen cards left read "0 cards to review" above a Start studying button.
5. **The announce prompt had no "Not now"** — a `confirmationDialog`'s cancel button is detached
   from the action list and did not render.
6. **Following a deck left `canPreview` stale**, in shared code, so a deck you had just kept still
   offered "Try these cards". Android had this too.

Plus three strings handed to `Text(_:)` as bare keys while their catalog values take arguments, so
the screen showed the specifier itself. Worth re-running that check whenever strings are ported:
walk the catalog for values containing `%`, then grep for bare `Text("key")` uses of them.

### Search does not hang

The previous audit recorded search "sat spinning and never returned" against the staging indexer.
It was not reproduced: "spanish" returns deck results in a couple of seconds. Treat the earlier
note as fixed by something in between rather than as an open blocker.

## iPad — adaptive layouts (#173) — 2026-08-29

Three width classes driven on three simulators, all iOS 26.5, via `xcodebuildmcp`. Signed in
against the real staging homeserver — the iPad by QR scanned from a phone, the iPhone from the
session it already held.

| Simulator | Window | Class | What it exercises |
| --- | --- | --- | --- |
| iPad Pro 13-inch (M5), landscape | 1366pt | expanded | every two-pane layout, the sidebar, the grade column |
| iPad mini (A17 Pro), portrait | 744pt | medium | 3-column grids, no two-pane, the QR **sheet** |
| iPhone 17 | 393pt | compact | the regression pass — nothing may have changed |

### Expanded — iPad Pro 13" landscape

| Screen | Result |
| --- | --- |
| Navigation | ✅ PASS — `.sidebarAdaptable` renders the four destinations as a Liquid Glass sidebar, and the toggle collapses it to the floating top tab bar. Order and identifiers unchanged. |
| Home | ✅ PASS — the due-today hero in a 340pt column beside "Today's decks" two across, greeting spanning both. |
| Deck detail | ✅ PASS — cover/title/author/tags/stats in a 360pt column, the card list beside it, the header bar drawn once across the top, Study capped at 520pt and centred. |
| Profile | ✅ PASS — avatar/name/Edit on the left; stats, people chips, pubky.app CTA and Sign out on the right. |
| Decks | ✅ PASS — 4-column grid, content capped at 1160pt and centred. |
| Discover | ✅ PASS — 4-column grid, topic strip, guest banner. |
| Study session | ✅ PASS — card held at 640pt with the four grades standing in a 200pt column beside it, each still thumb-sized and each keeping its `study_*` identifier. |
| Onboarding | ✅ PASS — hero left, sign-in right, both vertically centred. |
| Sign-in handoff | ✅ PASS — picked `AnotherDevice` from the window and rendered the QR **inline in the sign-in column**, not as a sheet. Scanned from a phone; the relay poll completed and the session landed on Home. |

### Medium — iPad mini portrait

| Screen | Result |
| --- | --- |
| Discover | ✅ PASS — 3 columns, not 2 and not 4. |
| Onboarding | ✅ PASS — stacked, and the QR arrives as a **sheet**: the handoff is still `AnotherDevice` (an iPad's key lives on its owner's phone at any width) but there is no second column to render it in. |

### Compact — iPhone 17, the regression pass

Home, Decks, Profile, deck detail and the study loop are pixel-unchanged: stacked layouts,
2-column grid, grades in a row under the card, tab bar at the bottom. Every ceiling added by this
work is above the phone's window, so none of them binds there.

### Two things worth knowing

**In sidebar mode the tab rows are invisible to `ui-automation snapshot-ui`.** The system sidebar
exposes only its `ToggleSidebar` button to the runtime snapshot; collapse it to the tab bar and all
four destinations appear as `tab` targets. Nothing in Loopky controls this — it is how SwiftUI
renders the sidebar — but a script driving the tabs on an iPad has to collapse the sidebar first.
VoiceOver reads the sidebar rows normally.

**The QR gate had a latent hole this exposed.** It asked only `!ringInstalledHere`, which was right
while every sign-in was `ThisDevice`. An iPad now hands off to another device, so the ViewModel
deliberately fires no deeplink — and on an iPad *with* Ring installed the old condition would have
left the screen waiting forever with nothing on it. The question is whether the deeplink was fired,
which is both halves of the ViewModel's own rule.

### Not exercised

- **Slide Over and Split View** — the simulator cannot be driven into either from the automation,
  and the rotation keystroke does not reach it either. The code path is the same one the iPhone
  proves: a narrow pane reports a `.compact` size class, which pins the width class to compact
  ahead of any width reading.
- **The stale iOS test-account phrase.** The recovery phrase recorded for the disposable simulator
  account is rejected by the local BIP39 checksum, so restore-from-phrase could not be used to sign
  the iPad in. The QR scan was used instead.
