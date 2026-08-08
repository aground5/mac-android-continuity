//
//  PairingTests.swift
//  BLE 페어링 기계 v2 (Mac = 개시자). 기기·서비스 기계와 수명이 별개라 따로 검사한다.
//
//  v1에서 이 파일이 지키던 성질 중 넷은 **문제 자체가 사라져서** 없어졌다:
//  신뢰 조합 대응표, SAS 다운그레이드 금지, 양쪽 사람 확인, 방식 협상. 그 넷은 전부
//  "이번 라운드에 사람을 부를까, 어떻게 부를까"를 두 피어가 합의하는 데서 나온 성질이고,
//  v2는 **모든 라운드가 PIN을 거치므로** 합의할 것이 없다.
//
//  대신 새로 지켜야 할 성질이 셋이다: 순서, 기회 1회, 핀은 PAKE 뒤에만.
//

import Foundation

// MARK: - 7. BLE 페어링 기계 (Mac = 개시자)

extension PairState {
    func after(_ evs: PairEv...) -> PairState { evs.reduce(self) { st, e in reduce(st, e).state } }
    func step(_ ev: PairEv) -> PairStep { reduce(self, ev) }
    func fx(_ ev: PairEv) -> [PairFx] { reduce(self, ev).fx }
}

/// HELLO를 보낸 직후 — OFFER를 기다리는 상태.
func linked() -> PairState {
    PairState().after(.startScan, .connectRequested(sts: false), .linkReady)
}

/// PIN 입력 화면까지 진행된 상태.
func awaitingPin() -> PairState { linked().after(.offerReceived) }

/// 정상 경로가 순서대로만 진행된다.
///
/// ★ Android의 `PairingMachineTest.kt`가 **거울상 순서**를 주장한다. 두 구현이 갈라지면
/// 어느 한쪽 테스트가 깨진다 — 두 피어가 보조를 맞춰야 하는 흐름이라 한쪽만 맞는 건 아무
/// 의미가 없다. 실기기에서 어긋남을 발견하기 전에 여기서 걸린다.
func testPairingHappyPath() {
    let hello = linked()
    expect(hello.phase, .offering, "HELLO를 보내고 OFFER를 기다린다")
    check(PairState().after(.startScan, .connectRequested(sts: false)).fx(.linkReady).contains(.sendHello(sts: false)),
          "링크가 서면 HELLO를 보낸다")

    let offered = hello.step(.offerReceived)
    expect(offered.state.phase, .enteringPin, "기기 화면에 PIN이 떴다 → 사람을 기다린다")
    check(offered.state.needsPin, "입력을 받을 수 있다")

    let sent = offered.state.step(.pinSubmitted("4821"))
    expect(sent.state.phase, .proving, "PAKE로 넘어간다")
    check(sent.fx.contains(.beginPake("4821")), "입력값으로 PAKE를 시작한다")
    check(!sent.state.needsPin, "제출 뒤에는 입력칸이 닫힌다")

    let proved = sent.state.step(.pakeAccepted)
    expect(proved.state.phase, .identifying, "채널이 봉인됐다 → 신원 교환")
    check(proved.fx.contains(.sendProofAndIdentity), "증명과 우리 신원을 함께 보낸다")
    check(!proved.fx.contains(.pinPeer), "상대 신원을 보기 전에는 핀하지 않는다")

    let identified = proved.state.step(.peerIdentity)
    expect(identified.state.phase, .awaitingCandidates, "핀하고 후보 교환으로")
    check(identified.fx.contains(.pinPeer), "상대 신원을 핀한다")
    check(identified.fx.contains(.sendCandidates), "후보를 보낸다")
}

/// STS1을 받을 수 있는 상태 — 링크 키를 들고 걸었다.
func stsLinked() -> PairState {
    PairState().after(.startScan, .connectRequested(sts: true), .linkReady)
}

