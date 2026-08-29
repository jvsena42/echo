import SwiftUI
import Shared

/// Publish step of Paste-to-Import. Pure layout; `PublishDeckScreen` owns the ViewModel.
struct PublishDeckView: View {
    var state: PublishViewState = PublishViewState()
    @Binding var title: String
    @Binding var description: String
    var onBack: () -> Void = {}
    var onPublish: () -> Void = {}
    var onCancelPublish: () -> Void = {}
    var onUndo: () -> Void = {}
    var onDone: () -> Void = {}
    var onAddTag: (String) -> Void = { _ in }
    var onRemoveTag: (String) -> Void = { _ in }
    var options: DeckStudyOptions
    var onCoverSelected: (ImageSelection) -> Void = { _ in }
    var onShareConfirm: () -> Void = {}
    var onShareDismiss: () -> Void = {}
    var onShareNeverAsk: () -> Void = {}

    @State private var isAddingTag = false
    @State private var pickingCover = false

    var body: some View {
        Group {
            if state.isPublished {
                publishedState
            } else {
                form
            }
        }
        .background(LoopkyColor.surfacePrimary.ignoresSafeArea())
        .navigationBarHidden(true)
        .sheet(isPresented: $pickingCover) {
            ImagePickerSheet(
                title: "publish_cover_label",
                subtitle: nil,
                onRemove: nil,
                onSelected: onCoverSelected,
                onClose: { pickingCover = false }
            )
        }
        .sheet(isPresented: $isAddingTag) {
            AddTagSheet(
                tags: state.tags,
                onAdd: { onAddTag($0); isAddingTag = false },
                onRemove: onRemoveTag
            )
        }
        // Announcing is opt-in per action: off means never asked and never posted.
        .confirmationDialog(
            Text("share_prompt_title"),
            isPresented: Binding(get: { state.sharePromptPreview != nil }, set: { if !$0 { onShareDismiss() } }),
            titleVisibility: .visible
        ) {
            Button("share_prompt_confirm", action: onShareConfirm).disabled(state.isSharing)
            Button("share_prompt_never", role: .destructive, action: onShareNeverAsk)
            Button("share_prompt_dismiss", role: .cancel, action: onShareDismiss)
        } message: {
            if let preview = state.sharePromptPreview { Text(preview) }
        }
    }

