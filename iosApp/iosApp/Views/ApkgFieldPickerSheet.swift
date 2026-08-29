import SwiftUI
import Shared

/// Choose which two of an Anki note type's fields become the card's front and back.
///
/// Each field is shown with a real value beside its name, because Anki decks routinely carry
/// fields called "Field 3" or nothing at all — a name alone makes the choice guesswork.
struct ApkgFieldPickerSheet: View {
    let fields: ApkgFields
    var onPick: (ApkgFieldMapping) -> Void
    var onClose: () -> Void

    @State private var front: Int
    @State private var back: Int

    init(fields: ApkgFields, onPick: @escaping (ApkgFieldMapping) -> Void, onClose: @escaping () -> Void) {
        self.fields = fields
        self.onPick = onPick
        self.onClose = onClose
        _front = State(initialValue: Int(fields.mapping.frontOrd))
        _back = State(initialValue: Int(fields.mapping.backOrd))
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    picker("bulk_fields_sheet_front", selection: $front)
                    picker("bulk_fields_sheet_back", selection: $back)
                } footer: {
                    Text("bulk_fields_sheet_hint")
                }
            }
            .navigationTitle(Text("bulk_fields"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("bulk_fields_sheet_cancel", action: onClose)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("image_sheet_done") {
                        onPick(ApkgFieldMapping(frontOrd: Int32(front), backOrd: Int32(back)))
                        onClose()
                    }
                    // The same field on both sides makes a card that answers itself.
                    .disabled(front == back)
                }
            }
        }
    }

    private func picker(_ label: LocalizedStringKey, selection: Binding<Int>) -> some View {
        Picker(label, selection: selection) {
            ForEach(fields.names.indices, id: \.self) { index in
                VStack(alignment: .leading, spacing: 1) {
                    Text(name(at: index))
                    if let sample = fields.samples.indices.contains(index)
                        ? fields.samples[index] : nil, !sample.isEmpty {
                        Text(sample).font(.caption).foregroundStyle(LoopkyColor.foregroundMuted)
                    }
                }
                .tag(index)
            }
        }
    }

    private func name(at index: Int) -> String {
        let raw = fields.names.indices.contains(index) ? fields.names[index] : ""
        return raw.isEmpty ? NSLocalizedString("bulk_fields_unnamed", comment: "") : raw
    }
}