/// 재연결 경로 — **PIN이 한 번도 안 나온다.**
func testStsReconnectHasNoPin() {
    let hello = stsLinked()
    expect(hello.phase, .offering, "HELLO를 보내고 STS1을 기다린다")
    check(PairState().after(.startScan, .connectRequested(sts: true))
            .fx(.linkReady).contains(.sendHello(sts: true)),
          "링크가 서면 힌트를 실은 HELLO를 보낸다")

    let responded = hello.step(.stsHelloReceived)
    expect(responded.state.phase, .stsConfirming, "STS2를 보내고 상대 서명을 기다린다")
    check(responded.fx.contains(.sendStsResponse), "캡슐화 결과와 봉인된 서명을 보낸다")

    let confirmed = responded.state.step(.stsConfirmed)
    expect(confirmed.state.phase, .awaitingCandidates, "인증이 끝나면 곧장 후보 교환")
    check(confirmed.fx.contains(.sendCandidates), "후보를 보낸다")
    // ★ 재연결은 **핀하지 않는다.** 여기 인증서가 오지 않으므로 핀할 것도 없고,
    // 핀이 여기서 나오면 그게 곧 사람 확인 없이 신뢰가 생기는 경로다.
    check(!confirmed.fx.contains(.pinPeer), "재연결은 신뢰를 새로 세우지 않는다")
}

/// ★ **두 경로는 서로를 대신하지 못한다.**
///
/// 우리가 링크 키로 상대를 지목해 불렀는데 OFFER(=PIN으로 하자)가 오면 그건 강등 시도다.
/// 반대로 PIN 라운드에 STS1이 오면 우리가 요청하지 않은 인증을 상대가 밀어 넣는 것이다.
/// 둘 다 그 자리에서 무시돼야 한다.
func testNoCrossPathDowngrade() {
    checks += 1
    if stsLinked().step(.offerReceived).state.phase == .enteringPin {
        failures.append("STS 라운드가 OFFER로 강등돼 PIN 화면이 떴다")
    }
    checks += 1
    if linked().step(.stsHelloReceived).state.phase == .stsConfirming {
        failures.append("PIN 라운드에 STS1이 끼어들어 인증 경로로 새어 나갔다")
    }
    checks += 1
    // 재연결 중에는 사람에게 물을 것이 없다 — 입력칸이 뜨면 안 된다.
    if stsLinked().needsPin || stsLinked().after(.stsHelloReceived).needsPin {
        failures.append("재연결 경로에서 PIN 입력칸이 떴다")
    }
}

/// ★ **기회는 한 번이다.** 이게 4자리 PIN을 실용적으로 만드는 유일한 장치다.
///
/// PAKE라 오프라인 대입은 불가능하지만, 재시도를 허용하면 1/10⁴이 반복 가능해져 금방
/// 뚫린다. 두 번째 입력이 `beginPake`로 이어지는 순간 그 성질이 무너진다.
func testPinIsOneShot() {
    let sent = awaitingPin().step(.pinSubmitted("4821"))

    checks += 1
    if sent.state.step(.pinSubmitted("9999")).fx.contains(.beginPake("9999")) {
        failures.append("PIN을 두 번 시도할 수 있다 — 온라인 추측이 가능해진다")
    }

    // 그리고 PAKE가 실패하면(= PIN 불일치) 라운드가 닫힌다. 재시도는 처음부터다.
    checks += 1
    let wrong = sent.state.step(.failed("PIN이 맞지 않습니다"))
    if case .failed = wrong.state.phase {} else {
        failures.append("PIN이 틀렸는데 라운드가 안 닫혔다")
    }
    check(wrong.fx.contains(.disconnect), "링크를 끊는다 — 다음 라운드는 새 PIN이다")
}

