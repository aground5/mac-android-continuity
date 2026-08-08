//
//  DeviceMachine.swift
//  Peri Display
//
//  기기 하나의 **연결 수명주기** 상태 기계 — 순수 함수. CoreGraphics·ScreenCaptureKit·AppKit에
//  의존하지 않으므로 `swiftc` 한 줄로 모델 검사를 돌릴 수 있다(MachineTests/ 참조).
//
//  왜 기기가 최상위인가
//  ────────────────────
//  예전에는 생명주기가 셋이었고 서로 어긋났다:
//
//    · `SessionManager.sessions` 배열   (UI 목록)
//    · `Router` 맵                      (clientID → 세션)
//    · `DisplayManager.entries`         (fingerprint → 디스플레이 + holder + unheldSince)
//
//  셋이 각자 "언제 생기고 언제 사라지는가"를 들고 있어서, 셋의 의견이 갈리는 순간이 곧 버그였다
//  (재개할 때마다 쌓이던 '핸드셰이크 대기' 유령 행이 그 셋 중 하나만 갱신한 결과였다).
//
//  합칠 대상은 **세션이 아니라 기기**다. 세션↔기기는 어느 순간에는 1:1이지만 시간축으로는
//  아니다 — 기기는 세션보다 오래 산다(무손실 재개 실패 시 콜드 재수립, LAN↔AOA 전환).
//
//  이 기계가 **모르는** 것
//  ──────────────────────
//  앱을 모른다. 확장도 공유도 파일 전송도, 심지어 "몇 개가 돌고 있는가"조차 모른다.
//  이 파일이 답하는 질문은 하나다: **이 기기와 지금 말할 수 있는가.**
//
//      DeviceMachine   Sess × Channel        ← 여기
//       │
//       └─ AppHost ─── 앱마다 독립된 기계
//            ├─ ExtendMachine   Pipe × Display × spec
//            ├─ ShareMachine    Consent × Recv
//            └─ FilesMachine    (아직 없음)
//
//  앱이 늘어도 이 파일은 **한 줄도 안 바뀐다**(DESIGN.md 원칙 2). 예전엔 여기 `screen`
//  축이 있어서 앱을 하나 넣을 때마다 상태·사건·부작용·리듀서를 전부 뜯어야 했다.
//  앱을 켜고 끄는 일은 `AppHost`가 하고, 그 앱이 무엇을 하는지는 그 앱의 기계가 안다.
//

import Foundation

/// 기기 신원(인증서 지문). 디스플레이·배치·serial이 전부 이 값에 매인다.
typealias Fingerprint = String

/// 전송 연결 식별자 — `LanServer.ClientID`(UUID)에 전송 종류를 얹은 것.
struct ConnID: Hashable, Sendable, CustomStringConvertible {
    let raw: UUID
    let via: Via
    var description: String { "\(via == .aoa ? "aoa" : "lan")#\(raw.uuidString.prefix(8))" }
}

enum Via: Sendable { case lan, aoa }

/// 암호 세션 — 전송이 죽어도 살아남는다(무손실 재개의 근거).
enum Sess: Hashable, Sendable {
    case none
    /// ClientHello를 받았고 신원 검증(ClientAuth)을 기다린다.
    case establishing
    case live(peer: String)
}

/// 제어 채널 — 지금 어느 연결로 말하고 있는가.
enum Channel: Hashable, Sendable {
    case none
    case live(ConnID)
}

struct DeviceState: Hashable, Sendable {
    var sess: Sess = .none
    var channel: Channel = .none
    /// 정리가 시작됐다. 되돌릴 수 없고, 새 연결도 받지 않는다.
    var retiring = false

    /// 말할 수 있는 상태인가 — 세션이 서고 채널이 살아 있다.
    /// **앱이 살 수 있는 유일한 조건**이고, 그래서 이 값이 곧 `AppHost`로 내려간다.
    var linked: Bool {
        if case .live = sess, case .live = channel { return true }
        return false
    }

    /// UI 한 줄 요약 — **계산값**이다. 예전엔 `statusMessage` 문자열을 여기저기서 직접
    /// 대입해서, 실제 상태와 문구가 따로 노는 일이 흔했다.
    ///
    /// 무슨 앱이 도는지는 **여기 없다.** 그건 앱들이 아는 것이고, UI가 합쳐 보여준다.
    var summary: String {
        if retiring { return "정리 중…" }
        switch (sess, channel) {
        case (.none, .none): return "대기 중"
        case (_, .none): return "경로 재탐색 중…"
        case (.none, .live), (.establishing, _): return "핸드셰이크 중…"
        case (.live(let peer), .live): return "연결됨 · \(peer)"
        }
    }
}

