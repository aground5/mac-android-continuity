//
//  PeriSts.swift
//  Peri Display
//
//  PQ-STS (Station-to-Station) — 세션 핸드셰이크의 상호 인증.
//  Android `crypto/PeriSts.kt`와 바이트 단위로 같아야 한다.
//
//  Android = 개시자(Alice), Mac = 응답자(Bob).
//
//    Pass 1  And → Mac : StsHello    { core }                    X_A, PK_A
//    Pass 2  Mac → And : StsResponse { core, E_K(cert_B ‖ S_B) } X_B, ct_B
//    Pass 3  And → Mac : StsConfirm  { E_K(cert_A ‖ S_A) }
//
//  ## 옛 방식에서 무엇이 고쳐졌나
//
//  ① **서명이 봉인된다.** 예전엔 서버 인증서와 서명이 ServerHello에 평문으로 실렸다. 같은
//     망의 누구나 두 기기의 신원을 읽을 수 있었고, 그건 "이 집에 이 맥과 이 폰이 있다"를
//     그대로 흘리는 것이다. 봉인은 동시에 키 확인이기도 하다 — 열렸다는 사실 자체가 상대가
//     같은 K에 도달했다는 증거다.
//
//  ② **서명이 협상 내용까지 덮는다.** 이게 더 중요하다. 예전 전사 해시는 KEX 재료(nonce·
//     공개키·암호문)만 묶었고, ClientHello의 나머지 — 코덱 목록, 해상도, HDR 지원 범위,
//     디코더 한계, 프로토콜 버전 — 는 평문이면서 **아무것도 덮지 않았다.** 경로상의 공격자가
//     supported_codecs에서 H265를 지우거나 supported_dynamic_ranges에서 HDR을 지워도 서명은
//     그대로 검증됐다. 지금은 `core` 바이트 전체가 서명 대상이라 그 강등이 불가능하다.
//
//  ## 왜 nonce 필드가 없나
//
//  임시 키쌍이 매 핸드셰이크 새로 생기므로 그 자체가 신선도다(HKDF salt를 비우는 이유도
//  같다). 재전송은 `session_id`가 `core` 안에서 서명에 덮여 막힌다.
//

import Foundation
import CryptoKit

enum PeriSts {

    /// 와이어 버전. 옛 핸드셰이크(1)와 섞이지 않게 올렸다.
    static let protocolVersion: UInt32 = 2

    private static let tag = Data("PeriSTS-v1".utf8)
    private static let serverCtx = Data("PeriSTS-v1/server".utf8)
    private static let clientCtx = Data("PeriSTS-v1/client".utf8)

    /// 스펙의 K. 서명 봉인 전용이고, 세션 키는 아래 두 라벨로 따로 뽑는다.
    private static let kInfo = Data("PQ-STS Session Key".utf8)

    /// 봉인 방향. 같은 K를 쓰므로 **nonce가 달라야 한다** — 각 방향이 정확히 한 번 봉인한다.
    enum Role: UInt64 {
        case server = 0
        case client = 1
    }

    // MARK: - 전사

    private static func lp(_ d: Data) -> Data {
        var out = Data()
        withUnsafeBytes(of: UInt32(d.count).bigEndian) { out.append(contentsOf: $0) }
        out.append(d)
        return out
    }

    /// **선에 실제로 나간 바이트**를 그대로 묶는다.
    ///
    /// 중첩 메시지로 두고 받은 쪽이 재직렬화해 해시하면, protobuf 구현이 미지 필드나 필드
    /// 순서를 다르게 내는 순간 서명이 어긋난다. 불투명 바이트로 실어야 양쪽이 같은 걸 본다.
    static func transcript(helloCore: Data, responseCore: Data) -> Data {
        var t = lp(tag)
        t.append(lp(helloCore))
        t.append(lp(responseCore))
        return Data(SHA256.hash(data: t))
    }

    static func serverSigningBytes(_ th: Data) -> Data { serverCtx + th }
    static func clientSigningBytes(_ th: Data) -> Data { clientCtx + th }

    // MARK: - 키

    struct Keys {
        /// 스펙의 K — 신원 봉인 전용.
        let auth: SymmetricKey
        /// 이후 제어·영상이 타는 방향별 세션.
        let session: PeriCryptoSession
    }

