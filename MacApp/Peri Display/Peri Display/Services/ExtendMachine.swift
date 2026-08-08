//
//  ExtendMachine.swift
//  Peri Display
//
//  **서비스 #1(화면 확장)의 상태 기계** — 순수 함수. `swiftc` 한 줄로 모델 검사가 돈다.
//
//  여태 이 축들은 `DeviceMachine` 안에 있었다. 세션이 곧 미러였고 서비스가 하나뿐이었으니
//  그게 이상하지 않았다. 이제는 아니다 — 서비스가 늘 때마다 기기 기계를 뜯어야 한다면
//  "새 서비스 추가 = 파일 추가 + 등록 한 줄"이 거짓말이 된다(DESIGN.md 원칙 2·3).
//
//  기기 기계는 **누가 화면을 갖는가**까지만 안다(`ScreenMode`). 그 안에서 가상 디스플레이를
//  빌리고 캡처를 세우고 SCK 재열거 사이클을 견디는 일은 전부 여기 있다.
//
//  세 축
//  ────
//    active   — 기기 기계가 이 서비스를 깨웠는가
//    link     — 보낼 곳이 있는가 (채널)
//    pipe     — 캡처·인코더가 어디까지 서 있는가
//
//  `display`는 축이 아니라 **기억**이다. 파이프라인이 몇 번을 오르내려도 가상 디스플레이는
//  기기에 매여 살아 있어야 한다 — 세션마다 파괴·재생성하면 ScreenCaptureKit 재열거 사이클이
//  돌아 붙어 있는 **모든** 기기가 1초씩 끊긴다(FB17797423).
//

import Foundation

/// 캡처·인코더 파이프라인.
///
/// 세션·경로와 **독립적으로** 오르내린다. SCK 재열거 사이클이 붙어 있는 모든 기기의
/// 파이프라인을 한꺼번에 내렸다 올리기 때문이다 — 그동안 세션도 경로도 멀쩡하다.
enum Pipe: Hashable, Sendable {
    case down
    case building
    case up
    /// SCK 재열거 사이클 때문에 잠시 내려간 상태. 사이클이 끝나면 자동 복귀한다.
    case suspended
}

/// 디스플레이를 세우는 데 필요한 제원. 이게 있어야 `CGVirtualDisplay`를 만들 수 있으므로,
/// 기기가 식별된 직후가 아니라 **이 값이 도착한 순간**이 디스플레이의 시작점이다.
struct DisplaySpec: Hashable, Sendable {
    var width: Int
    var height: Int
    var ppi: Double
    var refreshRate: Double
    var hiDPI: Bool
    var maxBound: Int
    /// HDR(확장 휘도) 디스플레이로 만들지. 모드 목록에 박히는 값이라 spec의 일부다 —
    /// 바뀌면 크기가 같아도 디스플레이를 재구성해야 한다.
    var hdr: Bool = false
}

struct ExtendState: Hashable, Sendable {
    /// 기기 기계가 이 서비스를 깨웠는가. 꺼져 있으면 어떤 사건도 파이프라인을 세우지 않는다.
    var active = false
    /// 보낼 곳이 있는가. 채널이 끊겨도 서비스는 살아 있다(디스플레이를 지킨다).
    var linked = false
    var pipe: Pipe = .down
    /// 디스플레이를 실제로 빌린 상태인가. 한 번 참이 되면 서비스가 재워질 때까지 유지된다.
    var hasDisplay = false
    /// 마지막으로 받은 제원 — 재협상(회전·분할)마다 갱신된다.
    var spec: DisplaySpec?

    /// 지금 화면을 내보내고 있는가.
    var isStreaming: Bool { active && linked && pipe == .up }

    /// UI 보조 문구. 기기 기계의 요약 뒤에 붙는다.
    var detail: String? {
        guard active else { return nil }
        switch pipe {
        case .up:        return nil            // 평상시엔 할 말이 없다
        case .building:  return "디스플레이 준비 중…"
        case .suspended: return "디스플레이 재구성 중…"
        case .down:      return linked ? "제원을 기다리는 중…" : "경로를 기다리는 중…"
        }
    }
}

