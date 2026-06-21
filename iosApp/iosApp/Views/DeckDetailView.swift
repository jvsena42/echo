import SwiftUI

enum DeckDetailViewState {
    case loading
    case content(DeckDetailContent)
    case error(String)
}

struct DeckDetailContent {
    let title: String
    let description: String?
    let coverEmoji: String
    let coverImageUrl: String?
    let coverImageBase64: String?
    let authorName: String?
    let authorPubky: String
    let authorInitial: String
    let isOwned: Bool
    let tags: [String]
    let totalCards: Int
    let dueCards: Int
    let masteredPercent: String
    let cards: [CardPreviewData]
}

struct CardPreviewData: Identifiable {
    let id: String
    let front: String
    let back: String
}

/// Pure layout — state comes from the shared `DeckDetailViewModel` via `DeckDetailScreen`.
struct DeckDetailView: View {
    var state: DeckDetailViewState = .loading
    var onBack: () -> Void = {}
    var onEdit: () -> Void = {}
    var onDelete: () -> Void = {}
    var onShare: () -> Void = {}
    var onStudy: () -> Void = {}

    var body: some View {
        ZStack(alignment: .bottom) {
            switch state {
            case .loading:
                VStack {
                    header(isOwned: false)
                    Spacer()
                    ProgressView()
                    Spacer()
                }
                .padding(.horizontal, 20)
                .padding(.top, 8)
            case .error(let message):
                VStack(spacing: 12) {
                    header(isOwned: false)
                    Spacer()
                    Text("deck_detail_error_title")
                        .font(.system(size: 20, weight: .heavy))
                        .foregroundColor(EchoColor.foregroundPrimary)
                    Text(message)
                        .font(.system(size: 14))
                        .foregroundColor(EchoColor.foregroundMuted)
                        .multilineTextAlignment(.center)
                    Spacer()
                }
                .padding(.horizontal, 20)
                .padding(.top, 8)
            case .content(let content):
                contentBody(content)
            }
        }
        .background(EchoColor.surfacePrimary.ignoresSafeArea())
        .navigationBarHidden(true)
    }

    @ViewBuilder
    private func contentBody(_ content: DeckDetailContent) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                header(isOwned: content.isOwned)

                // Cover
                coverView(content)
                    .frame(maxWidth: .infinity)
                    .frame(height: content.isOwned ? 120 : 160)
                    .clipShape(RoundedRectangle(cornerRadius: 28))

                // Badge (owned variant)
                if content.isOwned {
                    HStack(spacing: 4) {
                        Image(systemName: "checkmark")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundColor(.white)
                        Text("deck_detail_in_your_library")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(.white)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(Capsule().fill(EchoColor.srsGood))
                }

                // Title + Description
                VStack(alignment: .leading, spacing: 8) {
                    Text(content.title)
                        .font(.system(size: 28, weight: .heavy))
                        .foregroundColor(EchoColor.foregroundPrimary)
                    if let description = content.description, !description.isEmpty {
                        Text(description)
                            .font(.system(size: 14))
                            .foregroundColor(EchoColor.foregroundSecondary)
                            .lineSpacing(4)
                    }
                }

                // Author
                HStack(spacing: 10) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 16)
                            .fill(EchoColor.accentSecondarySoft)
                            .frame(width: 32, height: 32)
                        Text(content.authorInitial)
                            .font(.system(size: 14, weight: .heavy))
                            .foregroundColor(EchoColor.accentSecondary)
                    }
                    VStack(alignment: .leading) {
                        if content.isOwned {
                            Text("@you")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundColor(EchoColor.foregroundPrimary)
                        } else {
                            Text(content.authorName ?? "pk:\(content.authorPubky.prefix(6))")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundColor(EchoColor.foregroundPrimary)
                            Text("pk:\(content.authorPubky.prefix(6))…\(content.authorPubky.suffix(6))")
                                .font(.system(size: 11))
                                .foregroundColor(EchoColor.foregroundMuted)
                        }
                    }
                    Spacer()
                }

                // Tags
                if !content.tags.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(content.tags, id: \.self) { tag in
                                TagChipView(tag: tag)
                            }
                        }
                    }
                }

                // Stats
                StatsBarView(
                    totalCards: content.totalCards,
                    dueCards: content.dueCards,
                    masteredPercent: content.masteredPercent
                )

                // Card list
                VStack(spacing: 8) {
                    ForEach(content.cards) { card in
                        HStack {
                            Text(card.front)
                                .font(.system(size: 15, weight: .bold))
                                .foregroundColor(EchoColor.foregroundPrimary)
                            Spacer()
                            Text(card.back)
                                .font(.system(size: 13))
                                .foregroundColor(EchoColor.foregroundMuted)
                        }
                        .padding(14)
                        .background(
                            RoundedRectangle(cornerRadius: 14)
                                .fill(EchoColor.surfaceCard)
                        )
                        .shadow(color: EchoColor.shadowElevationLow, radius: 8, x: 0, y: 2)
                    }
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 100)
        }

        // Bottom CTA
        Button(action: onStudy) {
            HStack(spacing: 8) {
                Image(systemName: "play.fill")
                Text(studyLabel)
            }
        }
        .buttonStyle(.echoFilled)
        .shadow(color: EchoColor.shadowAccent, radius: 24, x: 0, y: 8)
        .padding(.horizontal, 20)
        .padding(.bottom, 20)
    }

    /// Resolves the cover in priority order: remote URL image → homeserver blob image → emoji box.
    @ViewBuilder
    private func coverView(_ content: DeckDetailContent) -> some View {
        if let urlString = content.coverImageUrl, let url = URL(string: urlString) {
            AsyncImage(url: url) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                coverFallback(content)
            }
        } else if let base64 = content.coverImageBase64,
                  let data = Data(base64Encoded: base64),
                  let uiImage = UIImage(data: data) {
            Image(uiImage: uiImage)
                .resizable()
                .scaledToFill()
        } else {
            coverFallback(content)
        }
    }

    /// Accent-soft box with the cover glyph — shown when a deck has no cover image.
    private func coverFallback(_ content: DeckDetailContent) -> some View {
        ZStack {
            Rectangle()
                .fill(EchoColor.accentPrimarySoft)
            Text(coverGlyph(content))
                .font(.system(size: content.isOwned ? 64 : 80))
        }
    }

    /// The deck's emoji, or its title initial when no emoji is set, falling back to a book glyph.
    private func coverGlyph(_ content: DeckDetailContent) -> String {
        let emoji = content.coverEmoji.trimmingCharacters(in: .whitespacesAndNewlines)
        if !emoji.isEmpty { return emoji }
        if let first = content.title.first { return String(first).uppercased() }
        return "📚"
    }

    private var studyLabel: String {
        if case .content(let content) = state, content.isOwned {
            return String(format: NSLocalizedString("deck_detail_start_studying", comment: ""), content.dueCards)
        }
        return NSLocalizedString("deck_detail_study_this_deck", comment: "")
    }

    private var isOwnedContent: Bool {
        if case .content(let content) = state { return content.isOwned }
        return false
    }

    @ViewBuilder
    private func header(isOwned: Bool) -> some View {
        HStack(spacing: 10) {
            Button(action: onBack) {
                circleIcon(systemName: "chevron.left")
            }
            Spacer()
            if isOwned {
                Button(action: onEdit) {
                    circleIcon(systemName: "pencil")
                }
                Button(action: onDelete) {
                    circleIcon(systemName: "trash", tint: EchoColor.srsAgain)
                }
            }
            Button(action: onShare) {
                circleIcon(systemName: "square.and.arrow.up")
            }
        }
    }

    private func circleIcon(systemName: String, tint: Color = EchoColor.foregroundPrimary) -> some View {
        ZStack {
            Circle()
                .fill(EchoColor.surfaceCard)
                .frame(width: 40, height: 40)
            Image(systemName: systemName)
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(tint)
        }
    }
}

