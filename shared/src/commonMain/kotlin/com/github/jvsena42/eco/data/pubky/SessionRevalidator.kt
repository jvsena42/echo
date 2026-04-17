package com.github.jvsena42.eco.data.pubky

import com.github.jvsena42.eco.domain.model.Session

/**
 * Narrow contract for revalidating an expired session against the homeserver.
 * Repos inject this alongside [SessionProvider] to transparently recover from
 * session expiry without depending on `IdentityRepository` (avoiding a dependency cycle).
 */
fun interface SessionRevalidator {
    /**
     * Revalidates the current session with the homeserver. On success the
     * [MutableSessionProvider] and persistent store are updated so subsequent
     * reads via [SessionProvider.current] return the refreshed session.
     *
     * @return the refreshed [Session], or failure if the session is revoked.
     */
    suspend fun revalidate(): Result<Session>
}
