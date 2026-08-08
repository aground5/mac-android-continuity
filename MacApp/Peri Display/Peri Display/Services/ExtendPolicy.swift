//
//  ExtendPolicy.swift
//  Peri Display
//
//  **extend 서비스가 정하는 것과 보고하는 것.**
//
//  여태 이 코드는 `MirrorSession.swift`에 있었다. 세션이 곧 미러였으므로 "무엇을 어떤 코덱·
//  해상도·비트심도로 보낼지"도 세션의 일처럼 보였다. 아니다 — 그건 **서비스의 정책**이다.
//  세션은 누구와 말하는지만 알면 되고, 코덱이라는 단어를 몰라야 다음 서비스(share·files)가
//  같은 자리에 들어올 수 있다(DESIGN.md §5).
//
//  협상의 원칙 하나: **원재료로 받고 곱셈은 여기서 한다.**
//  클라는 화면 능력(DisplayCaps)과 디코더 능력(DecoderCaps)을 *따로* 보낸다. 예전처럼 클라가
//  미리 곱해 불리언으로 주면, 결과가 기대와 다를 때 "화면이 못 하나 / 디코더가 못 하나"를
//  갈라낼 수가 없다.
//

import Combine
import CoreGraphics
import Foundation

// MARK: - 전송이 정하는 상한

/// 이 전송에서 허용하는 협상 상한. 서비스가 아니라 **채널이 답하는 질문**이라
/// (`BearerCaps.reliableStream`) LAN↔AOA 전환이 그대로 반영된다.
nonisolated struct ExtendLimits: Sendable {
    /// fps 상한. AOA(신뢰·광대역)는 패널 최대까지 수용, LAN(Wi-Fi)은 대역·안정성 위해 제한.
    let fpsCap: Int
    /// 품질 상한 클램프(kbps). AOA는 광대역이라 120fps 화질까지 넉넉히.
    let bitrateCeilingKbps: Int

    static func of(reliableStream: Bool) -> ExtendLimits {
        reliableStream ? ExtendLimits(fpsCap: 120, bitrateCeilingKbps: 40_000)
                       : ExtendLimits(fpsCap: 60,  bitrateCeilingKbps: 25_000)
    }
}

// MARK: - 협상 결과

/// ClientHello 협상 결과 — 송출 구성 파라미터 (Sendable, MainActor로 전달됨).
nonisolated struct NegotiatedParams: Sendable {
    let codec: PeriVideoCodec
    let width: Int
    let height: Int
    let fps: Int
    let bitrateKbps: Int     // 초기 동작점(연결 직후)
    let maxBitrateKbps: Int  // 품질 상한(적응 컨트롤러가 링크 한계까지 램프)
    let ppi: Double
    let hiDPI: Bool
    let refreshRate: Double
    let orientation: PeriOrientation
    let maxBound: Int        // 전체 디스플레이 긴 변 (초기 가상 디스플레이 framebuffer 상한)
    /// 협상된 다이내믹 레인지. **희망값이지 확정값이 아니다** — 가상 디스플레이가 실제로 EDR
    /// 헤드룸을 받았는지는 만들어 봐야 알고, 못 받으면 파이프라인이 SDR로 내려간다.
    let dynamicRange: PeriDynamicRange

    var wantsHDR: Bool { dynamicRange == .hdr10 || dynamicRange == .hlg }

    /// 신뢰·광대역 전송(AOA)용: 초기 동작점을 상한으로 끌어올린 사본.
    /// AOA는 혼잡·피드백이 없어 적응 컨트롤러가 램프를 못 도므로, start=ceiling으로 처음부터 최고 품질.
    func startingAtCeiling() -> NegotiatedParams {
        NegotiatedParams(codec: codec, width: width, height: height, fps: fps,
                         bitrateKbps: maxBitrateKbps, maxBitrateKbps: maxBitrateKbps,
                         ppi: ppi, hiDPI: hiDPI, refreshRate: refreshRate,
                         orientation: orientation, maxBound: maxBound,
                         dynamicRange: dynamicRange)
    }

    /// HDR 파이프라인 구성이 실패했을 때 같은 값으로 SDR 사본을 만든다.
    func downgradedToSDR() -> NegotiatedParams {
        NegotiatedParams(codec: codec, width: width, height: height, fps: fps,
                         bitrateKbps: bitrateKbps, maxBitrateKbps: maxBitrateKbps,
                         ppi: ppi, hiDPI: hiDPI, refreshRate: refreshRate,
                         orientation: orientation, maxBound: maxBound,
                         dynamicRange: .sdr)
    }

    /// 기기 기계가 아는 건 이만큼이다 — 디스플레이를 세우는 데 필요한 값만(코덱·비트레이트는 무관).
    var displaySpec: DisplaySpec {
        DisplaySpec(width: width, height: height, ppi: ppi,
                    refreshRate: refreshRate, hiDPI: hiDPI, maxBound: maxBound,
                    hdr: wantsHDR)
    }
}

