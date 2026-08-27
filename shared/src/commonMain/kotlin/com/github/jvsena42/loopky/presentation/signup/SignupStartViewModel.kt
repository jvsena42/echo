package com.github.jvsena42.loopky.presentation.signup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.homegate.MethodAvailability
import com.github.jvsena42.loopky.data.price.PriceSource
import com.github.jvsena42.loopky.data.repository.SignupRepository
import com.github.jvsena42.loopky.domain.model.formatSatsAsUsd
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * "Prove you're not a robot" — pick how to obtain a signup token.
 *
 * **Loopky redeems the token itself**, so nothing has to be installed to get through here. This
 * screen used to be parameterised by who would spend the token — Pubky Ring or Loopky — which put
 * a Ring install gate in front of every method and offered a "create it in Loopky instead" link
 * that re-entered this same screen with a different argument. Two identical screens for one
 * decision. Ring is now offered *after* the account exists, as a custody change, from the backup
 * step and the Settings nag — by which point the user has something to move rather than a
 * prerequisite to satisfy.
 *
 * **Method availability is a courtesy, not a gate.** A method Homegate could not be asked about
 * stays **enabled**: locking someone out of the only route into the app because a probe timed out
 * is worse than letting that method's own screen explain itself.
 */
class SignupStartViewModel(
    private val signupRepository: SignupRepository,
    private val priceSource: PriceSource,
) : ViewModel() {
    private val _state = MutableStateFlow(SignupStartUiState())
    val state: StateFlow<SignupStartUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            val availability = runSuspendCatching { signupRepository.availability() }
                .onFailure { Log.w(TAG, "load: availability failed — offering both anyway") }
                .getOrNull()
            _state.update {
                it.copy(
                    isLoading = false,
                    sms = availability?.sms ?: MethodAvailability.Unknown,
                    lightning = availability?.lightning ?: MethodAvailability.Unknown,
                )
            }
            loadFiatQuote()
        }
    }

    /**
     * A courtesy figure beside the sats price, fetched once per screen entry.
     *
     * Never gates anything: this is the screen where someone is trying to get *into* the app, so
     * the button stays enabled while the quote is in flight and after it fails. Only asked for when
     * Homegate actually quoted a price, so the request comes from users already on the Lightning
     * branch rather than everyone who opens onboarding.
     */
    private fun loadFiatQuote() {
        val sats = _state.value.lightningPriceSat ?: return
        viewModelScope.launch {
            val rate = priceSource.usdPerBtc()
            _state.update { it.copy(fiatPrice = formatSatsAsUsd(sats, rate)) }
        }
    }

    private companion object {
        const val TAG = "Loopky/SignupStartVM"
    }
}

data class SignupStartUiState(
    val isLoading: Boolean = true,
    val sms: MethodAvailability = MethodAvailability.Unknown,
    val lightning: MethodAvailability = MethodAvailability.Unknown,
    /**
     * Pre-formatted, e.g. `"≈ US$0.85"`. Null whenever no rate is known — the screen then renders
     * the sats-only string, unchanged.
     *
     * Formatted here rather than in the composable because the invoice screen recomposes on a
     * countdown tick, and money formatting has no business running once a second.
     */
    val fiatPrice: String? = null,
) {
    /** Disabled only when Homegate positively said no — never merely because we could not ask. */
    val isSmsEnabled: Boolean get() = sms !is MethodAvailability.Unavailable
    val isLightningEnabled: Boolean get() = lightning !is MethodAvailability.Unavailable

    /** Sats for the Lightning route, when known. */
    val lightningPriceSat: Long? get() = (lightning as? MethodAvailability.Available)?.priceSat
}
