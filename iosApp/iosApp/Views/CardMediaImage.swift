import SwiftUI
import Shared

/// Draws a card or cover picture, whichever of the three shapes it is in.
///
/// A `MediaRef.Image` is either a **remote** web URL (an Unsplash pick or a pasted address, stored
/// as-is with no blob) or a **blob** on a homeserver, which has to be fetched. On top of that a
/// freshly picked image exists only as `pendingBytes` until the deck is saved. All three have to
/// render, or a picture disappears at some point between choosing it and reloading the screen.
///
/// The fetch is keyed on the ref's sha, so switching cards re-fetches and re-rendering does not.
struct CardMediaImage: View {
    let ref: MediaRef.Image?
    /// Bytes chosen in this session and not yet uploaded. Takes precedence: it is the newest.
    var pendingBytes: Data?
    /// The *deck's* author, not the signed-in user — blobs on a deck you do not own live under
    /// their pubky, not yours.
    var authorPubky: String
    var deckId: String
    var contentMode: ContentMode = .fit

    @State private var blob: Data?
    @State private var isLoading = false

    var body: some View {
        Group {
            if let data = pendingBytes ?? blob, let image = UIImage(data: data) {
                Image(uiImage: image).resizable().aspectRatio(contentMode: contentMode)
            } else if let url = ref?.url, let link = URL(string: url) {
                AsyncImage(url: link) { image in
                    image.resizable().aspectRatio(contentMode: contentMode)
                } placeholder: {
                    placeholder
                }
            } else {
                placeholder
            }
        }
        .task(id: ref?.sha256) { await loadBlobIfNeeded() }
    }

    private var placeholder: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 12).fill(LoopkyColor.borderSubtle.opacity(0.4))
            if isLoading { ProgressView().controlSize(.small) }
        }
    }

    private func loadBlobIfNeeded() async {
        blob = nil
        guard pendingBytes == nil, let ref, ref.url == nil, !ref.sha256.isEmpty else { return }
        isLoading = true
        defer { isLoading = false }
        let bytes = try? await IosDependencies.shared.mediaBytes(
            authorPubky: authorPubky,
            deckId: deckId,
            ref: ref
        )
        blob = bytes?.toData()
    }
}
