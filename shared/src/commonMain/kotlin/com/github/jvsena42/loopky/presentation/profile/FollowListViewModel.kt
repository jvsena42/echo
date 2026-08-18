package com.github.jvsena42.loopky.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.DiscoveryRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One side of a user's follow graph, as people you can actually open.
 *
 * Both directions are the same screen because they are the same list with a different source —
 * see [FollowSource]. Everything here is already filtered to Loopky accounts by
 * [DiscoveryRepository.followingProfiles]; the empty state has to say so, or a list that is short
 * because most of your follows are pubky.app-only reads as a bug.
 */
class FollowListViewModel(
    private val targetPubky: String,
    private val source: FollowSource,
    private val discoveryRepository: DiscoveryRepository,
    private val identityRepository: IdentityRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(FollowListUiState(source = source))
    val state: StateFlow<FollowListUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun onRetry() = load()

    private fun load() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorReason = null) }

            // Whose graph this is decides the wording of the empty state: "people you follow"
            // is wrong the moment this screen is opened from someone else's profile.
            val myPubky = runSuspendCatching { identityRepository.currentSession()?.identity?.pubky }
                .getOrNull()
            _state.update { it.copy(isSelf = myPubky != null && myPubky == targetPubky) }

            runSuspendCatching {
                when (source) {
                    FollowSource.FOLLOWING -> discoveryRepository.followingProfiles(targetPubky)
                    FollowSource.FOLLOWERS -> discoveryRepository.followerProfiles(targetPubky)
                }
            }.onSuccess { people ->
                Log.d(TAG, "load: $source — ${people.size} Loopky accounts")
                _state.update { it.copy(isLoading = false, people = people) }
            }.onFailure { err ->
                Log.e(TAG, "load: $source FAILED — ${err.message}", err)
                _state.update { it.copy(isLoading = false, errorReason = err.toErrorReason()) }
            }
        }
    }

    companion object {
        private const val TAG = "Loopky/FollowListVM"
    }
}

/** Which direction of the follow graph a list shows. */
enum class FollowSource { FOLLOWING, FOLLOWERS }

data class FollowListUiState(
    val source: FollowSource,
    val isLoading: Boolean = true,
    /** True when this is the signed-in user's own graph — the empty state addresses them. */
    val isSelf: Boolean = false,
    val people: List<PubkyIdentity> = emptyList(),
    val errorReason: ErrorReason? = null,
)
