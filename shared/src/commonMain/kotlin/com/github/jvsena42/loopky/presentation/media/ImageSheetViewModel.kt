package com.github.jvsena42.loopky.presentation.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.unsplash.UnsplashClient
import com.github.jvsena42.loopky.data.unsplash.UnsplashPhoto
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs the "from web" image grid in the cover and card-image sheets. Debounces the search query
 * and queries [UnsplashClient]. When Unsplash is unconfigured (no access key) the grid stays empty
 * and the sheet falls back to gallery-only.
 */
class ImageSheetViewModel(
    private val unsplashClient: UnsplashClient,
) : ViewModel() {
    private val _state = MutableStateFlow(
        ImageSheetUiState(isUnsplashConfigured = unsplashClient.isConfigured),
    )
    val state: StateFlow<ImageSheetUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    /** Photo ids already reported to Unsplash, so re-picking the same photo doesn't double-count. */
    private val pinged = mutableSetOf<String>()

    init {
        if (unsplashClient.isConfigured) loadInitial()
    }

    private fun loadInitial() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            unsplashClient.random()
                .onSuccess { photos -> _state.update { it.copy(photos = photos, isLoading = false) } }
                .onFailure { err -> _state.update { it.copy(isLoading = false, error = err.error()) } }
        }
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        if (!unsplashClient.isConfigured) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            _state.update { it.copy(isLoading = true, error = null) }
            unsplashClient.search(query)
                .onSuccess { photos -> _state.update { it.copy(photos = photos, isLoading = false) } }
                .onFailure { err -> _state.update { it.copy(isLoading = false, error = err.error()) } }
        }
    }

    /** Highlights [photo] in the grid. Selection lives here so the credit line and the Done
     *  button both see the whole photo, not just its URL. */
    fun onPhotoSelected(photo: UnsplashPhoto) {
        _state.update { it.copy(selectedPhoto = photo) }
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

    private fun Throwable.error(): String = message ?: "Could not load images."

    companion object {
        private const val DEBOUNCE_MS = 350L
    }
}

data class ImageSheetUiState(
    val query: String = "",
    val photos: List<UnsplashPhoto> = emptyList(),
    val isLoading: Boolean = false,
    val isUnsplashConfigured: Boolean = false,
    val selectedPhoto: UnsplashPhoto? = null,
    val error: String? = null,
)
