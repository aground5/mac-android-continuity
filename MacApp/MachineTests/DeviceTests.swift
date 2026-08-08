//
//  DeviceTests.swift
//  기기 기계 — **연결 수명주기만**.
//
//  이 기계는 앱을 모른다. 여기서 검사하는 건 딱 둘이다: 연결이 옳게 흐르는가, 그리고
//  앱들에게 "말할 수 있다/없다"를 정확히 알리는가.
//

import Foundation

let lan1 = ConnID(raw: UUID(), via: .lan)
let lan2 = ConnID(raw: UUID(), via: .lan)
let usb1 = ConnID(raw: UUID(), via: .aoa)

extension DeviceState {
    func after(_ evs: DeviceEv...) -> DeviceState {
        evs.reduce(self) { st, e in reduce(st, e).state }
    }
    func step(_ ev: DeviceEv) -> DeviceStep { reduce(self, ev) }
    func fx(_ ev: DeviceEv) -> [DeviceFx] { reduce(self, ev).fx }
}

/// 세션까지 선 기기.
func linkedDevice() -> DeviceState {
    DeviceState().after(.connAdopted(lan1), .sessionLive(peer: "Pixel"))
}

// MARK: - 1. 기본 수명주기

func testDeviceColdPath() {
    var s = DeviceState()
    expect(s.summary, "대기 중", "초기")

    s = s.after(.connAdopted(lan1))
    expect(s.sess, .establishing, "연결 합류 → 핸드셰이크")
    expect(s.summary, "핸드셰이크 중…", "요약")
    check(!s.linked, "아직 말할 수 없다")

    s = s.after(.sessionLive(peer: "Pixel"))
    check(s.linked, "세션·경로가 섰다")
    expect(s.summary, "연결됨 · Pixel", "요약 — 무슨 앱이 도는지는 여기 없다")
}

/// ★ 세션이 섰다고 앱이 켜지지 않는다. 알리기만 한다.
func testSessionLiveOnlyNotifiesApps() {
    let fx = DeviceState().after(.connAdopted(lan1)).fx(.sessionLive(peer: "P"))
    check(fx.contains(.appsLinked), "앱들에게 말할 수 있다고 알린다")
    check(fx.contains(.startLiveness), "생존 감지 시작")
    check(fx.contains(.cancelHandshakeWatchdog), "핸드셰이크 마감 해제")
    // 이 기계에는 앱을 켜는 부작용 자체가 없다 — 표현 불가능하다는 게 요점이다.
    expect(fx.count, 3, "그 밖에 아무것도 하지 않는다")
}

// MARK: - 2. 앱에게 알리는 순간

func testLinkNotificationsArePaired() {
    // 붙었다고 알린 뒤에만 끊겼다고 알린다. 짝이 안 맞으면 앱이 유령 상태로 남는다.
    checks += 1
    for s in reachableDeviceStates() {
        for ev in allDeviceEvents {
            let step = reduce(s, ev)
            if step.fx.contains(.appsLinked) && s.linked {
                failures.append("이미 붙어 있는데 또 붙었다고 알린다: \(ev)")
                return
            }
            if step.fx.contains(.appsUnlinked) && !s.linked {
                failures.append("붙은 적 없는데 끊겼다고 알린다: \(ev) at \(s.sess)/\(s.channel)")
                return
            }
        }
    }
}

func testPathLossUnlinksApps() {
    let live = linkedDevice()
    let lost = live.step(.connLost(lan1))
    check(lost.fx.contains(.appsUnlinked), "경로가 끊기면 앱에게 알린다")
    check(lost.fx.contains(.armGrace(seconds: departureGrace)), "유예 시계")
    checks += 1
    if lost.fx.contains(where: { if case .retireDevice = $0 { return true }; return false }) {
        failures.append("경로 상실만으로 기기를 지웠다")
    }
}

func testColdReconnectUnlinksThenRelinks() {
    // 콜드 재연결은 재개가 아니다 — 상대 쪽 앱도 그 연결과 함께 죽었으므로 한 번 끊어야 한다.
    let live = linkedDevice()
    let re = live.step(.connAdopted(lan2))
    check(re.fx.contains(.appsUnlinked), "★ 새 연결이 붙으면 옛 앱 연결을 먼저 끊는다")
    expect(re.state.sess, .establishing, "핸드셰이크 다시")
    check(!re.state.linked, "아직 못 말한다")

    let back = re.state.step(.sessionLive(peer: "P"))
    check(back.fx.contains(.appsLinked), "세션이 서면 다시 알린다")
}

