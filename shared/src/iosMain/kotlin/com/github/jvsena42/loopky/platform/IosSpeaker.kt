package com.github.jvsena42.loopky.platform

/**
 * iOS [Speaker]. iOS is not yet runnable end-to-end (see CLAUDE.md), so this is a no-op for now —
 * it keeps the DI graph symmetric across platforms. The Swift side of the eventual wiring exists
 * in `iosApp/iosApp/Views/SpeechSpeaker.swift`, which already honours the language tag.
 */
class IosSpeaker : Speaker {

    override fun speak(text: String, languageTag: String): SpeakOutcome =
        SpeakOutcome.EngineUnavailable

    override fun availableLanguages(): List<String> = emptyList()
}
