import SwiftUI

/// How wide Loopky lets a block of content grow before it stops using the extra room.
///
/// The same three ceilings Android's `PaneWidth` uses, in points, and named for what the content
/// *is* rather than which screen it is on — so they apply at every width and simply never bite on a
/// phone, where the window is narrower than all of them.
///
/// A phone layout stretched to an iPad is the default failure, not an edge case: nothing about it
/// raises an error. It compiles, it runs, and it puts a row's label at one edge of a 1366pt window
/// and its control at the other.
enum PaneWidth {
    /// A single column of prose, form fields or settings rows. Roughly a 70-character measure.
    static let reading: CGFloat = 680

    /// A focused, self-contained task: onboarding, a study card, a confirmation.
    static let focused: CGFloat = 520

    /// Grids and tile walls, which genuinely do get better with more room — but not unbounded.
    static let wide: CGFloat = 1160
}

extension View {
    /// Caps this content's width at [max] and centres it in the space available.
    ///
    /// Two frames, in this order: the inner one is the ceiling, the outer one claims the rest of
    /// the width so the centring has something to centre *in*. A bare `.frame(maxWidth: max)` sizes
    /// the view but leaves a `VStack(alignment: .leading)` above it hugging the leading edge.
    ///
    /// Where a screen paints its own background, this goes **after** the background and scroll
    /// modifiers — before them it constrains the surface too, and the cream stops reaching the
    /// edges of the display.
    func contentPane(_ max: CGFloat = PaneWidth.reading) -> some View {
        frame(maxWidth: max).frame(maxWidth: .infinity)
    }
}

/// How many columns a deck grid should use at the current width.
///
/// Two columns is right on a phone and wrong on a landscape iPad, where it produces 550pt-wide
/// tiles with a cover the size of a paperback. Counting columns from the width — rather than from
/// "is this an iPad" — is also what keeps a Split View pane honest.
func deckGridColumns(_ widthClass: LoopkyWidthClass) -> Int {
    switch widthClass {
    case .compact: return 2
    case .medium: return 3
    case .expanded: return 4
    }
}

/// A deck-tile grid's `GridItem`s at the current width, with Loopky's standard 14pt gutter.
func deckGridItems(_ widthClass: LoopkyWidthClass, spacing: CGFloat = 14) -> [GridItem] {
    Array(
        repeating: GridItem(.flexible(), spacing: spacing),
        count: deckGridColumns(widthClass)
    )
}
