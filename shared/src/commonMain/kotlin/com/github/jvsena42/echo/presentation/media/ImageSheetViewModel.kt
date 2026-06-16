package com.github.jvsena42.echo.presentation.media

import com.github.jvsena42.echo.data.unsplash.UnsplashClient
import com.github.jvsena42.echo.data.unsplash.UnsplashPhoto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    mainScope: CoroutineScope? = null,
) {
    private val scope: CoroutineScope =
        mainScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(
        ImageSheetUiState(isUnsplashConfigured = unsplashClient.isConfigured),
    )
    val state: StateFlow<ImageSheetUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        if (unsplashClient.isConfigured) loadInitial()
    }

    private fun loadInitial() {
        searchJob?.cancel()
        searchJob = scope.launch {
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
        searchJob = scope.launch {
            delay(DEBOUNCE_MS)
            _state.update { it.copy(isLoading = true, error = null) }
            unsplashClient.search(query)
                .onSuccess { photos -> _state.update { it.copy(photos = photos, isLoading = false) } }
                .onFailure { err -> _state.update { it.copy(isLoading = false, error = err.error()) } }
        }
    }

    fun onDispose() {
        searchJob?.cancel()
        scope.cancel()
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
    val error: String? = null,
)
