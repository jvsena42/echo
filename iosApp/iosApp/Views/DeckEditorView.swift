import SwiftUI

struct DeckEditorView: View {
    var deckId: String? = nil
    var onBack: () -> Void = {}
    var onSaved: (String) -> Void = { _ in }

    // Static preview data until VM is wired via SKIE
    @State private var title = "Spanish Basics"
    @State private var description = "Core 500 words for everyday conversations."
    @State private var coverEmoji = "🇪🇸"
    @State private var tags = ["spanish", "language"]
    private let isNew: Bool = true
    private let cards: [PreviewCard] = [
        PreviewCard(id: "1", front: "el zorro", back: "the fox", hasImage: false, hasAudio: true),
        PreviewCard(id: "2", front: "la casa", back: "the house", hasImage: true, hasAudio: false),
        PreviewCard(id: "3", front: "el agua", back: "the water", hasImage: false, hasAudio: false),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                // Header
                HStack {
                    Button(action: onBack) {
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
                    Button(action: {}) {
                        Text("Save")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(.white)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 8)
                            .background(Capsule().fill(EchoColor.accentPrimary))
                    }
                }

                // Metadata card
                VStack(alignment: .leading, spacing: 14) {
                    // Cover + Title
                    HStack(spacing: 12) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 14)
                                .fill(EchoColor.accentPrimarySoft)
                                .frame(width: 64, height: 64)
                            Text(coverEmoji)
                                .font(.system(size: 32))
                        }
                        VStack(alignment: .leading, spacing: 6) {
                            Text("DECK TITLE")
                                .font(.system(size: 10, weight: .bold))
                                .kerning(0.8)
                                .foregroundColor(EchoColor.foregroundMuted)
                            Text(title.isEmpty ? "Untitled deck" : title)
                                .font(.system(size: 16, weight: .bold))
                                .foregroundColor(title.isEmpty ? EchoColor.foregroundMuted : EchoColor.foregroundPrimary)
                        }
                    }

                    // Description
                    VStack(alignment: .leading, spacing: 6) {
                        Text("DESCRIPTION")
                            .font(.system(size: 10, weight: .bold))
                            .kerning(0.8)
                            .foregroundColor(EchoColor.foregroundMuted)
                        Text(description.isEmpty ? "Add a description..." : description)
                            .font(.system(size: 14))
                            .foregroundColor(description.isEmpty ? EchoColor.foregroundMuted : EchoColor.foregroundSecondary)
                    }

                    // Tags
                    VStack(alignment: .leading, spacing: 8) {
                        Text("TAGS (PUBKY)")
                            .font(.system(size: 10, weight: .bold))
                            .kerning(0.8)
                            .foregroundColor(EchoColor.foregroundMuted)
                        HStack(spacing: 6) {
                            ForEach(tags, id: \.self) { tag in
                                TagChipView(tag: tag, onRemove: {
                                    tags.removeAll { $0 == tag }
                                })
                            }
                            Text("+ Add")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundColor(EchoColor.accentSecondary)
                                .padding(.horizontal, 14)
                                .padding(.vertical, 8)
                                .overlay(
                                    Capsule().stroke(EchoColor.accentSecondary, lineWidth: 1)
                                )
                        }
                    }
                }
                .padding(16)
                .background(
                    RoundedRectangle(cornerRadius: 20)
                        .fill(EchoColor.surfaceCard)
                )
                .shadow(color: .black.opacity(0.05), radius: 14, x: 0, y: 4)

                // Cards header
                Text("Cards (\(cards.count))")
                    .font(.system(size: 16, weight: .heavy))
                    .foregroundColor(EchoColor.foregroundPrimary)

                // Card list
                VStack(spacing: 10) {
                    ForEach(cards) { card in
                        HStack(spacing: 12) {
                            Image(systemName: "line.3.horizontal")
                                .font(.system(size: 14))
                                .foregroundColor(EchoColor.foregroundMuted)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(card.front)
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
                        .shadow(color: .black.opacity(0.05), radius: 8, x: 0, y: 2)
                    }
                }

                // Add card button
                Button(action: {}) {
                    HStack(spacing: 8) {
                        Image(systemName: "plus")
                            .font(.system(size: 16, weight: .semibold))
                        Text("Add card")
                            .font(.system(size: 15, weight: .bold))
                    }
                    .foregroundColor(EchoColor.accentPrimary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .overlay(
                        RoundedRectangle(cornerRadius: 14)
                            .stroke(EchoColor.accentPrimary, lineWidth: 1.5)
                    )
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 40)
        }
        .background(EchoColor.surfacePrimary.ignoresSafeArea())
        .navigationBarHidden(true)
    }
}

private struct PreviewCard: Identifiable {
    let id: String
    let front: String
    let back: String
    let hasImage: Bool
    let hasAudio: Bool
}

#Preview {
    DeckEditorView()
}
