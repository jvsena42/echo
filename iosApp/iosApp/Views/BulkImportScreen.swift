import SwiftUI
import Shared
import UniformTypeIdentifiers

/// Import a deck from a file: Anki's `.apkg`, or plain text (`.txt` / `.csv`).
///
/// The picked file is handed to the shared `BulkImportViewModel`, which routes it — text is parsed
/// by the same paste parser, `.apkg` by `ApkgReader`, which on iOS reads the zip with the system
/// zlib and the collection through a SQLite cinterop.
struct BulkImportScreen: View {
    @Environment(\.openURL) private var openURL

    var onCancel: () -> Void = {}
    var onContinue: () -> Void = {}

    @State private var viewModel: BulkImportViewModel?
    @State private var uiState: Any?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?
    @State private var isPicking = false
    @State private var isChoosingFields = false

    var body: some View {
        BulkImportView(
            state: viewState,
            onPickFile: { isPicking = true },
            onPickAnother: { viewModel?.onPickAnother() },
            onChooseFields: { isChoosingFields = true },
            onConfirm: { viewModel?.onConfirm() },
            onCancel: { viewModel?.onCancel() },
            onBrowseSharedDecks: {
                if let url = URL(string: ankiWebSharedDecksUrl) { openURL(url) }
            }
        )
        .fileImporter(
            isPresented: $isPicking,
            // `.item` rather than a narrow list: an `.apkg` has no registered UTType, and Files
            // reports one as `public.data` or `public.zip-archive` depending on where it came
            // from. The reader sniffs the magic bytes anyway, so the picker stays permissive and
            // the *content* decides — which is also what tells "you picked a photo" apart from
            // "this file would not open".
            allowedContentTypes: [.item],
            allowsMultipleSelection: false
        ) { result in
            handle(result)
        }
        .sheet(isPresented: $isChoosingFields) {
            if let fields = readyState?.fields {
                ApkgFieldPickerSheet(
                    fields: fields,
                    onPick: { viewModel?.onFieldMappingChanged(mapping: $0) },
                    onClose: { isChoosingFields = false }
                )
            }
        }
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    private var readyState: BulkImportUiStateReady? { uiState as? BulkImportUiStateReady }

    private var viewState: BulkImportViewState {
        if uiState is BulkImportUiStateReading { return BulkImportViewState(phase: .reading) }
        if let parsing = uiState as? BulkImportUiStateParsing {
            return BulkImportViewState(phase: .parsing, fileName: parsing.fileName)
        }
        if let error = uiState as? BulkImportUiStateError {
            return BulkImportViewState(
                phase: .failed,
                errorTitle: Self.errorTitle(error.reason),
                errorMessage: Self.errorMessage(error.reason)
            )
        }
        guard let ready = readyState else { return BulkImportViewState() }
        return BulkImportViewState(
            phase: .ready,
            fileName: ready.fileName,
            separatorLabel: KotlinInterop.separatorLabel(ready.separator),
            cardCount: Int(ready.cardCount),
            skippedCount: Int(ready.skippedCount),
            duplicatesCollapsed: Int(ready.duplicatesCollapsed),
            truncatedCount: Int(ready.truncatedCount),
            droppedNoteCount: Int(ready.droppedNoteCount),
            imagesSkippedCount: Int(ready.imagesSkippedCount),
            hasFields: ready.fields != nil,
            canImport: ready.canImport,
            sample: ready.sample.map {
                BulkSampleCard(front: $0.front, back: $0.back,
                               hasFrontImage: $0.hasFrontImage, hasBackImage: $0.hasBackImage)
            }
        )
    }

    /// A security-scoped URL: the picker grants access to the file, and it has to be released.
    private func handle(_ result: Result<[URL], Error>) {
        guard let url = try? result.get().first else {
            viewModel?.onFileReadFailed(reason: BulkImportError.unreadable)
            return
        }
        viewModel?.onFileReadStarted()
        guard url.startAccessingSecurityScopedResource() else {
            viewModel?.onFileReadFailed(reason: BulkImportError.unreadable)
            return
        }
        defer { url.stopAccessingSecurityScopedResource() }

        // Size first, before any read: the check exists precisely because reading is what costs.
        let declaredSize = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        if declaredSize > maxImportFileBytes {
            viewModel?.onFileReadFailed(reason: BulkImportError.toolarge)
            return
        }

        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe) else {
            viewModel?.onFileReadFailed(reason: BulkImportError.unreadable)
            return
        }
        // The declared size can be absent or wrong; the real length is the one that binds.
        if data.count > maxImportFileBytes {
            viewModel?.onFileReadFailed(reason: BulkImportError.toolarge)
            return
        }
        let name = url.lastPathComponent

        // The magic bytes decide, not the extension: an `.apkg` shared from a chat app routinely
        // arrives named `.zip` or with no extension at all.
        if ApkgReader.shared.canRead(header: Data(data.prefix(zipMagicByteCount)).toKotlinByteArray()) {
            // The reader opens a path, so hand it a copy the sandbox will still let us read once
            // the scoped access above is released.
            guard let copied = copyToTemp(data, named: name) else {
                viewModel?.onFileReadFailed(reason: BulkImportError.unreadable)
                return
            }
            viewModel?.onApkgLoaded(fileName: name, path: copied)
            return
        }

        guard let text = String(data: data, encoding: .utf8) else {
            viewModel?.onFileReadFailed(reason: BulkImportError.nottext)
            return
        }
        viewModel?.onFileLoaded(fileName: name, text: text)
    }

