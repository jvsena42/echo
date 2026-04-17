import SwiftUI

struct ProfileView: View {
    var body: some View {
        ZStack {
            EchoColor.surfacePrimary.ignoresSafeArea()
            VStack(spacing: 12) {
                Text("\u{1F464}").font(.system(size: 64))
                Text("Profile")
                    .font(.system(size: 24, weight: .heavy))
                    .foregroundColor(EchoColor.foregroundPrimary)
                Text("Coming soon")
                    .font(.system(size: 14))
                    .foregroundColor(EchoColor.foregroundMuted)
            }
            .padding(.bottom, 100)
        }
    }
}
