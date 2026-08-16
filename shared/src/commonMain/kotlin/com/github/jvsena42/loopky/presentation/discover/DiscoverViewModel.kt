package com.github.jvsena42.loopky.presentation.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.DiscoveryRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Discover, as strips that load independently after first paint: deck topics, a global browse of
 * everything published, and decks from people the user follows.
 *
 * Following nobody is a normal state, not an empty one — the screen used to collapse to "follow a
 * friend to see their decks here", which left a new account with nothing to do unless it already
 * knew someone's pubky (#26). Global browse comes from the Nexus indexer and needs no follow
 * relationship, so it carries the screen on its own.
 *
 * No strip gates another and none of them can blank the screen: each owns its loading, empty and
 * error state, and every loader catches its own failure so a sibling cannot take the rest down.
 */
class DiscoverViewModel(
    private val discoveryRepository: DiscoveryRepository,
    private val tagRepository: TagRepository,
    private val identityRepository: IdentityRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<DiscoverEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<DiscoverEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null
    private var browseJob: Job? = null

    /** The followed feed, kept so selecting a topic re-filters that strip with no round-trip. */
    private var feed: List<Deck> = emptyList()

    /** Global topics, kept apart from feed labels so either source can land first. */
    private var globalTopics: List<Tag> = emptyList()

    /** Resolved author profiles, so a re-render reuses names already in hand. */
    private val authors = mutableMapOf<String, PubkyIdentity>()

    init {
        load()
    }

    fun onRefresh() = load(isRefresh = true)

    private fun load(isRefresh: Boolean = false) {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            val tag = _state.value.selectedTag
            _state.update {
                it.copy(
                    topics = it.topics.loading(),
                    browse = it.browse.loading(),
                    following = it.following.loading(),
                    isRefreshing = isRefresh,
                )
            }
            coroutineScope {
                launch { loadFollowing() }
                launch { loadBrowse(tag) }
                launch { loadTopics() }
            }
            _state.update { it.copy(isRefreshing = false) }
        }
    }

    /**
     * The only strip whose repository throws, so the only one that can show an error. An
     * unreachable homeserver must not read as "you follow nobody" — see
     * [DiscoveryRepository.decksFromFollowing].
     */
    private suspend fun loadFollowing() {
        runCatching { discoveryRepository.decksFromFollowing() }
            .onSuccess { decks ->
                feed = decks
                _state.update {
                    it.copy(
                        following = it.following.loaded(decks.filterByTag(it.selectedTag).toCards()),
                        topics = it.topics.copy(items = mergedTopics()),
                    )
                }
                Log.d(TAG, "loadFollowing: ${decks.size} decks")
                loadAuthorProfiles(decks)
            }
            .onFailure { err ->
                Log.e(TAG, "loadFollowing: FAILED — ${err.message}", err)
                _state.update { it.copy(following = it.following.failed(err.toErrorReason())) }
            }
    }

    /**
     * Global browse. A null [tag] browses every published deck via [ReservedTags.DECK]; otherwise
     * it asks the indexer for that topic rather than filtering what is already on screen, because
     * a network-wide topic almost never matches the handful of decks in the followed feed.
     */
    private suspend fun loadBrowse(tag: Tag?) {
        val label = tag ?: ReservedTags.DECK
        // Documented never to throw; guarded anyway so a future change cannot cancel siblings.
        val decks = runCatching { discoveryRepository.decksByTagGlobal(label, BROWSE_LIMIT) }
            .onFailure { Log.e(TAG, "loadBrowse('${label.value}'): FAILED — ${it.message}", it) }
            .getOrElse { emptyList() }

        _state.update { current ->
            // A newer selection may have landed while this was in flight; cancelling the job can
            // miss a suspension point, so the selection itself is the token.
            if (current.selectedTag != tag) current else current.copy(browse = current.browse.loaded(decks.toCards()))
        }
        Log.d(TAG, "loadBrowse('${label.value}'): ${decks.size} decks")
        loadAuthorProfiles(decks)
    }

    private suspend fun loadTopics() {
        globalTopics = runCatching { tagRepository.trendingDeckTags() }
            .onFailure { Log.e(TAG, "loadTopics: FAILED — ${it.message}", it) }
            .getOrElse { emptyList() }
        _state.update { it.copy(topics = it.topics.loaded(mergedTopics())) }
    }

    /**
     * Author names arrive after first paint: a tile shows the bare pubky until its author's
     * profile lands, and an author with no published profile simply keeps it.
     */
    private suspend fun loadAuthorProfiles(decks: List<Deck>) {
        val pending = decks.map { it.authorPubky }.distinct().filterNot { it in authors }
        if (pending.isEmpty()) return
        val resolved = coroutineScope {
            pending.map { pubky -> async { identityRepository.fetchProfile(pubky).getOrNull() } }.awaitAll()
        }
        resolved.filterNotNull().forEach { authors[it.pubky] = it }
        if (resolved.all { it == null }) return
        _state.update { current ->
            current.copy(
                browse = current.browse.copy(items = current.browse.items.map(::withResolvedAuthor)),
                following = current.following.copy(items = current.following.items.map(::withResolvedAuthor)),
            )
        }
        Log.d(TAG, "loadAuthorProfiles: resolved=${resolved.count { it != null }}/${pending.size}")
    }

    fun onTagSelected(tag: Tag?) {
        val next = if (tag == _state.value.selectedTag) null else tag
        browseJob?.cancel()
        _state.update {
            it.copy(
                selectedTag = next,
                browse = SectionState(isLoading = true),
                following = it.following.copy(items = feed.filterByTag(next).toCards()),
            )
        }
        browseJob = viewModelScope.launch { loadBrowse(next) }
    }

    /** Retries the followed feed alone — the other strips degrade to empty rather than error. */
    fun onRetryFollowing() {
        viewModelScope.launch {
            _state.update { it.copy(following = it.following.loading()) }
            loadFollowing()
        }
    }

    fun onAddFriend() {
        viewModelScope.launch { _effects.emit(DiscoverEffect.OpenAddFriend) }
    }

    fun onOpenAuthor(pubky: String) {
        viewModelScope.launch { _effects.emit(DiscoverEffect.OpenProfile(pubky)) }
    }

    fun onOpenDeck(authorPubky: String, deckId: String) {
        viewModelScope.launch { _effects.emit(DiscoverEffect.OpenDeck(authorPubky, deckId)) }
    }

    /** Global topics first — they are ranked — then labels only the followed feed knows about. */
    private fun mergedTopics(): List<Tag> =
        (globalTopics + feed.flatMap { it.tags })
            .filterNot { ReservedTags.isReserved(it) }
            .distinct()

    private fun List<Deck>.toCards(): List<DiscoverDeck> = toCards(authors)

    private fun withResolvedAuthor(card: DiscoverDeck): DiscoverDeck =
        card.copy(author = authors[card.authorPubky] ?: card.author)

    companion object {
        private const val TAG = "Loopky/DiscoverVM"

        /**
         * Global browse costs one manifest fetch per deck, four at a time, each against a
         * different homeserver. Twelve is three waves and six rows of the grid; the repository
         * default of thirty would be eight waves before anything renders.
         */
        internal const val BROWSE_LIMIT = 12
    }
}

