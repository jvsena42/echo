package com.github.jvsena42.loopky.platform

/**
 * iOS [Speaker]. iOS is not yet runnable end-to-end (see CLAUDE.md), so this is a no-op for now —
 * it keeps the DI graph symmetric across platforms. Wire it to `AVSpeechSynthesizer` when the iOS
 * app is brought up.
 */
class IosSpeaker : Speaker {
    override fun speak(text: String) {
        // No-op until the iOS app is wired (AVFoundation TTS goes here).
    }
}
