//
//  UsbTrustPrompt.swift
//  Peri Display
//
//  USB로 온 미페어링 기기의 신뢰 확인 창.
//
//  왜 SwiftUI `.alert`이 아닌가: 이 앱은 켜두고 잊는 백그라운드 유틸리티라 **메인 창도
//  메뉴 막대 패널도 닫혀 있는 게 정상**이다. 그 둘에 붙인 alert은 호스트 뷰가 존재할 때만
//  뜨므로, 정작 필요한 상황(자리를 비운 사이 누가 케이블을 꽂음)에서 안 뜬다.
//
//  `NSAlert.runModal()`도 쓰지 않는다 — 메인 스레드를 막아 버려서 기기 상태 기계의 전이와
//  타이머(유예·워치독)가 사용자가 답할 때까지 통째로 멈춘다. 잠긴 Mac이면 그게 몇 분이다.
//
//  그래서 자체 `NSWindow`를 띄운다. 씬에 의존하지 않고, 논모달이라 아무것도 막지 않는다.
//

import AppKit
import SwiftUI

@MainActor
enum UsbTrustPrompt {
    private static var window: NSWindow?

    /// 확인 창을 띄운다. 이미 떠 있으면 앞으로 올리기만 한다.
    static func show(name: String, fingerprint: String, decide: @escaping (Bool) -> Void) {
        if let w = window {
            w.makeKeyAndOrderFront(nil)
            NSApp.activate(ignoringOtherApps: true)
            return
        }
        let view = UsbTrustView(name: name, fingerprint: fingerprint) { ok in
            close()
            decide(ok)
        }
        let w = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 420, height: 240),
            styleMask: [.titled, .fullSizeContentView],
            backing: .buffered, defer: false
        )
        w.title = "기기 신뢰 확인"
        w.contentViewController = NSHostingController(rootView: view)
        w.isReleasedWhenClosed = false
        w.level = .floating          // 다른 앱 위로 — 잠금 해제 직후 바로 보이게
        w.center()
        window = w
        w.makeKeyAndOrderFront(nil)
        // 잠금 중이면 이 호출은 무시되고, 풀리는 순간 창이 앞에 있다.
        NSApp.activate(ignoringOtherApps: true)
    }

    static func close() {
        window?.orderOut(nil)
        window = nil
    }
}

private struct UsbTrustView: View {
    let name: String
    let fingerprint: String
    let decide: (Bool) -> Void

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "cable.connector")
                .font(.system(size: 34))
                .foregroundStyle(.orange)

            VStack(spacing: 6) {
                Text("\(name)을(를) 신뢰할까요?").font(.headline)
                Text("USB로 연결된 기기입니다.")
                    .font(.caption).foregroundStyle(.secondary)
            }

            Text(fingerprint)
                .font(.caption.monospaced())
                .foregroundStyle(.tertiary)
                .textSelection(.enabled)

            // 대가를 분명히 말한다 — 케이블 하나로 무선 접근권까지 열린다는 걸 알아야 한다.
            Text("승인하면 이후 **Wi-Fi로도** 이 기기가 자동 연결됩니다.")
                .font(.caption)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)

            HStack(spacing: 10) {
                Button("거부") { decide(false) }
                    .keyboardShortcut(.cancelAction)
                Button("신뢰") { decide(true) }
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
            }
        }
        .padding(24)
        .frame(width: 420)
    }
}
