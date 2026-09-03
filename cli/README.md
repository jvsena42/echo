# `loopky` — the headless client

A terminal binary that creates and manages Loopky decks against the same homeserver layout the
Android and iOS apps use. The point is not a nicer keyboard UX: **it is that an agent can drive
Loopky.** Agents already write good flashcards; until this, the only way into a Loopky deck was a
phone screen.

Design decisions and their reasoning live in [`docs/Architecture.md` §13](../docs/Architecture.md).
This file is how to build and use it.

## Install

One file, no JRE, nothing else on the machine.

```shell
curl -fsSL https://raw.githubusercontent.com/jvsena42/loopky/main/cli/install.sh | sh
```

That picks the right build, checks its digest and drops it in `~/.local/bin` — no root anywhere.
By hand is a supported answer and is one line, because the artifact really is a single binary:

```shell
curl -fsSL https://github.com/jvsena42/loopky/releases/latest/download/loopky-linux-x86-64 \
  -o ~/.local/bin/loopky && chmod +x ~/.local/bin/loopky
```

| | |
| --- | --- |
| Linux x86_64 | `loopky-linux-x86-64` · needs glibc 2.34+ (Debian 12, Ubuntu 22.04, RHEL 9 and newer) |
| macOS, Apple Silicon | `loopky-macos-aarch64` |
| Container | `docker run --rm -e LOOPKY_SESSION ghcr.io/jvsena42/loopky deck list --json` |
| Debian/Ubuntu | `loopky_<version>_amd64.deb` on the release page — `dpkg -i`. Depends on `libc6 (>= 2.34)` and `zlib1g`, which is the whole of it: no JRE, and nothing else |
| Homebrew | a tap, `cli/packaging/loopky.rb` — see the note in that file |

