# Reachability metadata for `loopky`

What `native-image` cannot see by reading the code (#210). Three files, one job each:

| File | Covers |
| --- | --- |
| `reflect-config.json` | JNA's `Structure` subclasses and the UniFFI ones — `RustBuffer`, `ForeignBytes`, `RustCallStatus` and their `ByValue`/`ByReference` forms — whose *fields* JNA reads reflectively to lay out a C struct; plus the TLS providers `HttpURLConnection` instantiates by name. |
| `jni-config.json` | The other direction: what `libjnidispatch` calls **back into Java**, which is how a `com.sun.jna.Callback` works at all. `ForeignCallbackTypeEventListener` is the one this client actually uses. |
| `proxy-config.json` | `uniffi.pubkycore._UniFFILib`. `Native.load(name, Interface::class.java)` returns a **dynamic proxy**, and a closed-world image builds no proxy class it was not told about. |

## Where it came from, and how to regenerate it

The tracing agent, run against a real homeserver rather than a fake — the whole point is to record
the FFI paths, and `FakePubkyClient` never loads a library:

```shell
./gradlew :cli:installDist
JAVA_HOME=$GRAALVM_HOME \
  JAVA_OPTS="-agentlib:native-image-agent=config-merge-dir=/tmp/loopky-agent" \
  cli/build/install/loopky/bin/loopky login --url-only     # ^C once the URL is printed
```

`login` is the command to run because it is the one that reaches `_UniFFILib.INSTANCE` — loading
the library, checking the API checksums and registering the event-listener callback — before it
touches the network. Add `tag trending` for the TLS rows. Then merge the output here by hand
rather than copying it wholesale: the agent records everything the JVM did, including the parts a
native image supplies itself.

**The community reachability-metadata repository is deliberately off** (`cli/build.gradle.kts`).
Its JNA rows were a subset of the above, and enabling it dragged `java.awt` into the image, which
on Linux makes `native-image` emit three `libawt*.so` files beside the binary — see
`checkNativeImageIsOneFile`. `com.sun.jna.NativeLong` was the one row worth keeping and is merged
into `reflect-config.json`.

## What is not here, and why

`libpubkycore` itself. It is a **resource**, included by `-H:IncludeResources` from
`cli/build.gradle.kts` rather than from a file here, because which row to embed depends on the
build host: `native-image` does not cross-compile, so a Linux build must carry
`linux-x86-64/libpubkycore.so` and a macOS one `darwin-aarch64/libpubkycore.dylib`, and a static
file cannot say "whichever this machine is".