/// 핸드셰이크 때 받은 수신측 고정 특성 (재협상 시 재사용 — 분할/회전은 w/h만 바뀜).
nonisolated final class ClientCapsBox: @unchecked Sendable {
    private let lock = NSLock()
    private var _dpi = 160
    private var _refresh = 60.0
    private var _h265 = false
    private var _maxBound = 0      // 전체 디스플레이 긴 변 (가상 디스플레이 framebuffer 상한)
    /// 클라가 광고한 다이내믹 레인지 목록(선호순). 회전·분할 재협상에서 HDR이 조용히
    /// 떨어지지 않도록 여기 보관한다 — 재협상은 w/h만 바뀌지 능력은 그대로다.
    private var _ranges: [PeriDynamicRange] = []
    /// 디코더 실제 한계 — 회전·분할 재협상에서도 같은 클램프가 걸려야 한다.
    private var _decoders: [PeriDecoderCaps] = []
    func set(dpi: Int, refresh: Double, h265: Bool, maxBound: Int,
             ranges: [PeriDynamicRange] = [], decoders: [PeriDecoderCaps] = []) {
        lock.lock()
        _dpi = dpi; _refresh = refresh; _h265 = h265; _maxBound = maxBound
        _ranges = ranges; _decoders = decoders
        lock.unlock()
    }
    var snapshot: (dpi: Int, refresh: Double, h265: Bool, maxBound: Int,
                   ranges: [PeriDynamicRange], decoders: [PeriDecoderCaps]) {
        lock.lock(); defer { lock.unlock() }
        return (_dpi, _refresh, _h265, _maxBound, _ranges, _decoders)
    }
    /// 아직 ClientHello를 못 봤는가 — 재협상이 먼저 도착하면 협상 근거가 없다.
    var isEmpty: Bool { lock.lock(); defer { lock.unlock() }; return _maxBound == 0 }
}

// MARK: - 협상 (수신측 디스플레이 특성 → 송출 파라미터)

