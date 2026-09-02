# Native libraries for the desktop JVM target

`libpubkycore` for every desktop host the JVM target supports, laid out the way **JNA** looks for
it: `Platform.getNativeLibraryResourcePrefix()` maps a host to one of these directory names, and
`Native.load("pubkycore")` — the call the UniFFI-generated bindings make — extracts the matching
file from the classpath at runtime.

That layout is the whole point. It means the native library ships **inside the jar**: nobody
installing the CLI fetches one by hand, sets `-Djna.library.path`, or runs `ldconfig`.

| Directory | Host | Built by |
| --- | --- | --- |
| `linux-x86-64/libpubkycore.so` | Linux x86_64 (glibc) | `pubky-core-ffi-fork/build_desktop.sh linux` |
| `darwin-aarch64/libpubkycore.dylib` | macOS on Apple Silicon | `pubky-core-ffi-fork/build_desktop.sh macos` |

**Do not edit these; they are build output.** Regenerate them in the fork and copy the
`bindings/desktop/` tree here — the same arrangement `shared/src/androidMain/jniLibs` already has
for the four Android ABIs.

Two hosts are absent on purpose. An **Intel Mac** is not a target, so there is one
`darwin-aarch64` row rather than two and a `lipo`; a client should refuse it with a clear message
rather than let the JNA lookup miss and report a transport error. **Windows** is deferred by
decision, not omission — it would be `win32-x86-64/pubkycore.dll` and nothing in the design blocks
it.

`UniffiPubkyClientJvmTest` is what proves a row actually loads. The 1,271 shared tests run against
a fake client and pass identically on a machine where this directory is empty, the architecture is
wrong, or the file is one level off — none of which surfaces until the first homeserver call, as
an ordinary-looking transport error.
