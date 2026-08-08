//
//  SettingsWindow.swift
//  Peri Display
//
//  ⌘, 설정 창. 두 탭:
//   - 스트리밍: 기본 재생 프로파일 + 전송별 상한(현재는 코드 상수 — 정보 표시).
//   - 기기: 페어링된 기기(TrustStore) 목록·해제. 전엔 trust.json을 손으로 지워야 했다.
//

import SwiftUI

struct SettingsWindow: View {
    @EnvironmentObject private var core: AppCore

    var body: some View {
        TabView {
            StreamingPane(profiles: core.profiles)
                .tabItem { Label("스트리밍", systemImage: "dial.medium") }
            DevicesPane(core: core)
                .tabItem { Label("기기", systemImage: "iphone.gen3") }
            NetworkPane()
                .tabItem { Label("네트워크", systemImage: "network") }
        }
        .frame(width: 480)
    }
}

// MARK: - 스트리밍

private struct StreamingPane: View {
    @ObservedObject var profiles: ProfileStore

    var body: some View {
        Form {
            Picker("기본 재생 모드", selection: $profiles.profile) {
                Text("저지연").tag(StreamProfile.lowLatency)
                Text("고화질").tag(StreamProfile.highQuality)
            }
            .pickerStyle(.segmented)
            Text(profiles.profile == .lowLatency
                 ? "지터버퍼 없이 즉시 렌더하고, 인코더가 포화되면 fps를 자동으로 낮춥니다."
                 : "고정 지터버퍼로 부드러움을 극대화합니다. 지연이 늘어납니다.")
                .font(.caption)
                .foregroundStyle(.secondary)

            Section("전송별 상한") {
                LabeledContent("USB (AOA)") {
                    Text("120 fps · 40 Mbps").monospacedDigit()
                }
                LabeledContent("Wi-Fi (LAN)") {
                    Text("60 fps · 25 Mbps").monospacedDigit()
                }
                Text("USB는 혼잡·손실이 없어 처음부터 상한으로 시작합니다. Wi-Fi는 손실 피드백을 보며 올라갑니다.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .padding(.vertical, 4)
    }
}

// MARK: - 네트워크 (STUN)

/// STUN 서버 목록. 다른 망에 있는 기기와 P2P로 붙을 때 공인 주소를 알아내는 데 쓴다.
private struct NetworkPane: View {
    @State private var text = ""
    @State private var probing = false
    @State private var result: String?
    @State private var fastUdp = DatagramSettings.fastUdp

    var body: some View {
        Form {
            Section("데이터그램 경로") {
                Toggle("접속할 때 UDP도 미리 뚫기", isOn: $fastUdp)
                    .onChange(of: fastUdp) { _, on in DatagramSettings.fastUdp = on }
                Text("평소에는 세션을 TCP만으로 세우고, 화면을 나르는 앱이 뜰 때 UDP를 엽니다. NAT의 UDP 구멍은 금방 닫혀서 붙들고 있으려면 계속 두드려야 하는데, TCP 구멍은 훨씬 오래 살기 때문입니다.")
                    .font(.caption).foregroundStyle(.secondary)
                Text("켜면 접속하는 순간 UDP까지 함께 뚫어 둡니다. 붙자마자 화면 앱이 바로 떠야 할 때 — 지난번에 켜 둔 앱이 그런 앱이거나, 망이 바뀌어 다시 붙는 중일 때 — 첫 화면까지 왕복 한 번을 아낍니다. 앱이 다 내려가면 구멍은 어느 쪽이든 닫힙니다.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("STUN 서버") {
                TextEditor(text: $text)
                    .font(.callout.monospaced())
                    .frame(height: 96)
                Text("한 줄에 하나씩 `호스트:포트` 형식. 뒤에 `tcp`를 붙이면 TCP 후보에도 씁니다.")
                    .font(.caption).foregroundStyle(.secondary)
                // TCP 후보를 못 얻으면 제어 채널이 NAT를 못 넘는다 — 왜 tcp 표시가 필요한지 알린다.
                Text("공개 STUN 다수(Google·Cloudflare)는 UDP만 응답합니다. `tcp` 서버가 하나도 없으면 다른 망에서는 연결되지 않습니다.")
                    .font(.caption).foregroundStyle(.secondary)

                HStack {
                    Button("기본값으로") {
                        StunSettings.reset()
                        load()
                        result = nil
                    }
                    Button("저장") { save() }
                    Spacer()
                    Button(probing ? "확인 중…" : "연결 확인") { probe() }
                        .disabled(probing)
                }
                if let result {
                    Text(result)
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                }
            }
        }
        .formStyle(.grouped)
        .padding(.vertical, 4)
        .onAppear(perform: load)
    }

    private func load() {
        text = StunSettings.servers.map(StunSettings.line(for:)).joined(separator: "\n")
    }

    private func save() {
        let parsed = text.split(separator: "\n").compactMap { StunSettings.parse(line: String($0)) }
        StunSettings.servers = parsed
        load()   // 정규화된 형태로 되돌려 보여 준다(빈 입력이면 기본값이 복원된다)
    }

    /// 실제로 Binding 요청을 보내 공인 주소가 오는지 확인 — 설정이 맞는지 눈으로 볼 수 있게.
    private func probe() {
        save()
        probing = true
        result = nil
        let servers = StunSettings.servers
        Task.detached(priority: .userInitiated) {
            var lines: [String] = []
            // 미디어 소켓과 무관한 임시 소켓이라 여기서 얻은 매핑은 진단용이다(펀칭엔 못 씀).
            if let probe = try? UdpTransport(port: 0) {
                for s in servers {
                    if let ep = try? StunClient.query(udpSocket: probe.fd, server: s) {
                        lines.append("\(s.display) → \(ep)")
                    } else {
                        lines.append("\(s.display) → 응답 없음")
                    }
                }
                probe.close()
            }
            let text = lines.isEmpty ? "확인할 서버가 없습니다" : lines.joined(separator: "\n")
            await MainActor.run {
                result = text
                probing = false
            }
        }
    }
}

// MARK: - 기기 (TrustStore)

private struct DevicesPane: View {
    @ObservedObject var core: AppCore
    @State private var peers: [TrustedPeer] = []
    @State private var revoking: TrustedPeer?

    var body: some View {
        Form {
            if peers.isEmpty {
                Text("페어링된 기기가 없습니다.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(peers, id: \.pubBase64) { peer in
                    HStack(spacing: 10) {
                        Image(systemName: "iphone.gen3")
                            .foregroundStyle(connected(peer) ? Color.accentColor : Color.secondary)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(peer.name).fontWeight(.medium)
                            Text(peer.fingerprint)
                                .font(.caption2.monospaced())
                                .foregroundStyle(.tertiary)
                        }
                        Spacer()
                        if connected(peer) {
                            Text("연결됨")
                                .font(.caption)
                                .foregroundStyle(.green)
                        } else {
                            Text("마지막 연결 \(Date(timeIntervalSince1970: Double(peer.lastSeenMillis) / 1000), format: .relative(presentation: .named))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Button("해제…") { revoking = peer }
                            .controlSize(.small)
                    }
                }
            }
        }
        .formStyle(.grouped)
        .padding(.vertical, 4)
        .onAppear(perform: reload)
        .confirmationDialog("\(revoking?.name ?? "기기") 페어링을 해제할까요?",
                            isPresented: Binding(get: { revoking != nil },
                                                 set: { if !$0 { revoking = nil } }),
                            titleVisibility: .visible) {
            Button("페어링 해제", role: .destructive) {
                // TrustStore를 직접 건드리지 않는다 — 해제는 세션 종료·목록 갱신까지가
                // 한 동작이고, 그 전체를 아는 건 AppCore뿐이다.
                if let p = revoking { core.revoke(fingerprint: p.fingerprint) }
                revoking = nil
                reload()
            }
        } message: {
            Text("이 기기는 다시 PIN 페어링을 해야 연결할 수 있습니다.")
        }
    }

    private func reload() { peers = TrustStore.shared.all() }

    private func connected(_ peer: TrustedPeer) -> Bool {
        core.devices.contains { $0.id == peer.fingerprint && ($0.link == .usb || $0.link == .wifi) }
    }
}
