import SwiftUI

@main
struct iOSApp: App {
    init() {
        // TODO: once the Shared framework is built and `IosPubkyClient` conforms to the
        // generated `PubkyClient` protocol, bootstrap Koin here:
        //
        //   PlatformModule_iosKt.doInitKoin(pubkyClient: IosPubkyClient())
        //
        // The onboarding flow's real VM-driven behaviour unlocks at that point.
    }

    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}
