package com.github.jvsena42.loopky.data.repository

import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DraftCardImage
import com.github.jvsena42.loopky.domain.model.ImportDraft
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.ParsedRow
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.PubkyUri
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Separator
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.domain.model.SrsState
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.domain.model.TriageDecision
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
        const val DEFAULT_CAPABILITIES = "/pub/loopky/:rw,/pub/pubky.app/:rw"
    }
}

/**
 * How far a [DeckRepository.publish] has got. [chunksWritten] counts card chunks only; the
 * manifest write is reported by [done].
 */
data class PublishProgress(
    val chunksWritten: Int,
    val totalChunks: Int,
    val cardsWritten: Int,
    val totalCards: Int,
    val done: Boolean = false,
) {
    /** 0f..1f, reserving the last slice for the manifest write. */
    val fraction: Float
        get() = when {
            done -> 1f
            totalChunks <= 0 -> 0f
            else -> chunksWritten.toFloat() / (totalChunks + 1).toFloat()
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
 * /pub/loopky/decks/{deckId}/manifest.json     — metadata + chunk table, no card index
 * /pub/loopky/decks/{deckId}/cards/{n}.json    — up to CHUNK_SIZE cards per record
 * /pub/loopky/decks/{deckId}/media/{sha256}.{ext}
 * ```
 */
@Suppress("TooManyFunctions")
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

    /**
     * [publish], reporting progress as it goes. A 20k-card import is ~201 uploads; a spinner
     * cannot say how far along that is.
     *
     * [onProgress] is called from the publishing coroutine, so keep it cheap.
     */
    suspend fun publish(
        deck: Deck,
        cards: List<Card>,
        onProgress: (PublishProgress) -> Unit,
    ): Result<Deck>
    suspend fun updateMetadata(deck: Deck): Result<Deck>
    suspend fun delete(deckId: String): Result<Unit>

    /**
     * Add or replace a single card, rewriting only its chunk and patching the manifest's entry
     * for that chunk. This is the cheap edit path the chunked layout exists for: ~63 KB for a
     * card edit in a 20k-card deck, against ~1.5 MB when the manifest carried every card.
     *
     * A new card appends to the last chunk with room. Returns the updated deck so callers can
     * refresh their view of `cardCount` / `chunks`.
     */
    suspend fun upsertCard(deckId: String, card: Card): Result<Deck>

    /** Remove a single card, rewriting its chunk and patching the manifest. */
    suspend fun deleteCard(deckId: String, cardId: String): Result<Deck>
    suspend fun listOwned(): List<Deck>

    /** Public decks for any author (their homeserver, read-only). Powers friend profiles + Discover. */
    suspend fun listByAuthor(authorPubky: String): List<Deck>

    /** Pull-only sync driven by the manifest `updated_at` diff. */
    suspend fun sync(deckId: String): Result<Deck>

    // ── Following someone else's deck (#33) ──────────────────────────────
    //
    // Deliberately here rather than on DiscoveryRepository, which owns *user* follows. Three
    // reasons: [listFollowed] returns decks and has to merge with [listOwned] behind this one
    // [changes] flow; [sync] resolves a followed deck's author from the subscription record and
    // lives in this implementation; and DiscoveryRepository already depends on DeckRepository, so
    // putting it there would make the dependency cycle.
    //
    // Following stores a subscription pointing at the owner's deck — you get their updates and you
    // cannot edit it. Copying a deck into your own account is [clone], which is the opposite trade.

    /** Subscribe to [deck]. Idempotent: re-following overwrites one record. */
    suspend fun followDeck(deck: Deck): Result<Unit>

    /**
     * Drop the subscription — and only that. Review state stays: it is yours, not the author's, and
     * re-following must not silently reset your progress (Architecture.md §8.3).
     */
    suspend fun unfollowDeck(authorPubky: String, deckId: String): Result<Unit>

    suspend fun isFollowingDeck(deckId: String): Boolean

    /**
     * Decks you follow, fetched from their authors' homeservers. Unreadable decks are dropped
     * rather than failing the call — an author who deleted a deck you follow must not empty your
     * whole library — but a listing that resolved nothing at all still throws, for the same reason
     * [listByAuthor] does.
     */
    suspend fun listFollowed(): List<Deck>

    /** True when [deckId] is followed and its author has published changes since you last opened it. */
    suspend fun hasUpdate(deckId: String): Boolean

    /** Record that you have seen [deck] at its current `updated_at`. No-op for decks you don't follow. */
    suspend fun markSeen(deck: Deck)

    /**
     * Copy [source] into your own account as an independent deck: new deck id, new card ids, you as
     * the author, and `source` provenance pointing back at the original.
     *
     * A fork, not a subscription — the opposite trade from [followDeck]. It never receives the
     * original's later edits, and editing it never touches the original. **New card ids are what
     * keep SRS state from bleeding** between the two copies.
     *
     * Media is copied **by reference**: card refs are pinned to the source author's blobs rather
     * than re-uploaded, so cloning an Anki-sized deck stays instant. See
     * [com.github.jvsena42.loopky.data.pubky.absolutizedTo] and [MediaRepository.rehost].
     *
     * Commits through [publish] — no parallel commit path, the same rule Paste-to-Import follows.
     * Unfollows [source] if you were following it: you own a copy now.
     */
    suspend fun clone(source: Deck): Result<Deck>
}

/**
 * Chunk reads and raw chunk writes. Cards live batched in `cards/{n}.json`, so this interface is
 * deliberately chunk-shaped rather than card-shaped.
 *
 * Single-card mutations are **not** here: writing a chunk also has to move the manifest's
 * `chunks[n].updated_at` and `card_count`, and splitting those two writes across classes is what
 * let the old per-card layout drift out of sync. Use [DeckRepository.upsertCard] /
 * [DeckRepository.deleteCard], which own both halves.
 */
interface CardRepository {
    /** Whatever this session has already loaded or written, in study order. Empty on a cold cache. */
    suspend fun listByDeck(deckId: String): List<Card>

    /**
     * The deck's cards read from *its author's* homeserver, refreshing the cache as it goes.
     * Read-only, and not limited to decks you own — which is what makes a shared deck browsable.
     * Chunks whose `updated_at` has not moved since the last read are not re-fetched. A single
     * unreadable chunk keeps its previously cached cards; failing to read *any* of them is a
     * connectivity failure and fails the call rather than passing off an empty deck as real.
     */
    suspend fun fetchByDeck(deck: Deck): Result<List<Card>>

    suspend fun get(deckId: String, cardId: String): Card?

    /** Overwrite one chunk record; an empty [cards] deletes it. Caller updates the manifest. */
    suspend fun writeChunk(deckId: String, chunk: Int, cards: List<Card>): Result<Unit>

    /** Read a single chunk of [deck] from its author's homeserver, caching what it finds. */
    suspend fun readChunk(deck: Deck, chunk: Int): Result<List<Card>>

    /**
     * Which chunk holds [cardId], or null if this session hasn't seen it.
     *
     * Recorded when a chunk is read or written. Without it, locating a card to edit would mean
     * reading every chunk in the deck — 200 requests for a 20k-card deck, which is the very cost
     * chunking exists to remove.
     */
    suspend fun chunkOf(deckId: String, cardId: String): Int?

    /** Drop a card from the in-memory cache without touching the homeserver. */
    suspend fun evict(deckId: String, cardId: String)
}

interface ImportRepository {
    fun currentDraft(): ImportDraft?

    /**
     * [separator] overrides auto-detection (spec §5.2 "tap to change"); null auto-detects.
     */
    suspend fun parse(rawText: String, separator: Separator? = null): Result<ImportDraft>

    /**
     * [parse] with file-sized limits, for importing an exported deck rather than a paste.
     *
     * Anki's "Notes in Plain Text" export is tab-separated, which the existing separator rules
     * already handle (spec §6 rule 3) — so this is the same parser, not a second one, and needs no
     * new dependencies. What differs is the caps: a 20k-card export is ~2 MB of text and has no
     * business going through the paste box.
     *
     * The result skips swipe-triage in favour of a summary screen; nobody swipes 20,000 cards.
     * Rows missing a front or a back are discarded rather than kept, since there is no triage step
     * to fix them in and `publish` rejects an empty side.
     *
     * [suggestedTitle] prefills the commit screen. It lands on the draft rather than travelling as
     * a nav argument because every other part of this handoff — rows, decisions, row images —
     * already flows through this repository. [parse] takes none, which is what keeps a paste's
     * title empty by construction.
     */
    suspend fun parseBulk(
        rawText: String,
        separator: Separator? = null,
        suggestedTitle: String? = null,
    ): Result<ImportDraft>

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
 * Tagging via the pubky-app-specs tag primitive (records on the tagger's homeserver, indexed
 * network-wide by Pubky Nexus). Any URI can be a subject: a deck manifest or a user's profile.
 * Trending reads come from the Nexus REST API; local tag filtering over visible decks stays on
 * [DiscoveryRepository.decksByTag].
 *
 * The impl picks the record's namespace from the subject, which decides how Nexus indexes it —
 * see [com.github.jvsena42.loopky.data.repository.impl.TagRepositoryImpl] and Architecture.md §7.7.
 */
interface TagRepository {
    /** User-authored labels. Fails for [ReservedTags] — those are Loopky's, not the user's. */
    suspend fun putTag(subjectUri: PubkyUri, tag: Tag): Result<Unit>
    suspend fun removeTag(subjectUri: PubkyUri, tag: Tag): Result<Unit>

    /**
     * Write one of Loopky's own index labels. Separate from [putTag] so the reserved namespace has
     * exactly one door, and a user-entered label can never come through it. Fails for any tag that
     * is not in [ReservedTags.ALL].
     *
     * Idempotent: tag ids are content-derived, so re-writing the same label on the same subject
     * overwrites one record rather than accumulating them.
     */
    suspend fun putReservedTag(subjectUri: PubkyUri, tag: Tag): Result<Unit>
    suspend fun removeReservedTag(subjectUri: PubkyUri, tag: Tag): Result<Unit>

    /**
     * Topic labels carried by Loopky decks network-wide, most-decks-first — the chip row on
     * Discover.
     *
     * Aggregated client-side rather than read from the indexer, because deck tags are indexed as
     * *resources* and `/v0/tags/hot` only ever sees `Post|User` targets (Architecture.md §7.7
     * point 3). There is no deck-tag trending to ask for, but the resource stream returns each
     * deck's whole tag list in one response, which is enough to rank topics here.
     *
     * Reserved [ReservedTags] labels are excluded — they are Loopky's index, not topics.
     * Never throws: empty on indexer failure, like [trending].
     *
     * Sees only the top [sampleSize] decks by tagger count, so a topic that lives solely on an
     * unpopular deck is invisible. That is the ceiling of aggregating client-side (#58).
     */
    suspend fun trendingDeckTags(
        sampleSize: Int = DEFAULT_DECK_TAG_SAMPLE,
        limit: Int = DEFAULT_DECK_TAG_LIMIT,
    ): List<Tag>

    /**
     * Loopky subjects carrying [tag] network-wide, most-tagged first. This is the read that makes
     * a deck findable by someone who follows nobody.
     *
     * Untrusted: anyone can tag any URI with any label. Callers must verify a subject resolves to
     * what the label claims before showing it — see [DiscoveryRepository.decksByTagGlobal].
     */
    suspend fun taggedSubjects(tag: Tag, limit: Int = DEFAULT_TAGGED_LIMIT): List<TaggedSubject>

    /** Pubkys that have used [tag] anywhere; for a self-tag this is a directory of accounts. */
    suspend fun taggersOf(tag: Tag, limit: Int = DEFAULT_TAGGERS_LIMIT): List<String>

    /**
     * True when [pubky] applied [tag] to its own profile. The check that separates an account
     * announcing itself from someone else labelling it.
     */
    suspend fun isSelfTagged(pubky: String, tag: Tag): Boolean

    /**
     * Distinct taggers per label on [subjectUri] — "N people follow this deck" without Loopky
     * aggregating anything. Empty when the subject is unindexed or the indexer is unreachable.
     *
     * Approximate by nature (indexer lag, spam): fine to display, never to gate on.
     */
    suspend fun taggerCounts(subjectUri: PubkyUri): Map<Tag, Int>

    companion object {
        const val DEFAULT_TAGGED_LIMIT = 30
        const val DEFAULT_TAGGERS_LIMIT = 20

        /** One indexer page. The resource stream caps at 100; 50 is broad coverage for one request. */
        const val DEFAULT_DECK_TAG_SAMPLE = 50

        /** Chips that fill a scrollable row without becoming a wall. */
        const val DEFAULT_DECK_TAG_LIMIT = 12
    }
}

/** A URI carrying a tag, as the indexer reports it. */
data class TaggedSubject(
    val uri: PubkyUri,
    /** Capped by the indexer — count with [taggersCount], not `taggers.size`. */
    val taggers: List<String>,
    val taggersCount: Int,
)

/**
 * Social graph + discovery. Follows use the pubky.app native primitive (see
 * [com.github.jvsena42.loopky.data.pubky.PubkyPaths.follow]); the repo owns the follow/unfollow logic
 * and feed building. Tag discovery is a local filter over decks the user can already reach
 * (following + own) — global tag search is deferred pending an indexer (see [TagRepository.trending]).
 */
interface DiscoveryRepository {
    /** Pubkys the current user follows. */
    suspend fun following(): List<String>
    suspend fun isFollowing(pubky: String): Boolean
    suspend fun followUser(pubky: String): Result<Unit>
    suspend fun unfollowUser(pubky: String): Result<Unit>

    /**
     * Decks published by people the user follows, newest first. Never the user's own, even if
     * they follow themselves — Library is where those live.
     */
    suspend fun decksFromFollowing(): List<Deck>

    /** Visible decks (following + own) carrying [tag]. */
    suspend fun decksByTag(tag: Tag): List<Deck>

    /**
     * Decks carrying [tag] anywhere on the network, most-tagged first — no follow relationship
     * needed. Backed by the indexer, so entries are verified before being returned: the URI has to
     * be a deck manifest, the tagger has to be its author, and the manifest has to actually fetch
     * and parse. Anything else is dropped silently.
     *
     * The signed-in account's own decks are dropped too — not for trust, but because Library
     * already lists them and a browse full of them shows the user nothing new.
     *
     * Pass [ReservedTags.DECK] to browse every Loopky deck.
     */
    suspend fun decksByTagGlobal(
        tag: Tag,
        limit: Int = TagRepository.DEFAULT_TAGGED_LIMIT,
    ): List<Deck>

    /**
     * Accounts that announced themselves as Loopky users, excluding the signed-in one — the
     * suggested-people source for someone who follows nobody.
     *
     * Verified the same way: kept only if the account self-tagged (tagger == subject) and its
     * profile resolves.
     */
    suspend fun loopkyUsers(
        limit: Int = TagRepository.DEFAULT_TAGGERS_LIMIT,
    ): List<PubkyIdentity>

    /**
     * People worth showing to someone who follows nobody: the [loopkyUsers] directory first, then
     * the authors of [seedDecks] — pass the decks global browse already fetched, so this costs no
     * second browse.
     *
     * The directory alone is not enough in practice. `/v0/tags/hot` and `/v0/tags/taggers/{label}`
     * only surface labels with real traction, so a `loopky-user` self-tag with one tagger comes
     * back empty from the indexer even though the tag exists and the account is indexed (probed
     * against staging, #26). Deck authors are the source that works from the first published deck.
     *
     * A directory entry has only its own claim behind it, so it still has to verify. A deck author
     * is already corroborated — global browse fetched and parsed their manifest — so an author with
     * no published profile is kept under their bare pubky rather than dropped; the UI truncates it,
     * exactly as deck tiles already do for unresolved authors.
     *
     * Excludes the signed-in user and anyone already followed: suggesting those is noise.
     */
    suspend fun suggestedPeople(
        seedDecks: List<Deck>,
        limit: Int = DEFAULT_SUGGESTED_PEOPLE_LIMIT,
    ): List<PubkyIdentity>

    companion object {
        /** A horizontal strip; enough to scroll, few enough to resolve quickly. */
        const val DEFAULT_SUGGESTED_PEOPLE_LIMIT = 12
    }
}

/**
 * Spaced-repetition state, Pubky-backed (canonical) with an in-memory session cache. Records live at
 * `/pub/loopky/decks/{deckId}/srs/{cardId}.json`. The repo owns SRS grading (the SM-2-lite scheduler in
 * [com.github.jvsena42.loopky.domain.model] is invoked here, not in ViewModels).
 */
interface SrsRepository {
    /**
     * Emits the deck id of every review state write ([review], [upsert]) so screens showing due
     * counts can reload. Studying happens on its own full-screen destination while Home and
     * DeckDetail stay composed behind it, so without this signal they keep the counts they had
     * before the session. Deck-scoped screens filter on the id; Home reloads on any of them.
     */
    val changes: SharedFlow<String>

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

    /**
     * Persist a review state. Buffered in memory rather than written immediately — grading 100
     * cards costs a handful of chunk writes instead of 100 record writes. See [flush].
     */
    suspend fun upsert(deckId: String, state: SrsState): Result<Unit>

    /**
     * Write buffered reviews to the homeserver. Called at the end of a study session, and
     * automatically every so often so a crash costs a few cards rather than the whole session.
     */
    suspend fun flush(): Result<Unit>

    /**
     * [flush] on the repository's own scope, for callers that cannot await it.
     *
     * A ViewModel must use this rather than launching a flush itself: `viewModelScope` is
     * cancelled in `onCleared()`, so a flush started as the study screen goes away would be
     * killed before it finished — losing exactly the reviews it was meant to save.
     */
    fun flushAsync()
}

/**
 * Blob storage for image and audio media referenced by cards. Blobs live under the owning
 * deck's Pubky path (`/pub/loopky/decks/{deckId}/media/{sha256}.{ext}`) so they sync with the
 * deck and dedupe by content hash.
 */
interface MediaRepository {
    suspend fun putImage(deckId: String, bytes: ByteArray, mime: String): Result<MediaRef.Image>
    suspend fun putAudio(deckId: String, bytes: ByteArray, mime: String): Result<MediaRef.Audio>

    /**
     * Blob bytes for [ref], fetched lazily when a card is displayed.
     *
     * [authorPubky] is the *deck's* author, not the signed-in user. Resolving against the session
     * instead made media on any deck you don't own unreachable, since it looked for the blob under
     * your own pubky — which also blocked following a deck with images (#33).
     */
    suspend fun get(authorPubky: String, deckId: String, ref: MediaRef): Result<ByteArray>

    /**
     * Copy a blob referenced by absolute `pubky://` uri under [deckId]'s own media path, returning
     * a ref that no longer depends on the origin. A no-op for refs already stored locally.
     *
     * This is the second half of clone-by-reference: cloning stays instant, and the copy happens
     * opportunistically as blobs are first used, so a clone becomes self-contained over time
     * instead of re-uploading hundreds of MB up front.
     */
    suspend fun rehost(deckId: String, ref: MediaRef): Result<MediaRef>

    suspend fun delete(deckId: String, ref: MediaRef): Result<Unit>
}
