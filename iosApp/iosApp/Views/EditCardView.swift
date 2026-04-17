import SwiftUI

struct EditCardView: View {
    var deckId: String = ""
    var cardId: String = ""
    var onBack: () -> Void = {}

    // Static preview data until VM is wired via SKIE
    @State private var frontText = "por favor"
    @State private var backText = "please"
    @State private var tags = ["es", "polite"]
    private let deckTitle = "Spanish Basics"
    private let cardIndex = 12
    private let totalCards = 42

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                // Header
                HStack {
                    Button(action: onBack) {
                        Text("Cancel")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(EchoColor.accentPrimary)
                    }
                    Spacer()
                    Text("Edit card")
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

                // Context chip
                HStack(spacing: 6) {
                    Image(systemName: "square.stack.3d.up")
                        .font(.system(size: 12))
                        .foregroundColor(EchoColor.accentSecondary)
                    Text("Card \(cardIndex) of \(totalCards) · \(deckTitle)")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(EchoColor.accentSecondary)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(
                    Capsule().fill(EchoColor.accentSecondarySoft)
                )

                // Front section
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text("FRONT")
                            .font(.system(size: 10, weight: .bold))
                            .kerning(0.8)
                            .foregroundColor(EchoColor.foregroundMuted)
                        Spacer()
                        HStack(spacing: 4) {
                            Image(systemName: "speaker.wave.2")
                                .font(.system(size: 12))
                            Text("Speak")
                                .font(.system(size: 12, weight: .semibold))
                        }
                        .foregroundColor(EchoColor.accentPrimary)
                    }
                    TextEditor(text: $frontText)
                        .font(.system(size: 20, weight: .bold))
                        .foregroundColor(EchoColor.foregroundPrimary)
                        .scrollContentBackground(.hidden)
                        .frame(minHeight: 80)
                        .padding(16)
                        .background(
                            RoundedRectangle(cornerRadius: 16)
                                .stroke(EchoColor.accentPrimary, lineWidth: 2)
                        )
                }

                // Back section
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text("BACK")
                            .font(.system(size: 10, weight: .bold))
                            .kerning(0.8)
                            .foregroundColor(EchoColor.foregroundMuted)
                        Spacer()
                        HStack(spacing: 4) {
                            Image(systemName: "speaker.wave.2")
                                .font(.system(size: 12))
                            Text("Speak")
                                .font(.system(size: 12, weight: .semibold))
                        }
                        .foregroundColor(EchoColor.accentPrimary)
                    }
                    TextEditor(text: $backText)
                        .font(.system(size: 16))
                        .foregroundColor(EchoColor.foregroundPrimary)
                        .scrollContentBackground(.hidden)
                        .frame(minHeight: 60)
                        .padding(16)
                        .background(
                            RoundedRectangle(cornerRadius: 16)
                                .stroke(EchoColor.borderSubtle, lineWidth: 1)
                        )
                }

                // Media buttons
                HStack(spacing: 12) {
                    Button(action: {}) {
                        HStack(spacing: 8) {
                            Image(systemName: "photo")
                                .font(.system(size: 16))
                            Text("Image")
                                .font(.system(size: 14))
                        }
                        .foregroundColor(EchoColor.foregroundMuted)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .overlay(
                            RoundedRectangle(cornerRadius: 14)
                                .stroke(EchoColor.borderSubtle, lineWidth: 1)
                        )
                    }
                    Button(action: {}) {
                        HStack(spacing: 8) {
                            Image(systemName: "mic")
                                .font(.system(size: 16))
                            Text("Audio")
                                .font(.system(size: 14))
                        }
                        .foregroundColor(EchoColor.foregroundMuted)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .overlay(
                            RoundedRectangle(cornerRadius: 14)
                                .stroke(EchoColor.borderSubtle, lineWidth: 1)
                        )
                    }
                }

                // Tags section
                VStack(alignment: .leading, spacing: 8) {
                    Text("TAGS")
                        .font(.system(size: 10, weight: .bold))
                        .kerning(0.8)
                        .foregroundColor(EchoColor.foregroundMuted)
                    HStack(spacing: 6) {
                        ForEach(tags, id: \.self) { tag in
                            TagChipView(tag: tag)
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
                .padding(16)
                .overlay(
                    RoundedRectangle(cornerRadius: 16)
                        .stroke(EchoColor.borderSubtle, lineWidth: 1)
                )

                Spacer().frame(height: 20)

                // Delete button
                Button(action: {}) {
                    HStack(spacing: 8) {
                        Image(systemName: "trash")
                            .font(.system(size: 16))
                        Text("Delete card")
                            .font(.system(size: 15, weight: .bold))
                    }
                    .foregroundColor(EchoColor.srsAgain)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .background(
                        RoundedRectangle(cornerRadius: 14)
                            .fill(EchoColor.dangerSoft)
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

#Preview {
    EditCardView()
}
