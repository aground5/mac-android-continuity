//
//  PairingMachine.swift
//  Peri Display
//
//  BLE 페어링의 **순수 상태 기계** — Mac(개시자) 쪽.
//
//  ## v2에서 무엇이 사라졌나
//
//  `PairMode`(sas/otp/reauth)와 `VerifyMethod`가 통째로 없다. 그 두 축은 "이번 라운드에
//  사람을 부를 것인가, 부른다면 어떤 방식으로"를 **양쪽이 합의**하기 위해 있었고, 합의가
//  어긋나면 두 기기가 서로 다른 프레임을 기다리며 멈추는 게 이 코드베이스의 오래된 버그였다.
//
//  v2는 그 질문 자체를 없앴다. **모든 페어링 라운드가 PIN을 거친다.** 협상할 게 없으니
//  어긋날 것도 없고, 모드를 실어 나르던 프레임(VERIFY_REQUIRED·SERVER_AUTH·CLIENT_AUTH·
//  CLIENT_CONFIRM·CONFIRM)도 전부 사라졌다.
//
//  더 중요한 건 안전성이다. 옛 SAS는 코드가 **키 교환을 전혀 묶지 않아서**, 중간자가 양쪽
//  nonce·인증서를 그대로 흘리며 x25519와 ML-KEM만 갈아끼우면 두 기기에 같은 6자리가 떴다.
//  OTP도 봉인해 보내는 값이라 중간자가 풀었다 다시 봉하면 그만이었다. v2는 PAKE라 그 구멍이
//  원리적으로 없다 — **세션 키 자체가 PIN에서 나온다.**
//
//  ## 남은 축은 하나다
//
//  Phase — 프로토콜 진행. 순서 제약과 마감이 전부 여기 있다.
//
//  ## 기회는 한 번
//
//  4자리는 온라인 추측이 1/10⁴이다. 오프라인 대입은 PAKE가 막지만, **재시도를 허용하면**
//  그 1/10⁴이 반복 가능해진다. 그래서 PIN이 틀리면(= PAKE2 검증 실패) 이 기계는 라운드를
//  닫고 링크를 끊는다. 다시 하려면 처음부터고, 그러면 새 임시키·새 PIN이 뽑힌다.
//

import Foundation

// MARK: - 축

/// 프로토콜 진행 단계. **대기 단계에는 전부 마감이 있다** — 아래 `deadline`.
enum PairPhase: Equatable, Sendable {
    case idle
    /// 주변 탐색 중. 사람이 고를 때까지 머물러도 되는 유일한 대기다.
    case scanning
    /// BLE 링크 성립 대기.
    case linking
    /// HELLO를 보냈고 상대의 첫 응답을 기다린다.
    ///
    /// 여기서 두 갈래가 갈린다. HELLO에 무엇을 실었느냐(`PairState.sts`)에 따라 OFFER(PAKE)
    /// 또는 STS1이 온다. **그 선택은 우리가 했다** — 링크 키가 있으면 STS, 없으면 PAKE.
    case offering
    /// STS2(우리 서명 포함)를 보냈고 상대 서명을 기다린다. 재연결 경로에만 있다.
    case stsConfirming
    /// 기기 화면에 PIN이 떴다. **사람이 그걸 옮겨 적기를** 기다린다.
    case enteringPin
    /// PAKE1을 보냈고 PAKE2를 기다린다. 여기서 PIN이 맞았는지 갈린다.
    case proving
    /// PAKE가 끝나 채널이 봉인됐다. 우리 신원을 보냈고 상대 신원을 기다린다.
    case identifying
    /// 서로 핀했다. 후보를 보내고 상대 후보를 기다린다.
    case awaitingCandidates
    /// 개시 신호를 보냈고 펀칭 창이 끝나기를 기다린다.
    case punching
    /// 라운드 종료(성공).
    case done
    case failed(String)

