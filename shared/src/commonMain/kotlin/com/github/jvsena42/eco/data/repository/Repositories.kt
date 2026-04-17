package com.github.jvsena42.eco.data.repository

import com.github.jvsena42.eco.domain.model.Card
import com.github.jvsena42.eco.domain.model.Deck
import com.github.jvsena42.eco.domain.model.ImportDraft
import com.github.jvsena42.eco.domain.model.MediaRef
import com.github.jvsena42.eco.domain.model.PubkyIdentity
import com.github.jvsena42.eco.domain.model.PubkyUri
import com.github.jvsena42.eco.domain.model.Session
import com.github.jvsena42.eco.domain.model.SrsState
import com.github.jvsena42.eco.domain.model.Tag

interface IdentityRepository {
    suspend fun currentSession(): Session?
    suspend fun loadPersistedSession(): Session?
    suspend fun signIn(): Result<Session>
    suspend fun signOut(): Result<Unit>

    /**
     * Two-step Pubky Ring sign-in that hands control of "open the deeplink" back to the caller
     * so the ViewModel — not the repo — owns the UI effect.
     *
     * 1. [beginSignIn] calls `startAuthFlow` and returns the auth URL to hand to the OS.
     * 2. The caller opens the URL and then awaits [AuthFlowHandle.complete], which blocks on
     *    `awaitAuthApproval`, parses the callback URL, persists the session, and returns it.
     */
    suspend fun beginSignIn(capabilities: String = DEFAULT_CAPABILITIES): Result<AuthFlowHandle>

    /** Fetch the pubky.app profile for any user (public read). */
    suspend fun fetchProfile(pubky: String): Result<PubkyIdentity>

    /** Update the current user's pubky.app profile via session-authenticated PUT. */
    suspend fun updateProfile(name: String?, bio: String?): Result<PubkyIdentity>

    companion object {
        const val DEFAULT_CAPABILITIES = "/pub/echo/:rw,/pub/pubky.app/:rw"
    }
}

interface AuthFlowHandle {
    val authUrl: String
    suspend fun complete(): Result<Session>
}

/**
 * Deck persistence against the Pubky homeserver (canonical) and the local cache.
 *
 * Layout on the homeserver (see `docs/Architecture.md §8.0`):
 * ```
 * /pub/echo/decks/{deckId}/manifest.json
 * /pub/echo/decks/{deckId}/cards/{cardId}.json
 * /pub/echo/decks/{deckId}/media/{sha256}.{ext}
 * ```
 */
interface DeckRepository {
    suspend fun getLocal(id: String): Deck?
    suspend fun fetchRemote(authorPubky: String, deckId: String): Result<Deck>
    suspend fun publish(deck: Deck, cards: List<Card>): Result<Deck>
    suspend fun updateMetadata(deck: Deck): Result<Deck>
    suspend fun delete(deckId: String): Result<Unit>
    suspend fun listOwned(): List<Deck>

    /** Pull-only sync driven by the manifest `updated_at` diff. */
    suspend fun sync(deckId: String): Result<Deck>
}

interface CardRepository {
    suspend fun listByDeck(deckId: String): List<Card>
    suspend fun get(deckId: String, cardId: String): Card?
    suspend fun upsert(card: Card): Result<Unit>
    suspend fun delete(deckId: String, cardId: String): Result<Unit>
}

interface ImportRepository {
    fun currentDraft(): ImportDraft?
    suspend fun parse(rawText: String): Result<ImportDraft>
    fun clear()
}

interface TagRepository {
    suspend fun putTag(deckUri: PubkyUri, tag: Tag): Result<Unit>
    suspend fun removeTag(deckUri: PubkyUri, tag: Tag): Result<Unit>
    suspend fun trending(): List<Tag>
}

interface DiscoveryRepository {
    suspend fun decksByTag(tag: Tag): List<Deck>
    suspend fun decksFromFollowing(): List<Deck>
}

interface SrsRepository {
    suspend fun dueToday(): List<Card>
    suspend fun stateFor(cardId: String): SrsState?
    suspend fun upsert(state: SrsState): Result<Unit>
}

/**
 * Blob storage for image and audio media referenced by cards. Blobs live under the owning
 * deck's Pubky path (`/pub/echo/decks/{deckId}/media/{sha256}.{ext}`) so they sync with the
 * deck and dedupe by content hash.
 */
interface MediaRepository {
    suspend fun putImage(deckId: String, bytes: ByteArray, mime: String): Result<MediaRef.Image>
    suspend fun putAudio(deckId: String, bytes: ByteArray, mime: String): Result<MediaRef.Audio>
    suspend fun get(deckId: String, ref: MediaRef): Result<ByteArray>
    suspend fun delete(deckId: String, ref: MediaRef): Result<Unit>
}
