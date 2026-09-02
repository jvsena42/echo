package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.util.Log
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A string-keyed store held in one JSON file, mode 0600 — the desktop stand-in for the
 * `SharedPreferences` / `EncryptedSharedPreferences` pair the Android stores sit on.
 *
 * Two instances, not one, so the line Android draws between a preference and a credential
 * survives: [ConfigHome] holds a `preferences.json` and a `secrets.json`. Both are 0600 — the
 * split is so that a `preferences.json` stays a file a human can safely open, read and hand to a
 * bug report, which is not true of the other one. What neither reproduces is encryption at rest;
 * see [ConfigHome] for why that is the deliberate choice on a headless box rather than an
 * oversight.
 *
 * Values are cached in memory and the whole file is rewritten on every change: this holds a
 * session, a handful of preferences and a review journal, not a database. The rewrite is atomic
 * (temp file, then `ATOMIC_MOVE`) so a process killed mid-write leaves the previous contents
 * rather than a truncated file — which matters, because one of the things kept here is the
 * journal of reviews that have not reached the homeserver.
 */
internal class JsonFileStore(private val file: Path) {

    private val lock = ReentrantLock()
    private var cache: MutableMap<String, String>? = null

    fun string(key: String): String? = lock.withLock { load()[key] }

    fun set(key: String, value: String) = lock.withLock {
        val map = load()
        if (map[key] == value) return@withLock
        map[key] = value
        persist(map)
    }

    fun remove(key: String) = lock.withLock {
        val map = load()
        if (map.remove(key) == null) return@withLock
        persist(map)
    }

    private fun load(): MutableMap<String, String> = cache ?: read().also { cache = it }

    private fun read(): MutableMap<String, String> {
        if (!Files.exists(file)) return mutableMapOf()
        // A file we can no longer decode is treated as empty rather than fatal, matching the
        // Android vaults: the stored values are gone either way, and the choice is between a
        // client that starts signed out and one that cannot start.
        return runCatching {
            json.decodeFromString<Map<String, String>>(Files.readString(file)).toMutableMap()
        }.getOrElse {
            Log.w(TAG, "$file is unreadable — starting from empty", it)
            mutableMapOf()
        }
    }

    private fun persist(map: Map<String, String>) {
        ConfigHome.prepare(file.parent)
        val temp = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
        runCatching {
            Files.writeString(temp, json.encodeToString(map))
            restrictToOwner(temp)
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure {
            Files.deleteIfExists(temp)
            throw it
        }
    }

    /**
     * Best-effort 0600. A filesystem with no POSIX permissions (a Windows share, some container
     * overlays) cannot express it; failing the write there would cost the user their session for
     * a guarantee that host was never going to give.
     */
    private fun restrictToOwner(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(FILE_MODE))
        }.onFailure { Log.d(TAG, "could not set $FILE_MODE on $path: ${it.message}") }
    }

    private companion object {
        const val TAG = "Loopky/JsonFileStore"
        const val FILE_MODE = "rw-------"
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Non-secret settings — the desktop counterpart of Android's plain `SharedPreferences`. */
internal fun preferencesStore(home: Path): JsonFileStore =
    JsonFileStore(ConfigHome.prepare(home).resolve("preferences.json"))

/**
 * The session secret, a held key and the signup token — Android's `SECRETS_SERVICE_NAME` vault.
 * Separate from [preferencesStore] so that clearing preferences cannot discard a credential.
 */
internal fun secretsStore(home: Path): JsonFileStore =
    JsonFileStore(ConfigHome.prepare(home).resolve("secrets.json"))
