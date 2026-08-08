//
//  ExtendTests.swift
//  화면 확장 서비스의 기계 — 파이프라인 · 디스플레이 수명 · SCK 재열거 사이클.
//
//  기기 기계와 **따로** 검사한다. 이 기계는 세션도 신원도 모르고, `active`·`linked` 두
//  불리언으로만 바깥과 만난다 — 그래서 상태공간이 작고 검사가 날카롭다.
//

import Foundation

let extSpec = DisplaySpec(width: 1080, height: 2400, ppi: 400, refreshRate: 120,
                          hiDPI: true, maxBound: 4096)
let extSpec2 = DisplaySpec(width: 2400, height: 1080, ppi: 400, refreshRate: 120,
                           hiDPI: true, maxBound: 4096)

extension ExtendState {
    func after(_ evs: ExtendEv...) -> ExtendState {
        evs.reduce(self) { st, e in reduceExtend(st, e).state }
    }
    func step(_ ev: ExtendEv) -> ExtendStep { reduceExtend(self, ev) }
    func fx(_ ev: ExtendEv) -> [ExtendFx] { reduceExtend(self, ev).fx }
}

/// 정상 송출 중.
func streamingExtend() -> ExtendState {
    ExtendState().after(.activated, .linked, .specChanged(extSpec), .pipelineReady)
}

// MARK: - 1. 세울 조건

/// 세 재료(깨움·경로·제원)가 다 모여야 세운다. **도착 순서는 무관하다** —
/// 예전엔 갈래마다 조건을 다시 썼고, 한 갈래에서 조건을 빠뜨린 게 Wi-Fi→LTE 전환에서
/// 화면이 마지막 프레임에 얼어붙던 버그였다.
func testExtendBuildsRegardlessOfOrder() {
    let orders: [[ExtendEv]] = [
        [.activated, .linked, .specChanged(extSpec)],
        [.linked, .specChanged(extSpec), .activated],
        [.specChanged(extSpec), .activated, .linked],
        [.specChanged(extSpec), .linked, .activated],
    ]
    for order in orders {
        var s = ExtendState()
        var built = false
        for ev in order {
            let step = reduceExtend(s, ev)
            s = step.state
            if step.fx.contains(.buildPipeline) { built = true }
        }
        checks += 1
        if !built { failures.append("이 순서로는 파이프라인을 안 세운다: \(order)") }
        expect(s.pipe, .building, "세 재료가 모이면 구축 중")
    }
}

func testExtendNeedsAllThree() {
    // 하나라도 빠지면 안 세운다.
    check(!ExtendState().after(.linked, .specChanged(extSpec)).fx(.specChanged(extSpec)).contains(.buildPipeline),
          "깨우지 않았으면 안 세운다")
    check(!ExtendState().after(.activated, .specChanged(extSpec)).fx(.specChanged(extSpec)).contains(.buildPipeline),
          "경로가 없으면 안 세운다")
    let noSpec = ExtendState().after(.activated)
    check(!noSpec.fx(.linked).contains(.buildPipeline), "제원이 없으면 안 세운다")
}

func testExtendReadyAndFail() {
    let up = streamingExtend()
    expect(up.pipe, .up, "구축 완료")
    check(up.hasDisplay, "디스플레이를 들고 있다")
    check(up.isStreaming, "송출 중")

    // 구성 실패는 **서비스가 감당한다** — 기기 기계에 올리면 화면이 통째로 꺼진다.
    let failed = up.step(.pipelineFailed("SCStream 실패"))
    expect(failed.state.pipe, .down, "실패 → 내려감")
    checks += 1
    if failed.fx.contains(where: { if case .reportFailed = $0 { return true }; return false }) {
        failures.append("구성 실패로 모드까지 껐다 — 복구가 곧 다시 시도한다")
    }
}

// MARK: - 2. 회전 · 재협상

