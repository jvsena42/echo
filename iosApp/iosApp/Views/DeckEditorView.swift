import SwiftUI

struct EditorCardData: Identifiable {
    let id: String
    let front: String
    let back: String
    let hasImage: Bool
    let hasAudio: Bool
}

/// Pure layout — state comes from the shared `DeckEditorViewModel` via `DeckEditorScreen`.
struct DeckEditorView: View {
    var isNew: Bool = true
    var coverEmoji: String = ""
    var title: String = ""
    var description: String = ""
    var tags: [String] = []
    /// The cards paged in so far — a prefix of the deck, not the deck. See `totalCards`.
    var cards: [EditorCardData] = []
    /// Cards in the whole deck, from the manifest. What the header counts.
    var totalCards: Int = 0
    var isLoadingCards: Bool = false
    var hasMoreCards: Bool = false
    var isSaving: Bool = false
    var titleError: String?
    var descriptionError: String?
    var error: String?

    var onTitleChanged: (String) -> Void = { _ in }
    var onDescriptionChanged: (String) -> Void = { _ in }
    var onAddTag: (String) -> Void = { _ in }
    var onRemoveTag: (String) -> Void = { _ in }
    var onAddCard: () -> Void = {}
    var onCardTap: (String) -> Void = { _ in }
    var onLoadMoreCards: () -> Void = {}
    var onClose: () -> Void = {}
    var onSave: () -> Void = {}

    @State private var showTagSheet = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                // Header
                HStack {
                    Button(action: onClose) {
                        ZStack {
                            Circle()
                                .fill(LoopkyColor.surfaceCard)
                                .frame(width: 40, height: 40)
                            Image(systemName: "xmark")
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundColor(LoopkyColor.foregroundPrimary)
                        }
                    }
                    Spacer()
                    Text(isNew ? "deck_editor_title_new" : "deck_editor_title_edit")
                        .font(.system(size: 18, weight: .heavy))
                        .foregroundColor(LoopkyColor.foregroundPrimary)
                    Spacer()
                    Button(action: onSave) {
                        HStack(spacing: 6) {
                            if isSaving {
                                ProgressView()
                                    .tint(.white)
                                    .scaleEffect(0.7)
                            }
                            Text(isSaving ? "deck_editor_saving" : "deck_editor_save")
                        }
                    }
                    .buttonStyle(.loopkyCompactFilled)
                    .disabled(isSaving)
                }