/// 기기에 일어난 **사실**.
enum DeviceEv: Equatable, Sendable {
    /// 인증을 마친 연결이 이 기기에 합류했다(지문이 확정된 순간).
    case connAdopted(ConnID)
    /// RESUME 검증 통과 — 세션은 그대로 두고 제어 채널만 교체한다.
    case pathSwapped(ConnID)
    /// 신원 검증까지 끝나 세션이 섰다.
    case sessionLive(peer: String)
    /// 그 연결이 죽었다. 지금 쓰는 경로가 아니면 무시된다(교체된 옛 연결의 늦은 종료).
    case connLost(ConnID)
    /// Ping이 끊겼다 — 우리가 먼저 연결을 정리한다.
    case livenessLost
    /// 클라이언트가 Bye를 보냈다 = **확정 이탈**. 유예를 건너뛴다.
    case bye(reason: String)
    /// 사용자가 이 기기의 페어링을 해제했다 = **우리 쪽에서의 확정 이탈**.
    ///
    /// `bye`와 방향만 다르고 결과는 같다(즉시 정리, 유예 없음). 별도 사건으로 둔 이유는
    /// 상대에게 **먼저 알려야** 하기 때문이다 — 그냥 소켓을 닫으면 Android는 그걸 경로
    /// 상실로 보고 60초 동안 재협상을 시도한다. `Bye(AUTH_REJECTED)`를 보내야 다시
    /// 붙지 않는다. 그 통지가 [DeviceFx.rejectAndClose]다.
    case trustRevoked
    /// 경로가 없는 채로 유예가 지났다 = 기기가 떠난 것으로 확정.
    case graceExpired
    /// 전송은 붙었는데 세션이 안 선다 — ClientAuth가 안 온다.
    case handshakeTimeout
}

/// 기계가 지시하는 부작용. 실행은 `SessionManager`가 한다.
enum DeviceFx: Equatable, Sendable {
    /// 앱들에게 "이제 말할 수 있다"고 알린다. 각 앱이 그걸로 뭘 할지는 **앱이 정한다** —
    /// 확장은 파이프라인을 다시 세우고, 공유는 동의를 다시 청한다.
    case appsLinked
    /// 앱들에게 "말할 채널이 사라졌다"고 알린다. 무엇을 지키고 무엇을 버릴지도 앱이 정한다.
    case appsUnlinked
    /// 기기가 떠났다 — 앱을 전부 내리고 등록부에서 지운다.
    case retireDevice(reason: String)

    /// 경로가 없다 — 이 시간 안에 돌아오지 않으면 떠난 것으로 본다.
    case armGrace(seconds: Double)
    case cancelGrace
    case startLiveness
    case stopLiveness

    /// 세션이 서기를 기다리는 동안의 마감.
    case armHandshakeWatchdog(seconds: Double)
    case cancelHandshakeWatchdog
    case closeConn(ConnID)
    /// 상대에게 "너는 더 이상 신뢰되지 않는다"를 알리고 닫는다(Bye(AUTH_REJECTED) → close).
    /// 그냥 닫기(`closeConn`)와 달라야 하는 이유는 [DeviceEv.trustRevoked] 주석 참조.
    case rejectAndClose(ConnID, reason: String)
    case log(String)
}

/// 경로가 사라진 뒤 "기기가 떠났다"고 확정하기까지의 유예.
///
/// 왜 이렇게 넉넉한가: 끊김은 정상 동작 중에도 늘 일어난다 — AOA 워치독 8s, USB↔Wi-Fi 전환
/// (Android 쪽 20s 쿨다운), 재협상 30s. 이 모든 정당한 공백보다 확실히 길어야 전환 도중에
/// 디스플레이가 사라지지 않는다. 늦게 지우는 비용은 유령 디스플레이가 잠깐 남는 것뿐이고,
/// 일찍 지우는 비용은 창 이주 + SCK 재열거다. 사용자가 끊으면 Bye가 와서 유예를 건너뛴다.
let departureGrace: Double = 60

/// 연결이 붙은 뒤 세션이 서기까지의 상한.
///
/// 이게 없으면 ClientHello만 보내고 ClientAuth를 안 보내는 연결이 기기 항목과 TCP를 영원히
/// 붙잡는다 — 생존 감지는 세션이 서야 시작하고(`startLiveness`), 유예 시계는 경로가 살아 있어
/// 안 돌기 때문에 **아무 타이머도 없다.** Android 쪽에서 똑같은 구멍을 먼저 찾았고, 여기도
/// 같은 이유로 뚫려 있었다(`LanServer`에는 유휴 타임아웃이 없다).
let handshakeDeadline: Double = 10

