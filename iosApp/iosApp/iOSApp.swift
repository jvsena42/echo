import SwiftUI
import Shared

/// The Pubky Nexus indexer, per configuration. Staging and production index separate networks,
/// so a release build reading staging would show trending tags and search results that no
/// production user ever published (#42).
private enum NexusEnvironment {
    #if DEBUG
    static let baseUrl = "https://nexus.staging.pubky.app"
    #else
    static let baseUrl = "https://nexus.pubky.app"
    #endif
}

@main
struct iOSApp: App {
    init() {
        // Swift hands the dumb [status, payload] FFI pass-through to Kotlin; the shared
        // layer wraps it into the PubkyClient contract (IosPubkyClientAdapter).
        PlatformModule_iosKt.doInitKoin(
            rawPubkyClient: IosPubkyClient(),
            nexusBaseUrl: NexusEnvironment.baseUrl,
            // iOS ships no build-time Unsplash key, so web image search asks the user for one.
            // Their key is kept in the Keychain (IosUnsplashKeyStore), not here.
            unsplashFallbackKey: ""
        )
    }

    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}
