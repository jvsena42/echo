#!/usr/bin/env bash
#
# The release notes for a tag, from the commit subjects behind it.
#
# A script rather than a block inside `release.yml` so that the notes a tag is *going* to get can
# be read before the tag is pushed — `.github/scripts/changelog.sh v0.9.0 v0.8.1` off a local
# checkout prints exactly what the workflow will publish. A release note nobody could preview is
# one that gets reviewed after it is public.
#
# Conventional-commit types decide what is user-facing; everything else (chore, ci, docs, test,
# refactor, style, build) is dropped, including the release's own version bump.
set -euo pipefail

VERSION="${1:?usage: changelog.sh <version> [previous-tag]}"
PREV="${2:-}"

# `<version>^` rather than `<version>`, or `describe` answers with the tag we are writing notes
# for. Empty is a legitimate answer — the first release has nothing behind it.
if [ -z "$PREV" ]; then
    PREV=$(git describe --tags --abbrev=0 "${VERSION}^" 2>/dev/null || true)
fi

RANGE="$VERSION"
[ -n "$PREV" ] && RANGE="$PREV..$VERSION"

subjects_for() {
    local types="$1"
    git log "$RANGE" --no-merges --format='%s' |
        awk -v types="$types" '
            BEGIN { split(types, want, "|"); for (i in want) keep[want[i]] = 1 }
            match($0, /^[a-z]+(\([^)]*\))?!?:[[:space:]]*/) {
                head = substr($0, 1, RLENGTH)
                body = substr($0, RLENGTH + 1)
                type = head; sub(/[(!:].*$/, "", type)
                if (!(type in keep) || body == "") next
                scope = ""
                if (match(head, /\(([^)]*)\)/)) scope = substr(head, RSTART + 1, RLENGTH - 2)
                body = toupper(substr(body, 1, 1)) substr(body, 2)
                if (scope != "") printf "- **%s**: %s\n", scope, body
                else printf "- %s\n", body
            }
        '
}

section() {
    local heading="$1" types="$2" lines
    lines=$(subjects_for "$types")
    [ -z "$lines" ] && return 0
    printf '### %s\n\n%s\n\n' "$heading" "$lines"
}

NOTES=$(
    section 'Added' 'feat'
    section 'Fixed' 'fix'
    section 'Performance' 'perf'
)

# Never an empty body. A release whose commits were all chores is a real thing to publish and
# "nothing user-facing" is the honest note for it; a blank one reads as a generation failure.
if [ -z "$NOTES" ]; then
    NOTES='No user-facing changes — maintenance, tooling and documentation only.'
fi

# `$(…)` has already eaten the trailing blank line the sections wrote, so put one back rather than
# letting the compare link run onto the last bullet.
printf '%s\n\n' "$NOTES"

if [ -n "$PREV" ]; then
    REPO="${GITHUB_REPOSITORY:-jvsena42/loopky}"
    printf '**Full changelog**: https://github.com/%s/compare/%s...%s\n' "$REPO" "$PREV" "$VERSION"
fi