func testResumeDoesNotDisturbApps() {
    // ★ 무손실 재개 — 앱에게 아무 말도 하지 않는다. 그게 무손실의 요점이다.
    let live = linkedDevice()
    let swapped = live.step(.pathSwapped(lan2))
    check(swapped.state.linked, "여전히 붙어 있다")
    checks += 1
    if swapped.fx.contains(.appsUnlinked) || swapped.fx.contains(.appsLinked) {
        failures.append("재개가 앱 연결을 흔들었다")
    }
    check(swapped.fx.contains(.startLiveness), "생존 감지 재개")
}

func testStaleConnLossIgnored() {
    let live = linkedDevice().after(.pathSwapped(lan2))
    let stale = live.step(.connLost(lan1))   // 교체된 옛 연결
    check(stale.state.linked, "낡은 종료는 무시된다")
    checks += 1
    if stale.fx.contains(.appsUnlinked) { failures.append("낡은 종료가 앱을 끊었다") }
}

func testLateSessionLiveDiscarded() {
    let dead = DeviceState().after(.connAdopted(lan1), .connLost(lan1))
    let late = dead.step(.sessionLive(peer: "P"))
    expect(late.state.sess, .none, "경로가 죽은 뒤 도착한 인증은 폐기")
    checks += 1
    if late.fx.contains(.appsLinked) { failures.append("채널도 없는데 앱에게 붙었다고 알렸다") }
}

// MARK: - 3. 이탈

func testByeRetiresImmediately() {
    let live = linkedDevice()
    let bye = live.step(.bye(reason: "사용자 끊기"))
    check(bye.state.retiring, "정리 시작")
    check(bye.fx.contains(.retireDevice(reason: "사용자 끊기")), "등록부에서 지운다")
    check(bye.fx.contains(.closeConn(lan1)), "연결도 닫는다")
}

func testTrustRevokedNotifiesBeforeRetire() {
    let live = linkedDevice()
    let rev = live.step(.trustRevoked)
    guard let iReject = rev.fx.firstIndex(where: { if case .rejectAndClose = $0 { return true }; return false }),
          let iRetire = rev.fx.firstIndex(where: { if case .retireDevice = $0 { return true }; return false })
    else { fail("trustRevoked에 통지/은퇴가 없다"); return }
    check(iReject < iRetire, "★ 통지가 먼저 — 은퇴가 세션을 놓으면 봉인할 키가 없다")
}

func testGraceExpiryRetires() {
    let lost = linkedDevice().after(.connLost(lan1))
    let gone = lost.step(.graceExpired)
    check(gone.state.retiring, "유예 만료 → 정리")

    // 경로가 돌아왔으면 낡은 타이머다.
    let back = lost.after(.connAdopted(lan2))
    check(!back.step(.graceExpired).state.retiring, "복귀 후의 만료는 무시")
}

func testRetiringIsIrreversible() {
    let gone = linkedDevice().after(.bye(reason: "x"))
    for ev: DeviceEv in [.connAdopted(lan2), .sessionLive(peer: "P"), .pathSwapped(lan2)] {
        checks += 1
        if !reduce(gone, ev).state.retiring {
            failures.append("정리 중인 기기가 \(ev)로 되살아났다")
        }
    }
}

func testHandshakeDeadline() {
    let s = DeviceState().after(.connAdopted(lan1))
    check(DeviceState().fx(.connAdopted(lan1)).contains(.armHandshakeWatchdog(seconds: handshakeDeadline)),
          "연결이 붙으면 핸드셰이크 마감을 건다")
    check(s.step(.handshakeTimeout).fx.contains(.closeConn(lan1)), "마감이 지나면 그 연결을 닫는다")
    let live = s.step(.sessionLive(peer: "Pixel"))
    check(live.fx.contains(.cancelHandshakeWatchdog), "세션이 서면 마감 해제")
    check(!live.state.step(.handshakeTimeout).fx.contains(.closeConn(lan1)),
          "이미 세션이 섰으면 낡은 마감은 무시")
}

// MARK: - 4. 도달성 · 불변식 · 활성

let allDeviceEvents: [DeviceEv] = [
    .connAdopted(lan1), .connAdopted(lan2), .pathSwapped(lan2),
    .sessionLive(peer: "P"), .connLost(lan1), .connLost(lan2),
    .livenessLost, .bye(reason: "b"), .graceExpired, .handshakeTimeout, .trustRevoked,
]

