import SwiftUI

/// Liquid Glass, applied the two ways Loopky actually needs it.
///
/// Most of the glass in the app costs nothing to adopt: a `TabView`, a `List`, a sheet and a
/// navigation bar all render as Liquid Glass simply because the app is built against the iOS 26
/// SDK. What is left over is Loopky's own floating chrome — the circular back/share buttons a
/// screen draws over its own scroll view, and the study CTA that hovers above the card list —
/// which are hand-built shapes and therefore have to ask.
///
/// Everything here is availability-gated rather than gating the whole app: the deployment target is
/// iOS 18.2, and on 18 the same surfaces fall back to the opaque card fill they had before. The
/// fallback is a parameter rather than a constant because the two call sites want different tints.
extension View {
    /// A floating control that sits over content and should pick up what is behind it.
    ///
    /// `.interactive()` is what makes it respond to touch — the glass flexes and brightens under a
    /// finger, which is the whole reason a control gets glass rather than a plain material.
    @ViewBuilder
    func loopkyGlass<S: Shape>(in shape: S, fallback: Color = LoopkyColor.surfaceCard) -> some View {
        if #available(iOS 26.0, *) {
            glassEffect(.regular.interactive(), in: shape)
        } else {
            background(shape.fill(fallback))
        }
    }

    /// Groups sibling glass shapes so they merge and separate as one piece of material instead of
    /// each sampling the background on its own. A no-op before iOS 26.
    @ViewBuilder
    func loopkyGlassGroup(spacing: CGFloat = 8) -> some View {
        if #available(iOS 26.0, *) {
            GlassEffectContainer(spacing: spacing) { self }
        } else {
            self
        }
    }
}