                // Metadata card
                VStack(alignment: .leading, spacing: 14) {
                    // Cover + Title
                    HStack(spacing: 12) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 14)
                                .fill(LoopkyColor.accentPrimarySoft)
                                .frame(width: 64, height: 64)
                            Text(coverEmoji.isEmpty ? "📚" : coverEmoji)
                                .font(.system(size: 32))
                        }
                        VStack(alignment: .leading, spacing: 6) {
                            Text("deck_editor_label_title")
                                .font(.system(size: 10, weight: .bold))
                                .kerning(0.8)
                                .foregroundColor(LoopkyColor.foregroundMuted)
                            TextField("deck_editor_title_placeholder_untitled", text: binding(title, onTitleChanged))
                                .font(.system(size: 16, weight: .bold))
                                .foregroundColor(LoopkyColor.foregroundPrimary)
                        }
                    }
                    if let titleError {
                        FieldErrorText(message: titleError)
                    }

                    // Description
                    VStack(alignment: .leading, spacing: 6) {
                        Text("deck_editor_label_description")
                            .font(.system(size: 10, weight: .bold))
                            .kerning(0.8)
                            .foregroundColor(LoopkyColor.foregroundMuted)
                        TextField("deck_editor_description_placeholder", text: binding(description, onDescriptionChanged), axis: .vertical)
                            .font(.system(size: 14))
                            .foregroundColor(LoopkyColor.foregroundSecondary)
                    }
                    if let descriptionError {
                        FieldErrorText(message: descriptionError)
                    }

                    // Tags
                    VStack(alignment: .leading, spacing: 8) {
                        Text("deck_editor_label_tags")
                            .font(.system(size: 10, weight: .bold))
                            .kerning(0.8)
                            .foregroundColor(LoopkyColor.foregroundMuted)
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 6) {
                                ForEach(tags, id: \.self) { tag in
                                    TagChipView(tag: tag, onRemove: { onRemoveTag(tag) })
                                }
                                Button("deck_editor_add_tag", action: { showTagSheet = true })
                                    .buttonStyle(LoopkyOutlineButtonStyle(
                                        stroke: LoopkyColor.accentSecondary,
                                        foreground: LoopkyColor.accentSecondary,
                                        cornerRadius: 50,
                                        lineWidth: 1,
                                        fontSize: 13,
                                        fillWidth: false
                                    ))
                            }
                        }
                    }
                }
                .padding(16)
                .background(
                    RoundedRectangle(cornerRadius: 20)
                        .fill(LoopkyColor.surfaceCard)
                )
                .shadow(color: LoopkyColor.shadowElevationLow, radius: 14, x: 0, y: 4)

                if let error {
                    FieldErrorText(message: error)
                }

                // Cards header
                Text(String(format: NSLocalizedString("deck_editor_cards_count", comment: ""), totalCards))
                    .font(.system(size: 16, weight: .heavy))
                    .foregroundColor(LoopkyColor.foregroundPrimary)

                // Card list
                VStack(spacing: 10) {
                    ForEach(cards) { card in
                        Button(action: { onCardTap(card.id) }) {
                            HStack(spacing: 12) {
                                Image(systemName: "line.3.horizontal")
                                    .font(.system(size: 14))
                                    .foregroundColor(LoopkyColor.foregroundMuted)
                                VStack(alignment: .leading, spacing: 2) {
                                    Group {
                                        if card.front.isEmpty {
                                            Text("deck_editor_card_new")
                                        } else {
                                            Text(verbatim: card.front)
                                        }
                                    }
                                    .font(.system(size: 15, weight: .bold))
                                    .foregroundColor(LoopkyColor.foregroundPrimary)
                                    Text(card.back)
                                        .font(.system(size: 13))
                                        .foregroundColor(LoopkyColor.foregroundMuted)
                                }
                                Spacer()
                                HStack(spacing: 6) {
                                    if card.hasImage {
                                        Image(systemName: "photo")
                                            .font(.system(size: 14))
                                            .foregroundColor(LoopkyColor.accentSecondary)
                                    }
                                    if card.hasAudio {
                                        Image(systemName: "mic")
                                            .font(.system(size: 14))
                                            .foregroundColor(LoopkyColor.accentSecondary)
                                    }
                                }
                            }
                            .padding(14)
                            .background(
                                RoundedRectangle(cornerRadius: 14)
                                    .fill(LoopkyColor.surfaceCard)
                            )
                            .shadow(color: LoopkyColor.shadowElevationLow, radius: 8, x: 0, y: 2)
                        }
                        .buttonStyle(.plain)
                    }
                }

                // The tail of the deck arrives a chunk record at a time (#52): a 20k-card deck
                // must not become 20,000 rows the moment this screen opens.
                if isLoadingCards {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("deck_editor_loading_cards")
                            .font(.system(size: 13))
                            .foregroundColor(LoopkyColor.foregroundMuted)
                    }
                    .frame(maxWidth: .infinity)
                } else if hasMoreCards {
                    Button(action: onLoadMoreCards) {
                        Text("deck_editor_load_more_cards")
                    }
                    .buttonStyle(.loopkyOutline)
                }

                // Add card button
                Button(action: onAddCard) {
                    HStack(spacing: 8) {
                        Image(systemName: "plus")
                        Text("deck_editor_add_card")
                    }
                }
                .buttonStyle(.loopkyOutline)
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 40)
        }
        .background(LoopkyColor.surfacePrimary.ignoresSafeArea())
        .navigationBarHidden(true)
        .sheet(isPresented: $showTagSheet) {
            AddTagSheet(tags: tags, onAdd: onAddTag, onRemove: onRemoveTag)
        }
    }

    private func binding(_ value: String, _ onChange: @escaping (String) -> Void) -> Binding<String> {
        Binding(get: { value }, set: { onChange($0) })
    }
}

/// Small red helper text under a form field.
struct FieldErrorText: View {
    let message: String

    var body: some View {
        Text(message)
            .font(.system(size: 12, weight: .medium))
            .foregroundColor(LoopkyColor.srsAgain)
    }
}

#Preview {
    DeckEditorView(
        coverEmoji: "🇪🇸",
        title: "Spanish Basics",
        description: "Core 500 words.",
        tags: ["spanish", "language"],
        cards: [
            EditorCardData(id: "1", front: "el zorro", back: "the fox", hasImage: false, hasAudio: true),
            EditorCardData(id: "2", front: "la casa", back: "the house", hasImage: true, hasAudio: false),
        ]
    )
}
