package com.github.jvsena42.eco.data.storage

import com.github.jvsena42.eco.domain.model.Capability
import com.github.jvsena42.eco.domain.model.PubkyIdentity
import com.github.jvsena42.eco.domain.model.Session
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Platform-keystore-backed store for the signed-in [Session]. Android wraps
 * `EncryptedSharedPreferences` (master key in the Android Keystore); iOS wraps the native
 * Keychain (`kSecClassGenericPassword`). Both go through Liftric KVault.
 */
interface SecureSessionStore {
    suspend fun save(session: Session)
    suspend fun load(): Session?
    suspend fun clear()
}

internal const val SESSION_STORAGE_KEY = "session.v1"
internal const val SESSION_SERVICE_NAME = "echo.session"

internal val sessionStoreJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
internal data class StoredSession(
    val pubky: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val sessionSecret: String,
    val capabilities: List<String> = emptyList(),
    val homeserver: String,
) {
    fun toDomain(): Session = Session(
        identity = PubkyIdentity(
            pubky = pubky,
            displayName = displayName,
            avatarUrl = avatarUrl,
            bio = bio,
        ),
        sessionSecret = sessionSecret,
        capabilities = capabilities.map(::Capability),
        homeserver = homeserver,
    )

    companion object {
        fun fromDomain(session: Session): StoredSession = StoredSession(
            pubky = session.identity.pubky,
            displayName = session.identity.displayName,
            avatarUrl = session.identity.avatarUrl,
            bio = session.identity.bio,
            sessionSecret = session.sessionSecret,
            capabilities = session.capabilities.map { it.value },
            homeserver = session.homeserver,
        )
    }
}
