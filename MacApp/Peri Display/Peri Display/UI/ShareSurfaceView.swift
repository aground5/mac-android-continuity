//
//  ShareSurfaceView.swift
//  Peri Display
//
//  공유 창의 알맹이 — 기기 화면을 그리고, 마우스·키보드를 그 기기로 넘긴다.
//
//  좌표
//  ────
//  디지타이저는 정규화 좌표(0…1, **좌상단 원점**)를 받는다. AppKit은 좌하단 원점이라
//  y를 뒤집는다. 그리고 창은 종횡비가 못 박혀 있어 레이어가 뷰를 꽉 채우므로, 뷰 좌표를
//  그대로 정규화하면 된다 — 레터박스 보정이 필요 없다.
//
//  왜 마우스가 '터치'인가
//  ─────────────────────
//  디지타이저는 **절대 좌표** 장치다. 마우스 커서가 있는 곳이 곧 손가락이 닿는 곳이라
//  1:1로 옮겨진다. 상대 좌표 장치(마우스 HID)로 보내면 Android가 커서를 그리고 포인터
//  가속을 먹여서, 보고 있는 화면과 닿는 자리가 어긋난다.
//

import AppKit
import Foundation

@MainActor
final class ShareSurfaceView: NSView {

    private let renderer: ShareRenderer
    /// 입력을 넣을 통로. nil = 케이블이 없다(Wi-Fi 연결) → 안내를 띄운다.
    var inputs: DeviceInputs? {
        didSet {
            if inputs != nil, oldValue == nil { Task { await inputs?.register() } }
            needsDisplay = true
        }
    }
    /// 지금 창이 **실물 크기**인가. 축소됐으면 그렇게 표시해 준다.
    var showsRuler = true { didSet { needsDisplay = true } }

