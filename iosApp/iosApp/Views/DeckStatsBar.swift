import SwiftUI

/// Total / Due / New / Mastered, under a deck's metadata. Lives beside `DeckDetailView` rather
/// than in it only because that file is at SwiftLint's per-file line ceiling.
struct StatsBarView: View {
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

struct StatColumn: View {
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
