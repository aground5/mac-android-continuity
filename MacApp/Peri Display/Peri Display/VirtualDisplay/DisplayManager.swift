//
//  DisplayManager.swift
//  Peri Display
//
//  기기(신원 fingerprint)당 가상 디스플레이 1개를 앱 수명 동안 영속 소유한다.
//
//  왜 세션과 분리하나: 예전엔 세션이 디스플레이를 만들고 부쉈다(세션 수명 = 디스플레이 수명).
//  세션은 자주 죽고 살아나므로(LAN↔AOA 전환·USB 글리치·재연결) 디스플레이 churn이 상시 발생했다.
//  그 churn이 치명적인 이유는 **ScreenCaptureKit 버그(FB17797423)** 때문이다 — 가상 디스플레이
//  구성이 바뀌면 SCK가 내부 재열거를 못 해, 이후 만든 SCStream이 SCContentFilter에 올바른
//  displayID·좌표가 들어 있어도 '마지막에 연결된 가상 스크린'의 프레임버퍼를 물고 온다.
//  즉 *다른 기기 화면 송출*. (실측: 태블릿 스트림이 폰 화면을 레터박스로 수신.)
//  참고: https://developer.apple.com/forums/thread/786829
//  부수적으로 창 이주(디스플레이가 부서질 때 macOS가 창을 옮김)도 사용자 경험을 해친다.
//
//  그래서 여기서 지문당 하나를 영속 소유해 churn을 없앤다. 전송 전환·재연결은 같은 디스플레이를
//  무중단으로 인계받으므로 구성 변경이 아예 일어나지 않는다.
//
//  **수명 결정은 여기 없다.** 이 클래스는 "만들어라 / 크기를 바꿔라 / 부숴라"만 안다.
//  언제 그럴지는 기기 상태 기계(`DeviceMachine`)가 정하고 `SessionManager`가 지시한다.
//  예전엔 여기에도 holder 약참조·unheldSince·주기 스윕이 있어서, 등록부와 이 클래스가 각자
//  "기기가 아직 있는가"를 판단했다 — 둘의 의견이 갈리는 순간이 곧 버그였다.
//

import Foundation
import CoreGraphics
import ScreenCaptureKit
import OSLog

private nonisolated let log = Logger(subsystem: "com.peri.display.mac", category: "displaymgr")

@MainActor
final class DisplayManager {
    static let shared = DisplayManager()
    private init() {}

    /// SCK 재열거 사이클을 구독한 앱들. 사이클이 돌면 파이프라인을 내렸다 올려야 한다.
    ///
    /// **구독이지 훅이 아니다.** 예전엔 클로저 하나였고 세션 등록부가 그걸 잡아 모든 기기의
    /// 확장 앱으로 뿌렸는데, 그러면 "가상 디스플레이가 흔들린다"는 사실이 세션 계층을 한 번
    /// 통과한다. 세션은 디스플레이를 모르는 게 맞고(원칙 1), 공유 앱처럼 디스플레이를 안 쓰는
    /// 앱은 이 사건을 알 필요조차 없다 — 구독하지 않으면 그만이다.
    ///
    /// 키는 기기 지문이다. 기기가 떠나면 스스로 뺀다.
    private var sckObservers: [String: @MainActor (SCKPhase) async -> Void] = [:]

    func observeSCKCycle(_ key: String, _ hook: @escaping @MainActor (SCKPhase) async -> Void) {
        sckObservers[key] = hook
    }

    func removeSCKObserver(_ key: String) { sckObservers.removeValue(forKey: key) }

    private func notifySCK(_ phase: SCKPhase) async {
        // 사이클이 도는 동안 구독자가 바뀔 수 있다 — 스냅샷으로 돈다.
        for hook in Array(sckObservers.values) { await hook(phase) }
    }

    enum SCKPhase { case suspendAll, resumeAll }

