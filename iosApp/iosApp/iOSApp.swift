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

/// Which Homegate mints signup tokens, and which homeserver those tokens are valid on. Matched to
/// the Nexus environment above so one build never talks to two different networks.
private enum PubkyEnv {
    #if DEBUG
    static let name = "Staging"
    #else
    static let name = "Production"
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
            unsplashFallbackKey: "",
            pubkyEnvironmentName: PubkyEnv.name
        )
    }

    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}