    /// `ikm = k_ECDH ‖ k_PQC`. salt는 비운다(임시키가 이미 신선도를 준다).
    static func derive(ecdh: Data, kem: Data) -> Keys {
        let ikm = ecdh + kem
        let material = SymmetricKey(data: ikm)
        let auth = HKDF<SHA256>.deriveKey(inputKeyMaterial: material, salt: Data(),
                                          info: kInfo, outputByteCount: 32)
        return Keys(auth: auth, session: PeriCryptoSession.sts(ikm: ikm))
    }

    /// Mac(Bob)의 KEX 몫 — 임시 X25519 + 상대 ML-KEM 공개키로 캡슐화.
    static func respond(clientX25519: Data, clientMLKEM: Data)
        throws -> (x25519Pub: Data, ciphertext: Data, ecdh: Data, kem: Data) {
        let xPriv = Curve25519.KeyAgreement.PrivateKey()
        let peerX = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: clientX25519)
        let ssX = try xPriv.sharedSecretFromKeyAgreement(with: peerX)

        let mlkemPub = try MLKEM768.PublicKey(rawRepresentation: clientMLKEM)
        let enc = try mlkemPub.encapsulate()

        var ecdh = Data(); ssX.withUnsafeBytes { ecdh.append(contentsOf: $0) }
        var kem = Data(); enc.sharedSecret.withUnsafeBytes { kem.append(contentsOf: $0) }
        return (xPriv.publicKey.rawRepresentation, enc.encapsulated, ecdh, kem)
    }

    // MARK: - 신원 봉인

    /// [12B nonce][ciphertext‖tag]. nonce는 역할에서 결정되므로 재사용이 구조적으로 불가능하다.
    static func sealAuth(_ key: SymmetricKey, _ plaintext: Data, role: Role) throws -> Data {
        let nonce = authNonce(role)
        let box = try AES.GCM.seal(plaintext, using: key,
                                   nonce: try AES.GCM.Nonce(data: nonce))
        return nonce + box.ciphertext + box.tag
    }

    static func openAuth(_ key: SymmetricKey, _ blob: Data, role: Role) -> Data? {
        guard blob.count > 12 + 16 else { return nil }
        let d = Data(blob)   // 0-기반 보장
        // nonce는 **우리가 계산한 값**을 쓴다. 실려 온 값을 믿으면 상대가 nonce를 골라
        // 같은 키에 재사용을 유도할 수 있다.
        let expected = authNonce(role)
        guard d.prefix(12) == expected else { return nil }
        let body = d.suffix(from: 12)
        let ct = body.prefix(body.count - 16)
        let tag = body.suffix(16)
        guard let box = try? AES.GCM.SealedBox(nonce: try AES.GCM.Nonce(data: expected),
                                               ciphertext: ct, tag: tag) else { return nil }
        return try? AES.GCM.open(box, using: key)
    }

    // MARK: - 신원 조회

    /// 서명 하나로 **누구인지** 알아낸다 — 저장된 공개키를 하나씩 넣어 본다.
    ///
    /// 검증자는 서명 대상(전사)을 이미 알고 있으므로 공개키를 받을 이유가 없다. 페어링한
    /// 기기 수만큼(1~3) Ed25519 검증이 도는데 한 번에 수십 µs라 값이 안 나간다.
    ///
    /// **집합 소속이 아니라 신원을 낸다.** 예전 코드는 인증서를 받아 "이게 신뢰 목록에 있나"만
    /// 봤는데, 그러면 Mac을 둘 페어링해 뒀을 때 A에게 걸었는데 B가 응답해도 그냥 통과한다.
    /// 여기서 누구인지가 나오므로 호출부가 의도와 대조할 수 있다.
    static func resolveSigner(signature: Data, over signed: Data,
                              among peers: [TrustedPeer]) -> TrustedPeer? {
        peers.first { peer in
            let pub = peer.pub
            return !pub.isEmpty
                && PeriIdentity.verify(pub: pub, signature: signature, over: signed)
        }
    }

    /// [채널 2 = 인증][0,0,0][8B BE 역할]. 제어(0)·영상(1) nonce 공간과 겹치지 않는다.
    private static func authNonce(_ role: Role) -> Data {
        var d = Data(count: 12)
        d[0] = 2
        for i in 0..<8 { d[11 - i] = UInt8((role.rawValue >> (8 * UInt64(i))) & 0xFF) }
        return d
    }
}
