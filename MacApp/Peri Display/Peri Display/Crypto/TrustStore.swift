//
//  TrustStore.swift
//  Peri Display
//
//  핀된(신뢰된) 상대 신원 저장소. 첫 페어링에서 **사람이 확인한**(코드 비교 또는 패스코드
//  입력) 상대의 cert를 trust(_:)로 고정하고, 이후 연결에서 state(of:)로 신뢰를 확인한다.
//  키(pub) 자체로 신뢰하므로 모르는 키는 절대 자동연결되지 않는다(fail-closed). 키가 바뀐
//  기기는 그냥 '모르는 기기'로 보여 다시 페어링을 요구하게 된다.
//
//  ★ TOFU가 **아니다.** 한때 LAN 핸드셰이크에서 처음 보는 신원을 자동으로 핀하는 임시
//  경로가 있었지만(BLE 페어링이 완성되기 전의 부트스트랩), 지금은 지워졌다. 여기 들어오는
//  항목을 만드는 곳은 BLE 페어링 하나뿐이고, 그 경로는 사람의 확인 없이 끝나지 않는다.
//
//  저장: Application Support/PeriDisplay/trust.json. pub는 공개값이라 비밀유지가 아니라
//  무결성이 관심사 — 로컬 변조 방지 강화(신원키로 파일 서명/Keychain화)는 후속 과제(TODO).
//  Android TrustStore.kt 와 대칭.
//
//  ★ **링크 키만은 이 파일에 없다.** 페어링이 남기는 32바이트 공유 비밀은 진짜 비밀이라
//  평문 JSON에 둘 수 없다 — Keychain(ThisDeviceOnly)에 상대 공개키별로 따로 넣고, 여기엔
//  아무 흔적도 남기지 않는다. 그래서 `linkKey(for:)`가 유일한 창구다.
//

import Foundation
import CryptoKit

enum TrustState: Equatable { case unknown, trusted }

struct TrustedPeer: Codable, Equatable {
    let pubBase64: String
    var name: String
    let pairedAtMillis: Int64
    var lastSeenMillis: Int64

    var pub: Data { Data(base64Encoded: pubBase64) ?? Data() }

    /// 기기 신원 지문 — PeriCert.fingerprint와 동일 규칙(SHA256(pub) 앞 8바이트 hex 4그룹).
    /// UI가 저장된 기기와 활성 세션을 같은 키로 잇는 데 쓴다.
    var fingerprint: String {
        let h = Data(SHA256.hash(data: pub)).prefix(8)
        return stride(from: 0, to: h.count, by: 2).map {
            String(format: "%02X%02X", h[h.startIndex + $0], h[h.startIndex + $0 + 1])
        }.joined(separator: "-")
    }
}

final class TrustStore {
    static let shared = TrustStore()

    private let lock = NSLock()
    private var peers: [String: TrustedPeer] = [:]   // key = pubBase64
    private let url: URL

    private init() {
        let base = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("PeriDisplay", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        url = base.appendingPathComponent("trust.json")
        load()
    }

    func state(of pub: Data) -> TrustState {
        lock.lock(); defer { lock.unlock() }
        return peers[pub.base64EncodedString()] != nil ? .trusted : .unknown
    }

    func peer(for pub: Data) -> TrustedPeer? {
        lock.lock(); defer { lock.unlock() }
        return peers[pub.base64EncodedString()]
    }

    /// PIN 인증을 통과한 cert를 핀. 자기서명이 유효해야 받아들인다. 성공 시 true.
    ///
    /// `linkKey`는 이번 페어링이 남기는 공유 비밀이다 — 있으면 상대 신원 옆에 같이 붙는다.
    /// 케이블(AOA) 부트스트랩처럼 PAKE를 거치지 않은 경로는 nil로 부르고, 그때는 기존에
    /// 저장돼 있던 키를 **지우지 않는다**. 신원이 같으면 예전에 사람이 확인한 그 키가 여전히
    /// 유효하고, 새 근거가 없다고 해서 옛 근거를 버릴 이유는 없다.
    @discardableResult
    func trust(_ cert: PeriCert, linkKey: Data? = nil) -> Bool {
        guard cert.verifySelfSignature() else { return false }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let key = cert.pub.base64EncodedString()
        if let linkKey, !linkKey.isEmpty {
            Keychain.write(linkKey, account: Self.linkAccount(cert.pub))
        }
        lock.lock()
        if var existing = peers[key] {
            existing.name = cert.name
            existing.lastSeenMillis = now
            peers[key] = existing
        } else {
            peers[key] = TrustedPeer(pubBase64: key, name: cert.name,
                                     pairedAtMillis: now, lastSeenMillis: now)
        }
        lock.unlock()
        save()
        return true
    }

    /// 신뢰된 상대를 봤을 때 lastSeen 갱신.
    func touch(_ pub: Data) {
        let key = pub.base64EncodedString()
        lock.lock()
        guard var p = peers[key] else { lock.unlock(); return }
        p.lastSeenMillis = Int64(Date().timeIntervalSince1970 * 1000)
        peers[key] = p
        lock.unlock()
        save()
    }

    /// 이 상대와 공유하는 링크 키. 없으면 nil — 페어링 전이거나, 케이블로만 만난 사이다.
    func linkKey(for pub: Data) -> Data? {
        Keychain.read(Self.linkAccount(pub))
    }

    @discardableResult
    func revoke(_ pub: Data) -> Bool {
        let key = pub.base64EncodedString()
        lock.lock(); let removed = peers.removeValue(forKey: key) != nil; lock.unlock()
        // 신뢰를 거두면 **비밀도 같이 사라져야 한다.** 목록에서만 지우고 Keychain에 두면,
        // 해제한 기기와 다시 만났을 때 사람 확인 없이 되살아날 재료가 남는다.
        Keychain.delete(Self.linkAccount(pub))
        if removed { save() }
        return removed
    }

    /// 링크 키의 Keychain 계정명 — 상대 공개키마다 하나씩.
    private static func linkAccount(_ pub: Data) -> String {
        "com.peri.display.link." + pub.base64EncodedString()
    }

    func all() -> [TrustedPeer] {
        lock.lock(); defer { lock.unlock() }
        return Array(peers.values).sorted { $0.lastSeenMillis > $1.lastSeenMillis }
    }

    // MARK: persistence

    private func load() {
        guard let data = try? Data(contentsOf: url),
              let list = try? JSONDecoder().decode([TrustedPeer].self, from: data) else { return }
        peers = Dictionary(list.map { ($0.pubBase64, $0) }, uniquingKeysWith: { a, _ in a })
    }

    private func save() {
        lock.lock(); let list = Array(peers.values); lock.unlock()
        guard let data = try? JSONEncoder().encode(list) else { return }
        try? data.write(to: url, options: .atomic)
    }
}
