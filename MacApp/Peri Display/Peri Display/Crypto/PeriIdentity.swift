//
//  PeriIdentity.swift
//  Peri Display
//
//  이 기기의 장기 신원 Ed25519 키. 한 번 생성해 Keychain(ThisDeviceOnly)에 보관하고
//  이후 재사용한다. 핸드셰이크/BLE 페어링에서 전사(transcript)에 서명해 신원을 증명하고,
//  상대의 서명을 검증한다. 임시 X25519 KEX 키(PeriCrypto)와 별개 — 서명 전용 신원.
//

import Foundation
import CryptoKit

final class PeriIdentity {
    static let shared = PeriIdentity()

    private static let account = "com.peri.display.identity.ed25519"
    private let key: Curve25519.Signing.PrivateKey

    private init() {
        if let raw = Keychain.read(Self.account),
           let k = try? Curve25519.Signing.PrivateKey(rawRepresentation: raw) {
            key = k
        } else {
            let k = Curve25519.Signing.PrivateKey()
            Keychain.write(k.rawRepresentation, account: Self.account)
            key = k
        }
    }

    /// 내 신원 공개키 (32B).
    var publicKey: Data { key.publicKey.rawRepresentation }

    /// 주어진 이름으로 내 자기서명 인증서를 만든다.
    func makeCert(name: String,
                  createdMillis: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> PeriCert {
        let region = PeriCert.signedRegion(pub: publicKey, name: name, createdMillis: createdMillis)
        let sig = (try? key.signature(for: region)) ?? Data()
        return PeriCert(pub: publicKey, name: name, createdMillis: createdMillis, selfSig: sig)
    }

    /// 임의 데이터(예: 핸드셰이크/페어링 전사)에 신원키로 서명.
    func sign(_ data: Data) -> Data { (try? key.signature(for: data)) ?? Data() }

    /// 상대 공개키로 서명 검증.
    static func verify(pub: Data, signature: Data, over data: Data) -> Bool {
        guard let k = try? Curve25519.Signing.PublicKey(rawRepresentation: pub) else { return false }
        return k.isValidSignature(signature, for: data)
    }
}

/// 최소 Keychain 헬퍼 (generic password, this-device-only). 비밀 전용 —
/// 신원 개인키와 상대별 링크 키(TrustStore)가 여기 산다.
enum Keychain {
    private static let service = "com.peri.display"

    static func read(_ account: String) -> Data? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess else { return nil }
        return out as? Data
    }

    @discardableResult
    static func write(_ data: Data, account: String) -> Bool {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(base as CFDictionary)
        var add = base
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    static func delete(_ account: String) {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(q as CFDictionary)
    }
}
