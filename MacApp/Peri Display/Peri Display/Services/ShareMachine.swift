//
//  ShareMachine.swift
//  Peri Display
//
//  **서비스 #2(화면 공유, Android → Mac)의 상태 기계** — 순수 함수.
//
//  extend와 대칭이 아니다. 여기서는 우리가 **받는 쪽**이고, 시작 버튼은 Mac에 있지만
//  실제로 화면을 내줄지는 상대가 정한다:
//
//    Mac이 요청 → Android가 사람에게 묻는다(MediaProjection 동의) → 인코더 기동 → 프레임
//
//  ⚠ **동의는 세션보다 수명이 짧다.** Android 14+는 `mediaProjection` 타입 포그라운드
//  서비스가 선행돼야 하고 동의 토큰이 1회용이다. 무손실 재개로 **세션은 살아남아도 이
//  서비스는 못 살아난다** — 재동의가 필요하다. 그래서 `unlinked`가 extend에서는 아무것도
//  안 하지만 여기서는 동의를 무효로 만든다. 서비스마다 재개 정책이 다르다는 걸 보여주는
//  실제 사례이고, 기계를 갈라 뒀기 때문에 그 차이가 코드로 드러난다.
//
//  두 축
//  ────
//    consent — 상대가 화면을 내주기로 했는가 (사람의 결정)
//    recv    — 프레임이 실제로 들어오고 있는가 (기계의 결정)
//
//  둘을 하나로 합치고 싶어지지만 그러면 "동의는 났는데 인코더가 죽은" 상태를 표현할 수 없다.
//  그 구분이 사용자에게 보여줄 문구를 가른다("동의를 기다리는 중" vs "신호가 끊겼다").
//

import Foundation

/// 상대의 화면 공유 동의 상태. **사람이 움직이는 축**이라 시간이 오래 걸릴 수 있다.
enum Consent: Hashable, Sendable {
    case none
    /// 요청을 보냈고 사람의 응답을 기다린다. 여기에는 마감이 필요하다 — 상대가 대화상자를
    /// 무시하면 우리는 영원히 기다리게 된다.
    case asked
    case granted
}

/// 수신 파이프라인.
enum Recv: Hashable, Sendable {
    case idle
    /// 동의는 났고 첫 프레임을 기다린다(상대 인코더 기동 중).
    case starting
    case up
}

/// 동의를 기다리는 상한. 사람이 대화상자를 눌러야 하므로 넉넉하되, 무한은 아니다 —
/// 앱이 백그라운드라 대화상자를 아예 못 띄우는 경우가 실제로 있고, 그때 이 마감이
/// "요청했는데 아무 일도 안 일어남"을 사용자에게 보이는 실패로 바꾼다.
let consentDeadline: Double = 45

struct ShareState: Hashable, Sendable {
    /// 기기 기계가 이 서비스를 깨웠는가.
    var active = false
    /// 말할 채널이 있는가.
    var linked = false
    var consent: Consent = .none
    var recv: Recv = .idle
    /// 상대가 보고한 영상 제원 — 디코더를 세우는 데 필요하다. 없으면 첫 프레임을 못 푼다.
    var hasParams = false

    var isReceiving: Bool { active && linked && recv == .up }

    /// UI 보조 문구.
    var detail: String? {
        guard active else { return nil }
        if !linked { return "경로를 기다리는 중…" }
        switch (consent, recv) {
        case (.none, _):        return "요청을 준비하는 중…"
        case (.asked, _):       return "기기에서 화면 공유를 허용해 주세요"
        case (.granted, .idle), (.granted, .starting): return "상대 화면을 기다리는 중…"
        case (.granted, .up):   return nil
        }
    }
}

enum ShareEv: Equatable, Sendable {
    case activated
    case deactivated
    case linked
    case unlinked
    /// 상대가 동의했다(포그라운드 서비스·MediaProjection 기동 완료).
    case consentGranted
    /// 상대가 거절했거나 동의를 받을 수 없었다.
    case consentDenied(String)
    /// 동의 응답이 마감 안에 오지 않았다.
    case consentTimeout
    /// 상대가 영상 제원을 보냈다(크기·코덱).
    case paramsArrived
    /// 첫 프레임(또는 재개 후 첫 프레임)이 들어왔다.
    case frameArrived
    /// 상대가 공유를 멈췄다(사용자 중지, 인코더 오류, 화면 꺼짐).
    case remoteStopped(String)
}

