import SwiftUI

/// The Loopky fox on a coloured plate — the brand mark as the app wears it.
///
/// The fox is the 🦊 emoji rather than an image, so it is drawn by the platform's own emoji font
/// and matches everywhere the app writes it. The app icon and the launch screen are the same
/// glyph baked to a bitmap, because neither of those can render text.
struct FoxPlate: View {
    var size: CGFloat
    var glyphSize: CGFloat
    var containerColor: Color
    var cornerRadius: CGFloat?

    var body: some View {
        ZStack {
            plate
            Text(verbatim: "🦊").font(.system(size: glyphSize))
        }
        .frame(width: size, height: size)
        // Decorative: every place this appears also spells out "Loopky" next to it.
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private var plate: some View {
        if let cornerRadius {
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous).fill(containerColor)
        } else {
            Circle().fill(containerColor)
        }
    }
}
