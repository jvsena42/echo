import SwiftUI

struct HomeStubView: View {
    var pubky: String? = nil

    var body: some View {
        VStack(spacing: 12) {
            Text("You're signed in")
                .font(.system(size: 24, weight: .heavy))
            Text(pubky.map { "pk:\($0.prefix(6))…\($0.suffix(6))" } ?? "…")
                .font(.system(size: 14))
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(red: 1.0, green: 0.98, blue: 0.96).ignoresSafeArea())
    }
}

#if DEBUG
struct HomeStubView_Previews: PreviewProvider {
    static var previews: some View { HomeStubView(pubky: "abc123456789xyz") }
}
#endif
