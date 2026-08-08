//
//  ShareTests.swift
//  화면 공유 서비스의 기계 — 동의 · 수신.
//
//  extend와 **대칭이 아니라는 것**이 여기서 검사할 핵심이다. MediaProjection 동의는
//  1회용이고 상대의 포그라운드 서비스에 매여 있어서, 경로가 끊기면 세션이 살아남아도
//  이 서비스는 못 살아난다. 기계를 갈라 뒀기 때문에 그 차이가 코드로 드러난다.
//

import Foundation

extension ShareState {
    func after(_ evs: ShareEv...) -> ShareState {
        evs.reduce(self) { st, e in reduceShare(st, e).state }
    }
    func step(_ ev: ShareEv) -> ShareStep { reduceShare(self, ev) }
    func fx(_ ev: ShareEv) -> [ShareFx] { reduceShare(self, ev).fx }
}

/// 프레임이 들어오고 있는 상태.
func receivingShare() -> ShareState {
    ShareState().after(.activated, .linked, .consentGranted, .paramsArrived, .frameArrived)
}

// MARK: - 1. 동의 흐름

func testShareAsksOnlyWhenAwakeAndLinked() {
    // 깨우기만 해서는 못 묻는다 — 보낼 채널이 없다.
    let awake = ShareState().step(.activated)
    checks += 1
    if awake.fx.contains(.requestConsent) { failures.append("경로도 없는데 동의를 청했다") }

    let ready = awake.state.step(.linked)
    check(ready.fx.contains(.requestConsent), "깨어 있고 경로가 있으면 청한다")
    check(ready.fx.contains(.armConsentDeadline(seconds: consentDeadline)),
          "★ 마감을 건다 — 상대가 대화상자를 못 띄우면 영원히 기다리게 된다")
    expect(ready.state.consent, .asked, "응답 대기")
}

func testShareAskOrderIndependent() {
    // linked 먼저, activated 나중이어도 같아야 한다.
    let other = ShareState().after(.linked)
    let ready = other.step(.activated)
    check(ready.fx.contains(.requestConsent), "순서가 반대여도 청한다")
}

func testConsentGrantedStartsReceiver() {
    let asked = ShareState().after(.activated, .linked)
    let ok = asked.step(.consentGranted)
    expect(ok.state.consent, .granted, "동의됨")
    expect(ok.state.recv, .starting, "상대 인코더 기동 대기")
    check(ok.fx.contains(.cancelConsentDeadline), "마감 해제")
    check(ok.fx.contains(.startReceiver), "수신기 준비")
}

func testConsentDeniedReportsFailure() {
    let asked = ShareState().after(.activated, .linked)
    let no = asked.step(.consentDenied("사용자가 거부함"))
    expect(no.state.consent, .none, "동의 없음")
    check(no.fx.contains(.reportFailed("사용자가 거부함")),
          "★ 기기 기계에 올린다 — 화면이 꺼져야 사용자가 왜 안 되는지 안다")
}

func testConsentTimeout() {
    let asked = ShareState().after(.activated, .linked)
    let late = asked.step(.consentTimeout)
    expect(late.state.consent, .none, "마감 → 포기")
    check(late.fx.contains(where: { if case .reportFailed = $0 { return true }; return false }),
          "실패로 보고한다")

    // 낡은 타이머는 무시한다.
    let granted = asked.after(.consentGranted)
    checks += 1
    if granted.step(.consentTimeout).state.consent != .granted {
        failures.append("동의된 뒤 도착한 낡은 마감이 동의를 지웠다")
    }
}

// MARK: - 2. 수신

func testFrameNeedsParams() {
    // 제원 없이 온 프레임은 풀 수 없다 — 코덱도 크기도 모른다.
    let granted = ShareState().after(.activated, .linked, .consentGranted)
    let early = granted.step(.frameArrived)
    expect(early.state.recv, .starting, "★ 제원 없이는 수신으로 올라가지 않는다")

    let withParams = granted.after(.paramsArrived).step(.frameArrived)
    expect(withParams.state.recv, .up, "제원이 있으면 수신 시작")
    check(withParams.state.isReceiving, "수신 중")
}