func reachableDeviceStates() -> [DeviceState] {
    reachableStates(from: DeviceState(), events: allDeviceEvents) { reduce($0, $1).state }
}

func testDeviceInvariants() {
    let all = reachableDeviceStates()
    print("기기 기계 도달 상태: \(all.count)개")
    let rules: [(String, (DeviceState) -> Bool)] = [
        ("정리 중인 기기는 세션도 경로도 없다", { s in
            guard s.retiring else { return true }
            if case .none = s.sess, case .none = s.channel { return true }
            return false
        }),
        ("정리 중인 기기는 말할 수 없다", { !$0.retiring || !$0.linked }),
    ]
    for (name, holds) in rules {
        checks += 1
        let bad = all.filter { !holds($0) }
        if !bad.isEmpty {
            failures.append("위반: \(name)\n" + bad.prefix(4).map {
                "    · sess=\($0.sess) channel=\($0.channel) retiring=\($0.retiring)"
            }.joined(separator: "\n"))
        }
    }
}

func testDeviceLiveness() {
    let all = reachableDeviceStates()
    func timers(_ s: DeviceState) -> Bool {
        if case .none = s.channel, !s.retiring { return true }            // 유예 → graceExpired
        if case .live = s.channel, case .live = s.sess { return true }    // 생존감지 → livenessLost
        if case .establishing = s.sess { return true }                    // 핸드셰이크 마감
        return false
    }
    func mayRest(_ s: DeviceState) -> Bool {
        if s.retiring { return true }                                     // 곧 사라진다(비가역)
        if case .live = s.channel, case .none = s.sess { return true }    // 익명 연결 — 전송 마감이 진다
        return false
    }
    checks += 1
    let stuck = all.filter { !mayRest($0) && !timers($0) }
    if !stuck.isEmpty {
        failures.append("마감 없이 외부 사건만 기다리는 기기 상태 \(stuck.count)개")
    }
}

func testEveryStateAcceptsBye() {
    checks += 1
    let bad = reachableDeviceStates().filter { s in
        !s.retiring && !reduce(s, .bye(reason: "x")).state.retiring
    }
    if !bad.isEmpty { failures.append("Bye로 정리되지 않는 상태 \(bad.count)개") }
}

/// ★ 전역 불변식: 세션이 살아 있는 동안 기기를 은퇴시킬 수 있는 건 **확정 이탈**뿐이다.
///
/// 확정 이탈 = 누군가가 "끝"이라고 말한 것: Android의 `bye`, 유예 만료(`graceExpired`),
/// 사용자의 페어링 해제(`trustRevoked`). 전송 사고(경로 상실·핸드셰이크 실패·Ping 유실)는
/// 여기 못 든다 — 은퇴는 가상 디스플레이 파괴를 부르고, 그러면 창이 이주하고 SCK 재열거가
/// 돌아 **붙어 있는 다른 기기까지** 1초씩 멈춘다.
///
/// 새 이탈 사건을 추가하면 이 목록에 등록해야 한다. 등록을 잊으면 검사가 잡는다.
func testDeviceNeverRetiresOnAccident() {
    checks += 1
    for s in reachableDeviceStates() {
        guard case .live = s.sess else { continue }
        for ev in allDeviceEvents {
            guard reduce(s, ev).fx.contains(where: {
                if case .retireDevice = $0 { return true }; return false
            }) else { continue }
            switch ev {
            case .bye, .graceExpired, .trustRevoked: continue
            default:
                failures.append("세션이 살아 있는데 기기 은퇴: \(ev) at sess=\(s.sess) channel=\(s.channel)")
                return
            }
        }
    }
}

func runDeviceTests() {
    testDeviceColdPath()
    testSessionLiveOnlyNotifiesApps()
    testLinkNotificationsArePaired()
    testPathLossUnlinksApps()
    testColdReconnectUnlinksThenRelinks()
    testResumeDoesNotDisturbApps()
    testStaleConnLossIgnored()
    testLateSessionLiveDiscarded()
    testByeRetiresImmediately()
    testTrustRevokedNotifiesBeforeRetire()
    testGraceExpiryRetires()
    testRetiringIsIrreversible()
    testHandshakeDeadline()
    testDeviceInvariants()
    testDeviceLiveness()
    testEveryStateAcceptsBye()
    testDeviceNeverRetiresOnAccident()
}