    /// 이 단계에 머무를 수 있는 상한(초). nil = 마감 없음.
    ///
    /// ★ 이 프로퍼티가 존재하는 이유는 옛 버그다. 예전엔 대기가 **제어 흐름**으로만
    /// 표현돼 있어서(`while Date() < deadline { sleep }`) 어떤 대기에 마감이 있는지 사람이
    /// 코드를 읽어야 알 수 있었고, 정작 갇히는 단계에는 아무 마감도 없었다.
    var deadline: TimeInterval? {
        switch self {
        case .idle, .scanning, .done, .failed: return nil   // 사건을 기다리는 게 정상인 상태
        case .linking: return 15
        case .offering: return 10
        // ML-KEM 공개키 1184B가 BLE로 건너오는 시간까지 든다.
        case .stsConfirming: return 10
        // 사람이 기기 화면을 보고 옮겨 적는 시간 — 넉넉하되 무한은 아니다.
        case .enteringPin: return 120
        // Argon2id가 양쪽에서 한 번씩 도므로(64MiB) 왕복보다 계산이 오래 걸린다.
        case .proving: return 15
        case .identifying: return 10
        // Android도 STUN 왕복을 해야 한다. 여기서 조급하게 끊으면 펀칭 자체가 불가능해진다.
        case .awaitingCandidates: return 15
        // 양쪽 펀치 타임아웃(10s) + 여유. 이 창 동안 BLE를 붙잡아 재조율 여지를 남긴다.
        case .punching: return 13
        }
    }
}

struct PairState: Equatable, Sendable {
    var phase: PairPhase = .idle
    /// 이번 라운드가 **재연결(STS)**인가. 링크 키를 가진 상대에 걸 때만 true.
    ///
    /// 이 값이 PAKE 경로와 STS 경로의 순서 제약을 갈라 준다 — `.sts`인데 OFFER가 오면 그건
    /// 우리가 요청하지 않은 것이고, 반대도 마찬가지다. 리듀서가 둘 다 거절한다.
    var sts = false
    /// 상대 후보가 도착했는가. 펀칭 창으로 넘어가는 조건.
    var gotRemoteCandidates = false
    /// PIN을 이미 한 번 제출했는가 — **두 번째 입력을 막는 유일한 장치**.
    var pinSubmitted = false

    /// UI 한 줄 — **계산값**이다. 예전엔 `status` 문자열을 9곳에서 직접 대입했다.
    var summary: String {
        switch phase {
        case .idle: return "대기"
        case .scanning: return "주변 기기 탐색 중…"
        case .linking: return "연결 중…"
        case .offering: return sts ? "기기 확인 중…" : "페어링 교환 중…"
        case .stsConfirming: return "기기 확인 중…"
        case .enteringPin: return "기기 화면의 PIN을 입력하세요 (기회 1회)"
        case .proving: return "PIN 확인 중…"
        case .identifying: return "신원 교환 중…"
        case .awaitingCandidates: return "연결 경로 교환 중…"
        case .punching: return "경로 연결 중…"
        // "연결됨"이라고 하지 않는다 — 이 단계가 뜻하는 건 **페어링 라운드가 끝났다**는
        // 것뿐이고 실제 연결 상태는 이 기계가 모른다(기기 등록부가 안다).
        case .done: return "연결 준비 완료"
        case .failed(let why): return why
        }
    }

    /// 사람이 PIN을 입력해야 하는 상태인가 — UI가 입력칸을 띄울지의 유일한 기준.
    var needsPin: Bool { phase == .enteringPin && !pinSubmitted }
}

// MARK: - 사건

enum PairEv: Equatable, Sendable {
    case startScan
    case stopScan
    /// 사용자가 기기를 골랐거나, 광고의 재협상 비트를 보고 우리가 붙기로 했다.
    ///
    /// `sts`는 **우리가 그 상대의 링크 키를 들고 있는가**다. 실행기가 신뢰저장소를 보고 정한다.
    case connectRequested(sts: Bool)
    /// BLE 채널 준비 완료(특성 탐색·notify까지).
    case linkReady
    case linkLost

    /// OFFER 도착 — 실행기가 KEM 복호와 파라미터 대조까지 끝냈다.
    /// 이 순간 기기 화면에는 PIN이 떠 있다.
    case offerReceived
    /// 사람이 PIN을 입력했다. **기회는 이번 한 번뿐이다.**
    case pinSubmitted(String)
    /// PAKE2 검증 성공 → 세션이 섰다. 여기서 상대가 PIN을 알았다는 게 증명된다.
    case pakeAccepted
    /// STS1 도착 — 실행기가 캡슐화·서명·봉인까지 끝내 STS2를 들고 있다.
    case stsHelloReceived
    /// STS3의 서명이 **우리가 부른 그 기기**의 것으로 확인됐다.
    case stsConfirmed
    /// 봉인된 채널로 상대 인증서가 도착했다.
    case peerIdentity
    /// 상대가 라운드를 닫았다.
    case peerAborted(String)