func testEarlyFrameRecoversWhenParamsArriveLate() {
    // 프레임이 제원보다 **먼저** 닿는 건 흔한 일이다: 제원은 신뢰 채널(TCP)로, 프레임은
    // 손실 허용 채널(UDP)로 오고 둘 사이엔 순서 보장이 없다.
    //
    // 기계가 그 프레임을 버리는 건 옳다(코덱도 크기도 모른다). 하지만 **그 뒤에 제원이
    // 오면 반드시 올라갈 수 있어야 한다** — 이 성질이 곧 구동부에 대한 계약이다:
    // "도착했다"를 첫 프레임에 한 번만 알리고 잠그면 안 된다.
    //
    // 실기기에서 정확히 그렇게 검은 화면이 났다. 인코더는 잘 돌고 프레임도 갔는데 Mac은
    // `.starting`에 머물렀다 — 기계는 처음부터 옳았고 구동부가 기회를 소진했다.
    let granted = ShareState().after(.activated, .linked, .consentGranted)
    let dropped = granted.step(.frameArrived)
    expect(dropped.state.recv, .starting, "제원 전 프레임은 버린다")

    let recovered = dropped.state.after(.paramsArrived).step(.frameArrived)
    expect(recovered.state.recv, .up, "★ 제원이 늦게 와도 다음 프레임에서 올라간다")
    check(recovered.state.isReceiving, "복구 후 수신 중")
}

func testParamsWithoutConsentIgnored() {
    let bare = ShareState().after(.activated, .linked)
    check(!bare.step(.paramsArrived).state.hasParams,
          "동의 전에 온 제원은 받지 않는다")
}

func testRemoteStoppedReportsFailure() {
    let up = receivingShare()
    let stopped = up.step(.remoteStopped("기기에서 공유를 중지했습니다"))
    expect(stopped.state.recv, .idle, "수신 종료")
    expect(stopped.state.consent, .none, "동의도 무효")
    check(stopped.fx.contains(.stopReceiver), "수신기 정리")
    check(stopped.fx.contains(.reportFailed("기기에서 공유를 중지했습니다")), "사유를 올린다")
}

// MARK: - 3. ★ extend와 갈리는 지점 — 재개 정책

func testUnlinkedInvalidatesConsent() {
    // extend는 경로가 끊겨도 아무것도 안 버린다. 여기서는 **동의가 통째로 무효**다:
    // MediaProjection 토큰은 1회용이고 상대의 포그라운드 서비스도 그 연결과 함께 죽는다.
    let up = receivingShare()
    let lost = up.step(.unlinked)
    expect(lost.state.consent, .none, "★ 동의 무효 — 경로가 돌아와도 다시 물어야 한다")
    expect(lost.state.recv, .idle, "수신 종료")
    check(!lost.state.hasParams, "제원도 버린다")
    check(lost.fx.contains(.stopReceiver), "수신기 정리")

    // 경로가 돌아오면 **다시 청한다** — 자동으로 살아나지 않는다.
    let back = lost.state.step(.linked)
    check(back.fx.contains(.requestConsent), "복귀 시 재요청")
    expect(back.state.consent, .asked, "다시 응답 대기")
}

func testDeactivateTellsRemoteToStop() {
    // 우리가 껐으면 상대의 인코더·포그라운드 서비스도 멈춰야 한다. 안 알리면 상대는
    // 아무도 안 보는 화면을 계속 인코딩한다(배터리).
    let up = receivingShare()
    let off = up.step(.deactivated)
    check(off.fx.contains(.tellRemoteStop), "★ 상대에게 멈추라고 알린다")
    check(off.fx.contains(.stopReceiver), "수신기 정리")
    expect(off.state.consent, .none, "동의 무효")
}

