package com.github.jvsena42.loopky.data.storage

import android.content.Context
import com.github.jvsena42.loopky.util.Log
import com.liftric.kvault.KVault

/**
 * Open the keystore-backed vault named [service], recovering from an undecryptable one.
 *
 * `KVault` wraps `EncryptedSharedPreferences`, which throws from *construction* when the XML on
 * disk cannot be decrypted with the Android Keystore master key. Every caller here builds its vault
 * in a field initialiser, so that throw surfaces the moment Koin resolves the store — which is
 * during onboarding, on a device that has done nothing wrong.
 *
 * The way it happens is a restore. The vault file is ordinary shared prefs as far as backup is
 * concerned, but the key that decrypts it is hardware-bound and does not travel. Loopky now sets
 * `allowBackup="false"` so it should not arise again, but installs that predate that change carry
 * the restored file already, and a Keystore can also be invalidated on-device (a factory reset of
 * secure hardware, a device-admin wipe).
 *
 * So: delete the unreadable file and open a fresh vault. The stored values are gone either way —
 * they were unreadable ciphertext — and the choice is between an app that starts signed out and an
 * app that cannot start. Returns null only if even the second attempt fails, and every caller
 * degrades to "no stored value" rather than throwing.
 */
internal fun openVaultOrNull(context: Context, service: String): KVault? {
    runCatching { KVault(context, service) }.onSuccess { return it }.onFailure {
        Log.e(TAG, "vault '$service' unreadable, resetting it", it)
    }
    return runCatching {
        context.deleteSharedPreferences(service)
        KVault(context, service)
    }.onFailure {
        Log.e(TAG, "vault '$service' unavailable even after reset", it)
    }.getOrNull()
}

/**
 * Read [key] from a vault that may not exist, treating any failure as absence.
 *
 * A decrypt can fail per-entry as well as per-file, and a value that cannot be read is the same
 * thing as no value to every caller here.
 */
internal fun KVault?.stringOrNull(key: String): String? =
    this?.let { runCatching { it.string(key) }.getOrNull() }

private const val TAG = "Loopky/Vaults"