    private struct Entry {
        let display: PeriVirtualDisplay
        let serial: UInt32
    }
    private var entries: [String: Entry] = [:]   // fingerprint → 영속 디스플레이
    /// 파괴했지만 ScreenCaptureKit이 아직 목록에서 안 내린 디스플레이 ID들.
    /// 이게 남아 있는 동안 새 디스플레이를 만들면 SCK가 새 SCDisplay를 *옛 표면*에 매핑한다
    /// (실측: 새 디스플레이의 스트림이 다른 기기 화면을 레터박스로 받았다). 반드시 비운 뒤 생성.
    private var awaitingSCKDrop: Set<CGDirectDisplayID> = []
    /// SCK 재열거 사이클 진행 중 — 재진입(사이클 안의 lease/discard)이 사이클을 또 돌리지 않게.
    private var cycling = false

    struct Lease {
        let display: PeriVirtualDisplay
        let displayID: CGDirectDisplayID   // confirmedDisplayID로 정체성 확정된 값
        let serial: UInt32
    }

    // MARK: - 진단용 관찰창
    //
    // 아래 값들은 **전역**이다 — 기기 상태 기계는 자기 파이프라인만 알지, 사이클이 도는지는
    // 모른다(지시를 받을 뿐이다). 한 기기의 증상이 사실은 전역 사이클 때문일 때가 있어서
    // 진단 화면에 같이 놓는다.

    /// 살아 있는 가상 디스플레이 수 (지문별 1개).
    var liveDisplayCount: Int { entries.count }
    /// SCK 재열거 사이클이 도는 중인가 — 그동안 모든 기기의 파이프라인이 내려간다.
    var isCycling: Bool { cycling }
    /// 파괴했지만 ScreenCaptureKit이 아직 목록에서 안 내린 디스플레이 ID 수.
    /// 0이 아니면 새 디스플레이 생성이 대기 중이라는 뜻이다(FB17797423 회피).
    var pendingSCKDrops: Int { awaitingSCKDrop.count }
    /// 지문 → 디스플레이 ID. 진단에서 "이 기기가 어느 디스플레이인가"를 확정하는 값.
    var displayIDsByFingerprint: [String: CGDirectDisplayID] {
        entries.mapValues { $0.display.displayID }
    }

