import SwiftUI

struct TagChipView: View {
    let tag: String
    var onTap: (() -> Void)? = nil
    var onRemove: (() -> Void)? = nil

    var body: some View {
        HStack(spacing: 4) {
            Text("#")
                .font(.system(size: 13, weight: .bold))
                .foregroundColor(EchoColor.accentSecondary)
            Text(tag)
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(EchoColor.accentSecondary)
            if let onRemove {
                Button(action: onRemove) {
                    Image(systemName: "xmark")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(EchoColor.accentSecondary)
                }
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
