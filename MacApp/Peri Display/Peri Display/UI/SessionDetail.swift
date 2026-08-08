//
//  SessionDetail.swift
//  Peri Display
//
//  활성 세션의 상세 화면.
//
//  레이아웃 원칙 (HIG macOS 레이아웃 규약):
//   ① 내용이 pane 폭을 실제로 채운다. 예전엔 680pt 칼럼을 가운데 띄워 좌우가 텅 비었는데,
//      HIG의 "center equalization"은 *좌우 시각 무게의 균형*이지 좁은 칼럼의 가운데 정렬이
//      아니다. 상한(Metrics.maxContentWidth)은 초광폭에서 카드가 늘어지는 것만 막는 선.
//   ② 위계는 3층: 타일(한눈에 읽는 수치) → 카드(왜 그런지) → 인스펙터 ⌥⌘I(원시 수치 전부).
//   ③ 배경 대비. pane은 눌린 색, 카드는 밝은 색. 예전엔 pane을 흰색으로 덮어써서
//      흰 배경 위 흰 카드 = 경계가 사라져 화면이 납작했다.
//
//  ★ 레이아웃 루프 주의: 이 화면의 GeometryReader(스파크라인·지연 스택·배치 미니맵)는
//  반드시 폭이 *부모에서 먼저 확정된* 자리에만 둔다. 내용 크기로 잡히는 컨테이너 안에
//  넣으면 "폭을 알려면 내용이, 내용을 알려면 폭이" 순환이 생기고, 1초마다 바뀌는 숫자가
//  그 순환을 계속 흔들어 AppKit 무한 루프 가드(NSGenericException)로 앱이 죽는다.
//  → HStack 자식엔 maxWidth:.infinity, 그리드는 열 폭이 위에서 내려오는 것만 쓴다.
//

import SwiftUI

struct SessionDetail: View {
    let device: DeviceModel
    @ObservedObject var core: AppCore
    /// 연결 자체(경로 종류, 연결 해제). 갈릴 수 있다.
    @ObservedObject var session: Session
    /// 이 기기의 홈 화면 — 앱 전부. 기기에 매여 세션보다 오래 산다.
    @ObservedObject var apps: AppHost
    @ObservedObject var profiles: ProfileStore
    @Binding var inspectorPresented: Bool

    /// 배치는 앱 전체에 하나뿐인 스토어가 디바운스해 갱신한다 — 뷰마다 알림을 구독하면
    /// 기기 수만큼 WindowServer 조회가 중복돼 메인 스레드가 묶인다.
    @ObservedObject private var arrangement = DisplayArrangementStore.shared