    private func copyToTemp(_ data: Data, named name: String) -> String? {
        let path = NSTemporaryDirectory() + "loopky-import-" + name
        return (try? data.write(to: URL(fileURLWithPath: path))) == nil ? nil : path
    }

    private static func errorTitle(_ reason: BulkImportError) -> LocalizedStringKey {
        switch reason {
        case BulkImportError.toolarge: return "bulk_error_too_large_title"
        case BulkImportError.nottext: return "bulk_error_not_text_title"
        case BulkImportError.unsupportedapkg: return "bulk_error_unsupported_apkg_title"
        case BulkImportError.legacystubonly: return "bulk_error_legacy_stub_title"
        case BulkImportError.nocardsfound: return "bulk_error_no_cards_title"
        case BulkImportError.unreadable: return "bulk_error_unreadable_title"
        default: return "bulk_error_unknown_title"
        }
    }

    private static func errorMessage(_ reason: BulkImportError) -> LocalizedStringKey {
        switch reason {
        case BulkImportError.toolarge: return "bulk_error_too_large_message"
        case BulkImportError.nottext: return "bulk_error_not_text_message"
        case BulkImportError.unsupportedapkg: return "bulk_error_unsupported_apkg_message"
        case BulkImportError.legacystubonly: return "bulk_error_legacy_stub_message"
        case BulkImportError.nocardsfound: return "bulk_error_no_cards_message"
        case BulkImportError.unreadable: return "bulk_error_unreadable_message"
        default: return "bulk_error_unknown_message"
        }
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.bulkImportViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case is BulkImportEffectContinue: onContinue()
            case is BulkImportEffectNavigateBack: onCancel()
            default: break
            }
        }
    }

    private func detach() {
        if let viewModel { IosDependencies.shared.clear(viewModel: viewModel) }
        viewModel = nil
        stateSink = nil
        effectSink = nil
    }
}

/// Enough of the file for `ApkgReader.canRead` to recognise a zip signature.
private let zipMagicByteCount = 4

/// Ceiling on a picked file, mirroring Android's `MAX_IMPORT_FILE_BYTES`.
///
/// Kept in the UI layer on both platforms, as Android does — the reader's own per-entry limits
/// (`ApkgLimits`) bound what an entry *inflates* to, which is a different question from how big a
/// file a user may hand over. Without this the whole file is read into memory before anything
/// looks at it, so the guard has to come first.
let maxImportFileBytes = 500 * 1024 * 1024

/// Where someone with no deck yet can find one.
private let ankiWebSharedDecksUrl = "https://ankiweb.net/shared/decks"

enum BulkImportPhase { case idle, reading, parsing, ready, failed }

struct BulkSampleCard: Identifiable {
    let front: String
    let back: String
    var hasFrontImage = false
    var hasBackImage = false
    var id: String { "\(front)\u{1F}\(back)" }
}

struct BulkImportViewState {
    var phase: BulkImportPhase = .idle
    var fileName: String = ""
    var separatorLabel: String = ""
    var cardCount: Int = 0
    var skippedCount: Int = 0
    var duplicatesCollapsed: Int = 0
    var truncatedCount: Int = 0
    var droppedNoteCount: Int = 0
    var imagesSkippedCount: Int = 0
    var hasFields: Bool = false
    var canImport: Bool = false
    var errorTitle: LocalizedStringKey?
    var errorMessage: LocalizedStringKey?
    var sample: [BulkSampleCard] = []
}
