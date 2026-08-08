//
//  DiagnosticsInspector.swift
//  Peri Display
//
//  진단 인스펙터(⌥⌘I) — 정보 위계의 3층 "파헤치기".
//
//  이 화면의 내용이 곧 예전 메인 UI였다. 개발 중엔 필수지만 평소엔 소음이라 여기로 옮겼다.
//
//  구성 원칙: **위에서 아래로 추상이 낮아진다.**
//
//   1. 기기 상태 기계 — 지금 이 기기가 어느 상태인가. 화면의 모든 문구가 여기서 파생된다.
//   2. 영상 / 비트레이트 / 지연 — 송출이 서 있을 때의 원시 수치.
//   3. 링크 · 신원 — 이 세션이 무엇을 타고 있고 누구인가.
//   4. 호스트 — 이 Mac 자신. 기기와 무관하게 전역인 것들(SCK 사이클, ICE 라운드, 수신 상태).
//
//  1번이 맨 위인 이유: 증상을 볼 때 제일 먼저 알아야 할 게 "기계는 지금 뭐라고 믿고 있나"다.
//  실제로 겪은 정지 버그가 정확히 그 어긋남이었다 — 기계는 `송출 중`, 실물 캡처는 죽어 있었다.
//  그래서 상태와 함께 **무장된 시계**도 보여준다. 상태와 시계가 안 맞으면 경고를 띄운다.
//

import SwiftUI

struct DiagnosticsInspector: View {
    @ObservedObject var core: AppCore
    let device: DeviceModel?

    private var manager: SessionManager { core.sessions }

    /// 지문이 확정된 기기만 등록부에 있다 — 핸드셰이크 전 임시 행(`s:<uuid>`)은 기계가 없다.
    private var snapshot: DeviceSnapshot? {
        guard let id = device?.id, !id.hasPrefix("s:"), !id.hasPrefix("ble:") else { return nil }
        return manager.snapshot(for: id)
    }

