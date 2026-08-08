//
//  Peri_DisplayApp.swift
//  Peri Display
//
//  씬 셋: 메인 창(설정·진단·페어링) + 메뉴 막대(일상 동선) + 설정(⌘,).
//  상태는 AppCore가 단일 소유 — 창이 닫혀도 서비스(BLE·USB 감시·LAN 서버)는 계속 돈다.
//

import SwiftUI

@main
struct Peri_DisplayApp: App {
    @StateObject private var core = AppCore()

    var body: some Scene {
        // 메인 창 — 단일 인스턴스(Window), 크기 조절·위치 복원은 시스템이 처리.
        Window("Peri Display", id: "main") {
            MainWindow()
                .environmentObject(core)
        }
        .defaultSize(width: 1040, height: 680)

        // 메뉴 막대 — 아이콘이 곧 상태: 연결 0대면 외곽선, N대면 채움 + 숫자.
        MenuBarExtra {
            MenuBarPanel()
                .environmentObject(core)
        } label: {
            let n = core.devices.filter { $0.link == .usb || $0.link == .wifi }.count
            if n > 0 {
                Image(systemName: "rectangle.on.rectangle.fill")
                Text("\(n)")
            } else {
                Image(systemName: "rectangle.on.rectangle")
            }
        }
        .menuBarExtraStyle(.window)

        Settings {
            SettingsWindow()
                .environmentObject(core)
        }
    }
}