private const val FALLBACK_EMOJI = "📚"

internal fun bareIdentity(pubky: String) =
    PubkyIdentity(pubky, displayName = null, avatarUrl = null, bio = null)

private fun List<Deck>.filterByTag(tag: Tag?): List<Deck> =
    if (tag == null) this else filter { tag in it.tags }

private fun List<Deck>.toCards(authors: Map<String, PubkyIdentity>): List<DiscoverDeck> = map { deck ->
    DiscoverDeck(
        id = deck.id,
        authorPubky = deck.authorPubky,
        title = deck.title,
        cardCount = deck.cardCount,
        coverEmoji = deck.coverEmoji ?: deck.title.firstOrNull()?.toString() ?: FALLBACK_EMOJI,
        author = authors[deck.authorPubky] ?: bareIdentity(deck.authorPubky),
        tags = deck.tags.map { it.value },
    )
}

/**
 * Discover's strips. A single state rather than a sealed hierarchy because the screen has no
 * modes: the strips coexist and settle at different times, and requiring each write to first prove
 * the screen is in some `Content` case is what produced the stale-write races this replaced.
 */
data class DiscoverUiState(
    val topics: SectionState<Tag> = SectionState(),
    val browse: SectionState<DiscoverDeck> = SectionState(),
    val following: SectionState<DiscoverDeck> = SectionState(),
    val selectedTag: Tag? = null,
    val isRefreshing: Boolean = false,
)

/** One independently-loading strip. Strips never gate each other. */
data class SectionState<T>(
    val items: List<T> = emptyList(),
    val isLoading: Boolean = false,
    val error: ErrorReason? = null,
) {
    /** Settled with nothing to show — the only case that draws an empty placeholder. */
    val isEmpty: Boolean get() = items.isEmpty() && !isLoading && error == null

    fun loading(): SectionState<T> = copy(isLoading = true, error = null)
    fun loaded(items: List<T>): SectionState<T> = SectionState(items = items)
    fun failed(reason: ErrorReason): SectionState<T> = copy(isLoading = false, error = reason)
}

data class DiscoverDeck(
    val id: String,
    val authorPubky: String,
    val title: String,
    val cardCount: Int,
    val coverEmoji: String,
    val author: PubkyIdentity,
    val tags: List<String>,
)

sealed interface DiscoverEffect {
    data object OpenAddFriend : DiscoverEffect
    data class OpenProfile(val pubky: String) : DiscoverEffect
    data class OpenDeck(val authorPubky: String, val deckId: String) : DiscoverEffect
}