/// ★ 핀이 나오는 경로는 **하나뿐**이다 — PAKE를 통과하고 상대 신원을 받은 뒤.
///
/// 다른 경로가 생기면 그게 곧 "PIN 없이 신원이 고정되는" 구멍이다. v1에서 정확히 그
/// 종류의 구멍(한쪽 확인만으로 핀)이 실제 공격이 됐다.
func testPinPeerOnlyAfterPake() {
    let everyEvent: [PairEv] = [
        .startScan, .stopScan, .connectRequested(sts: false), .connectRequested(sts: true),
        .linkReady, .linkLost, .stsHelloReceived, .stsConfirmed,
        .offerReceived, .pinSubmitted("4821"), .pakeAccepted, .peerIdentity,
        .peerAborted("x"), .remoteCandidates, .deadlineElapsed, .failed("x"),
    ]
    // `identifying` 이전의 어떤 단계에서도 어떤 사건으로도 핀이 나오면 안 된다.
    let before: [PairState] = [
        PairState(), linked(), awaitingPin(),
        awaitingPin().after(.pinSubmitted("4821")),
    ]
    for s in before {
        for ev in everyEvent {
            checks += 1
            if reduce(s, ev).fx.contains(.pinPeer) {
                failures.append("PAKE 전 단계(\(s.phase))가 \(ev)로 신원을 핀했다")
            }
        }
    }

    // 그리고 `identifying`에서도 상대 신원이 실제로 와야만 핀한다.
    let identifying = awaitingPin().after(.pinSubmitted("4821"), .pakeAccepted)
    for ev in everyEvent where ev != .peerIdentity {
        checks += 1
        if reduce(identifying, ev).fx.contains(.pinPeer) {
            failures.append("신원 교환 단계가 \(ev)로 핀했다")
        }
    }
}

/// 모든 대기 단계에 마감이 있다 — v1의 무한 대기가 여기서 걸린다.
func testEveryPairingWaitHasDeadline() {
    let waits: [PairPhase] = [
        .linking, .offering, .enteringPin, .proving, .identifying,
        .awaitingCandidates, .punching,
    ]
    for p in waits {
        checks += 1
        if p.deadline == nil { failures.append("마감 없는 대기 단계: \(p)") }
    }
    // 그리고 단계를 옮길 때마다 마감이 **자동으로** 다시 걸려야 한다.
    checks += 1
    let step = linked().step(.offerReceived)
    let armed = step.fx.contains { if case .armDeadline = $0 { return true }; return false }
    if !armed { failures.append("단계 전이에 마감 무장이 없다") }
}

/// 사람이 PIN을 옮겨 적는 중에 재협상 요청이 화면을 가로채면 안 된다.
func testPairingNotHijackedMidPin() {
    checks += 1
    let step = awaitingPin().step(.connectRequested(sts: false))
    if step.state.phase != .enteringPin {
        failures.append("PIN 입력 중에 새 연결 요청이 화면을 날렸다")
    }
}

/// 단계 밖 프레임은 조용히 무시된다 — 순서를 건너뛰게 만드는 메시지가 없어야 한다.
func testOutOfOrderFramesIgnored() {
    // OFFER 없이 PAKE 결과가 왔다 / 신원이 먼저 왔다 — 어느 쪽도 전진시키면 안 된다.
    for (from, ev) in [(linked(), PairEv.pakeAccepted),
                       (linked(), PairEv.peerIdentity),
                       (awaitingPin(), PairEv.pakeAccepted),
                       (awaitingPin(), PairEv.peerIdentity)] {
        checks += 1
        if reduce(from, ev).state.phase != from.phase {
            failures.append("단계 밖 \(ev)가 \(from.phase)를 전진시켰다")
        }
    }
}

func runPairingTests() {
    testPairingHappyPath()
    testStsReconnectHasNoPin()
    testNoCrossPathDowngrade()
    testPinIsOneShot()
    testPinPeerOnlyAfterPake()
    testEveryPairingWaitHasDeadline()
    testPairingNotHijackedMidPin()
    testOutOfOrderFramesIgnored()
}