    /// 화면 확장 앱 — 통계·HDR·재생 버퍼의 출처.
    private var extend: ExtendService { apps.extend }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.sectionGap) {
                header
                // 홈 화면이 먼저다. 무엇이 켜져 있는지가 이 화면의 첫 문장이고, 아래 카드들은
                // 그중 **확장 앱 하나**의 속사정이다 — 확장이 꺼져 있으면 있을 이유가 없다.
                PeriHome(apps: apps, share: apps.share)
                if extend.isRunning {
                    if extend.stats.active {
                        tiles
                        detailRow
                        bitrateCard
                        playbackCard
                    } else {
                        connectingCard
                    }
                }
            }
            .padding(Metrics.margin)
            // 상한까지는 폭을 꽉 쓰고, 그보다 넓은 창에서만 가운데로 균형을 잡는다.
            .frame(maxWidth: Metrics.maxContentWidth, alignment: .leading)
            .frame(maxWidth: .infinity, alignment: .center)
        }
        .recessedBackground()
        .navigationTitle(device.name)
    }

    private var s: StreamStats { extend.stats }

    // MARK: 헤더

    private var header: some View {
        HStack(alignment: .center, spacing: 14) {
            Image(systemName: "iphone.gen3")
                .font(.system(size: 22, weight: .medium))
                .foregroundStyle(.tint)
                .frame(width: 46, height: 46)
                .background(Color.accentColor.opacity(0.12),
                            in: RoundedRectangle(cornerRadius: 11))

            VStack(alignment: .leading, spacing: 3) {
                Text(device.name)
                    .font(.title2.weight(.semibold))
                HStack(spacing: 6) {
                    StatusDot(color: s.active ? .green : .secondary, pulsing: s.active)
                    Text(device.detailText ?? "—")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }

            Spacer(minLength: 12)

            if device.link == .usb {
                BadgeLabel(text: "USB · AOA", systemImage: "cable.connector", tint: .orange)
            } else {
                BadgeLabel(text: device.wireless.label,
                           systemImage: device.wireless.icon,
                           tint: device.wireless.tint)
                    .help(device.wireless.detail)
            }

            // 연결 해제는 되돌리는 동작이라 화면에서 가장 시끄러운 요소가 되면 안 된다
            // (예전엔 borderedProminent 파란 버튼이라 눈이 여기 먼저 갔다).
            Button("연결 해제") {
                Task { await core.disconnect(device) }
            }
            .help("기기에 종료를 알리고 이 가상 디스플레이를 정리합니다")
        }
    }

    private var connectingCard: some View {
        Card {
            HStack(spacing: 10) {
                ProgressView().controlSize(.small)
                Text(device.detailText ?? "—").foregroundStyle(.secondary)
                Spacer()
            }
        }
    }

    // MARK: 1층 — 한눈에 읽는 수치

    /// 폭에 따라 열 수가 자동으로 접힌다(4→2→1). GeometryReader 없이 컨테이너 폭만으로
    /// 결정되므로 레이아웃 순환이 없다.
    ///
    /// 부제(detail)에는 **다른 카드에 이미 있는 수치를 반복하지 않는다.** 예전엔 총 지연 밑에
    /// "인코딩 25 · 전송 22"(→지연 분해 카드와 중복), 비트레이트 밑에 상한(→비트레이트 카드
    /// 헤더와 중복), 왕복 지연 밑에 코덱·fps(→헤더와 중복인 데다 왕복 지연과 무관)를 넣었다.
    /// 부제는 그 수치를 *해석*하거나 *출처*를 밝히는 데만 쓴다.
    private var tiles: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 170), spacing: Metrics.cardGap)],
                  spacing: Metrics.cardGap) {
            MetricTile(label: "총 지연", systemImage: "timer",
                       value: s.hasLatency ? "\(s.totalMs)" : "—",
                       unit: s.hasLatency ? "ms" : nil,
                       detail: s.hasLatency ? latencyVerdict : "측정 대기 중",
                       tint: latencyTint)

            MetricTile(label: "비트레이트", systemImage: "chart.line.uptrend.xyaxis",
                       value: bitrateValue, unit: bitrateUnit,
                       detail: bitrateHeadroom)

            MetricTile(label: "손실", systemImage: "chart.line.downtrend.xyaxis",
                       value: "\(s.lossPct)", unit: "%",
                       detail: s.lossPct >= 5 ? "LTR 키프레임으로 복구 중" : "안정",
                       tint: s.lossPct >= 5 ? .orange : .green)

            MetricTile(label: "왕복 지연", systemImage: "arrow.left.arrow.right",
                       value: s.rttMs > 0 ? "\(s.rttMs)" : "—",
                       unit: s.rttMs > 0 ? "ms" : nil,
                       detail: s.rttMs > 0 ? "1초 주기 Ping–Pong 실측" : "핑 응답 대기")
        }
    }

    /// 총 지연을 사람 말로 판정 — 숫자만으로는 이게 좋은 값인지 알 수 없다.
    private var latencyVerdict: String {
        if s.totalMs >= 120 { return "체감 지연 있음" }
        if s.totalMs >= 60 { return "보통" }
        return "쾌적"
    }

    /// 협상 상한 대비 여유. 상한 값 자체는 비트레이트 카드가 이미 보여 준다.
    private var bitrateHeadroom: String {
        guard s.maxKbps > 0 else { return "상한 미정" }
        let pct = Int((Double(s.measuredKbps) / Double(s.maxKbps) * 100).rounded())
        return "상한의 \(max(0, pct))% 사용"
    }

    private var latencyTint: Color {
        guard s.hasLatency else { return .primary }
        if s.totalMs >= 120 { return .orange }
        if s.totalMs >= 60 { return .yellow }
        return .green
    }

    private var bitrateValue: String {
        s.measuredKbps >= 1000
            ? String(format: "%.1f", Double(s.measuredKbps) / 1000)
            : "\(s.measuredKbps)"
    }

    private var bitrateUnit: String { s.measuredKbps >= 1000 ? "Mbps" : "kbps" }

    // MARK: 2층 — 배치 · 지연 분해

    /// 나란한 두 카드는 **높이를 맞춘다.** 그냥 HStack에 두면 각자 내용 높이로 잡혀,
    /// 짧은 쪽(지연 분해) 아래에 카드 높이의 절반쯤 되는 빈 구멍이 생겼다.
    /// Grid 한 행은 셀 높이를 행 높이로 통일해 주므로, 셀에 maxHeight:.infinity만 주면 채워진다.
    private var detailRow: some View {
        Grid(horizontalSpacing: Metrics.cardGap, verticalSpacing: Metrics.cardGap) {
            GridRow {
                arrangementCard.frame(maxWidth: .infinity, maxHeight: .infinity)
                latencyCard.frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }

    private var arrangementCard: some View {
        Card(title: "디스플레이 배치", systemImage: "rectangle.3.group",
             accessory: AnyView(
                Button("시스템 설정…") { DisplayArrangement.openSystemSettings() }
                    .controlSize(.small)
             )) {
            VStack(alignment: .leading, spacing: 10) {
                ArrangementMap(slots: arrangement.slots, highlight: s.displayID, height: 170)
                Text(placementSummary)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    /// 주 디스플레이 기준 상대 위치를 사람 말로. 배치를 못 읽으면 논리 해상도만 표시.
    private var placementSummary: String {
        let logical = "\(s.logicalW)×\(s.logicalH)pt · \(String(format: "%.0f", s.backingScale))x"
        guard let mine = arrangement.slots.first(where: { $0.id == s.displayID }),
              let main = arrangement.slots.first(where: { $0.isMain }), mine.id != main.id else {
            return logical
        }
        let dx = mine.frame.midX - main.frame.midX
        let dy = mine.frame.midY - main.frame.midY
        let side: String
        if abs(dx) >= abs(dy) { side = dx >= 0 ? "오른쪽" : "왼쪽" }
        else { side = dy >= 0 ? "아래" : "위" }
        return "주 디스플레이 \(side) · 확장 · \(logical)"
    }

    /// 지연 스택 — 어느 구간이 병목인지 숫자를 읽지 않고 폭으로 안다.
    private var latencyCard: some View {
        Card(title: "지연 분해", systemImage: "chart.bar.doc.horizontal") {
            if s.hasLatency {
                VStack(alignment: .leading, spacing: 12) {
                    LatencyStack(enc: s.encMs, net: s.netMs, jbuf: s.jbufMs, dec: s.decMs)
                    VStack(spacing: 7) {
                        LatencyRow(label: "인코딩", detail: "Mac", value: s.encMs,
                                   total: s.totalMs, color: LatencyPalette.encode)
                        LatencyRow(label: "전송",
                                   detail: device.link == .usb ? "USB" : device.wireless.label,
                                   value: s.netMs, total: s.totalMs, color: LatencyPalette.network)
                        LatencyRow(label: "수신 버퍼", detail: "Android", value: s.jbufMs,
                                   total: s.totalMs, color: LatencyPalette.buffer)
                        LatencyRow(label: "디코딩", detail: "Android", value: s.decMs,
                                   total: s.totalMs, color: LatencyPalette.decode)
                    }
                    Spacer(minLength: 0)   // 배치 카드와 높이를 맞출 때 내용은 위로 붙인다
                }
            } else {
                VStack(spacing: 6) {
                    Text("측정 대기 중").font(.callout).foregroundStyle(.secondary)
                    Text("기기가 첫 피드백을 보내면 표시됩니다")
                        .font(.caption2).foregroundStyle(.tertiary)
                }
                .frame(maxWidth: .infinity, minHeight: 170)
            }
        }
    }

    // MARK: 비트레이트 추이 — 폭을 다 쓸 때 비로소 읽을 값이 된다

    private var bitrateCard: some View {
        // 헤더 보조값은 그래프에서 읽을 수 없는 것만 — 현재값은 위 타일에, 상한은 타일 부제에
        // 이미 있으므로 여기서는 축의 꼭대기(최고점)를 밝혀 세로 눈금을 해석할 수 있게 한다.
        Card(title: "비트레이트 · 최근 60초", systemImage: "waveform.path.ecg",
             accessory: AnyView(
                Text(s.bitrateHistory.isEmpty
                     ? "기록 없음"
                     : "최고 \(fmtMbps(s.bitrateHistory.max() ?? 0))")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
             )) {
            Sparkline(values: s.bitrateHistory, window: 60)
                .frame(height: 64)
        }
    }

    // MARK: 재생 모드

    private var playbackCard: some View {
        Card {
            HStack(spacing: 12) {
                Picker("재생 모드", selection: $profiles.profile) {
                    Text("저지연").tag(StreamProfile.lowLatency)
                    Text("고화질").tag(StreamProfile.highQuality)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .fixedSize()

                if profiles.profile == .highQuality {
                    Image(systemName: "timer").foregroundStyle(.secondary)
                    // 확장 앱은 이제 `apps`를 거쳐 오므로 `$extend`가 없다 — 바인딩을 직접 만든다.
                    // set에서 곧장 적용하므로 별도 onChange가 필요 없다(예전엔 값 대입과 적용이
                    // 두 곳으로 갈려 슬라이더를 놓는 순간에만 반영됐다).
                    Slider(value: Binding(get: { extend.playoutBufferMs },
                                          set: { extend.setPlayoutBuffer($0) }),
                           in: 8...150, step: 2)
                    Text("\(Int(extend.playoutBufferMs)) ms")
                        .font(.caption.monospacedDigit())
                        .frame(width: 52, alignment: .trailing)
                } else {
                    Label("즉시 렌더 · 적응형 fps", systemImage: "bolt.fill")
                        .font(.caption).foregroundStyle(.secondary)
                    Spacer()
                }

                // 베타. 기기가 HDR10을 실제로 받을 수 있을 때만 나타난다(협상 결과).
                // 전환은 가상 디스플레이까지 다시 만들므로 화면이 한 번 끊긴다.
                if extend.hdrAvailable {
                    Toggle("HDR", isOn: Binding(
                        get: { extend.hdrEnabled },
                        set: { on in Task { await extend.setHDR(on) } }
                    ))
                    .toggleStyle(.switch)
                    .controlSize(.small)
                    .help(extend.hdrActive
                          ? "HDR10 송출 중 · 10비트 PQ/Rec.2020 (베타)"
                          : "베타 — 켜면 디스플레이를 HDR로 다시 만든다(짧게 끊김). 대역폭·인코더 부하가 늘어난다")
                }

                Button {
                    inspectorPresented.toggle()
                } label: {
                    Label("진단", systemImage: "stethoscope")
                }
                .controlSize(.small)
                .help("원시 수치 전체 보기 (⌥⌘I)")
            }
        }
    }

    private func fmtMbps(_ kbps: Int) -> String {
        kbps >= 1000 ? String(format: "%.1f Mbps", Double(kbps) / 1000) : "\(kbps) kbps"
    }
}

// MARK: - 지연 스택

/// 구간별 지연을 폭으로 인코딩한 누적 막대. 색은 구간의 책임 주체를 구분한다:
/// Mac 인코딩 / 전송 / 수신 버퍼 / 수신 디코딩.
struct LatencyStack: View {
    let enc: Int, net: Int, jbuf: Int, dec: Int

    var body: some View {
        GeometryReader { geo in
            let total = max(1, enc + net + jbuf + dec)
            HStack(spacing: 1.5) {
                seg(enc, total, geo.size.width, LatencyPalette.encode)
                seg(net, total, geo.size.width, LatencyPalette.network)
                seg(jbuf, total, geo.size.width, LatencyPalette.buffer)
                seg(dec, total, geo.size.width, LatencyPalette.decode)
            }
        }
        .frame(height: 26)
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }

    @ViewBuilder
    private func seg(_ v: Int, _ total: Int, _ width: CGFloat, _ color: Color) -> some View {
        if v > 0 {
            let w = width * CGFloat(v) / CGFloat(total)
            Rectangle()
                .fill(color.gradient)
                .frame(width: max(2, w))
                .overlay {
                    if w > 20 {
                        Text("\(v)")
                            .font(.system(size: 10, weight: .bold).monospacedDigit())
                            .foregroundStyle(.white)
                    }
                }
        }
    }
}

/// 지연 한 구간의 설명 줄 — 색 칩 · 이름 · 담당 · 값 · 비중.
struct LatencyRow: View {
    let label: String
    let detail: String
    let value: Int
    let total: Int
    let color: Color

    var body: some View {
        HStack(spacing: 8) {
            RoundedRectangle(cornerRadius: 2).fill(color).frame(width: 8, height: 8)
            Text(label).font(.caption)
            Text(detail).font(.caption2).foregroundStyle(.tertiary)
            Spacer(minLength: 6)
            Text("\(value) ms")
                .font(.caption.monospacedDigit())
                .foregroundStyle(value == 0 ? .secondary : .primary)
            Text(pct)
                .font(.caption2.monospacedDigit())
                .foregroundStyle(.tertiary)
                .frame(width: 38, alignment: .trailing)
        }
    }

    private var pct: String {
        guard total > 0, value > 0 else { return "—" }
        return "\(Int((Double(value) / Double(total) * 100).rounded()))%"
    }
}

// MARK: - 스파크라인

/// 비트레이트 이력. 면적 채움 + 최신 지점 강조.
///
/// 시간축이 정직해야 한다: 가로축은 항상 `window`초 전체를 뜻하고, 표본이 그보다 적으면
/// **오른쪽(최신)에 붙여** 그리고 왼쪽은 비워 둔다. 예전엔 있는 표본을 폭 전체로 늘려 그려서,
/// 5초치 데이터가 60초처럼 보이고 스파이크의 시점도 왜곡됐다.
///
/// 세로 스케일은 *데이터*에 맞춘다. 협상 상한(예: 18.2Mbps)에 고정하면 실측 59kbps 같은 값이
/// 바닥에 붙어 그래프가 비어 보인다. 축의 꼭대기는 카드 헤더가 숫자로 밝힌다.
struct Sparkline: View {
    let values: [Int]
    var window: Int = 60

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height
            let maxV = max(Double(values.max() ?? 1) * 1.25, 1)
            let n = values.count
            // 한 표본이 차지하는 가로 폭 — 표본 수와 무관하게 창 길이로 고정된다.
            let step = w / CGFloat(max(window - 1, 1))
            // 최신 표본이 오른쪽 끝에 오도록 시작점을 민다.
            let x0 = w - step * CGFloat(max(n - 1, 0))

            ZStack {
                gridLines(w: w, h: h)

                if n >= 2 {
                    let pts = values.enumerated().map { i, v in
                        CGPoint(x: x0 + step * CGFloat(i),
                                y: h * (1 - CGFloat(Double(v) / maxV)))
                    }
                    Path { p in
                        p.move(to: CGPoint(x: pts[0].x, y: h))
                        pts.forEach { p.addLine(to: $0) }
                        p.addLine(to: CGPoint(x: pts[n - 1].x, y: h))
                        p.closeSubpath()
                    }
                    .fill(LinearGradient(colors: [Color.accentColor.opacity(0.28),
                                                  Color.accentColor.opacity(0.02)],
                                         startPoint: .top, endPoint: .bottom))

                    Path { p in
                        p.move(to: pts[0])
                        pts.dropFirst().forEach { p.addLine(to: $0) }
                    }
                    .stroke(Color.accentColor,
                            style: StrokeStyle(lineWidth: 1.8, lineCap: .round, lineJoin: .round))

                    Circle()
                        .fill(Color.accentColor)
                        .frame(width: 6, height: 6)
                        .position(pts[n - 1])
                }
            }
        }
        .background(Color.primary.opacity(0.03), in: RoundedRectangle(cornerRadius: 6))
    }

    /// 눈금 3줄 — 값이 없어도 카드가 비어 보이지 않게 하고, 기울기를 읽을 기준선이 된다.
    private func gridLines(w: CGFloat, h: CGFloat) -> some View {
        Path { p in
            for i in 1...3 {
                let y = h * CGFloat(i) / 4
                p.move(to: CGPoint(x: 0, y: y))
                p.addLine(to: CGPoint(x: w, y: y))
            }
        }
        .stroke(Color.secondary.opacity(0.15), lineWidth: 0.5)
    }
}
