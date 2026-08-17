package com.github.jvsena42.loopky.presentation.decks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.data.repository.SrsRepository
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DeckSource
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.inStudyOrder
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
@Suppress("LongParameterList", "TooManyFunctions")
class DeckDetailViewModel(
    private val deckId: String,
    private val authorPubky: String? = null,
    private val deckRepository: DeckRepository,
    private val cardRepository: CardRepository,
    private val identityRepository: IdentityRepository,
    private val srsRepository: SrsRepository,
    private val mediaRepository: MediaRepository,
    private val tagRepository: TagRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<DeckDetailUiState>(DeckDetailUiState.Loading)
    val state: StateFlow<DeckDetailUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<DeckDetailEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<DeckDetailEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null

    /** Re-entrancy guard, so double-tapping the follow pill cannot race two writes. */
    private var followJob: Job? = null

    init {
        load()
        // Studying runs on its own full-screen destination while this screen stays composed
        // behind it, so without this the due count keeps the value it had before the session.
        viewModelScope.launch {
            srsRepository.changes
                .filter { it == deckId }
                .collect { load(silent = true) }
        }
    }

    fun onRefresh() = load()

    /** [silent] keeps existing content on screen while a background refresh runs. */
    private fun load(silent: Boolean = false) {
        // Cancel rather than bail out: a review that lands mid-load must not be dropped.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            Log.d(TAG, "load: deckId=$deckId (silent=$silent)")
            if (!silent) _state.update { DeckDetailUiState.Loading }

            val session = runSuspendCatching { identityRepository.currentSession() }.getOrNull()
                ?: runSuspendCatching { identityRepository.loadPersistedSession() }.getOrNull()

            var deck = deckRepository.getLocal(deckId)
            if (deck == null && authorPubky != null) {
                Log.d(TAG, "load: cache miss, fetching remote author=$authorPubky")
                deck = deckRepository.fetchRemote(authorPubky, deckId)
                    .onFailure { err ->
                        Log.e(TAG, "load: remote fetch FAILED — ${err::class.simpleName}: ${err.message}", err)
                    }
                    .getOrNull()
            }
            if (deck == null) {
                _state.update { DeckDetailUiState.Error(ErrorReason.NotFound, canRetry = false) }
                return@launch
            }

            // Must be a fetch, not a cache read: nothing has loaded this deck's cards yet on a
            // cold launch, and for a deck you don't own nothing ever will.
            runSuspendCatching { cardRepository.fetchByDeck(deck).getOrThrow().inStudyOrder() }
                .onSuccess { cards ->
                    val dueCount = runSuspendCatching { srsRepository.dueForDeck(deckId).size }
                        .getOrDefault(0)
                    val mastered = masteredPercent(cards)
                    val isFollowing = runSuspendCatching { deckRepository.isFollowingDeck(deckId) }
                        .getOrDefault(false)
                    _state.update {
                        deck.toContent(cards, session?.identity, dueCount, mastered, isFollowing)
                    }
                    Log.d(TAG, "load: cards=${cards.size} due=$dueCount mastered=$mastered")
                    loadCoverBlob(deck.coverImageRef, deck.authorPubky)
                    loadAuthorProfile(deck.authorPubky)
                    loadClonedFrom(deck)
                    loadCounts(deck)
                    // Opening a followed deck is what "seen" means, so the library stops flagging
                    // it as changed. Last, and best-effort: it is cosmetic.
                    if (isFollowing) runSuspendCatching { deckRepository.markSeen(deck) }
                }
                .onFailure { err ->
                    Log.e(TAG, "load: FAILED — ${err::class.simpleName}: ${err.message}", err)
                    _state.update { DeckDetailUiState.Error(err.toErrorReason()) }
                }
        }
    }

    fun onBackClick() {
        viewModelScope.launch { _effects.emit(DeckDetailEffect.NavigateBack) }
    }

    fun onShareClick() {
        viewModelScope.launch {
            val deck = deckRepository.getLocal(deckId) ?: return@launch
            _effects.emit(DeckDetailEffect.Share(title = deck.title, uri = deck.pubkyUri.value))
        }
    }

    fun onStudyClick() {
        // Keeping the deck is what earns review state, so browsing someone else's deck is not
        // enough to study it. The UI hides the button; this stops a stale click getting through.
        val current = _state.value as? DeckDetailUiState.Content ?: return
        if (!current.isOwned && !current.isFollowing) return
        viewModelScope.launch { _effects.emit(DeckDetailEffect.NavigateStudy) }
    }

    fun onEditClick() {
        viewModelScope.launch { _effects.emit(DeckDetailEffect.NavigateEditDeck(deckId)) }
    }

    fun onDeleteDeck() {
        val current = _state.value as? DeckDetailUiState.Content ?: return
        _state.update { current.copy(showDeleteConfirm = true) }
    }

    fun onDismissDelete() {
        val current = _state.value as? DeckDetailUiState.Content ?: return
        _state.update { current.copy(showDeleteConfirm = false) }
    }

    fun onConfirmDelete() {
        val current = _state.value as? DeckDetailUiState.Content ?: return
        _state.update { current.copy(showDeleteConfirm = false, isDeleting = true) }
        viewModelScope.launch {
            Log.d(TAG, "onConfirmDelete: deckId=$deckId")
            deckRepository.delete(deckId)
                .onSuccess { _effects.emit(DeckDetailEffect.Deleted) }
                .onFailure { err ->
                    Log.e(TAG, "onConfirmDelete: FAILED — ${err::class.simpleName}: ${err.message}", err)
                    _state.update { DeckDetailUiState.Error(err.toErrorReason()) }
                }
        }
    }

    /**
     * Subscribe to / unsubscribe from someone else's deck (#33).
     *
     * Optimistic, like the author-follow toggle on friend profiles: the pill flips immediately and
     * reverts if the write fails, because waiting on a homeserver round trip to acknowledge a tap
     * reads as a dead button.
     */
    fun onToggleFollow() {
        if (followJob?.isActive == true) return
        val current = _state.value as? DeckDetailUiState.Content ?: return
        if (current.isOwned) return

        followJob = viewModelScope.launch {
            val wasFollowing = current.isFollowing
            _state.update { s ->
                (s as? DeckDetailUiState.Content)
                    ?.copy(isFollowing = !wasFollowing, isFollowPending = true) ?: s
            }

            val deck = deckRepository.getLocal(deckId)
            if (deck == null) {
                _state.update { s ->
                    (s as? DeckDetailUiState.Content)
                        ?.copy(isFollowing = wasFollowing, isFollowPending = false) ?: s
                }
                return@launch
            }

            val result = if (wasFollowing) {
                deckRepository.unfollowDeck(deck.authorPubky, deck.id)
            } else {
                deckRepository.followDeck(deck)
            }
            result
                .onSuccess {
                    _state.update { s ->
                        (s as? DeckDetailUiState.Content)?.copy(isFollowPending = false) ?: s
                    }
                }
                .onFailure { err ->
                    Log.e(TAG, "onToggleFollow: FAILED — ${err.message}", err)
                    _state.update { s ->
                        (s as? DeckDetailUiState.Content)?.copy(
                            isFollowing = wasFollowing,
                            isFollowPending = false,
                            errorReason = err.toErrorReason(),
                        ) ?: s
                    }
                }
        }
    }

    /** Confirmed rather than immediate: a clone is N+1 writes, and the card count says how many. */
    fun onCloneClick() {
        val current = _state.value as? DeckDetailUiState.Content ?: return
        _state.update { current.copy(showCloneConfirm = true) }
    }

    fun onDismissClone() {
        val current = _state.value as? DeckDetailUiState.Content ?: return
        _state.update { current.copy(showCloneConfirm = false) }
    }

    fun onConfirmClone() {
        val current = _state.value as? DeckDetailUiState.Content ?: return
        _state.update { current.copy(showCloneConfirm = false, isCloning = true) }
        viewModelScope.launch {
            val source = deckRepository.getLocal(deckId)
            if (source == null) {
                _state.update { s ->
                    (s as? DeckDetailUiState.Content)?.copy(isCloning = false) ?: s
                }
                return@launch
            }
            deckRepository.clone(source)
                .onSuccess { clone ->
                    Log.d(TAG, "onConfirmClone: $deckId -> ${clone.id}")
                    // Navigate to the copy: it is what the user now owns, and the source screen
                    // would otherwise sit there looking unchanged.
                    _effects.emit(DeckDetailEffect.Cloned(clone.id))
                }
                .onFailure { err ->
                    Log.e(TAG, "onConfirmClone: FAILED — ${err.message}", err)
                    _state.update { s ->
                        (s as? DeckDetailUiState.Content)?.copy(
                            isCloning = false,
                            errorReason = err.toErrorReason(),
                        ) ?: s
                    }
                }
        }
    }

    fun onDismissError() {
        val current = _state.value as? DeckDetailUiState.Content ?: return
        _state.update { current.copy(errorReason = null) }
    }

    /**
     * Resolves "Cloned from @someone" for a deck that carries clone provenance, so credit is
     * visible on the copy and not only in the manifest.
     */
    private suspend fun loadClonedFrom(deck: Deck) {
        val uri = deck.source?.takeIf { it.kind == DeckSource.Kind.Clone }?.uri ?: return
        val ref = parseDeckManifestUri(uri) ?: return
        val profile = identityRepository.fetchProfile(ref).getOrNull()
            ?: PubkyIdentity(ref, displayName = null, avatarUrl = null, bio = null)
        _state.update { s ->
            (s as? DeckDetailUiState.Content)?.copy(clonedFrom = profile) ?: s
        }
    }

    /**
     * Follower and clone counts, from the indexer's distinct-tagger count for the reserved labels.
     * Approximate by nature (indexer lag, spam) — fine to display, never to gate on — and zero
     * whenever the indexer is unreachable, so this never blocks the screen.
     */
    private suspend fun loadCounts(deck: Deck) {
        val counts = runSuspendCatching { tagRepository.taggerCounts(deck.pubkyUri) }
            .getOrDefault(emptyMap())
        if (counts.isEmpty()) return
        _state.update { s ->
            (s as? DeckDetailUiState.Content)?.copy(
                followerCount = counts[ReservedTags.FOLLOWED] ?: 0,
                clonedCount = counts[ReservedTags.CLONED] ?: 0,
            ) ?: s
        }
    }

    /**
     * The author pubky out of `pubky://{author}/pub/loopky/decks/{id}/manifest.json`.
     *
     * Parsed here rather than through `PubkyUris.parseDeckManifest`, which is `internal` to the data
     * layer — presentation only needs the owner segment.
     */
    private fun parseDeckManifestUri(uri: String): String? {
        if (!uri.startsWith(PUBKY_SCHEME)) return null
        return uri.removePrefix(PUBKY_SCHEME).substringBefore('/', "").ifEmpty { null }
    }

    /**
     * Share of cards whose review interval has reached SM-2's "mature" threshold.
     * `dueForDeck` has already warmed the per-session SRS cache, so [SrsRepository.stateFor]
     * is a cheap lookup here. "—" until the deck has cards.
     */
    private suspend fun masteredPercent(cards: List<Card>): String {
        if (cards.isEmpty()) return "—"
        val mastered = cards.count { card ->
            val state = runSuspendCatching { srsRepository.stateFor(card.id) }.getOrNull()
            state != null && state.intervalDays >= MATURE_INTERVAL_DAYS
        }
        return "${mastered * PERCENT / cards.size}%"
    }

    /**
     * Fetches a homeserver blob cover and folds its Base64 bytes into the current [Content] so the
     * UI can render the real image. Remote (URL) covers need no fetch — they are already carried by
     * [DeckDetailUiState.Content.coverImageUrl]. No-ops on null/remote refs or while not in Content.
     */
    private suspend fun loadCoverBlob(ref: MediaRef.Image?, authorPubky: String) {
        if (ref == null || ref.isRemote) return
        val bytes = mediaRepository.get(authorPubky, deckId, ref)
            .onFailure { Log.e(TAG, "loadCoverBlob: FAILED — ${it.message}", it) }
            .getOrNull() ?: return
        val encoded = Base64.encode(bytes)
        _state.update { current ->
            (current as? DeckDetailUiState.Content)?.copy(coverImageBase64 = encoded) ?: current
        }
    }

    /**
     * Fetches the author's pubky.app profile and folds it into the current [Content], so the author
     * row shows the same name and picture the author's own profile screen does. Keeps whatever the
     * session already gave us when the author has published no profile.
     */
    private suspend fun loadAuthorProfile(authorPubky: String) {
        val profile = identityRepository.fetchProfile(authorPubky).getOrNull() ?: return
        _state.update { current ->
            (current as? DeckDetailUiState.Content)?.let {
                it.copy(
                    author = it.author.copy(
                        displayName = profile.displayName ?: it.author.displayName,
                        avatarUrl = profile.avatarUrl ?: it.author.avatarUrl,
                    ),
                )
            } ?: current
        }
    }

    private fun Deck.toContent(
        cards: List<Card>,
        myIdentity: PubkyIdentity?,
        dueCount: Int,
        mastered: String,
        isFollowing: Boolean,
    ): DeckDetailUiState.Content {
        val isOwned = authorPubky == myIdentity?.pubky
        return DeckDetailUiState.Content(
            isFollowing = isFollowing,
            deckId = id,
            title = title,
            description = description,
            coverEmoji = coverEmoji ?: title.firstOrNull()?.toString() ?: "📚",
            coverImageUrl = coverImageRef?.url,
            // Your own decks can name you straight away from the session; for anyone else the
            // pubky stands in until loadAuthorProfile lands.
            author = myIdentity?.takeIf { isOwned }
                ?: PubkyIdentity(authorPubky, displayName = null, avatarUrl = null, bio = null),
            isOwned = isOwned,
            isIncomplete = incomplete,
            tags = tags.map { it.value },
            totalCards = cardCount,
            dueCards = dueCount,
            masteredPercent = mastered,
            cardPreviews = cards.map { it.toPreview() },
        )
    }

    private fun Card.toPreview(): CardPreviewModel = CardPreviewModel(
        id = id,
        frontText = front.text ?: "",
        backText = back.text ?: "",
    )

    companion object {
        private const val TAG = "Loopky/DeckDetailVM"

        /** SM-2 convention: a card with a ≥21-day interval counts as mature/mastered. */
        private const val MATURE_INTERVAL_DAYS = 21
        private const val PERCENT = 100
        private const val PUBKY_SCHEME = "pubky://"
    }
}

