import SwiftUI
import Shared

/// VM-driven wrapper around the presentational `PasteView`.
///
/// The parsing all happens in `PasteImportViewModel` over the shared paste parser — the rules in
/// spec §6 and the edge cases in §9, which `commonTest` covers. The view used to do its own
/// `contains(" — ")` detection, which agreed with the parser only by accident and told the user a
/// separator the import would not actually use.
struct PasteScreen: View {
    var onCancel: () -> Void = {}
    var onNext: () -> Void = {}

    @State private var viewModel: PasteImportViewModel?
    @State private var uiState: PasteImportUiState?

    /// The editor's text is owned here, not read back from `uiState`.
    ///
    /// Binding the field's `get` to `uiState.rawText` sends every keystroke through the ViewModel
    /// and waits for it to come back on another dispatch: the round trip races the next keystroke
    /// and the field loses characters. Typing "hola, hello" landed as "l". The ViewModel is still
    /// the parser's input — `onTextChanged` is called on every edit — it just is not the field's
    /// source of truth while the user is typing.
    @State private var draft = ""

    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?

    var body: some View {
        PasteView(
            state: viewState,
            text: Binding(
                get: { draft },
                set: { newValue in
                    draft = newValue
                    viewModel?.onTextChanged(text: newValue)
                }
            ),
            onCancel: { viewModel?.onCancelClick() },
            onNext: { viewModel?.onNextClick() },
            onSeparatorPicked: { viewModel?.onSeparatorOverride(separator: $0) }
        )
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    private var viewState: PasteViewState {
        guard let state = uiState else { return PasteViewState() }
        return PasteViewState(
            isParsed: state.isParsed,
            cardCount: Int(state.cardCount),
            incompleteCardCount: Int(state.incompleteCardCount),
            separatorLabel: KotlinInterop.separatorLabel(state.detectedSeparator),
            activeSeparator: state.separatorOverride ?? state.detectedSeparator,
            previewCards: state.previewCards.map { PastePreviewCard(front: $0.front, back: $0.back) },
            hasPreviewableCard: state.hasPreviewableCard,
            noPatternDetected: state.noPatternDetected,
            hasIncompleteCards: state.hasIncompleteCards,
            errorMessage: state.error
        )
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.pasteImportViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? PasteImportUiState }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case is PasteImportEffectNavigatePublish: onNext()
            case is PasteImportEffectNavigateBack: onCancel()
            default: break
            }
        }
    }

    private func detach() {
        viewModel?.release()
        viewModel = nil
        stateSink = nil
        effectSink = nil
    }
}

/// What `PasteView` draws. Mapped from `PasteImportUiState` so the view stays free of `Shared`
/// types apart from `Separator`, which the picker has to hand back verbatim.
struct PasteViewState {
    var isParsed: Bool = false
    var cardCount: Int = 0
    var incompleteCardCount: Int = 0
    var separatorLabel: String = ""
    var activeSeparator: Separator?
    var previewCards: [PastePreviewCard] = []
    var hasPreviewableCard: Bool = false
    var noPatternDetected: Bool = false
    var hasIncompleteCards: Bool = false
    var errorMessage: String?

    /// Next is live only once the parser found a card with both sides — the same gate as Android.
    var canAdvance: Bool { isParsed && hasPreviewableCard }
}

struct PastePreviewCard: Identifiable {
    let front: String
    let back: String
    var id: String { "\(front)\u{1F}\(back)" }
}
