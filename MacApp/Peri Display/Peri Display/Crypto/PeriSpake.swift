//
//  PeriSpake.swift
//  Peri Display
//
//  SPAKE2+EE — 짧은 PIN 하나로 BLE 첫 페어링의 **능동 중간자**를 막는다.
//  Android `crypto/Spake2.kt`와 **같은 C**(ThirdParty/spake2-ee)를 부른다.
//
//  왜 이게 필요했나
//  ───────────────
//  예전 방식(commitment + 6자리 SAS 비교)은 코드가 **키 교환을 전혀 묶지 않았다.** 양쪽
//  nonce와 인증서만 해시했기 때문에, 중간자가 그 둘을 그대로 흘려보내면서 x25519·ML-KEM만
//  자기 것으로 갈아끼우면 두 기기에 **같은 6자리가 뜬다.** 사람이 눈으로 맞다고 확인해도
//  실제로는 각자 공격자와 키를 나눠 가진 상태다. OTP도 마찬가지였다 — 봉인해 보내 봐야
//  중간자가 자기 키로 풀었다 다시 봉해 넘기면 그만이다.
//
//  PAKE는 그 구멍이 원리적으로 없다. **세션 키 자체가 PIN에서 나오므로** PIN을 모르는
//  중간자는 어느 쪽과도 키를 세우지 못하고, 오프라인 대입도 불가능하다. 남는 건 온라인
//  추측 한 번뿐이고 그건 라운드 폐기로 막는다.
//
//  역할
//  ────
//  SPAKE2+EE는 augmented라 비대칭이다 — **PIN 원문을 쥔 쪽이 client**, 검증자를 쥔 쪽이
//  server다. PIN을 만들어 화면에 띄우는 건 Android이므로 **Mac이 client**다.
//  ([SpakeServer]도 두는 건 한 프로세스에서 전 과정을 도는 검증 테스트를 위해서다.)
//
//  `clientId`/`serverId`에는 **장기 신원키 지문**을 싣는다. 그래서 PAKE 통과가 곧 "이 PIN을
//  본 사람이 이 신원키의 주인"이라는 증명이 된다 — 중간자가 인증서를 바꿔치면 id가 어긋나
//  마지막 검증에서 죽는다.
//
//  ⚠ **기회는 한 번이다.** 틀린 PIN이면 라운드도 PIN도 버린다. 재시도를 허용하면 온라인
//  추측이 반복 가능해져 4자리(1/10⁴)가 금방 뚫린다. 그 규칙은 페어링 기계가 지킨다.
//
//  실패는 전부 nil이다. 이유를 나누지 않는 것도 설계다 — PAKE에서 "왜 틀렸는지"는 오라클이다.
//

import Foundation

/// libsodium 1회 초기화. Swift 전역 `let`은 지연 + 스레드 안전이라 이게 곧 dispatch_once다.
private let sodiumReady: Bool = { sodium_init() >= 0 }()

/// PAKE가 낸 방향별 키 두 개.
///
/// 이걸 그대로 세션 키로 쓰지 않는다 — ML-KEM 공유비밀과 함께 HKDF에 넣어야 "PIN과
/// 양자내성 키교환 **둘 다** 깨야 한다"가 성립한다.
struct SpakeKeys {
    let clientSK: Data
    let serverSK: Data
}

enum PeriSpake {
    /// 선에 실리는 값들의 길이 (ThirdParty/spake2-ee/crypto_spake.h).
    static let publicDataBytes = Int(crypto_spake_PUBLICDATABYTES)
    static let response1Bytes = Int(crypto_spake_RESPONSE1BYTES)
    static let response2Bytes = Int(crypto_spake_RESPONSE2BYTES)
    static let response3Bytes = Int(crypto_spake_RESPONSE3BYTES)
    static let sharedKeyBytes = Int(crypto_spake_SHAREDKEYBYTES)

