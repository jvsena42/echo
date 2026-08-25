package com.github.jvsena42.loopky.data.pubky

/**
 * Remembers which account a repository's in-memory caches were filled for, so they cannot outlive
 * it.
 *
 * Repositories cache per-session state — who you follow, which decks you subscribe to, your review
 * states — in plain maps that live as long as the process. Signing out clears the session but not
 * those maps, so the next account to sign in inherits them: a brand-new pubky that owns nothing
 * and follows nobody opens Home and finds the previous user's decks waiting for it, because the
 * cache is consulted *before* anything reads whose session it is.
 *
 * Only caches whose contents depend on **who is asking** need this. A deck or a card is public
 * content keyed by its own id, identical whoever reads it, so those caches are account-independent
 * and deliberately not stamped.
 *
 * Keyed on the pubky rather than a change counter so a session *revalidation* — same account, new
 * secret — keeps the caches it just filled, and only a genuine account change drops them. Signing
 * out counts as a change: the stamp goes to null, and the next sign-in cannot match it.
 *
 * **Not thread-safe on its own.** Every call must happen under the same lock that guards the
 * caches it stamps, which is also the only way [changed] and the eviction it triggers can be
 * atomic with respect to each other.
 */
internal class AccountStamp(private val session: SessionProvider) {

    private var owner: String? = null
    private var stamped = false

    /**
     * True when the caches this stamps belong to a different account than the one signed in now,
     * and the caller must drop them before reading.
     *
     * False before anything has been stamped: there is nothing cached to be wrong yet, and
     * treating "empty" as "stale" would evict on every cold read.
     */
    fun changed(): Boolean = stamped && owner != session.current()?.identity?.pubky

    /** Records the current account as the one the caches now hold data for. */
    fun mark() {
        owner = session.current()?.identity?.pubky
        stamped = true
    }
}
