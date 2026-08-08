//
//  DisplayArrangement.swift
//  Peri Display
//
//  데스크톱 디스플레이 배치의 읽기 전용 스냅샷 + 미니맵 뷰.
//
//  이 앱의 본질적 질문 — "내 폰 화면이 Mac 데스크톱의 어디에 붙었나" — 에 답하는 자리다.
//  드래그 배치는 macOS 시스템 설정이 이미 완벽히 제공하므로 흉내내지 않고 딥링크만 건다.
//

import SwiftUI
import Combine
import CoreGraphics
import AppKit
import OSLog

private let log = Logger(subsystem: "com.peri.display.mac", category: "arrangement")

/// 온라인 디스플레이 한 대의 배치 정보 (전역 좌표계).
struct DisplaySlot: Identifiable, Equatable {
    let id: CGDirectDisplayID
    let frame: CGRect      // CGDisplayBounds — 전역 데스크톱 좌표
    let isMain: Bool
    let isPeri: Bool       // 우리 가상 디스플레이인가 (vendor/product로 판별)
    let name: String

    var pixelLabel: String { "\(Int(frame.width))×\(Int(frame.height))" }
}

enum DisplayArrangement {
    /// 현재 온라인 디스플레이 전부를 조회. 조회 실패/유효값 없음이면 빈 배열.
    ///
    /// 전부 WindowServer 동기 IPC라 메인 전용이다(NSScreen도 메인 전용) — @MainActor로 못박아
    /// 실수로 백그라운드에서 부르지 못하게 한다. 재구성 *도중*엔 이 호출들이 느려질 수 있으므로
    /// 호출 빈도는 DisplayArrangementStore가 디바운스로 통제한다.
    @MainActor
    static func snapshot() -> [DisplaySlot] {
        var count: UInt32 = 0
        guard CGGetOnlineDisplayList(0, nil, &count) == .success, count > 0 else { return [] }
        var ids = [CGDirectDisplayID](repeating: 0, count: Int(count))
        var filled: UInt32 = 0
        guard CGGetOnlineDisplayList(count, &ids, &filled) == .success else { return [] }

        var seen = Set<CGDirectDisplayID>()
        var out: [DisplaySlot] = []
        // filled가 배열보다 크다고 보고돼도 넘지 않게 캡 (방어).
        for id in ids.prefix(Int(min(filled, count))) {
            // 조회와 조회 사이에 디스플레이가 사라질 수 있다 — 그 경우 CGDisplayBounds는
            // CGRectZero를 준다(헤더 명시). 그대로 두면 union이 원점까지 늘어나 미니맵이
            // 찌그러지므로 버린다. 비유한값도 함께 거른다(위치 계산이 NaN이 되면 CALayer가 죽는다).
            let b = CGDisplayBounds(id)
            guard isUsable(b), seen.insert(id).inserted else { continue }   // ForEach는 중복 id에 취약
            let isPeri = CGDisplayVendorNumber(id) == PeriVirtualDisplay.vendorID
                && CGDisplayModelNumber(id) == PeriVirtualDisplay.productID
            out.append(DisplaySlot(id: id,
                                   frame: b,
                                   isMain: CGDisplayIsMain(id) != 0,
                                   isPeri: isPeri,
                                   name: localizedName(of: id, isPeri: isPeri)))
        }
        return out
    }

    /// 화면 배치 계산에 쓸 수 있는 사각형인가 — 유한하고 넓이가 있는 것만.
    static func isUsable(_ r: CGRect) -> Bool {
        !r.isNull && !r.isInfinite && !r.isEmpty
            && r.origin.x.isFinite && r.origin.y.isFinite
            && r.size.width.isFinite && r.size.height.isFinite
            && r.size.width > 0 && r.size.height > 0
    }

    /// NSScreen에서 사람이 읽는 이름을 찾는다 (CGDirectDisplayID로 매칭). 없으면 종류로 대체.
    @MainActor
    private static func localizedName(of id: CGDirectDisplayID, isPeri: Bool) -> String {
        let key = NSDeviceDescriptionKey("NSScreenNumber")
        if let screen = NSScreen.screens.first(where: {
            ($0.deviceDescription[key] as? NSNumber)?.uint32Value == id
        }) {
            return screen.localizedName
        }
        return isPeri ? "Peri" : (CGDisplayIsBuiltin(id) != 0 ? "내장 디스플레이" : "디스플레이")
    }