func testResizeOnSpecChange() {
    let up = streamingExtend()
    let rot = up.step(.specChanged(extSpec2))
    expect(rot.state.pipe, .building, "회전 → 재구축")
    check(rot.fx.contains(.leaseDisplay(extSpec2)), "새 제원으로 디스플레이를 다시 빌린다")
    check(rot.fx.contains(.buildPipeline), "파이프라인 재구성")
    expect(rot.state.spec, extSpec2, "제원 갱신")
}

// MARK: - 3. 경로 상실 vs 재움 — **여기가 extend와 share가 갈리는 지점**

func testUnlinkedKeepsEverything() {
    // ★ 경로만 사라졌다. 파이프라인도 디스플레이도 유지한다 — 클라이언트는 지금 BLE로
    // 새 경로를 뚫는 중일 수 있고, 유예 안에 돌아오면 아무 일도 없었던 게 된다.
    let up = streamingExtend()
    let lost = up.step(.unlinked)
    check(lost.state.hasDisplay, "디스플레이 유지")
    expect(lost.state.pipe, .up, "파이프라인도 유지 — 내리면 복귀 때 검은 화면")
    checks += 1
    if !lost.fx.isEmpty { failures.append("경로 상실에 부작용이 있다: \(lost.fx)") }

    // 돌아오면 그대로다.
    let back = lost.state.step(.linked)
    check(back.state.isStreaming, "복귀 즉시 송출")
}

func testDeactivateReleasesDisplay() {
    // ★ 재움은 다르다. 사용자가 확장을 껐다면 가상 디스플레이가 데스크톱에 남을 이유가 없다.
    let up = streamingExtend()
    let off = up.step(.deactivated)
    check(!off.state.hasDisplay, "디스플레이를 놓는다")
    expect(off.state.pipe, .down, "파이프라인도 내린다")
    check(off.fx.contains(.teardownPipeline), "캡처 정지")
    check(off.fx.contains(where: { if case .releaseDisplay = $0 { return true }; return false }),
          "디스플레이 파괴")
    guard let iTear = off.fx.firstIndex(of: .teardownPipeline),
          let iRel = off.fx.firstIndex(where: { if case .releaseDisplay = $0 { return true }; return false })
    else { fail("teardown/release가 없다"); return }
    check(iTear < iRel, "★ 캡처를 먼저 멈추고 디스플레이를 부순다 (순서가 곧 정확성)")

    // 다시 깨우면 제원은 남아 있으므로 곧바로 다시 선다.
    let on = off.state.step(.activated)
    expect(on.state.pipe, .building, "다시 깨우면 곧바로 재구축")
}

// MARK: - 4. SCK 재열거 사이클

func testSCKCycle() {
    let up = streamingExtend()
    let susp = up.step(.suspendForSCK)
    expect(susp.state.pipe, .suspended, "사이클 중 정지")
    check(!susp.state.hasDisplay, "사이클이 디스플레이를 파괴한다")
    check(susp.fx.contains(.suspendPipeline), "캡처 정지")

    let res = susp.state.step(.resumeAfterSCK)
    expect(res.state.pipe, .building, "복귀 → 재구축")
    check(res.fx.contains(.leaseDisplay(extSpec)), "디스플레이 재생성")
    check(res.fx.contains(.resumePipeline), "파이프라인 복귀")
    check(res.state.after(.pipelineReady).isStreaming, "복귀 완료")
}

func testSCKCycleWhileUnlinked() {
    // ★ 디스플레이는 **경로가 없어도** 되살린다. 안 그러면 배치·serial이 날아가고,
    // 경로가 돌아왔을 때 새로 만들어야 해서 **또 한 번 SCK 사이클**을 부른다.
    let susp = streamingExtend().after(.suspendForSCK, .unlinked)
    let res = susp.step(.resumeAfterSCK)
    check(res.fx.contains(.leaseDisplay(extSpec)), "그래도 디스플레이는 되살린다")
    expect(res.state.pipe, .down, "보낼 곳이 없으면 파이프라인은 안 올린다")
    checks += 1
    if res.fx.contains(.resumePipeline) { failures.append("경로가 없는데 파이프라인을 세웠다") }

    // 경로가 돌아오면 그때 세운다.
    let back = res.state.step(.linked)
    expect(back.state.pipe, .building, "경로 복귀 시 구축")
}

