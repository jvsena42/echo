import SwiftUI

/// Echo design tokens. Values mirror `EchoColors.kt` on Android. Will be replaced by
/// codegen from the Pencil `.pen` variables once that pipeline exists.
enum EchoColor {
    static let surfacePrimary = Color(red: 1.0, green: 0.984, blue: 0.961)
    static let surfaceCard = Color.white
    static let accentPrimary = Color(red: 1.0, green: 0.361, blue: 0.0)
    static let accentPrimarySoft = Color(red: 1.0, green: 0.910, blue: 0.839)
    static let accentSecondary = Color(red: 0.478, green: 0.298, blue: 1.0)
    static let foregroundPrimary = Color(red: 0.106, green: 0.106, blue: 0.122)
    static let foregroundSecondary = Color(red: 0.353, green: 0.353, blue: 0.400)
    static let foregroundMuted = Color(red: 0.545, green: 0.545, blue: 0.600)
}