// MARK: - 4. 도달성 · 불변식

let allShareEvents: [ShareEv] = [
    .activated, .deactivated, .linked, .unlinked,
    .consentGranted, .consentDenied("x"), .consentTimeout,
    .paramsArrived, .frameArrived, .remoteStopped("x"),
]

func reachableShareStates() -> [ShareState] {
    reachableStates(from: ShareState(), events: allShareEvents) { reduceShare($0, $1).state }
}

func testShareInvariants() {
    let all = reachableShareStates()
    print("공유 기계 도달 상태: \(all.count)개")
    let rules: [(String, (ShareState) -> Bool)] = [
        ("수신 중이면 동의가 나 있다", { $0.recv == .idle || $0.consent == .granted }),
        ("수신 중이면 제원을 안다", { $0.recv != .up || $0.hasParams }),
        ("재워진 서비스는 동의도 수신도 없다", { s in
            s.active || (s.consent == .none && s.recv == .idle && !s.hasParams)
        }),
        ("경로가 없으면 동의도 없다 (토큰이 연결에 매여 있다)", { s in
            s.linked || s.consent == .none
        }),
        ("동의 없이 제원을 들고 있지 않는다", { $0.consent == .granted || !$0.hasParams }),
    ]
    for (name, holds) in rules {
        checks += 1
        let bad = all.filter { !holds($0) }
        if !bad.isEmpty {
            failures.append("위반: \(name)\n" + bad.prefix(4).map {
                "    · active=\($0.active) linked=\($0.linked) consent=\($0.consent) recv=\($0.recv) params=\($0.hasParams)"
            }.joined(separator: "\n"))
        }
    }
}

/// 모든 대기 상태에 마감이 있다. 동의 대기가 유일한 사람-대기 구간이고, 거기가 제일 위험하다.
func testShareLiveness() {
    checks += 1
    let stuck = reachableShareStates().filter { s in
        guard s.active, s.linked else { return false }         // 재워졌거나 경로가 없으면 대기가 아니다
        switch (s.consent, s.recv) {
        case (.asked, _):   return false                       // 동의 마감이 돈다
        case (.none, _):    return false                       // 곧 다시 청한다(askIfPossible)
        case (.granted, .starting): return true                // ← 상대 인코더가 안 오면?
        default:            return false
        }
    }
    // `.granted/.starting`은 상대의 첫 프레임을 기다린다. 여기엔 아직 마감이 없다 —
    // 상대가 동의해 놓고 인코더를 못 세우면 "상대 화면을 기다리는 중…"에서 멈춘다.
    // 서비스 본체가 첫 프레임 마감을 걸어야 한다(B6에서 상대가 실패를 보고하도록 배선).
    if !stuck.isEmpty {
        print("  ⓘ 첫 프레임 대기 상태 \(stuck.count)개 — 상대의 실패 보고(remoteStopped)에 의존")
    }
}

func testShareDeactivatedNeverReceives() {
    checks += 1
    for s in reachableShareStates() where !s.active {
        for ev in allShareEvents where ev != .activated {
            let n = reduceShare(s, ev).state
            if n.recv != .idle || n.consent != .none {
                failures.append("재워진 서비스가 \(ev)로 살아났다")
                return
            }
        }
    }
}

func runShareTests() {
    testShareAsksOnlyWhenAwakeAndLinked()
    testShareAskOrderIndependent()
    testConsentGrantedStartsReceiver()
    testConsentDeniedReportsFailure()
    testConsentTimeout()
    testFrameNeedsParams()
    testEarlyFrameRecoversWhenParamsArriveLate()
    testParamsWithoutConsentIgnored()
    testRemoteStoppedReportsFailure()
    testUnlinkedInvalidatesConsent()
    testDeactivateTellsRemoteToStop()
    testShareInvariants()
    testShareLiveness()
    testShareDeactivatedNeverReceives()
}
