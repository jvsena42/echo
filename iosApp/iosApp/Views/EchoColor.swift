import SwiftUI

/// Echo design tokens. Values mirror `EchoColors.kt` on Android. Will be replaced by
/// codegen from the Pencil `.pen` variables once that pipeline exists.
enum EchoColor {
    static let surfacePrimary = Color(red: 1.0, green: 0.984, blue: 0.961)
    static let surfaceSecondary = Color(red: 1.0, green: 0.957, blue: 0.918)
    static let surfaceCard = Color.white
    static let accentPrimary = Color(red: 1.0, green: 0.361, blue: 0.0)
    static let accentPrimarySoft = Color(red: 1.0, green: 0.910, blue: 0.839)
    static let accentSecondary = Color(red: 0.478, green: 0.298, blue: 1.0)
    static let accentSecondarySoft = Color(red: 0.914, green: 0.878, blue: 1.0)
    static let foregroundPrimary = Color(red: 0.106, green: 0.106, blue: 0.122)
    static let foregroundSecondary = Color(red: 0.353, green: 0.353, blue: 0.400)
    static let foregroundMuted = Color(red: 0.545, green: 0.545, blue: 0.600)
    static let foregroundOnAccent = Color.white
    static let borderSubtle = Color(red: 0.941, green: 0.902, blue: 0.851)
    static let srsGood = Color(red: 0.129, green: 0.788, blue: 0.478)
    static let srsAgain = Color(red: 1.0, green: 0.306, blue: 0.392)
    static let danger = Color(red: 0.851, green: 0.173, blue: 0.173)
    static let dangerSoft = Color(red: 1.0, green: 0.306, blue: 0.392).opacity(0.08)
}
