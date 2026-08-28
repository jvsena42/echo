package com.github.jvsena42.loopky.presentation.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.unsplash.UnsplashClient
import com.github.jvsena42.loopky.data.unsplash.UnsplashError
import com.github.jvsena42.loopky.data.unsplash.UnsplashException
import com.github.jvsena42.loopky.data.unsplash.UnsplashPhoto
import com.github.jvsena42.loopky.domain.model.ImageLink
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs the "from web" image grid in the cover and card-image sheets. Debounces the search query
 * and queries [UnsplashClient].
 *
 * Failures are surfaced as a typed [UnsplashError] rather than a message string: the platform layer
 * picks the copy (shared has no string resources), and the three key-related errors get an
 * "add your own key" call to action instead of a bland "no results".
 *
 * The same field also takes a link ([ImageLink]) — see [onQueryChange]. Search is only one of the
 * two things a person does with a text box holding an image address.
 */
class ImageSheetViewModel(
    private val unsplashClient: UnsplashClient,
) : ViewModel() {
    private val _state = MutableStateFlow(ImageSheetUiState())
    val state: StateFlow<ImageSheetUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    /** Photo ids already reported to Unsplash, so re-picking the same photo doesn't double-count. */
    private val pinged = mutableSetOf<String>()

    init {
        // Collected, not read once: the user can leave for Settings, set a key, and come back to a
        // sheet that was never torn down. Reloading on that edge is the whole point.
        //
        // The unconfigured case still loads rather than short-circuiting here, because the request
        // is what produces UnsplashError.MissingKey — and that error is what puts the "add a key"
        // panel on screen. Skipping it would leave an empty grid explaining nothing.
        unsplashClient.isConfigured
            .distinctUntilChanged()
            .onEach { loadInitial() }
            .launchIn(viewModelScope)
    }

    private fun loadInitial() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            unsplashClient.random()
                .onSuccess { photos -> _state.update { it.copy(photos = photos, isLoading = false) } }
                .onFailure { err -> _state.update { it.failed(err) } }
        }
    }

    /**
     * Takes the field's text as either a search term or a link, whichever it is.
     *
     * A link cancels the search rather than running alongside it: "cat.jpg" is not a query any
     * image API answers usefully, and leaving a grid of results under a pasted address would offer
     * two answers to one input. The highlighted photo goes with it, so [ImageSheetUiState.link] and
     * [ImageSheetUiState.selectedPhoto] can never both be waiting to be committed.
     */
    fun onQueryChange(query: String) {
        val link = ImageLink.parse(query)
        if (link != null) {
            searchJob?.cancel()
            _state.update {
                it.copy(query = query, link = link, selectedPhoto = null, isLoading = false, error = null)
            }
            return
        }
        _state.update { it.copy(query = query, link = null) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            _state.update { it.copy(isLoading = true, error = null) }
            unsplashClient.search(query)
                .onSuccess { photos -> _state.update { it.copy(photos = photos, isLoading = false) } }
                .onFailure { err -> _state.update { it.failed(err) } }
        }
    }

    /** Highlights [photo] in the grid. Selection lives here so the credit line and the Done
     *  button both see the whole photo, not just its URL. */
    fun onPhotoSelected(photo: UnsplashPhoto) {
        _state.update { it.copy(selectedPhoto = photo) }
    }

    /** Empties the field, dropping the link with it and putting the grid back. */
    fun onLinkCleared() {
        onQueryChange("")
    }

    /**
     * Reports the selected photo to Unsplash as used — call this when the pick is committed, not
     * when it is merely highlighted. Fire-and-forget: a failed ping must never block the user, so
     * it is neither awaited nor surfaced as an error.
     */
    fun onPhotoUsed() {
        val photo = _state.value.selectedPhoto ?: return
        if (!pinged.add(photo.id)) return
        viewModelScope.launch { unsplashClient.trackDownload(photo) }
    }

    /**
     * Clears the grid as well as flagging the error. Leaving stale results underneath an error
     * panel reads as "here are some photos, and also something went wrong" — two contradictory
     * answers to one search.
     */
    private fun ImageSheetUiState.failed(cause: Throwable): ImageSheetUiState = copy(
        isLoading = false,
        photos = emptyList(),
        selectedPhoto = null,
        error = (cause as? UnsplashException)?.error ?: UnsplashError.Unavailable,
    )

    companion object {
        private const val DEBOUNCE_MS = 350L
    }
}

data class ImageSheetUiState(
    val query: String = "",
    /** Set when [query] holds an address rather than a search term; the grid gives way to it. */
    val link: ImageLink? = null,
    val photos: List<UnsplashPhoto> = emptyList(),
    val isLoading: Boolean = false,
    val selectedPhoto: UnsplashPhoto? = null,
    val error: UnsplashError? = null,
)
