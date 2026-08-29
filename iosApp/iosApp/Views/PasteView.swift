import SwiftUI
import Shared

/// Paste-to-Import, spec §5: paste a list, watch it parse, advance to publish.
///
/// Pure layout — every number and label comes from `PasteImportViewModel` through `PasteScreen`.
struct PasteView: View {
    var state: PasteViewState = PasteViewState()
    @Binding var text: String
    var onCancel: () -> Void = {}
    var onNext: () -> Void = {}
    var onSeparatorPicked: (Separator) -> Void = { _ in }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                header
                editor
                if state.isParsed { separatorRow }
                if let errorMessage = state.errorMessage { noticeRow(errorMessage, tone: .danger) }
                if state.noPatternDetected { noticeRow(NSLocalizedString("paste_no_pattern", comment: ""), tone: .warning) }
                if state.hasIncompleteCards { noticeRow(incompleteText, tone: .warning) }
                if state.hasPreviewableCard { preview } else if text.isEmpty { examples }
                publicNotice
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 40)
        }
        .background(LoopkyColor.surfacePrimary.ignoresSafeArea())
        .navigationBarHidden(true)
    }

    private var header: some View {
        HStack {
            Button(action: onCancel) {
                Text("paste_cancel")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(LoopkyColor.accentPrimary)
            }
            Spacer()
            Text("paste_title")
                .font(.system(size: 18, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Spacer()
            Button("paste_next", action: onNext)
                .buttonStyle(LoopkyCompactFilledButtonStyle(
                    fill: state.canAdvance ? LoopkyColor.accentPrimary : LoopkyColor.borderSubtle,
                    foreground: state.canAdvance ? .white : LoopkyColor.foregroundMuted
                ))
                .disabled(!state.canAdvance)
        }
    }

    private var editor: some View {
        TextEditor(text: $text)
            .font(.system(size: 14))
            .foregroundStyle(LoopkyColor.foregroundPrimary)
            .scrollContentBackground(.hidden)
            .frame(minHeight: 160)
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 16).fill(LoopkyColor.surfaceCard))
            .overlay(
                RoundedRectangle(cornerRadius: 16)
                    .stroke(
                        text.isEmpty ? LoopkyColor.borderSubtle : LoopkyColor.accentPrimary,
                        lineWidth: 2
                    )
            )
            .overlay(alignment: .topLeading) {
                if text.isEmpty { placeholder }
            }
    }

    private var placeholder: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("paste_input_placeholder_title")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(LoopkyColor.foregroundMuted)
            Text("paste_input_placeholder_subtitle")
                .font(.system(size: 13))
                .foregroundStyle(LoopkyColor.foregroundMuted.opacity(0.6))
        }
        .padding(20)
        .allowsHitTesting(false)
    }

    /// The chip is a `Menu`, not decoration: spec §5.2 asks for "tap to change", and the parser
    /// takes the override and re-parses the same text.
    private var separatorRow: some View {
        HStack {
            Menu {
                ForEach(Array(KotlinInterop.selectableSeparators.enumerated()), id: \.offset) { _, separator in
                    Button(KotlinInterop.separatorLabel(separator)) { onSeparatorPicked(separator) }
                }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "checkmark")
                        .font(.system(size: 12, weight: .bold))
                    Text(String(
                        format: NSLocalizedString("paste_detected_separator", comment: ""),
                        state.separatorLabel
                    ))
                    .font(.system(size: 12, weight: .semibold))
                    Image(systemName: "chevron.down").font(.system(size: 9, weight: .bold))
                }
                .foregroundStyle(LoopkyColor.accentSecondary)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(Capsule().fill(LoopkyColor.accentSecondarySoft))
            }
            Spacer()
            Text(String(
                format: NSLocalizedString("paste_card_count", comment: ""),
                state.cardCount
            ))
            .font(.system(size: 13, weight: .medium))
            .foregroundStyle(LoopkyColor.foregroundMuted)
        }
    }

    private var preview: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("paste_preview_label")
                .font(.system(size: 10, weight: .bold))
                .kerning(0.8)
                .foregroundStyle(LoopkyColor.foregroundMuted)
            ForEach(state.previewCards) { card in
                HStack(alignment: .top, spacing: 10) {
                    Text(card.front.isEmpty ? NSLocalizedString("paste_blank_placeholder", comment: "") : card.front)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(LoopkyColor.foregroundPrimary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text(card.back.isEmpty ? NSLocalizedString("paste_blank_placeholder", comment: "") : card.back)
                        .font(.system(size: 13))
                        .foregroundStyle(LoopkyColor.foregroundSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 12).fill(LoopkyColor.surfaceCard))
                .overlay(
                    RoundedRectangle(cornerRadius: 12).stroke(LoopkyColor.borderSubtle, lineWidth: 1)
                )
            }
        }
    }

    private var examples: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("paste_try_pasting_label")
                .font(.system(size: 10, weight: .bold))
                .kerning(0.8)
                .foregroundStyle(LoopkyColor.foregroundMuted)
            ExampleCardView(
                title: "Vocab list",
                separator: KotlinInterop.separatorLabel(Separator.EmDash.shared),
                lines: ["hola — hello", "gracias — thank you"]
            )
            ExampleCardView(
                title: "Glossary",
                separator: KotlinInterop.separatorLabel(Separator.Colon.shared),
                lines: ["mitosis: cell division", "osmosis: water moves across a membrane"]
            )
            ExampleCardView(
                title: "Notion table",
                separator: KotlinInterop.separatorLabel(Separator.MarkdownTable.shared),
                lines: ["| capital | France |", "| currency | euro |"]
            )
        }
    }

    private var incompleteText: String {
        String(
            format: NSLocalizedString("paste_incomplete_cards", comment: ""),
            state.incompleteCardCount
        )
    }

    private enum NoticeTone { case danger, warning }

    private func noticeRow(_ message: String, tone: NoticeTone) -> some View {
        let color = tone == .danger ? LoopkyColor.danger : LoopkyColor.srsHard
        return HStack(alignment: .top, spacing: 8) {
            Image(systemName: tone == .danger ? "exclamationmark.circle.fill" : "exclamationmark.triangle.fill")
                .font(.system(size: 13))
            Text(message).font(.system(size: 13, weight: .medium))
            Spacer(minLength: 0)
        }
        .foregroundStyle(color)
    }

    private var publicNotice: some View {
        HStack(spacing: 6) {
            Text("🔗").font(.system(size: 14))
            Text("paste_public_notice")
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(LoopkyColor.accentSecondary)
        }
        .frame(maxWidth: .infinity)
    }
}

private struct ExampleCardView: View {
    let title: String
    let separator: String
    let lines: [String]

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(title)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(LoopkyColor.foregroundPrimary)
                Spacer()
                Text(separator)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(LoopkyColor.accentPrimary)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 2)
                    .background(Capsule().fill(LoopkyColor.accentPrimarySoft))
            }
            ForEach(lines, id: \.self) { line in
                Text(line)
                    .font(.system(size: 13))
                    .foregroundStyle(LoopkyColor.foregroundSecondary)
            }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 14).fill(LoopkyColor.surfaceCard))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(LoopkyColor.borderSubtle, lineWidth: 1))
    }
}