    /// Argon2id 파라미터 — **양쪽에 못 박고 받은 값을 대조한다.**
    ///
    /// PIN이 일회용이라 암호 해싱이 오프라인 내성을 사 주는 건 아니다(그건 PAKE가 이미 한다).
    /// 그런데 이 값이 선을 타고 오므로, 대조하지 않으면 상대가 memlimit에 4GiB를 적어 보내
    /// 우리를 죽일 수 있다. libsodium의 INTERACTIVE 기본값에 고정하고 벗어나면 거부한다.
    static let opsLimit: UInt64 = 2
    static let memLimit: Int = 67_108_864   // 64 MiB

    /// Argon2id13. C에서 읽어 온다 — 상수를 두 곳에 적으면 언젠가 어긋난다.
    static var alg: Int32 { crypto_pwhash_alg_default() }

    /// 상대가 보낸 파라미터가 우리가 기대하는 그것인가.
    static func accepts(_ publicData: Data) -> Bool {
        guard sodiumReady, publicData.count == publicDataBytes else { return false }
        return publicData.withUnsafeBytes { p in
            crypto_spake_validate_public_data(
                p.bindMemory(to: UInt8.self).baseAddress!,
                // 대조 함수만 memlimit을 unsigned long long로 받는다(나머지는 size_t).
                alg, opsLimit, UInt64(memLimit)) == 0
        }
    }
}

// MARK: - client (Mac의 역할)

/// 돌고 있는 client 라운드. 상태에 키 재료가 있어 `deinit`에서 지운다.
final class SpakeClient {

    private var state = crypto_spake_client_state()

    /// 상대에게 보낼 첫 조각.
    let response1: Data

    /// ⚠ Argon2id가 64MiB를 돌린다 — **BLE 콜백 스레드에서 만들지 말 것.**
    init?(publicData: Data, pin: String) {
        guard sodiumReady, PeriSpake.accepts(publicData) else { return nil }
        var out = [UInt8](repeating: 0, count: PeriSpake.response1Bytes)
        var secret = Array(pin.utf8)
        defer { sodium_memzero(&secret, secret.count) }

        // 상태를 지역 변수로 두고 마지막에 옮긴다 — 클로저 안에서 `&state`를 쓰면 `self`가
        // 저장 프로퍼티 초기화 전에 캡처된다.
        var st = crypto_spake_client_state()
        let ok = publicData.withUnsafeBytes { pub in
            secret.withUnsafeBufferPointer { pw in
                pw.baseAddress!.withMemoryRebound(to: CChar.self, capacity: pw.count) { pwc in
                    crypto_spake_step1(&st, &out,
                                       pub.bindMemory(to: UInt8.self).baseAddress!,
                                       pwc, UInt64(pw.count)) == 0
                }
            }
        }
        guard ok else {
            sodium_memzero(&st, MemoryLayout<crypto_spake_client_state>.size)
            return nil
        }
        state = st
        response1 = Data(out)
        sodium_memzero(&st, MemoryLayout<crypto_spake_client_state>.size)
    }

    /// response2 검증 → (보낼 response3, 공유 키). nil이면 상대가 PIN을 몰랐다.
    func finish(response2: Data, clientID: Data, serverID: Data)
        -> (response3: Data, keys: SpakeKeys)? {
        guard response2.count == PeriSpake.response2Bytes else { return nil }
        var out = [UInt8](repeating: 0, count: PeriSpake.response3Bytes)
        var keys = crypto_spake_shared_keys()
        defer { sodium_memzero(&keys, MemoryLayout.size(ofValue: keys)) }

        let ok = response2.withUnsafeBytes { r2 in
            clientID.withUnsafeBytes { cid in
                serverID.withUnsafeBytes { sid in
                    crypto_spake_step3(&state, &out, &keys,
                                       cid.baseAddress?.assumingMemoryBound(to: CChar.self),
                                       cid.count,
                                       sid.baseAddress?.assumingMemoryBound(to: CChar.self),
                                       sid.count,
                                       r2.bindMemory(to: UInt8.self).baseAddress!) == 0
                }
            }
        }
        guard ok else { return nil }
        return (Data(out), keys.split())
    }

