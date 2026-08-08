//
//  PeriPageToken.swift
//  Peri Display
//
//  **호출(paging) 토큰** — Mac이 특정 기기 하나를 깨우려고 광고에 싣는 값.
//  Android `ble/PeriPageToken.kt`와 바이트 단위로 같아야 한다.
//
//  ## 방향이 뒤집혔다
//
//  예전엔 폰이 상시 광고하고 Mac이 스캔했다. 지금은 반대다 — **콘센트에 꽂힌 쪽이 부르고**,
//  폰은 `PendingIntent` 스캔만 걸어 둔다. 그 스캔은 블루투스 컨트롤러가 하드웨어에서
//  필터링하므로 앱이 돌지 않고, 매치될 때만 OS가 깨운다.
//
//  ## 왜 서비스 UUID 안에 넣나
//
//  macOS는 광고에 임의 바이트를 실을 수 없다. SDK 헤더가 못 박아 놨다:
//
//    > Supported advertising data types are `CBAdvertisementDataLocalNameKey`
//    > and `CBAdvertisementDataServiceUUIDsKey`.
//
//  제조사 데이터도 서비스 데이터도 안 된다. 남은 자리는 **128비트 UUID 그 자체(16바이트)**뿐이라
//  거기에 인코딩한다:
//
//      [4B 고정 접두]["PERI"]  [4B nonce]  [8B OTP]
//        └ ScanFilter 마스크가 이것만 매치 → 오프로드 유지
//
//  UUID 하나 = 16B + 헤더 2B = 18B이고 광고 예산이 28B라 **한 번에 대상 하나**다. 그게 맞다 —
//  호출은 원래 하나를 부르는 일이다. 여럿이면 순번을 돌린다. 이 구간에서 기기 이름은 싣지
//  않는다(예산도 그렇고, 이름 노출은 첫 페어링에만 둔다).
//
//  ## nonce와 epoch은 역할이 다르다 — 둘 다 필요하다
//
//  **nonce(전송됨) = 불연결성.** 광고마다 새로 뽑아 같이 실으므로, 같은 상대끼리라도 매번
//  달라 보인다. 받는 쪽은 읽어서 그대로 쓰니 시계 정밀도가 필요 없다.
//
//  **epoch(전송 안 됨) = 신선도.** 이게 없으면 `otp`가 (링크 키, nonce)만으로
//  **결정적**이라 한 번 캡처한 광고를 언제든 다시 쏠 수 있다. 그게 왜 나쁘냐면 공격자가 키를
//  하나도 몰라도 되기 때문이다 — 진짜 호출을 한 번 녹음해 두었다가 나중에 재생하면, 폰이
//  깨어나 광고를 시작하는 게 그대로 보인다. **"그 폰이 지금 여기 있나"를 묻는 존재 확인
//  오라클**이 되고, nonce로 만들어 둔 불연결성을 통째로 우회한다.
//
//  epoch을 **소금이 아니라 HMAC 메시지에** 넣는 게 요점이다. 그러면 `dk`가 epoch과 무관해
//  유도는 여전히 한 번이고, 앞뒤 창을 열어도 HMAC만 3 × (Mac 수)번이다.
//
//  ## 무엇을 증명하나 — **부르는 쪽이 페어링된 상대라는 것**
//
//  ikm이 링크 키(페어링이 남긴 공유 비밀)라 이 값은 그 키를 가진 쪽만 만들 수 있다. 예전엔
//  ikm이 폰 공개키였고, 공개키는 정의상 비밀이 아니라 **한 번이라도 그 값을 본 사람이면
//  누구나 남의 폰을 깨울 수 있었다.**
//
//  그래도 **세션 인증자는 아니다.** 한 방향 방송이고 창 안에서는 재생된다. 이걸로 정해지는
//  건 "부를 자격이 있는 쪽이 부른다"까지고, 상대가 누구인지는 그 뒤 STS에서만 확정된다.
//
//  ## 재료
//
//      dk  = HKDF(ikm: link_key, salt: nonce, info: "PeriPage-v2")
//      otp = HMAC-SHA256(dk, epoch(8B BE))[0..<8]
//
//  **소금(nonce)이 공격자 선택이어도 된다.** 예전엔 ikm이 공개값이라 이 점이 걸렸지만, 지금은
//  ikm이 비밀이다 — HKDF-Extract가 원래 상정하는 그림이고(RFC 5869), 소금은 공개여도 무방하다.
//
//  **Mac 공개키는 어디에도 안 들어간다.** 링크 키가 이미 상대별이라, 어느 링크 키로 값이
//  맞았는가가 곧 "누가 부르는가"다. 공개키를 메시지에 더 넣어 봐야 이미 정해진 답을 한 번 더
//  적는 것이고, 그만큼 양쪽이 맞춰야 할 바이트만 는다.
//
//  ## 대가: 링크 키가 없으면 부를 수 없다
//
//  첫 페어링에는 링크 키가 없다 — 그래서 그 한 번은 여전히 폰이 광고하는 경로다. 케이블로만
//  신뢰를 세운 상대도 마찬가지다(PAKE를 거치지 않아 공유 비밀이 없다).
//

import Foundation
import CryptoKit

enum PeriPageToken {

    /// UUID 앞 4바이트 — 고정. Android의 `ScanFilter` 마스크가 이것만 본다.
    static let prefix: [UInt8] = Array("PERI".utf8)

    static let nonceBytes = 4
    /// 호출 UUID에 실리는 OTP 길이. UUID 16바이트에서 접두 4 + nonce 4를 뺀 나머지가 통째로
    /// 우리 것이라 여유가 있다.
    static let otpBytes = 8

