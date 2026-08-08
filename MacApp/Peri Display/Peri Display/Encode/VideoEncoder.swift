//
//  VideoEncoder.swift
//  Peri Display
//
//  VTCompressionSession H.264 인코더 — 저지연 모드(WWDC21) 기반 재구성.
//
//  - EnableLowLatencyRateControl: 프레임 재정렬 제거(1-in-1-out), 빠른 rate 적응, HW 인코더
//  - EnableLTR: Long-Term Reference. 손실 복구를 거대 IDR 대신 작은 LTR-P로.
//      흐름: 인코더가 LTR 프레임에 ack 토큰 발급 → 수신측 ACK(앱레벨) → acknowledgeLTR()
//            → 복구 시 forceLTRRefresh() → ACK된 LTR 참조하는 작은 LTR-P (없으면 IDR 폴백)
//  - MaxAllowedFrameQP: 화면 콘텐츠 텍스트 선명도 (QP 상한, 초과 시 프레임 드롭)
//
//  출력은 Annex-B. 저지연 모드는 macOS 26 SDK에서 H.264·HEVC 둘 다 지원
//  (VTCompressionSessionCreate + RequireHW 런타임 검증 완료) → 코덱 무관 저지연.
//

import Foundation
import VideoToolbox
import CoreMedia
import OSLog

private nonisolated let log = Logger(subsystem: "com.peri.display.mac", category: "encode")

/// HDR 정적 메타데이터 — "이 영상이 어떤 디스플레이에서 만들어졌나".
///
/// 수신측은 이 값을 보고 자기 화면 능력에 맞게 톤매핑한다. 안 보내면 폰이 기본값(보통 1000nit
/// 마스터링)을 가정하므로, 우리 소스 피크가 그와 많이 다를수록 밝기가 어긋난다.
///
/// ⚠️ **폰의 능력치를 넣으면 안 된다.** 이건 소스(Mac 가상 디스플레이)를 기술하는 값이고,
/// 폰 숫자를 되돌려 넣으면 톤매핑이 두 번 걸린다.
nonisolated struct HDRMasteringInfo: Sendable {
    /// SDR 백색(EDR 1.0)이 매핑되는 절대 휘도.
    ///
    /// **측정값이다.** extendedLinearSRGB의 1.0을 kCGColorSpaceITUR_2100_PQ로 변환하면
    /// PQ 코드값 0.5807이 나오고 ST.2084 EOTF를 역산하면 정확히 203.0 cd/m² —
    /// ITU-R BT.2408 reference white와 일치한다.
    static let sdrReferenceWhiteNits = 203.0

    /// 소스 피크 휘도 = EDR 헤드룸 × SDR 백색.
    let peakNits: Double
    /// 프레임 평균 광량 상한. 데스크톱 콘텐츠의 현실적 평균은 SDR 백색 근처다.
    let frameAverageNits: Double

    init(edrHeadroom: Double) {
        peakNits = max(1.0, edrHeadroom) * Self.sdrReferenceWhiteNits
        frameAverageNits = Self.sdrReferenceWhiteNits
    }

    // Rec.2020 원색 + D65 백색점. 우리가 태그하는 컨테이너와 반드시 같아야 한다.
    private static let r = (x: 0.708, y: 0.292)
    private static let g = (x: 0.170, y: 0.797)
    private static let b = (x: 0.131, y: 0.046)
    private static let w = (x: 0.3127, y: 0.3290)

    /// VideoToolbox `kVTCompressionPropertyKey_MasteringDisplayColorVolume`용 24B.
    /// ST 2086 레이아웃: **big-endian**, 원색 **G→B→R 순**, 휘도 단위 0.0001 cd/m².
    var masteringDisplayColorVolume: Data {
        var d = Data()
        func be16(_ v: Double) {
            let x = UInt16(clamping: Int((v * 50_000).rounded()))
            d.append(UInt8(x >> 8)); d.append(UInt8(x & 0xff))
        }
        func be32(_ v: Double) {
            let x = UInt32(clamping: Int((v * 10_000).rounded()))
            for sh: UInt32 in [24, 16, 8, 0] { d.append(UInt8((x >> sh) & 0xff)) }
        }
        be16(Self.g.x); be16(Self.g.y)
        be16(Self.b.x); be16(Self.b.y)
        be16(Self.r.x); be16(Self.r.y)
        be16(Self.w.x); be16(Self.w.y)
        be32(peakNits)
        be32(0.0001)          // 가상 디스플레이엔 물리 최소휘도가 없다 → 사실상 0
        return d
    }

    /// VideoToolbox `kVTCompressionPropertyKey_ContentLightLevelInfo`용 4B (BE): MaxCLL, MaxFALL.
    var contentLightLevelInfo: Data {
        var d = Data()
        func be16(_ v: Double) {
            let x = UInt16(clamping: Int(v.rounded()))
            d.append(UInt8(x >> 8)); d.append(UInt8(x & 0xff))
        }
        be16(peakNits); be16(frameAverageNits)
        return d
    }

    /// Android `MediaFormat.KEY_HDR_STATIC_INFO`용 25B — CTA-861.3 Static Metadata Descriptor.
    /// ST 2086과 **레이아웃이 다르다**: little-endian, 원색 **R→G→B 순**,
    /// 최대휘도 단위 1 cd/m²(최소만 0.0001), 그리고 맨 앞에 디스크립터 ID 1바이트.
    var cta8613StaticMetadata: Data {
        var d = Data([0x00])   // descriptor ID = Type 1
        func le16(_ v: Double, scale: Double) {
            let x = UInt16(clamping: Int((v * scale).rounded()))
            d.append(UInt8(x & 0xff)); d.append(UInt8(x >> 8))
        }
        for p in [Self.r, Self.g, Self.b, Self.w] { le16(p.x, scale: 50_000); le16(p.y, scale: 50_000) }
        le16(peakNits, scale: 1)        // max display mastering luminance (cd/m²)
        le16(0.0001, scale: 10_000)     // min display mastering luminance (0.0001 cd/m²)
        le16(peakNits, scale: 1)        // MaxCLL
        le16(frameAverageNits, scale: 1) // MaxFALL
        return d
    }
}

