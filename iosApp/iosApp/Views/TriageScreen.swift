import SwiftUI
import Shared

/// Review each imported card before it is published — spec §5.5.
///
/// One card at a time, keep or discard, with an undo for the decision just made. The same screen
/// sits between both import sources and the publish step.
struct TriageScreen: View {
    var onBack: () -> Void = {}
    var onPublish: () -> Void = {}

    @State private var viewModel: TriageViewModel?
    @State private var uiState: TriageUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?

    var body: some View {
        TriageView(
            state: viewState,
            onKeep: { viewModel?.onKeep() },
            onDiscard: { viewModel?.onDiscard() },
            onUndo: { viewModel?.onUndo() },
            onApproveAll: { viewModel?.onApproveAll() },
            onBack: { viewModel?.onBackClick() }
        )
        .onAppear {
            attach()
            // The draft can change while this screen is backgrounded — a card edited and saved
            // comes back here, so the queue is re-read rather than trusted.
            viewModel?.refresh()
        }
        .onDisappear { detach() }
    }

    private var viewState: TriageViewState {
        guard let state = uiState else { return TriageViewState() }
        return TriageViewState(
            front: state.currentCard?.front ?? "",
            back: state.currentCard?.back ?? "",
            position: Int(state.currentIndex) + 1,
            total: Int(state.total),
            keptCount: Int(state.keptCount),
            discardedCount: Int(state.discardedCount),
            canUndo: state.canUndo,
            hasCard: state.currentCard != nil,
            errorMessage: state.error
        )
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.triageViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? TriageUiState }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case is TriageEffectNavigatePublish: onPublish()
            case is TriageEffectNavigateBack: onBack()
            default:
                // `NavigateEditCard` has no iOS destination yet — the triage card editor is a
                // separate screen still to be built, so the row stays keep-or-discard.
                break
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

struct TriageViewState {
    var front: String = ""
    var back: String = ""
    var position: Int = 0
    var total: Int = 0
    var keptCount: Int = 0
    var discardedCount: Int = 0
    var canUndo: Bool = false
    var hasCard: Bool = false
    var errorMessage: String?
}
