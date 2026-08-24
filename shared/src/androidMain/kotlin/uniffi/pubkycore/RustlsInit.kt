package uniffi.pubkycore

import android.content.Context

/**
 * Hands the [Context] to `rustls-platform-verifier` inside `libpubkycore.so`.
 *
 * pkarr's relay HTTP client uses `rustls-platform-verifier`, which on Android must be given the
 * `JavaVM` + application `Context` once before any TLS handshake — otherwise the first
 * verification panics with "Expect rustls-platform-verifier to be initialized". Because the panic
 * happens on a background task (the auth-relay poller), it surfaces only as an opaque
 * `RequestExpired` at sign-in.
 *
 * The UniFFI bindings load the library through JNA, so `JNI_OnLoad` never fires and the native
 * side cannot capture the `JavaVM` on its own. This object is the counterpart to the plain JNI
 * entrypoint `Java_uniffi_pubkycore_RustlsInit_initPlatformVerifier` in the FFI crate's
 * `src/rustls_init.rs` — the symbol name pins both the package and the object name, so neither
 * can be moved.
 *
 * Backed by a `OnceCell` on the native side, so calling more than once is harmless.
 *
 * **This is only half of what the crate needs.** It also calls back into
 * `org.rustls.platformverifier.CertificateVerifier` to do the actual verification, and that class
 * ships in the crate's own AAR — vendored under `shared/libs/maven` and depended on from
 * `shared/build.gradle.kts`. With this call in place but the class missing, init still succeeds
 * and every later handshake fails with "failed to call native verifier", which reaches the user
 * as "You're offline".
 *
 * This file is hand-written; it is not part of the generated `pubkycore.kt`.
 */
object RustlsInit {

    /**
     * Loads `libpubkycore.so` into the JVM and initializes the platform verifier.
     *
     * Must run before the first Pubky network call — [android.app.Application.onCreate] is the
     * right place. [System.loadLibrary] is required in addition to JNA's own loading so that the
     * JVM can resolve the [initPlatformVerifier] JNI symbol.
     */
    fun init(context: Context) {
        System.loadLibrary("pubkycore")
        initPlatformVerifier(context.applicationContext)
    }

    private external fun initPlatformVerifier(context: Context)
}
