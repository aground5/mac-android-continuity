//
//  PeriCert.swift
//  Peri Display
//
//  장기 신원 "인증서" — X.509 아님. Ed25519 공개키(=신원) + 표시이름 + 생성시각에
//  자기서명(selfSig)을 붙인 자기기술 레코드. 신뢰 결정은 pub를 핀(SSH 방식) — 다만 핀을
//  만드는 건 사람이 확인하는 BLE 페어링뿐이다(TOFU 아님, TrustStore 주석 참조).
//  Android PeriCert.kt 와 동일한 raw 바이너리 인코딩이라 한쪽이 만든 cert를 반대쪽이
//  그대로 검증한다 (기존 X25519/ML-KEM raw-interop 규율과 동일).
//
//  레이아웃: ["PCRT" 4B][ver 1B=1][created 8B BE][nameLen 2B BE][name UTF8][pub 32B][selfSig 64B]
//  selfSig = Ed25519( 위 바이트열에서 selfSig 직전까지 ) — 보유자가 키를 통제함을 증명.
//

import Foundation
import CryptoKit

struct PeriCert: Equatable {
    static let magic = Data("PCRT".utf8)
    static let version: UInt8 = 1
    static let pubLen = 32
    static let sigLen = 64

    let pub: Data            // Ed25519 공개키 32B = 신원
    let name: String         // 사람이 읽는 기기 이름
    let createdMillis: Int64
    let selfSig: Data        // 64B

    /// 자기서명 검증 — name/created가 pub에 대해 위변조되지 않았고 보유자가 키를 통제함을 보장.
    func verifySelfSignature() -> Bool {
        guard pub.count == Self.pubLen, selfSig.count == Self.sigLen,
              let key = try? Curve25519.Signing.PublicKey(rawRepresentation: pub) else { return false }
        return key.isValidSignature(selfSig,
            for: Self.signedRegion(pub: pub, name: name, createdMillis: createdMillis))
    }

    /// 짧은 지문 — 페어링 화면/목록 표시·SAS 비교용. SHA256(pub) 앞 8바이트를 hex 4그룹.
    var fingerprint: String {
        let h = Data(SHA256.hash(data: pub)).prefix(8)
        return stride(from: 0, to: h.count, by: 2).map {
            String(format: "%02X%02X", h[h.startIndex + $0], h[h.startIndex + $0 + 1])
        }.joined(separator: "-")
    }

    // MARK: 직렬화

    func serialized() -> Data {
        var d = Self.signedRegion(pub: pub, name: name, createdMillis: createdMillis)
        d.append(selfSig)
        return d
    }

    static func parse(_ input: Data) -> PeriCert? {
        let bytes = Data(input)            // 연속/0-기반 인덱스 보장
        var i = 0
        func take(_ n: Int) -> Data? {
            guard n >= 0, i + n <= bytes.count else { return nil }
            let r = bytes.subdata(in: i..<(i + n)); i += n; return r
        }
        guard let m = take(4), m == magic,
              let v = take(1), v[0] == version,
              let createdB = take(8), let nameLenB = take(2) else { return nil }
        let created = createdB.reduce(Int64(0)) { ($0 << 8) | Int64($1) }
        let nameLen = (Int(nameLenB[0]) << 8) | Int(nameLenB[1])
        guard let nameB = take(nameLen), let name = String(data: nameB, encoding: .utf8),
              let pub = take(pubLen), let sig = take(sigLen), i == bytes.count else { return nil }
        return PeriCert(pub: pub, name: name, createdMillis: created, selfSig: sig)
    }

    /// selfSig가 덮는 영역 (magic..pub). 서명/검증 양쪽이 같은 함수를 쓴다.
    static func signedRegion(pub: Data, name: String, createdMillis: Int64) -> Data {
        var d = Data()
        d.append(magic)
        d.append(version)
        var c = createdMillis.bigEndian
        withUnsafeBytes(of: &c) { d.append(contentsOf: $0) }
        let nameB = Data(name.utf8)
        d.append(UInt8((nameB.count >> 8) & 0xFF))
        d.append(UInt8(nameB.count & 0xFF))
        d.append(nameB)
        d.append(pub)
        return d
    }
}
