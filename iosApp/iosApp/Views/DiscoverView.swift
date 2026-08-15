import SwiftUI

struct DiscoverView: View {
    var body: some View {
        ZStack {
            LoopkyColor.surfacePrimary.ignoresSafeArea()
            VStack(spacing: 12) {
                Text("\u{1F50D}").font(.system(size: 64))
                Text("discover_title")
                    .font(.system(size: 24, weight: .heavy))
                    .foregroundColor(LoopkyColor.foregroundPrimary)
                Text("common_coming_soon")
                    .font(.system(size: 14))
                    .foregroundColor(LoopkyColor.foregroundMuted)
            }
            .padding(.bottom, 100)
        }
    }
}

#Preview {
    DiscoverView()
}
