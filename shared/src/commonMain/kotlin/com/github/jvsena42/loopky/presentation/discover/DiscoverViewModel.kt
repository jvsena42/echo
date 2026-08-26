package com.github.jvsena42.loopky.presentation.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.DiscoveryRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.presentation.auth.SignInReason
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
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
            // Discover is the one screen a signed-out visitor gets in full, so the session is
            // resolved here rather than assumed: everything below reads public records, and the
            // only thing an account changes is whether the followed strip and the follow pills
            // mean anything.
            val signedIn = runSuspendCatching { identityRepository.currentSession() }.getOrNull()
                ?: runSuspendCatching { identityRepository.loadPersistedSession() }.getOrNull()
            val isSignedIn = signedIn != null
            _state.update {
                it.copy(
                    isSignedIn = isSignedIn,
                    topics = it.topics.loading(),
                    people = it.people.loading(),
                    browse = it.browse.loading(),
                    // Not "loading" for a guest: there is no follow graph to read, and a spinner
                    // that can only ever settle empty is a strip promising something it has none of.
                    following = if (isSignedIn) it.following.loading() else it.following.loaded(emptyList()),
                    isRefreshing = isRefresh,
                )
            }
            coroutineScope {
                if (isSignedIn) launch { loadFollowing() }
                launch { loadTopics() }
                // People is seeded from what browse found, so it chains off it rather than
                // racing it. Everything else runs alongside.
                launch { loadPeople(seed = loadBrowse(tag)) }
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
        runSuspendCatching { discoveryRepository.decksFromFollowing() }
            .onSuccess { decks ->
                feed = decks
                _state.update {
                    it.copy(
                        following = it.following.loaded(decks.filterByTag(it.selectedTag).toCards(authors)),
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
    private suspend fun loadBrowse(tag: Tag?): List<Deck> {
        val label = tag ?: ReservedTags.DECK
        // Documented never to throw; guarded anyway so a future change cannot cancel siblings.
        val decks = runSuspendCatching { discoveryRepository.decksByTagGlobal(label, BROWSE_LIMIT) }
            .onFailure { Log.e(TAG, "loadBrowse('${label.value}'): FAILED — ${it.message}", it) }
            .getOrElse { emptyList() }

        _state.update { current ->
            // A newer selection may have landed while this was in flight; cancelling the job can
            // miss a suspension point, so the selection itself is the token.
            if (current.selectedTag != tag) {
                current
            } else {
                current.copy(browse = current.browse.loaded(decks.toCards(authors)))
            }
        }
        Log.d(TAG, "loadBrowse('${label.value}'): ${decks.size} decks")
        loadAuthorProfiles(decks)
        return decks
    }

    /**
     * People to follow, seeded with whatever browse found: the indexer's `loopky-user` directory
     * comes back empty for a young label, so deck authors are the source that actually works — see
     * [DiscoveryRepository.suggestedPeople].
     */
    private suspend fun loadPeople(seed: List<Deck>) {
        val people = runSuspendCatching { discoveryRepository.suggestedPeople(seed, PEOPLE_LIMIT) }
            .onFailure { Log.e(TAG, "loadPeople: FAILED — ${it.message}", it) }
            .getOrElse { emptyList() }
        _state.update { it.copy(people = it.people.loaded(people.map(::DiscoverPerson))) }
        Log.d(TAG, "loadPeople: ${people.size} suggestions")
    }

    private suspend fun loadTopics() {
        globalTopics = runSuspendCatching { tagRepository.trendingDeckTags() }
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
        _state.update { it.withResolvedAuthors(authors) }
        Log.d(TAG, "loadAuthorProfiles: resolved=${resolved.count { it != null }}/${pending.size}")
    }

    fun onTagSelected(tag: Tag?) {
        val next = if (tag == _state.value.selectedTag) null else tag
        browseJob?.cancel()
        _state.update {
            it.copy(
                selectedTag = next,
                browse = SectionState(isLoading = true),
                following = it.following.copy(items = feed.filterByTag(next).toCards(authors)),
            )
        }
        browseJob = viewModelScope.launch { loadBrowse(next) }
    }

    /** Retries the followed feed alone — the other strips degrade to empty rather than error. */
    fun onRetryFollowing() {
        if (!_state.value.isSignedIn) return
        viewModelScope.launch {
            _state.update { it.copy(following = it.following.loading()) }
            loadFollowing()
        }
    }

    /**
     * Follow straight from the suggestions strip. Optimistic so the pill responds immediately, and
     * reverted on failure — the person stays on the strip either way, since a failed follow is
     * worth retrying rather than hiding.
     */
    fun onFollowToggle(pubky: String) {
        // On the action, not only on the pill. A guest sees the strip in full — the suggestions
        // are worth seeing, and they are what a visitor is here for — but following writes a
        // record into a follow graph that does not exist yet.
        if (!_state.value.isSignedIn) {
            viewModelScope.launch { _effects.emit(DiscoverEffect.RequireSignIn(SignInReason.FollowPerson)) }
            return
        }
        val person = _state.value.people.items.firstOrNull { it.identity.pubky == pubky } ?: return
        if (person.isFollowPending) return
        val wasFollowing = person.isFollowing
        _state.updatePerson(pubky) { it.copy(isFollowing = !wasFollowing, isFollowPending = true) }

        viewModelScope.launch {
            val result = if (wasFollowing) {
                discoveryRepository.unfollowUser(pubky)
            } else {
                discoveryRepository.followUser(pubky)
            }
            result
                .onSuccess { _state.updatePerson(pubky) { it.copy(isFollowPending = false) } }
                .onFailure { err ->
                    Log.e(TAG, "onFollowToggle($pubky): FAILED — ${err.message}", err)
                    _state.updatePerson(pubky) { it.copy(isFollowing = wasFollowing, isFollowPending = false) }
                    _effects.emit(DiscoverEffect.ShowFollowError(err.toErrorReason()))
                }
        }
    }

    fun onSearch() {
        viewModelScope.launch { _effects.emit(DiscoverEffect.OpenSearch) }
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

    companion object {
        private const val TAG = "Loopky/DiscoverVM"

        /**
         * Global browse costs one manifest fetch per deck, four at a time, each against a
         * different homeserver. Twelve is three waves and six rows of the grid; the repository
         * default of thirty would be eight waves before anything renders.
         */
        internal const val BROWSE_LIMIT = 12

        /**
         * Each candidate costs a self-tag check plus a profile fetch, and the indexer clamps the
         * directory read to twenty. Ten keeps the strip inside five waves.
         */
        internal const val PEOPLE_LIMIT = 10
    }
}

private const val FALLBACK_EMOJI = "📚"

internal fun bareIdentity(pubky: String) =
    PubkyIdentity(pubky, displayName = null, avatarUrl = null, bio = null)

/** Re-renders both deck strips with any author names that have since resolved. */
private fun DiscoverUiState.withResolvedAuthors(authors: Map<String, PubkyIdentity>): DiscoverUiState {
    fun DiscoverDeck.resolved() = copy(author = authors[authorPubky] ?: author)
    return copy(
        browse = browse.copy(items = browse.items.map { it.resolved() }),
        following = following.copy(items = following.items.map { it.resolved() }),
    )
}

private fun MutableStateFlow<DiscoverUiState>.updatePerson(
    pubky: String,
    transform: (DiscoverPerson) -> DiscoverPerson,
) = update { current ->
    current.copy(
        people = current.people.copy(
            items = current.people.items.map { person ->
                if (person.identity.pubky == pubky) transform(person) else person
            },
        ),
    )
}

private fun List<Deck>.filterByTag(tag: Tag?): List<Deck> =
    if (tag == null) this else filter { tag in it.tags }

internal fun List<Deck>.toCards(authors: Map<String, PubkyIdentity>): List<DiscoverDeck> = map { deck ->
    DiscoverDeck(
        id = deck.id,
        authorPubky = deck.authorPubky,
        title = deck.title,
        cardCount = deck.cardCount,
        coverEmoji = deck.coverEmoji ?: deck.title.firstOrNull()?.uppercaseChar()?.toString() ?: FALLBACK_EMOJI,
        coverImage = deck.coverImageRef,
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
    /**
     * False while the visitor has no account. Discover works either way — every read on it is
     * public — so this changes only what is *offered*: the followed strip goes, and the follow
     * pills raise a sign-in prompt instead of writing.
     */
    val isSignedIn: Boolean = true,
    val topics: SectionState<Tag> = SectionState(),
    val people: SectionState<DiscoverPerson> = SectionState(),
    val browse: SectionState<DiscoverDeck> = SectionState(),
    val following: SectionState<DiscoverDeck> = SectionState(),
    val selectedTag: Tag? = null,
    val isRefreshing: Boolean = false,
) {
    /**
     * Global browse minus whatever the follow strip is already showing.
     *
     * The two strips load independently and neither knew about the other, so a followed author's
     * deck was drawn twice on the same screen — once under "From people you follow" and again
     * under "Discover decks". The follow strip wins because it is the more specific claim.
     *
     * Derived rather than filtered at write time: the strips settle in either order, so the
     * exclusion has to be recomputed on every emission rather than applied once.
     */
    val browseExcludingFollowed: SectionState<DiscoverDeck>
        get() {
            if (following.items.isEmpty()) return browse
            val shown = following.items.mapTo(mutableSetOf()) { it.authorPubky to it.id }
            return browse.copy(items = browse.items.filterNot { (it.authorPubky to it.id) in shown })
        }

    /**
     * True when browse found matches and the follow strip is already showing every one of them.
     *
     * The distinction the empty state turns on. "No decks tagged X yet" is a claim about the
     * world, and it is false whenever the only matches happen to be decks you follow — selecting
     * a tag with one such deck rendered that sentence directly above the deck it denied. So the
     * browse section is dropped whole in this case rather than drawn empty: there is nothing to
     * say, and the deck is on screen already.
     */
    val browseFullyCoveredByFollowed: Boolean
        get() = browse.items.isNotEmpty() && browseExcludingFollowed.items.isEmpty()
}

/** Someone worth following, with the state of the follow pill beside them. */
data class DiscoverPerson(
    val identity: PubkyIdentity,
    val isFollowing: Boolean = false,
    val isFollowPending: Boolean = false,
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
    /** The deck's cover art, when it has one. Renders over [coverEmoji]; null falls back to it. */
    val coverImage: MediaRef.Image? = null,
    val author: PubkyIdentity,
    val tags: List<String>,
)

sealed interface DiscoverEffect {
    /**
     * Open search. Discover shows what the network happens to be publishing; search is how
     * someone with a name, a pubky or a shared link finds the one thing they came for — including
     * an account too new for any index, which is why pasting a pubky has to stay reachable.
     */
    data object OpenSearch : DiscoverEffect
    data class OpenProfile(val pubky: String) : DiscoverEffect
    data class OpenDeck(val authorPubky: String, val deckId: String) : DiscoverEffect

    /** A follow that failed — the pill has already reverted, so this only explains why. */
    data class ShowFollowError(val reason: ErrorReason) : DiscoverEffect

    /** A guest reached for something that writes. [reason] is what the prompt is about. */
    data class RequireSignIn(val reason: SignInReason) : DiscoverEffect
}
