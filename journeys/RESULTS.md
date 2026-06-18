# Android journey results

Run against the Echo debug APK on the `emulator-5558` Pixel emulator with Pubky Ring
installed (staging identity), 2026-06-13.

## 01 — Onboarding / Pubky Ring auth — ✅ PASS

| Step | Result |
| --- | --- |
| Launch Echo, onboarding shown with "Sign in with Pubky Ring" | PASSED |
| Tap sign in → Pubky Ring opens with the authorization prompt | PASSED — staging identity, relay `httprelay.pubky.app/inbox`, capabilities `/pub/echo/:rw` + `/pub/pubky.app/:rw` |
| Approve in Ring | PASSED — "Authorization Successful" |
| Echo completes sign-in | PASSED — token decrypted → session exchange → session saved → Home shown greeting the real pubky (`pk:x1kwaq`). Session persists across restarts. |

**Earlier blocker (now fixed):** sign-in failed instantly with "auth request expired".
Root cause was a panic in the pubky SDK's HTTPS client — its `icann_http` client used
reqwest's default rustls config (rustls-platform-verifier), which on Android panics
("Expect rustls-platform-verifier to be initialized") because the native verifier component
isn't present. The panic killed the auth-relay poller, surfacing as `RequestExpired`.
Fixed by making the SDK pin bundled webpki roots for ICANN TLS (pubky/pubky-core#430),
consumed via the FFI fork's `[patch]`. Surfaced by adding logcat tracing + a panic hook to
the FFI (`init_logging`).

**Auto-return caveat:** Echo appends Ring's `x-success`/`x-cancel`/`x-error`/`x-source`
callbacks (→ `echo://login-callback`, `MainActivity` = singleTask) so Ring re-opens Echo
after approval. On the installed Ring build the success screen shows an "OK" button and does
not fire `openXSuccess`, so the user taps back to Echo manually — a Ring-side issue, not Echo.

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
| Study front card (`w1CAm`) | PASSED — word + "Tap card to reveal answer" only; **no Listen/Speak on the front** |
| Reveal back (`aLoMj`) | PASSED — **"Listen"** pill (peach) + **"Speak"** pill (purple); names + colors now match the design (previously both read "Speak") |
| Tap **Speak** → mic permission | PASSED — "Allow Echo to record audio?" dialog appears (the user-reported "permission not requested" bug is fixed) |
| Grant → recognition unavailable | PASSED — Toast "Speech recognition is unavailable on this device" shows instead of silently doing nothing |
| Paste preview (`MJ1SR`) | PASSED — bottom orange **Next** button shown once parsed |
| Publish (`yFOOS`) | PASSED — peach "N cards ready" badge with solid orange check; white card fields; **Listen/Speak option rows have leading icons** (peach headphones / purple mic) |
| Cover image sheet (`OQ2QL`) | PASSED — **Done** is now a pill (disabled-grey until a selection) |

Speech recognition itself still needs a device/emulator with Google speech for the
Correct/Wrong outcome.
