import Foundation
import Shared

extension KotlinByteArray {
    /// Copies a Kotlin `ByteArray` into `Data`.
    ///
    /// Kotlin bytes are signed (`Int8`); `Data` wants unsigned. Reinterpreting rather than
    /// converting keeps the bit pattern, which is what matters for image bytes.
    func toData() -> Data {
        var out = Data(capacity: Int(size))
        for index in 0..<size {
            out.append(UInt8(bitPattern: get(index: index)))
        }
        return out
    }
}

extension Data {
    /// Copies `Data` into a Kotlin `ByteArray`, reinterpreting unsigned bytes as signed.
    func toKotlinByteArray() -> KotlinByteArray {
        let out = KotlinByteArray(size: Int32(count))
        for (index, byte) in enumerated() {
            out.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return out
    }
}
