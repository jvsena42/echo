import SwiftUI

/// Bottom sheet to add/remove tags. Callback-based so VM-owned tag lists
/// (DeckEditor, EditCard, PublishDeck) can drive it directly.
struct AddTagSheet: View {
    let tags: [String]
    var onAdd: (String) -> Void
    var onRemove: (String) -> Void

    @State private var tagInput = ""

    private let suggestedOptions = ["language", "beginner", "travel", "daily"]

    private var suggestedTags: [String] {
        suggestedOptions.filter { !tags.contains($0) }
    }

    private var trimmedInput: String {
        tagInput.trimmingCharacters(in: .whitespaces).lowercased()
    }

    var body: some View {
        VStack(spacing: 20) {
            // Handle
            RoundedRectangle(cornerRadius: 2)
                .fill(LoopkyColor.borderSubtle)
                .frame(width: 36, height: 4)
                .padding(.top, 12)

            VStack(alignment: .leading, spacing: 20) {
                Text("publish_tag_sheet_title")
                    .font(.system(size: 20, weight: .heavy))
                    .foregroundColor(LoopkyColor.foregroundPrimary)

                // Input row
                HStack(spacing: 10) {
                    Text("#")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(LoopkyColor.accentSecondary)
                    TextField("publish_tag_input_placeholder", text: $tagInput)
                        .font(.system(size: 15, weight: .medium))
                        .foregroundColor(LoopkyColor.foregroundPrimary)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .background(
                    RoundedRectangle(cornerRadius: 14)
                        .fill(LoopkyColor.surfacePrimary)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 14)
                        .stroke(LoopkyColor.borderSubtle, lineWidth: 1.5)
                )

                // Suggested
                if !suggestedTags.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("publish_suggested_label")
                            .font(.system(size: 10, weight: .bold))
                            .kerning(1)
                            .foregroundColor(LoopkyColor.foregroundMuted)
                        HStack(spacing: 6) {
                            ForEach(suggestedTags, id: \.self) { tag in
                                TagChipView(tag: tag, onTap: { onAdd(tag) })
                            }
                        }
                    }
                }

                // Current tags
                if !tags.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("publish_current_tags_label")
                            .font(.system(size: 10, weight: .bold))
                            .kerning(1)
                            .foregroundColor(LoopkyColor.foregroundMuted)
                        HStack(spacing: 6) {
                            ForEach(tags, id: \.self) { tag in
                                TagChipView(tag: tag, onRemove: { onRemove(tag) })
                            }
                        }
                    }
                }

                // Add Tag button
                Button(action: {
                    if !trimmedInput.isEmpty {
                        onAdd(trimmedInput)
                        tagInput = ""
                    }
                }) {
                    HStack(spacing: 8) {
                        Image(systemName: "plus")
                        Text("publish_add_tag_button")
                    }
                }
                .buttonStyle(LoopkyFilledButtonStyle(
                    fill: trimmedInput.isEmpty ? Color.gray : LoopkyColor.accentPrimary,
                    fontSize: 16
                ))
                .shadow(color: LoopkyColor.shadowAccent, radius: 24, x: 0, y: 8)
                .disabled(trimmedInput.isEmpty)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 32)

            Spacer(minLength: 0)
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.hidden)
        .presentationBackground(.white)
    }
}

#Preview {
    AddTagSheet(
        tags: ["spanish", "beginner"],
        onAdd: { _ in },
        onRemove: { _ in }
    )
}
