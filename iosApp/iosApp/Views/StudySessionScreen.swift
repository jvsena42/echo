import SwiftUI
import Shared

/// VM-driven wrapper around `StudySessionView` — the SRS review loop.
///
/// **Listen works here without a Kotlin `IosSpeaker`.** The ViewModel emits
/// `StudySessionEffect.Speak(text:languageTag:)` and this screen plays it through the native
/// `SpeechSpeaker`, which honours the deck's declared language rather than the reader's locale.
/// `IosSpeaker` staying a stub only affects the deck editor's voice list.
///
/// **Speak (pronunciation practice) is not available.** It needs a `SpeechRecognizer` binding,
/// microphone and speech-recognition usage strings in `Info.plist`, and a permission flow. Until
/// then the button is hidden rather than shown-and-inert — `Deck.speechReady` and the per-deck
/// opt-ins already make "this deck declares Speak but this device cannot offer it" a supported
/// state, not a broken one. Tracked in #113.
struct StudySessionScreen: View {
    /// `nil` studies everything due across the library.
    let deckId: String?
    var onClose: () -> Void = {}

    @State private var viewModel: StudySessionViewModel?
    @State private var uiState: Any?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?

    /// The typed answer is owned here while the user types, like every other field in the app: a
    /// binding that round-trips through Kotlin drops characters.
    @State private var typed = ""

    var body: some View {
        StudySessionView(
            state: viewState,
            typed: Binding(
                get: { typed },
                set: { typed = $0; viewModel?.onAnswerChange(text: $0) }
            ),
            onClose: { viewModel?.onClose() },
            onReveal: { viewModel?.onReveal() },
            onGrade: { viewModel?.onGrade(grade: $0) },
            onCheckAnswer: { viewModel?.onCheckAnswer() },
            onGiveUp: { viewModel?.onGiveUp() },
            onListen: { viewModel?.onSpeak() },
            onNextCard: { viewModel?.onNextCard() },
            onDismissSyncError: { viewModel?.onDismissSyncError() },
            onContinueAfterGoal: { viewModel?.onContinueAfterGoal() }
        )
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    private var viewState: StudyViewState {
        if uiState is StudySessionUiStateLoading { return StudyViewState(phase: .loading) }
        if let empty = uiState as? StudySessionUiStateEmpty {
            return StudyViewState(phase: .empty, deckTitle: empty.deckTitle)
        }
        if let error = uiState as? StudySessionUiStateError {
            return StudyViewState(phase: .failed, errorMessage: ErrorCopy.message(for: error.reason))
        }
        if let done = uiState as? StudySessionUiStateComplete {
            return StudyViewState(
                phase: .complete,
                syncErrorMessage: done.syncError.map { ErrorCopy.message(for: $0) },
                reviewed: Int(done.reviewed)
            )
        }
        guard let card = uiState as? StudySessionUiStateReviewing else { return StudyViewState() }
        return StudyViewState(
            phase: .reviewing,
            deckTitle: card.deckTitle,
            position: Int(card.position),
            total: Int(card.total),
            reversed: card.reversed,
            frontText: card.frontText,
            backText: card.backText,
            backLabel: card.backLabel,
            revealed: card.revealed,
            answerHidden: card.answerHidden,
            gradesAvailable: card.gradesAvailable,
            intervals: Self.intervals(card.intervals),
            listenEnabled: card.listenEnabled,
            typePhase: Self.typePhase(card.typePhase),
            typeMissMessage: Self.missMessage(card.typePhase),
            promptLanguage: card.reversed ? card.backLang : card.frontLang,
            deckId: card.deckId,
            authorPubky: card.authorPubky,
            frontImageRef: card.frontImageRef,
            backImageRef: card.backImageRef,
            syncErrorMessage: card.syncError.map { ErrorCopy.message(for: $0) },
            goalReached: card.goalCelebration != nil
        )
    }

    private static func intervals(_ raw: [SrsGrade: String]) -> [StudyGrade: String] {
        var out: [StudyGrade: String] = [:]
        for grade in StudyGrade.allCases {
            out[grade] = raw[grade.shared]
        }
        return out
    }

    private static func typePhase(_ phase: TypePhase) -> StudyTypePhase {
        switch phase {
        case is TypePhaseAnswering: return .answering
        case is TypePhaseCorrect: return .correct
        case is TypePhaseGaveUp: return .gaveUp
        default: return .off
        }
    }

    /// The miss line names *what kind* of miss it was — a near miss is worth a different sentence
    /// from a wrong answer, because "check the accents" is a hint about what you typed rather than
    /// a verdict on the card.
    private static func missMessage(_ phase: TypePhase) -> String? {
        guard let answering = phase as? TypePhaseAnswering, let miss = answering.lastMiss else { return nil }
        let nearMiss = miss.outcome == TypedAnswerOutcome.nearmiss
        return NSLocalizedString(nearMiss ? "study_type_near_miss" : "study_type_wrong", comment: "")
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.studySessionViewModel(deckId: deckId)
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { value in
            uiState = value
            // Clearing on advance keeps the previous card's answer out of the next card's field.
            if let card = value as? StudySessionUiStateReviewing, card.typedAnswer != typed {
                typed = card.typedAnswer
            }
        }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case let speak as StudySessionEffectSpeak:
                SpeechSpeaker.shared.speak(speak.text, languageTag: speak.languageTag)
            case is StudySessionEffectClose:
                onClose()
            default:
                // `StartSpeechRecognition` lands here until iOS has a recognizer. The button that
                // would emit it is hidden, so nothing reaches this in practice.
                break
            }
        }
    }

    private func detach() {
        if let viewModel { IosDependencies.shared.clear(viewModel: viewModel) }
        viewModel = nil
        stateSink = nil
        effectSink = nil
    }
}

