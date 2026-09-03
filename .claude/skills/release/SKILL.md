---
name: release
description: Bump versions on a release branch, build a signed APK and Play bundle, tag, and publish a GitHub release with the CLI binaries
disable-model-invocation: true
argument-hint: "<version> (e.g. v0.2.0)"
---

Release process for Loopky. Version: $ARGUMENTS

Loopky is a Kotlin Multiplatform app **plus a headless CLI**, so a release bumps all three version
numbers in the same commit. What reaches users differs per artifact, and the difference is what
this file is mostly about:

| Artifact | Built by | Where it goes |
| --- | --- | --- |
| Android APK | you, in step 7 | attached to the GitHub release |
| Android App Bundle | you, in step 7 | **not** attached — an AAB cannot be installed directly; it is for the Play Console upload the user performs by hand |
| `loopky` binaries (Linux x86_64, macOS arm64), the `.deb`, their `.sha256`s | **`.github/workflows/release.yml`, triggered by the tag** | attached to the same GitHub release, by the workflow |
| Container image | the same workflow | pushed to `ghcr.io/jvsena42/loopky` |

**The CLI half is automatic and you do not build it by hand.** `native-image` does not
cross-compile, so the Linux and macOS binaries need one CI job each; that is why pushing the tag
is what produces them. Your job is to make sure the tag is one the workflow will accept, and to
check afterwards that the assets actually landed — a release page without them is a failed release
that looks like a successful one, and `cli/install.sh` fetches those exact names.

`main` is protected — nothing is committed or pushed to it directly. The version bump goes on a
release branch and reaches `main` through a pull request; the tag is then created on the resulting
merge commit.

## Steps

1. **Validate version argument**: Ensure a version was provided (e.g. `v0.2.0`). It must start with
   `v` followed by semver. Abort if missing or malformed. Derive the numeric version by stripping
   the `v` prefix (e.g. `v0.2.0` -> `0.2.0`). Abort if the tag already exists (`git tag -l`).

2. **Pre-flight checks**:
   - Ensure the working tree is clean (`git status`). Abort if there are uncommitted changes.
   - Ensure you are on `main` and in sync with `origin/main` (`git fetch origin && git status`).
   - Run `./gradlew detektAll`. Abort if it reports issues.
   - Run `./gradlew :shared:allTests`. Abort if tests fail.
   - Run `./gradlew :cli:test`. Abort if tests fail — the CLI is a release artifact now, not a
     developer convenience.

3. **Create the release branch**: `git switch -c chore/version-<numeric_version>` off `main`.

4. **Bump versions (one commit, all three)**:
   - `composeApp/build.gradle.kts` — set `versionName` to the numeric version and increment
     `versionCode` by 1.
   - `iosApp/Configuration/Config.xcconfig` — set `MARKETING_VERSION` to the numeric version and
     `CURRENT_PROJECT_VERSION` to the **same number as the new Android `versionCode`**, keeping the
     two build numbers in lockstep. This xcconfig is the base configuration for the project's build
     configs; `Info.plist` carries no version keys.
   - `gradle.properties` — set `loopkyCliVersion` to the numeric version. This is the **single
     source** for `loopky --version` (compiled in by `:cli:generateCliVersion`), the `.deb`'s
     `Version:`, and the container image tag.

     **Forgetting this fails the whole release, by design.** `release.yml`'s first job compares the
     tag against this property and refuses to build anything when they disagree, because the
     alternative is worse: a `v0.8.0` release whose binary answers `loopky 0.1.0`, and
     `install.sh` ends by printing `--version`, so the wrong number is the first thing a new user
     sees. The apps and the CLI are therefore versioned in lockstep — one number for the repo,
     which is also the honest answer to "which CLI matches app 0.8.0".
   - `Config.xcconfig` is tracked in git and its `TEAM_ID` is intentionally empty — a locally
     filled `TEAM_ID` must never be committed. Verify by inspecting **added lines only**:
     `git diff | grep -E "^\+[^+]"` must show exactly the four version lines and nothing else.
     Do not grep the whole diff: `TEAM_ID=` sits a few lines above the version block, so it always
     appears as an unchanged context line and a naive grep aborts the release on every run.
     With the CLI bump added, that added-lines check now shows **five** version lines rather than
     four, and still nothing else.
   - Commit: `chore: bump version to <numeric_version>`.

5. **Open the pull request**:
   - `git push -u origin chore/version-<numeric_version>`.
   - `gh pr create` targeting `main`, title `chore: bump version to <numeric_version>`, with a body
     covering Summary, Changes and Test plan. There is no PR template in this repo — write the body
     directly.
   - Print the PR URL.

