package com.github.jvsena42.echo.data.repository

import com.github.jvsena42.echo.domain.model.Card
import com.github.jvsena42.echo.domain.model.Deck
import com.github.jvsena42.echo.domain.model.DraftCardImage
import com.github.jvsena42.echo.domain.model.ImportDraft
import com.github.jvsena42.echo.domain.model.MediaRef
import com.github.jvsena42.echo.domain.model.ParsedRow
import com.github.jvsena42.echo.domain.model.PubkyIdentity
import com.github.jvsena42.echo.domain.model.PubkyUri
import com.github.jvsena42.echo.domain.model.Separator
import com.github.jvsena42.echo.domain.model.Session
import com.github.jvsena42.echo.domain.model.SrsGrade
import com.github.jvsena42.echo.domain.model.SrsState
import com.github.jvsena42.echo.domain.model.Tag
import com.github.jvsena42.echo.domain.model.TriageDecision
import kotlinx.coroutines.flow.SharedFlow

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

    /**
     * Fetch the pubky.app profile for any user (public read).
     *
     * Served from a session-scoped cache so a deck grid can resolve every author's name without
     * one homeserver round trip per tile per render. Pass [forceRefresh] where staleness would be
     * visible — a profile screen the user pulled to refresh.
     */
    suspend fun fetchProfile(pubky: String, forceRefresh: Boolean = false): Result<PubkyIdentity>

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
    /**
     * Emits after every local mutation ([publish], [updateMetadata], [delete]) so screens
     * showing a deck list can reload. Publish and delete happen on their own full-screen
     * destinations, and the tab pages that list decks stay composed behind them, so without
     * this signal a freshly published deck does not appear until the process restarts.
     */
    val changes: SharedFlow<Unit>

    suspend fun getLocal(id: String): Deck?
    suspend fun fetchRemote(authorPubky: String, deckId: String): Result<Deck>
    suspend fun publish(deck: Deck, cards: List<Card>): Result<Deck>
    suspend fun updateMetadata(deck: Deck): Result<Deck>
    suspend fun delete(deckId: String): Result<Unit>
    suspend fun listOwned(): List<Deck>

    /** Public decks for any author (their homeserver, read-only). Powers friend profiles + Discover. */
    suspend fun listByAuthor(authorPubky: String): List<Deck>

    /** Pull-only sync driven by the manifest `updated_at` diff. */
    suspend fun sync(deckId: String): Result<Deck>
}

interface CardRepository {
    /** Whatever this session has already loaded or written. Empty on a cold cache. */
    suspend fun listByDeck(deckId: String): List<Card>

    /**
     * The deck's cards read from *its author's* homeserver, in `cardIndex` order, refreshing the
     * cache as it goes. Read-only, and not limited to decks you own — which is what makes a
     * shared deck browsable. Records already cached at or past the index's `updatedAt` are not
     * re-fetched. A single unreadable card is skipped; failing to read *any* of them is a
     * connectivity failure and fails the call rather than passing off an empty deck as real.
     */
    suspend fun fetchByDeck(deck: Deck): Result<List<Card>>

    suspend fun get(deckId: String, cardId: String): Card?
    suspend fun upsert(card: Card): Result<Unit>
    suspend fun delete(deckId: String, cardId: String): Result<Unit>
}

interface ImportRepository {
    fun currentDraft(): ImportDraft?

    /**
     * [separator] overrides auto-detection (spec §5.2 "tap to change"); null auto-detects.
     */
    suspend fun parse(rawText: String, separator: Separator? = null): Result<ImportDraft>

    /** Per-row keep/discard decisions made during triage (default [TriageDecision.Keep]). */
    fun decisions(): Map<Int, TriageDecision>
    fun setDecision(rowIndex: Int, decision: TriageDecision)

    /** Override a draft row's front/back text (triage edit). */
    fun updateRow(rowIndex: Int, front: String, back: String)

    /** Attach/replace an image on a draft card side (triage); `null` clears it. */
    fun setRowImage(rowIndex: Int, isFront: Boolean, image: DraftCardImage?)

    /** The image attached to a draft card side during triage, if any. */
    fun rowImage(rowIndex: Int, isFront: Boolean): DraftCardImage?

    /** The rows kept after triage, with any edits applied. */
    fun keptRows(): List<ParsedRow>

    fun clear()
}

/**
 * Deck tagging via the pubky.app native tag primitive (records on the tagger's homeserver,
 * indexed network-wide by Pubky Nexus). Trending reads come from the Nexus REST API; local
 * tag filtering over visible decks stays on [DiscoveryRepository.decksByTag].
 */
interface TagRepository {
    suspend fun putTag(deckUri: PubkyUri, tag: Tag): Result<Unit>
    suspend fun removeTag(deckUri: PubkyUri, tag: Tag): Result<Unit>

    /** Network-wide trending tags from the Nexus indexer; empty on network failure. */
    suspend fun trending(): List<Tag>
}

/**
 * Social graph + discovery. Follows use the pubky.app native primitive (see
 * [com.github.jvsena42.echo.data.pubky.PubkyPaths.follow]); the repo owns the follow/unfollow logic
 * and feed building. Tag discovery is a local filter over decks the user can already reach
 * (following + own) — global tag search is deferred pending an indexer (see [TagRepository.trending]).
 */
interface DiscoveryRepository {
    /** Pubkys the current user follows. */
    suspend fun following(): List<String>
    suspend fun isFollowing(pubky: String): Boolean
    suspend fun followUser(pubky: String): Result<Unit>
    suspend fun unfollowUser(pubky: String): Result<Unit>

    /** Decks published by people the user follows, newest first. */
    suspend fun decksFromFollowing(): List<Deck>

    /** Visible decks (following + own) carrying [tag]. */
    suspend fun decksByTag(tag: Tag): List<Deck>
}

/**
 * Spaced-repetition state, Pubky-backed (canonical) with an in-memory session cache. Records live at
 * `/pub/echo/decks/{deckId}/srs/{cardId}.json`. The repo owns SRS grading (the SM-2-lite scheduler in
 * [com.github.jvsena42.echo.domain.model] is invoked here, not in ViewModels).
 */
interface SrsRepository {
    /**
     * Emits after every review state write ([review], [upsert]) so screens showing due counts
     * can reload. Studying happens on its own full-screen destination while Home stays composed
     * behind it, so without this signal Home keeps the count it had before the session.
     */
    val changes: SharedFlow<Unit>

    /** All cards due for review across every owned deck (new cards count as due). */
    suspend fun dueToday(): List<Card>

    /** Cards due for review within a single deck. */
    suspend fun dueForDeck(deckId: String): List<Card>

    /**
     * When the soonest not-yet-due card comes up for review, or null if nothing is scheduled.
     * Lets Home say "next review in 4h" instead of showing an empty queue with no explanation.
     */
    suspend fun nextDueAt(): Long?

    /** Cached review state for a card, if it has been loaded this session. */
    suspend fun stateFor(cardId: String): SrsState?

    /** Grade a card: compute the next state via the scheduler, persist it, and return it. */
    suspend fun review(card: Card, grade: SrsGrade): Result<SrsState>

    /** Persist a review state. [deckId] scopes the homeserver path. */
    suspend fun upsert(deckId: String, state: SrsState): Result<Unit>
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
