import SwiftUI
import Shared

@main
struct iOSApp: App {
    init() {
        // Swift hands the dumb [status, payload] FFI pass-through to Kotlin; the shared
        // layer wraps it into the PubkyClient contract (IosPubkyClientAdapter).
        PlatformModule_iosKt.doInitKoin(rawPubkyClient: IosPubkyClient())
    }

    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}
