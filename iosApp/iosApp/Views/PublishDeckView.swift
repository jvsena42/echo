import SwiftUI

struct PublishDeckView: View {
    var onBack: () -> Void = {}
    var onPublished: (String) -> Void = { _ in }

    @State private var title = ""
    @State private var description = ""
    @State private var coverEmoji = "📚"
    @State private var tags: [String] = []
    @State private var showTagSheet = false
    @State private var tagInput = ""
    private let cardCount = 42

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
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
                    Text("publish_title")
                        .font(.system(size: 18, weight: .heavy))
                        .foregroundColor(EchoColor.foregroundPrimary)
                    Spacer()
                    Spacer().frame(width: 40)
                }

                // Cards ready badge
                HStack(spacing: 10) {
                    Image(systemName: "checkmark")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(EchoColor.srsGood)
                    VStack(alignment: .leading) {
                        Text(String(format: NSLocalizedString("publish_cards_ready", comment: ""), cardCount))
                            .font(.system(size: 15, weight: .bold))
                            .foregroundColor(EchoColor.foregroundPrimary)
                    }
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    RoundedRectangle(cornerRadius: 14)
                        .fill(EchoColor.srsGood.opacity(0.15))
                )

                // Cover
                HStack(spacing: 12) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 14)
                            .fill(EchoColor.accentPrimarySoft)
                            .frame(width: 64, height: 64)
                        Text(coverEmoji).font(.system(size: 32))
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text("publish_cover_label")
                            .font(.system(size: 10, weight: .bold))
                            .kerning(0.8)
                            .foregroundColor(EchoColor.foregroundMuted)
                        HStack(spacing: 4) {
                            Text("🖼️").font(.system(size: 14))
                            Text("publish_cover_change")
                                .font(.system(size: 13, weight: .medium))
                                .foregroundColor(EchoColor.foregroundPrimary)
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .overlay(
                            RoundedRectangle(cornerRadius: 8)
                                .stroke(EchoColor.borderSubtle, lineWidth: 1)
                        )
                    }
                }

                // Title
                VStack(alignment: .leading, spacing: 6) {
                    Text("publish_title_label")
                        .font(.system(size: 10, weight: .bold))
                        .kerning(0.8)
                        .foregroundColor(EchoColor.foregroundMuted)
                    TextField("publish_title_placeholder", text: $title)
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(EchoColor.foregroundPrimary)
                        .padding(14)
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(EchoColor.borderSubtle, lineWidth: 1)
                        )
                }

                // Description
                VStack(alignment: .leading, spacing: 6) {
                    Text("publish_description_label")
                        .font(.system(size: 10, weight: .bold))
                        .kerning(0.8)
                        .foregroundColor(EchoColor.foregroundMuted)
                    TextField("publish_description_placeholder", text: $description)
                        .font(.system(size: 14))
                        .foregroundColor(EchoColor.foregroundSecondary)
                        .padding(14)
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(EchoColor.borderSubtle, lineWidth: 1)
                        )
                }

                // Tags
                VStack(alignment: .leading, spacing: 8) {
                    Text("publish_tags_label")
                        .font(.system(size: 10, weight: .bold))
                        .kerning(0.8)
                        .foregroundColor(EchoColor.foregroundMuted)
                    HStack(spacing: 6) {
                        ForEach(tags, id: \.self) { tag in
                            TagChipView(tag: tag, onRemove: {
                                tags.removeAll { $0 == tag }
                            })
                        }
                        Button(action: { showTagSheet = true }) {
                            HStack(spacing: 4) {
                                Image(systemName: "plus")
                                Text("publish_add")
                            }
                        }
                        .buttonStyle(EchoOutlineButtonStyle(
                            stroke: EchoColor.borderSubtle,
                            foreground: EchoColor.foregroundMuted,
                            cornerRadius: 50,
                            lineWidth: 1.5,
                            fontSize: 12,
                            fillWidth: false
                        ))
                    }
                }

                // Public notice
                HStack(spacing: 10) {
                    Text("🌐").font(.system(size: 18))
                    VStack(alignment: .leading) {
                        Text("publish_public_title")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(EchoColor.foregroundPrimary)
                        Text("publish_public_subtitle")
                            .font(.system(size: 12))
                            .foregroundColor(EchoColor.foregroundSecondary)
                    }
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    RoundedRectangle(cornerRadius: 14)
                        .fill(EchoColor.accentSecondarySoft)
                )

                // Publish button
                Button(action: { onPublished("preview-deck-id") }) {
                    HStack(spacing: 8) {
                        Text("🔗")
                        Text("publish_button")
                    }
                }
                .buttonStyle(EchoFilledButtonStyle(fill: title.isEmpty ? Color.gray : EchoColor.accentPrimary))
                .shadow(color: EchoColor.shadowAccent, radius: 24, x: 0, y: 8)
                .disabled(title.isEmpty)
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 40)
        }
        .background(EchoColor.surfacePrimary.ignoresSafeArea())
        .navigationBarHidden(true)
        .sheet(isPresented: $showTagSheet) {
            PublishAddTagSheet(tags: $tags, tagInput: $tagInput)
        }
    }
}