6. **Wait for the PR to merge**: `main` *requires* two CI contexts — `Kotlin lint (detekt)` and
   `Unit tests + Android build` — and requires the branch to be up to date with `main` before
   merging, but requires no approving review. Two more jobs run without being required,
   `CLI on Linux x86_64` and `CLI as a native binary`; the second builds the artifact the release
   is about to publish, so **do not merge past a failure in it** even though GitHub will let you.
   Watch with `gh pr checks --watch`, then merge, or use `gh pr merge --auto` if the user asks. Do
   not proceed until `gh pr view --json state` reports `MERGED`. Then
   `git switch main && git pull origin main`.

7. **Build the signed artifacts** (from merged `main`, so they match the commit being tagged):
   - Confirm `local.properties` defines all four signing constants: `KEYSTORE_FILE`,
     `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Check that the **keys are present** only —
     never read, print, or echo their values (e.g. `grep -c '^KEYSTORE_FILE=' local.properties`).
     Abort with a clear message if any is missing, because the build would otherwise silently
     produce an unsigned APK.
   - Run `./gradlew clean :composeApp:assembleRelease :composeApp:bundleRelease`. The APK is the
     GitHub release asset; the AAB is what gets uploaded to the Play Console.
   - Verify the APK exists at `composeApp/build/outputs/apk/release/composeApp-release.apk` and the
     bundle at `composeApp/build/outputs/bundle/release/composeApp-release.aab`.
   - Verify the APK is actually signed: `apksigner verify <apk>` (or `jarsigner -verify`). Abort if
     it is unsigned. The `signingConfigs` block in `composeApp/build.gradle.kts` only creates a
     `release` config when `KEYSTORE_FILE` is present, and the release build type falls back to an
     unsigned build when it is not — so an unsigned APK here means the constants did not reach
     Gradle, never that the build failed. Verify the AAB the same way with `jarsigner -verify`
     (an AAB carries JAR/v1 signing only, so `apksigner` does not apply to it); look for
     `jar verified` — the self-signed and missing-timestamp warnings it also prints are expected
     for an upload key and are not failures.
   - **Check the signing key's algorithm, not just that a signature exists.** Play App Signing
     requires the upload key to be **RSA, 2048 bits or more**; it rejects anything else at upload
     time with "Your Android App Bundle has an invalid signature", which says nothing about the
     real cause and cannot be fixed by rebuilding. `jarsigner`/`apksigner` happily verify a
     non-RSA signature, so this has to be asserted separately — extract the signature block from
     the AAB and print the certificate (public data, safe to show):

     ```shell
     unzip -o -q <aab> 'META-INF/*.RSA' 'META-INF/*.EC' 'META-INF/*.DSA' -d /tmp/certchk
     keytool -printcert -file /tmp/certchk/META-INF/<block> | grep -E "Public Key|Signature algorithm|Valid"
     ```

     Abort unless it reads `RSA` at 2048 bits or more (`SHA256withRSA`). A `256-bit EC
     (secp256r1)` key is the failure this check exists to catch — see v0.6.1, where the release
     published fine and only the Play upload rejected it. Also confirm the certificate's validity
     ends after **2033-10-22**, Play's floor.
   - **Rename them for upload**: copy the APK to `Loopky-<numeric_version>.apk` and the AAB to
     `Loopky-<numeric_version>.aab` in the repo root, so the artifacts carry the app name and
     version rather than `composeApp-release.*`. `*.apk` and `*.aab` are both gitignored, so the
     copies stay out of the working tree's status.

8. **Generate changelog** (before tagging, so the user confirms the notes and the publish together):
   - Find the previous tag: `git describe --tags --abbrev=0 HEAD` — the new tag does not exist yet,
     so this returns the previous one. If no tag exists at all (the repo had none as of the first
     release), use every commit: `git log --oneline --no-merges`.
   - List commits since it: `git log <previous_tag>..HEAD --oneline --no-merges`.
   - For a large range, group first: `git log <prev>..HEAD --no-merges --format="%s" | sed -E
     's/^([a-z]+)(\(.*\))?:.*/\1/' | sort | uniq -c | sort -rn`, then read the `feat:` and `fix:`
     subjects. Summarize by theme rather than per commit.
   - Write the changelog as a bullet list of user-facing changes (group related commits, skip
     chore/CI/test/docs/refactor commits — including this release's own version bump — and keep each
     bullet to one sentence in English). Do not trust commit subjects blindly; check the diff when a
     subject looks mislabelled.
   - End with a full-changelog link:
     `https://github.com/jvsena42/loopky/compare/<previous_tag>...<version>` (omit this line for a
     first release with no previous tag).