    /// 이 기기의 디스플레이를 준다 — 없으면 만들고, 있으면 재사용한다.
    ///
    /// **언제 부를지는 여기서 정하지 않는다.** 기기 상태 기계(`DeviceMachine`)가 `leaseDisplay`
    /// 부작용으로 지시할 때만 불린다. 예전엔 이 함수가 "이전 세션 정리"까지 했는데, 그건 수명
    /// 결정이라 등록부와 중복이었다 — 기계가 기기당 세션이 하나임을 보장하므로 사라졌다.
    ///
    ///  - 기존 디스플레이가 있으면 재사용(크기 다르면 resize-in-place) → ID·창 배치 유지.
    ///  - 없으면 생성 후 정체성 확정(confirmedDisplayID)까지 대기. 실패 시 반드시 파괴(leak 금지) 후 nil.
    func lease(fingerprint: String, deviceName: String,
               width: Int, height: Int, ppi: Double, refreshRate: Double,
               hiDPI: Bool, maxBound: Int, hdr: Bool = false) async -> Lease? {
        // 기존 디스플레이 재사용
        if let e = entries[fingerprint] {
            let vd = e.display
            // HDR 전환은 재사용이 불가능하다. transferFunction은 모드 목록이라 교체로 되지만
            // 색역(P3) 선언은 **디스크립터**에 있고 그건 생성 시점에만 정해진다 → 다시 만들어야
            // 둘이 일치한다. 사용자가 토글을 누를 때만 일어나는 일이라 비용을 감수한다.
            if vd.hdr != hdr {
                log.notice("displaymgr[\(fingerprint)]: HDR \(hdr ? "켜짐" : "꺼짐") → 디스플레이 재생성")
                discard(fingerprint: fingerprint)
            } else if vd.isOnline() {
                var ok = true
                if vd.pixelWidth != UInt32(width) || vd.pixelHeight != UInt32(height) {
                    ok = vd.resize(toWidth: UInt32(width), height: UInt32(height),
                                   ppi: ppi, refreshRate: refreshRate, hiDPI: hiDPI)
                    if !ok { log.error("displaymgr[\(fingerprint)]: 재사용 resize 실패 → 재생성") }
                }
                let vdid = vd.confirmedDisplayID()
                if ok && vdid != 0 {
                    entries[fingerprint] = Entry(display: vd, serial: e.serial)
                    log.notice("displaymgr[\(fingerprint)]: 디스플레이 재사용 display=\(vdid) serial=\(e.serial)")
                    return Lease(display: vd, displayID: vdid, serial: e.serial)
                }
            }
            // 오프라인/불일치/resize 실패 → 폐기 후 아래에서 새로 생성
            discard(fingerprint: fingerprint)
        }

        // 신규 생성 (지문별 고유 serial — 배치/스케일 영속의 열쇠)
        let serial = DisplaySerialStore.serial(for: fingerprint)

        let vd = PeriVirtualDisplay(
            name: "Peri · \(deviceName)",
            serialNum: serial,
            width: UInt32(width), height: UInt32(height),
            ppi: ppi, refreshRate: refreshRate, hiDPI: hiDPI,
            maxPixelDim: UInt32(maxBound)
        )
        vd.hdr = hdr   // 모드 목록을 만들 때 읽으므로 create() 전에 정해져 있어야 한다
        guard vd.create() else {
            log.error("displaymgr[\(fingerprint)]: CGVirtualDisplay 생성 실패")
            return nil
        }
        // 정체성 확정 대기 — 등록 전엔 vendor/product/serial이 아직 0xffffffff라 confirmedDisplayID가 0.
        var vdid: CGDirectDisplayID = 0
        for _ in 0..<40 {
            vdid = vd.confirmedDisplayID()
            if vdid != 0 { break }
            try? await Task.sleep(for: .milliseconds(50))
        }
        guard vdid != 0 else {
            log.error("displaymgr[\(fingerprint)]: 정체성 확정 실패 (serial=\(serial)) → 파괴 (leak 방지)")
            vd.destroy()
            return nil
        }
        entries[fingerprint] = Entry(display: vd, serial: serial)
        log.notice("displaymgr[\(fingerprint)]: 디스플레이 생성 display=\(vdid) serial=\(serial) \(width)x\(height)")
        // HDR을 요청했으면 실제로 헤드룸을 받았는지 즉시 판정한다 — 선언이 먹혔는지는
        // 이 값 말고는 알 방법이 없고, 이후 캡처·인코더 설정이 전부 여기에 달려 있다.
        if hdr { log.notice("displaymgr[\(fingerprint)]: HDR \(vd.hdrDiagnostics())") }
        return Lease(display: vd, displayID: vdid, serial: serial)
    }

    /// 오염 복구 전용: 디스플레이를 파괴하고 항목 제거 — 다음 lease가 새로 만든다.
    /// (오프라인 복구는 호출 전 DisplaySerialStore.rotate로 신원을 회전시켜 오염 배치를 탈출.)
    func discard(fingerprint: String) {
        guard let e = entries.removeValue(forKey: fingerprint) else { return }
        // 파괴 전 ID를 기억해 둔다 — SCK가 이 ID를 목록에서 내릴 때까지 새 디스플레이를 만들면 안 된다.
        let doomed = e.display.displayID
        if doomed != 0 { awaitingSCKDrop.insert(doomed) }
        log.notice("displaymgr[\(fingerprint)]: 디스플레이 폐기 (serial=\(e.serial) display=\(doomed))")
        e.display.destroy()
    }

    /// 기기 이탈로 디스플레이를 없앤다. 남아 있는 가상 디스플레이가 있으면 SCK 재열거까지 수행한다.
    func retire(fingerprint: String, reason: String) async {
        guard entries[fingerprint] != nil else { return }
        log.notice("displaymgr[\(fingerprint)]: 기기 떠남(\(reason)) → 디스플레이 정리")
        discard(fingerprint: fingerprint)
        if !entries.isEmpty { await recycleForSCK() }
    }