nonisolated enum ExtendNegotiator {

    // 디스플레이 정보가 없는 구형 클라이언트용 폴백 해상도.
    private static let fallbackWidth = 1920
    private static let fallbackHeight = 1080
    private static let maxLongSide = 2400   // 인코더 부하 상한 (긴 변)

    /// ClientHello에서 협상. 분할/회전 재협상은 [negotiate(widthPx:...)]를 직접 호출.
    static func negotiate(from ch: PeriStsHelloCore, limits: ExtendLimits) -> NegotiatedParams {
        let hasDisp = ch.hasDisplay
        let w = (hasDisp && ch.display.widthPx > 0) ? Int(ch.display.widthPx) : fallbackWidth
        let h = (hasDisp && ch.display.heightPx > 0) ? Int(ch.display.heightPx) : fallbackHeight
        // 전체 디스플레이 긴 변 (분할/팝업이면 창보다 큼). 없으면 현재 창으로 폴백.
        let maxBound = max(
            (hasDisp && ch.display.maxWidthPx > 0) ? Int(ch.display.maxWidthPx) : w,
            (hasDisp && ch.display.maxHeightPx > 0) ? Int(ch.display.maxHeightPx) : h
        )
        return negotiate(
            widthPx: w, heightPx: h,
            densityDpi: (hasDisp && ch.display.densityDpi > 0) ? Int(ch.display.densityDpi) : 160,
            refreshRate: (hasDisp && ch.display.refreshRate > 0) ? Double(ch.display.refreshRate) : 60.0,
            orientation: hasDisp ? ch.display.orientation : .unspecified,
            supportsH265: ch.supportedCodecs.contains(.h265),
            clientRanges: hasDisp ? ch.display.supportedDynamicRanges : [],
            decoders: ch.decoders,
            maxBound: maxBound, limits: limits
        )
    }

    static func negotiate(
        widthPx origW: Int, heightPx origH: Int, densityDpi dpiIn: Int,
        refreshRate refresh: Double, orientation orient: PeriOrientation, supportsH265: Bool,
        clientRanges: [PeriDynamicRange] = [],
        decoders: [PeriDecoderCaps] = [],
        maxBound: Int, limits: ExtendLimits
    ) -> NegotiatedParams {
        // 0) 코덱: Android가 H265 디코드 가능 광고 → H265 (Mac HEVC 저지연 검증됨), 아니면 H264.
        let codec: PeriVideoCodec = supportsH265 ? .h265 : .h264
        // 그 코덱의 **디코더 실제 한계**. 구버전 클라는 안 보내므로 nil일 수 있고, 그때는
        // 예전처럼 디스플레이 제원만으로 협상한다(아래 클램프가 전부 통과된다).
        let dec = decoders.first { $0.codec == codec }

        // 1) 해상도: 긴 변 ≤ maxLongSide 로 캡 (종횡비 유지), 짝수 보정.
        //    디코더 상한도 함께 본다 — 화면이 보여줄 수 있는 크기와 디코더가 풀 수 있는 크기는
        //    다른 사실이고, 후자를 넘기면 그냥 프레임이 안 나온다.
        var scale = 1.0
        let longSide = max(origW, origH)
        if longSide > maxLongSide { scale = Double(maxLongSide) / Double(longSide) }
        if let dec, dec.maxWidth > 0, dec.maxHeight > 0 {
            // 방향이 뒤집힐 수 있으므로 긴 변/짧은 변끼리 비교한다.
            let decLong = Double(max(dec.maxWidth, dec.maxHeight))
            let decShort = Double(min(dec.maxWidth, dec.maxHeight))
            let curLong = Double(max(origW, origH)) * scale
            let curShort = Double(min(origW, origH)) * scale
            if curLong > decLong { scale *= decLong / curLong }
            if curShort > decShort { scale *= decShort / curShort }
        }
        var w = Int((Double(origW) * scale).rounded())
        var h = Int((Double(origH) * scale).rounded())
        w -= w % 2; h -= h % 2
        if w < 2 { w = 2 }; if h < 2 { h = 2 }

        // 3) fps: 주사율을 전송별 상한(fpsCap)으로 캡. AOA=120, LAN=60.
        //    디코더가 낼 수 있는 fps도 상한이다. 클라는 자기 화면 해상도 기준으로 재서 보내므로
        //    우리가 다운스케일했다면 여유는 늘어날 뿐 — 이 값을 상한으로 쓰는 게 항상 안전하다.
        var fps = max(15, min(Int(refresh.rounded()), limits.fpsCap))
        if let dec, dec.maxFrameRate > 0 { fps = max(15, min(fps, Int(dec.maxFrameRate))) }

        // 4) bitrate: 적응 컨트롤러가 실제 값을 정하므로 여기선 *초기 동작점*과 *품질 상한*만.
        //    start=보수적(연결 직후 버스트 방지). ceiling=넉넉(링크 좋으면 품질 위해 램프업).
        //    실 송출은 수신측 큐잉지연 피드백으로 GCC-lite가 start↔ceiling 사이 자동수렴.
        let fpsD = Double(fps)
        let startKbps = max(3_000, min(Int(Double(w * h) * fpsD * 0.05 / 1000.0), 12_000))
        // 디코더 비트레이트 상한도 천장에 반영한다 — 넘겨 봐야 디코더가 못 따라온다.
        let decCeil = (dec?.maxBitrateKbps).map(Int.init).flatMap { $0 > 0 ? $0 : nil } ?? Int.max
        let ceilingKbps = max(startKbps * 2,
                              min(Int(Double(w * h) * fpsD * 0.12 / 1000.0),
                                  min(limits.bitrateCeilingKbps, decCeil)))

        // 5) DPI/hiDPI: 폰은 고밀도 → macOS가 2x 렌더하도록 hiDPI. 해상도 캡 시 물리밀도도 비례 축소.
        let hiDPI = dpiIn >= 240
        let ppi = Double(dpiIn) * scale

        let orientation: PeriOrientation =
            orient != .unspecified ? orient : (w >= h ? .landscape : .portrait)

        // maxBound는 캡 후 해상도(긴 변) 이상이어야 함.
        let effMaxBound = max(maxBound, max(w, h))

        // 6) 다이내믹 레인지. **화면 능력 ∧ 디코더 능력**의 교집합이다 — 클라는 두 사실을 따로
        //    보내오고, 곱하는 건 여기서 한다. 예전엔 클라가 미리 곱해 불리언으로 줬는데 그러면
        //    "화면이 SDR이라서"인지 "디코더가 8비트라서"인지 구분할 수 없었다.
        //
        //    추가 조건 둘:
        //    · 코덱이 H265 — HDR은 10비트 프로파일(Main10)을 요구하고 H.264엔 대응 프로파일이 없다.
        //    · 디코더가 **하드웨어 가속** — 10비트를 SW로 풀면 이 해상도에서 그대로 프레임 드롭이다.
        //      HDR을 포기하고 SDR로 부드럽게 가는 쪽이 낫다.
        //
        //    HLG는 클라가 광고해도 합의하지 않는다 — 우리 인코더는 PQ만 내보내므로 거짓말이 된다.
        //
        //    여기서 정하는 건 *희망값*이다 — 가상 디스플레이가 EDR 헤드룸을 못 받으면
        //    ExtendService가 SDR로 내리고 확정값을 SessionParams로 다시 보낸다.
        let decoderDoesHDR10 = dec.map { $0.dynamicRanges.contains(.hdr10) && $0.hardwareAccelerated }
            ?? clientRanges.contains(.hdr10)   // 구버전 클라: 디코더 정보가 없으니 화면 값만 믿는다
        let dr: PeriDynamicRange =
            (codec == .h265 && clientRanges.contains(.hdr10) && decoderDoesHDR10) ? .hdr10 : .sdr

        return NegotiatedParams(codec: codec, width: w, height: h, fps: fps,
                                bitrateKbps: startKbps, maxBitrateKbps: ceilingKbps,
                                ppi: ppi, hiDPI: hiDPI,
                                refreshRate: refresh, orientation: orientation, maxBound: effMaxBound,
                                dynamicRange: dr)
    }

    /// 클라가 보낸 **원재료**를 한 줄로 — 협상 결과가 예상과 다를 때
    /// "화면이 못 하나 / 디코더가 못 하나 / 우리가 깎았나"를 여기서 갈라낸다.
    static func capsDescription(_ ch: PeriStsHelloCore) -> String {
        func ranges(_ rs: [PeriDynamicRange]) -> String {
            rs.map { $0 == .hdr10 ? "HDR10" : ($0 == .hlg ? "HLG" : "SDR") }.joined(separator: ",")
        }
        let decDesc = ch.decoders.map {
            "\($0.codec == .h265 ? "H265" : "H264")\($0.hardwareAccelerated ? "(HW)" : "(SW)")"
            + " ≤\($0.maxWidth)x\($0.maxHeight)@\($0.maxFrameRate) ≤\($0.maxBitrateKbps)kbps"
            + " [\(ranges($0.dynamicRanges))]"
        }.joined(separator: " | ")
        let screen = ch.hasDisplay
            ? "\(ch.display.widthPx)x\(ch.display.heightPx)@\(Int(ch.display.refreshRate))"
            : "?"
        return "화면 \(screen) HDR=[\(ch.hasDisplay ? ranges(ch.display.supportedDynamicRanges) : "")]"
            + " · 디코더 \(decDesc.isEmpty ? "(미보고 — 구버전 클라)" : decDesc)"
    }
}