    case remoteCandidates
    /// 현재 단계의 마감이 지났다. 실행기가 `phase.deadline`으로 무장한다.
    ///
    /// 펀칭 창이 끝나는 것도 이 사건이다 — 별도 사건을 두면 같은 뜻의 타이머가 둘이 되고,
    /// 그 둘이 어긋나는 게 정확히 이 기계가 없애려던 종류의 결함이다.
    case deadlineElapsed
    case failed(String)
}

// MARK: - 부작용

enum PairFx: Equatable, Sendable {
    case startScan
    case stopScan
    case connect
    case disconnect
    /// HELLO 송신. `sts`면 TOTP 힌트를, 아니면 새 ML-KEM 공개키를 싣는다.
    case sendHello(sts: Bool)
    /// STS2 송신 — 캡슐화 결과와 봉인된 우리 서명.
    case sendStsResponse
    /// 입력받은 PIN으로 PAKE를 시작한다(step1 → PAKE1 송신).
    ///
    /// ⚠ Argon2id가 64MiB를 돌린다. 실행기가 BLE 콜백 스레드를 막지 않도록 해야 한다.
    case beginPake(String)
    /// step3 결과(response3)를 보내고 이어서 우리 인증서를 봉인해 보낸다.
    case sendProofAndIdentity
    /// 상대 인증서를 핀한다(신뢰저장소 기록).
    case pinPeer
    case sendCandidates
    case sendPunchStart
    case beginPunch
    /// 현재 단계 마감 무장/해제. 값은 `PairPhase.deadline`.
    case armDeadline(TimeInterval)
    case cancelDeadline
    case log(String)
}

struct PairStep: Equatable {
    var state: PairState
    var fx: [PairFx] = []
}

// MARK: - 전이

/// 단계를 옮기며 마감 무장/해제를 **자동으로** 끼워 넣는다.
///
/// 손으로 `armDeadline`을 붙이게 두면 언젠가 하나를 빠뜨리고, 그게 곧 무한 대기다.
/// 여기 한 곳에서만 만들어지게 한다.
private func goto(_ s: PairState, _ phase: PairPhase, _ fx: [PairFx] = []) -> PairStep {
    var n = s
    n.phase = phase
    var out: [PairFx] = [.cancelDeadline]
    out.append(contentsOf: fx)
    if let d = phase.deadline { out.append(.armDeadline(d)) }
    return PairStep(state: n, fx: out)
}