private struct PublishAddTagSheet: View {
    @Binding var tags: [String]
    @Binding var tagInput: String
    @Environment(\.dismiss) private var dismiss

    private let suggestedOptions = ["language", "beginner", "travel", "daily"]

    private var suggestedTags: [String] {
        suggestedOptions.filter { !tags.contains($0) }
    }

    var body: some View {
        VStack(spacing: 20) {
            // Handle
            RoundedRectangle(cornerRadius: 2)
                .fill(EchoColor.borderSubtle)
                .frame(width: 36, height: 4)
                .padding(.top, 12)

            VStack(alignment: .leading, spacing: 20) {
                // Title
                Text("publish_tag_sheet_title")
                    .font(.system(size: 20, weight: .heavy))
                    .foregroundColor(EchoColor.foregroundPrimary)

                // Input row
                HStack(spacing: 10) {
                    Text("#")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(EchoColor.accentSecondary)
                    TextField("publish_tag_input_placeholder", text: $tagInput)
                        .font(.system(size: 15, weight: .medium))
                        .foregroundColor(EchoColor.foregroundPrimary)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .background(
                    RoundedRectangle(cornerRadius: 14)
                        .fill(EchoColor.surfacePrimary)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 14)
                        .stroke(EchoColor.borderSubtle, lineWidth: 1.5)
                )

                // Suggested
                if !suggestedTags.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("publish_suggested_label")
                            .font(.system(size: 10, weight: .bold))
                            .kerning(1)
                            .foregroundColor(EchoColor.foregroundMuted)
                        HStack(spacing: 6) {
                            ForEach(suggestedTags, id: \.self) { tag in
                                TagChipView(tag: tag, onTap: {
                                    let trimmed = tag.trimmingCharacters(in: .whitespaces).lowercased()
                                    if !trimmed.isEmpty && !tags.contains(trimmed) {
                                        tags.append(trimmed)
                                    }
                                })
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
                            .foregroundColor(EchoColor.foregroundMuted)
                        HStack(spacing: 6) {
                            ForEach(tags, id: \.self) { tag in
                                TagChipView(tag: tag, onRemove: {
                                    tags.removeAll { $0 == tag }
                                })
                            }
                        }
                    }
                }

                // Add Tag button
                Button(action: {
                    let trimmed = tagInput.trimmingCharacters(in: .whitespaces).lowercased()
                    if !trimmed.isEmpty && !tags.contains(trimmed) {
                        tags.append(trimmed)
                        tagInput = ""
                    }
                }) {
                    HStack(spacing: 8) {
                        Image(systemName: "plus")
                        Text("publish_add_tag_button")
                    }
                }
                .buttonStyle(EchoFilledButtonStyle(
                    fill: tagInput.trimmingCharacters(in: .whitespaces).isEmpty ? Color.gray : EchoColor.accentPrimary,
                    fontSize: 16
                ))
                .shadow(color: EchoColor.shadowAccent, radius: 24, x: 0, y: 8)
                .disabled(tagInput.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 32)
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.hidden)
        .presentationBackground(.white)
    }
}

#Preview {
    PublishDeckView()
}
