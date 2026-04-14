import Foundation
// The Shared framework (KMP) exposes `Data_pubkyPubkyClient` as the Kotlin interface.
// After the first Kotlin build, import Shared and conform this class to that protocol.
// import Shared

/// Swift implementation of the shared `PubkyClient` interface. Delegates to the
/// UniFFI-generated Swift bindings in `pubkycore.swift`, backed by `PubkyCore.xcframework`
/// (both under `iosApp/iosApp/Frameworks` / `iosApp/iosApp/Pubky`).
///
/// The UniFFI surface returns `[String]` of shape `[status, payload]`. Each helper below
/// converts that into a Swift `Result<String, Error>`.
///
/// Wire this into Koin at app start from `iOSAppApp.swift` so shared repositories can
/// resolve `PubkyClient` the same way they do on Android.
final class IosPubkyClient /*: Data_pubkyPubkyClient*/ {

    struct PubkyError: Error { let message: String }

    // MARK: - Keys & mnemonics

    func generateSecretKey() -> Result<String, Error> {
        run { try pubkycore.generateSecretKey() }
    }

    func getPublicKeyFromSecretKey(secretKey: String) -> Result<String, Error> {
        run { try pubkycore.getPublicKeyFromSecretKey(secretKey: secretKey) }
    }

    func generateMnemonicPhrase() -> Result<String, Error> {
        run { try pubkycore.generateMnemonicPhrase() }
    }

    func generateMnemonicPhraseAndKeypair() -> Result<String, Error> {
        run { try pubkycore.generateMnemonicPhraseAndKeypair() }
    }

    func mnemonicPhraseToKeypair(mnemonicPhrase: String) -> Result<String, Error> {
        run { try pubkycore.mnemonicPhraseToKeypair(mnemonicPhrase: mnemonicPhrase) }
    }

    func validateMnemonicPhrase(mnemonicPhrase: String) -> Result<String, Error> {
        run { try pubkycore.validateMnemonicPhrase(mnemonicPhrase: mnemonicPhrase) }
    }

    // MARK: - Recovery files

    func createRecoveryFile(secretKey: String, passphrase: String) -> Result<String, Error> {
        run { try pubkycore.createRecoveryFile(secretKey: secretKey, passphrase: passphrase) }
    }

    func decryptRecoveryFile(recoveryFile: String, passphrase: String) -> Result<String, Error> {
        run { try pubkycore.decryptRecoveryFile(recoveryFile: recoveryFile, passphrase: passphrase) }
    }

    // MARK: - Auth / sessions

    func signUp(secretKey: String, homeserver: String, signupToken: String?) async -> Result<String, Error> {
        run { try pubkycore.signUp(secretKey: secretKey, homeserver: homeserver, signupToken: signupToken) }
    }

    func getSignupToken(homeserverPubky: String, adminPassword: String) async -> Result<String, Error> {
        run { try pubkycore.getSignupToken(homeserverPubky: homeserverPubky, adminPassword: adminPassword) }
    }

    func signIn(secretKey: String) async -> Result<String, Error> {
        run { try pubkycore.signIn(secretKey: secretKey) }
    }

    func signOut(sessionSecret: String) async -> Result<String, Error> {
        run { try pubkycore.signOut(sessionSecret: sessionSecret) }
    }

    func revalidateSession(sessionSecret: String) async -> Result<String, Error> {
        run { try pubkycore.revalidateSession(sessionSecret: sessionSecret) }
    }

    func startAuthFlow(capabilities: String) async -> Result<String, Error> {
        run { try pubkycore.startAuthFlow(capabilitiesStr: capabilities) }
    }

    func awaitAuthApproval() async -> Result<String, Error> {
        run { try pubkycore.awaitAuthApproval() }
    }

    func parseAuthUrl(url: String) -> Result<String, Error> {
        run { try pubkycore.parseAuthUrl(url: url) }
    }

    func auth(url: String, secretKey: String) async -> Result<String, Error> {
        run { try pubkycore.auth(url: url, secretKey: secretKey) }
    }

    // MARK: - Records (secret-key auth)

    func publish(recordName: String, recordContent: String, secretKey: String) async -> Result<String, Error> {
        run { try pubkycore.publish(recordName: recordName, recordContent: recordContent, secretKey: secretKey) }
    }

    func publishHttps(recordName: String, target: String, secretKey: String) async -> Result<String, Error> {
        run { try pubkycore.publishHttps(recordName: recordName, target: target, secretKey: secretKey) }
    }

    func put(url: String, content: String, secretKey: String) async -> Result<String, Error> {
        run { try pubkycore.put(url: url, content: content, secretKey: secretKey) }
    }

    func get(url: String) async -> Result<String, Error> {
        run { try pubkycore.get(url: url) }
    }

    func list(url: String) async -> Result<String, Error> {
        run { try pubkycore.list(url: url) }
    }

    func deleteFile(url: String, secretKey: String) async -> Result<String, Error> {
        run { try pubkycore.deleteFile(url: url, secretKey: secretKey) }
    }

    func republishHomeserver(secretKey: String, homeserver: String) async -> Result<String, Error> {
        run { try pubkycore.republishHomeserver(secretKey: secretKey, homeserver: homeserver) }
    }

    // MARK: - Records (session auth)

    func putWithSession(url: String, content: String, sessionSecret: String) async -> Result<String, Error> {
        run { try pubkycore.putWithSession(url: url, content: content, sessionSecret: sessionSecret) }
    }

    func deleteWithSession(url: String, sessionSecret: String) async -> Result<String, Error> {
        run { try pubkycore.deleteWithSession(url: url, sessionSecret: sessionSecret) }
    }

    // MARK: - DHT resolution

    func resolve(publicKey: String) async -> Result<String, Error> {
        run { try pubkycore.resolve(publicKey: publicKey) }
    }

    func resolveHttps(publicKey: String) async -> Result<String, Error> {
        run { try pubkycore.resolveHttps(publicKey: publicKey) }
    }

    func getHomeserver(pubky: String) async -> Result<String, Error> {
        run { try pubkycore.getHomeserver(pubky: pubky) }
    }

    // MARK: - Network

    func switchNetwork(useTestnet: Bool) -> Result<String, Error> {
        run { try pubkycore.switchNetwork(useTestnet: useTestnet) }
    }

    // MARK: - Helpers

    private func run(_ block: () throws -> [String]) -> Result<String, Error> {
        do {
            let response = try block()
            guard response.count >= 2 else {
                return .failure(PubkyError(message: "Unexpected FFI response: \(response)"))
            }
            if response[0] == "success" {
                return .success(response[1])
            } else {
                return .failure(PubkyError(message: response[1]))
            }
        } catch {
            return .failure(error)
        }
    }
}
