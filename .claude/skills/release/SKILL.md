---
name: release
description: Bump versions on a release branch, build a signed APK, tag, and publish a GitHub release
disable-model-invocation: true
argument-hint: "<version> (e.g. v0.2.0)"
---

Release process for Loopky. Version: $ARGUMENTS

Loopky is a Kotlin Multiplatform app, so a release bumps **both** platforms in the same commit even
though only the Android APK is published as a release asset.

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

3. **Create the release branch**: `git switch -c chore/version-<numeric_version>` off `main`.

4. **Bump versions (one commit, both platforms)**:
   - `composeApp/build.gradle.kts` — set `versionName` to the numeric version and increment
     `versionCode` by 1.
   - `iosApp/Configuration/Config.xcconfig` — set `MARKETING_VERSION` to the numeric version and
     `CURRENT_PROJECT_VERSION` to the **same number as the new Android `versionCode`**, keeping the
     two build numbers in lockstep. This xcconfig is the base configuration for the project's build
     configs; `Info.plist` carries no version keys.
   - `Config.xcconfig` is tracked in git and its `TEAM_ID` is intentionally empty — a locally
     filled `TEAM_ID` must never be committed. Verify by inspecting **added lines only**:
     `git diff | grep -E "^\+[^+]"` must show exactly the four version lines and nothing else.
     Do not grep the whole diff: `TEAM_ID=` sits a few lines above the version block, so it always
     appears as an unchanged context line and a naive grep aborts the release on every run.
   - Commit: `chore: bump version to <numeric_version>`.

5. **Open the pull request**:
   - `git push -u origin chore/version-<numeric_version>`.
   - `gh pr create` targeting `main`, title `chore: bump version to <numeric_version>`, with a body
     covering Summary, Changes and Test plan. There is no PR template in this repo — write the body
     directly.
   - Print the PR URL.

6. **Wait for the PR to merge**: `main` requires both CI contexts to pass — `Kotlin lint (detekt)`
   and `Unit tests + Android build` (`.github/workflows/ci.yml`) — and requires the branch to be up
   to date with `main` before merging, but requires no approving review. Watch it with
   `gh pr checks --watch`, then merge it, or use `gh pr merge --auto` if the user asks. Do not
   proceed until `gh pr view --json state` reports `MERGED`. Then
   `git switch main && git pull origin main`.

7. **Build signed APK** (from merged `main`, so the artifact matches the commit being tagged):
   - Confirm `local.properties` defines all four signing constants: `KEYSTORE_FILE`,
     `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Check that the **keys are present** only —
     never read, print, or echo their values (e.g. `grep -c '^KEYSTORE_FILE=' local.properties`).
     Abort with a clear message if any is missing, because the build would otherwise silently
     produce an unsigned APK.
   - Run `./gradlew clean :composeApp:assembleRelease`.
   - Verify the APK exists at `composeApp/build/outputs/apk/release/composeApp-release.apk`.
   - Verify it is actually signed: `apksigner verify <apk>` (or `jarsigner -verify`). Abort if the
     APK is unsigned. The `signingConfigs` block in `composeApp/build.gradle.kts` only creates a
     `release` config when `KEYSTORE_FILE` is present, and the release build type falls back to an
     unsigned build when it is not — so an unsigned APK here means the constants did not reach
     Gradle, never that the build failed.
   - **Rename it for upload**: copy it to `Loopky-<numeric_version>.apk` in the repo root, so the
     release asset carries the app name and version rather than `composeApp-release.apk`. `*.apk`
     is gitignored, so the copy stays out of the working tree's status.

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
   - Show the user the changelog and state plainly that the next actions push a tag and publish a
     **public** release. Get explicit approval before continuing.
   - `git tag -a <version> -m "Release <version>"`.
   - Confirm it landed on the merge commit: `git rev-list -n1 <version>` should equal `main`'s tip.
   - `git push origin <version>` (tags push fine; only branch pushes to `main` are blocked).

10. **Create GitHub release**:
    - Write the approved changelog to a temp file and pass it with `--notes-file` — `--notes` is
      unwieldy for multi-line bodies.
    - `gh release create <version> Loopky-<numeric_version>.apk --title "Loopky <version>"
      --notes-file <file> --latest`.
    - Verify it published as intended:
      `gh release view <version> --json tagName,name,isDraft,isPrerelease,assets` — the asset must
      be listed as `Loopky-<numeric_version>.apk` — and
      `gh api repos/jvsena42/loopky/releases/latest -q '.tag_name'`. Note there is no `isLatest`
      JSON field — query the `releases/latest` endpoint instead.

11. **Clean up and summarize**: Delete the temporary `Loopky-<numeric_version>.apk` and the merged release branch
    (`git branch -d chore/version-<numeric_version>`), then print the release URL, the new Android
    `versionCode`/`versionName`, and the iOS `MARKETING_VERSION`/`CURRENT_PROJECT_VERSION`.

## Important

- Abort immediately if any step fails.
- Never commit or push directly to `main` — it is protected. The version bump always goes through
  the release branch and its PR.
- Ask the user for confirmation before pushing the tag and creating the release (step 9).
- Run `git commit`, `git push` and `gh pr create` as **separate** commands, never chained into one.
  Chained, a declined or interrupted call can leave the commit and push already done while the PR
  is missing, and the run then looks inconsistent. If a step seems to have half-run, re-check the
  real state (`git log`, `git ls-remote --heads origin <branch>`, `gh pr list`) before redoing
  anything — `git rev-parse @{u}` can fail on a branch that *was* pushed, when its remote-tracking
  ref is simply not fetched yet.
- Never skip detekt, the tests, or the APK signature verification.
- Never print, log, or commit any value from `local.properties` — it holds the signing credentials
  and the Unsplash key, and it is gitignored for that reason. Refer to the constants by name only,
  and never commit a real `TEAM_ID`.
