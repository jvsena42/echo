import SwiftUI
import UIKit

/// Identifiable payload for `.sheet(item:)`-driven shares.
struct ShareItem: Identifiable {
    let id = UUID()
    let text: String
}

/// UIActivityViewController wrapper for effect-driven shares (deck URIs, profile links).
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
