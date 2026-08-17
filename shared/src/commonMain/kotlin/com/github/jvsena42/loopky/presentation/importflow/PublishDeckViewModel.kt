package com.github.jvsena42.loopky.presentation.importflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.ImportRepository
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DeckSource
import com.github.jvsena42.loopky.domain.model.DraftCardImage
import com.github.jvsena42.loopky.domain.model.FormError
import com.github.jvsena42.loopky.domain.model.ImportDraft
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.domain.model.frontBackOf
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.epochMillis
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Suppress("TooManyFunctions")
class PublishDeckViewModel(
    private val importRepository: ImportRepository,
    private val deckRepository: DeckRepository,
    private val identityRepository: IdentityRepository,
    private val mediaRepository: MediaRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PublishDeckUiState())
    val state: StateFlow<PublishDeckUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<PublishDeckEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<PublishDeckEffect> = _effects.asSharedFlow()

    private var publishJob: Job? = null
    private var undoCountdownJob: Job? = null

    init {
        val draft = importRepository.currentDraft()
        if (draft != null) {
            val kept = importRepository.keptRows().size
            _state.update {
                it.copy(
                    // A file import knows a good title — the .apkg's deck name, else the file it
                    // came from — and used to throw it away. A paste has none, so this stays "".
                    title = draft.suggestedTitle.orEmpty(),
                    cardCount = kept,
                    discardedCount = draft.rows.size - kept,
                )
            }
        }
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

    fun onToggleListen() {
        _state.update { it.copy(listenEnabled = !it.listenEnabled) }
    }

    fun onToggleSpeak() {
        _state.update { it.copy(speakEnabled = !it.speakEnabled) }
    }

    /** A web (Unsplash) cover image was chosen — saved by URL, no upload. */
    fun onCoverWebSelected(url: String) {
        _state.update { it.copy(coverImageUrl = url, coverPendingBytes = null, coverPendingMime = null) }
    }

    /** A gallery cover image was chosen — already compressed; uploaded on publish. */
    fun onCoverGallerySelected(bytes: ByteArray, mime: String) {
        _state.update { it.copy(coverImageUrl = null, coverPendingBytes = bytes, coverPendingMime = mime) }
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
        _state.update { s -> s.copy(tags = s.tags + trimmed) }
    }

    fun onRemoveTag(tag: String) {
        _state.update { s -> s.copy(tags = s.tags - tag) }
    }

    fun onBackClick() {
        viewModelScope.launch { _effects.emit(PublishDeckEffect.NavigateBack) }
    }

    fun onPublishClick() {
        if (publishJob?.isActive == true) return
        val s = _state.value
        if (!validateForPublish(s)) return

        val draft = importRepository.currentDraft()
        if (draft == null) {
            _state.update { it.copy(error = "No import data. Please go back and paste again.") }
            return
        }

        publishJob = viewModelScope.launch {
            // publishedCardCount is reset here so a retry after a failure does not open on the
            // count the failed run reached.
            _state.update {
                it.copy(isPublishing = true, error = null, publishProgress = 0f, publishedCardCount = 0)
            }
            Log.d(TAG, "publish: title=${s.title}, cards=${importRepository.keptRows().size}")

            val session = runCatching { identityRepository.currentSession() }.getOrNull()
                ?: runCatching { identityRepository.loadPersistedSession() }.getOrNull()
            val authorPubky = session?.identity?.pubky ?: run {
                _state.update { it.copy(isPublishing = false, error = "Not signed in.") }
                return@launch
            }

            val now = epochMillis()
            val deckId = generateId()
            val cards = buildCards(draft, deckId, now)
            val coverImageRef = resolveCoverImage(s, deckId)

            val deck = s.toDeck(deckId, authorPubky, coverImageRef, cards, now)

            publishWithProgress(deck, cards)
                .onSuccess {
                    Log.d(TAG, "publish: SUCCESS deckId=$deckId")
                    _state.update {
                        it.copy(
                            isPublishing = false,
                            publishProgress = null,
                            publishedDeckId = deckId,
                            undoSecondsRemaining = UNDO_WINDOW_SECONDS,
                        )
                    }
                    startUndoCountdown(deckId)
                }
                .onFailure { err ->
                    Log.e(TAG, "publish: FAILED — ${err.message}", err)
                    _state.update {
                        it.copy(
                            isPublishing = false,
                            publishProgress = null,
                            error = err.message ?: "Publish failed.",
                        )
                    }
                }
        }
    }

    private fun PublishDeckUiState.toDeck(
        deckId: String,
        authorPubky: String,
        coverImageRef: MediaRef.Image?,
        cards: List<Card>,
        now: Long,
    ) = Deck(
        id = deckId,
        authorPubky = authorPubky,
        title = title,
        description = description.ifBlank { null },
        coverEmoji = coverEmoji.ifBlank { null },
        coverImageRef = coverImageRef,
        tags = tags.map { Tag(it) },
        createdAt = now,
        updatedAt = now,
        // publish() writes the chunk table; this is the optimistic count it confirms.
        cardCount = cards.size,
        source = DeckSource(kind = DeckSource.Kind.Import, importedAt = now),
        listenEnabled = listenEnabled,
        speakEnabled = speakEnabled,
    )

    /**
     * A 20k-card import is ~201 uploads; a bare spinner cannot say how far along that is, so the
     * repository's progress is mirrored into the UI state as it arrives.
     */
    private suspend fun publishWithProgress(deck: Deck, cards: List<Card>) =
        deckRepository.publish(deck, cards) { progress ->
            _state.update {
                // Not nulled on `done`: that made the bar blank for a frame at ~99% instead of
                // filling. `isPublishing = false` is what removes the whole block.
                it.copy(
                    publishProgress = progress.fraction,
                    publishedCardCount = progress.cardsWritten,
                )
            }
        }

    fun onUndoPublish() {
        val deckId = _state.value.publishedDeckId ?: return
        undoCountdownJob?.cancel()
        viewModelScope.launch {
            Log.d(TAG, "undo: deleting deckId=$deckId")
            deckRepository.delete(deckId)
                .onSuccess {
                    Log.d(TAG, "undo: SUCCESS deckId=$deckId")
                    _state.update {
                        it.copy(publishedDeckId = null, undoSecondsRemaining = 0, error = null)
                    }
                }
                .onFailure { err ->
                    Log.e(TAG, "undo: FAILED — ${err.message}", err)
                    _state.update { it.copy(error = err.message ?: "Undo failed.") }
                }
        }
    }

    fun onDonePublish() {
        val deckId = _state.value.publishedDeckId ?: return
        undoCountdownJob?.cancel()
        importRepository.clear()
        viewModelScope.launch { _effects.emit(PublishDeckEffect.Published(deckId)) }
    }

    private fun startUndoCountdown(deckId: String) {
        undoCountdownJob?.cancel()
        undoCountdownJob = viewModelScope.launch {
            var remaining = UNDO_WINDOW_SECONDS
            while (remaining > 0) {
                delay(COUNTDOWN_TICK_MS)
                remaining -= 1
                _state.update { it.copy(undoSecondsRemaining = remaining) }
            }
            importRepository.clear()
            _effects.emit(PublishDeckEffect.Published(deckId))
        }
    }

    /** Maps the kept triage rows to [Card]s, uploading any per-row images attached during triage. */
    private suspend fun buildCards(draft: ImportDraft, deckId: String, now: Long): List<Card> {
        val cards = mutableListOf<Card>()
        for (row in importRepository.keptRows()) {
            val (front, back) = draft.frontBackOf(row)
            cards.add(
                Card(
                    id = generateId(),
                    deckId = deckId,
                    updatedAt = now,
                    front = CardSide(
                        text = front.takeIf { it.isNotBlank() },
                        imageRef = resolveDraftImage(importRepository.rowImage(row.index, isFront = true), deckId),
                    ),
                    back = CardSide(
                        text = back.takeIf { it.isNotBlank() },
                        imageRef = resolveDraftImage(importRepository.rowImage(row.index, isFront = false), deckId),
                    ),
                ),
            )
        }
        return cards
    }

    /** Resolves a triage [DraftCardImage]: upload gallery bytes, wrap a web URL, else none. */
    private suspend fun resolveDraftImage(image: DraftCardImage?, deckId: String): MediaRef.Image? = when {
        image == null -> null
        image.bytes != null ->
            mediaRepository.putImage(deckId, image.bytes, image.mime ?: "image/jpeg")
                .onFailure { Log.e(TAG, "card image upload failed — ${it.message}", it) }
                .getOrNull()

        image.url != null ->
            MediaRef.Image(path = "", mime = "image/jpeg", sha256 = "", width = null, height = null, url = image.url)

        else -> null
    }

    /** Builds the cover [MediaRef.Image]: upload gallery bytes, or wrap a web URL, else none. */
    private suspend fun resolveCoverImage(s: PublishDeckUiState, deckId: String): MediaRef.Image? = when {
        s.coverPendingBytes != null ->
            mediaRepository.putImage(deckId, s.coverPendingBytes, s.coverPendingMime ?: "image/jpeg")
                .onFailure { Log.e(TAG, "cover upload failed — ${it.message}", it) }
                .getOrNull()

        s.coverImageUrl != null ->
            MediaRef.Image(path = "", mime = "image/jpeg", sha256 = "", width = null, height = null, url = s.coverImageUrl)

        else -> null
    }

    /**
     * Reachable again now that the button is enabled. It used to be dead code: the screen
     * passed `enabled = state.canPublish`, and `canPublish` was false in exactly the cases
     * this checks — so tapping Publish with an empty title did nothing and said nothing.
     */
    private fun validateForPublish(s: PublishDeckUiState): Boolean {
        val titleError = when {
            s.title.isBlank() -> FormError.TitleRequired
            else -> titleErrorFor(s.title)
        }
        val descriptionError = descriptionErrorFor(s.description)
        if (titleError != null || descriptionError != null) {
            _state.update { it.copy(titleError = titleError, descriptionError = descriptionError) }
            return false
        }
        return true
    }

    private fun titleErrorFor(text: String): FormError? =
        if (text.length > TITLE_MAX_LENGTH) FormError.TitleTooLong else null

    private fun descriptionErrorFor(text: String): FormError? =
        if (text.length > DESCRIPTION_MAX_LENGTH) FormError.DescriptionTooLong else null

    companion object {
        private const val TAG = "Loopky/PublishVM"
        private const val UNDO_WINDOW_SECONDS = 10
        private const val COUNTDOWN_TICK_MS = 1_000L
        /** Internal so a prefilled title can be capped to it rather than duplicating the number. */
        internal const val TITLE_MAX_LENGTH = 120
        private const val DESCRIPTION_MAX_LENGTH = 500

        private fun generateId(): String {
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            return (1..12).map { chars.random() }.joinToString("")
        }
    }
}