enum ExtendEv: Equatable, Sendable {
    /// 기기 기계가 깨웠다 / 재웠다.
    case activated
    case deactivated
    /// 말할 채널이 생겼다 / 끊겼다.
    case linked
    case unlinked
    /// 화면 제원 도착(첫 협상 또는 회전·분할 재협상).
    case specChanged(DisplaySpec)
    case pipelineReady
    case pipelineFailed(String)
    /// SCK 재열거 사이클 — 조정자가 모든 기기에 뿌린다.
    case suspendForSCK
    case resumeAfterSCK
}

enum ExtendFx: Equatable, Sendable {
    /// 이 제원으로 디스플레이를 빌린다(없으면 생성, 있으면 resize-in-place).
    case leaseDisplay(DisplaySpec)
    case buildPipeline
    case teardownPipeline
    case suspendPipeline
    case resumePipeline
    /// 디스플레이를 파괴하고 리스를 놓는다.
    case releaseDisplay(reason: String)
    /// 이 서비스가 못 하겠다고 기기 기계에 보고한다 → 화면이 꺼진다.
    case reportFailed(String)
    case log(String)
}

struct ExtendStep: Equatable {
    var state: ExtendState
    var fx: [ExtendFx] = []
}

/// 지금 제원으로 파이프라인을 (다시) 세우는 지시 — 조건이 안 맞으면 빈 배열이다.
///
/// 세 갈래가 전부 같은 질문을 하므로(`activated`·`linked`·`specChanged`) 한 곳에 모은다.
/// 예전엔 갈래마다 조건을 다시 썼고, 한 갈래에서 조건을 빠뜨린 게 Wi-Fi→LTE 전환에서
/// 화면이 마지막 프레임에 얼어붙던 버그였다.
private func rebuild(_ s: inout ExtendState) -> [ExtendFx] {
    guard s.active, s.linked, let spec = s.spec else { return [] }
    guard s.pipe != .suspended else { return [] }   // 사이클의 소유물 — 손대지 않는다
    s.pipe = .building
    return [.leaseDisplay(spec), .buildPipeline]
}

/// 내려가 있을 때만 세운다.
///
/// **이미 서 있으면 손대지 않는 게 핵심이다.** 경로가 잠깐 끊겼다 돌아온 경우가 여기인데,
/// 그때 파이프라인은 계속 돌고 있었다(`unlinked`가 아무것도 안 버린다). 다시 세우면 수신측이
/// 키프레임을 기다리는 검은 화면이 생긴다 — 아무것도 안 하는 게 곧 무중단이다.
private func buildIfDown(_ s: inout ExtendState) -> [ExtendFx] {
    guard s.pipe == .down else { return [] }
    return rebuild(&s)
}

