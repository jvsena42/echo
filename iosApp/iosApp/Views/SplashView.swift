import SwiftUI

/// The branded splash. Drawn while the persisted session is being read back, and deliberately a
/// continuation of the `UILaunchScreen` window (same cream surface, same fox on the same soft-accent
/// circle, same position) so the two read as one screen — this one just adds the words.
struct SplashView: View {
    var body: some View {
        ZStack {
            LoopkyColor.surfacePrimary.ignoresSafeArea()
            VStack(spacing: 0) {
                FoxPlate(size: 160, glyphSize: 96, containerColor: LoopkyColor.accentPrimarySoft)
                Spacer().frame(height: 24)
                Text("onboarding_brand_name")
                    .font(.system(size: 30, weight: .heavy))
                    .foregroundColor(LoopkyColor.foregroundPrimary)
                Spacer().frame(height: 10)
                Text("brand_tagline")
                    .font(.system(size: 16))
                    .foregroundColor(LoopkyColor.foregroundSecondary)
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, 32)
        }
    }
}

#Preview {
    SplashView()
}
