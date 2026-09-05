package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.platform.isMacOs
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.Base64

/**
 * The desktop [SecureSessionStore], chosen by host (#213).
 *
 * macOS gets the Keychain and Linux gets the 0600 file, and the split is not an inconsistency —
 * it is the two rows having genuinely different users. The Linux row's primary target is the
 * headless box an agent runs on, where libsecret is usually *absent*, so a keyring default would
 * fail exactly where the tool is meant to work ([ConfigHome]). The macOS row is the developer's
 * machine: there is a human at the keyboard and the Keychain is always there.
 *
 * **An explicit config home keeps everything in it.** `LOOPKY_CONFIG_HOME` exists so a container
 * or a test can point state somewhere disposable — a Keychain item is not disposable, and one
 * shared across every config home would make `LOOPKY_CONFIG_HOME=/tmp/x loopky login` overwrite
 * the caller's real session. So the Keychain is used only when the resolved home *is* the
 * platform default, which is also what keeps this testable without touching the real keychain.
 *
 * **`XDG_CONFIG_HOME` does the same thing, and it is the one that will surprise people.**
 * [ConfigHome.resolve] returns `$XDG_CONFIG_HOME/loopky` before it ever reaches
 * [ConfigHome.platformDefault], so a Mac user who exports it — common among exactly the dotfiles
 * crowd this tool is for — gets the file store, never migrates, and is told only by `whoami`'s
 * `session_store`. That follows from the same rule rather than contradicting it: the variable
 * means "keep everything here" too. It is written down here, in [ConfigHome], and in `--help`
 * because nothing about the behaviour announces itself.
 */
internal fun desktopSecureSessionStore(
    configHome: Path,
    secrets: JsonFileStore,
    keychain: Keychain? = if (keychainEligible(configHome)) SecurityCliKeychain() else null,
): SecureSessionStore {
    val file = FileSecureSessionStore(secrets)
    return if (keychain == null) file else MacKeychainSessionStore(keychain, file)
}

/** Whether this host and this [configHome] are the pair the Keychain is used for. */
internal fun keychainEligible(
    configHome: Path,
    macOs: Boolean = isMacOs(),
    toolPresent: Boolean = keychainToolPresent(),
    default: Path = ConfigHome.platformDefault(),
): Boolean = macOs &&
    toolPresent &&
    configHome.toAbsolutePath().normalize() == default.toAbsolutePath().normalize()

/**
 * The session in the macOS Keychain, with the 0600 file underneath it.
 *
 * **The file is read first, and that one ordering is what makes the rest safe.** It is empty
 * whenever the Keychain holds the session, because a successful write clears it — so on the happy
 * path this costs one lookup in an already-loaded map and changes nothing. When it is *not* empty,
 * it is not empty for exactly one reason: a Keychain write failed and this is the newer
 * credential. Preferring it is therefore not a tie-break, it is the correct answer.
 *
 * Reading the Keychain first is the arrangement that cannot be made safe. A second `login` whose
 * write fails leaves the previous account in the Keychain and the new one in the file, and the
 * reader picks the old one — silently, with `session_live` true, because that session really is
 * live. Deleting the stale item before falling back fixes the read but not the host: `write` and
 * `delete` fail together for almost every reason either fails, so on a locked or absent keychain —
 * over SSH, under `launchd`, on a CI runner — the delete fails too and sign-in fails outright, on
 * the exact host the fallback exists for.
 *
 * So the invariant is stated as what a reader can rely on rather than as a count: **when both hold
 * something, the file is the newer one and wins.** The duplicate is temporary either way, since
 * every [load] tries to migrate the file copy up and clears the file when it lands.
 *
 * The file is thus reached three ways — the fallback for a Keychain that will not answer, the
 * migration source for installs predating #213, and the second thing [clear] empties.
 */
