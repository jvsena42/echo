import SwiftUI

struct ProfileView: View {
    var body: some View {
        ZStack {
            LoopkyColor.surfacePrimary.ignoresSafeArea()
            VStack(spacing: 12) {
                Text("\u{1F464}").font(.system(size: 64))
                Text("profile_title")
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
    ProfileView()
}
