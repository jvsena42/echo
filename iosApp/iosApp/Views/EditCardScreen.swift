import SwiftUI
import Shared

/// VM-driven wrapper around `EditCardView`.
///
/// A blank `cardId` means "new card": `EditCardViewModel` mints the id and appends on save, which
/// is how `RootView` models the new-card route rather than carrying a separate destination.
struct EditCardScreen: View {
    let deckId: String
    let cardId: String
    var onBack: () -> Void = {}

    @State private var viewModel: EditCardViewModel?
    @State private var uiState: EditCardUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?

    /// Owned locally while typing; the ViewModel is still told about every edit. Binding a field's
    /// `get` to state that round-trips through Kotlin drops characters as you type.
    @State private var front = ""
    @State private var back = ""
    @State private var didSeedFields = false

    var body: some View {
        EditCardView(
            state: viewState,
            front: Binding(
                get: { front },
                set: { front = $0; viewModel?.onFrontTextChanged(text: $0) }
            ),
            back: Binding(
                get: { back },
                set: { back = $0; viewModel?.onBackTextChanged(text: $0) }
            ),
            onCancel: { viewModel?.onCancelClick() },
            onSave: { viewModel?.onSaveClick() },
            onDelete: { viewModel?.onDeleteCard() }
        )
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    private var viewState: EditCardViewState {
        guard let state = uiState else { return EditCardViewState() }
        return EditCardViewState(
            deckTitle: state.deckTitle,
            isNewCard: state.isNewCard,
            cardIndex: Int(state.cardIndex),
            totalCards: Int(state.totalCards),
            isSaving: state.isSaving,
            frontError: FormErrorCopy.message(for: state.frontError),
            backError: FormErrorCopy.message(for: state.backError),
            errorMessage: state.error
        )
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.editCardViewModel(deckId: deckId, cardId: cardId)
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { value in
            guard let state = value as? EditCardUiState else { return }
            uiState = state
            if !didSeedFields {
                didSeedFields = true
                front = state.frontText
                back = state.backText
            }
        }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case is EditCardEffectNavigateBack, is EditCardEffectSaveSuccess, is EditCardEffectDeleted:
                onBack()
            default:
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

struct EditCardViewState {
    var deckTitle: String = ""
    var isNewCard: Bool = false
    var cardIndex: Int = 0
    var totalCards: Int = 0
    var isSaving: Bool = false
    var frontError: String?
    var backError: String?
    var errorMessage: String?
}
