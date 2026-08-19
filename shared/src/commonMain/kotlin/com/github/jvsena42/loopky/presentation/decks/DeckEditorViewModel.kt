package com.github.jvsena42.loopky.presentation.decks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.DiscoveryRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.data.storage.AppPreferences
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DeckAnnouncement
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.domain.model.inStudyOrder
import com.github.jvsena42.loopky.presentation.share.DeckSharePrompt
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.epochMillis
import com.github.jvsena42.loopky.util.generateId
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Seven collaborators because the editor writes a deck end to end: manifest, cards, cover upload,
// the author it stamps, and — since #39 — the announcement it offers on a create.
@Suppress("TooManyFunctions", "LongParameterList")
class DeckEditorViewModel(
    private val deckId: String?,
    private val deckRepository: DeckRepository,
    private val cardRepository: CardRepository,
    private val identityRepository: IdentityRepository,
    private val mediaRepository: MediaRepository,
    private val discoveryRepository: DiscoveryRepository,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(DeckEditorUiState())
    val state: StateFlow<DeckEditorUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<DeckEditorEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<DeckEditorEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null
    private var saveJob: Job? = null

    /**
     * Set when an existing deck's cards could not be read. Saving rewrites the deck's chunks from
     * [DeckEditorUiState.cards], so saving an editor that failed to load would drop every card out
     * of the deck — the user's deck, gone. Block the save instead.
     */
    private var cardsLoadFailed = false

    /**
     * The deck as loaded from the repository. Saving rewrites the whole manifest, so the fields
     * the editor does not expose (cover image, Listen/Speak, provenance) must be carried forward
     * from here or they are destroyed on the homeserver.
     */
    private var loadedDeck: Deck? = null

    /** The deck's cards as loaded, so a save can tell whether the card set actually changed. */
    private var loadedCards: List<Card> = emptyList()

    /** The id the last successful save wrote — the destination once the share prompt resolves. */
    private var savedDeckId: String? = null

    init {
        if (deckId != null) loadExisting()
    }

    private fun loadExisting() {
        loadJob = viewModelScope.launch {
            Log.d(TAG, "loadExisting: deckId=$deckId")
            val deck = deckRepository.getLocal(deckId!!) ?: return@launch
            loadedDeck = deck
            // A cache read returns nothing on a cold launch, which would open the editor empty.
            val cards = cardRepository.fetchByDeck(deck)
                .onFailure { err ->
                    Log.e(TAG, "loadExisting: cards FAILED — ${err.message}", err)
                    cardsLoadFailed = true
                }
                .getOrDefault(emptyList())
                .inStudyOrder()
            loadedCards = cards
            _state.update { DeckEditorUiState(
                isNew = false,
                coverEmoji = deck.coverEmoji ?: deck.title.firstOrNull()?.toString() ?: "",
                title = deck.title,
                description = deck.description ?: "",
                tags = deck.tags.map { it.value },
                cards = cards.map { it.toEditable() },
                error = if (cardsLoadFailed) CARDS_LOAD_FAILED else null,
            ) }
        }
    }

    /**
     * Re-read the deck's cards, for when the screen comes back to the foreground.
     *
     * The card editor is a separate screen writing straight through to the repository, so on the
     * way back this list is showing the card as it was before that edit — and since a save
     * rebuilds every card from it, it would write that stale version back over the edit. Order and
     * any not-yet-saved card added here are kept; only the cards the repository actually knows are
     * refreshed.
     */
    fun onResume() {
        if (deckId == null) return
        viewModelScope.launch {
            val latest = refreshSnapshot().cardsById
            if (latest.isEmpty()) return@launch
            _state.update { s ->
                s.copy(cards = s.cards.map { editable -> latest[editable.id]?.toEditable() ?: editable })
            }
        }
    }

    /**
     * Re-read the deck and its cards, and re-baseline [loadedCards] against them.
     *
     * The card editor is a separate screen writing straight through to the repository, so the
     * snapshot this editor took when it opened goes stale the moment a card is edited there.
     * Both halves matter: a full publish rebuilt from the stale cards would write that card back
     * as it was and destroy the edit — an image added in the card editor, say (#80) — and a
     * metadata-only save would restore the pre-edit chunk table over the one `upsertCard` just
     * patched. Empty for a deck that does not exist yet.
     */
    private suspend fun refreshSnapshot(): DeckSnapshot {
        val currentDeckId = deckId ?: return DeckSnapshot(null, emptyMap())
        val cardsById = cardRepository.listByDeck(currentDeckId).associateBy(Card::id)
        loadedCards = loadedCards.map { cardsById[it.id] ?: it }
        return DeckSnapshot(deckRepository.getLocal(currentDeckId) ?: loadedDeck, cardsById)
    }

    fun onTitleChanged(text: String) {
        _state.update { it.copy(title = text, titleError = titleErrorFor(text)) }
    }

    fun onDescriptionChanged(text: String) {
        _state.update { it.copy(description = text, descriptionError = descriptionErrorFor(text)) }
    }

    fun onCoverEmojiChanged(emoji: String) {
        _state.update { it.copy(coverEmoji = emoji) }
    }

    fun onAddTag(tag: String) {
        val trimmed = tag.trim().lowercase()
        if (trimmed.isBlank()) return
        // `loopky-*` is Loopky's own index namespace, not a topic (#40) — a hand-entered one would
        // forge a global-browse entry and read as a topical chip on the deck.
        if (ReservedTags.isReserved(trimmed)) {
            Log.d(TAG, "onAddTag: ignoring reserved label '$trimmed'")
            return
        }
        // Dedup: the tag input can be tapped twice with the same label, and a tag record is keyed
        // by label, so a duplicate is a no-op on the homeserver but a second chip in the UI (#83).
        _state.update { s -> if (trimmed in s.tags) s else s.copy(tags = s.tags + trimmed) }
    }

    fun onRemoveTag(tag: String) {
        _state.update { s -> s.copy(tags = s.tags - tag) }
    }

    fun onAddCard() {
        val newCard = EditableCardModel(
            id = generateId(),
            frontText = "",
            backText = "",
            hasImage = false,
            hasAudio = false,
        )
        _state.update { s -> s.copy(cards = s.cards + newCard) }
    }

    /**
     * A cover could previously only be set while publishing — the editor's cover box was not
     * tappable, so an existing deck's cover could never be changed. Mirrors the identical pair
     * on [com.github.jvsena42.loopky.presentation.importflow.PublishDeckViewModel].
     */
    fun onCoverWebSelected(url: String) {
        _state.update { it.copy(coverImageUrl = url, coverPendingBytes = null, coverPendingMime = null) }
    }

    fun onCoverGallerySelected(bytes: ByteArray, mime: String) {
        _state.update { it.copy(coverImageUrl = null, coverPendingBytes = bytes, coverPendingMime = mime) }
    }

    /**
     * Move a card one position. Order persists through each card's `ord`, which `publish` assigns
     * from the editor's list order, so reordering here is all that is needed.
     */
    fun onMoveCard(from: Int, to: Int) {
        _state.update { s ->
            if (from !in s.cards.indices || to !in s.cards.indices || from == to) return@update s
            val reordered = s.cards.toMutableList()
            reordered.add(to, reordered.removeAt(from))
            s.copy(cards = reordered)
        }
    }

    fun onCardClick(cardId: String) {
        val currentDeckId = deckId ?: return
        viewModelScope.launch { _effects.emit(DeckEditorEffect.NavigateEditCard(currentDeckId, cardId)) }
    }

    fun onCloseClick() {
        viewModelScope.launch { _effects.emit(DeckEditorEffect.NavigateBack) }
    }

    fun onSaveClick() {
        if (saveJob?.isActive == true) return
        val s = _state.value
        if (cardsLoadFailed) {
            _state.update { it.copy(error = CARDS_LOAD_FAILED) }
            return
        }
        if (s.title.isBlank()) {
            _state.update { it.copy(error = "Title is required.") }
            return
        }
        val titleError = titleErrorFor(s.title)
        val descriptionError = descriptionErrorFor(s.description)
        if (titleError != null || descriptionError != null) {
            _state.update { it.copy(titleError = titleError, descriptionError = descriptionError) }
            return
        }
        saveJob = viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null) }
            Log.d(TAG, "save: title=${s.title}, cards=${s.cards.size}")

            val session = runSuspendCatching { identityRepository.currentSession() }.getOrNull()
                ?: runSuspendCatching { identityRepository.loadPersistedSession() }.getOrNull()
            val authorPubky = session?.identity?.pubky ?: run {
                _state.update { it.copy(isSaving = false, error = "Not signed in.") }
                return@launch
            }

            val now = epochMillis()
            val actualDeckId = deckId ?: generateId()
            val (existing, latest) = refreshSnapshot()
            val cards = buildCards(s.cards, actualDeckId, now, latest)
            val cover = resolveCoverImage(s, actualDeckId, mediaRepository) ?: existing?.coverImageRef
            val deck = buildDeck(s, authorPubky, actualDeckId, existing, cards, now, cover)

            writeDeck(deck, cards, existing)
                .onSuccess {
                    Log.d(TAG, "save: SUCCESS deckId=$actualDeckId")
                    _state.update { it.copy(isSaving = false) }
                    settle(deck, isCreate = deckId == null)
                }
                .onFailure { err ->
                    Log.e(TAG, "save: FAILED — ${err.message}", err)
                    _state.update { it.copy(isSaving = false, error = err.message ?: "Save failed.") }
                }
        }
    }

    /**
     * Leave the editor, offering to announce the deck first when this save *created* it (#39).
     *
     * [isCreate] keys off the constructor's `deckId` being null, which is the editor's only honest
     * "new deck" signal: `save()` republishes the whole manifest on every edit, so announcing from
     * the success path unconditionally would post again every time someone fixed a typo.
     */
    private suspend fun settle(deck: Deck, isCreate: Boolean) {
        savedDeckId = deck.id
        if (isCreate && appPreferences.shareOnPubky.first()) {
            _state.update {
                it.copy(
                    sharePrompt = DeckSharePrompt(
                        DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Created),
                    ),
                )
            }
            return
        }
        _effects.emit(DeckEditorEffect.SaveSuccess(deck.id))
    }

    /** Post the announcement, then leave regardless — a failed post is not a failed save. */
    fun onShareConfirm() {
        val prompt = _state.value.sharePrompt?.takeIf { !it.isPosting } ?: return
        viewModelScope.launch {
            _state.update { it.copy(sharePrompt = prompt.copy(isPosting = true)) }
            discoveryRepository.announceDeck(prompt.announcement)
                .onSuccess { _effects.emit(DeckEditorEffect.Shared) }
                .onFailure { err ->
                    Log.e(TAG, "share: FAILED — ${err.message}", err)
                    _effects.emit(DeckEditorEffect.ShareFailed)
                }
            dismissSharePrompt()
        }
    }

    fun onShareDismiss() {
        _state.value.sharePrompt?.takeIf { !it.isPosting } ?: return
        viewModelScope.launch { dismissSharePrompt() }
    }

    /** Declines *and* turns the offer off, so the prompt and the Settings switch stay one setting. */
    fun onShareNeverAsk() {
        _state.value.sharePrompt?.takeIf { !it.isPosting } ?: return
        viewModelScope.launch {
            appPreferences.setShareOnPubky(false)
            dismissSharePrompt()
        }
    }

    private suspend fun dismissSharePrompt() {
        val savedId = savedDeckId ?: return
        _state.update { it.copy(sharePrompt = null) }
        _effects.emit(DeckEditorEffect.SaveSuccess(savedId))
    }

    /**
     * Persist the deck, writing as little as the change allows.
     *
     * Republishing rewrites every chunk. For a metadata-only edit — a rename, a tag, a new cover —
     * that would re-upload the entire deck to change a single field: ~201 requests and every card's
     * bytes for a 20k-card deck. When the card set is untouched, only the manifest is written.
     */
    private suspend fun writeDeck(deck: Deck, cards: List<Card>, existing: Deck?): Result<Deck> =
        if (existing != null && !cardsChanged(existing, cards)) {
            Log.d(TAG, "save: metadata-only deckId=${deck.id} cards=${cards.size}")
            deckRepository.updateMetadata(
                deck.copy(cardCount = existing.cardCount, chunks = existing.chunks),
            )
        } else {
            Log.d(TAG, "save: full publish deckId=${deck.id} cards=${cards.size}")
            deckRepository.publish(deck, cards)
        }

    /**
     * Whether the card set differs from what was loaded — in membership, order, or content.
     *
     * Compares against [loadedCards] rather than a count, so an edit that swaps a card's text
     * without changing how many there are still triggers a full write. `updatedAt` is ignored:
     * [buildCards] restamps every card with `now`, so comparing it would report every save as a
     * change and defeat the check.
     */
    private fun cardsChanged(existing: Deck, cards: List<Card>): Boolean {
        if (loadedCards.size != cards.size) return true
        if (existing.chunks.isEmpty() && existing.cardCount > 0) return true // chunk table unknown
        return loadedCards.zip(cards).any { (before, after) ->
            before.id != after.id || before.front != after.front || before.back != after.back
        }
    }

    companion object {
        private const val TAG = "Loopky/DeckEditorVM"
    }
}

