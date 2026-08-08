//
//  PeriCrypto.swift
//  Peri Display
//
//  E2E 암호화 (DESIGN.md §3). 하이브리드 키교환 X25519 + ML-KEM-768 → HKDF → AES-256-GCM.
//  Mac = responder (encapsulator). 방향별 키 분리(m2a 송신 / a2m 수신).
//
//  상호운용: CryptoKit는 raw 바이트(FIPS 203 / RFC 7748)를 사용한다. Android는
//  BouncyCastle lightweight API로 동일한 raw 인코딩을 맞춘다 (SPKI DER 아님).
//

import Foundation
import CryptoKit

// PQ-STS 세션 키. 스펙의 K("PQ-STS Session Key")에서 방향만 갈랐다 — 한 키를 양방향에
// 쓰면 두 방향이 같은 (key, nonce)를 만날 수 있고, GCM에서 그건 곧 파국이다.
private let kStsSend = Data("PQ-STS Session Key/m2a".utf8)
private let kStsRecv = Data("PQ-STS Session Key/a2m".utf8)

// BLE 페어링 채널은 도메인을 따로 쓴다 — 같은 재료가 두 채널에 재사용되지 않게.
private let kBleSend = Data("PeriBLE-v2/m2a".utf8)
private let kBleRecv = Data("PeriBLE-v2/a2m".utf8)

// 데이터그램(UDP) 채널. 마스터를 한 번 뽑아 두고, 세대마다 channel_id를 소금으로 갈라 쓴다.
private let kUdpMaster = Data("PeriUDP-v1/master".utf8)
private let kUdpSend = Data("PeriUDP-v1/m2a".utf8)
private let kUdpRecv = Data("PeriUDP-v1/a2m".utf8)

/**
 데이터그램 채널 **한 세대**의 키.

 세션 키에서 `channel_id`를 소금으로 갈라 나온다. 두 가지를 동시에 얻는다:

  · **0-RTT** — 채널을 연다는 통보(`UdpOpen`)만 오면 양쪽이 즉시 같은 키에 도달한다.
    UDP 위에서 키를 다시 합의할 필요가 없으니 첫 데이터그램부터 봉인돼 나간다.
  · **세대 격리** — 영상 nonce는 frame_id에서 유도되는데, 채널을 닫았다 다시 열면서
    프레임 번호가 되감기면 같은 (키, nonce)가 두 번 나온다. 세대마다 키가 바뀌면
    그 사고가 **구조적으로** 불가능해진다. 카운터 규율에 기대지 않는 쪽을 골랐다.
 */
nonisolated final class PeriDatagramKeys: @unchecked Sendable {
    let sender: Sealer
    let receiver: Opener

    fileprivate init(sendKey: SymmetricKey, recvKey: SymmetricKey) {
        sender = Sealer(key: sendKey)
        receiver = Opener(key: recvKey)
    }
}