    /// **응답 광고**에 실리는 OTP 길이 — 여긴 여유가 없다.
    ///
    /// 길이를 여기서 고르지 않고 `PeriAdvertToken`의 예산에서 받아 온다. 이건 암호학적
    /// 선택이 아니라 **광고 한 패킷에 몇 바이트가 남는가**의 문제이고, 그 산술이 사는 곳은
    /// 와이어 포맷 쪽이기 때문이다.
    ///
    /// 40비트로 충분한 이유: 이 값이 정하는 건 **"Mac이 GATT를 붙어 볼 것인가"** 하나다.
    /// 위조에 성공해도 얻는 건 소켓 하나 열었다 STS에서 튕기는 것이고, 창은 10초 ±1이며,
    /// Mac은 자기 호출 하나(후보 3개)와만 대조한다.
    static var responseBytes: Int { PeriAdvertToken.responseBytes }

    /// 신선도 창(초). 재전송이 유효한 시간이 여기에 ±1 창을 더한 만큼으로 닫힌다.
    static let epochSeconds: TimeInterval = 10

    private static let info = Data("PeriPage-v2".utf8)
    private static let respInfo = Data("PeriPage-v2/resp".utf8)

    static func currentEpoch(_ now: Date = Date()) -> Int64 {
        Int64((now.timeIntervalSince1970 / epochSeconds).rounded(.down))
    }

    /// (링크 키, nonce) → 유도키. 링크 키가 상대별이라 **부를 상대마다** 한 번씩이다.
    static func deriveKey(linkKey: Data, nonce: Data) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: linkKey),
                               salt: nonce, info: info, outputByteCount: 32)
    }

    /// 응답용 유도키. 호출과 **같은 재료·다른 라벨**이라 서로를 계산하지 못한다.
    ///
    /// 응답을 따로 두는 이유: 호출 광고는 방송이라 지나가는 누구나 되받아칠 수 있다. 응답에
    /// MAC이 없으면 Mac이 아무 응답에나 GATT를 붙어 시간을 버린다. 이 몇 바이트가 "네가 부른
    /// 그 폰이 여기 있다"까지를 말한다 — 그 이상은 STS가 정한다.
    static func responseKey(linkKey: Data, nonce: Data) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: linkKey),
                               salt: nonce, info: respInfo, outputByteCount: 32)
    }

    /// 폰이 광고에 실어 되돌리는 [responseBytes]바이트.
    static func response(linkKey: Data, nonce: Data,
                         epoch: Int64 = currentEpoch()) -> [UInt8] {
        otp(key: responseKey(linkKey: linkKey, nonce: nonce), epoch: epoch,
            bytes: responseBytes)
    }

    /// 받은 응답이 이 호출에 대한 것인가. 창은 호출과 같은 ±1이다.
    static func matches(response got: Data, linkKey: Data, nonce: Data,
                        now: Date = Date()) -> Bool {
        guard got.count == responseBytes else { return false }
        let key = responseKey(linkKey: linkKey, nonce: nonce)
        let e = currentEpoch(now)
        return ((e - 1)...(e + 1)).contains {
            Data(otp(key: key, epoch: $0, bytes: responseBytes)) == got
        }
    }

    /// 유도키 + 창 → [bytes]바이트. **RFC 4226 동적 절단**을 쓴다.
    ///
    /// 잘라 낼 위치를 HMAC 자신이 정한다: 마지막 바이트의 하위 4비트가 시작 오프셋이다.
    /// 늘 같은 자리(앞 N바이트)를 쓰면 그 자리에 있을지 모르는 편향이 매번 같은 자리에
    /// 그대로 나온다 — 위치를 값에 따라 흩어 두면 그런 것이 한곳에 쌓이지 않는다.
    ///
    /// 오프셋은 0~15고 32바이트에서 최대 8을 떼므로 끝이 23 — 범위를 벗어날 수 없다.
    /// RFC와 달리 첫 바이트의 최상위 비트는 **안 지운다.** 그건 정수로 바꿔 십진수를 뽑을 때
    /// 부호 때문에 필요한 것이고, 우리는 바이트를 그대로 비교하므로 1비트를 버릴 이유가 없다.
    static func otp(key: SymmetricKey, epoch: Int64, bytes: Int = otpBytes) -> [UInt8] {
        var msg = Data()
        withUnsafeBytes(of: epoch.bigEndian) { msg.append(contentsOf: $0) }
        let code = Array(HMAC<SHA256>.authenticationCode(for: msg, using: key))
        let offset = Int(code[code.count - 1] & 0x0F)
        return Array(code[offset ..< (offset + bytes)])
    }

    /// 광고에 실을 UUID. 접두 4B ‖ nonce 4B ‖ OTP 8B. (epoch은 싣지 않는다 — 양쪽이 계산한다.)
    static func uuid(linkKey: Data,
                     nonce: Data = randomNonce(),
                     epoch: Int64 = currentEpoch()) -> UUID {
        var bytes = prefix
        bytes.append(contentsOf: nonce.prefix(nonceBytes))
        bytes.append(contentsOf: otp(key: deriveKey(linkKey: linkKey, nonce: nonce),
                                     epoch: epoch))
        return UUID(uuid: (bytes[0], bytes[1], bytes[2], bytes[3],
                           bytes[4], bytes[5], bytes[6], bytes[7],
                           bytes[8], bytes[9], bytes[10], bytes[11],
                           bytes[12], bytes[13], bytes[14], bytes[15]))
    }

    static func randomNonce() -> Data {
        var d = Data(count: nonceBytes)
        d.withUnsafeMutableBytes {
            _ = SecRandomCopyBytes(kSecRandomDefault, nonceBytes, $0.baseAddress!)
        }
        return d
    }
}