private const val CARDS_LOAD_FAILED =
    "Couldn't load this deck's cards. Saving now would remove them, so try again when you're back online."

private const val TITLE_MAX_LENGTH = 120
private const val DESCRIPTION_MAX_LENGTH = 500
private const val DEFAULT_IMAGE_MIME = "image/jpeg"

private fun titleErrorFor(text: String): String? =
    if (text.length > TITLE_MAX_LENGTH) "Title must be $TITLE_MAX_LENGTH characters or fewer." else null

private fun descriptionErrorFor(text: String): String? =
    if (text.length > DESCRIPTION_MAX_LENGTH) {
        "Description must be $DESCRIPTION_MAX_LENGTH characters or fewer."
    } else {
        null
    }

private fun Card.toEditable(): EditableCardModel = EditableCardModel(
    id = id,
    frontText = front.text ?: "",
    backText = back.text ?: "",
    hasImage = front.imageRef != null || back.imageRef != null,
    hasAudio = front.audioRef != null || back.audioRef != null,
    original = this,
)

/** Uploads picked gallery bytes or wraps a web URL; null means "keep whatever is there". */
private suspend fun resolveCoverImage(
    s: DeckEditorUiState,
    deckId: String,
    mediaRepository: MediaRepository,
): MediaRef.Image? = when {
    s.coverPendingBytes != null ->
        mediaRepository.putImage(deckId, s.coverPendingBytes, s.coverPendingMime ?: DEFAULT_IMAGE_MIME)
            .getOrNull()

    s.coverImageUrl != null -> MediaRef.Image(
        path = "",
        mime = DEFAULT_IMAGE_MIME,
        sha256 = "",
        width = null,
        height = null,
        url = s.coverImageUrl,
    )

    else -> null
}