**An Intel Mac and Windows are not targets**, by decision rather than omission (#54). Both are
refused with a message that says which, rather than failing at the first homeserver call: there is
one `darwin-aarch64` row of `libpubkycore` and no `lipo`, and Windows would need a
`win32-x86-64/pubkycore.dll` that is not built.

There is **no hosted apt repository** and there will not be one. Adding a third-party apt repo is
four privileged commands and a GPG keyring on a machine that may not have root, where the same
binary is one `curl` and needs none.

## Build

```shell
./gradlew :cli:nativeCompile        # -> cli/build/native/nativeCompile/loopky, the shipped artifact
./gradlew :cli:installDist          # -> cli/build/install/loopky/bin/loopky, jar + start script
./gradlew :cli:linuxDistTar         # -> cli/build/distributions/loopky-linux-x86-64.tar
./gradlew :cli:macosDistTar         # -> cli/build/distributions/loopky-darwin-aarch64.tar
./gradlew :cli:test                 # the CLI's own unit tests
./gradlew :shared:jvmTest           # the whole shared suite on the jvm() target, plus the
                                    # FFI smoke test that proves libpubkycore actually loads
```

`nativeCompile` needs a **GraalVM for JDK 25** and takes it from `GRAALVM_HOME` (or `JAVA_HOME`);
compilation itself still runs on the toolchain's JDK 17. `native-image` does **not** cross-compile,
so the machine you build on is the machine the binary runs on. For Linux that means a container,
which is also how CI and the release do it — one recipe, no second one to drift:

```shell
docker build -f cli/Dockerfile --target export \
  --output type=local,dest=cli/build/native/linux-x86-64 .
docker build -f cli/Dockerfile --target runtime -t loopky .
```

**Ubuntu 22.04 is the base on purpose.** A native image links against the glibc of the machine that
built it, and the floor is not ours to choose — `libpubkycore.so` already needs 2.34. Building on a
newer runner produces a binary that will not start on hosts the library is perfectly happy on.

The jar distributions are still built and are still worth having: they need a JRE 17, but they are
produced for either row from either host, where a binary cannot be. `installDist` carries both
native rows because cross-row is the point of a developer build; `linuxDistTar` and `macosDistTar`
carry one each, so a Linux box no longer hauls 11 MB of macOS dylib it can never load.

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

loopky import deck.apkg --dry-run --json     # look before you publish. No session needed.
loopky import deck.apkg --title "Japanese Core 2000" --front-field Expression --back-field Meaning

loopky tag trending --limit 20
```

`loopky --help` is the full surface. Every command takes `--json`.

## Anki `.apkg`

Bulk Anki import is the job this tool was built for (#46). `import` takes an `.apkg` on the same
command and through the same parser spine as a text file — the shared reader turns the collection
into notes and `parseBulkNotes` applies the same dedupe, caps and drop policy — so a deck imported
here and one imported on a phone come out the same.

```shell
loopky import deck.apkg --dry-run --json
loopky import deck.apkg --title "Japanese Core 2000" --front-field Expression --back-field Meaning
```

**Look first.** `--dry-run` reads the archive, reports what publishing it would do and writes
nothing; it needs no session, because reading a local file is not a homeserver operation. It is
worth doing every time, for three reasons that are all one reason — an `.apkg` is the import where
being wrong is expensive and invisible:

- **Which two fields become the card.** Anki decks routinely have fields called "Field 3". The
  reader scores them and usually chooses well, but 9000 Spanish Sentences once imported 9,213 cards
  reading `2528426` → `2760065`, because its first two fields are database ids. `--dry-run` shows
  every field with a real sample value; `--front-field` / `--back-field` take a name or a **1-based**
  number and disagree with the choice.
- **What did not become a card.** `dropped` breaks the notes down into `empty`, `half_empty` and
  `missing_media`. Reported rather than subtracted: 1,458 notes in and 1,338 cards out used to be
  explained as "1 duplicate".
- **What it will spend.** This is the **only** command that uploads bytes. Everywhere else a card's
  picture is a URL (#167) — no quota at all — but an `.apkg`'s pictures are blobs, written against
  a 1 GB homeserver allowance with no endpoint that reports what is left and a 507 that is
  terminal. And `loopky` ships no image codec, so unlike the apps it cannot shrink them: they go up
  **at full resolution**. `images.bytes` is that total, before it is spent.

Anki's own deck description and note tags are **reported and never adopted** — the description is
AnkiWeb boilerplate more often than not, and a tag is a public record indexed network-wide. Pass
them back as `--description` / `--tag` if they are right. A reversed Anki note type does set
`--reverse` on by default, since that is a fact about the deck rather than a label on the network;
`--no-reverse` turns it off.

Two failures have their own advice under exit code 9. A collection this build cannot unpack
(`collection.anki21b`, zstd — see "not in scope" below) and an export holding only Anki's legacy
compatibility stub both mean "re-export with *Support older Anki versions* ticked, or as Notes in
Plain Text"; a corrupt archive means "download it again". They used to be one message that sent
people hunting for a format problem they might not have had.

`.apkg` cannot come from stdin: a SQLite driver opens a path, not a stream. `collection.anki21b`
stays unsupported and reported — it is the one variant that would need a real dependency.

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
| `RUST_LOG` | The pubky SDK's own tracing, defaulted to `warn` — by the start script in the jar distribution and through libc in the binary, which has no start script. `RUST_LOG=debug` is the first thing to try when a homeserver call fails for no visible reason. |

## Exit codes

| | | | |
| --- | --- | --- | --- |
| 0 | ok | 6 | not found |
| 1 | internal | 7 | storage full (507 — terminal, never retried) |
| 2 | usage | 8 | environment mismatch |
| 3 | not signed in | 9 | bad input |
| 4 | **session expired** | 10 | unsupported host |
| 5 | network | | |

10 is the machine, not the command: there is no `libpubkycore` for this OS/architecture pair, and
no retry, no re-login and no second attempt can change that. It has a code of its own because
without the check the answer is not vague but *wrong* — the JNA lookup misses with "…not found in
resource path…", which the shared classifier reads as **6, not found**, and an agent is told the
deck it asked for does not exist.

4 has a code of its own because the homeserver session dies after roughly an hour and nothing
renews it: writes start failing, reads keep working, and from the outside that is
indistinguishable from a network wobble. An agent told the wrong one either retries a dead session
forever or abandons a working network.

`--dry-run` is the one `import` mode that exits 0 without a session at all: it reads a local file.

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

## Packaging

`loopky` is a **GraalVM `native-image` binary**: one file, no runtime, ~5 ms to answer `--version`
against the jar's ~50, and nothing installed on the machine it lands on. That last property is the
one the whole thing is for — the audience is a cloud agent's sandbox, non-root, recreated per task,
configured by a setup script, and "install a JDK" is exactly what a one-line install cannot be.

Three things about it are worth knowing before changing anything here.

**JNA was the entire difficulty, and it is solved by three files.** `libpubkycore` ships *inside*
the jar under JNA's resource layout and `Native.load` extracts it at runtime — reflective resource
loading, which is the one thing a closed-world image must be told about explicitly. The
registrations live in `src/main/resources/META-INF/native-image/`, with a README of their own
explaining where they came from and how to regenerate them.

**The binary has to be one file, and that is checked rather than remembered.** `native-image` does
not fail when it cannot fold a JDK native library into the executable; it emits the library beside
it and reports success. Anything reaching `javax.imageio` pulls in AWT and adds `libawt.so`,
`libawt_headless.so` and `libawt_xawt.so` — an X11 library, in a sandbox with no display — and the
one-line install is quietly dead with a green build behind it. `:cli:checkNativeImageIsOneFile`
fails the build instead. It has caught this twice already: the QR PNG writer (now ~30 lines of
chunk-and-CRC in `TerminalQr`) and the Koin binding for `MediaProcessor` (now
`PassThroughMediaProcessor`, since a card's picture here is a URL and nothing is ever decoded).

**`-march=compatibility`, not the default.** `native-image` targets x86-64-v3 unless told
otherwise, which needs AVX2; this binary is *downloaded*, onto a sandbox whose CPU nobody chose,
and a v3 binary on a host without it dies with SIGILL. Irrelevant to a client that spends its life
waiting on a homeserver.

Windows is out of scope for v1 by decision, not omission.
