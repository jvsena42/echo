#!/bin/sh
#
# Build a `.deb` around an already-built Linux binary.
#
#   cli/packaging/deb.sh cli/build/native/linux-x86-64/loopky 0.1.0 out/
#
# **A file on the Releases page, and no hosted apt repository.** Adding a third-party apt repo is
# four privileged commands and a GPG keyring on a machine that may not have root, where the same
# binary is one `curl` and needs none (#54). The `.deb` is here for the people whose fleet is
# managed by a package manager and who would rather `dpkg -i` than curl, not as the recommended
# path.
set -eu

BINARY="${1:?usage: deb.sh <binary> <version> <outdir>}"
VERSION="${2:?usage: deb.sh <binary> <version> <outdir>}"
OUTDIR="${3:?usage: deb.sh <binary> <version> <outdir>}"

# Debian rejects a leading `v`, and the tag has one.
VERSION="${VERSION#v}"

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT INT TERM

mkdir -p "$STAGE/DEBIAN" "$STAGE/usr/bin" "$STAGE/usr/share/doc/loopky"
install -m 0755 "$BINARY" "$STAGE/usr/bin/loopky"
install -m 0644 cli/README.md "$STAGE/usr/share/doc/loopky/README.md"

# No `Depends:` line, and that is the point of the whole exercise — the binary needs no JRE and
# links only against glibc and libgcc, which nothing on Debian is without. The floor is glibc 2.34
# (`libpubkycore.so`'s, not ours), which is Debian 12 and Ubuntu 22.04 upward; `dpkg` cannot
# express that, so it is stated in the description instead of enforced.
cat > "$STAGE/DEBIAN/control" <<CONTROL
Package: loopky
Version: $VERSION
Section: utils
Priority: optional
Architecture: amd64
Maintainer: Loopky <https://github.com/jvsena42/loopky>
Homepage: https://github.com/jvsena42/loopky
Description: Headless Loopky client for creating and managing flashcard decks
 loopky is a terminal client for Loopky, a Pubky-backed flashcards app. It exists so an
 agent can create and manage decks without a phone screen: every command takes --json,
 and the exit code is the primary result.
 .
 A single self-contained binary. No JRE is required. Needs glibc 2.34 or newer
 (Debian 12, Ubuntu 22.04, RHEL 9 and later).
CONTROL

mkdir -p "$OUTDIR"
dpkg-deb --build --root-owner-group "$STAGE" "$OUTDIR/loopky_${VERSION}_amd64.deb"