/**
 * Saving republishes every card record, so each side is rebuilt *from* the card as stored:
 * the editor only edits text, and dropping the rest would wipe the card's image and audio
 * off the homeserver.
 */
private fun buildCards(
    editables: List<EditableCardModel>,
    deckId: String,
    now: Long,
    latest: Map<String, Card>,
): List<Card> = editables.map { editable ->
    // The repository's copy first: it has anything the card editor changed since this list loaded.
    val original = latest[editable.id] ?: editable.original
    val front = (original?.front ?: CardSide()).copy(text = editable.frontText.ifBlank { null })
    val back = (original?.back ?: CardSide()).copy(text = editable.backText.ifBlank { null })
    val unchanged = original != null && original.front == front && original.back == back
    Card(
        id = editable.id,
        deckId = deckId,
        // Only bump the timestamp the sync reads when the card actually changed.
        updatedAt = if (unchanged) original.updatedAt else now,
        front = front,
        back = back,
    )
}

/** [existing] supplies the fields this editor does not expose, so a save cannot destroy them. */
@Suppress("LongParameterList")
private fun buildDeck(
    s: DeckEditorUiState,
    authorPubky: String,
    deckId: String,
    existing: Deck?,
    cards: List<Card>,
    now: Long,
    coverImageRef: MediaRef.Image?,
): Deck = Deck(
    id = deckId,
    authorPubky = authorPubky,
    title = s.title,
    description = s.description.ifBlank { null },
    coverEmoji = s.coverEmoji.ifBlank { null },
    coverImageRef = coverImageRef,
    tags = s.tags.map { Tag(it) },
    createdAt = if (s.isNew) now else existing?.createdAt ?: now,
    updatedAt = now,
    // publish() recomputes the chunk table from the cards it writes, so the count here is just
    // the optimistic value; `chunks` is deliberately left empty rather than guessed at.
    cardCount = cards.size,
    source = existing?.source,
    listenEnabled = existing?.listenEnabled ?: true,
    speakEnabled = existing?.speakEnabled ?: true,
)

