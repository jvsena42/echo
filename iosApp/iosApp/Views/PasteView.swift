import SwiftUI

struct PasteView: View {
    var onCancel: () -> Void = {}
    var onNext: () -> Void = {}

    @State private var rawText = ""
    @State private var isParsed = false
    @State private var detectedSeparator = ""
    @State private var cardCount = 0

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                // Header
                HStack {
                    Button(action: onCancel) {
                        Text("Cancel")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(EchoColor.accentPrimary)
                    }
                    Spacer()
                    Text("Paste cards")
                        .font(.system(size: 18, weight: .heavy))
                        .foregroundColor(EchoColor.foregroundPrimary)
                    Spacer()
                    Button(action: onNext) {
                        Text("Next")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(isParsed ? .white : EchoColor.foregroundMuted)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 8)
                            .background(
                                Capsule().fill(isParsed ? EchoColor.accentPrimary : EchoColor.borderSubtle)
                            )
                    }
                    .disabled(!isParsed)
                }

                // Text field
                TextEditor(text: $rawText)
                    .font(.system(size: 14))
                    .foregroundColor(EchoColor.foregroundPrimary)
                    .scrollContentBackground(.hidden)
                    .frame(minHeight: 160)
                    .padding(16)
                    .background(
                        RoundedRectangle(cornerRadius: 16)
                            .fill(EchoColor.surfaceCard)
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 16)
                            .stroke(rawText.isEmpty() ? EchoColor.borderSubtle : EchoColor.accentPrimary, lineWidth: 2)
                    )
                    .overlay(alignment: .topLeading) {
                        if rawText.isEmpty() {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Paste your list here")
                                    .font(.system(size: 16, weight: .semibold))
                                    .foregroundColor(EchoColor.foregroundMuted)
                                Text("one card per line")
                                    .font(.system(size: 13))
                                    .foregroundColor(EchoColor.foregroundMuted.opacity(0.6))
                            }
                            .padding(20)
                            .allowsHitTesting(false)
                        }
                    }
                    .onChange(of: rawText) { _ in
                        // Simple local detection for preview
                        let lines = rawText.split(separator: "\n").filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
                        isParsed = lines.count >= 2
                        cardCount = lines.count
                        if rawText.contains(" — ") { detectedSeparator = "em-dash" }
                        else if rawText.contains("\t") { detectedSeparator = "tab" }
                        else if rawText.contains(": ") { detectedSeparator = "colon" }
                        else if rawText.contains(",") { detectedSeparator = "comma" }
                        else { detectedSeparator = "auto" }
                    }

                // Separator chip
                if isParsed {
                    HStack {
                        HStack(spacing: 6) {
                            Image(systemName: "checkmark")
                                .font(.system(size: 12, weight: .bold))
                                .foregroundColor(EchoColor.accentSecondary)
                            Text("Detected: \(detectedSeparator)")
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundColor(EchoColor.accentSecondary)
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Capsule().fill(EchoColor.accentSecondarySoft))
                        Spacer()
                        Text("\(cardCount) cards")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundColor(EchoColor.foregroundMuted)
                    }
                }

                // Examples when empty
                if rawText.isEmpty() {
                    Text("PREVIEW")
                        .font(.system(size: 10, weight: .bold))
                        .kerning(0.8)
                        .foregroundColor(EchoColor.foregroundMuted)
                    Text("TRY PASTING SOMETHING LIKE")
                        .font(.system(size: 10, weight: .bold))
                        .kerning(0.8)
                        .foregroundColor(EchoColor.foregroundMuted)

                    ExampleCardView(title: "Vocab list", separator: "em-dash", lines: ["hola — hello", "gracias — thank you"])
                    ExampleCardView(title: "Glossary", separator: "colon", lines: ["mitosis: cell division", "osmosis: water moves across a membrane"])
                    ExampleCardView(title: "Notion table", separator: "markdown", lines: ["| capital | France |", "| currency | euro |"])
                }

                // Public notice
                HStack(spacing: 6) {
                    Text("🔗").font(.system(size: 14))
                    Text("This deck will be public on your profile")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundColor(EchoColor.accentSecondary)
                }
                .frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 40)
        }
        .background(EchoColor.surfacePrimary.ignoresSafeArea())
        .navigationBarHidden(true)
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
                    .foregroundColor(EchoColor.foregroundPrimary)
                Spacer()
                Text(separator)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundColor(EchoColor.accentPrimary)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 2)
                    .background(Capsule().fill(EchoColor.accentPrimarySoft))
            }
            ForEach(lines, id: \.self) { line in
                Text(line)
                    .font(.system(size: 13))
                    .foregroundColor(EchoColor.foregroundSecondary)
            }
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: 14)
                .fill(EchoColor.surfaceCard)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 14)
                .stroke(EchoColor.borderSubtle, lineWidth: 1)
        )
    }
}

#Preview {
    PasteView()
}