sealed interface DeckDetailUiState {
    data object Loading : DeckDetailUiState
    data class Content(
        val deckId: String,
        val title: String,
        val description: String?,
        val coverEmoji: String,
        val coverImageUrl: String? = null,
        val coverImageBase64: String? = null,
        val author: PubkyIdentity,
        /** Ownership is a separate concern from identity — the author row shows both. */
        val isOwned: Boolean,
        /**
         * The deck was claimed by a publish that never finished, so some of its cards are missing.
         * Surfaced rather than hidden: the count comes from the manifest, so the deck would
         * otherwise look complete while silently holding fewer cards than it claims.
         */
        val isIncomplete: Boolean = false,
        val tags: List<String>,
        /**
         * You hold a subscription to this deck: you receive the author's updates and it is
         * read-only. Always false for a deck you own — you cannot follow yourself.
         */
        val isFollowing: Boolean = false,
        /** A follow/unfollow write is in flight; the pill is already showing its new state. */
        val isFollowPending: Boolean = false,
        val showCloneConfirm: Boolean = false,
        val isCloning: Boolean = false,
        /** The author this deck was cloned from, when it carries clone provenance. */
        val clonedFrom: PubkyIdentity? = null,
        /**
         * Distinct taggers of the reserved labels, per the indexer. Approximate by nature (indexer
         * lag, spam) and zero while the indexer is unreachable — display only, never gate on them.
         */
        val followerCount: Int = 0,
        val clonedCount: Int = 0,
        val totalCards: Int,
        val dueCards: Int,
        val masteredPercent: String,
        val cardPreviews: List<CardPreviewModel>,
        val showDeleteConfirm: Boolean = false,
        val isDeleting: Boolean = false,
        /** A recoverable failure worth showing without tearing down the loaded deck. */
        val errorReason: ErrorReason? = null,
    ) : DeckDetailUiState
    data class Error(
        val reason: ErrorReason,
        /** False when retrying cannot possibly succeed (e.g. the deck no longer exists). */
        val canRetry: Boolean = true,
    ) : DeckDetailUiState
}

data class CardPreviewModel(
    val id: String,
    val frontText: String,
    val backText: String,
)

sealed interface DeckDetailEffect {
    data object NavigateBack : DeckDetailEffect
    data class NavigateEditDeck(val deckId: String) : DeckDetailEffect
    data object NavigateStudy : DeckDetailEffect
    data class Share(val title: String, val uri: String) : DeckDetailEffect
    data object Deleted : DeckDetailEffect

    /** The clone is what the user now owns, so the screen moves to it rather than staying put. */
    data class Cloned(val deckId: String) : DeckDetailEffect
}