    deinit { sodium_memzero(&state, MemoryLayout<crypto_spake_client_state>.size) }
}

// MARK: - server (Android의 역할 — 여기선 검증 테스트용)

/// 돌고 있는 server 라운드.
final class SpakeServer {

    private var state = crypto_spake_server_state()
    private var stored = [UInt8](repeating: 0, count: Int(crypto_spake_STOREDBYTES))

    /// 상대에게 보낼 파라미터(암호 해싱 설정만 들었다 — 비밀 아님).
    let publicData: Data

    /// ⚠ Argon2id가 돈다.
    init?(pin: String) {
        guard sodiumReady else { return nil }
        var secret = Array(pin.utf8)
        defer { sodium_memzero(&secret, secret.count) }

        // 지역 변수를 거치는 이유는 [SpakeClient.init]과 같다.
        var verifier = [UInt8](repeating: 0, count: Int(crypto_spake_STOREDBYTES))
        var st = crypto_spake_server_state()
        defer { sodium_memzero(&verifier, verifier.count) }

        let stashed = secret.withUnsafeBufferPointer { pw in
            pw.baseAddress!.withMemoryRebound(to: CChar.self, capacity: pw.count) { pwc in
                crypto_spake_server_store(&verifier, pwc, UInt64(pw.count),
                                          PeriSpake.opsLimit, PeriSpake.memLimit) == 0
            }
        }
        var pub = [UInt8](repeating: 0, count: PeriSpake.publicDataBytes)
        guard stashed, crypto_spake_step0(&st, &pub, verifier) == 0 else {
            sodium_memzero(&st, MemoryLayout<crypto_spake_server_state>.size)
            return nil
        }
        stored = verifier
        state = st
        publicData = Data(pub)
        sodium_memzero(&st, MemoryLayout<crypto_spake_server_state>.size)
    }

    /// response1 수신 → 보낼 response2.
    func step2(response1: Data, clientID: Data, serverID: Data) -> Data? {
        guard response1.count == PeriSpake.response1Bytes else { return nil }
        var out = [UInt8](repeating: 0, count: PeriSpake.response2Bytes)
        let ok = response1.withUnsafeBytes { r1 in
            clientID.withUnsafeBytes { cid in
                serverID.withUnsafeBytes { sid in
                    crypto_spake_step2(&state, &out,
                                       cid.baseAddress?.assumingMemoryBound(to: CChar.self),
                                       cid.count,
                                       sid.baseAddress?.assumingMemoryBound(to: CChar.self),
                                       sid.count,
                                       stored,
                                       r1.bindMemory(to: UInt8.self).baseAddress!) == 0
                }
            }
        }
        return ok ? Data(out) : nil
    }

    /// response3 검증 → 공유 키. **여기서 상대가 PIN을 알았다는 게 증명된다.**
    func step4(response3: Data) -> SpakeKeys? {
        guard response3.count == PeriSpake.response3Bytes else { return nil }
        var keys = crypto_spake_shared_keys()
        defer { sodium_memzero(&keys, MemoryLayout.size(ofValue: keys)) }
        let ok = response3.withUnsafeBytes { r3 in
            crypto_spake_step4(&state, &keys,
                               r3.bindMemory(to: UInt8.self).baseAddress!) == 0
        }
        return ok ? keys.split() : nil
    }

    deinit {
        sodium_memzero(&state, MemoryLayout<crypto_spake_server_state>.size)
        sodium_memzero(&stored, stored.count)
    }
}

// MARK: -

private extension crypto_spake_shared_keys {
    /// C의 고정길이 배열은 Swift에 튜플로 들어온다 — 자기 자신의 메모리를 그대로 읽는 게
    /// 튜플 원소를 하나씩 꺼내는 것보다 정확하고 짧다.
    func split() -> SpakeKeys {
        var copy = self
        let n = Int(crypto_spake_SHAREDKEYBYTES)
        return withUnsafeBytes(of: &copy) { raw in
            SpakeKeys(clientSK: Data(raw[0..<n]), serverSK: Data(raw[n..<(n * 2)]))
        }
    }
}