func reduce(_ s: PairState, _ ev: PairEv) -> PairStep {
    // 종료 상태는 되살아나지 않는다 — 새 라운드는 `startScan`/`connectRequested`로 시작한다.
    if case .failed = s.phase {
        switch ev {
        case .startScan, .connectRequested: break
        default: return PairStep(state: s)
        }
    }
    // `startScan`/`stopScan`은 라운드를 새로 여는 사건이라 아래에서 PairState()로 초기화된다
    // — 그때 `sts`도 같이 지워진다(기본값 false).

    switch ev {

    case .startScan:
        return goto(PairState(), .scanning, [.startScan])

    case .stopScan:
        return goto(PairState(), .idle, [.stopScan])

    case .connectRequested(let sts):
        // 사람이 PIN을 옮겨 적는 중이면 가로채지 않는다 — 입력 화면을 날려버린다.
        // 끝난 라운드(`.done`)는 가로챌 것이 없다 — 거기서 막으면 한 번 붙었다 끊은 기기에
        // 다시 붙을 수 없다(Android 쪽에서 정확히 그 버그가 났다 — `PairPhase.inRound` 참조).
        switch s.phase {
        case .idle, .scanning, .done, .failed:
            return goto(PairState(sts: sts), .linking, [.connect])
        default:
            return PairStep(state: s, fx: [.log("연결 요청 보류 — 페어링 진행 중")])
        }

    case .linkReady:
        guard s.phase == .linking else { return PairStep(state: s) }
        return goto(s, .offering, [.sendHello(sts: s.sts)])

    case .offerReceived:
        // ★ **STS를 걸어 놓고 OFFER를 받아들이면 그게 강등이다.** 우리는 링크 키로 상대를
        // 지목해 불렀는데 상대가 "PIN으로 하자"고 답하는 상황이고, 그건 정상 경로에 없다.
        guard s.phase == .offering, !s.sts else {
            return PairStep(state: s, fx: [.log("단계 밖 OFFER 무시")])
        }
        return goto(s, .enteringPin, [.log("기기 화면의 PIN을 기다린다")])

    case .stsHelloReceived:
        guard s.phase == .offering, s.sts else {
            return PairStep(state: s, fx: [.log("단계 밖 STS1 무시")])
        }
        return goto(s, .stsConfirming, [.sendStsResponse])

    case .stsConfirmed:
        // 핀하지 않는다 — 이미 신뢰된 상대이고, 여기엔 인증서도 오지 않는다.
        guard s.phase == .stsConfirming else {
            return PairStep(state: s, fx: [.log("단계 밖 STS3 무시")])
        }
        return goto(s, .awaitingCandidates, [.sendCandidates])

    case .pinSubmitted(let pin):
        // ★ **두 번째 입력은 없다.** 이 가드가 온라인 추측을 1회로 묶는다.
        guard s.needsPin else {
            return PairStep(state: s, fx: [.log("PIN을 받을 단계가 아니다")])
        }
        var n = s
        n.pinSubmitted = true
        return goto(n, .proving, [.beginPake(pin)])

    case .pakeAccepted:
        guard s.phase == .proving else {
            return PairStep(state: s, fx: [.log("단계 밖 PAKE 결과 무시")])
        }
        return goto(s, .identifying, [.sendProofAndIdentity])

    case .peerIdentity:
        // ★ **핀이 나오는 경로는 여기 하나뿐이다.** 다른 데서 핀하면 그게 곧 PAKE 없이
        // 신원이 고정되는 구멍이 된다 — 검사가 그걸 본다.
        guard s.phase == .identifying else {
            return PairStep(state: s, fx: [.log("단계 밖 신원 무시")])
        }
        return goto(s, .awaitingCandidates, [.pinPeer, .sendCandidates])

    case .peerAborted(let why):
        return goto(s, .failed(why), [.disconnect])

    case .remoteCandidates:
        guard s.phase == .awaitingCandidates else {
            return PairStep(state: s, fx: [.log("단계 밖 후보 무시")])
        }
        var n = s
        n.gotRemoteCandidates = true
        // 개시 신호를 보내고 **곧바로** 우리도 시작한다 — TCP simultaneous open은 양쪽이
        // 같은 쌍을 거의 동시에 두드려야 성립한다.
        return goto(n, .punching, [.sendPunchStart, .beginPunch])

    case .deadlineElapsed:
        switch s.phase {
        case .awaitingCandidates:
            // 후보가 안 왔다고 실패가 아니다 — 같은 LAN이면 host 후보로 붙는다.
            return goto(s, .done, [
                .log("상대 후보 미수신 — 펀칭 불가, 같은 LAN이면 host 후보로 접속된다"),
                .disconnect,
            ])
        case .punching:
            return goto(s, .done, [.disconnect])
        case .enteringPin:
            return goto(s, .failed("PIN 입력 시간 초과"), [.disconnect])
        case .proving:
            // 여기 마감은 곧 "상대가 응답을 못 했다"이고, PIN이 틀렸을 때와 구분되지 않는다.
            // 구분해 봐야 사용자에게 할 말이 같으므로 한 문구로 합친다.
            return goto(s, .failed("PIN이 맞지 않거나 응답이 없습니다"), [.disconnect])
        case .linking, .offering, .identifying, .stsConfirming:
            return goto(s, .failed("\(s.phase) 단계 응답 없음"), [.disconnect])
        default:
            return PairStep(state: s)   // 마감이 없는 단계의 낡은 타이머
        }

    case .linkLost:
        switch s.phase {
        case .idle, .scanning, .done, .failed:
            return PairStep(state: s)
        case .punching, .awaitingCandidates:
            // 여기서 끊기는 건 정상 종료다(우리가 끊었거나 상대가 먼저 닫았다).
            return goto(s, .done)
        default:
            return goto(s, .failed("연결이 끊어졌습니다"))
        }

    case .failed(let why):
        return goto(s, .failed(why), [.disconnect])
    }
}
