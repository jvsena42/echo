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
    var cards: [EditorCardData] = []
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
                                .fill(EchoColor.surfaceCard)
                                .frame(width: 40, height: 40)
                            Image(systemName: "xmark")
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundColor(EchoColor.foregroundPrimary)
                        }
                    }
                    Spacer()
                    Text(isNew ? "New Deck" : "Edit Deck")
                        .font(.system(size: 18, weight: .heavy))
                        .foregroundColor(EchoColor.foregroundPrimary)
                    Spacer()
                    Button(action: onSave) {
                        HStack(spacing: 6) {
                            if isSaving {
                                ProgressView()
                                    .tint(.white)
                                    .scaleEffect(0.7)
                            }
                            Text(isSaving ? "Saving…" : "Save")
                        }
                    }
                    .buttonStyle(.echoCompactFilled)
                    .disabled(isSaving)
                }

                // Metadata card
                VStack(alignment: .leading, spacing: 14) {
                    // Cover + Title
                    HStack(spacing: 12) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 14)
                                .fill(EchoColor.accentPrimarySoft)
                                .frame(width: 64, height: 64)
                            Text(coverEmoji.isEmpty ? "📚" : coverEmoji)
                                .font(.system(size: 32))
                        }
                        VStack(alignment: .leading, spacing: 6) {
                            Text("DECK TITLE")
                                .font(.system(size: 10, weight: .bold))
                                .kerning(0.8)
                                .foregroundColor(EchoColor.foregroundMuted)
                            TextField("Untitled deck", text: binding(title, onTitleChanged))
                                .font(.system(size: 16, weight: .bold))
                                .foregroundColor(EchoColor.foregroundPrimary)
                        }
                    }
                    if let titleError {
                        FieldErrorText(message: titleError)
                    }

                    // Description
                    VStack(alignment: .leading, spacing: 6) {
                        Text("DESCRIPTION")
                            .font(.system(size: 10, weight: .bold))
                            .kerning(0.8)
                            .foregroundColor(EchoColor.foregroundMuted)
                        TextField("Add a description...", text: binding(description, onDescriptionChanged), axis: .vertical)
                            .font(.system(size: 14))
                            .foregroundColor(EchoColor.foregroundSecondary)
                    }
                    if let descriptionError {
                        FieldErrorText(message: descriptionError)
                    }

                    // Tags
                    VStack(alignment: .leading, spacing: 8) {
                        Text("TAGS (PUBKY)")
                            .font(.system(size: 10, weight: .bold))
                            .kerning(0.8)
                            .foregroundColor(EchoColor.foregroundMuted)
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 6) {
                                ForEach(tags, id: \.self) { tag in
                                    TagChipView(tag: tag, onRemove: { onRemoveTag(tag) })
                                }
                                Button("+ Add", action: { showTagSheet = true })
                                    .buttonStyle(EchoOutlineButtonStyle(
                                        stroke: EchoColor.accentSecondary,
                                        foreground: EchoColor.accentSecondary,
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
                        .fill(EchoColor.surfaceCard)
                )
                .shadow(color: EchoColor.shadowElevationLow, radius: 14, x: 0, y: 4)

                if let error {
                    FieldErrorText(message: error)
                }

                // Cards header
                Text("Cards (\(cards.count))")
                    .font(.system(size: 16, weight: .heavy))
                    .foregroundColor(EchoColor.foregroundPrimary)

                // Card list
                VStack(spacing: 10) {
                    ForEach(cards) { card in
                        Button(action: { onCardTap(card.id) }) {
                            HStack(spacing: 12) {
                                Image(systemName: "line.3.horizontal")
                                    .font(.system(size: 14))
                                    .foregroundColor(EchoColor.foregroundMuted)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(card.front.isEmpty ? "New card" : card.front)
                                        .font(.system(size: 15, weight: .bold))
                                        .foregroundColor(EchoColor.foregroundPrimary)
                                    Text(card.back)
                                        .font(.system(size: 13))
                                        .foregroundColor(EchoColor.foregroundMuted)
                                }
                                Spacer()
                                HStack(spacing: 6) {
                                    if card.hasImage {
                                        Image(systemName: "photo")
                                            .font(.system(size: 14))
                                            .foregroundColor(EchoColor.accentSecondary)
                                    }
                                    if card.hasAudio {
                                        Image(systemName: "mic")
                                            .font(.system(size: 14))
                                            .foregroundColor(EchoColor.accentSecondary)
                                    }
                                }
                            }
                            .padding(14)
                            .background(
                                RoundedRectangle(cornerRadius: 14)
                                    .fill(EchoColor.surfaceCard)
                            )
                            .shadow(color: EchoColor.shadowElevationLow, radius: 8, x: 0, y: 2)
                        }
                        .buttonStyle(.plain)
                    }
                }

                // Add card button
                Button(action: onAddCard) {
                    HStack(spacing: 8) {
                        Image(systemName: "plus")
                        Text("Add card")
                    }
                }
                .buttonStyle(.echoOutline)
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 40)
        }
        .background(EchoColor.surfacePrimary.ignoresSafeArea())
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
            .foregroundColor(EchoColor.srsAgain)
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
