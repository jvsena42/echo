# Android journey results

Run against the Echo debug APK on the `emulator-5558` Pixel emulator with Pubky Ring
installed (staging identity), 2026-06-13.

## 01 — Onboarding / Pubky Ring auth — ✅ UI verified, ⚠️ blocked downstream

| Step | Result |
| --- | --- |
| Launch Echo, onboarding shown with "Sign in with Pubky Ring" | PASSED |
| Tap sign in → Pubky Ring opens with the authorization prompt | PASSED — Ring showed the staging identity, relay `httprelay.pubky.app/inbox`, and the requested capabilities `/pub/echo/:rw` + `/pub/pubky.app/:rw` (exactly what `IdentityRepository.DEFAULT_CAPABILITIES` requests) |
| Approve in Ring | PASSED — Ring showed "Authorization Successful" with the granted permissions |
| Echo returns to Home | **FAILED** — Echo surfaced "Auth approval failed: the provided auth request has expired or was cancelled." |

**Root cause (upstream, not Echo):** the FFI's `await_auth_approval` background poller fails
~600 ms after `start_auth_flow` — fast failures, not a timeout. The pubky SDK's HTTPS client
uses pkarr's RFC 7250 RawPublicKey `CertVerifier`, which resolves a host's endpoint public key
over the DHT and rejects standard CA chains (`!intermediates.is_empty()` → `UnknownIssuer`).
Against `httprelay.pubky.app` from the emulator this never matches (DHT endpoint resolution /
CA-cert handling), so the relay poll errors three times and the SDK returns `RequestExpired`.

This lives in `pubky-core` / `pkarr` (the SDK's TLS + DHT layer), below the thin FFI surface and
below Echo. Pubky Ring reaches the same relay because it uses the OS HTTP stack, not pkarr's
verifier. The Echo-side Ring integration (auth URL, capabilities, deeplink hand-off, `startAuthFlow`
/ `awaitAuthApproval` / `parseAuthUrl` wiring) is correct and verified up to Ring's approval.

## 02–06 — blocked on a live session

Paste-import→publish, study loop, discover/social, deck delete, and profile/settings/sign-out
all require a signed-in session and live homeserver HTTPS. Since the same pkarr TLS path gates
every homeserver read/write, these could not be executed against real data on this emulator.
The journey specs (`02`–`06`) are authored and ready to run once the relay/TLS path resolves on a
real device or a DHT-reachable network.

## Reproducing 02–06 once auth works

Run the apk, complete journey 01, then drive `journeys/02…06.xml` step by step with the
`android` CLI (`android screen capture` + `adb shell input` per action), reporting the JSON
result shape from the journeys skill.
