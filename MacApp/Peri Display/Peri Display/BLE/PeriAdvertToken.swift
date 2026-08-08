//
//  PeriAdvertToken.swift
//  Peri Display
//
//  Android BLE 광고에 실린 **회전 신원 토큰**을 해석해 "이미 페어링된 그 기기"로 되돌린다.
//
//  Android PeriAdvertToken.kt 와 대칭 — 태그·epoch 길이·바이트 순서가 반드시 같아야 한다.
//  지문을 그대로 광고하지 않는 이유는 그쪽 주석 참조(영구 추적 가능 비콘 방지).
//
//  해석 성질이 곧 UX 규칙이다: 해석되면 페어링된 기기(기존 목록 행에 병합),
//  해석 안 되면 낯선 주변 기기(페어링 후보) — 신뢰 상태가 화면 구조를 정한다.
//

import Foundation
import CryptoKit

enum PeriAdvertToken {
    static let companyID: UInt16 = 0xFFFF   // SIG 미할당(내부/시험용)
    static let version: UInt8 = 1
    /// 호출 응답 광고의 버전 바이트 — Android `PeriAdvertToken.VERSION_RESPONSE`와 같아야 한다.
    static let versionResponse: UInt8 = 2
    static let epochSeconds: TimeInterval = 900
    private static let tag = Data("PeriADV-v1".utf8)

    /// 재협상 요청 비트 — Android가 "내 IP가 바뀌었다"를 광고로 알린다.
    /// IP가 바뀌는 순간 IP 기반 채널은 못 믿으므로, 개시 신호는 IP에 독립적인 광고로 받는다.
    static let flagIceRestart: UInt8 = 0x01

    // MARK: - 광고 한 패킷의 예산
    //
    // **주석이 아니라 상수로 둔다.** 여기 덧셈이 틀렸던 적이 있고, 그때 증상은 "응답 광고가
    // 안 올라간다"가 아니라 **아무 일도 안 일어난다**였다 — Android 스택은 초과분을
    // `ADVERTISE_FAILED_DATA_TOO_LARGE`로 조용히 거절한다. 세면 알 수 있는 것을 세지 않아서
    // 생긴 버그라 세는 일을 코드에 옮겨 놨다(Android `PeriAdvertToken.kt`와 값이 같아야 한다).

    /// 레거시 광고 페이로드 한도.
    static let legacyAdvertLimit = 31
    /// **스택이 붙이는** AD Flags — connectable ∧ discoverable 광고에 강제로 들어간다.
    /// 우리 코드에 안 보이므로 예산에서 빠뜨리기 가장 쉬운 3바이트다.
    static let flagsFieldBytes = 3
    /// 128비트 서비스 UUID AD: len 1 + type 1 + UUID 16.
    static let serviceUuidFieldBytes = 18
    /// 제조사 데이터 AD 머리: len 1 + type 1 + 회사ID 2.
    static let manufacturerHeaderBytes = 4

    /// 그래서 제조사 데이터 **본문**에 쓸 수 있는 바이트. 지금은 6이다.
    static let maxManufacturerBody =
        legacyAdvertLimit - flagsFieldBytes - serviceUuidFieldBytes - manufacturerHeaderBytes

    /// 응답 본문 = [1B 버전][나머지 전부가 응답]. `PeriPageToken.responseBytes`가 이걸 쓴다.
    static let responseBytes = maxManufacturerBody - 1

    /// 광고에서 뽑아낸 내용.
    struct Advert {
        let token: Data
        let flags: UInt8
        var wantsIceRestart: Bool { flags & PeriAdvertToken.flagIceRestart != 0 }
    }

    static func currentEpoch(_ now: Date = Date()) -> Int64 {
        Int64((now.timeIntervalSince1970 / epochSeconds).rounded(.down))
    }

    /// HMAC-SHA256(신원공개키, TAG ‖ epoch(8B BE)) 앞 4바이트.
    static func token(pub: Data, epoch: Int64) -> Data {
        var msg = tag
        withUnsafeBytes(of: epoch.bigEndian) { msg.append(contentsOf: $0) }
        let code = HMAC<SHA256>.authenticationCode(for: msg, using: SymmetricKey(data: pub))
        return Data(code).prefix(4)
    }

    /// 광고 제조사 데이터 해석 — [2B 회사ID(LE)][1B 버전][4B 토큰][1B 플래그(선택)].
    /// 회사ID/버전이 안 맞으면 우리 것이 아니므로 nil.
    ///
    /// 플래그 바이트는 뒤에 붙어 있어 **없어도 정상**으로 읽는다(0으로 간주). 토큰 오프셋이
    /// 그대로라 플래그를 안 싣는 빌드와도 그냥 섞여 돈다.
    static func parse(manufacturerData: Data) -> Advert? {
        let d = Data(manufacturerData)   // 0-기반 인덱스 보장
        guard d.count >= 7 else { return nil }
        let company = UInt16(d[0]) | (UInt16(d[1]) << 8)
        guard company == companyID, d[2] == version else { return nil }
        return Advert(token: d.subdata(in: 3..<7), flags: d.count >= 8 ? d[7] : 0)
    }

    /// **호출 응답** 광고 — [2B 회사ID][1B 버전=2][responseBytes B 응답]. 우리 것이 아니면 nil.
    ///
    /// 상시 신원 토큰(버전 1)과 갈라져 있다. 이건 비콘이 아니라 한 번의 대답이라, 부른
    /// 쪽만(호출 nonce와 링크 키를 아는 쪽만) 대조할 수 있다.
    static func parseResponse(manufacturerData: Data) -> Data? {
        let d = Data(manufacturerData)
        guard d.count >= 3 + responseBytes else { return nil }
        let company = UInt16(d[0]) | (UInt16(d[1]) << 8)
        guard company == companyID, d[2] == versionResponse else { return nil }
        return d.subdata(in: 3..<(3 + responseBytes))
    }
}

/// 토큰 → 지문 역인덱스. 신뢰된 기기의 공개키로 epoch N-1/N/N+1의 토큰을 미리 계산해두고
/// 광고가 들어올 때 대조한다(±1 구간 = 양쪽 시계 오차 ±15분 허용).
/// 광고는 초당 여러 번 오므로 (epoch, 신뢰기기 수)가 그대로면 표를 재사용한다.
final class AdvertResolver {
    private var table: [Data: String] = [:]
    private var builtEpoch: Int64 = .min
    private var builtCount = -1

    /// 광고 토큰이 신뢰된 기기의 것이면 그 지문, 아니면 nil(= 미페어링 기기).
    func fingerprint(forToken token: Data) -> String? {
        rebuildIfNeeded()
        return table[token]
    }

    private func rebuildIfNeeded() {
        let epoch = PeriAdvertToken.currentEpoch()
        let peers = TrustStore.shared.all()
        guard epoch != builtEpoch || peers.count != builtCount else { return }
        var t: [Data: String] = [:]
        for p in peers {
            let pub = p.pub
            guard !pub.isEmpty else { continue }
            let fp = p.fingerprint
            for e in (epoch - 1)...(epoch + 1) {
                t[PeriAdvertToken.token(pub: pub, epoch: e)] = fp
            }
        }
        table = t
        builtEpoch = epoch
        builtCount = peers.count
    }
}