    /// 시스템 설정 > 디스플레이 열기 — 배치 변경은 여기서 한다.
    static func openSystemSettings() {
        guard let url = URL(string: "x-apple.systempreferences:com.apple.Displays-Settings.extension") else { return }
        if !NSWorkspace.shared.open(url) {
            log.error("시스템 설정 디스플레이 창을 열지 못했습니다")
        }
    }
}

/// 배치 스냅샷의 단일 소유자.
///
/// 왜 필요한가: 예전엔 `SessionDetail`이 각자 `didChangeScreenParametersNotification`을 구독하고
/// 알림마다 곧장 snapshot()을 돌렸다. 그런데 ① 이 앱은 *스스로* 가상 디스플레이를 만들고·리사이즈하고
/// ·복구해(1초 주기 recoverDisplayIfNeeded) 그 알림을 계속 유발하고, ② 알림 하나에 세션 수만큼
/// 스냅샷이 돌았으며, ③ snapshot()은 전부 WindowServer 동기 IPC라 *재구성 도중*엔 느려진다.
/// 결과적으로 메인 스레드가 IPC 폭풍에 묶여 앱이 무응답이 될 수 있었다.
///
/// 그래서 구독은 앱 전체에 하나, 알림 버스트는 디바운스로 한 번의 조회로 뭉친다.
@MainActor
final class DisplayArrangementStore: ObservableObject {
    static let shared = DisplayArrangementStore()

    @Published private(set) var slots: [DisplaySlot] = []

    /// 재구성 버스트가 잦아들 때까지 기다리는 시간. 배치는 사람이 바꾸는 것이라 이 정도 지연은
    /// 체감되지 않고, 그 사이 WindowServer도 안정된다.
    private static let settleDelay: Duration = .milliseconds(250)

    private var refreshTask: Task<Void, Never>?
    private var observer: NSObjectProtocol?

    private init() {
        slots = DisplayArrangement.snapshot()
        observer = NotificationCenter.default.addObserver(
            forName: NSApplication.didChangeScreenParametersNotification,
            object: nil, queue: nil
        ) { [weak self] _ in
            Task { @MainActor in self?.scheduleRefresh() }
        }
    }

    deinit {
        if let observer { NotificationCenter.default.removeObserver(observer) }
    }

    /// 화면 구성이 바뀐 뒤 한 번만 다시 읽는다(버스트는 마지막 것으로 접힘).
    private func scheduleRefresh() {
        refreshTask?.cancel()
        refreshTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: Self.settleDelay)
            guard !Task.isCancelled, let self else { return }
            let fresh = DisplayArrangement.snapshot()
            // 빈 결과는 "디스플레이가 없다"가 아니라 조회 실패일 가능성이 크다(물리 화면은 항상 ≥1).
            // 그럴 땐 마지막으로 성공한 배치를 유지 — 미니맵이 깜빡이며 비는 것보다 낫다.
            guard !fresh.isEmpty else {
                log.warning("배치 조회가 비어 있음 — 직전 스냅샷 유지")
                return
            }
            if fresh != self.slots { self.slots = fresh }
        }
    }
}

// MARK: - 미니맵

/// 배치 미니맵. 전역 좌표를 캔버스에 비례 축소해 그린다. highlight = 이 세션의 디스플레이.
struct ArrangementMap: View {
    let slots: [DisplaySlot]
    let highlight: CGDirectDisplayID
    var height: CGFloat = 170