    private var form: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                header
                cardsReady
                field("publish_title_label", placeholder: "publish_title_placeholder",
                      text: $title, error: state.titleError)
                field("publish_description_label", placeholder: "publish_description_placeholder",
                      text: $description, error: state.descriptionError, axis: .vertical)
                tagsSection
                options
                if let errorMessage = state.errorMessage { errorRow(errorMessage) }
                if state.isPublishing { progressBlock }
                publicNotice
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 40)
        }
    }

    private var header: some View {
        HStack {
            Button(action: onBack) {
                Text("publish_back")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(LoopkyColor.accentPrimary)
            }
            .disabled(state.isPublishing)
            Spacer()
            Text("publish_title")
                .font(.system(size: 18, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Spacer()
            Button("publish_button", action: onPublish)
                .buttonStyle(LoopkyCompactFilledButtonStyle(
                    fill: state.canPublish ? LoopkyColor.accentPrimary : LoopkyColor.borderSubtle,
                    foreground: state.canPublish ? .white : LoopkyColor.foregroundMuted
                ))
                .disabled(!state.canPublish)
        }
    }

    private var cardsReady: some View {
        HStack(spacing: 12) {
            Button { pickingCover = true } label: {
                ZStack {
                    RoundedRectangle(cornerRadius: 12).fill(LoopkyColor.accentPrimarySoft)
                    if hasCover {
                        CardMediaImage(
                            ref: coverRef,
                            pendingBytes: state.coverPendingBytes,
                            authorPubky: "",
                            deckId: "",
                            contentMode: .fill
                        )
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                    } else {
                        Text(state.coverEmoji.isEmpty ? "📚" : state.coverEmoji).font(.system(size: 24))
                    }
                }
                .frame(width: 52, height: 52)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("publish_cover_change"))
            .accessibilityIdentifier("publish_cover_change")

            Text(String(format: NSLocalizedString("publish_cards_ready", comment: ""), state.cardCount))
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(LoopkyColor.foregroundSecondary)
            Spacer()
        }
    }

    private var hasCover: Bool { state.coverImageUrl != nil || state.coverPendingBytes != nil }

    private var coverRef: MediaRef.Image? {
        state.coverImageUrl.map {
            MediaRef.Image(path: "", mime: "", sha256: "", width: nil, height: nil, uri: nil, url: $0)
        }
    }

    private func field(
        _ label: LocalizedStringKey,
        placeholder: LocalizedStringKey,
        text: Binding<String>,
        error: String?,
        axis: Axis = .horizontal
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(.system(size: 10, weight: .bold))
                .kerning(0.8)
                .foregroundStyle(LoopkyColor.foregroundMuted)
            TextField(placeholder, text: text, axis: axis)
                .font(.system(size: 15))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
                .lineLimit(axis == .vertical ? 2...5 : 1...1)
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

    private var tagsSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("publish_tags_label")
                .font(.system(size: 10, weight: .bold))
                .kerning(0.8)
                .foregroundStyle(LoopkyColor.foregroundMuted)
            FlowTags(tags: state.tags, onRemove: onRemoveTag) { isAddingTag = true }
        }
    }

    private var progressBlock: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let progress = state.publishProgress {
                ProgressView(value: progress).tint(LoopkyColor.accentPrimary)
                Text(String(
                    format: NSLocalizedString("publish_progress_count", comment: ""),
                    state.publishedCardCount, state.cardCount
                ))
                .font(.system(size: 12))
                .foregroundStyle(LoopkyColor.foregroundMuted)
            } else {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("publish_publishing")
                        .font(.system(size: 13))
                        .foregroundStyle(LoopkyColor.foregroundMuted)
                }
            }
            Button("publish_cancel", action: onCancelPublish)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(LoopkyColor.danger)
                .disabled(state.isCancelling)
        }
    }

    private var publishedState: some View {
        VStack(spacing: 16) {
            Spacer()
            Text("🎉")
                .font(.system(size: 56))
                .accessibilityLabel(Text("publish_published_icon_desc"))
            Text("publish_published_title")
                .font(.system(size: 22, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Text("publish_published_subtitle")
                .font(.system(size: 14))
                .foregroundStyle(LoopkyColor.foregroundMuted)
                .multilineTextAlignment(.center)
            if let errorMessage = state.errorMessage { errorRow(errorMessage) }
            Spacer()
            VStack(spacing: 10) {
                Button("publish_done", action: onDone).buttonStyle(.loopkyFilled)
                if state.undoSecondsRemaining > 0 {
                    Button(String(
                        format: NSLocalizedString("publish_undo", comment: ""),
                        state.undoSecondsRemaining
                    ), action: onUndo)
                    .buttonStyle(.loopkyOutline)
                }
            }
        }
        .padding(24)
    }

    private func errorRow(_ message: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "exclamationmark.circle.fill").font(.system(size: 13))
            Text(message).font(.system(size: 13, weight: .medium))
            Spacer(minLength: 0)
        }
        .foregroundStyle(LoopkyColor.danger)
    }

    private var publicNotice: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("publish_public_title")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(LoopkyColor.accentSecondary)
            Text("publish_public_subtitle")
                .font(.system(size: 12))
                .foregroundStyle(LoopkyColor.foregroundMuted)
        }
    }
}

/// Tag chips that wrap, with a trailing "add" chip.
private struct FlowTags: View {
    let tags: [String]
    let onRemove: (String) -> Void
    let onAdd: () -> Void

    var body: some View {
        ViewThatFits(in: .horizontal) {
            row
            ScrollView(.horizontal, showsIndicators: false) { row }
        }
    }

    private var row: some View {
        HStack(spacing: 8) {
            ForEach(tags, id: \.self) { tag in
                TagChipView(tag: tag, onRemove: { onRemove(tag) })
            }
            Button(action: onAdd) {
                HStack(spacing: 4) {
                    Image(systemName: "plus").font(.system(size: 10, weight: .bold))
                    Text("publish_add_tag_button").font(.system(size: 12, weight: .semibold))
                }
                .foregroundStyle(LoopkyColor.accentPrimary)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(Capsule().fill(LoopkyColor.accentPrimarySoft))
            }
        }
    }
}
