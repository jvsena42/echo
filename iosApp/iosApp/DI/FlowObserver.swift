import Combine
import Shared

/// Observes a Kotlin `Flow`/`StateFlow` from the Shared framework and republishes values
/// into SwiftUI. Built on the Kotlin-side `IosFlowWatcher` (no SKIE — Kotlin 2.3.x predates
/// SKIE support), whose callbacks always land on the main dispatcher.
///
/// Generics are erased across the ObjC bridge, so values arrive as `Any` and are cast to the
/// concrete exported type:
/// ```swift
/// @StateObject var state: FlowObserver<OnboardingUiState>
/// init() {
///     let vm = IosDependencies.shared.onboardingViewModel()
///     _state = StateObject(wrappedValue: FlowObserver(vm.state))
/// }
/// ```
final class FlowObserver<T: AnyObject>: ObservableObject {
    @Published private(set) var value: T?

    private let watcher: IosFlowWatcher

    init(_ flow: Kotlinx_coroutines_coreFlow) {
        watcher = IosFlowWatcher(flow: flow)
        watcher.watch { [weak self] anyValue in
            self?.value = anyValue as? T
        }
    }

    deinit {
        watcher.close()
    }
}

/// Effect-stream variant: invokes a handler per emission instead of retaining a value.
/// Keep a strong reference for the lifetime of the screen (e.g. `@State`).
final class FlowEffectSink {
    private let watcher: IosFlowWatcher

    init(_ flow: Kotlinx_coroutines_coreFlow, onEach: @escaping (Any?) -> Void) {
        watcher = IosFlowWatcher(flow: flow)
        watcher.watch { onEach($0) }
    }

    deinit {
        watcher.close()
    }
}
