//
//  MenuBarPanel.swift
//  Peri Display
//
//  메뉴 막대 패널 — 일상 동선의 90%가 여기서 끝난다: 연결 상태 확인, 프로파일 전환, 창 열기.
//  이 앱은 켜두고 잊는 백그라운드 유틸리티라 AirPlay·Sidecar처럼 메뉴 막대가 1차 표면이다.
//
//  기기 행에 연결 스위치는 두지 않는다 — 현재 프로토콜엔 Mac→Android "세션 종료·재접속 금지"
//  수단이 없어 teardown해도 즉시 재접속된다. 되는 것만 보여준다(상태 + 창/설정 진입).
//

import SwiftUI

struct MenuBarPanel: View {
    @EnvironmentObject private var core: AppCore

    var body: some View {
        MenuBarContent(core: core, profiles: core.profiles)
    }
}

private struct MenuBarContent: View {
    @ObservedObject var core: AppCore
    @ObservedObject var profiles: ProfileStore
    @Environment(\.openWindow) private var openWindow
    @Environment(\.openSettings) private var openSettings

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            let visible = core.devices.filter { $0.link != .discovered }
            if visible.isEmpty {
                emptyRow
            } else {
                ForEach(visible) { d in
                    DevicePanelRow(
                        device: d,
                        onOpen: { open(deviceID: d.id) },
                        onToggle: { on in
                            if on { core.connect(d) } else { Task { await core.disconnect(d) } }
                        }
                    )
                }
            }

            Divider().padding(.vertical, 5)

            HStack {
                Text("재생 모드").font(.caption).foregroundStyle(.secondary)
                Spacer()
                Picker("", selection: $profiles.profile) {
                    Text("저지연").tag(StreamProfile.lowLatency)
                    Text("고화질").tag(StreamProfile.highQuality)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .fixedSize()
            }
            .padding(.horizontal, 8)

            Divider().padding(.vertical, 5)

            PanelButton(title: "Peri Display 열기", systemImage: "macwindow") {
                open(deviceID: nil)
            }
            .keyboardShortcut("0", modifiers: .command)
            PanelButton(title: "설정…", systemImage: "gearshape") {
                openSettings()
                NSApp.activate(ignoringOtherApps: true)
            }
            .keyboardShortcut(",", modifiers: .command)
            PanelButton(title: "Peri Display 종료", systemImage: "power") {
                NSApp.terminate(nil)
            }
            .keyboardShortcut("q", modifiers: .command)
        }
        .padding(8)
        .frame(width: 280)
    }

    private var emptyRow: some View {
        VStack(spacing: 4) {
            Text("연결된 기기 없음").font(.callout)
            Text("USB를 꽂거나 Android 앱을 여세요")
                .font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
    }

    private func open(deviceID: String?) {
        core.focusedDeviceID = deviceID
        openWindow(id: "main")
        NSApp.activate(ignoringOtherApps: true)
    }
}

/// 기기 1행 — 이름을 누르면 메인 창에서 열고, 스위치로 연결/해제한다.
///
/// 스위치는 실제로 동작할 때만 켤 수 있다: 끄기는 늘 가능(Bye 전송), 켜기는 그 기기가 BLE로
/// 보일 때만 가능하다(그때만 Mac이 접속을 개시할 수단이 있다). 눌러도 아무 일 없는 스위치를
/// 두느니 비활성으로 이유를 알리는 게 낫다.
private struct DevicePanelRow: View {
    let device: DeviceModel
    let onOpen: () -> Void
    let onToggle: (Bool) -> Void
    @State private var hovering = false

    private var connected: Bool { device.link == .usb || device.link == .wifi }
    private var canConnect: Bool { device.blePeer != nil }

    var body: some View {
        HStack(spacing: 8) {
            Button(action: onOpen) {
                HStack(spacing: 8) {
                    Circle()
                        .fill(connected ? Color.green : Color.secondary.opacity(0.4))
                        .frame(width: 7, height: 7)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(device.name)
                            .fontWeight(.medium)
                            .foregroundStyle(connected ? .primary : .secondary)
                        Text(subtitle)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 4)
                    if device.link == .usb {
                        BadgeLabel(text: "USB", systemImage: "cable.connector", tint: .orange)
                    } else if device.link == .wifi {
                        BadgeLabel(text: device.wireless.label,
                                   systemImage: device.wireless.icon,
                                   tint: device.wireless.tint)
                            .help(device.wireless.detail)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Toggle("연결", isOn: Binding(get: { connected }, set: onToggle))
                .toggleStyle(.switch)
                .controlSize(.mini)
                .labelsHidden()
                .disabled(!connected && !canConnect)
                .help(connected ? "연결 해제"
                      : (canConnect ? "연결" : "기기가 근처에 없습니다"))
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .background(hovering ? Color.accentColor.opacity(0.12) : .clear,
                    in: RoundedRectangle(cornerRadius: 7))
        .onHover { hovering = $0 }
    }

    private var subtitle: String {
        if let s = device.extend?.stats, s.active {
            return "\(s.width)×\(s.height) · \(s.fps)fps"
        }
        if connected { return device.detailText ?? "세션 준비 중" }
        return canConnect ? "근처에 있음" : "연결 안 됨"
    }
}

/// 패널 하단 액션 버튼 — 메뉴 항목처럼 hover 하이라이트.
private struct PanelButton: View {
    let title: String
    let systemImage: String
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: systemImage)
                    .frame(width: 16)
                    .foregroundStyle(.secondary)
                Text(title)
                Spacer()
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .background(hovering ? Color.accentColor.opacity(0.12) : .clear,
                        in: RoundedRectangle(cornerRadius: 7))
            .contentShape(RoundedRectangle(cornerRadius: 7))
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
    }
}