    var body: some View {
        Form {
            if let snapshot {
                MachineSection(snap: snapshot)
            } else if device != nil {
                Section("기기 상태 기계") {
                    Text("아직 등록부에 없다 — 신원이 확정되기 전이거나 페어링만 된 기기다.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }

            if let session = device?.session, let extend = device?.extend {
                // 기계가 "파이프라인 올라옴"이라 믿는지를 같이 넘긴다 — 그래야 아래에서
                // "기계는 올라왔다는데 캡처는 죽어 있다"는 **대조**가 가능하다. 그 어긋남이
                // 곧 화면 정지의 지문이고, 한쪽만 보고 있으면 절대 안 보인다.
                StreamSections(session: session, extend: extend,
                               machineSaysUp: extend.machineState.pipe == .up)
            } else {
                Section("영상") {
                    Text("송출 중인 세션이 없다.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }

            HostSection(host: manager.hostSnapshot(aoa: core.aoa, pairing: core.pairing))
            TouchSpikeSection(aoa: core.aoa)
        }
        .formStyle(.grouped)
        .font(.caption)
    }
}

// MARK: - 5. USB 터치 주입 (AOAv2 HID 스파이크)

/// 실기기로만 알 수 있는 것들을 확인하기 위한 실험 패널.
///
/// 무엇을 관찰하려는가:
///  1. `REGISTER_HID`가 최신 Android에서 성공하는가 (f_accessory의 HID 경로가 살아 있는가)
///  2. 커널이 드라이버를 붙이고 입력 기기를 만드는가 (`adb shell getevent -pl`)
///  3. InputReader가 **터치스크린**으로 분류하는가 (마우스/태블릿이 아니라)
///  4. 논리 0…32767이 화면 어디로 떨어지는가 — 회전·디스플레이 연결 포함
///
/// 등록이 자동이 아니라 버튼인 건 실험 편의가 아니라 **설계 규칙**이다. Android는 EP0로 들어오는
/// HID를 거부할 수 없으므로, "언제 켜지는가"를 우리가 쥐는 것이 유일한 방어선이다.
private struct TouchSpikeSection: View {
    @ObservedObject var aoa: AoaLink
    @State private var selected: String?
    @State private var registered = false
    @State private var note = ""

    private var deviceID: String? { selected ?? aoa.usbDeviceIDs.first }

    var body: some View {
        Section("USB 터치 주입 (실험)") {
            if aoa.usbDeviceIDs.isEmpty {
                Text("USB로 연결된 기기가 없다. 케이블을 꽂으면 여기 나타난다.")
                    .font(.caption).foregroundStyle(.secondary)
            } else if let id = deviceID {
                let proto = aoa.aoaProtocolVersion(id)

                if aoa.usbDeviceIDs.count > 1 {
                    Picker("기기", selection: Binding(get: { id }, set: { selected = $0 })) {
                        ForEach(aoa.usbDeviceIDs, id: \.self) { Text($0).tag($0) }
                    }
                }
                DiagRow("AOA 프로토콜", proto > 0 ? "v\(proto)" : "미확인", warn: proto < 2)

                if proto < 2 {
                    Text("HID는 AOAv2 이상에서만 동작한다. 이 기기는 디지타이저를 등록할 수 없다.")
                        .font(.caption).foregroundStyle(.secondary)
                } else {
                    HStack {
                        // 디지타이저·키보드를 한 벌로 다룬다 — 하나만 켜 두면 "터치는 되는데
                        // 글자는 안 들어간다" 같은 반쪽 상태가 생기고 원인을 못 찾는다.
                        Button(registered ? "입력 HID 해제" : "입력 HID 등록") {
                            guard let inj = DeviceInputHub.shared.inputs(deviceID: id) else {
                                note = "이 기기의 주입기가 없다 (케이블 확인)"
                                return
                            }
                            if registered {
                                inj.unregister(); registered = false; note = "해제됨"
                            } else {
                                note = "등록 중…"
                                Task {
                                    registered = await inj.register()
                                    note = registered ? "등록 성공 — adb로 입력 기기 확인"
                                                      : "등록 실패 (콘솔 로그 확인)"
                                }
                            }
                        }
                        Spacer()
                        if !note.isEmpty {
                            Text(note).font(.caption).foregroundStyle(.secondary)
                        }
                    }

                    if registered {
                        TouchPad { p in
                            note = String(format: "탭 (%.2f, %.2f)", p.x, p.y)
                            Task { await DeviceInputHub.shared.inputs(deviceID: id)?.touch.tap(at: p) }
                        }
                        HStack {
                            Button("↑ 위로 스와이프 (홈)") {
                                note = "스와이프 ↑"
                                Task {
                                    await DeviceInputHub.shared.inputs(deviceID: id)?.touch.swipe(
                                        from: CGPoint(x: 0.5, y: 0.95),
                                        to:   CGPoint(x: 0.5, y: 0.40))
                                }
                            }
                            Button("↓ 아래로 (알림)") {
                                note = "스와이프 ↓"
                                Task {
                                    await DeviceInputHub.shared.inputs(deviceID: id)?.touch.swipe(
                                        from: CGPoint(x: 0.5, y: 0.02),
                                        to:   CGPoint(x: 0.5, y: 0.60))
                                }
                            }
                        }
                        Text("확인: `adb shell getevent -pl` 에 새 입력 기기가 뜨는지, "
                             + "`INPUT_PROP_DIRECT`가 붙는지, 탭이 화면 같은 위치에 떨어지는지.")
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                }
            }
        }
    }
}

/// 폰 화면 비율의 판. 누른 지점을 정규화 좌표(0…1, 좌상단 원점)로 넘긴다 —
/// 디스크립터가 선언한 논리 공간과 같은 방향이라 매핑이 그대로 드러난다.
private struct TouchPad: View {
    let onTap: (CGPoint) -> Void

    var body: some View {
        GeometryReader { geo in
            RoundedRectangle(cornerRadius: 8)
                .strokeBorder(.secondary, lineWidth: 1)
                .background(RoundedRectangle(cornerRadius: 8).fill(.quaternary))
                .overlay(alignment: .top) {
                    Text("여기를 누르면 그 위치로 터치가 주입된다")
                        .font(.caption2).foregroundStyle(.secondary).padding(.top, 6)
                }
                .contentShape(Rectangle())
                .onTapGesture(coordinateSpace: .local) { pt in
                    guard geo.size.width > 0, geo.size.height > 0 else { return }
                    onTap(CGPoint(x: pt.x / geo.size.width, y: pt.y / geo.size.height))
                }
        }
        .frame(height: 260)
        .frame(maxWidth: 130)
        .frame(maxWidth: .infinity, alignment: .center)
    }
}

// MARK: - 1. 기기 상태 기계

private struct MachineSection: View {
    let snap: DeviceSnapshot

    var body: some View {
        let s = snap.state
        Section("기기 상태 기계") {
            // 파생값이 맨 위 — 사이드바·상세 화면이 보여주는 그 문구다.
            DiagRow("요약(파생)", s.summary)

            DiagRow("세션", text(s.sess))
            DiagRow("제어 채널", text(s.channel))
            // (파이프라인·디스플레이·제원은 여기 없다 — 기기 기계는 앱을 모른다.
            //  확장 앱의 축이므로 아래 '영상' 절에서 보여준다.)
            if s.retiring { DiagRow("정리 중", "예", warn: true) }
            DiagRow("실행 세션", snap.hasSession ? "있음" : "없음",
                    warn: !snap.hasSession && isLive(s.sess))
        }

        Section("무장된 시계") {
            DiagRow("유예(\(Int(departureGrace))s)", snap.graceArmed ? "돌고 있음" : "—")
            DiagRow("핸드셰이크 마감(\(Int(handshakeDeadline))s)", snap.handshakeArmed ? "돌고 있음" : "—")
            if let why = snap.timerMismatch {
                Label(why, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption).foregroundStyle(.orange)
            }
        }
    }

    private func isLive(_ v: Sess) -> Bool { if case .live = v { return true }; return false }
    private func isLive(_ v: Channel) -> Bool { if case .live = v { return true }; return false }

    private func text(_ v: Sess) -> String {
        switch v {
        case .none: return "없음"
        case .establishing: return "확립 중"
        case .live(let peer): return "확립됨 · \(peer)"
        }
    }

    private func text(_ v: Channel) -> String {
        switch v {
        case .none: return "없음"
        case .live(let c): return String(describing: c)
        }
    }

    private func text(_ v: Pipe) -> String {
        switch v {
        case .down: return "내려감"
        case .building: return "구성 중"
        case .up: return "올라옴"
        case .suspended: return "SCK 사이클로 정지"
        }
    }
}

// MARK: - 2·3. 원시 수치 (세션이 있을 때만)

private struct StreamSections: View {
    @ObservedObject var session: Session
    /// 수치의 출처. 세션이 아니라 **서비스**가 낸다 — 세션이 갈려도 같은 값이 이어진다.
    @ObservedObject var extend: ExtendService
    /// 기기 상태 기계가 파이프라인을 `.up`으로 보고 있는가. 아래 캡처 상태와 대조한다.
    let machineSaysUp: Bool

    var body: some View {
        let s = extend.stats

        Section("영상") {
            DiagRow("코덱", s.codec)
            DiagRow("송출 해상도", "\(s.width)×\(s.height)")
            DiagRow("프레임레이트", "\(s.fps) fps")
            DiagRow("논리(표시)", "\(s.logicalW)×\(s.logicalH) pt")
            DiagRow("백킹 스케일", String(format: "%.0fx", s.backingScale))
            DiagRow("캡처 활성", s.active ? "예" : "아니오", warn: machineSaysUp && !s.active)
            if machineSaysUp && !s.active {
                Label("기계는 파이프라인이 올라왔다고 보는데 캡처가 죽어 있다 — 화면이 멈춘다.",
                      systemImage: "exclamationmark.triangle.fill")
                    .font(.caption).foregroundStyle(.orange)
            }
        }

        Section("비트레이트") {
            DiagRow("실측", fmtKbps(s.measuredKbps))
            DiagRow("목표(AIMD)", fmtKbps(s.targetKbps))
            DiagRow("협상 상한", fmtKbps(s.maxKbps))
            DiagRow("손실률", "\(s.lossPct) %", warn: s.lossPct >= 5)
        }

        Section("지연 구간") {
            if s.hasLatency {
                DiagRow("인코딩(Mac)", ms(s.encMs))
                DiagRow("전송", ms(s.netMs))
                DiagRow("수신 버퍼", ms(s.jbufMs))
                DiagRow("디코딩", ms(s.decMs))
                DiagRow("합계", ms(s.totalMs))
            } else {
                Text("아직 보고 없음 — 클럭 동기 전이거나 수신 피드백이 끊겼다.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            DiagRow("왕복(RTT)", ms(s.rttMs))
        }

        Section("링크 · 신원") {
            DiagRow("전송", s.linkDetail)
            if !session.reliableTransport {
                // 관측(피어 주소가 우리 서브넷인가)과 보고(클라가 말한 망 종류)를 합친 결과다.
                DiagRow("경로 종류", session.wireless.label,
                        warn: session.wireless == .cellular)
                Text(session.wireless.detail)
                    .font(.caption2).foregroundStyle(.secondary)
            }
            DiagRow("신뢰 전송(AOA)", session.reliableTransport ? "예 (USB)" : "아니오 (LAN)")
            DiagRow("디스플레이 ID", s.displayID > 0 ? String(s.displayID) : "—")
            DiagRow("기기 지문", session.fingerprint ?? "미확정")
            DiagRow("재생 버퍼", "\(Int(extend.playoutBufferMs)) ms")
            if let detail = session.detail ?? extend.detail {
                // 기계가 모르는 예외 문구 — 있으면 그게 곧 지금의 이상이다.
                Label(detail, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption).foregroundStyle(.orange)
            }
        }
    }

    private func ms(_ v: Int) -> String { v > 0 ? "\(v) ms" : "—" }
    private func fmtKbps(_ kbps: Int) -> String {
        kbps >= 1000 ? String(format: "%.1f Mbps", Double(kbps) / 1000) : "\(kbps) kbps"
    }
}

// MARK: - 4. 호스트 (전역)

private struct HostSection: View {
    let host: HostSnapshot

    var body: some View {
        Section("호스트 · 수신") {
            DiagRow("LAN 수신", host.serving ? "켜짐" : "꺼짐")
            DiagRow("제어 포트", host.controlPort > 0 ? String(host.controlPort) : "—")
            DiagRow("로컬 주소", host.localIP)
        }

        Section("호스트 · ICE 라운드") {
            DiagRow("라운드 번호", host.negoRound > 0 ? "r:\(host.negoRound)" : "—")
            DiagRow("NAT 매핑", host.nat)
            DiagRow("펀치 TCP 포트", host.punchPort > 0 ? String(host.punchPort) : "—")
            DiagRow("RESUME 토큰", host.resumeArmed ? "세워짐" : "없음")
        }

        Section("호스트 · 디스플레이") {
            DiagRow("등록 기기", "\(host.deviceCount)대 (송출 \(host.streamingCount))")
            // `DisplayManager`를 **직접** 읽는다. 예전엔 세션 등록부가 이 값들을 진단 구조체로
            // 옮겨 담았는데, 그 중계 때문에 세션 계층이 디스플레이를 알아야 했다(원칙 1 위반).
            // 여기는 뷰라 디스플레이를 알아도 된다 — 화면에 그리는 게 일이다.
            DiagRow("가상 디스플레이", "\(DisplayManager.shared.liveDisplayCount)개")
            // 사이클이 도는 동안은 **모든** 기기의 파이프라인이 내려간다 — 한 기기의 증상이
            // 사실 전역 사이클 때문일 수 있어서 여기 같이 놓는다.
            DiagRow("SCK 재열거", DisplayManager.shared.isCycling ? "도는 중" : "정상",
                    warn: DisplayManager.shared.isCycling)
            DiagRow("SCK 미반영 파괴",
                    DisplayManager.shared.pendingSCKDrops > 0 ? "\(DisplayManager.shared.pendingSCKDrops)개" : "없음",
                    warn: DisplayManager.shared.pendingSCKDrops > 0)
        }

        Section("호스트 · 곁가지") {
            DiagRow("USB(AOA)", host.usbStatus)
            DiagRow("BLE 페어링", host.blePhase)
        }
    }
}

// MARK: - 공용 행

private struct DiagRow: View {
    let key: String
    let value: String
    let warn: Bool

    init(_ key: String, _ value: String, warn: Bool = false) {
        self.key = key; self.value = value; self.warn = warn
    }

    var body: some View {
        LabeledContent(key) {
            Text(value)
                .font(.caption.monospaced())
                .foregroundStyle(warn ? Color.orange : Color.primary)
                .textSelection(.enabled)
        }
    }
}