/// 핸드셰이크 결과 + 방향별 sealing 상태.
nonisolated final class PeriCryptoSession: @unchecked Sendable {
    let sender: Sealer    // Mac → Android (key_m2a)
    let receiver: Opener  // Android → Mac (key_a2m)

    /// 데이터그램 세대 키의 뿌리. 세션 키와 **다른 라벨**로 뽑아 둔다 — 한쪽이 새도
    /// 다른 쪽이 나오지 않게.
    private let datagramMaster: SymmetricKey

    private init(sendKey: SymmetricKey, recvKey: SymmetricKey, datagramMaster: SymmetricKey) {
        sender = Sealer(key: sendKey)
        receiver = Opener(key: recvKey)
        self.datagramMaster = datagramMaster
    }

    /// 이 세대의 데이터그램 키. `channel_id`는 Mac이 세대마다 새로 뽑는 16바이트다.
    func datagram(channel id: Data) -> PeriDatagramKeys {
        let send = HKDF<SHA256>.deriveKey(inputKeyMaterial: datagramMaster, salt: id,
                                          info: kUdpSend, outputByteCount: 32)
        let recv = HKDF<SHA256>.deriveKey(inputKeyMaterial: datagramMaster, salt: id,
                                          info: kUdpRecv, outputByteCount: 32)
        return PeriDatagramKeys(sendKey: send, recvKey: recv)
    }

    /// PQ-STS 세션 (LAN/USB). salt를 비우는 이유는 [PeriSts] 주석 참조 — 임시키가 신선도다.
    static func sts(ikm: Data) -> PeriCryptoSession {
        let key = SymmetricKey(data: ikm)
        let send = HKDF<SHA256>.deriveKey(inputKeyMaterial: key, salt: Data(),
                                          info: kStsSend, outputByteCount: 32)
        let recv = HKDF<SHA256>.deriveKey(inputKeyMaterial: key, salt: Data(),
                                          info: kStsRecv, outputByteCount: 32)
        let master = HKDF<SHA256>.deriveKey(inputKeyMaterial: key, salt: Data(),
                                            info: kUdpMaster, outputByteCount: 32)
        return PeriCryptoSession(sendKey: send, recvKey: recv, datagramMaster: master)
    }

    /// BLE 페어링(v2) 세션 — PAKE 결과와 KEM 공유비밀을 **함께** 넣어 파생한다.
    ///
    /// 둘을 한 ikm에 붙이는 게 요점이다: PIN만 깨도, 양자내성 키교환만 깨도 이 키는 안 나온다.
    /// LAN 세션과 info를 갈라 둔 건 같은 재료가 두 채널에 쓰이는 일이 없게 하기 위해서다.
    ///
    /// 데이터그램 마스터도 형식상 뽑히지만 **BLE에는 데이터그램 경로가 없어** 쓰이지 않는다.
    /// 선택적으로 두지 않은 이유: nil일 수 있는 키는 언젠가 nil인 채로 불린다.
    static func pairing(ikm: Data, salt: Data) -> PeriCryptoSession {
        let key = SymmetricKey(data: ikm)
        let send = HKDF<SHA256>.deriveKey(inputKeyMaterial: key, salt: salt,
                                          info: kBleSend, outputByteCount: 32)
        let recv = HKDF<SHA256>.deriveKey(inputKeyMaterial: key, salt: salt,
                                          info: kBleRecv, outputByteCount: 32)
        let master = HKDF<SHA256>.deriveKey(inputKeyMaterial: key, salt: salt,
                                            info: kUdpMaster, outputByteCount: 32)
        return PeriCryptoSession(sendKey: send, recvKey: recv, datagramMaster: master)
    }

    static func randomNonce32() -> Data {
        var d = Data(count: 32)
        d.withUnsafeMutableBytes { _ = SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        return d
    }

    /// 데이터그램 세대 id. Mac만 만든다(부여자가 하나여야 이름이 안 겹친다).
    static func randomChannelID() -> Data {
        var d = Data(count: datagramChannelIDBytes)
        d.withUnsafeMutableBytes {
            _ = SecRandomCopyBytes(kSecRandomDefault, datagramChannelIDBytes, $0.baseAddress!)
        }
        return d
    }
}

/// 데이터그램 세대 id의 길이. 와이어(UDP HELLO 접두)와 키 유도 소금이 같은 값을 쓴다.
nonisolated let datagramChannelIDBytes = 16

/// nonce 채널 (같은 키에서 제어/영상 nonce 공간 분리 → 재사용 방지).
enum NonceChannel: UInt8 { case control = 0, video = 1 }

/// 12B nonce = [채널(1B)][000][카운터/frame_id (8B big-endian)].
private func makeNonce(_ channel: NonceChannel, _ value: UInt64) -> Data {
    var d = Data(count: 12)
    d[0] = channel.rawValue
    for i in 0..<8 { d[11 - i] = UInt8((value >> (8 * UInt64(i))) & 0xFF) }
    return d
}

/// AES-256-GCM 송신측. nonce 재사용 금지 (제어=카운터, 영상=frame_id).
nonisolated final class Sealer: @unchecked Sendable {
    private let key: SymmetricKey
    private let lock = NSLock()
    private var counter: UInt64 = 0

    init(key: SymmetricKey) { self.key = key }

    /// 제어용: (12B nonce 전송, ciphertext||tag). nonce는 단조 카운터(채널 control).
    func seal(_ plaintext: Data) throws -> (nonce: Data, body: Data) {
        lock.lock(); let c = counter; counter &+= 1; lock.unlock()
        let nonceData = makeNonce(.control, c)
        let box = try AES.GCM.seal(plaintext, using: key, nonce: try AES.GCM.Nonce(data: nonceData))
        return (nonceData, box.ciphertext + box.tag)
    }

    /// 영상용: nonce를 frame_id로 유도(전송 안 함). 반환 body = ciphertext||tag.
    func sealVideo(_ plaintext: Data, frameId: UInt64) throws -> Data {
        let nonceData = makeNonce(.video, frameId)
        let box = try AES.GCM.seal(plaintext, using: key, nonce: try AES.GCM.Nonce(data: nonceData))
        return box.ciphertext + box.tag
    }
}

/// AES-256-GCM 수신측.
nonisolated final class Opener: @unchecked Sendable {
    private let key: SymmetricKey
    init(key: SymmetricKey) { self.key = key }

    /// 제어용: 전송된 nonce 사용.
    func open(nonce: Data, body: Data) throws -> Data {
        try Self.open(key: key, nonce: nonce, body: body)
    }

    /// 영상용: nonce를 frame_id로 유도.
    func openVideo(_ body: Data, frameId: UInt64) throws -> Data {
        try Self.open(key: key, nonce: makeNonce(.video, frameId), body: body)
    }

    private static func open(key: SymmetricKey, nonce: Data, body: Data) throws -> Data {
        guard body.count >= 16 else { throw CryptoError.short }
        let ct = body.prefix(body.count - 16)
        let tag = body.suffix(16)
        let box = try AES.GCM.SealedBox(nonce: try AES.GCM.Nonce(data: nonce), ciphertext: ct, tag: tag)
        return try AES.GCM.open(box, using: key)
    }

    enum CryptoError: Error { case short }
}
