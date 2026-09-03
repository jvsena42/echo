# `loopky` — the headless client

A terminal binary that creates and manages Loopky decks against the same homeserver layout the
Android and iOS apps use. The point is not a nicer keyboard UX: **it is that an agent can drive
Loopky.** Agents already write good flashcards; until this, the only way into a Loopky deck was a
phone screen.

Design decisions and their reasoning live in [`docs/Architecture.md` §13](../docs/Architecture.md).
This file is how to build and use it.

## Build

```shell
./gradlew :cli:installDist          # -> cli/build/install/loopky/bin/loopky
./gradlew :cli:distTar              # -> cli/build/distributions/loopky.tar
./gradlew :cli:test                 # the CLI's own unit tests
./gradlew :shared:jvmTest           # the whole shared suite on the jvm() target, plus the
                                    # FFI smoke test that proves libpubkycore actually loads
```

Needs a JRE 17 on the target machine — see "Packaging" below, which is the honest gap.

The native library ships inside the jar under JNA's resource layout, so nothing is installed by
hand. Rebuild it in the fork with `./build_desktop.sh linux|macos|all` and copy
`bindings/desktop/` into `shared/src/jvmMain/resources/`; see the README there.

## Use

```shell
loopky login                        # QR for Pubky Ring, then waits for approval
loopky login --export               # also prints the session secret, for LOOPKY_SESSION
loopky whoami --json
loopky logout

loopky deck list
loopky deck create --title "Capitais" --tag geografia --tag "português" --from-file cards.tsv
loopky deck show <deckId> --json
loopky deck sync <deckId>
loopky deck compact <deckId>
loopky deck delete <deckId>

loopky card list <deckId> --json
loopky card add <deckId> --front "Brasília" --back "Capital do Brasil"
loopky card add <deckId> --from-file more.tsv
loopky card edit <deckId> --from-file edits.jsonl
loopky card rm <deckId> <cardId>

loopky import cards.tsv --title "Biomas e Sub-ecossistemas Brasileiros" --resume
cat cards.tsv | loopky import - --title "…" --separator tab

loopky tag trending --limit 20
```

`loopky --help` is the full surface. Every command takes `--json`.

## Card and import files

```
TSV     front <TAB> back <TAB> front_image_url <TAB> back_image_url    (last two optional)
JSONL   {"id":"…","front":"…","back":"…","front_image_url":"…","back_image_url":"…"}
```

An image column must be an `http(s)` URL — a third column holding prose is refused rather than
stored as a picture, because a 3-column Anki export (Front / Back / Example sentence) would
otherwise publish every card with an image ref pointing at a sentence. `loopky import` is more
forgiving with the same file: it falls through to the text parser instead, since there the third
column is content somebody wants imported. Blank lines and `# ` comments are skipped in TSV — hash
**plus whitespace**, so a card whose front is `#1 ranked` survives.

The format is chosen by extension, then by content, never by a flag. JSONL is for the two things
TSV cannot hold: a side containing a tab or a newline, and — for `card edit` — naming which card to
change and which fields to leave alone. A field that is absent in an edit row is left unchanged;
clearing a side takes an explicit empty value.

The image columns matter more than they look. Both bulk paths in the apps carry a picture only when
a field is *nothing but* that image, so "this side has text **and** a picture" had no
representation at all — which is why ~190 images had to go through the card editor by hand even
though the decks imported in one shot. An image here is a **URL**, so no bytes cross the wire and
no media quota is spent.

## Environment

| Variable | What it does |
| --- | --- |
| `LOOPKY_SESSION` | A session secret — a **bearer token**, see the note below. Read **before** the stored session, and the only way in on a sandbox that has no stored one. Mint it with `loopky login --export` on a machine with a human at it, and revoke it with `loopky logout` while it is set. |
| `LOOPKY_ENV` | `staging` or `production`. `--env` wins. Defaults to production. |
| `LOOPKY_CONFIG_HOME` | Where state lives. Defaults to `$XDG_CONFIG_HOME/loopky`, then `~/.config/loopky`. |
| `RUST_LOG` | The pubky SDK's own tracing. The start script defaults it to `warn`; `RUST_LOG=debug` is the first thing to try when a homeserver call fails for no visible reason. |