/// The four SRS grades, mirrored natively so the view can switch exhaustively and keep the order
/// fixed — hardest first. The order is muscle memory; it must not vary between screens.
enum StudyGrade: CaseIterable {
    case again, hard, good, easy

    var shared: SrsGrade {
        switch self {
        case .again: return SrsGrade.again
        case .hard: return SrsGrade.hard
        case .good: return SrsGrade.good
        case .easy: return SrsGrade.easy
        }
    }

    var label: String { shared.name }

    var color: Color {
        switch self {
        case .again: return LoopkyColor.srsAgain
        case .hard: return LoopkyColor.srsHard
        case .good: return LoopkyColor.srsGood
        case .easy: return LoopkyColor.srsEasy
        }
    }
}

enum StudyTypePhase { case off, answering, correct, gaveUp }

enum StudyPhase { case loading, reviewing, empty, complete, failed }

struct StudyViewState {
    var phase: StudyPhase = .loading
    var deckTitle: String = ""
    var position: Int = 0
    var total: Int = 0
    var reversed: Bool = false
    var frontText: String = ""
    var backText: String = ""
    var backLabel: String?
    var revealed: Bool = false
    var answerHidden: Bool = false
    var gradesAvailable: Bool = false
    var intervals: [StudyGrade: String] = [:]
    var listenEnabled: Bool = false
    var typePhase: StudyTypePhase = .off
    var typeMissMessage: String?
    var promptLanguage: String?
    var deckId: String = ""
    var authorPubky: String = ""
    var frontImageRef: MediaRef.Image?
    var backImageRef: MediaRef.Image?
    var syncErrorMessage: String?
    var goalReached: Bool = false
    var reviewed: Int = 0
    var errorMessage: String?

    /// What the card is currently showing. Distinct from `revealed`: a typing card can be flipped
    /// while its answer is still withheld, which is exactly the state `answerHidden` describes.
    var answerVisible: Bool { revealed && !answerHidden }
    var progress: Double { total > 0 ? Double(position) / Double(total) : 0 }
}
