package com.github.jvsena42.echo.platform

/**
 * Native text-to-speech for card "Speak" buttons. Platform-provided via Koin (like `PubkyClient`)
 * because a TTS engine needs platform context/lifecycle — Android `TextToSpeech`, iOS
 * `AVSpeechSynthesizer`. Consumed by the UI layer when a `Speak` effect is collected.
 */
interface Speaker {
    fun speak(text: String)
}
