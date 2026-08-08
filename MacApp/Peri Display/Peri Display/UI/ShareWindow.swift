//
//  ShareWindow.swift
//  Peri Display
//
//  **화면 공유 전용 창 — 실물 크기로.**
//
//  왜 별도 창인가: 이건 "저 기기를 지금 보고 만지는" 창이다. 설정·진단이 있는 메인 창 안에
//  끼워 두면 크기가 그 창에 매이고, 실물 크기라는 말이 의미를 잃는다. 창이 따로 있어야
//  옆에 두고 쓸 수 있다.
//
//  실물 크기를 어떻게 맞추나
//  ────────────────────────
//  기기가 화면의 물리 치수(µm)를 보내 준다(`ShareParams`). Mac 쪽은 그걸 **포인트**로
//  바꿔야 하는데, 그 환산율은 디스플레이마다 다르다:
//
//      포인트/인치 = 화면 폭(pt) / (CGDisplayScreenSize 폭(mm) / 25.4)
//
//  이 MacBook에서는 127.5 pt/inch다(실측). 즉 폭 70mm짜리 폰은 351pt 창이 된다.
//  72를 쓰면 안 된다 — 그건 PDF의 관례이고 실제 화면 밀도와는 관계가 없다.
//
//  창을 옮기면 환산율이 바뀔 수 있으므로(외장 모니터), 화면이 바뀌면 다시 맞춘다.
//

import AppKit
import Foundation
import OSLog

private nonisolated let log = Logger(subsystem: "com.peri.display.mac", category: "sharewin")

// MARK: - 실물 크기 환산

enum PhysicalScale {
    /// 이 화면의 **포인트/인치**. 못 구하면 nil — 그때는 실물 크기를 포기하고 적당히 띄운다.
    static func pointsPerInch(of screen: NSScreen?) -> Double? {
        guard let screen,
              let num = screen.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")]
                  as? CGDirectDisplayID
        else { return nil }
        let mm = CGDisplayScreenSize(num)          // 밀리미터
        guard mm.width > 1 else { return nil }     // 0 = EDID가 물리 크기를 안 준다
        return Double(screen.frame.width) / (Double(mm.width) / 25.4)
    }

    /// 물리 치수(µm) → 포인트. 환산율을 못 구하면 nil.
    static func points(fromMicrometers um: Int, on screen: NSScreen?) -> Double? {
        guard um > 0, let ppi = pointsPerInch(of: screen) else { return nil }
        return Double(um) / 25_400.0 * ppi
    }
}

// MARK: - 창

@MainActor
final class ShareWindowController: NSObject, NSWindowDelegate {

    private let title: String
    private let renderer: ShareRenderer
    /// 사용자가 창을 닫았다 — 앱을 내리라는 뜻이다(창이 곧 앱이다).
    private let onClose: () -> Void

    private var window: NSWindow?
    private var surface: ShareSurfaceView?

    /// 기기 화면의 물리 크기(µm). 0이면 모른다 — 그때는 영상 비율로만 띄운다.
    private var physical = (w: 0, h: 0)
    /// 영상 프레임 크기(px) — 종횡비의 출처.
    private var video = CGSize(width: 1080, height: 2340)

    init(title: String, renderer: ShareRenderer, onClose: @escaping () -> Void) {
        self.title = title
        self.renderer = renderer
        self.onClose = onClose
    }

    // MARK: 수명

    func show() {
        if let window {
            window.makeKeyAndOrderFront(nil)
            return
        }
        let surface = ShareSurfaceView(renderer: renderer)
        let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 360, height: 780),
                         styleMask: [.titled, .closable, .miniaturizable, .resizable],
                         backing: .buffered, defer: false)
        w.title = title
        w.contentView = surface
        w.delegate = self
        w.isReleasedWhenClosed = false
        w.backgroundColor = .black
        // 창을 여러 개 띄우면 기기별로 자리를 기억한다 — 두 대를 나란히 두고 쓰는 게 자연스럽다.
        w.setFrameAutosaveName("share-\(title)")
        w.center()
        self.window = w
        self.surface = surface
        applySizing()
        w.makeKeyAndOrderFront(nil)
        // 키보드 입력을 받으려면 first responder여야 한다.
        w.makeFirstResponder(surface)
    }

    func close() {
        surface?.releaseAllInput()
        window?.delegate = nil
        window?.close()
        window = nil
        surface = nil
    }

    /// 기기가 제원을 알려 왔다 — 크기를 다시 맞춘다.
    func apply(video: CGSize, physicalWidthUm: Int, physicalHeightUm: Int) {
        self.video = video
        self.physical = (physicalWidthUm, physicalHeightUm)
        applySizing()
    }

    /// 입력을 어디로 보낼지. nil이면 창이 "케이블을 연결하세요"를 띄운다.
    func setInputs(_ inputs: DeviceInputs?) {
        surface?.inputs = inputs
    }

    // MARK: 크기

    private func applySizing() {
        guard let window, let surface else { return }
        let aspect = video.width > 0 && video.height > 0 ? video.width / video.height : 9.0 / 19.5

        // 종횡비를 창에 못 박는다 — 크기를 바꿔도 영상이 늘어나지 않는다.
        window.contentAspectRatio = NSSize(width: aspect, height: 1)

        guard let wPt = PhysicalScale.points(fromMicrometers: physical.w, on: window.screen ?? NSScreen.main)
        else {
            log.info("실물 크기 미상 — 비율만 맞춘다")
            surface.showsRuler = false
            return
        }
        let hPt = wPt / aspect

        // 화면보다 큰 폰은 없지만, 창 장식과 메뉴 막대를 빼면 세로가 모자랄 수 있다.
        // 그때는 **비율을 지키며** 줄인다 — 잘라 내면 실물 크기라는 말이 무의미해진다.
        let visible = (window.screen ?? NSScreen.main)?.visibleFrame.size
            ?? NSSize(width: 1440, height: 900)
        let maxH = Double(visible.height) - 80
        var contentW = wPt, contentH = hPt
        var exact = true
        if contentH > maxH {
            contentH = maxH
            contentW = maxH * aspect
            exact = false
        }
        surface.showsRuler = exact

        window.setContentSize(NSSize(width: contentW.rounded(), height: contentH.rounded()))
        log.notice("공유 창 \(Int(contentW))x\(Int(contentH))pt (실물 \(self.physical.w / 1000)x\(self.physical.h / 1000)mm, \(exact ? "실물 크기" : "축소"))")
    }

    // MARK: NSWindowDelegate

    func windowWillClose(_ notification: Notification) {
        surface?.releaseAllInput()
        window = nil
        surface = nil
        onClose()
    }

    /// 다른 디스플레이로 옮기면 포인트/인치가 달라진다 → 실물 크기를 다시 맞춘다.
    func windowDidChangeScreen(_ notification: Notification) {
        applySizing()
    }

    func windowDidResignKey(_ notification: Notification) {
        // 포커스를 잃었는데 키가 눌린 채로 남으면 Android에서 자동 반복이 돈다.
        surface?.releaseAllInput()
    }
}
