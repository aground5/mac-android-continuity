//
//  MainWindow.swift
//  Peri Display
//
//  메인 창 — NavigationSplitView. 왼쪽은 통합 기기 목록(연결됨/페어링됨/주변), 오른쪽은
//  선택된 기기의 상세. 일상 조작은 메뉴 막대가 담당하고, 이 창은 설정·확인·페어링용이다.
//
//  기기 축은 AppCore.devices(지문 머지) 하나 — LAN/AOA 세션을 따로 나열하지 않는다.
//

import SwiftUI

struct MainWindow: View {
    @EnvironmentObject private var core: AppCore

    var body: some View {
        MainSplit(core: core, manager: core.sessions, profiles: core.profiles)
    }
}

private struct MainSplit: View {
    @ObservedObject var core: AppCore
    @ObservedObject var manager: SessionManager
    @ObservedObject var profiles: ProfileStore
    @State private var selection: String?
    @State private var pairingPresented = false
    @State private var inspectorPresented = false

    private var devices: [DeviceModel] { core.devices }

    private var selectedDevice: DeviceModel? {
        devices.first(where: { $0.id == selection })
    }


    var body: some View {
        NavigationSplitView {
            sidebar
                .navigationSplitViewColumnWidth(min: 210, ideal: 235, max: 300)
        } detail: {
            detail
        }
        // 창 최소 크기를 NavigationSplitView에 직접 걸지 않는다 — 컬럼 min/max와 서로 물려
        // 매 패스마다 min 크기가 재계산되며 레이아웃 루프의 공범이 된다. 사이드바는
        // navigationSplitViewColumnWidth로, 디테일은 자기 minWidth로 각자 바닥을 갖는다.
        // 진단은 3층 중 "파헤치기" — 인스펙터로 밀어 넣어 일상 화면을 점유하지 않게.
        .inspector(isPresented: $inspectorPresented) {
            // 세션이 아니라 **기기**를 넘긴다 — 진단의 1층이 기기 상태 기계이고, 그건
            // 세션이 없는 동안(경로 재탐색·유예 중)에도 봐야 할 값이다.
            DiagnosticsInspector(core: core, device: selectedDevice)
                .inspectorColumnWidth(min: 260, ideal: 300, max: 380)
        }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    pairingPresented = true
                } label: {
                    Label("기기 추가", systemImage: "plus")
                }
                .help("근처의 Android 기기를 찾아 페어링")
            }
            ToolbarSpacer(.fixed, placement: .primaryAction)
            ToolbarItem(placement: .primaryAction) {
                Button {
                    inspectorPresented.toggle()
                } label: {
                    Label("진단", systemImage: "stethoscope")
                }
                .keyboardShortcut("i", modifiers: [.option, .command])
                .help("원시 수치 전체 보기 (⌥⌘I)")
            }
        }
        .sheet(isPresented: $pairingPresented) {
            PairingSheet(pairing: core.pairing)
        }
        // (USB 신뢰 확인은 여기 붙이지 않는다 — 이 창이 닫혀 있는 게 정상이라 안 뜬다.
        //  씬에 의존하지 않는 `UsbTrustPrompt`가 자체 창으로 띄운다.)
        // 메뉴 막대에서 특정 기기를 눌러 창을 연 경우 그 기기를 선택.
        .onChange(of: core.focusedDeviceID) {
            if let id = core.focusedDeviceID {
                selection = id
                core.focusedDeviceID = nil
            }
        }
        // 선택이 비었거나 사라졌으면 첫 기기를 자동 선택.
        .onChange(of: devices) {
            if selection == nil || !devices.contains(where: { $0.id == selection }) {
                selection = devices.first?.id
            }
        }
        .onAppear {
            if selection == nil { selection = devices.first?.id }
        }
    }

    // MARK: 사이드바

    @ViewBuilder
    private var sidebar: some View {
        let connected = devices.filter { $0.link == .usb || $0.link == .wifi }
        let paired = devices.filter { $0.link == .offline }
        let nearby = devices.filter { $0.link == .discovered }

        List(selection: $selection) {
            if !connected.isEmpty {
                Section("연결됨") {
                    ForEach(connected) { d in DeviceRow(device: d).tag(d.id) }
                }
            }
            if !paired.isEmpty {
                Section("페어링됨") {
                    ForEach(paired) { d in DeviceRow(device: d).tag(d.id) }
                }
            }
            if !nearby.isEmpty {
                Section("주변 기기") {
                    ForEach(nearby) { d in DeviceRow(device: d).tag(d.id) }
                }
            }
        }
        .listStyle(.sidebar)
        .overlay {
            if devices.isEmpty {
                VStack(spacing: 6) {
                    Image(systemName: "iphone.gen3.slash")
                        .font(.title2).foregroundStyle(.tertiary)
                    Text("기기 없음").font(.callout).foregroundStyle(.secondary)
                }
            }
        }
        .safeAreaInset(edge: .bottom) { serverFooter }
    }

    /// 사이드바 하단 — 이 Mac의 상태 표시(읽기 전용).
    ///
    /// 수신 on/off 토글은 없앴다. 켤지 말지는 앱이 아는 정보로 정해진다 — 후보를 모으면 켜고,
    /// 마지막 연결이 떠나고 유예가 지나면 끈다. 사용자가 눌러 봐야 다음 연결 때 어차피 다시
    /// 켜지므로, 끌 수 있다는 인상만 주고 실제로는 아무것도 보장하지 못하는 스위치였다.
    private var serverFooter: some View {
        VStack(alignment: .leading, spacing: 3) {
            Divider()
            HStack(spacing: 6) {
                Circle()
                    .fill(manager.isRunning ? Color.green : Color.secondary.opacity(0.5))
                    .frame(width: 7, height: 7)
                Text(manager.isRunning ? "수신 대기 중" : "대기")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
            }
            // 포트는 표시하지 않는다 — 유동이고, 사용자가 어디에도 입력하지 않는다(BLE 후보 교환).
            Text(manager.localIP)
                .font(.caption.monospaced())
                .foregroundStyle(.secondary)
                .textSelection(.enabled)
                .padding(.leading, 13)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.bar)
    }

    // MARK: 디테일

    @ViewBuilder
    private var detail: some View {
        if let d = devices.first(where: { $0.id == selection }) {
            switch d.link {
            case .usb, .wifi:
                if let s = d.session, let apps = d.apps {
                    SessionDetail(device: d, core: core, session: s, apps: apps,
                                  profiles: profiles, inspectorPresented: $inspectorPresented)
                } else {
                    OfflineDetail(device: d, pairing: core.pairing, core: core, pager: core.pager)
                }
            case .offline:
                OfflineDetail(device: d, pairing: core.pairing, core: core, pager: core.pager)
            case .discovered:
                DiscoveredDetail(device: d, pairing: core.pairing,
                                 pairingPresented: $pairingPresented)
            }
        } else {
            EmptyDetail(address: manager.localIP, pairingPresented: $pairingPresented)
        }
    }
}