/// 인코딩된 한 프레임.
nonisolated struct EncodedFrame: Sendable {
    let data: Data          // Annex-B
    let keyframe: Bool
    let ptsMs: UInt32
    /// 이 프레임이 LTR이면 수신측 ACK이 필요한 토큰(없으면 nil).
    let ltrToken: Int64?
    /// 캡처 PTS를 host clock µs로 (지연 계측용 — Mac/네트워크 구간 분해). ptsMs와 동일 시각·풀정밀.
    let ptsHostUs: UInt64
}

nonisolated final class VideoEncoder: @unchecked Sendable {
    private var session: VTCompressionSession?
    private let codec: PeriVideoCodec
    private let width: Int32
    private let height: Int32
    /// HDR10(10비트 PQ/Rec.2020)로 인코딩할지. HEVC에서만 성립한다 — H.264로 떨어지면 무시된다.
    private let hdr: Bool
    /// 소스 마스터링 조건. hdr일 때만 의미 있다.
    private let mastering: HDRMasteringInfo?

    var onEncoded: (@Sendable (EncodedFrame) -> Void)?

    // 다음 encode에 적용할 프레임 옵션 (네트워크/캡처 스레드 교차 → 락 보호)
    private let optLock = NSLock()
    private var pendingForceKeyframe = false
    private var pendingForceLTR = false
    private var pendingAckTokens: [Int64] = []
    /// 스트림 프로파일·레벨 로그는 인코더당 한 번만 (콜백 스레드 전용이라 락 불필요).
    private var loggedStreamProfile = false

    init(width: Int, height: Int, codec: PeriVideoCodec = .h264, hdr: Bool = false,
         mastering: HDRMasteringInfo? = nil) {
        self.width = Int32(width)
        self.height = Int32(height)
        self.codec = codec
        // HDR10은 10비트 프로파일(Main10)을 요구한다. H.264 경로엔 대응 프로파일이 없으므로
        // 코덱이 H.264로 떨어졌으면 여기서 조용히 꺼진다 — 협상도 같은 규칙을 쓴다.
        self.hdr = hdr && (codec == .h265)
        self.mastering = self.hdr ? mastering : nil
    }

    /// keyframeIntervalSec=0 → 주기 IDR off (LTR 손실복구 + 접속 IDR에 의존). >0이면 그 간격 + 안전망.
    func start(bitrateKbps: Int = 8_000, fps: Int = 60,
               keyframeIntervalSec: Int = 0, maxFrameQP: Int? = 40) throws {
        let isHEVC = (codec == .h265)
        let codecType: CMVideoCodecType = isHEVC ? kCMVideoCodecType_HEVC
                                                 : kCMVideoCodecType_H264
        // 저지연 모드: macOS 26에서 H.264·HEVC 모두 지원(런타임 검증됨).
        let lowLatency = true

        let encoderSpec = [
            kVTVideoEncoderSpecification_EnableLowLatencyRateControl: kCFBooleanTrue!
        ] as CFDictionary

        var session: VTCompressionSession?
        let status = VTCompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            width: width, height: height, codecType: codecType,
            encoderSpecification: encoderSpec,
            imageBufferAttributes: nil, compressedDataAllocator: nil,
            outputCallback: Self.outputCallback,
            refcon: Unmanaged.passUnretained(self).toOpaque(),
            compressionSessionOut: &session
        )
        guard status == noErr, let session else { throw EncoderError.creationFailed(status) }
        self.session = session

        func set(_ key: CFString, _ value: CFTypeRef) {
            VTSessionSetProperty(session, key: key, value: value)
        }
        set(kVTCompressionPropertyKey_RealTime, kCFBooleanTrue)
        set(kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse)
        // 저지연은 High profile만 허용 (SDK 문서). 코덱별 프로파일.
        // HDR10이면 Main10 — 10비트가 아니면 PQ의 계조가 밴딩으로 무너져서 HDR이 의미가 없다.
        let profile: CFString = isHEVC
            ? (hdr ? kVTProfileLevel_HEVC_Main10_AutoLevel : kVTProfileLevel_HEVC_Main_AutoLevel)
            : kVTProfileLevel_H264_High_AutoLevel
        set(kVTCompressionPropertyKey_ProfileLevel, profile)
        if hdr {
            // 색상 태그. 이 셋이 SPS VUI에 실려 나가므로 수신측 디코더가 톤매핑을 제대로 한다.
            // 세션 속성만으로 충분함을 확인했다(입력 버퍼 어태치먼트 없이도 출력 포맷 기술자에
            // BitsPerComponent=10 / ITU_R_2020 / SMPTE_ST_2084_PQ 가 붙는다).
            set(kVTCompressionPropertyKey_ColorPrimaries, kCMFormatDescriptionColorPrimaries_ITU_R_2020)
            set(kVTCompressionPropertyKey_TransferFunction, kCMFormatDescriptionTransferFunction_SMPTE_ST_2084_PQ)
            set(kVTCompressionPropertyKey_YCbCrMatrix, kCMFormatDescriptionYCbCrMatrix_ITU_R_2020)
            // 마스터링 메타데이터. ⚠️ 이건 **와이어로 안 나간다** — VideoToolbox가 이 값을
            // CMFormatDescription에만 달고 Annex-B에 SEI로 넣지 않는 걸 확인했다(키프레임에
            // PREFIX_SEI NAL 없음). 같은 정보를 SessionParams.hdr_static_info로 따로 보낸다.
            // 여기 설정하는 건 인코더 세션을 정확히 기술해 두기 위함이다.
            if let m = mastering {
                set(kVTCompressionPropertyKey_MasteringDisplayColorVolume, m.masteringDisplayColorVolume as CFData)
                set(kVTCompressionPropertyKey_ContentLightLevelInfo, m.contentLightLevelInfo as CFData)
            }
        }
        set(kVTCompressionPropertyKey_AverageBitRate, NSNumber(value: bitrateKbps * 1000))
        set(kVTCompressionPropertyKey_ExpectedFrameRate, NSNumber(value: fps))
        // 주기 IDR은 스트림에서 가장 큰 버스트(예: 188KB)다 → Wi-Fi 큐 포화 → RTT/영상 펄싱.
        // LTR(손실 시 작은 LTR-P 복구) + 접속 시 IDR로 충분하므로 주기 IDR은 끈다(0).
        // 0이면 안전망으로만 매우 긴 간격(10분)을 둬 LTR 상태 오염 시 자가 복구 여지를 남긴다.
        let kfFrames = keyframeIntervalSec > 0 ? fps * keyframeIntervalSec : fps * 600
        set(kVTCompressionPropertyKey_MaxKeyFrameInterval, NSNumber(value: kfFrames))

        if lowLatency {
            set(kVTCompressionPropertyKey_EnableLTR, kCFBooleanTrue)   // LTR 활성화 (H.264/HEVC 공통)
        }
        if let q = maxFrameQP {
            set(kVTCompressionPropertyKey_MaxAllowedFrameQP, NSNumber(value: q))  // 텍스트 선명도
        }

        VTCompressionSessionPrepareToEncodeFrames(session)
        log.info("encoder started \(self.width)x\(self.height) codec=\(isHEVC ? "HEVC" : "H264")\(self.hdr ? " Main10/PQ/2020" : "") \(bitrateKbps)kbps lowLatency=\(lowLatency) LTR=\(lowLatency)")
    }

    func encode(_ sampleBuffer: CMSampleBuffer) {
        guard let session, let imageBuffer = sampleBuffer.imageBuffer else { return }
        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)

        optLock.lock()
        var props: [CFString: Any] = [:]
        if pendingForceKeyframe {
            props[kVTEncodeFrameOptionKey_ForceKeyFrame] = kCFBooleanTrue
            pendingForceKeyframe = false
        }
        if pendingForceLTR {
            props[kVTEncodeFrameOptionKey_ForceLTRRefresh] = kCFBooleanTrue
            pendingForceLTR = false
        }
        if !pendingAckTokens.isEmpty {
            props[kVTEncodeFrameOptionKey_AcknowledgedLTRTokens] =
                pendingAckTokens.map { NSNumber(value: $0) } as CFArray
            pendingAckTokens.removeAll()
        }
        optLock.unlock()

        let frameProps = props.isEmpty ? nil : (props as CFDictionary)
        VTCompressionSessionEncodeFrame(
            session, imageBuffer: imageBuffer, presentationTimeStamp: pts, duration: .invalid,
            frameProperties: frameProps, sourceFrameRefcon: nil, infoFlagsOut: nil
        )
    }

    /// 런타임 비트레이트 변경 (Transport-CC bitrate 적응). kbps.
    func setBitrate(_ kbps: Int) {
        guard let session else { return }
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AverageBitRate,
                             value: NSNumber(value: kbps * 1000))
    }

    /// 런타임 기대 프레임레이트 변경 (적응형 fps governor). 캡처 rate와 맞춰 rate control 정확도 유지.
    func setExpectedFrameRate(_ fps: Int) {
        guard let session, fps > 0 else { return }
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_ExpectedFrameRate,
                             value: NSNumber(value: fps))
    }

    /// 초기/강제 IDR.
    func requestKeyframe() { optLock.lock(); pendingForceKeyframe = true; optLock.unlock() }
    /// 손실 복구: ACK된 LTR 참조하는 LTR-P 생성 (없으면 IDR).
    func forceLTRRefresh() { optLock.lock(); pendingForceLTR = true; optLock.unlock() }
    /// 수신측이 ACK한 LTR 토큰 전달.
    func acknowledgeLTR(_ tokens: [Int64]) {
        guard !tokens.isEmpty else { return }
        optLock.lock(); pendingAckTokens.append(contentsOf: tokens); optLock.unlock()
    }

    func stop() {
        guard let session else { return }
        VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: .invalid)
        VTCompressionSessionInvalidate(session)
        self.session = nil
    }

    enum EncoderError: Error { case creationFailed(OSStatus) }

    /// 실제로 나가는 스트림의 프로파일·레벨·크로마를 SPS에서 읽어 한 번만 남긴다.
    ///
    /// 레벨은 `AutoLevel`이라 VideoToolbox가 고른다 — HEVC엔 레벨을 못박는 상수가 아예 없다.
    /// 우리가 해상도·fps·비트레이트를 디코더 상한 안으로 클램프하므로 고른 레벨도 그 안에
    /// 들어오지만(레벨을 정의하는 양이 바로 그 셋이다), **확인은 되어야** 한다. 다른 기기에서
    /// 재생이 안 되면 여기가 첫 번째로 볼 값이다.
    private static func logHEVCProfileLevel(_ sets: [Data]) {
        // SPS(NAL type 33) 안의 profile_tier_level 고정 위치에서 읽는다.
        //   [0..1] NAL 헤더 · [2] vps_id/max_sub_layers/nesting
        //   [3] profile_space(2)+tier(1)+profile_idc(5) · [4..7] 호환 플래그 · [8..13] 제약 플래그
        //   [14] general_level_idc
        guard let sps = sets.first(where: { $0.count > 14 && (Int($0[0]) & 0x7E) >> 1 == 33 })
        else { return }
        let profileIdc = Int(sps[3]) & 0x1F
        let tier = (Int(sps[3]) >> 5) & 0x1        // 0=Main tier, 1=High tier
        let levelIdc = Int(sps[14])
        let name = switch profileIdc {
        case 1: "Main(8bit 4:2:0)"
        case 2: "Main10(10bit 4:2:0)"
        case 3: "MainStillPicture"
        case 4: "RExt(4:2:2/4:4:4 가능)"
        default: "profile_idc=\(profileIdc)"
        }
        log.info("stream profile: \(name) tier=\(tier == 0 ? "Main" : "High") level=\(String(format: "%.1f", Double(levelIdc) / 30.0))")
    }

    // MARK: - VT output callback

    private static let outputCallback: VTCompressionOutputCallback = {
        (refcon, _, status, _, sampleBuffer) in
        guard status == noErr, let refcon, let sampleBuffer,
              CMSampleBufferDataIsReady(sampleBuffer) else { return }
        Unmanaged<VideoEncoder>.fromOpaque(refcon).takeUnretainedValue().handleEncoded(sampleBuffer)
    }

    private func handleEncoded(_ sampleBuffer: CMSampleBuffer) {
        let keyframe = Self.isKeyframe(sampleBuffer)
        let ltrToken = Self.ltrToken(sampleBuffer)
        var out = Data()

        if keyframe, let fmt = CMSampleBufferGetFormatDescription(sampleBuffer) {
            let sets = Self.parameterSets(fmt, hevc: codec == .h265)
            if codec == .h265, !loggedStreamProfile {
                loggedStreamProfile = true
                Self.logHEVCProfileLevel(sets)
            }
            for ps in sets {
                out.append(Self.startCode); out.append(ps)
            }
        }
        if let block = CMSampleBufferGetDataBuffer(sampleBuffer) {
            out.append(Self.annexB(from: block))
        }

        let ptsSec = CMSampleBufferGetPresentationTimeStamp(sampleBuffer).seconds
        let ptsMs = UInt32((ptsSec * 1000).truncatingRemainder(dividingBy: Double(UInt32.max)))
        let ptsHostUs = UInt64(max(0, ptsSec) * 1_000_000)
        onEncoded?(EncodedFrame(data: out, keyframe: keyframe, ptsMs: ptsMs,
                                ltrToken: ltrToken, ptsHostUs: ptsHostUs))
    }

    private static let startCode = Data([0x00, 0x00, 0x00, 0x01])

    private static func attachments(_ sb: CMSampleBuffer) -> [CFString: Any]? {
        (CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: false)
            as? [[CFString: Any]])?.first
    }

    private static func isKeyframe(_ sb: CMSampleBuffer) -> Bool {
        guard let a = attachments(sb) else { return true }
        if let notSync = a[kCMSampleAttachmentKey_NotSync] as? Bool { return !notSync }
        return true
    }

    /// LTR ack 토큰 (이 프레임이 LTR이면 non-nil).
    private static func ltrToken(_ sb: CMSampleBuffer) -> Int64? {
        guard let a = attachments(sb),
              let n = a[kVTSampleAttachmentKey_RequireLTRAcknowledgementToken] as? NSNumber
        else { return nil }
        return n.int64Value
    }

    private static func parameterSets(_ fmt: CMFormatDescription, hevc: Bool) -> [Data] {
        var sets: [Data] = []
        var count = 0
        if hevc {
            CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(fmt, parameterSetIndex: 0,
                parameterSetPointerOut: nil, parameterSetSizeOut: nil,
                parameterSetCountOut: &count, nalUnitHeaderLengthOut: nil)
        } else {
            CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fmt, parameterSetIndex: 0,
                parameterSetPointerOut: nil, parameterSetSizeOut: nil,
                parameterSetCountOut: &count, nalUnitHeaderLengthOut: nil)
        }
        for i in 0..<count {
            var ptr: UnsafePointer<UInt8>?
            var size = 0
            let ok: OSStatus = hevc
                ? CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(fmt, parameterSetIndex: i,
                    parameterSetPointerOut: &ptr, parameterSetSizeOut: &size,
                    parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil)
                : CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fmt, parameterSetIndex: i,
                    parameterSetPointerOut: &ptr, parameterSetSizeOut: &size,
                    parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil)
            if ok == noErr, let ptr { sets.append(Data(bytes: ptr, count: size)) }
        }
        return sets
    }

    private static func annexB(from block: CMBlockBuffer) -> Data {
        var lengthAtOffset = 0, totalLength = 0
        var dataPtr: UnsafeMutablePointer<Int8>?
        guard CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: &lengthAtOffset,
                totalLengthOut: &totalLength, dataPointerOut: &dataPtr) == noErr,
              let dataPtr else { return Data() }
        var out = Data(capacity: totalLength)
        let bytes = UnsafeRawPointer(dataPtr).assumingMemoryBound(to: UInt8.self)
        var offset = 0
        while offset + 4 <= totalLength {
            let nalLen = (Int(bytes[offset]) << 24) | (Int(bytes[offset+1]) << 16)
                       | (Int(bytes[offset+2]) << 8) | Int(bytes[offset+3])
            offset += 4
            guard nalLen > 0, offset + nalLen <= totalLength else { break }
            out.append(startCode)
            out.append(Data(bytes: bytes + offset, count: nalLen))
            offset += nalLen
        }
        return out
    }
}