func testSuspendedIsUntouchable() {
    // 사이클이 소유한 상태다. 그동안 제원이 바뀌어도 손대지 않는다 — 복귀 때 반영된다.
    let susp = streamingExtend().after(.suspendForSCK)
    let mid = susp.step(.specChanged(extSpec2))
    expect(mid.state.pipe, .suspended, "★ 사이클 중에는 파이프라인을 건드리지 않는다")
    expect(mid.state.spec, extSpec2, "제원은 기록해 둔다")
    let res = mid.state.step(.resumeAfterSCK)
    check(res.fx.contains(.leaseDisplay(extSpec2)), "복귀 때 새 제원으로")
}

// MARK: - 5. 도달성 · 불변식

let allExtendEvents: [ExtendEv] = [
    .activated, .deactivated, .linked, .unlinked,
    .specChanged(extSpec), .specChanged(extSpec2),
    .pipelineReady, .pipelineFailed("x"),
    .suspendForSCK, .resumeAfterSCK,
]

func reachableExtendStates() -> [ExtendState] {
    reachableStates(from: ExtendState(), events: allExtendEvents) { reduceExtend($0, $1).state }
}

func testExtendInvariants() {
    let all = reachableExtendStates()
    print("확장 기계 도달 상태: \(all.count)개")
    let rules: [(String, (ExtendState) -> Bool)] = [
        ("파이프라인이 올라와 있으면 디스플레이를 들고 있다", { $0.pipe != .up || $0.hasDisplay }),
        ("SCK 정지 중에는 디스플레이가 없다", { $0.pipe != .suspended || !$0.hasDisplay }),
        ("제원 없이는 파이프라인이 올라올 수 없다", { $0.pipe == .down || $0.spec != nil }),
        ("재워진 서비스는 파이프라인도 디스플레이도 없다", { s in
            s.active || (s.pipe == .down && !s.hasDisplay)
        }),
        ("송출 중이면 깨어 있고 경로도 있다", { s in
            !s.isStreaming || (s.active && s.linked)
        }),
    ]
    for (name, holds) in rules {
        checks += 1
        let bad = all.filter { !holds($0) }
        if !bad.isEmpty {
            failures.append("위반: \(name)\n" + bad.prefix(4).map {
                "    · active=\($0.active) linked=\($0.linked) pipe=\($0.pipe) display=\($0.hasDisplay) spec=\($0.spec != nil)"
            }.joined(separator: "\n"))
        }
    }
}

/// 재워진 상태에서는 **어떤 사건도** 파이프라인을 세우지 못한다.
/// 이게 "서비스는 요청받아야 산다"의 기계적 보증이다.
func testDeactivatedNeverBuilds() {
    checks += 1
    for s in reachableExtendStates() where !s.active {
        for ev in allExtendEvents where ev != .activated {
            let step = reduceExtend(s, ev)
            if step.fx.contains(.buildPipeline) || step.state.pipe != .down {
                failures.append("재워진 서비스가 \(ev)로 파이프라인을 세웠다")
                return
            }
        }
    }
}

func runExtendTests() {
    testExtendBuildsRegardlessOfOrder()
    testExtendNeedsAllThree()
    testExtendReadyAndFail()
    testResizeOnSpecChange()
    testUnlinkedKeepsEverything()
    testDeactivateReleasesDisplay()
    testSCKCycle()
    testSCKCycleWhileUnlinked()
    testSuspendedIsUntouchable()
    testExtendInvariants()
    testDeactivatedNeverBuilds()
}