// MARK: - 사이드바 행

private struct DeviceRow: View {
    let device: DeviceModel

    var body: some View {
        HStack(spacing: 8) {
            statusDot
            VStack(alignment: .leading, spacing: 1) {
                Text(device.name)
                    .fontWeight(.medium)
                    .lineLimit(1)
                subtitle
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            linkBadge
        }
        .padding(.vertical, 3)
    }

    @ViewBuilder
    private var statusDot: some View {
        switch device.link {
        case .usb, .wifi:
            Circle().fill(.green).frame(width: 7, height: 7)
        case .offline:
            Circle().strokeBorder(.secondary.opacity(0.6), lineWidth: 1.2)
                .frame(width: 7, height: 7)
        case .discovered:
            Image(systemName: "dot.radiowaves.left.and.right")
                .font(.caption2).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private var subtitle: some View {
        switch device.link {
        case .usb, .wifi:
            if let s = device.extend?.stats, s.active {
                Text("\(s.width)×\(s.height) · \(s.fps)fps")
            } else {
                Text(device.detailText ?? "세션 준비 중")
            }
        case .offline:
            if device.blePeer != nil {
                Text("근처에 있음 · 연결 가능").foregroundStyle(.tint)
            } else if let ms = device.lastSeenMillis {
                Text("\(Date(timeIntervalSince1970: Double(ms) / 1000), format: .relative(presentation: .named)) 연결")
            } else {
                Text("연결 안 됨")
            }
        case .discovered:
            Text("\(device.blePeer?.rssi ?? 0) dBm")
        }
    }

    @ViewBuilder
    private var linkBadge: some View {
        switch device.link {
        case .usb:
            BadgeLabel(text: "USB", systemImage: "cable.connector", tint: .orange)
        case .wifi:
            // 무선은 한 덩어리가 아니다 — LAN / 외부 Wi-Fi / LTE는 지연도 대역도 다르다.
            BadgeLabel(text: device.wireless.label,
                       systemImage: device.wireless.icon,
                       tint: device.wireless.tint)
                .help(device.wireless.detail)
        default:
            EmptyView()
        }
    }
}

/// 전송 배지 — 전송은 기기의 속성이므로 행을 나누지 않고 배지로만 표시.
struct BadgeLabel: View {
    let text: String
    let systemImage: String
    let tint: Color

    var body: some View {
        Label(text, systemImage: systemImage)
            .font(.caption2.weight(.semibold))
            .labelStyle(.titleAndIcon)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(tint.opacity(0.15), in: Capsule())
            .foregroundStyle(tint)
    }
}

// MARK: - 디테일: 페어링됨(오프라인)

private struct OfflineDetail: View {
    let device: DeviceModel
    let pairing: PairingCoordinator
    @ObservedObject var core: AppCore
    @ObservedObject var pager: PeriPageAdvertiser
    @State private var confirmingRevoke = false

    /// 지금 **이 기기**를 부르고 있는가. 다른 기기를 부르는 중이면 여기선 평소 버튼이 뜬다.
    private var paging: Bool { pager.paging == device.id }

    var body: some View {
        VStack(spacing: 20) {
            IconBadge(systemImage: "iphone.gen3", tint: device.blePeer != nil ? .accentColor : .secondary)
            VStack(spacing: 6) {
                Text(device.name).font(.title2.weight(.semibold))
                if device.blePeer != nil {
                    Label("근처에 있음", systemImage: "dot.radiowaves.left.and.right")
                        .font(.caption.weight(.medium))
                        .foregroundStyle(.tint)
                } else if let ms = device.lastSeenMillis {
                    Text("마지막 연결 \(Date(timeIntervalSince1970: Double(ms) / 1000), format: .relative(presentation: .named))")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Text(paging
                     ? "기기를 깨우는 중입니다. 잠금 상태여도 응답합니다."
                     : device.blePeer != nil
                     ? "연결하면 Mac이 접속 정보를 보내 Wi-Fi로 이어집니다."
                     : "연결을 누르면 이 기기를 깨웁니다. USB 케이블을 꽂아도 됩니다.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 320)
                    .padding(.top, 2)
            }
            // ★ **버튼은 항상 있다.**
            //
            // 예전엔 `blePeer`(= 지금 광고 중)일 때만 띄웠다. 폰이 상시 광고를 그만두면서 그
            // 신호가 사라졌으므로, 그대로 두면 페어링해 둔 기기에 영영 연결할 수 없다.
            // 근처에 있는지 모르는 채로 누르는 게 정상이고, 그때 Mac이 호출 광고를 낸다.
            if paging {
                VStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Button("취소") { core.cancelPage() }
                        .controlSize(.large)
                }
            } else {
                VStack(spacing: 8) {
                    Button("연결") { core.connect(device) }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                    if let problem = pager.problem {
                        Text(problem)
                            .font(.caption)
                            .foregroundStyle(.orange)
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: 300)
                    }
                }
            }

            VStack(spacing: 10) {
                Divider().frame(width: 220)
                Text("지문 \(device.id)")
                    .font(.caption2.monospaced())
                    .foregroundStyle(.tertiary)
                    .textSelection(.enabled)
                Button("페어링 해제…", role: .destructive) { confirmingRevoke = true }
                    .controlSize(.small)
            }
            .padding(.top, 4)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .recessedBackground()
        .navigationTitle(device.name)
        .confirmationDialog("\(device.name) 페어링을 해제할까요?",
                            isPresented: $confirmingRevoke, titleVisibility: .visible) {
            Button("페어링 해제", role: .destructive) { revoke() }
        } message: {
            Text("이 기기는 다시 PIN 페어링을 해야 연결할 수 있습니다.")
        }
    }

    private func revoke() { core.revoke(fingerprint: device.id) }
}

// MARK: - 디테일: BLE 발견(미페어링)

private struct DiscoveredDetail: View {
    let device: DeviceModel
    let pairing: PairingCoordinator
    @Binding var pairingPresented: Bool

    var body: some View {
        VStack(spacing: 20) {
            IconBadge(systemImage: "dot.radiowaves.left.and.right", tint: .blue)
            VStack(spacing: 6) {
                Text(device.name).font(.title2.weight(.semibold))
                Text("근처에서 발견됨 · \(device.blePeer?.rssi ?? 0) dBm")
                    .font(.caption).foregroundStyle(.secondary)
                Text("아직 이 Mac과 페어링되지 않았습니다. PIN 코드로 한 번만 확인하면\n다음부터는 자동으로 연결됩니다.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 320)
                    .padding(.top, 2)
            }
            Button("페어링…") {
                if let peer = device.blePeer { pairing.connect(peer) }
                pairingPresented = true
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .recessedBackground()
        .navigationTitle(device.name)
    }
}

// MARK: - 디테일: 빈 상태

private struct EmptyDetail: View {
    let address: String
    @Binding var pairingPresented: Bool

    var body: some View {
        VStack(spacing: 20) {
            IconBadge(systemImage: "rectangle.on.rectangle.angled", tint: .secondary, size: 68)
            VStack(spacing: 6) {
                Text("연결된 기기 없음").font(.title2.weight(.semibold))
                Text("Android 기기를 USB로 연결하거나,\n같은 Wi-Fi에서 Peri Display 앱을 여세요.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            Text(address)
                .font(.callout.monospaced())
                .foregroundStyle(.secondary)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(.quaternary.opacity(0.4), in: Capsule())
                .textSelection(.enabled)
            Button {
                pairingPresented = true
            } label: {
                Label("기기 추가", systemImage: "plus")
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .padding(.top, 4)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .recessedBackground()
    }
}
