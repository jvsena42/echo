import Foundation
import Shared

/// Small bridging helpers for values that cross the Kotlin/Native ObjC boundary
/// in awkward shapes (Kotlin `Char` → `unichar`, `value class Tag` → opaque `id`).
enum KotlinInterop {
    /// Kotlin `Char` arrives as `unichar` (UInt16). Converts to a one-character String.
    static func charToString(_ value: unichar) -> String {
        UnicodeScalar(value).map { String(Character($0)) } ?? "?"
    }

    /// `Tag` is a Kotlin value class, so it crosses the bridge as an opaque `Any`.
    /// Extracts a displayable label without assuming the boxed representation.
    static func tagLabel(_ tag: Any) -> String {
        if let string = tag as? String { return string }
        let described = String(describing: tag)
        if let range = described.range(of: "Tag(value="), described.hasSuffix(")") {
            return String(described[range.upperBound..<described.index(before: described.endIndex)])
        }
        return described
    }

    /// Human label for the parser's detected separator.
    static func separatorLabel(_ separator: Separator?) -> String {
        switch separator {
        case is Separator.EmDash: return "em-dash"
        case is Separator.Tab: return "tab"
        case is Separator.Colon: return "colon"
        case is Separator.Semicolon: return "semicolon"
        case is Separator.Comma: return "comma"
        case is Separator.Pipe: return "pipe"
        case is Separator.MarkdownTable: return "markdown"
        case is Separator.BlankLine: return "blank line"
        case is Separator.SingleColumn: return "single column"
        case is Separator.Custom: return "custom"
        default: return "auto"
        }
    }
}