    /// ★ Apple 버그 우회 (FB17797423, developer.apple.com/forums/thread/786829).
    ///
    /// 가상 디스플레이 구성이 바뀌면 ScreenCaptureKit이 내부 재열거를 못 해서, 이후 만든 SCStream이
    /// **필터에 올바른 displayID·좌표가 들어 있어도 '마지막에 연결된 가상 스크린'의 프레임버퍼**를
    /// 물고 온다. 실측으로 확인: 태블릿 디스플레이의 스트림이 폰 디스플레이 화면을 레터박스로 받았다
    /// (평균색이 정확히 종횡비 비율만큼 어두웠다). 필터는 멀쩡한데 픽셀만 남의 것이라 코드로는 못 고친다.
    ///
    /// 유일하게 알려진 회복 방법이 "가상 디스플레이를 전부 내렸다가 다시 올리기"다. 그래서 하나를
    /// 없앨 때 다른 게 남아 있으면 전부 내렸다 되살린다. 남은 기기들이 잠깐(≈1s) 끊기지만,
    /// 이탈은 드문 사건이고 대안은 화면이 뒤바뀐 채 계속 나가는 것이다.
    ///
    /// (예전 '영속 디스플레이' 구조가 이 버그를 안 건드린 이유도 같다 — 구성 변경이 아예 없었다.)
    private func recycleForSCK() async {
        guard !cycling else { return }
        cycling = true
        defer { cycling = false }

        log.notice("displaymgr: SCK 재열거 사이클 시작 — 가상 디스플레이 \(self.entries.count)개 전부 내림")
        // 누가 이 디스플레이들을 쓰고 있는지는 구독자만 안다. 여기서 세션을 약참조로 들고 있으면
        // 그게 곧 두 번째 수명 관리가 된다.
        await notifySCK(.suspendAll)
        for fp in Array(entries.keys) { discard(fingerprint: fp) }

        await waitForSCKDrop()          // SCK 목록에서 전부 사라질 때까지
        try? await Task.sleep(for: .milliseconds(300))   // 재열거 안정화 여유

        await notifySCK(.resumeAll)     // 기기별 leaseDisplay가 새 디스플레이를 만든다
        log.notice("displaymgr: SCK 재열거 사이클 완료")
    }

    /// ScreenCaptureKit이 파괴된 디스플레이를 목록에서 내릴 때까지 기다린다.
    ///
    /// ★ 이걸 안 기다리면 화면이 뒤바뀐다(실측 증명): 디스플레이를 부수고 곧바로 새로 만들면,
    /// SCK는 새 displayID에 대해 겉보기엔 올바른 SCDisplay(id·frame 모두 일치)를 돌려주지만
    /// 그 스트림이 실어 나르는 픽셀은 *다른 디스플레이*의 것이었다. CG 테이블(CGGetOnlineDisplayList)이
    /// 먼저 갱신되고 SCK 캐시가 뒤늦게 따라오기 때문 — CG만 보고 기다린 게 부족했다.
    private func waitForSCKDrop(timeout: TimeInterval = 3.0) async {
        guard !awaitingSCKDrop.isEmpty else { return }
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            guard let content = try? await SCShareableContent.excludingDesktopWindows(
                false, onScreenWindowsOnly: true) else { break }
            let live = Set(content.displays.map(\.displayID))
            awaitingSCKDrop.subtract(awaitingSCKDrop.subtracting(live))   // 사라진 건 제거
            if awaitingSCKDrop.isEmpty {
                log.notice("displaymgr: SCK가 옛 디스플레이를 목록에서 내림 — 생성 진행")
                return
            }
            try? await Task.sleep(for: .milliseconds(100))
        }
        let left = awaitingSCKDrop.map(String.init).joined(separator: ",")
        log.warning("displaymgr: SCK가 \(Int(timeout))s 내 옛 디스플레이(\(left))를 안 내림 — 그대로 진행")
        awaitingSCKDrop.removeAll()
    }
}
