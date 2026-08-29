import Foundation

/// Reading and writing a `recovery.pkarr`, mirroring Android's `RecoveryFileReader` /
/// `RecoveryFileWriter`.
///
/// **The encoding is the whole point of this file.** A recovery file on disk is *raw bytes* —
/// `"pubky.org/recovery\n"` followed by the Argon2id-encrypted key — which is what pubky-app
/// writes and what Pubky Ring reads back. The FFI, by contrast, hands out and takes **Base64**.
///
/// Getting it backwards fails in the worst possible way: writing the Base64 text verbatim produces
/// a file only Loopky can open, and the failure does not surface here — it surfaces months later
/// on another device as "this recovery file is corrupt". Neither direction points at the encoding.
enum RecoveryFile {

    /// A real recovery file is ~100 bytes. Generous, and still rejects a picked photo.
    static let maxBytes = 64 * 1024

    static let defaultName = "recovery.pkarr"

    struct Picked {
        let name: String
        /// Base64, because that is what `decrypt_recovery_file` takes.
        let base64: String
    }

    /// Reads the file at [url] and hands it back Base64-encoded for the FFI.
    ///
    /// The URL is security-scoped when it comes from the document picker, so access is taken and
    /// released around the read. No spooling, unlike the deck import path: this is a couple of
    /// hundred bytes, and the size cap rejects anything that plainly is not one.
    static func read(_ url: URL) -> Result<Picked, Error> {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        do {
            let data = try Data(contentsOf: url)
            guard !data.isEmpty else { throw RecoveryFileError.empty }
            guard data.count <= maxBytes else { throw RecoveryFileError.tooLarge }
            return .success(Picked(name: url.lastPathComponent, base64: data.base64EncodedString()))
        } catch {
            return .failure(error)
        }
    }

    /// Writes [base64] to [url] **as raw bytes**, decoding first. See the note above.
    static func write(base64: String, to url: URL) -> Result<Void, Error> {
        guard let bytes = Data(base64Encoded: base64) else {
            return .failure(RecoveryFileError.notBase64)
        }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            try bytes.write(to: url, options: .atomic)
            return .success(())
        } catch {
            return .failure(error)
        }
    }
}

enum RecoveryFileError: Error {
    case empty
    case tooLarge
    /// The FFI handed back something that is not Base64 — a bug here, not bad input.
    case notBase64
}