// MARK: - UI 표면

/// macOS 앱에 표시할 송출 통계.
struct StreamStats: Sendable {
    var codec = "—"
    var width = 0
    var height = 0
    var logicalW = 0
    var logicalH = 0
    var backingScale = 1.0
    var fps = 0
    var targetKbps = 0       // AIMD 목표 비트레이트
    var maxKbps = 0          // 협상 상한
    var measuredKbps = 0     // 실제 인코딩된 비트레이트(측정)
    var lossPct = 0          // 최근 보고 손실률
    var active = false

    // 지연 스택 — macEnc는 Mac이 로컬 측정(FpsGovernor와 동일 신호), 나머지는 수신측 보고.
    var encMs = 0            // 인코딩+egress (캡처 PTS → 송신)
    var netMs = 0            // 네트워크+재조립
    var jbufMs = 0           // 수신 지터버퍼 대기
    var decMs = 0            // 디코드
    var rttMs = 0            // Ping→Pong 왕복
    var totalMs: Int { encMs + netMs + jbufMs + decMs }
    /// 지연 값이 실제로 보고됐는지 (클럭 동기 전이면 전부 0).
    var hasLatency: Bool { encMs > 0 || netMs > 0 || decMs > 0 }

    /// 실측 비트레이트 최근 이력(kbps, 1Hz, 최대 60초) — 스파크라인용. 최신이 마지막.
    var bitrateHistory: [Int] = []
    var displayID: CGDirectDisplayID = 0   // 배치 미니맵이 이 세션의 디스플레이를 강조
    var linkDetail = "—"                   // "USB 0x14400000" / "192.168.0.12"
}

/// 재생 프로파일 (Mac UI 토글).
///  - lowLatency: 지터버퍼 없음(render-on-arrival) + 적응형 fps → 최소 지연 (AOA 최적)
///  - highQuality: 고정 지터버퍼 + 최대 fps 고정 → 최대 부드러움 (지연↑ 감수)
enum StreamProfile: String, CaseIterable {
    case lowLatency
    case highQuality
}

/// 전역 프로파일 상태(UserDefaults 영속). 새 파이프라인은 빌드 시 이 값을 읽어 적용하고,
/// UI 토글 변경은 AppCore가 활성 기기에 팬아웃한다.
@MainActor final class ProfileStore: ObservableObject {
    static let shared = ProfileStore()
    private static let key = "streamProfile"
    @Published var profile: StreamProfile {
        didSet { UserDefaults.standard.set(profile.rawValue, forKey: Self.key) }
    }
    private init() {
        profile = StreamProfile(rawValue: UserDefaults.standard.string(forKey: Self.key) ?? "")
            ?? .lowLatency
    }
}
