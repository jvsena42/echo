package com.github.jvsena42.loopky.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The platform collaborators a headless client answers for by *not* doing them.
 *
 * All four exist because `sharedModule` resolves them, not because the CLI has any use for them —
 * `:shared` is one Koin graph and a missing binding is a start-up failure, so the JVM target has
 * to answer for the whole surface. Each returns the honest "no" rather than a stub that pretends:
 * a `Speaker` that silently succeeds would let a study command claim it read a card aloud.
 */

/** No TTS. `Deck.speechReady` is about the deck; this is about the host, and a server has no voice. */
class NoSpeaker : Speaker {
    override fun speak(text: String, languageTag: String): SpeakOutcome = SpeakOutcome.EngineUnavailable
    override fun availableLanguages(): List<String> = emptyList()
}

/** No microphone. [listen] completes immediately with the unavailable error rather than hanging. */
class NoSpeechRecognizer : SpeechRecognizer {
    override fun isAvailable(): Boolean = false
    override fun listen(languageTag: String?): Flow<SpeechEvent> =
        flowOf(SpeechEvent.Error(SpeechError.UNAVAILABLE))
}

/**
 * No Pubky Ring on this machine, which is the *correct* answer even when the user has it on their
 * phone: [isInstalled] asks whether a `pubkyauth://` deeplink has somewhere to go **here**, and on
 * a headless box it does not. Answering false is what makes the desktop sign-in a printed QR code
 * — the same fallback a tablet with no Ring gets — rather than a deeplink into nothing.
 */
class NoPubkyRingPresence : PubkyRingPresence {
    override fun isInstalled(): Boolean = false
    override fun canImportKey(): Boolean = false
    override val installUrl: String = PUBKY_RING_INSTALL_URL
}

/** No OS password manager to save a recovery phrase into. */
class NoPasswordManagerPresence : PasswordManagerPresence {
    override fun canSave(): Boolean = false
}

/**
 * Where to get Pubky Ring, for a message printed on a terminal rather than a button.
 *
 * The web page rather than a store deeplink, because the reader is at a keyboard and the phone
 * that needs the app is a different device.
 */
private const val PUBKY_RING_INSTALL_URL = "https://pubkyring.app"