// MARK: - Stats Bar

private struct StatsBarView: View {
    let totalCards: Int
    let dueCards: Int
    let masteredPercent: String

    var body: some View {
        HStack {
            StatColumn(value: "\(totalCards)", label: "component_stats_bar_cards", valueColor: EchoColor.foregroundPrimary)
            Divider().frame(height: 32).overlay(EchoColor.borderSubtle)
            StatColumn(value: "\(dueCards)", label: "component_stats_bar_due", valueColor: EchoColor.accentPrimary)
            Divider().frame(height: 32).overlay(EchoColor.borderSubtle)
            StatColumn(value: masteredPercent, label: "component_stats_bar_mastered", valueColor: EchoColor.srsGood)
        }
        .padding(16)
        .frame(maxWidth: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 20)
                .fill(EchoColor.surfaceSecondary)
        )
    }
}

private struct StatColumn: View {
    let value: String
    let label: LocalizedStringKey
    let valueColor: Color

    var body: some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.system(size: 22, weight: .heavy))
                .foregroundColor(valueColor)
            Text(label)
                .font(.system(size: 11, weight: .medium))
                .foregroundColor(EchoColor.foregroundMuted)
        }
        .frame(maxWidth: .infinity)
    }
}

#Preview("Owned · no cover image") {
    DeckDetailView(
        state: .content(DeckDetailContent(
            title: "Spanish Basics",
            description: "Core 500 words for everyday conversations.",
            coverEmoji: "",
            coverImageUrl: nil,
            coverImageBase64: nil,
            authorName: nil,
            authorPubky: "abc123xyz789",
            authorInitial: "A",
            isOwned: true,
            tags: ["spanish", "language", "beginner"],
            totalCards: 42,
            dueCards: 12,
            masteredPercent: "68%",
            cards: [
                CardPreviewData(id: "1", front: "el zorro", back: "the fox"),
                CardPreviewData(id: "2", front: "la casa", back: "the house"),
            ]
        ))
    )
}

#Preview("Other author · remote cover") {
    DeckDetailView(
        state: .content(DeckDetailContent(
            title: "Spanish Basics",
            description: "Core 500 words for everyday conversations.",
            coverEmoji: "🇪🇸",
            coverImageUrl: "https://images.unsplash.com/photo-1505765050516-f72dcac9c60e",
            coverImageBase64: nil,
            authorName: "Maria Lopez",
            authorPubky: "abc123xyz789",
            authorInitial: "M",
            isOwned: false,
            tags: ["spanish", "language", "beginner"],
            totalCards: 42,
            dueCards: 12,
            masteredPercent: "68%",
            cards: [
                CardPreviewData(id: "1", front: "el zorro", back: "the fox"),
                CardPreviewData(id: "2", front: "la casa", back: "the house"),
            ]
        ))
    )
}