/** The deck and its cards as the repository has them right now — see `refreshSnapshot`. */
private data class DeckSnapshot(val deck: Deck?, val cardsById: Map<String, Card>)

data class DeckEditorUiState(
    val isNew: Boolean = true,
    val coverEmoji: String = "",
    val title: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val cards: List<EditableCardModel> = emptyList(),
    val coverImageUrl: String? = null,
    val coverPendingBytes: ByteArray? = null,
    val coverPendingMime: String? = null,
    val isSaving: Boolean = false,
    val titleError: String? = null,
    val descriptionError: String? = null,
    val error: String? = null,
    /** Set after a save that created the deck, unless the user has opted out of being asked (#39). */
    val sharePrompt: DeckSharePrompt? = null,
)

data class EditableCardModel(
    val id: String,
    val frontText: String,
    val backText: String,
    val hasImage: Boolean,
    val hasAudio: Boolean,
    /**
     * The card this model was loaded from, kept so a save round-trip preserves the image and
     * audio refs the editor cannot edit. `null` for cards added in this session.
     */
    val original: Card? = null,
)

sealed interface DeckEditorEffect {
    data object NavigateBack : DeckEditorEffect
    data class NavigateEditCard(val deckId: String, val cardId: String) : DeckEditorEffect
    data class SaveSuccess(val deckId: String) : DeckEditorEffect

    /** The announcement post went out, or didn't. Cosmetic either way — the deck is saved. */
    data object Shared : DeckEditorEffect
    data object ShareFailed : DeckEditorEffect
}
