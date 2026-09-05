package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.domain.model.Capability
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.platform.desktopNativeRow
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSessionStoreTest {

    private val home: Path = Files.createTempDirectory("loopky-session-store")

    @AfterTest
    fun cleanUp() {
        home.toFile().deleteRecursively()
    }

    // --- which store, on which host ------------------------------------------

    @Test
    fun `linux keeps the session in the file, whatever else is installed`() {
        assertFalse(keychainEligible(home, macOs = false, toolPresent = true, default = home))
    }

    @Test
    fun `a mac with no security tool falls back rather than failing`() {
        assertFalse(keychainEligible(home, macOs = true, toolPresent = false, default = home))
    }

    /**
     * `LOOPKY_CONFIG_HOME` exists so a container or a test can point state somewhere disposable. A
     * Keychain item is not disposable and is shared by every config home, so honouring it there
     * would make `LOOPKY_CONFIG_HOME=/tmp/x loopky login` overwrite the caller's real session.
     */
    @Test
    fun `an explicit config home keeps the session in it`() {
        assertTrue(keychainEligible(home, macOs = true, toolPresent = true, default = home))
        assertFalse(
            keychainEligible(home, macOs = true, toolPresent = true, default = Paths.get("/somewhere/else")),
        )
    }

    @Test
    fun `an ineligible host gets the plain file store`() {
        val store = desktopSecureSessionStore(home, secretsStore(home), keychain = null)
        assertEquals(home.resolve("secrets.json").toString(), store.location)
    }

    // --- the keychain wrapper ------------------------------------------------

    @Test
    fun `a saved session comes back, and does not stay in the file`() = runTest {
        val keychain = FakeKeychain()
        val store = desktopSecureSessionStore(home, secretsStore(home), keychain)

        store.save(SESSION)

        assertEquals(SESSION, store.load())
        assertNull(FileSecureSessionStore(secretsStore(home)).load())
    }

    /**
     * Every macOS install predating #213 has a live session in `secrets.json`. An upgrade that
     * silently signed everybody out would be read as a bug, so the first read adopts it — and
     * clears the file, because the one rule here is that a usable credential never sits in two
     * places.
     */
    @Test
    fun `a session stored by an older version is adopted into the keychain`() = runTest {
        val secrets = secretsStore(home)
        FileSecureSessionStore(secrets).save(SESSION)
        val keychain = FakeKeychain()

        val store = desktopSecureSessionStore(home, secrets, keychain)

        assertEquals(SESSION, store.load())
        assertNotNull(keychain.value)
        assertNull(FileSecureSessionStore(secretsStore(home)).load())
    }

    @Test
    fun `a keychain that will not write leaves the session in the file`() = runTest {
        val keychain = FakeKeychain(writable = false)
        val store = desktopSecureSessionStore(home, secretsStore(home), keychain)

        store.save(SESSION)

        assertNull(keychain.value)
        assertEquals(SESSION, store.load())
        assertEquals(SESSION, FileSecureSessionStore(secretsStore(home)).load())
    }

    /**
     * The failure that is worse than not storing the session: storing it *somewhere the reader
     * does not look first*. [MacKeychainSessionStore.load] reads the Keychain before the file, so
     * a second sign-in whose write fails used to leave the previous account winning every command
     * afterwards — with `session_live` true, because that session really is live.
     *
     * Starting from a populated keychain is the whole test. The `writable = false` case above
     * starts from an empty one, falls through to the file, and looks correct.
     */
    @Test
    fun `a failed write does not leave the previous account in the keychain`() = runTest {
        val secrets = secretsStore(home)
        val keychain = FakeKeychain()
        desktopSecureSessionStore(home, secrets, keychain).save(SESSION)

        val second = SESSION.copy(
            identity = SESSION.identity.copy(pubky = "pk:second"),
            sessionSecret = "pk:second:cookie",
        )
        val locked = FakeKeychain(value = keychain.value, writable = false)
        val store = desktopSecureSessionStore(home, secretsStore(home), locked)
        store.save(second)

        assertNull(locked.value, "the stale item has to go, or load() keeps preferring it")
        assertEquals(second, store.load())
    }

    /**
     * Neither storing the new session nor giving up the old one is refused outright: a store that
     * cannot say which of two credentials comes back has nothing to offer, and failing the
     * sign-in beats writing decks under the wrong pubky.
     */
    @Test
    fun `a keychain that will neither write nor delete refuses rather than picking`() = runTest {
        val keychain = FakeKeychain(value = "c3RhbGU=", writable = false, deletable = false)
        val store = desktopSecureSessionStore(home, secretsStore(home), keychain)

        assertFailsWith<IllegalStateException> { store.save(SESSION) }
        assertNull(FileSecureSessionStore(secretsStore(home)).load())
    }

    /**
     * `clear()` promises the credential is gone. It used to swallow a refused delete, which is how
     * `loopky logout` came to print "Signed out." over an item still in the Keychain.
     */
    @Test
    fun `clearing reports a keychain that will not give the credential up`() = runTest {
        val secrets = secretsStore(home)
        val keychain = FakeKeychain(value = "c3RpbGwtaGVyZQ==", deletable = false)
        val store = desktopSecureSessionStore(home, secrets, keychain)

        assertFailsWith<IllegalStateException> { store.clear() }
        // The file is emptied first regardless, so a failure never leaves it in two places.
        assertNull(FileSecureSessionStore(secretsStore(home)).load())
    }

    @Test
    fun `a keychain that will not answer reads the file instead, and says so`() = runTest {
        val secrets = secretsStore(home)
        FileSecureSessionStore(secrets).save(SESSION)
        val store = desktopSecureSessionStore(home, secrets, FakeKeychain(readable = false))

        assertEquals(SESSION, store.load())
        assertContains(store.location, "not answering")
    }

    @Test
    fun `clearing empties both places`() = runTest {
        val secrets = secretsStore(home)
        val keychain = FakeKeychain()
        val store = desktopSecureSessionStore(home, secrets, keychain)
        store.save(SESSION)
        // Put a stale copy back to prove `clear` does not trust the invariant it maintains.
        FileSecureSessionStore(secrets).save(SESSION)

        store.clear()

        assertNull(keychain.value)
        assertNull(store.load())
        assertNull(FileSecureSessionStore(secretsStore(home)).load())
    }

    @Test
    fun `an item that is not a session is treated as absent rather than fatal`() = runTest {
        val keychain = FakeKeychain(value = "bm90LWEtc2Vzc2lvbg==")
        val store = desktopSecureSessionStore(home, secretsStore(home), keychain)

        assertNull(store.load())
    }

    /**
     * The value has to survive `security -i`'s shell-like line splitting unquoted, which is what
     * keeps the secret out of `argv` — see [SecurityCliKeychain].
     */
    @Test
    fun `what is handed to the keychain is one argv-safe token`() = runTest {
        val keychain = FakeKeychain()
        val store = desktopSecureSessionStore(home, secretsStore(home), keychain)

        store.save(SESSION.copy(identity = SESSION.identity.copy(displayName = "Ana \"la\" Peña")))

        val written = assertNotNull(keychain.value)
        assertTrue(written.all { it.isLetterOrDigit() || it in "+/=" }, written)
    }

    @Test
    fun `the file the fallback writes is the same one the file store owns`() = runTest {
        val store = desktopSecureSessionStore(home, secretsStore(home), FakeKeychain(writable = false))

        store.save(SESSION)

        assertContains(home.resolve("secrets.json").readText(), "session.v1")
    }

    // --- the real thing, on the one host that has it -------------------------

    /**
     * The only test that exercises `security(1)`, and it runs nowhere else. The fake above proves
     * the wrapper's decisions; this proves the protocol — `-i` on stdin, Base64 in, exit 44 for a
     * missing item — which is the half a fake cannot say anything about.
     *
     * It does **not** prove the native image can spawn a subprocess at all; that is checked by
     * running the built binary (`journeys/RESULTS.md`, the macOS row).
     */
    @Test
    fun `a real keychain round trip, on a Mac`() {
        if (desktopNativeRow() != com.github.jvsena42.loopky.platform.DesktopNativeRow.MacArm64) return
        val keychain = SecurityCliKeychain(service = "loopky.test.${System.nanoTime()}")
        try {
            assertIs<KeychainRead.Missing>(keychain.read())
            assertTrue(keychain.write("aGVsbG8=").isSuccess)
            assertEquals(KeychainRead.Found("aGVsbG8="), keychain.read())
            assertTrue(keychain.write("d29ybGQ=").isSuccess, "a second sign-in has to replace the item")
            assertEquals(KeychainRead.Found("d29ybGQ="), keychain.read())
        } finally {
            keychain.delete()
        }
        assertIs<KeychainRead.Missing>(keychain.read())
    }

    /**
     * The bound has to actually fire, which is not what the code said before: draining both pipes
     * inline meant `waitFor` was only ever reached by a process that had already exited, so the
     * timeout could never be produced. `security find-generic-password` really does block
     * indefinitely on an unlock prompt nobody can answer over SSH, and unbounded that is a
     * `loopky whoami` that never returns.
     *
     * A `sleep` standing in for `security`, so this needs no keychain and runs anywhere POSIX.
     */
    @Test
    fun `a security that never answers is cut off rather than waited on`() {
        val sleeper = Files.createTempFile("fake-security", ".sh")
        sleeper.toFile().writeText("#!/bin/sh\nsleep 30\n")
        sleeper.toFile().setExecutable(true)

        val started = System.nanoTime()
        val read = SecurityCliKeychain(security = sleeper, timeoutSeconds = 1).read()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertIs<KeychainRead.Failed>(read)
        assertContains(read.message, "no answer in 1s")
        assertTrue(elapsedMs < 15_000, "gave up after ${elapsedMs}ms, so the bound did not fire")
        Files.deleteIfExists(sleeper)
    }

    private class FakeKeychain(
        var value: String? = null,
        private val readable: Boolean = true,
        private val writable: Boolean = true,
        private val deletable: Boolean = true,
    ) : Keychain {
        override val location = "a fake keychain"

        override fun read(): KeychainRead = when {
            !readable -> KeychainRead.Failed("no")
            else -> value?.let { KeychainRead.Found(it) } ?: KeychainRead.Missing
        }

        override fun write(value: String): Result<Unit> {
            if (!writable) return Result.failure(IllegalStateException("no"))
            this.value = value
            return Result.success(Unit)
        }

        override fun delete(): Result<Unit> {
            if (!deletable) return Result.failure(IllegalStateException("no"))
            value = null
            return Result.success(Unit)
        }
    }

    private companion object {
        val SESSION = Session(
            identity = PubkyIdentity(pubky = "pk:abc", displayName = null, avatarUrl = null, bio = null),
            sessionSecret = "pk:abc:cookie",
            capabilities = listOf(Capability("/pub/loopky/:rw")),
            homeserver = "hs.example",
        )
    }
}