/// 전이 함수. **순수**하며 모든 (상태, 사건) 쌍에 대해 정의된다.
func reduceExtend(_ s: ExtendState, _ ev: ExtendEv) -> ExtendStep {
    switch ev {

    case .activated:
        guard !s.active else { return ExtendStep(state: s) }
        var n = s
        n.active = true
        // fx를 **먼저** 받는다. `ExtendStep(state: n, fx: buildIfDown(&n))`로 쓰면
        // `state: n`이 먼저 평가돼 `buildIfDown`의 변경(pipe = .building)이 빠진다.
        // (모델 검사가 이걸 잡았다.)
        let fx = buildIfDown(&n)
        return ExtendStep(state: n, fx: fx)

    case .deactivated:
        guard s.active else { return ExtendStep(state: s) }
        var n = s
        n.active = false
        n.pipe = .down
        n.hasDisplay = false
        // ★ 재워질 때는 디스플레이까지 놓는다. `unlinked`와 다른 점이 정확히 이것이다 —
        // 경로가 끊긴 건 곧 돌아올 수 있다는 뜻이지만, 사용자가 확장을 껐다면 가상
        // 디스플레이가 데스크톱에 남아 있을 이유가 없다.
        return ExtendStep(state: n, fx: [.teardownPipeline, .releaseDisplay(reason: "확장 꺼짐")])

    case .linked:
        guard !s.linked else { return ExtendStep(state: s) }
        var n = s
        n.linked = true
        let fx = buildIfDown(&n)
        return ExtendStep(state: n, fx: fx)

    case .unlinked:
        guard s.linked else { return ExtendStep(state: s) }
        var n = s
        n.linked = false
        // ★ 파이프라인도 디스플레이도 유지한다. 경로만 사라졌을 뿐이고 클라이언트는 지금
        // BLE로 새 경로를 뚫는 중일 수 있다 — 유예 안에 돌아오면 아무 일도 없었던 게 된다.
        // (내리면 복귀 때 키프레임 대기 = 검은 화면. 헛도는 인코딩 비용보다 그쪽이 훨씬 비싸다.)
        return ExtendStep(state: n)

    case .specChanged(let spec):
        var n = s
        n.spec = spec
        // 여기만 `rebuild`다 — 회전·분할은 **서 있는 파이프라인도** 새 크기로 다시 세워야 한다.
        // (`activated`·`linked`는 내려가 있을 때만 세운다. 그 차이가 무중단 복귀를 만든다.)
        let fx = rebuild(&n)
        return ExtendStep(state: n, fx: fx.isEmpty ? [.log("제원 기록(아직 세울 때가 아니다)")] : fx)

    case .pipelineReady:
        var n = s
        guard n.pipe == .building else { return ExtendStep(state: n) }
        n.pipe = .up
        n.hasDisplay = true
        return ExtendStep(state: n)

    case .pipelineFailed(let why):
        var n = s
        n.pipe = .down
        // 구성 실패는 **서비스가 감당한다** — 기기 기계에 올리면 화면이 통째로 꺼진다.
        // 디스플레이 오염 복구가 곧 다시 시도하므로 모드를 유지하는 편이 맞다.
        return ExtendStep(state: n, fx: [.log("파이프라인 실패: \(why)")])

    case .suspendForSCK:
        guard s.pipe == .up || s.pipe == .building else { return ExtendStep(state: s) }
        var n = s
        n.pipe = .suspended
        n.hasDisplay = false   // 사이클이 디스플레이를 파괴한다
        return ExtendStep(state: n, fx: [.suspendPipeline])

    case .resumeAfterSCK:
        guard s.pipe == .suspended else { return ExtendStep(state: s) }
        var n = s
        guard let spec = n.spec else {
            n.pipe = .down
            return ExtendStep(state: n, fx: [.log("SCK 복귀 — 제원을 모른다")])
        }
        // ★ 디스플레이는 **경로가 없어도** 되살린다.
        //
        // 사이클이 모든 디스플레이를 파괴했는데, 마침 이 기기가 핸드오버 중이라 경로가 잠깐
        // 없을 수 있다. 그때 디스플레이를 안 만들면 배치·serial이 날아가고, 경로가 돌아왔을 때
        // 새로 만들어야 해서 **또 한 번 SCK 사이클**을 유발한다. 디스플레이 수명이 기기에
        // 매여 있다는 원칙이 바로 이런 경우를 위한 것이다.
        //
        // 반면 파이프라인(캡처·인코더)은 보낼 곳이 있을 때만 돌린다.
        guard s.linked else {
            n.pipe = .down
            return ExtendStep(state: n, fx: [
                .leaseDisplay(spec),
                .log("SCK 복귀 — 디스플레이만 되살리고 파이프라인은 경로 복귀 시"),
            ])
        }
        n.pipe = .building
        return ExtendStep(state: n, fx: [.leaseDisplay(spec), .resumePipeline])
    }
}