9. **Confirm, then create the git tag** on the merge commit now at the tip of `main`:
   - Show the user the changelog and state plainly what the next actions do. Pushing the tag is no
     longer just a tag: it starts `release.yml`, which builds the CLI binaries, creates or fills
     the **public** GitHub release, and **pushes a container image to `ghcr.io`**. Say all three
     out loud. A registry push cannot be taken back the way an unpublished artifact can. Get
     explicit approval before continuing.
   - `git tag -a <version> -m "Release <version>"`.
   - Confirm it landed on the merge commit: `git rev-list -n1 <version>` should equal `main`'s tip.
   - `git push origin <version>` (tags push fine; only branch pushes to `main` are blocked).
   - The workflow starts here and takes roughly ten minutes. Continue to step 10 immediately —
     do not wait for it, because the two are meant to race and the next step explains who wins.

10. **Create the GitHub release, or fill in the one the workflow made**:

    Both you and `release.yml` will try to create it, and whoever is second falls back rather than
    failing. In practice you win — the workflow spends minutes compiling before it gets here — but
    write the step so either order works, because the difference decides whether the release
    carries your changelog or GitHub's auto-generated one.

    - Write the approved changelog to a temp file and pass it with `--notes-file` — `--notes` is
      unwieldy for multi-line bodies.
    - `gh release create <version> Loopky-<numeric_version>.apk --title "Loopky <version>"
      --notes-file <file> --latest=false`.

      **`--latest=false`, and it is the whole point of splitting this across two steps.** The CLI
      assets are still ten minutes away, and `latest` is not decoration: `cli/install.sh` defaults
      to `https://github.com/jvsena42/loopky/releases/latest/download/loopky-linux-x86-64`, and so
      does the one-liner in `cli/README.md`. Mark this release latest now and the documented
      install 404s until the workflow finishes — a bounded outage on every release, self-healing,
      and therefore one nobody ever catches. Held back, `latest` keeps pointing at the previous
      release, so the one-liner installs an older working binary instead of nothing. Step 11 moves
      it once the assets are there and the binary has been run.

      Note that **omitting** `--latest` does not do this. Its default is "automatic based on date
      and version", and GitHub reads a new highest version as the latest one. Only
      `--latest=false` holds it back.
    - **If that fails because the release already exists**, the workflow got there first. It
      builds its release as a draft, uploads the assets, and publishes only then, so what you are
      looking at already has the CLI binaries in it and is already latest — there is no window to
      worry about on that path. What it does not have is your changelog:
      `gh release edit <version> --title "Loopky <version>" --notes-file <file>`, then
      `gh release upload <version> Loopky-<numeric_version>.apk --clobber`. Do not pass
      `--latest=false` here, and do not re-mark it in step 11: the assets are already there and
      the flag is already right.
    - Verify it exists and carries the APK:
      `gh release view <version> --json tagName,name,isDraft,isPrerelease,assets` — not a draft,
      not a prerelease, and `Loopky-<numeric_version>.apk` listed.

      Do **not** check `releases/latest` here. It still points at the previous release, on purpose,
      and will until step 11 — that is the fix, not a failure.

