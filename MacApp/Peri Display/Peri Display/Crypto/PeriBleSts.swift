//
//  PeriBleSts.swift
//  Peri Display
//
//  **BLE 재연결** — 이미 페어링한 상대와 다시 만날 때. PIN을 묻지 않는다.
//  Android `crypto/PeriBleSts.kt`와 바이트 단위로 같아야 한다.
//
//  ## 언제 이 경로인가 — 폰은 아무것도 기억하지 않는다
//
//  Mac이 HELLO에 **호출 토큰과 똑같은 TOTP**를 싣는다. 폰은 신뢰 목록을 훑어 각 Mac의 링크
//  키로 같은 값을 만들어 보고, 맞는 게 있으면 그 상대와 STS로 간다. 없으면 PAKE다.
//
//  요점은 **폰이 "아까 호출당했다" 같은 상태를 들고 있지 않다**는 것이다. 그런 깃발을 두면
//  깃발이 선 짧은 창 안에 아무나 끼어들어 그걸 소비할 수 있다. 여기서는 매 연결이 스스로
//  자격을 증명한다 — 기억할 게 없으니 훔칠 것도 없다.
//
//  힌트는 **추측**이지 인증이 아니다. 창(±1) 안에서 재생되므로 남의 HELLO를 녹음해 다시
//  쏘면 폰이 STS를 시작하기는 한다. 거기서 끝이다 — 신원은 서명으로만 정해진다.
//
//  ## 흐름 (Mac이 개시자, 하지만 STS 역할은 LAN과 같다)
//
//      1. Mac → HELLO : [mode=2][nonce_M][totp]
//      2. And → STS1  : X_A ‖ PK_A                      (Android = client)
//      3. Mac → STS2  : X_B ‖ ct ‖ E_K(S_mac)
//      4. And → STS3  : E_K(S_and)
//
//  Mac은 자기가 부른 그 상대의 공개키로만 검증한다(누굴 불렀는지 안다). Android는 TOTP가
//  짚어 준 그 Mac의 공개키로만 검증한다. **양쪽의 짐작이 서명으로 확정되는 지점**이 여기다.
//
//  ## 전사
//
//      th = SHA256( LP(tag) ‖ LP(hello) ‖ LP(sts1) ‖ LP(sts2core) )
//
//  `hello`가 통째로 들어가므로 **TOTP도 서명에 덮인다.** 중간자가 mode 바이트를 갈아끼우거나
//  힌트를 건드리면 양쪽 전사가 갈라져 서명 검증이 그 자리에서 죽는다.
//
//  ## LAN STS와 무엇이 다른가
//
//  실어 나르는 게 protobuf가 아니라 BLE 프레임이다. LAN 쪽 `core`에는 코덱·해상도·HDR 협상이
//  들어 있는데 여기서는 협상할 게 없다 — 이 채널은 시그널링이고, 실제 세션은 TCP에서 자기
//  STS를 다시 돈다. 컨텍스트 태그도 갈라 두어 한쪽 서명이 다른 쪽에 재생되지 않는다.
//

import Foundation
import CryptoKit

enum PeriBleSts {

    private static let tag = Data("PeriBLE-sts-v1".utf8)
    private static let macCtx = Data("PeriBLE-sts-v1/mac".utf8)
    private static let androidCtx = Data("PeriBLE-sts-v1/android".utf8)
    private static let authInfo = Data("PeriBLE-sts-v1/auth".utf8)
    private static let hintInfo = Data("PeriBLE-sts-v1/hint".utf8)

    /// HELLO에 실리는 힌트 길이 — **HMAC 전체.** 자르지 않는다.
    ///
    /// 호출 토큰이 8바이트인 건 광고 예산(31B) 때문이지 그게 충분해서가 아니다. 여기는 GATT라
    /// 그 예산이 없다. 자르면 충돌이 생기고, 충돌은 "힌트가 A를 짚었는데 서명은 B"라는 상태를
    /// 만들어 라운드를 죽인다 — 24바이트 아끼자고 만들 문제가 아니다.
    static let hintBytes = 32

    // MARK: - 힌트

    /// HELLO 힌트의 유도키. **호출 토큰과 라벨이 다르다.**
    ///
    /// 구성이 같다고 같은 `dk`를 쓰면 안 된다. 호출 토큰은 **방송**이라 지나가는 누구나 8바이트
    /// 표본을 모을 수 있는데, 같은 키를 HELLO 힌트가 재사용하면 두 값이 한 열쇠 아래 묶인다.
    /// 창이 짧아 지금은 실질 위험이 작지만, 그건 창 길이에 기댄 안전이지 구조적 안전이 아니다.
    /// 라벨 하나로 두 값이 서로를 계산하지 못하게 못 박는 편이 싸다.
    static func hintKey(linkKey: Data, nonce: Data) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: linkKey),
                               salt: nonce, info: hintInfo, outputByteCount: 32)
    }

    /// HELLO에 실을 TOTP. 소금은 이 라운드의 논스다.
    ///
    /// Mac 공개키는 안 들어간다 — 링크 키가 이미 상대별이라 **어느 링크 키로 맞았는가**가
    /// 곧 누가 부르는지다.
    static func hint(linkKey: Data, nonce: Data,
                     epoch: Int64 = PeriPageToken.currentEpoch()) -> Data {
        var msg = Data()
        withUnsafeBytes(of: epoch.bigEndian) { msg.append(contentsOf: $0) }
        return Data(HMAC<SHA256>.authenticationCode(
            for: msg, using: hintKey(linkKey: linkKey, nonce: nonce)))
    }

    // MARK: - 전사

    private static func lp(_ d: Data) -> Data {
        var out = Data()
        withUnsafeBytes(of: UInt32(d.count).bigEndian) { out.append(contentsOf: $0) }
        out.append(d)
        return out
    }

    /// 선에 실제로 나간 바이트를 그대로 묶는다. `responseCore`는 STS2에서 봉인 블롭 **앞**까지다.
    static func transcript(hello: Data, stsHello: Data, responseCore: Data) -> Data {
        var t = lp(tag)
        t.append(lp(hello)); t.append(lp(stsHello)); t.append(lp(responseCore))
        return Data(SHA256.hash(data: t))
    }

    static func macSigningBytes(_ th: Data) -> Data { macCtx + th }
    static func androidSigningBytes(_ th: Data) -> Data { androidCtx + th }

    // MARK: - 키

    struct Keys {
        /// 서명 봉인 전용(스펙의 K).
        let auth: SymmetricKey
        /// 봉인된 BLE 프레임(PARAMS·CANDIDATES·PUNCH)이 타는 세션. PAKE 경로와 같은 라벨이라
        /// 그 뒤의 코드가 어느 경로로 왔는지 몰라도 된다.
        let session: PeriCryptoSession
    }

    /// 전사를 소금으로 쓴다 — 임시키가 신선도를 주는 데다, 이 라운드 밖에서는 같은 키가 안 난다.
    static func derive(ecdh: Data, kem: Data, transcript: Data) -> Keys {
        let ikm = ecdh + kem
        let auth = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ikm),
                                          salt: transcript, info: authInfo, outputByteCount: 32)
        return Keys(auth: auth, session: PeriCryptoSession.pairing(ikm: ikm, salt: transcript))
    }
}
