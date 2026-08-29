import SwiftUI

struct TagChipView: View {
    let tag: String
    var onTap: (() -> Void)?
    var onRemove: (() -> Void)?

    var body: some View {
        // A `Button` when it does something, not an `onTapGesture`: a tap gesture on a shape is
        // not a control, so VoiceOver never announces it and UI automation cannot find it — the
        // chips on deck detail were tappable by a finger and by nothing else.
        if let onTap {
            Button(action: onTap) { chip }
                .buttonStyle(.plain)
                .accessibilityIdentifier("tag_chip_\(tag)")
        } else {
            chip
        }
    }

    private var chip: some View {
        HStack(spacing: 4) {
            Text(String(format: NSLocalizedString("component_tag_chip_label", comment: ""), tag))
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(LoopkyColor.accentSecondary)
            if let onRemove {
                Button(action: onRemove) {
                    Image(systemName: "xmark")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(LoopkyColor.accentSecondary)
                }
                .accessibilityLabel("component_tag_chip_remove")
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(Capsule().fill(LoopkyColor.accentSecondarySoft))
    }
}