    init(renderer: ShareRenderer) {
        self.renderer = renderer
        super.init(frame: .zero)
        wantsLayer = true
        layer?.backgroundColor = NSColor.black.cgColor
        layer?.addSublayer(renderer.layer)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    override var isFlipped: Bool { true }
    override var acceptsFirstResponder: Bool { true }
    /// 창을 클릭해 활성화하는 그 클릭도 **터치로 넘긴다.** 안 그러면 창을 쓸 때마다
    /// 첫 탭이 항상 먹히지 않는다.
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override func layout() {
        super.layout()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        renderer.layer.frame = bounds
        CATransaction.commit()
    }

    // MARK: - 안내

    override func draw(_ dirtyRect: NSRect) {
        super.draw(dirtyRect)
        guard inputs == nil else { return }
        // 영상 위에 얇게 — 화면은 보이되 "만질 수는 없다"를 알린다.
        let text = "입력하려면 USB 케이블을 연결하세요"
        let attrs: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: 11, weight: .medium),
            .foregroundColor: NSColor.white.withAlphaComponent(0.85),
        ]
        let size = (text as NSString).size(withAttributes: attrs)
        let pad: CGFloat = 8
        let box = NSRect(x: (bounds.width - size.width) / 2 - pad,
                         y: bounds.height - size.height - pad * 3,
                         width: size.width + pad * 2, height: size.height + pad)
        NSColor.black.withAlphaComponent(0.6).setFill()
        NSBezierPath(roundedRect: box, xRadius: 6, yRadius: 6).fill()
        (text as NSString).draw(at: NSPoint(x: box.minX + pad, y: box.minY + pad / 2),
                                withAttributes: attrs)
    }

    // MARK: - 포인터 → 디지타이저

    private func normalized(_ event: NSEvent) -> CGPoint {
        let p = convert(event.locationInWindow, from: nil)
        guard bounds.width > 0, bounds.height > 0 else { return .zero }
        // `isFlipped`가 true라 뷰 좌표가 이미 좌상단 원점이다.
        return CGPoint(x: max(0, min(1, p.x / bounds.width)),
                       y: max(0, min(1, p.y / bounds.height)))
    }

    override func mouseDown(with event: NSEvent) {
        scroll.cancel()
        inputs?.touch.set(contact: 0, down: true, at: normalized(event))
    }

    override func mouseDragged(with event: NSEvent) {
        inputs?.touch.set(contact: 0, down: true, at: normalized(event))
    }

    override func mouseUp(with event: NSEvent) {
        inputs?.touch.set(contact: 0, down: false, at: normalized(event))
    }

    /// 스크롤 → **손가락 하나로 미는 동작**.
    ///
    /// 디지타이저에는 스크롤이 없다. 그래서 휠을 손가락 하나의 드래그로 옮긴다:
    /// 첫 이벤트에 커서 자리에 손가락을 내리고, 이후 이벤트마다 그만큼 끌고, 잠깐 멎으면 뗀다.
    /// 관성 스크롤도 같은 경로를 타므로 자연스럽게 이어진다.
    override func scrollWheel(with event: NSEvent) {
        guard let touch = inputs?.touch, bounds.height > 0 else { return }
        let start = normalized(event)
        scroll.feed(dy: event.scrollingDeltaY / bounds.height,
                    dx: event.scrollingDeltaX / bounds.width,
                    origin: start, touch: touch)
    }

    private lazy var scroll = ScrollDragger()

    // MARK: - 키보드 → HID

    override func keyDown(with event: NSEvent) {
        guard let kb = inputs?.keyboard else { super.keyDown(with: event); return }
        // `isARepeat`도 그대로 넘긴다 — Android가 자기 반복 속도로 처리하게.
        kb.set(virtualKey: event.keyCode, down: true)
    }

    override func keyUp(with event: NSEvent) {
        guard let kb = inputs?.keyboard else { super.keyUp(with: event); return }
        kb.set(virtualKey: event.keyCode, down: false)
    }

    override func flagsChanged(with event: NSEvent) {
        inputs?.keyboard.setModifiers(event.modifierFlags)
    }

    /**
     ⌘ 조합을 기기로 넘긴다.

     안 가로채면 macOS 메뉴가 먼저 먹는다 — ⌘A는 '전체 선택'이 아니라 아무 데도 안 가고,
     ⌘C는 Mac의 클립보드로 간다. 우리는 ⌘를 Ctrl로 보내므로(→ `KeyboardInjector` 주석)
     Android에서 복사·붙여넣기·전체선택이 손에 익은 대로 동작해야 한다.

     ★ **탈출구는 남긴다.** ⌘Q(종료)·⌘W(닫기)·⌘M(최소화)·⌘H(가리기)·⌘,(설정)는 macOS에
     그대로 넘긴다. 전부 삼키면 창에 갇혀서 앱을 끌 방법이 없어진다.
     */
    override func performKeyEquivalent(with event: NSEvent) -> Bool {
        guard let kb = inputs?.keyboard,
              event.modifierFlags.contains(.command),
              !Self.escapeHatch.contains(event.keyCode)
        else { return super.performKeyEquivalent(with: event) }

        kb.setModifiers(event.modifierFlags)
        kb.set(virtualKey: event.keyCode, down: true)
        // ⌘ 조합은 keyUp이 안 오는 경우가 있다(메뉴가 먹은 것으로 처리되는 경로).
        // 눌린 채로 남지 않게 여기서 짝을 맞춘다.
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(30))
            kb.set(virtualKey: event.keyCode, down: false)
        }
        return true
    }

    /// macOS에 양보하는 ⌘ 조합 — 이게 없으면 창에서 빠져나올 수 없다.
    /// Q(0x0C) W(0x0D) M(0x2E) H(0x04) ,(0x2B)  ※ 값은 kVK_ANSI_*
    private static let escapeHatch: Set<UInt16> = [0x0C, 0x0D, 0x2E, 0x04, 0x2B]

    /// 창이 포커스를 잃거나 닫힐 때 — 눌린 것을 전부 뗀다.
    func releaseAllInput() {
        scroll.cancel()
        inputs?.keyboard.releaseAll()
        inputs?.touch.releaseAll()
    }
}

/// 휠 이벤트를 손가락 드래그로 옮기는 작은 상태기.
///
/// 휠은 이산 사건이고 터치는 연속 동작이라 그 사이를 메워야 한다. 멎는 순간을 타이머로
/// 잡아 손가락을 떼는데, 그 지연이 너무 짧으면 관성 스크롤 중간에 손가락이 떨어지고
/// 너무 길면 다음 조작이 드래그로 이어져 버린다.
@MainActor
private final class ScrollDragger {
    private var down = false
    private var at = CGPoint(x: 0.5, y: 0.5)
    private var liftTask: Task<Void, Never>?
    private weak var touch: TouchInjector?

    /// 마지막 휠 이벤트 뒤 이만큼 조용하면 손가락을 뗀다.
    private static let liftDelayMs: UInt64 = 90

    func feed(dy: CGFloat, dx: CGFloat, origin: CGPoint, touch: TouchInjector) {
        self.touch = touch
        if !down {
            down = true
            at = origin
            touch.set(contact: 0, down: true, at: at)
        }
        // 휠을 아래로 굴리면(deltaY<0) 화면은 위로 밀린다 — 손가락도 위로 간다.
        at = CGPoint(x: max(0, min(1, at.x + dx)),
                     y: max(0, min(1, at.y + dy)))
        touch.set(contact: 0, down: true, at: at)

        liftTask?.cancel()
        liftTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(Self.liftDelayMs))
            guard !Task.isCancelled else { return }
            self?.lift()
        }
    }

    func cancel() {
        liftTask?.cancel(); liftTask = nil
        lift()
    }

    private func lift() {
        guard down else { return }
        down = false
        touch?.set(contact: 0, down: false, at: at)
    }
}
