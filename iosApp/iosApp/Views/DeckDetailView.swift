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
    let author: IdentityData
    let isOwned: Bool
    let tags: [String]
    let totalCards: Int
    /// Pre-formatted by the shared ViewModel — "—" when the review state could not be read.
    let dueLabel: String
    /// Cards never studied. Apart from `dueLabel` because nothing about an unseen card is late.
    let newCards: Int
    let canStudy: Bool
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
                        .foregroundColor(LoopkyColor.foregroundPrimary)
                    Text(message)
                        .font(.system(size: 14))
                        .foregroundColor(LoopkyColor.foregroundMuted)
                        .multilineTextAlignment(.center)
                    Spacer()
                }
                .padding(.horizontal, 20)
                .padding(.top, 8)
            case .content(let content):
                contentBody(content)
            }
        }
        .background(LoopkyColor.surfacePrimary.ignoresSafeArea())
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
                    .background(Capsule().fill(LoopkyColor.srsGood))
                }

                // Title + Description
                VStack(alignment: .leading, spacing: 8) {
                    Text(content.title)
                        .font(.system(size: 28, weight: .heavy))
                        .foregroundColor(LoopkyColor.foregroundPrimary)
                    if let description = content.description, !description.isEmpty {
                        Text(description)
                            .font(.system(size: 14))
                            .foregroundColor(LoopkyColor.foregroundSecondary)
                            .lineSpacing(4)
                    }
                }

                // Author
                HStack(spacing: 10) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 16)
                            .fill(LoopkyColor.accentSecondarySoft)
                            .frame(width: 32, height: 32)
                        Text(content.author.initial)
                            .font(.system(size: 14, weight: .heavy))
                            .foregroundColor(LoopkyColor.accentSecondary)
                    }
                    VStack(alignment: .leading) {
                        HStack(spacing: 6) {
                            Text(content.author.label)
                                .font(.system(size: 13, weight: .bold))
                                .foregroundColor(LoopkyColor.foregroundPrimary)
                                .lineLimit(1)
                            if content.isOwned {
                                YouBadge()
                            }
                        }
                        Text(content.author.truncatedPubky)
                            .font(.system(size: 11))
                            .foregroundColor(LoopkyColor.foregroundMuted)
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
                    dueLabel: content.dueLabel,
                    newCards: content.newCards,
                    masteredPercent: content.masteredPercent
                )

                // Cards. Shown for decks you don't own too — being able to look through the
                // cards before studying is what makes a shared deck worth opening.
                cardsHeading(count: content.cards.count)

                if content.cards.isEmpty {
                    Text(content.isOwned
                        ? "deck_detail_cards_empty_owned"
                        : "deck_detail_cards_empty_foreign")
                        .font(.system(size: 14))
                        .foregroundColor(LoopkyColor.foregroundMuted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    LazyVStack(spacing: 8) {
                        ForEach(content.cards) { card in
                            HStack {
                                Text(card.front)
                                    .font(.system(size: 15, weight: .bold))
                                    .foregroundColor(LoopkyColor.foregroundPrimary)
                                Spacer()
                                Text(card.back)
                                    .font(.system(size: 13))
                                    .foregroundColor(LoopkyColor.foregroundMuted)
                            }
                            .padding(14)
                            .background(
                                RoundedRectangle(cornerRadius: 14)
                                    .fill(LoopkyColor.surfaceCard)
                            )
                            .shadow(color: LoopkyColor.shadowElevationLow, radius: 8, x: 0, y: 2)
                        }
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
        .buttonStyle(.loopkyFilled)
        .disabled(!canStudy)
        .shadow(color: LoopkyColor.shadowAccent, radius: 24, x: 0, y: 8)
        .padding(.horizontal, 20)
        .padding(.bottom, 20)
    }

    private func cardsHeading(count: Int) -> some View {
        HStack {
            Text("deck_detail_cards_heading")
                .font(.system(size: 18, weight: .heavy))
                .foregroundColor(LoopkyColor.foregroundPrimary)
            Spacer()
            Text("\(count)")
                .font(.system(size: 14, weight: .bold))
                .foregroundColor(LoopkyColor.foregroundMuted)
        }
        .padding(.top, 4)
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
                .fill(LoopkyColor.accentPrimarySoft)
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

    /// Reviews take precedence; with none, the count that matters is the unseen one, so a freshly
    /// imported deck says how much is waiting instead of reading "0 due".
    private var studyLabel: String {
        guard case .content(let content) = state, content.isOwned else {
            return NSLocalizedString("deck_detail_study_this_deck", comment: "")
        }
        if content.dueLabel == "0" && content.newCards > 0 {
            return String(format: NSLocalizedString("deck_detail_start_studying_new", comment: ""), content.newCards)
        }
        return String(format: NSLocalizedString("deck_detail_start_studying", comment: ""), content.dueLabel)
    }

    /// False when there is neither a review nor an unseen card — Study would land on "All done!".
    private var canStudy: Bool {
        if case .content(let content) = state { return content.canStudy }
        return true
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
                    circleIcon(systemName: "trash", tint: LoopkyColor.srsAgain)
                }
            }
            Button(action: onShare) {
                circleIcon(systemName: "square.and.arrow.up")
            }
        }
    }

    private func circleIcon(systemName: String, tint: Color = LoopkyColor.foregroundPrimary) -> some View {
        ZStack {
            Circle()
                .fill(LoopkyColor.surfaceCard)
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
    let dueLabel: String
    let newCards: Int
    let masteredPercent: String

    var body: some View {
        HStack {
            StatColumn(value: "\(totalCards)", label: "component_stats_bar_cards", valueColor: LoopkyColor.foregroundPrimary)
            Divider().frame(height: 32).overlay(LoopkyColor.borderSubtle)
            StatColumn(value: dueLabel, label: "component_stats_bar_due", valueColor: LoopkyColor.accentPrimary)
            Divider().frame(height: 32).overlay(LoopkyColor.borderSubtle)
            StatColumn(value: "\(newCards)", label: "component_stats_bar_new", valueColor: LoopkyColor.foregroundPrimary)
            Divider().frame(height: 32).overlay(LoopkyColor.borderSubtle)
            StatColumn(value: masteredPercent, label: "component_stats_bar_mastered", valueColor: LoopkyColor.srsGood)
        }
        .padding(16)
        .frame(maxWidth: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 20)
                .fill(LoopkyColor.surfaceSecondary)
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
                .foregroundColor(LoopkyColor.foregroundMuted)
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
            author: IdentityData(pubky: "abc123xyz789", displayName: "Cosmic-Crystal-Panda"),
            isOwned: true,
            tags: ["spanish", "language", "beginner"],
            totalCards: 42,
            dueLabel: "12",
            newCards: 8,
            canStudy: true,
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
            author: IdentityData(pubky: "abc123xyz789", displayName: "Maria Lopez"),
            isOwned: false,
            tags: ["spanish", "language", "beginner"],
            totalCards: 42,
            dueLabel: "12",
            newCards: 8,
            canStudy: true,
            masteredPercent: "68%",
            cards: [
                CardPreviewData(id: "1", front: "el zorro", back: "the fox"),
                CardPreviewData(id: "2", front: "la casa", back: "the house"),
            ]
        ))
    )
}
