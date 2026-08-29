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
            onDelete: { viewModel?.onDeleteCard() },
            onFrontImage: { apply($0, toFront: true) },
            onBackImage: { apply($0, toFront: false) },
            onRemoveFrontImage: { viewModel?.onRemoveFrontImage() },
            onRemoveBackImage: { viewModel?.onRemoveBackImage() }
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
            deckId: state.deckId,
            authorPubky: state.authorPubky,
            frontImageRef: state.frontImageRef,
            backImageRef: state.backImageRef,
            frontPendingBytes: state.frontPendingBytes?.toData(),
            backPendingBytes: state.backPendingBytes?.toData(),
            frontError: FormErrorCopy.message(for: state.frontError),
            backError: FormErrorCopy.message(for: state.backError),
            errorMessage: state.error
        )
    }

    /// A web pick is stored as a URL; gallery bytes are handed to the ViewModel, which runs them
    /// through the shared `MediaProcessor` before upload.
    private func apply(_ selection: ImageSelection, toFront: Bool) {
        switch selection {
        case .web(let url):
            if toFront {
                viewModel?.onFrontImageWebSelected(url: url)
            } else {
                viewModel?.onBackImageWebSelected(url: url)
            }
        case .gallery(let bytes, let mime):
            let kotlinBytes = bytes.toKotlinByteArray()
            if toFront {
                viewModel?.onFrontImageGallerySelected(bytes: kotlinBytes, mime: mime)
            } else {
                viewModel?.onBackImageGallerySelected(bytes: kotlinBytes, mime: mime)
            }
        }
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.editCardViewModel(deckId: deckId, cardId: cardId)
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { value in
            guard let state = value as? EditCardUiState else { return }
            uiState = state
            // Seed once the card has actually loaded, not on the first emission.
            //
            // The ViewModel publishes its default state before the fetch returns, so seeding on
            // "first value seen" copied empty strings into the fields and then never corrected
            // them — the card's text vanished while its picture rendered fine. A non-empty
            // `deckId` is the VM's own signal that the load has landed.
            if !didSeedFields && !state.deckId.isEmpty {
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
        viewModel?.release()
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
    var deckId: String = ""
    var authorPubky: String = ""
    var frontImageRef: MediaRef.Image?
    var backImageRef: MediaRef.Image?
    var frontPendingBytes: Data?
    var backPendingBytes: Data?
    var frontError: String?
    var backError: String?
    var errorMessage: String?
}
