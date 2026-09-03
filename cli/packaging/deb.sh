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

# **No JRE, and the two libraries there really are.** `ldd` on the binary reports `libz.so.1`,
# `libc.so.6` and the loader, and nothing else — no JVM, no JNI stubs. That is the claim this
# package exists to make, and stating the two real dependencies does not weaken it.
#
# The `libc6 (>= 2.34)` bound is load-bearing rather than tidy. The floor is `libpubkycore.so`'s,
# not ours, and without the bound `dpkg -i` on an older release **succeeds** — then the binary
# dies at exec with `/lib/x86_64-linux-gnu/libc.so.6: version 'GLIBC_2.34' not found`, a raw
# loader error naming no package and no fix. That is the same misdiagnosis `SupportedHost` and
# exit code 10 exist to prevent, one layer down; with the bound, dpkg refuses up front and says
# which package is too old.
#
# `zlib1g` unversioned on purpose: the binary needs `ZLIB_1.2.2`, which predates every release
# that could satisfy the libc bound anyway, and pinning it would mean guessing at the epoch
# Debian carries on that package.
cat > "$STAGE/DEBIAN/control" <<CONTROL
Package: loopky
Version: $VERSION
Section: utils
Priority: optional
Architecture: amd64
Depends: libc6 (>= 2.34), zlib1g
Maintainer: Loopky <https://github.com/jvsena42/loopky>
Homepage: https://github.com/jvsena42/loopky
Description: Headless Loopky client for creating and managing flashcard decks
 loopky is a terminal client for Loopky, a Pubky-backed flashcards app. It exists so an
 agent can create and manage decks without a phone screen: every command takes --json,
 and the exit code is the primary result.
 .
 A single self-contained binary. No JRE is required: it links only against libc and
 zlib. The glibc 2.34 floor is Debian 12, Ubuntu 22.04, RHEL 9 and later, and is
 enforced by the Depends line rather than left to fail at exec.
CONTROL

mkdir -p "$OUTDIR"
dpkg-deb --build --root-owner-group "$STAGE" "$OUTDIR/loopky_${VERSION}_amd64.deb"