## Exit codes

| | | | |
| --- | --- | --- | --- |
| 0 | ok | 5 | network |
| 1 | internal | 6 | not found |
| 2 | usage | 7 | storage full (507 — terminal, never retried) |
| 3 | not signed in | 8 | environment mismatch |
| 4 | **session expired** | 9 | bad input |

4 has a code of its own because the homeserver session dies after roughly an hour and nothing
renews it: writes start failing, reads keep working, and from the outside that is
indistinguishable from a network wobble. An agent told the wrong one either retries a dead session
forever or abandons a working network.

`whoami --json` reports `session_live`, asked rather than assumed — worth checking before starting
an hour-long import rather than forty cards in. There is no `expires_at` to report: the session
payload does not carry one.

## Things worth knowing before you script it

- **Nothing prompts.** Including the nice ones — there is no "announce this deck?" confirmation,
  because this client never requests the capability a post would need. `login` is the only command
  that blocks on a human, and `--qr-out` / `--url-only` exist for a box with no terminal anyone is
  watching.
- **stdout is the machine channel.** Results and failures both go there as `--json`; the QR code,
  prompts, progress and every log line go to stderr. `--json` silences **progress counters** on
  stderr, because the result carries the same numbers — it does not silence stderr. Warnings still
  arrive there, so capturing stderr for diagnostics is worth doing in either mode.
- **`card add` is idempotent** by front/back-plus-image, and reports what it skipped. `import
  --resume` checkpoints against the deck on the homeserver rather than a local cursor, matched on
  `--title` — which is why `--title` is mandatory and never derived from a filename.
- **The session can only write `/pub/loopky/`.** Not `/pub/pubky.app/`. It cannot post, follow, or
  edit a profile under any bug or any prompt injection, because it was never handed the capability.
  Deck tags still work — a deck manifest's tag record lives in the loopky namespace.
- **Sessions are a mode-0600 file, not an OS keyring**, and that is deliberate: libsecret is
  usually absent on the headless box this is built for. A session secret on that machine is
  protected by file permissions and nothing else. What is stored is a capability-scoped, expiring
  session — never a secret key, which never leaves Pubky Ring.
- **`LOOPKY_SESSION` is the weaker channel of the two**, and worth knowing before you reach for it
  on a shared host. An environment variable is readable by any same-uid process through
  `/proc/<pid>/environ`, is inherited by every child the CLI spawns, and lands in shell history
  when set inline. `LOOPKY_SESSION=… loopky …` as a one-shot prefix keeps it out of unrelated
  processes' environments but **not** out of `history`. It is still the right tool for an
  ephemeral sandbox, which has no stored session and no human to scan a code — the point is that
  it is a bearer token, so `loopky logout` with it set now *revokes* it rather than refusing.
- **`--qr-out` writes a live credential.** The PNG is the `pubkyauth://` URL, secret included — a
  QR code is an encoding, not a protection. It is created `0600` and deleted once approval lands,
  but for the length of the approval window that file is a session anyone who can read it can
  take.
- **A session and an `--env` that disagree is a hard error**, not a warning. Nexus answers a query
  aimed at the wrong network *successfully and empty*, so a mismatch would look like a failed write
  rather than a misconfiguration.

## Packaging: what is not done

What ships is a jar plus a start script needing a JRE 17. For the audience this exists for — a
cloud sandbox, non-root, ephemeral, configured by a setup script — "install a JDK" is exactly what
a one-line install cannot be. The remaining work is GraalVM `native-image` (JNA's library
extraction is the sharp edge), with `jlink` as the fallback, plus a container image. The tarball
also carries both native rows today, so a Linux box hauls 11 MB of macOS dylib it will never load.

Windows is out of scope for v1 by decision, not omission.
