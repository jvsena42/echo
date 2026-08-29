package com.github.jvsena42.loopky.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Swift-facing helper to observe a Kotlin [Flow] without SKIE (Kotlin 2.3.x predates SKIE
 * support — see Architecture.md §9.2). Generics are erased across the ObjC
 * bridge, so Swift receives values as `Any` and casts to the concrete `UiState`/`Effect`
 * type exported by the framework.
 *
 * Usage from Swift:
 * ```swift
 * let watcher = IosFlowWatcher(flow: viewModel.state)
 * watcher.watch { state in self.state = state as! HomeUiState }
 * // on disappear:
 * watcher.close()
 * ```
 * Callbacks land on the main dispatcher, safe to assign to `@Published` properties.
 */
class IosFlowWatcher(private val flow: Flow<Any?>) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun watch(onEach: (Any?) -> Unit) {
        scope.launch {
            flow.collect { onEach(it) }
        }
    }

    fun close() {
        scope.cancel()
    }
}
