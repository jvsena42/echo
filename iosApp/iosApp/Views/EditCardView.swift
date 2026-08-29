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
                        Text("edit_card_cancel")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(LoopkyColor.accentPrimary)
                    }
                    Spacer()
                    Text("edit_card_title")
                        .font(.system(size: 18, weight: .heavy))
                        .foregroundColor(LoopkyColor.foregroundPrimary)
                    Spacer()
                    Button("edit_card_save", action: {})
                        .buttonStyle(.loopkyCompactFilled)
                }

                // Context chip
                HStack(spacing: 6) {
                    Image(systemName: "square.stack.3d.up")
                        .font(.system(size: 12))
                        .foregroundColor(LoopkyColor.accentSecondary)
                    Text(String(format: NSLocalizedString("edit_card_context", comment: ""), cardIndex, totalCards, deckTitle))
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(LoopkyColor.accentSecondary)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(
                    Capsule().fill(LoopkyColor.accentSecondarySoft)
                )

                // Front section
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text("edit_card_label_front")
                            .font(.system(size: 10, weight: .bold))
                            .kerning(0.8)
                            .foregroundColor(LoopkyColor.foregroundMuted)
                        Spacer()
                        HStack(spacing: 4) {
                            Image(systemName: "speaker.wave.2")
                                .font(.system(size: 12))
                            Text("edit_card_speak")
                                .font(.system(size: 12, weight: .semibold))
                        }
                        .foregroundColor(LoopkyColor.accentPrimary)
                    }
                    TextEditor(text: $frontText)
                        .font(.system(size: 20, weight: .bold))
                        .foregroundColor(LoopkyColor.foregroundPrimary)
                        .scrollContentBackground(.hidden)
                        .frame(minHeight: 80)
                        .padding(16)
                        .background(
                            RoundedRectangle(cornerRadius: 16)
                                .stroke(LoopkyColor.accentPrimary, lineWidth: 2)
                        )
                }

                // Back section
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text("edit_card_label_back")
                            .font(.system(size: 10, weight: .bold))
                            .kerning(0.8)
                            .foregroundColor(LoopkyColor.foregroundMuted)
                        Spacer()
                        HStack(spacing: 4) {
                            Image(systemName: "speaker.wave.2")
                                .font(.system(size: 12))
                            Text("edit_card_speak")
                                .font(.system(size: 12, weight: .semibold))
                        }
                        .foregroundColor(LoopkyColor.accentPrimary)
                    }
                    TextEditor(text: $backText)
                        .font(.system(size: 16))
                        .foregroundColor(LoopkyColor.foregroundPrimary)
                        .scrollContentBackground(.hidden)
                        .frame(minHeight: 60)
                        .padding(16)
                        .background(
                            RoundedRectangle(cornerRadius: 16)
                                .stroke(LoopkyColor.borderSubtle, lineWidth: 1)
                        )
                }

                // Media buttons
                HStack(spacing: 12) {
                    Button(action: {}) {
                        HStack(spacing: 8) {
                            Image(systemName: "photo")
                            Text("edit_card_image")
                        }
                    }
                    .buttonStyle(LoopkyOutlineButtonStyle(
                        stroke: LoopkyColor.borderSubtle,
                        foreground: LoopkyColor.foregroundMuted,
                        lineWidth: 1,
                        fontSize: 14
                    ))
                    Button(action: {}) {
                        HStack(spacing: 8) {
                            Image(systemName: "mic")
                            Text("edit_card_audio")
                        }
                    }
                    .buttonStyle(LoopkyOutlineButtonStyle(
                        stroke: LoopkyColor.borderSubtle,
                        foreground: LoopkyColor.foregroundMuted,
                        lineWidth: 1,
                        fontSize: 14
                    ))
                }

                // Tags section
                VStack(alignment: .leading, spacing: 8) {
                    Text("edit_card_label_tags")
                        .font(.system(size: 10, weight: .bold))
                        .kerning(0.8)
                        .foregroundColor(LoopkyColor.foregroundMuted)
                    HStack(spacing: 6) {
                        ForEach(tags, id: \.self) { tag in
                            TagChipView(tag: tag)
                        }
                        Text("edit_card_add_tag")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(LoopkyColor.accentSecondary)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .overlay(
                                Capsule().stroke(LoopkyColor.accentSecondary, lineWidth: 1)
                            )
                    }
                }
                .padding(16)
                .overlay(
                    RoundedRectangle(cornerRadius: 16)
                        .stroke(LoopkyColor.borderSubtle, lineWidth: 1)
                )

                Spacer().frame(height: 20)

                // Delete button
                Button(action: {}) {
                    HStack(spacing: 8) {
                        Image(systemName: "trash")
                            .font(.system(size: 16))
                        Text("edit_card_delete")
                            .font(.system(size: 15, weight: .bold))
                    }
                    .foregroundColor(LoopkyColor.srsAgain)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .background(
                        RoundedRectangle(cornerRadius: 14)
                            .fill(LoopkyColor.dangerSoft)
                    )
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 40)
        }
        .background(LoopkyColor.surfacePrimary.ignoresSafeArea())
        .navigationBarHidden(true)
    }
}

#Preview {
    EditCardView()
}
