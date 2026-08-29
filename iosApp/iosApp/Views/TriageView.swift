import SwiftUI

/// Keep or discard each imported card. Pure layout.
struct TriageView: View {
    var state: TriageViewState = TriageViewState()
    var onKeep: () -> Void = {}
    var onDiscard: () -> Void = {}
    var onUndo: () -> Void = {}
    var onApproveAll: () -> Void = {}
    var onBack: () -> Void = {}

    var body: some View {
        VStack(spacing: 16) {
            header
            progress
            if state.hasCard { card } else { Spacer() }
            if let errorMessage = state.errorMessage {
                Text(errorMessage).font(.system(size: 13)).foregroundStyle(LoopkyColor.danger)
            }
            Spacer(minLength: 0)
            if state.hasCard { actions }
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
        .padding(.bottom, 20)
        .background(LoopkyColor.surfacePrimary.ignoresSafeArea())
        .navigationBarHidden(true)
    }

    private var header: some View {
        HStack {
            Button("bulk_cancel", action: onBack)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(LoopkyColor.accentPrimary)
            Spacer()
            Text("triage_title")
                .font(.system(size: 18, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Spacer()
            Button("triage_approve_all", action: onApproveAll)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(LoopkyColor.accentPrimary)
                .accessibilityIdentifier("triage_approve_all")
        }
    }

    private var progress: some View {
        VStack(spacing: 4) {
            Text(String(
                format: NSLocalizedString("triage_progress", comment: ""),
                state.position, state.total
            ))
            .font(.system(size: 14, weight: .heavy))
            .foregroundStyle(LoopkyColor.foregroundPrimary)
            .accessibilityIdentifier("triage_progress")

            Text(String(
                format: NSLocalizedString("triage_stats", comment: ""),
                state.keptCount, state.discardedCount
            ))
            .font(.system(size: 12))
            .foregroundStyle(LoopkyColor.foregroundMuted)
        }
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 14) {
            side("triage_front_label", state.front)
            Divider()
            side("triage_back_label", state.back)
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 20).fill(LoopkyColor.surfaceCard))
        .shadow(color: LoopkyColor.shadowElevationLow, radius: 12, y: 4)
        .accessibilityIdentifier("triage_card")
    }

    private func side(_ label: LocalizedStringKey, _ text: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(.system(size: 10, weight: .bold))
                .kerning(0.8)
                .foregroundStyle(LoopkyColor.foregroundMuted)
            Text(text)
                .font(.system(size: 17))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
        }
    }

    private var actions: some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                Button("triage_discard", action: onDiscard)
                    .buttonStyle(.loopkyOutline)
                    .accessibilityIdentifier("triage_discard")
                Button("triage_keep", action: onKeep)
                    .buttonStyle(.loopkyFilled)
                    .accessibilityIdentifier("triage_keep")
            }
            // Only after a decision, and only for the last one — the ViewModel owns that rule.
            if state.canUndo {
                Button("triage_undo", action: onUndo)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
            }
        }
    }
}
