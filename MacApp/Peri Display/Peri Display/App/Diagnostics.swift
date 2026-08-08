//
//  Diagnostics.swift
//  Peri Display
//
//  진단 화면이 읽는 **읽기 전용 스냅샷** 타입들.
//
//  왜 스냅샷인가: 진단 뷰가 `SessionManager`·`DisplayManager`의 내부를 직접 들여다보게 하면
//  진단을 위해 캡슐화를 뚫는 구멍이 영구히 남는다. 대신 각 소유자가 "지금 이렇다"를 한 번에
//  찍어 건네고, 뷰는 그걸 그리기만 한다.
//
//  두 스냅샷의 성격이 다르다는 점이 중요하다:
//
//   - `DeviceSnapshot`은 **상태 기계에서 나온다.** `DeviceState`는 순수 타입이고, 뷰가 보는
//     값과 기계가 결정에 쓰는 값이 같은 값이다. 어긋날 수가 없다.
//
//   - `HostSnapshot`은 **기계가 없다.** 호스트(이 Mac) 자신의 상태는 아직 SessionManager의
//     불린·옵셔널, DisplayManager의 플래그, AoaLink의 문자열, PairingCoordinator의 phase에
//     흩어져 있다. 여기 한자리에 모으는 이유가 그거다 — 흩어져 있으면 서로 어긋나도 안 보인다.
//     (호스트 상태 기계를 세우게 되면 이 구조체가 그 자리의 밑그림이 된다.)
//

import Foundation
import CoreGraphics

/// 선택된 기기 하나의 상태 — 기계의 상태 + 그 상태를 실현 중인 것들.
struct DeviceSnapshot {
    let fingerprint: Fingerprint
    /// 기계가 들고 있는 상태 그 자체. UI 요약(`summary`)도 여기서 파생된다.
    let state: DeviceState
    /// 이 기기를 지금 실행 중인 세션이 있는가(암호·파이프라인·통계의 소유자).
    let hasSession: Bool
    /// 유예 시계가 돌고 있는가 — 경로가 없는 동안만 참.
    let graceArmed: Bool
    /// 핸드셰이크 마감이 걸려 있는가 — 세션이 서기를 기다리는 동안만 참.
    let handshakeArmed: Bool

    /// 상태와 무장된 시계가 서로 맞는가. 어긋나면 기계나 실행기 중 한쪽이 틀린 것이다.
    ///
    /// 이게 진단에 있는 이유: 오늘 겪은 정지 버그처럼 "기계는 송출 중이라 말하는데 실물은
    /// 죽어 있는" 상황을 사람이 눈으로 잡아내려면 대조할 기준이 화면에 있어야 한다.
    var timerMismatch: String? {
        let pathGone: Bool = { if case .none = state.channel { return true }; return false }()
        if pathGone, !state.retiring, !graceArmed { return "경로가 없는데 유예 시계가 없다" }
        if !pathGone, graceArmed { return "경로가 살아 있는데 유예 시계가 돈다" }
        let waiting: Bool = { if case .establishing = state.sess { return true }; return false }()
        if waiting, !handshakeArmed { return "핸드셰이크 대기인데 마감이 없다" }
        if !waiting, handshakeArmed { return "핸드셰이크 중이 아닌데 마감이 걸려 있다" }
        return nil
    }
}

/// 이 Mac 자신의 상태. **기계가 없는 축이라 값이 흩어져 있다** — 파일 머리 주석 참조.
struct HostSnapshot {
    // 수신 (LAN 서버)
    var serving = false
    var controlPort: UInt16 = 0
    var localIP = "—"

    // ICE 라운드 — 지금은 straight-line async 코드로 표현돼 있어 "지금 어느 단계인가"를
    // 가리키는 변수가 없다. 대신 라운드가 남긴 흔적을 보여준다.
    var negoRound = 0
    var nat = "—"
    var punchPort: UInt16 = 0
    /// 이번 라운드의 펀치 토큰이 세워져 있는가 = RESUME을 받아들일 준비가 된 상태.
    var resumeArmed = false

    // 등록부
    var deviceCount = 0
    var streamingCount = 0

    // (제거됨) `liveDisplays`·`sckCycling`·`pendingSCKDrops`.
    //
    // 세션 등록부가 `DisplayManager`를 읽어 여기 옮겨 담던 값들이다. 그러려면 세션 계층이
    // 디스플레이를 알아야 해서 원칙 1의 마지막 잔재였는데, 정작 **아무도 중계를 필요로 하지
    // 않았다** — `DisplayManager`는 MainActor 싱글턴이라 진단 화면이 직접 읽으면 그만이다.

    // 곁가지 링크
    var usbStatus = "—"
    var blePhase = "—"
}
