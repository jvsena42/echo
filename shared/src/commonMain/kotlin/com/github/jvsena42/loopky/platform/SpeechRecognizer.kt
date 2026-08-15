package com.github.jvsena42.loopky.platform

import kotlinx.coroutines.flow.Flow

/** Events emitted while listening for speech. */
sealed interface SpeechEvent {
    data object Ready : SpeechEvent
    data object BeginningOfSpeech : SpeechEvent
    data class Partial(val text: String) : SpeechEvent
    data class Result(val text: String) : SpeechEvent
    data class Error(val reason: SpeechError) : SpeechEvent
}

enum class SpeechError { NO_MATCH, PERMISSION, NETWORK, BUSY, UNAVAILABLE, UNKNOWN }

/**
 * On-device speech recognition for the Speak pronunciation practice (study). Implemented per
 * platform (Android [android.speech.SpeechRecognizer] / iOS Speech framework). The caller must
 * hold microphone permission before collecting [listen]; cancelling the collector stops it.
 */
interface SpeechRecognizer {
    /** Whether recognition is available on this device. */
    fun isAvailable(): Boolean

    /** Start listening, emitting [SpeechEvent]s until the flow is cancelled or completes. */
    fun listen(languageTag: String? = null): Flow<SpeechEvent>
}
