import Shared

extension Lifecycle_viewmodelViewModel {

    /// Release this ViewModel — the whole of what androidx's `clear()` does, in its order.
    ///
    /// Two halves, split across the language boundary because neither side can do both.
    /// `IosDependencies.clear` cancels `viewModelScope`, which Kotlin can do and Swift cannot.
    /// `onCleared()` is `protected` in Kotlin — so Kotlin cannot call it either — but Objective-C
    /// export ignores Kotlin visibility, so Swift can.
    ///
    /// Skipping the callback is not cosmetic. `UnregisteredKeyViewModel` drops an orphaned key
    /// from the keystore in `onCleared` — one written before anyone agreed to anything, that
    /// nothing else removes, and that would otherwise offer its recovery phrase to whoever signs
    /// in next. Any future `onCleared` would be lost the same silent way.
    func release() {
        IosDependencies.shared.clear(viewModel: self)
        onCleared()
    }
}
