import AVFoundation

/// Minimal native TTS used by the study session's `Speak` effect.
final class SpeechSpeaker {
    static let shared = SpeechSpeaker()

    private let synthesizer = AVSpeechSynthesizer()

    private init() {}

    /// Reads `text` in `languageTag` (BCP-47, e.g. `"es-ES"`), interrupting anything in progress.
    ///
    /// The voice is set explicitly rather than left to default: without it AVSpeechSynthesizer
    /// uses the *reader's* device language, which reads a Spanish card with an English voice.
    /// Returns false when iOS has no voice installed for the tag, so the caller can say so instead
    /// of letting the wrong accent pass for the right one.
    @discardableResult
    func speak(_ text: String, languageTag: String) -> Bool {
        guard let voice = AVSpeechSynthesisVoice(language: languageTag) else { return false }
        synthesizer.stopSpeaking(at: .immediate)
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = voice
        synthesizer.speak(utterance)
        return true
    }

    /// BCP-47 tags iOS has voices for, for the deck language picker.
    func availableLanguages() -> [String] {
        Array(Set(AVSpeechSynthesisVoice.speechVoices().map(\.language))).sorted()
    }
}
