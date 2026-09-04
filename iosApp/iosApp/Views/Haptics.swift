import UIKit
import Shared

/// The study loop's haptics.
///
/// *When* to buzz is the shared ViewModel's decision — it is the only place that knows whether a
/// tap changed anything — so this only maps its vocabulary onto UIKit's generators. The system
/// honours the user's haptics setting, so nothing here needs to ask.
enum Haptics {
    private static let impact = UIImpactFeedbackGenerator(style: .light)
    private static let notification = UINotificationFeedbackGenerator()

    /// Compared rather than pattern-matched: a Kotlin enum crosses as an object with class
    /// properties, not as a Swift enum, so `case .tick` is not a case to match on.
    ///
    /// Main-thread only, like every UIKit generator — which is what the caller has: `IosFlowWatcher`
    /// delivers on the main dispatcher.
    static func play(_ pattern: StudyHaptic) {
        if pattern == StudyHaptic.success {
            notification.notificationOccurred(.success)
        } else if pattern == StudyHaptic.warning {
            notification.notificationOccurred(.warning)
        } else if pattern == StudyHaptic.failure {
            notification.notificationOccurred(.error)
        } else if pattern == StudyHaptic.tick {
            impact.impactOccurred()
        }
    }

    /// Warms the Taptic Engine so the first buzz of a session lands with the tap rather than a
    /// beat after it. Cheap, and it lapses on its own.
    static func prepare() {
        impact.prepare()
        notification.prepare()
    }
}
