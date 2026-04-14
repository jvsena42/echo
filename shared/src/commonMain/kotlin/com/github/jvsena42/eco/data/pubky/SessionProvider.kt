package com.github.jvsena42.eco.data.pubky

import com.github.jvsena42.eco.domain.model.Session

/**
 * Read-only view over the currently signed-in session. Repositories need it to author writes
 * against the homeserver without depending on [com.github.jvsena42.eco.data.repository.IdentityRepository]
 * directly (that would be a cycle — IdentityRepository itself may end up needing repos).
 */
interface SessionProvider {
    fun current(): Session?
}

internal fun SessionProvider.requireSession(): Session =
    current() ?: error("Not signed in")