enum ShareFx: Equatable, Sendable {
    /// 상대에게 "화면을 공유해 달라"고 청한다. 사람에게 묻는 건 상대의 몫이다.
    case requestConsent
    /// 동의 응답 마감을 건다 / 푼다.
    case armConsentDeadline(seconds: Double)
    case cancelConsentDeadline
    /// 수신기(디코더·창)를 세운다 / 접는다.
    case startReceiver
    case stopReceiver
    /// 상대에게 공유를 멈추라고 알린다.
    case tellRemoteStop
    /// 이 서비스가 못 하겠다고 기기 기계에 보고한다 → 화면이 꺼진다.
    case reportFailed(String)
    case log(String)
}

struct ShareStep: Equatable {
    var state: ShareState
    var fx: [ShareFx] = []
}

/// 동의를 청할 수 있는 상태면 청한다.
private func askIfPossible(_ s: inout ShareState) -> [ShareFx] {
    guard s.active, s.linked, s.consent == .none else { return [] }
    s.consent = .asked
    return [.requestConsent, .armConsentDeadline(seconds: consentDeadline)]
}

/// 동의·수신을 전부 놓는다. 재개 정책이 extend와 갈리는 지점이라 한 곳에 모은다.
private func dropConsent(_ s: inout ShareState) -> [ShareFx] {
    let hadConsent = s.consent != .none
    s.consent = .none
    s.recv = .idle
    s.hasParams = false
    return hadConsent ? [.cancelConsentDeadline, .stopReceiver] : [.stopReceiver]
}

/// 전이 함수. **순수**하며 모든 (상태, 사건) 쌍에 대해 정의된다.
func reduceShare(_ s: ShareState, _ ev: ShareEv) -> ShareStep {
    switch ev {

    case .activated:
        guard !s.active else { return ShareStep(state: s) }
        var n = s
        n.active = true
        // fx를 **먼저** 받는다 — `ShareStep(state: n, fx: askIfPossible(&n))`로 쓰면
        // `state: n`이 먼저 평가돼 `consent = .asked`가 반환값에서 빠진다.
        let fx = askIfPossible(&n)
        return ShareStep(state: n, fx: fx)

    case .deactivated:
        guard s.active else { return ShareStep(state: s) }
        var n = s
        n.active = false
        var fx: [ShareFx] = [.tellRemoteStop]
        fx += dropConsent(&n)
        return ShareStep(state: n, fx: fx)

    case .linked:
        guard !s.linked else { return ShareStep(state: s) }
        var n = s
        n.linked = true
        let fx = askIfPossible(&n)
        return ShareStep(state: n, fx: fx)

    case .unlinked:
        guard s.linked else { return ShareStep(state: s) }
        var n = s
        n.linked = false
        // ★ extend와 갈리는 지점. 거기서는 경로가 끊겨도 아무것도 안 버리지만, 여기서는
        // **동의가 통째로 무효**다 — MediaProjection 토큰은 1회용이고 상대의 포그라운드
        // 서비스도 그 연결과 함께 죽는다. 경로가 돌아오면 사람에게 다시 물어야 한다.
        let fx = dropConsent(&n)
        return ShareStep(state: n, fx: fx + [.log("경로 상실 — 동의 무효, 복귀 시 재요청")])

    case .consentGranted:
        guard s.consent == .asked else { return ShareStep(state: s, fx: [.log("낡은 동의 응답 — 무시")]) }
        var n = s
        n.consent = .granted
        n.recv = .starting
        return ShareStep(state: n, fx: [.cancelConsentDeadline, .startReceiver])

    case .consentDenied(let why):
        guard s.consent == .asked else { return ShareStep(state: s) }
        var n = s
        let fx = dropConsent(&n)
        return ShareStep(state: n, fx: fx + [.reportFailed(why)])

    case .consentTimeout:
        guard s.consent == .asked else { return ShareStep(state: s) }   // 낡은 타이머
        var n = s
        let fx = dropConsent(&n)
        return ShareStep(state: n, fx: fx + [
            .reportFailed("기기가 응답하지 않습니다 (동의 대기 \(Int(consentDeadline))초)"),
        ])

    case .paramsArrived:
        guard s.consent == .granted else { return ShareStep(state: s) }
        var n = s
        n.hasParams = true
        return ShareStep(state: n)

    case .frameArrived:
        // 제원 없이 온 프레임은 풀 수 없다 — 코덱도 크기도 모른다.
        guard s.consent == .granted, s.hasParams else {
            return ShareStep(state: s, fx: [.log("제원 없이 도착한 프레임 — 폐기")])
        }
        guard s.recv != .up else { return ShareStep(state: s) }
        var n = s
        n.recv = .up
        return ShareStep(state: n)

    case .remoteStopped(let why):
        guard s.consent != .none else { return ShareStep(state: s) }
        var n = s
        let fx = dropConsent(&n)
        return ShareStep(state: n, fx: fx + [.reportFailed(why)])
    }
}
