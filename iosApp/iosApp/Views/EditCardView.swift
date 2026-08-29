import SwiftUI

/// Edit one card's two sides. Pure layout; `EditCardScreen` owns the ViewModel.
///
/// Image and audio attachment are deliberately absent rather than present-and-inert: they were
/// `action: {}` buttons before, which look like a working feature. They need `MediaProcessor`,
/// which has no iOS binding yet — see #113.
struct EditCardView: View {
    var state: EditCardViewState = EditCardViewState()
    @Binding var front: String
    @Binding var back: String
    var onCancel: () -> Void = {}
    var onSave: () -> Void = {}
    var onDelete: () -> Void = {}

    @State private var isConfirmingDelete = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                header
                context
                side("edit_card_label_front", placeholder: "edit_card_front_placeholder",
                     text: $front, error: state.frontError)
                side("edit_card_label_back", placeholder: "edit_card_back_placeholder",
                     text: $back, error: state.backError)
                if let errorMessage = state.errorMessage { errorRow(errorMessage) }
                if !state.isNewCard { deleteButton }
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 40)
        }
        .background(LoopkyColor.surfacePrimary.ignoresSafeArea())
        .navigationBarHidden(true)
        .confirmationDialog(
            Text("edit_card_delete"),
            isPresented: $isConfirmingDelete,
            titleVisibility: .visible
        ) {
            Button("edit_card_delete", role: .destructive, action: onDelete)
            Button("edit_card_cancel", role: .cancel) {}
        }
    }

    private var header: some View {
        HStack {
            Button(action: onCancel) {
                Text("edit_card_cancel")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(LoopkyColor.accentPrimary)
            }
            Spacer()
            Text(state.isNewCard ? "edit_card_new_title" : "edit_card_title")
                .font(.system(size: 18, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Spacer()
            if state.isSaving {
                ProgressView().controlSize(.small)
            } else {
                Button("edit_card_save", action: onSave)
                    .buttonStyle(LoopkyCompactFilledButtonStyle(
                        fill: LoopkyColor.accentPrimary,
                        foreground: .white
                    ))
            }
        }
    }

    @ViewBuilder
    private var context: some View {
        if !state.deckTitle.isEmpty {
            // "Card %1$lld of %2$lld · %3$@" — the two counts come first, the deck title last.
            Text(String(
                format: NSLocalizedString("edit_card_context", comment: ""),
                state.cardIndex, state.totalCards, state.deckTitle
            ))
            .font(.system(size: 12, weight: .medium))
            .foregroundStyle(LoopkyColor.foregroundMuted)
        }
    }

    private func side(
        _ label: LocalizedStringKey,
        placeholder: LocalizedStringKey,
        text: Binding<String>,
        error: String?
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(.system(size: 10, weight: .bold))
                .kerning(0.8)
                .foregroundStyle(LoopkyColor.foregroundMuted)
            TextField(placeholder, text: text, axis: .vertical)
                .font(.system(size: 16))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
                .lineLimit(2...6)
                .padding(14)
                .background(RoundedRectangle(cornerRadius: 14).fill(LoopkyColor.surfaceCard))
                .overlay(
                    RoundedRectangle(cornerRadius: 14)
                        .stroke(error == nil ? LoopkyColor.borderSubtle : LoopkyColor.danger, lineWidth: 1)
                )
            if let error {
                Text(error).font(.system(size: 12)).foregroundStyle(LoopkyColor.danger)
            }
        }
    }

    private var deleteButton: some View {
        Button { isConfirmingDelete = true } label: {
            HStack(spacing: 8) {
                Image(systemName: "trash")
                Text("edit_card_delete")
            }
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(LoopkyColor.danger)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .background(RoundedRectangle(cornerRadius: 14).fill(LoopkyColor.dangerSoft))
        }
    }

    private func errorRow(_ message: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "exclamationmark.circle.fill").font(.system(size: 13))
            Text(message).font(.system(size: 13, weight: .medium))
            Spacer(minLength: 0)
        }
        .foregroundStyle(LoopkyColor.danger)
    }
}