struct DeviceStep: Equatable {
    var state: DeviceState
    var fx: [DeviceFx] = []
}

/// 전이 함수. **순수**하며 모든 (상태, 사건) 쌍에 대해 정의된다.
///
/// 불변식 하나: `retiring`이 참이면 어떤 사건도 기기를 되살리지 못한다. 은퇴는 자원 해제를
/// 동반하는 비가역 동작이라, 그 도중에 도착한 늦은 사건이 절반만 살아난 기기를 만들면 안 된다.
/// 같은 기기가 다시 오면 **새 기기 상태**로 시작한다.
func reduce(_ s: DeviceState, _ ev: DeviceEv) -> DeviceStep {
    if s.retiring {
        // 정리 중에 뒤늦게 붙은 연결은 그냥 닫는다 — 되살리면 정리 중인 자원을 물게 된다.
        if case .connAdopted(let c) = ev {
            return DeviceStep(state: s, fx: [.closeConn(c), .log("정리 중인 기기에 도착한 연결 — 폐기")])
        }
        return DeviceStep(state: s)
    }

    switch ev {

    case .connAdopted(let conn):
        var n = s
        let wasLinked = s.linked
        n.channel = .live(conn)
        // 세션이 이미 살아 있으면 이건 재개가 아니라 **새 연결의 핸드셰이크**다
        // (콜드 재연결). 옛 세션은 이 시점에 의미가 없으므로 새로 세운다.
        n.sess = .establishing
        var fx: [DeviceFx] = []
        // 세션이 갈렸다 = 상대 쪽 앱도 그 연결과 함께 죽었다. 우리 앱들에게 알린다.
        if wasLinked { fx.append(.appsUnlinked) }
        fx += [
            .cancelGrace,
            .armHandshakeWatchdog(seconds: handshakeDeadline),
            .log("연결 합류 \(conn)"),
        ]
        return DeviceStep(state: n, fx: fx)

    case .pathSwapped(let conn):
        // ★ 무손실 재개 — 세션도 앱도 **하나도** 건드리지 않는다.
        // 이게 성립하는 이유는 세션이 연결보다 오래 살도록 설계됐기 때문이다.
        guard case .live = s.sess else {
            // 재개할 세션이 없는데 교체 성공이 왔다 = 늦은 보고. 그 연결은 쓸 수 없다.
            return DeviceStep(state: s, fx: [.closeConn(conn), .log("세션 없이 도착한 RESUME — 폐기")])
        }
        var n = s
        n.channel = .live(conn)
        return DeviceStep(state: n, fx: [
            .cancelGrace, .startLiveness, .log("★ 제어 채널 교체 → \(conn)"),
        ])

    case .sessionLive(let peer):
        // 경로가 이미 죽었으면 늦게 도착한 인증이다 — 받아들이면 실을 채널이 없는 세션이 된다.
        guard case .live = s.channel else {
            return DeviceStep(state: s, fx: [.log("경로가 죽은 뒤 도착한 세션 확립 — 폐기")])
        }
        let wasLinked = s.linked
        var n = s
        n.sess = .live(peer: peer)
        // ★ **여기서 앱을 켜지 않는다.** 세션이 섰다는 건 말할 수 있게 됐다는 뜻이지
        // 무엇을 할지가 정해졌다는 뜻이 아니다. 앱은 사용자가 아이콘을 눌러야 뜬다.
        // (마지막에 켜져 있던 앱을 다시 띄우는 건 `AppHost`가 기억해 뒀다 하는 일이다.)
        var fx: [DeviceFx] = [.cancelHandshakeWatchdog, .startLiveness]
        // 이미 붙어 있었으면(USB 신뢰 승인 뒤의 재개 등) 또 알리지 않는다 — 앱 쪽에서
        // 중복 `linked`는 무시되지만, 짝이 안 맞는 알림을 내보내는 것 자체가 사고의 씨앗이다.
        if !wasLinked { fx.append(.appsLinked) }
        return DeviceStep(state: n, fx: fx)

    case .connLost(let conn):
        // 지금 쓰는 경로가 아니면 교체된 옛 연결의 늦은 종료다 — 무시한다.
        // (Android `LanClient.generation`과 같은 역할. 이게 없으면 재개 직후 옛 소켓의 종료가
        //  방금 세운 새 채널을 날린다.)
        guard case .live(let cur) = s.channel, cur == conn else {
            return DeviceStep(state: s, fx: [.log("교체된 옛 연결의 종료 무시 \(conn)")])
        }
        let wasLinked = s.linked
        var n = s
        n.channel = .none
        // ★ **선** 세션만 살아남는다. 확립된 세션은 연결보다 오래 살도록 설계됐지만(그게 무손실
        // 재개의 근거다), 핸드셰이크 **중이던** 세션은 그 연결 위에 쌓이던 것이라 같이 죽는다.
        // 남겨 두면 뒤늦게 도착한 인증이 실을 채널도 없이 세션을 세우려 든다.
        if case .establishing = n.sess { n.sess = .none }
        var fx: [DeviceFx] = [.stopLiveness, .cancelHandshakeWatchdog]
        if wasLinked { fx.append(.appsUnlinked) }
        fx += [
            .armGrace(seconds: departureGrace),
            .log("경로 상실 — \(Int(departureGrace))s 안에 복귀 없으면 정리"),
        ]
        return DeviceStep(state: n, fx: fx)

    case .livenessLost:
        // 우리가 먼저 끊는다. 뒤이은 `connLost`가 평소 경로를 탄다 — 여기서 상태를 바꾸지 않는
        // 이유는, 끊는 데 실패해도 기계가 현실과 어긋나지 않게 하기 위해서다.
        guard case .live(let conn) = s.channel else { return DeviceStep(state: s) }
        return DeviceStep(state: s, fx: [.closeConn(conn), .log("Ping 유실 → 연결 정리")])

    case .bye(let reason):
        // 확정 이탈 — 유예를 기다릴 이유가 없다.
        var n = s
        n.retiring = true
        var fx: [DeviceFx] = [.stopLiveness, .cancelGrace, .cancelHandshakeWatchdog]
        if case .live(let conn) = s.channel { fx.append(.closeConn(conn)) }
        fx.append(.retireDevice(reason: reason))
        n.sess = .none; n.channel = .none
        return DeviceStep(state: n, fx: fx)

    case .trustRevoked:
        // `bye`와 같은 종착지 — 다만 통지의 방향이 반대라 상대에게 먼저 사유를 보낸다.
        //
        // **신뢰 여부를 상태 축으로 두지 않은 이유가 여기 있다.** 등록부에 들어온 기기는
        // 전부 신뢰된 기기다(신뢰되지 않은 연결은 핸드셰이크에서 거부돼 `adopt`까지 못 온다).
        // 그리고 해제되는 순간 곧바로 정리에 들어가므로 '해제됨'은 **머물 수 있는 상태가
        // 아니다.** 머물 수 없는 상태를 축으로 만들면 도달 가능한 상태 수만 늘고 검사는
        // 아무것도 더 보장하지 못한다 — `retiring`이 이미 그 자리를 지킨다.
        var n = s
        n.retiring = true
        // 통지가 **맨 앞**이다. 뒤이은 `retireDevice`가 세션 객체를 놓아 버리므로, 그 전에
        // Bye를 봉인해 내보내야 한다(봉인에는 세션의 송신 키가 필요하다).
        var fx: [DeviceFx] = []
        if case .live(let conn) = s.channel {
            fx.append(.rejectAndClose(conn, reason: "페어링이 해제되었습니다"))
        }
        fx += [.stopLiveness, .cancelGrace, .cancelHandshakeWatchdog,
               .retireDevice(reason: "페어링 해제")]
        n.sess = .none; n.channel = .none
        return DeviceStep(state: n, fx: fx)

    case .graceExpired:
        // 경로가 그 사이 돌아왔으면 낡은 타이머다.
        guard case .none = s.channel else {
            return DeviceStep(state: s, fx: [.log("유예 만료됐지만 경로가 복귀함 — 무시")])
        }
        var n = s
        n.retiring = true
        n.sess = .none
        return DeviceStep(state: n, fx: [
            .cancelHandshakeWatchdog,
            .retireDevice(reason: "\(Int(departureGrace))s간 복귀 없음"),
        ])

    case .handshakeTimeout:
        // 세션이 안 서는 연결은 버린다. 뒤이은 `connLost`가 평소 경로(유예 → 정리)를 탄다 —
        // 여기서 상태를 직접 바꾸지 않는 이유는 닫기에 실패해도 기계가 현실과 어긋나지 않게 하기 위해서다.
        guard case .establishing = s.sess, case .live(let conn) = s.channel else {
            return DeviceStep(state: s)   // 이미 세션이 섰거나 경로가 죽었다 — 낡은 타이머
        }
        return DeviceStep(state: s, fx: [
            .closeConn(conn),
            .log("세션 확립 없음 (\(Int(handshakeDeadline))s) → 연결 정리"),
        ])
    }
}
