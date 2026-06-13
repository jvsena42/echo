import Foundation
import Shared

/// Swift implementation of the shared `RawPubkyClient` interface — a dumb pass-through to
/// the UniFFI-generated free functions in `pubkycore.swift` (backed by
/// `PubkyCore.xcframework`). Every method returns the FFI's native `[status, payload]`
/// array; all `Result` conversion and threading happens on the Kotlin side in
/// `IosPubkyClientAdapter` (see `shared/src/iosMain/.../data/pubky/`).
///
/// Binary payloads cross this boundary Base64-encoded and are decoded to raw `Data` here so
/// blobs land raw on the homeserver. `Eco.` prefixes disambiguate the module-level UniFFI
/// functions from the protocol methods of the same name.
final class IosPubkyClient: NSObject, RawPubkyClient {

    // MARK: - Keys & mnemonics

    func generateSecretKey() -> [String] { Eco.generateSecretKey() }

    func getPublicKeyFromSecretKey(secretKey: String) -> [String] {
        Eco.getPublicKeyFromSecretKey(secretKey: secretKey)
    }

    func generateMnemonicPhrase() -> [String] { Eco.generateMnemonicPhrase() }

    func generateMnemonicPhraseAndKeypair() -> [String] { Eco.generateMnemonicPhraseAndKeypair() }

    func mnemonicPhraseToKeypair(mnemonicPhrase: String) -> [String] {
        Eco.mnemonicPhraseToKeypair(mnemonicPhrase: mnemonicPhrase)
    }

    func validateMnemonicPhrase(mnemonicPhrase: String) -> [String] {
        Eco.validateMnemonicPhrase(mnemonicPhrase: mnemonicPhrase)
    }

    // MARK: - Recovery files

    func createRecoveryFile(secretKey: String, passphrase: String) -> [String] {
        Eco.createRecoveryFile(secretKey: secretKey, passphrase: passphrase)
    }

    func decryptRecoveryFile(recoveryFile: String, passphrase: String) -> [String] {
        Eco.decryptRecoveryFile(recoveryFile: recoveryFile, passphrase: passphrase)
    }

    // MARK: - Auth / sessions

    func signUp(secretKey: String, homeserver: String, signupToken: String?) -> [String] {
        Eco.signUp(secretKey: secretKey, homeserver: homeserver, signupToken: signupToken)
    }

    func getSignupToken(homeserverPubky: String, adminPassword: String) -> [String] {
        Eco.getSignupToken(homeserverPubky: homeserverPubky, adminPassword: adminPassword)
    }

    func signIn(secretKey: String) -> [String] { Eco.signIn(secretKey: secretKey) }

    func signOut(sessionSecret: String) -> [String] { Eco.signOut(sessionSecret: sessionSecret) }

    func revalidateSession(sessionSecret: String) -> [String] {
        Eco.revalidateSession(sessionSecret: sessionSecret)
    }

    func startAuthFlow(capabilities: String) -> [String] {
        Eco.startAuthFlow(capabilitiesStr: capabilities)
    }

    func awaitAuthApproval() -> [String] { Eco.awaitAuthApproval() }

    func parseAuthUrl(url: String) -> [String] { Eco.parseAuthUrl(url: url) }

    func auth(url: String, secretKey: String) -> [String] {
        Eco.auth(url: url, secretKey: secretKey)
    }

    // MARK: - Records (secret-key auth)

    func publish(recordName: String, recordContent: String, secretKey: String) -> [String] {
        Eco.publish(recordName: recordName, recordContent: recordContent, secretKey: secretKey)
    }

    func publishHttps(recordName: String, target: String, secretKey: String) -> [String] {
        Eco.publishHttps(recordName: recordName, target: target, secretKey: secretKey)
    }

    func put(url: String, content: String, secretKey: String) -> [String] {
        Eco.put(url: url, content: content, secretKey: secretKey)
    }

    func putBytesBase64(url: String, contentBase64: String, secretKey: String) -> [String] {
        guard let data = Data(base64Encoded: contentBase64) else {
            return ["true", "Invalid Base64 payload"]
        }
        return Eco.putBytes(url: url, content: data, secretKey: secretKey)
    }

    func get(url: String) -> [String] { Eco.get(url: url) }

    func getBytes(url: String) -> [String] { Eco.getBytes(url: url) }

    func list(
        url: String,
        cursor: String?,
        reverse: KotlinBoolean?,
        limit: KotlinInt?,
        shallow: KotlinBoolean?
    ) -> [String] {
        Eco.list(
            url: url,
            cursor: cursor,
            reverse: reverse?.boolValue,
            limit: limit.map { UInt16(truncating: $0) },
            shallow: shallow?.boolValue
        )
    }

    func deleteFile(url: String, secretKey: String) -> [String] {
        Eco.deleteFile(url: url, secretKey: secretKey)
    }

    func republishHomeserver(secretKey: String, homeserver: String) -> [String] {
        Eco.republishHomeserver(secretKey: secretKey, homeserver: homeserver)
    }

    // MARK: - Records (session auth)

    func putWithSession(url: String, content: String, sessionSecret: String) -> [String] {
        Eco.putWithSession(url: url, content: content, sessionSecret: sessionSecret)
    }

    func putBytesBase64WithSession(
        url: String,
        contentBase64: String,
        sessionSecret: String
    ) -> [String] {
        guard let data = Data(base64Encoded: contentBase64) else {
            return ["true", "Invalid Base64 payload"]
        }
        return Eco.putBytesWithSession(url: url, content: data, sessionSecret: sessionSecret)
    }

    func deleteWithSession(url: String, sessionSecret: String) -> [String] {
        Eco.deleteWithSession(url: url, sessionSecret: sessionSecret)
    }

    // MARK: - pubky-app-specs helpers

    func createTagId(uri: String, label: String) -> [String] {
        Eco.createTagId(uri: uri, label: label)
    }

    // MARK: - DHT resolution

    func resolve(publicKey: String) -> [String] { Eco.resolve(publicKey: publicKey) }

    func resolveHttps(publicKey: String) -> [String] { Eco.resolveHttps(publicKey: publicKey) }

    func getHomeserver(pubky: String) -> [String] { Eco.getHomeserver(pubky: pubky) }

    // MARK: - Network

    func switchNetwork(useTestnet: Bool) -> [String] { Eco.switchNetwork(useTestnet: useTestnet) }
}
