import SwiftUI

struct TagChipView: View {
    let tag: String
    var onTap: (() -> Void)?
    var onRemove: (() -> Void)?

    var body: some View {
        HStack(spacing: 4) {
            Text(String(format: NSLocalizedString("component_tag_chip_label", comment: ""), tag))
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(EchoColor.accentSecondary)
            if let onRemove {
                Button(action: onRemove) {
                    Image(systemName: "xmark")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(EchoColor.accentSecondary)
                }
                .accessibilityLabel("component_tag_chip_remove")
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(Capsule().fill(EchoColor.accentSecondarySoft))
        .onTapGesture { onTap?() }
    }
}

#Preview {
    HStack(spacing: 8) {
        TagChipView(tag: "spanish")
        TagChipView(tag: "language")
        TagChipView(tag: "beginner")
    }
    .padding()
}