    var body: some View {
        GeometryReader { geo in
            let bounds = union
            // 배치·캔버스 어느 쪽이든 값이 성치 않으면 아무것도 그리지 않는다. NaN/무한이 그대로
            // .position에 들어가면 CALayer가 "position contains NaN"으로 앱을 죽인다 —
            // snapshot에서 이미 걸렀지만, 렌더 단계에서도 막아 두 겹으로 방어한다.
            if let layout = Layout(bounds: bounds, canvas: geo.size) {
                ForEach(slots) { slot in
                    let r = slot.frame
                    let x = r.midX * layout.scale + layout.offsetX
                    let y = r.midY * layout.scale + layout.offsetY
                    if x.isFinite, y.isFinite {
                        ScreenTile(slot: slot, isHighlighted: slot.id == highlight)
                            .frame(width: max(20, r.width * layout.scale),
                                   height: max(16, r.height * layout.scale))
                            .position(x: x, y: y)
                    }
                }
            }
        }
        .frame(height: height)
        .frame(maxWidth: .infinity)
        .padding(10)
        .background(Color.primary.opacity(0.035), in: RoundedRectangle(cornerRadius: 8))
    }

    /// 전역 좌표 → 캔버스 변환. 성립하지 않는 입력(빈 배치, 아직 크기 0인 캔버스, 비유한값)이면 nil.
    private struct Layout {
        let scale: CGFloat, offsetX: CGFloat, offsetY: CGFloat

        init?(bounds: CGRect, canvas: CGSize) {
            guard DisplayArrangement.isUsable(bounds),
                  canvas.width > 0, canvas.height > 0,
                  canvas.width.isFinite, canvas.height.isFinite else { return nil }
            let s = min(canvas.width / bounds.width, canvas.height / bounds.height) * 0.94
            guard s.isFinite, s > 0 else { return nil }
            let ox = (canvas.width - bounds.width * s) / 2 - bounds.minX * s
            let oy = (canvas.height - bounds.height * s) / 2 - bounds.minY * s
            guard ox.isFinite, oy.isFinite else { return nil }
            scale = s; offsetX = ox; offsetY = oy
        }
    }

    /// 모든 디스플레이를 감싸는 사각형 (전역 좌표). 빈 배치면 .null → Layout이 걸러낸다.
    private var union: CGRect {
        slots.reduce(CGRect.null) { $0.union($1.frame) }
    }
}

private struct ScreenTile: View {
    let slot: DisplaySlot
    let isHighlighted: Bool

    /// macOS가 우리 가상 디스플레이에 붙인 이름은 "Peri · SM-S931N" 형태다. 이 미니맵은 이미
    /// 그 기기의 세션 화면 안에 있어 기기명이 중복이고, 좁은 폰 타일엔 들어가지도 않는다.
    /// 접두사만 남겨 "여기가 Peri 화면"이라는 사실만 전한다.
    private var shortName: String {
        guard slot.isPeri, let head = slot.name.components(separatedBy: " · ").first,
              !head.isEmpty else { return slot.name }
        return head
    }

    var body: some View {
        RoundedRectangle(cornerRadius: 4)
            .fill(isHighlighted
                  ? AnyShapeStyle(Color.accentColor.opacity(0.18))
                  : AnyShapeStyle(Color(nsColor: .controlBackgroundColor)))
            .overlay {
                RoundedRectangle(cornerRadius: 4)
                    .strokeBorder(isHighlighted ? Color.accentColor : Color.secondary.opacity(0.4),
                                  lineWidth: isHighlighted ? 1.8 : 1)
            }
            .overlay {
                VStack(spacing: 1) {
                    // 주 디스플레이는 메뉴 막대가 있는 화면 — 작은 표시로 방향을 잡게 한다.
                    if slot.isMain {
                        Image(systemName: "menubar.rectangle")
                            .font(.system(size: 8))
                            .foregroundStyle(.tertiary)
                    }
                    Text(shortName)
                        .font(.system(size: 9, weight: .semibold))
                        .lineLimit(2)
                        .multilineTextAlignment(.center)
                    Text(slot.pixelLabel)
                        .font(.system(size: 8).monospacedDigit())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                .padding(3)
                .minimumScaleFactor(0.5)
                // 세로로 좁은 폰 타일에선 이름이 타일 폭을 넘어 밖으로 삐져나왔다.
                // 축소(minimumScaleFactor)만으로는 한계가 있어 타일 경계에서 잘라 낸다.
                .clipped()
            }
            .foregroundStyle(isHighlighted ? Color.accentColor : Color.secondary)
            .help(slot.isMain ? "\(slot.name) · 주 디스플레이" : slot.name)
    }
}