11. **Wait for the CLI artifacts, and check they arrived**:
    - `gh run watch $(gh run list --workflow=release.yml --limit 1 --json databaseId -q '.[0].databaseId')`.
    - A failure in `check-version` means the tag and `loopkyCliVersion` disagree — step 4 was
      missed. The tag is already public at that point, so the fix is to bump the property on a new
      branch, merge it, delete and re-push the tag, not to hand-edit the release.
    - When it finishes, assert every CLI asset is on the release, by name. `cli/install.sh`
      fetches these exact strings, so a missing one is a 404 for every user who runs the documented
      one-liner:

      ```shell
      gh release view <version> --json assets -q '.assets[].name' | sort
      ```

      must include `loopky-linux-x86-64`, `loopky-linux-x86-64.sha256`, `loopky-macos-aarch64`,
      `loopky-macos-aarch64.sha256`, `loopky_<numeric_version>_amd64.deb`, `install.sh` and
      `latest.json`.

      The last two are newer and are what the documented one-liner and every installed binary
      resolve through (#209). `install.sh` is the installer *at this tag* — the README tells
      strangers to pipe `releases/latest/download/install.sh` into `sh`, so a release missing it
      breaks the primary install path outright. `latest.json` is the manifest every `loopky` in
      the world reads to learn it is out of date; a release without one is silently the release
      after which nobody is told anything. Check what it says, too — it must name this version:

      ```shell
      curl -fsSL https://github.com/jvsena42/loopky/releases/download/<version>/latest.json
      ```
    - Sanity-check the published binary rather than trusting the job: download it and run it.
      It is one file and needs nothing installed, which is the entire claim being shipped:

      ```shell
      curl -fsSL https://github.com/jvsena42/loopky/releases/download/<version>/loopky-linux-x86-64 -o /tmp/loopky
      chmod +x /tmp/loopky && /tmp/loopky --version    # must print `loopky <numeric_version>`
      ```

      On macOS, use `loopky-macos-aarch64` instead — the Linux binary will not exec there. If
      `--version` disagrees with the tag, stop: the release is publishing a binary that lies about
      what it is.
    - While that binary is downloaded, check the update path answers: `/tmp/loopky update --check
      --json` must exit 0 and report `"latest"` as the *previous* release until `latest` moves,
      and this version after it. It is allowed to report nothing (`"latest":null`) on a host with
      no egress — a check that cannot complete is never a failure — but on a machine that can
      reach github.com, a null here means `latest.json` is missing or unreadable.
    - **Only now, mark it latest**: `gh release edit <version> --latest`. This is the step that
      makes `cli/install.sh` and the README's one-liner point at this release, so it comes after
      the assets are listed *and* after one of them has been run — never before. Skip it if the
      workflow created the release itself (step 10's fallback branch), where it is already latest.
      Skip it deliberately for a pre-release: a `v1.0.0-rc1` should not become what a bare
      `curl … /releases/latest/download/…` installs.
    - Confirm the move landed, and that the install path a new user takes now resolves. There is
      no `isLatest` JSON field on a release — query the endpoint instead:

      ```shell
      gh api repos/jvsena42/loopky/releases/latest -q '.tag_name'          # <version>
      curl -fsIL https://github.com/jvsena42/loopky/releases/latest/download/loopky-linux-x86-64 \
        -o /dev/null -w '%{http_code}\n'                                  # 200
      ```
    - Confirm the image: `docker pull ghcr.io/jvsena42/loopky:<numeric_version>`. `:latest` moves
      only for a final version — a pre-release tag such as `v1.0.0-rc1` deliberately leaves it
      where it is, so do not treat an unmoved `:latest` as a failure.

12. **Clean up and summarize**: Delete the temporary `Loopky-<numeric_version>.apk` and the merged
    release branch (`git branch -d chore/version-<numeric_version>`), then print the release URL,
    the new Android `versionCode`/`versionName`, the iOS
    `MARKETING_VERSION`/`CURRENT_PROJECT_VERSION`, and the CLI's `loopkyCliVersion` with the
    install one-liner from `cli/README.md`, so the user can paste it somewhere.

    **Keep `Loopky-<numeric_version>.aab`** — it is not a GitHub release asset, it is the artifact
    the user uploads to the Play Console by hand, and deleting it means rebuilding. Print its path
    and say it is ready for upload.

## Important

- Abort immediately if any step fails.
- Never commit or push directly to `main` — it is protected. The version bump always goes through
  the release branch and its PR.
- Ask the user for confirmation before pushing the tag and creating the release (step 9), and say
  explicitly that the tag also publishes a container image to `ghcr.io` — that one is not
  retractable.
- Run `git commit`, `git push` and `gh pr create` as **separate** commands, never chained into one.
  Chained, a declined or interrupted call can leave the commit and push already done while the PR
  is missing, and the run then looks inconsistent. If a step seems to have half-run, re-check the
  real state (`git log`, `git ls-remote --heads origin <branch>`, `gh pr list`) before redoing
  anything — `git rev-parse @{u}` can fail on a branch that *was* pushed, when its remote-tracking
  ref is simply not fetched yet.
- Never skip detekt, the tests, the signature verification, or the key-algorithm check. The
  algorithm check is the one that is easy to think redundant: a signature that verifies can
  still be rejected by Play, and the rejection happens after the release is already public.
- **A release page without the CLI assets is a failed release that looks like a successful one.**
  Step 11 is not a formality: `cli/install.sh` fetches `loopky-linux-x86-64` and its `.sha256` by
  those exact names, so a workflow that failed after the release was created leaves every
  documented install path returning 404 while the page reads fine. Check the names, and run the
  binary you actually published.
- **Nothing is `latest` until it is complete.** `latest` is what both documented install paths
  resolve through, so moving it is the act of publishing, not a flag on the way past. Step 10
  creates with `--latest=false` and step 11 moves it after the assets are listed and one has been
  run. Doing it in one step gives every release a ten-minute window where the one-liner 404s —
  bounded, self-healing, and therefore never noticed.
- **`loopkyCliVersion` and the tag are one number.** The release workflow refuses the build when
  they disagree, which is the good failure; the bad one is skipping the bump, having the workflow
  stop, and hand-fixing the release page so the binary and the tag say different things.
- Never print, log, or commit any value from `local.properties` — it holds the signing credentials
  and the Unsplash key, and it is gitignored for that reason. Refer to the constants by name only,
  and never commit a real `TEAM_ID`.
