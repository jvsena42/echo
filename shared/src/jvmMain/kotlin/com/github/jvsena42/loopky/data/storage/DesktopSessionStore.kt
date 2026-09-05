package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.platform.isMacOs
import com.github.jvsena42.loopky.util.Log
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
 * The file is not dead weight, and it is reached three ways. It is the **fallback** for a Mac
 * whose Keychain will not answer — a locked keychain over SSH, a CI runner with none — because a
 * client that cannot store a session is worse than one that stores it the way it did last
 * release. It is the **migration** source, since every macOS install before this one has a live
 * session in `secrets.json` and an upgrade that silently signed everybody out would be read as a
 * bug. And it is what [clear] has to empty as well as the Keychain.
 *
 * The one rule threaded through all three: **never leave a usable credential in both places.** A
 * successful Keychain write clears the file, and a successful migration clears it too.
 */
internal class MacKeychainSessionStore(
    private val keychain: Keychain,
    private val fallback: SecureSessionStore,
) : SecureSessionStore {

    /**
     * Probed rather than asserted, because this is read by the one command someone runs *when the
     * session has gone missing* — answering "the Keychain" on a Mac whose Keychain is not
     * answering would point them at the wrong place. One extra `security` call, on `whoami` and
     * `login` only.
     */
    override val location: String by lazy {
        if (keychain.read() is KeychainRead.Failed) {
            "${fallback.location} — ${keychain.location} is not answering"
        } else {
            keychain.location
        }
    }

    override suspend fun save(session: Session) = withContext(Dispatchers.IO) {
        keychain.write(encode(session)).fold(
            onSuccess = { fallback.clear() },
            onFailure = {
                Log.w(TAG, "keychain write failed, keeping the session in ${fallback.location}", it)
                fallback.save(session)
            },
        )
    }

    override suspend fun load(): Session? = withContext(Dispatchers.IO) {
        when (val read = keychain.read()) {
            is KeychainRead.Found -> decode(read.value)
            KeychainRead.Missing -> adoptStoredFile()
            is KeychainRead.Failed -> {
                Log.w(TAG, "keychain read failed (${read.message}); reading ${fallback.location}")
                fallback.load()
            }
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        keychain.delete()
        fallback.clear()
    }

    /** Move a pre-#213 session into the Keychain the first time it is read back. */
    private suspend fun adoptStoredFile(): Session? {
        val session = fallback.load() ?: return null
        keychain.write(encode(session))
            .onSuccess { fallback.clear() }
            .onFailure { Log.w(TAG, "could not move the stored session into the keychain", it) }
        return session
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
