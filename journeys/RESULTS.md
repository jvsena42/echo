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

## 02 — Paste-to-Import → publish — ✅ PASS

| Step | Result |
| --- | --- |
| Decks tab → "Paste to import" | PASSED |
| Paste 3 comma-separated lines | PASSED — parser auto-detected "comma", "3 cards", live preview (dog→cachorro, cat→gato, bird→passaro) |
| Next → publish screen, enter title "Animals PT" | PASSED — "3 cards ready" |
| Publish deck | PASSED — 4 `put_with_session` writes to the homeserver (3 cards + manifest) succeeded; `SUCCESS deckId=pv0b3aq0ruz6` |
| Undo window | PASSED — "Deck published! … Undo (6s)" countdown + Done (spec §5.6) |
| Done → deck detail | PASSED — "Animals PT", Total 3 / Due 3, owner edit/delete/share controls |

## 03–06 — runnable

With sign-in and homeserver writes working, the remaining journeys (study loop, discover,
deck manage/delete, profile/settings/sign-out) are runnable on the emulator. Drive
`journeys/03…06.xml` with the `android` CLI (`android screen capture` + `adb shell input`).