data class PublishDeckUiState(
    val title: String = "",
    val description: String = "",
    val coverEmoji: String = "",
    val tags: List<String> = emptyList(),
    val cardCount: Int = 0,
    val discardedCount: Int = 0,
    val isPublishing: Boolean = false,
    /**
     * 0f..1f while uploading, null when not publishing or when the count is too small to be worth
     * a determinate bar. Lets a large import show real progress instead of an indefinite spinner.
     */
    val publishProgress: Float? = null,
    /** Cards uploaded so far, for a "1,200 of 18,432" label alongside the bar. */
    val publishedCardCount: Int = 0,
    val publishedDeckId: String? = null,
    val undoSecondsRemaining: Int = 0,
    val listenEnabled: Boolean = true,
    val speakEnabled: Boolean = true,
    val coverImageUrl: String? = null,
    val coverPendingBytes: ByteArray? = null,
    val coverPendingMime: String? = null,
    val titleError: FormError? = null,
    val descriptionError: FormError? = null,
    val error: String? = null,
) {
    val canPublish: Boolean
        get() = title.isNotBlank() && titleError == null && descriptionError == null && !isPublishing
}

sealed interface PublishDeckEffect {
    data object NavigateBack : PublishDeckEffect
    data class Published(val deckId: String) : PublishDeckEffect
}
