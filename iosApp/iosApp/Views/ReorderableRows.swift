import SwiftUI
import UniformTypeIdentifiers

/// Drag-to-reorder for a plain `VStack` of rows.
///
/// A `List` with `.onMove` would be the one-liner, but the deck editor's card list lives inside a
/// scrolling column of form fields, and a `List` nested in a `ScrollView` has no intrinsic height —
/// it would need a hard-coded one, which a deck of unknown length does not have.
///
/// The move is reported **once, on drop**. Reordering live under the finger would send a write per
/// crossed row: `DeckRepository.moveCard` rewrites the chunks between the two positions, so a drag
/// across ten rows would be ten rewrites of the same records.
struct ReorderableVStack<Item: Identifiable, Row: View>: View {
    let items: [Item]
    var spacing: CGFloat = 10
    /// `(from, to)` as indices into `items`, matching the shared ViewModel's `onMoveCard`.
    var onMove: (Int, Int) -> Void
    @ViewBuilder var row: (Item) -> Row

    @State private var draggingId: Item.ID?

    var body: some View {
        VStack(spacing: spacing) {
            ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                row(item)
                    // Dimmed rather than removed: taking the row out of the stack while it is held
                    // collapses everything below it and the drop target moves under the finger.
                    .opacity(draggingId == item.id ? 0.35 : 1)
                    .onDrag {
                        draggingId = item.id
                        // The id, so a drop into another app carries something meaningful rather
                        // than an empty provider.
                        return NSItemProvider(object: String(describing: item.id) as NSString)
                    }
                    .onDrop(
                        of: [UTType.text],
                        delegate: RowDropDelegate(
                            targetIndex: index,
                            sourceIndex: { draggingId.flatMap { id in items.firstIndex { $0.id == id } } },
                            onMove: onMove,
                            onFinish: { draggingId = nil }
                        )
                    )
            }
        }
    }
}

/// Reports the move on drop and clears the drag either way — including a drop outside any row,
/// which would otherwise leave a row dimmed forever.
private struct RowDropDelegate: DropDelegate {
    let targetIndex: Int
    let sourceIndex: () -> Int?
    let onMove: (Int, Int) -> Void
    let onFinish: () -> Void

    func performDrop(info: DropInfo) -> Bool {
        defer { onFinish() }
        guard let from = sourceIndex(), from != targetIndex else { return false }
        onMove(from, targetIndex)
        return true
    }

    func dropExited(info: DropInfo) {}

    func validateDrop(info: DropInfo) -> Bool { sourceIndex() != nil }
}
