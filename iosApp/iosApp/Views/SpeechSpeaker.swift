import AVFoundation

/// Minimal native TTS used by the study session and card editor `Speak` effects.
final class SpeechSpeaker {
    static let shared = SpeechSpeaker()

    private let synthesizer = AVSpeechSynthesizer()

    private init() {}

    func speak(_ text: String) {
        synthesizer.stopSpeaking(at: .immediate)
        synthesizer.speak(AVSpeechUtterance(string: text))
    }
}
