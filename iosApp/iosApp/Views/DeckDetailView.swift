import SwiftUI

struct DeckDetailView: View {
    var deckId: String = ""
    var onBack: () -> Void = {}

    // Static preview data until VM is wired via SKIE
    private let title = "Spanish Basics"
    private let description = "Core 500 words for everyday conversations. Built for absolute beginners."
    private let coverEmoji = "🇪🇸"
    private let authorName = "Maria Lopez"
    private let authorPubky = "pk:abc123…xyz789"
    private let authorInitial = "M"
    private let isOwned = true
    private let tags = ["spanish", "language", "beginner"]
    private let totalCards = 42
    private let dueCards = 12
    private let masteredPercent = "68%"
    private let cards: [(front: String, back: String)] = [
        ("el zorro", "the fox"),
        ("la casa", "the house"),
        ("el agua", "the water"),
    ]

    var body: some View {
        ZStack(alignment: .bottom) {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    // Header
                    HStack {
                        Button(action: onBack) {
                            ZStack {
                                Circle()
                                    .fill(EchoColor.surfaceCard)
                                    .frame(width: 40, height: 40)
                                Image(systemName: "chevron.left")
                                    .font(.system(size: 16, weight: .semibold))
                                    .foregroundColor(EchoColor.foregroundPrimary)
                            }
                        }
                        Spacer()
                        ZStack {
                            Circle()
                                .fill(EchoColor.surfaceCard)
                                .frame(width: 40, height: 40)
                            Image(systemName: isOwned ? "bookmark.fill" : "square.and.arrow.up")
                                .font(.system(size: 14))
                                .foregroundColor(isOwned ? EchoColor.accentPrimary : EchoColor.foregroundPrimary)
                        }
                    }

                    // Cover
                    ZStack {
                        RoundedRectangle(cornerRadius: 28)
                            .fill(EchoColor.accentPrimarySoft)
                        Text(coverEmoji)
                            .font(.system(size: isOwned ? 64 : 80))
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: isOwned ? 120 : 160)

                    // Badge (owned variant)
                    if isOwned {
                        HStack(spacing: 8) {
                            HStack(spacing: 4) {
                                Image(systemName: "checkmark")
                                    .font(.system(size: 10, weight: .bold))
                                    .foregroundColor(.white)
                                Text("IN YOUR LIBRARY")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundColor(.white)
                            }
                            .padding(.horizontal, 10)
                            .padding(.vertical, 4)
                            .background(Capsule().fill(EchoColor.srsGood))
                            Text("Last studied 2h ago")
                                .font(.system(size: 11, weight: .medium))
                                .foregroundColor(EchoColor.foregroundMuted)
                        }
                    }

                    // Title + Description
                    VStack(alignment: .leading, spacing: 8) {
                        Text(title)
                            .font(.system(size: 28, weight: .heavy))
                            .foregroundColor(EchoColor.foregroundPrimary)
                        if !isOwned, let desc = description as String? {
                            Text(desc)
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
                            Text(authorInitial)
                                .font(.system(size: 14, weight: .heavy))
                                .foregroundColor(EchoColor.accentSecondary)
                        }
                        VStack(alignment: .leading) {
                            Text(authorName)
                                .font(.system(size: 13, weight: .bold))
                                .foregroundColor(EchoColor.foregroundPrimary)
                            Text(authorPubky)
                                .font(.system(size: 11))
                                .foregroundColor(EchoColor.foregroundMuted)
                        }
                        Spacer()
                        Text(isOwned ? "Following" : "Follow")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundColor(isOwned ? EchoColor.accentSecondary : .white)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 6)
                            .background(
                                Capsule().fill(isOwned ? EchoColor.accentSecondarySoft : EchoColor.accentSecondary)
                            )
                    }

                    // Tags
                    HStack(spacing: 8) {
                        ForEach(tags, id: \.self) { tag in
                            TagChipView(tag: tag)
                        }
                    }

                    // Stats
                    StatsBarView(totalCards: totalCards, dueCards: dueCards, masteredPercent: masteredPercent)

                    // Card list
                    VStack(spacing: 8) {
                        ForEach(Array(cards.enumerated()), id: \.offset) { _, card in
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
                            .shadow(color: .black.opacity(0.05), radius: 8, x: 0, y: 2)
                        }
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 8)
                .padding(.bottom, 100)
            }

            // Bottom CTA
            Button(action: {}) {
                HStack(spacing: 8) {
                    Image(systemName: "play.fill").font(.system(size: 16))
                    Text(isOwned ? "Start studying · \(dueCards) due" : "Study this deck")
                        .font(.system(size: 17, weight: .bold))
                }
                .foregroundColor(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 18)
                .background(Capsule().fill(EchoColor.accentPrimary))
                .shadow(color: EchoColor.accentPrimary.opacity(0.2), radius: 24, x: 0, y: 8)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 20)
        }
        .background(EchoColor.surfacePrimary.ignoresSafeArea())
        .navigationBarHidden(true)
    }
}

// MARK: - Stats Bar

private struct StatsBarView: View {
    let totalCards: Int
    let dueCards: Int
    let masteredPercent: String

    var body: some View {
        HStack {
            StatColumn(value: "\(totalCards)", label: "Cards", valueColor: EchoColor.foregroundPrimary)
            Divider().frame(height: 32).overlay(EchoColor.borderSubtle)
            StatColumn(value: "\(dueCards)", label: "Due", valueColor: EchoColor.accentPrimary)
            Divider().frame(height: 32).overlay(EchoColor.borderSubtle)
            StatColumn(value: masteredPercent, label: "Mastered", valueColor: EchoColor.srsGood)
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
    let label: String
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

#Preview {
    DeckDetailView()
}
