import Foundation
import Shared

/// Swift implementation of the shared `RawPubkyClient` interface — a dumb pass-through to
/// the UniFFI-generated free functions in `pubkycore.swift` (backed by
/// `PubkyCore.xcframework`). Every method returns the FFI's native `[status, payload]`
/// array; all `Result` conversion and threading happens on the Kotlin side in
/// `IosPubkyClientAdapter` (see `shared/src/iosMain/.../data/pubky/`).
///
/// Binary payloads cross this boundary Base64-encoded and are decoded to raw `Data` here so
/// blobs land raw on the homeserver. `Echo.` prefixes disambiguate the module-level UniFFI
/// functions from the protocol methods of the same name.
final class IosPubkyClient: NSObject, RawPubkyClient {

    // MARK: - Keys & mnemonics

    func generateSecretKey() -> [String] { Echo.generateSecretKey() }

    func getPublicKeyFromSecretKey(secretKey: String) -> [String] {
        Echo.getPublicKeyFromSecretKey(secretKey: secretKey)
    }

    func generateMnemonicPhrase() -> [String] { Echo.generateMnemonicPhrase() }

    func generateMnemonicPhraseAndKeypair() -> [String] { Echo.generateMnemonicPhraseAndKeypair() }

    func mnemonicPhraseToKeypair(mnemonicPhrase: String) -> [String] {
        Echo.mnemonicPhraseToKeypair(mnemonicPhrase: mnemonicPhrase)
    }

    func validateMnemonicPhrase(mnemonicPhrase: String) -> [String] {
        Echo.validateMnemonicPhrase(mnemonicPhrase: mnemonicPhrase)
    }

    // MARK: - Recovery files

    func createRecoveryFile(secretKey: String, passphrase: String) -> [String] {
        Echo.createRecoveryFile(secretKey: secretKey, passphrase: passphrase)
    }

    func decryptRecoveryFile(recoveryFile: String, passphrase: String) -> [String] {
        Echo.decryptRecoveryFile(recoveryFile: recoveryFile, passphrase: passphrase)
    }

    // MARK: - Auth / sessions

    func signUp(secretKey: String, homeserver: String, signupToken: String?) -> [String] {
        Echo.signUp(secretKey: secretKey, homeserver: homeserver, signupToken: signupToken)
    }

    func getSignupToken(homeserverPubky: String, adminPassword: String) -> [String] {
        Echo.getSignupToken(homeserverPubky: homeserverPubky, adminPassword: adminPassword)
    }

    func signIn(secretKey: String) -> [String] { Echo.signIn(secretKey: secretKey) }

    func signOut(sessionSecret: String) -> [String] { Echo.signOut(sessionSecret: sessionSecret) }

    func revalidateSession(sessionSecret: String) -> [String] {
        Echo.revalidateSession(sessionSecret: sessionSecret)
    }

    func startAuthFlow(capabilities: String) -> [String] {
        Echo.startAuthFlow(capabilitiesStr: capabilities)
    }

    func awaitAuthApproval() -> [String] { Echo.awaitAuthApproval() }

    func parseAuthUrl(url: String) -> [String] { Echo.parseAuthUrl(url: url) }

    func auth(url: String, secretKey: String) -> [String] {
        Echo.auth(url: url, secretKey: secretKey)
    }

    // MARK: - Records (secret-key auth)

    func publish(recordName: String, recordContent: String, secretKey: String) -> [String] {
        Echo.publish(recordName: recordName, recordContent: recordContent, secretKey: secretKey)
    }

    func publishHttps(recordName: String, target: String, secretKey: String) -> [String] {
        Echo.publishHttps(recordName: recordName, target: target, secretKey: secretKey)
    }

    func put(url: String, content: String, secretKey: String) -> [String] {
        Echo.put(url: url, content: content, secretKey: secretKey)
    }

    func putBytesBase64(url: String, contentBase64: String, secretKey: String) -> [String] {
        guard let data = Data(base64Encoded: contentBase64) else {
            return ["true", "Invalid Base64 payload"]
        }
        return Echo.putBytes(url: url, content: data, secretKey: secretKey)
    }

    func get(url: String) -> [String] { Echo.get(url: url) }

    func getBytes(url: String) -> [String] { Echo.getBytes(url: url) }

    func list(
        url: String,
        cursor: String?,
        reverse: KotlinBoolean?,
        limit: KotlinInt?,
        shallow: KotlinBoolean?
    ) -> [String] {
        Echo.list(
            url: url,
            cursor: cursor,
            reverse: reverse?.boolValue,
            limit: limit.map { UInt16(truncating: $0) },
            shallow: shallow?.boolValue
        )
    }

    func deleteFile(url: String, secretKey: String) -> [String] {
        Echo.deleteFile(url: url, secretKey: secretKey)
    }

    func republishHomeserver(secretKey: String, homeserver: String) -> [String] {
        Echo.republishHomeserver(secretKey: secretKey, homeserver: homeserver)
    }

    // MARK: - Records (session auth)

    func putWithSession(url: String, content: String, sessionSecret: String) -> [String] {
        Echo.putWithSession(url: url, content: content, sessionSecret: sessionSecret)
    }

    func putBytesBase64WithSession(
        url: String,
        contentBase64: String,
        sessionSecret: String
    ) -> [String] {
        guard let data = Data(base64Encoded: contentBase64) else {
            return ["true", "Invalid Base64 payload"]
        }
        return Echo.putBytesWithSession(url: url, content: data, sessionSecret: sessionSecret)
    }

    func deleteWithSession(url: String, sessionSecret: String) -> [String] {
        Echo.deleteWithSession(url: url, sessionSecret: sessionSecret)
    }

    // MARK: - pubky-app-specs helpers

    func createTagId(uri: String, label: String) -> [String] {
        Echo.createTagId(uri: uri, label: label)
    }

    // MARK: - DHT resolution

    func resolve(publicKey: String) -> [String] { Echo.resolve(publicKey: publicKey) }

    func resolveHttps(publicKey: String) -> [String] { Echo.resolveHttps(publicKey: publicKey) }

    func getHomeserver(pubky: String) -> [String] { Echo.getHomeserver(pubky: pubky) }

    // MARK: - Network

    func switchNetwork(useTestnet: Bool) -> [String] { Echo.switchNetwork(useTestnet: useTestnet) }
}