internal class MacKeychainSessionStore(
    private val keychain: Keychain,
    private val fallback: SecureSessionStore,
) : SecureSessionStore {

    /**
     * Probed rather than asserted, because this is read by the one command someone runs *when the
     * session has gone missing* — answering "the Keychain" on a Mac whose Keychain is not
     * answering would point them at the wrong place. [Keychain.exists] rather than
     * [Keychain.read], so the probe does not pull the secret through a pipe to answer a question
     * about presence.
     *
     * **Two costs a caller other than `:cli` has to know about, because the signature hides
     * both.** It runs a subprocess, so it can block the calling thread for the full
     * `TIMEOUT_SECONDS` on a wedged `securityd` — every other member of [SecureSessionStore] is
     * `suspend` and hops to `Dispatchers.IO`, and this one is a `val` a SwiftUI or Compose screen
     * can touch on the main thread. And `by lazy` memoises for the process, so a Keychain that
     * unlocks mid-session keeps reporting that it is not answering. Both are free for a process
     * that runs one command and exits, which is the only thing reading it today.
     */
    override val location: String by lazy {
        if (keychain.exists() == null) {
            "${fallback.location} — ${keychain.location} is not answering"
        } else {
            keychain.location
        }
    }

    /**
     * A failed write is a fallback and never a refusal.
     *
     * Nothing is deleted from the Keychain here and nothing throws: whatever it still holds is
     * older than what is going into the file, and [load] reads the file first, so a stale item
     * cannot win. It is cleaned up by the next [load] that finds the Keychain answering again,
     * which overwrites it with the newer session on its way past.
     */
    override suspend fun save(session: Session) = withContext(Dispatchers.IO) {
        keychain.write(encode(session)).fold(
            // Re-established rather than assumed. `JsonFileStore.persist` rethrows, so this clear
            // can fail on a full or read-only disk — and it would leave the Keychain holding the
            // *new* session and the file an *older* one, which is the single arrangement the
            // file-first ordering assumes cannot exist. It fails loudly at first, but not
            // permanently: once the disk recovers, `storedInFile()` migrates the older credential
            // back over the newer one and nothing is left to notice. A `save` that also fails
            // throws, which is the honest outcome.
            onSuccess = {
                runSuspendCatching { fallback.clear() }.getOrElse { fallback.save(session) }
            },
            onFailure = {
                Log.w(TAG, "keychain write failed, keeping the session in ${fallback.location}", it)
                fallback.save(session)
            },
        )
    }

    override suspend fun load(): Session? = withContext(Dispatchers.IO) {
        storedInFile() ?: fromKeychain()
    }

    /**
     * Empties the file first, then reports whether the Keychain item is gone.
     *
     * Sign-out's remote half already reports its own failure all the way up
     * (`SignOutOutcome.revokedRemotely`) so nobody is told "signed out" while the token lives. The
     * local half — the half this method actually promises — used to report nothing at all, so a
     * keychain that refused a delete produced "Signed out." over an item still sitting in it.
     *
     * A refused delete is only a failure if there is something to fail about: a Keychain that
     * answers [KeychainRead.Missing] holds nothing, whatever it thinks of the delete. That check
     * is what stops a clean sign-out on a file-only host from reporting a missing credential as a
     * surviving one.
     */
    override suspend fun clear() = withContext(Dispatchers.IO) {
        fallback.clear()
        keychain.delete().getOrElse { failure ->
            if (keychain.exists() != false) throw failure
        }
    }

    /**
     * The file copy, migrated up on the way past.
     *
     * The migration is the same call for both of its jobs: it moves a pre-#213 session into the
     * Keychain, and it overwrites whatever stale item a failed write left behind. Best-effort —
     * on a Keychain that is still not answering the session is simply served from the file again.
     */
    private suspend fun storedInFile(): Session? {
        val session = fallback.load() ?: return null
        keychain.write(encode(session))
            .onSuccess { fallback.clear() }
            .onFailure { Log.w(TAG, "could not move the stored session into the keychain", it) }
        return session
    }

    private fun fromKeychain(): Session? = when (val read = keychain.read()) {
        is KeychainRead.Found -> decode(read.value)
        KeychainRead.Missing -> null
        is KeychainRead.Failed -> {
            Log.w(TAG, "keychain read failed (${read.message}) and ${fallback.location} is empty")
            null
        }
    }

    /**
     * Base64 over the same JSON every other store writes, so the value is one `argv`-safe token —
     * see [SecurityCliKeychain] for why that is a requirement of the write path rather than taste.
     */
    private fun encode(session: Session): String {
        val json = sessionStoreJson.encodeToString(StoredSession.fromDomain(session))
        return Base64.getEncoder().encodeToString(json.toByteArray())
    }

    private fun decode(value: String): Session? = runCatching {
        val json = String(Base64.getDecoder().decode(value))
        sessionStoreJson.decodeFromString<StoredSession>(json).toDomain()
    }.getOrElse {
        // Same posture as the file store's unreadable-JSON path: the credential is gone either
        // way, and the choice is between starting signed out and not starting.
        Log.w(TAG, "the keychain item is not a session; treating it as absent", it)
        null
    }

    private companion object {
        const val TAG = "Loopky/KeychainSession"
    }
}
