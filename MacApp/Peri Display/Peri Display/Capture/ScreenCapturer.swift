//
//  ScreenCapturer.swift
//  Peri Display
//
//  ScreenCaptureKit으로 지정한 디스플레이를 캡처해 CMSampleBuffer(BGRA)를 흘려보낸다.
//  화면 녹화 권한은 첫 캡처 시 macOS가 TCC 프롬프트로 요청한다.
//
//  ★ 이 파일의 불변식: **캡처러 하나당 살아 있는 스트림은 하나뿐이고, 그 스트림에서 온 프레임만
//  밖으로 나간다.** addStreamOutput(self,…)라 이 인스턴스가 만든 모든 스트림의 출력 대상이 self
//  하나이므로, 정지에 실패한 고아 스트림이 남아 있으면 현재 세션의 인코더로 프레임을 밀어 넣는다.
//  또 고아 스트림은 살아만 있어도 macOS '공유 중' 목록을 채우고 GPU·인코더를 계속 먹는다.
//  → start가 항상 이전 스트림을 먼저 정지시키고(누수 차단), 세대 카운터로 진행 중인 start를
//    무효화하며(정지 후 뒤늦은 설치 차단), 출력 콜백이 출처를 확인한다(최후 방어선).
//
//  참고: 멀티 기기 "다른 기기 화면 송출"의 진짜 원인은 이 파일이 아니라 ScreenCaptureKit 버그였다
//  (FB17797423 — 가상 디스플레이 구성이 바뀌면 이후 SCStream이 '마지막에 연결된' 가상 스크린의
//  프레임버퍼를 물고 온다). 우회는 DisplayManager.recycleForSCK 참조.
//

import Foundation
import ScreenCaptureKit
import CoreMedia
import OSLog

private nonisolated let log = Logger(subsystem: "com.peri.display.mac", category: "capture")

/// 캡처된 한 프레임의 메타 + 픽셀버퍼.
nonisolated struct CapturedFrame {
    let sampleBuffer: CMSampleBuffer
    let width: Int
    let height: Int
}

nonisolated final class ScreenCapturer: NSObject, @unchecked Sendable {
    private let outputQueue = DispatchQueue(label: "com.peri.display.capture.output")

    /// 캡처 콜백. 캡처 큐에서 호출됨.
    var onFrame: (@Sendable (CapturedFrame) -> Void)?

    private(set) var width = 0
    private(set) var height = 0

    // start/stop이 서로 다른 실행자에서 겹칠 수 있고(둘 다 nonisolated async), 출력 콜백은
    // 캡처 큐에서 온다 → 스트림/세대는 락으로 보호한다.
    private let lock = NSLock()
    private var _stream: SCStream?
    /// start/stop마다 증가. 진행 중인 start는 자기 세대가 뒤처지면 스스로를 폐기한다.
    private var _generation = 0
    // 적응형 fps용: start에서 config 저장 → updateFrameRate가 minimumFrameInterval만 라이브 교체.
    private var _config: SCStreamConfiguration?
    private var _currentFPS = 60
    /// 첫 프레임의 실제 픽셀 포맷을 스트림마다 한 번만 로그한다 — 요청한 포맷과 SCK가 실제로
    /// 주는 포맷이 다를 수 있고(특히 HDR), 그게 다르면 인코더 입력 가정이 통째로 틀어진다.
    private var _formatLogged = false

    /// 지정한 displayID 캡처 시작. 권한이 없거나 대상이 안 나타나면 throw.
    /// pixelWidth/Height = 출력 픽셀 크기 (가상 디스플레이 해상도와 일치시킨다).
    ///
    /// hdr: 10비트 HDR로 캡처한다. **Apple Silicon 전용**이고(Intel에선 조용히 무시된다),
    /// 대상 디스플레이가 실제로 EDR 헤드룸을 갖고 있어야 의미가 있다 — 호출자가 판정해서 넘긴다.
    func start(displayID: CGDirectDisplayID, pixelWidth: Int, pixelHeight: Int,
               maxFPS: Int = 60, hdr: Bool = false) async throws {
        // ★ 한 캡처러에 살아 있는 스트림은 항상 하나뿐이어야 한다.
        // 예전엔 install()이 _stream을 덮어쓰기만 해서 이전 스트림이 *계속 캡처하며* 남았다.
        // 세대 검사 덕에 그 프레임이 인코더로 가진 않지만, 스트림은 여전히 살아 있어
        // macOS '공유 중' 목록에 유령이 쌓이고 GPU·인코더 자원을 계속 먹는다(fps governor가
        // 바닥까지 내려가던 원인). 시작 전에 반드시 정리한다.
        await stop()
        let gen = bumpGeneration()

        // 가상 디스플레이는 생성 직후 SCShareableContent에 아직 안 올라와 있을 수 있다(등록 지연).
        // 폴백 없이 요청한 displayID가 나타날 때까지 기다린다(최대 ~3s) — 못 찾으면 실패시켜
        // 잘못된 화면을 송출하느니 끊는다. 이 대기 중에 세션이 내려갈 수 있으므로 매 회 세대 확인.
        var target: SCDisplay?
        for _ in 0..<30 {
            guard isCurrent(gen) else { throw CaptureError.superseded }
            let content = try await SCShareableContent.excludingDesktopWindows(
                false, onScreenWindowsOnly: true
            )
            if let d = content.displays.first(where: { $0.displayID == displayID }) { target = d; break }
            try? await Task.sleep(for: .milliseconds(100))
        }
        guard let display = target else {
            log.error("capture 대상 displayID=\(displayID) 를 SCShareableContent에서 못 찾음")
            throw CaptureError.noDisplay
        }
        guard isCurrent(gen) else { throw CaptureError.superseded }

        let filter = SCContentFilter(display: display, excludingWindows: [])

        // HDR이면 Apple이 권장하는 조합(captureDynamicRange + pixelFormat + colorSpace + colorMatrix)을
        // 프리셋에서 통째로 받아 온다 — 이 넷은 서로 맞물려 있어서 하나만 골라 넣으면 어긋난다.
        // canonical(local이 아니라): 우리 소비자는 캡처한 디스플레이가 아니라 **폰**이므로,
        // 특정 디스플레이에 맞춘 톤매핑이 아니라 일반 HDR 디스플레이 기준 출력이 맞다.
        let config = hdr
            ? SCStreamConfiguration(preset: .captureHDRStreamCanonicalDisplay)
            : SCStreamConfiguration()
        config.width = pixelWidth
        config.height = pixelHeight
        if hdr {
            // 프리셋 기본은 DisplayP3_PQ다 — 이 Mac 화면이 P3라서 고른 값이지, 우리 소비자(폰)
            // 기준이 아니다. 그대로 두면 **P3 픽셀에 Rec.2020 딱지**가 붙는다:
            // VideoToolbox는 픽셀 포맷 변환은 해도 색역 매핑은 하지 않고 태그만 갈아끼우기 때문에,
            // 수신측이 P3 좌표를 2020으로 해석해 색이 옅어진다.
            // HDR10의 정의가 Rec.2020 + PQ이고 Android MediaCodec도 COLOR_STANDARD_BT2020만
            // 아니까, 캡처 단계에서 2020으로 합성시켜 인코더 태그와 일치시킨다.
            config.colorSpaceName = CGColorSpace.itur_2100_PQ
        } else {
            config.pixelFormat = kCVPixelFormatType_32BGRA
            // SDR도 색공간을 **명시**한다. 예전엔 비워 뒀는데, 그건 "디스플레이가 sRGB/709"라는
            // 가정에 조용히 기대는 것이었다. 그 가정이 깨질 수 있다:
            // HDR로 만든 디스플레이(16bpc · PQ · P3)인데 EDR 헤드룸을 못 받아 스트림만 SDR로
            // 강등되는 경우가 있다. 그 프레임버퍼를 태그 없이 BGRA로 뜨면 PQ 값이 감마로
            // 해석되어 화면이 통째로 뒤틀린다.
            // sRGB로 못박으면 SCK가 변환을 수행하므로 디스플레이가 어느 모드든 결과가 같다
            // — 수신측의 BT.709 가정과도 맞다(sRGB와 709는 원색·백색점이 동일).
            config.colorSpaceName = CGColorSpace.sRGB
        }
        config.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(maxFPS))
        config.queueDepth = 5
        config.showsCursor = true

        let stream = SCStream(filter: filter, configuration: config, delegate: self)
        try stream.addStreamOutput(self, type: .screen, sampleHandlerQueue: outputQueue)
        try await stream.startCapture()

        // startCapture를 기다리는 동안 정지·재시작이 있었으면 이 스트림은 고아다.
        // 설치하지 않고 즉시 되돌린다 — 살려두면 남의 화면을 실어 나른다.
        let placed = install(stream, config: config, fps: maxFPS,
                             w: config.width, h: config.height, gen: gen)
        if let evicted = placed.evicted {
            log.warning("capture: 설치 중 남아 있던 이전 스트림 정지 (누수 방지)")
            try? await evicted.stopCapture()
        }
        guard placed.ok else {
            try? await stream.stopCapture()
            log.notice("capture start 폐기 (세대 교체됨) display=\(displayID)")
            throw CaptureError.superseded
        }
        log.notice("capture started \(pixelWidth)x\(pixelHeight) @\(maxFPS)fps display=\(displayID)")
    }

    /// 캡처 프레임레이트만 라이브 교체(스트림 재시작 없음). 적응형 fps governor가 호출.
    func updateFrameRate(_ fps: Int) {
        lock.lock()
        guard let stream = _stream, let config = _config, fps > 0, fps != _currentFPS else {
            lock.unlock(); return
        }
        _currentFPS = fps
        config.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(fps))
        lock.unlock()
        stream.updateConfiguration(config) { error in
            if let error {
                log.error("updateConfiguration(\(fps)fps) failed: \(error.localizedDescription)")
            }
        }
    }

    /// 캡처 정지. 진행 중인 start가 있으면 세대를 올려 그쪽이 스스로 폐기하게 만든다 —
    /// 예전엔 stream이 아직 nil이면 그냥 리턴해서, 뒤늦게 끝난 start가 좀비 스트림을 설치했다.
    func stop() async {
        _ = bumpGeneration()
        guard let stream = takeStream() else { return }
        try? await stream.stopCapture()
        log.notice("capture stopped")
    }

    enum CaptureError: Error {
        case noDisplay
        /// 시작 도중 정지/재시작이 일어나 이 시도는 폐기됨 (오류가 아니라 정상 취소).
        case superseded
    }

    // MARK: - 상태 접근 (락 보호)

    private func bumpGeneration() -> Int {
        lock.lock(); defer { lock.unlock() }
        _generation += 1
        return _generation
    }

    private func isCurrent(_ gen: Int) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return gen == _generation
    }

    /// 세대가 아직 유효할 때만 스트림을 설치한다. 실패하면 호출자가 그 스트림을 정지시켜야 한다.
    /// 설치 시점에 다른 스트림이 남아 있으면 (있어선 안 되지만) 반환해 호출자가 정지시킨다.
    private func install(_ s: SCStream, config: SCStreamConfiguration,
                         fps: Int, w: Int, h: Int, gen: Int) -> (ok: Bool, evicted: SCStream?) {
        lock.lock(); defer { lock.unlock() }
        guard gen == _generation else { return (false, nil) }
        let evicted = _stream
        _stream = s
        _config = config
        _currentFPS = fps
        _formatLogged = false
        width = w
        height = h
        return (true, evicted)
    }

    private func takeStream() -> SCStream? {
        lock.lock(); defer { lock.unlock() }
        let s = _stream
        _stream = nil
        return s
    }

    /// 출력 콜백이 출처를 확인하는 데 쓴다.
    private func isActive(_ s: SCStream) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return _stream === s
    }

    /// 스트림마다 첫 프레임에서만 true — 실제 픽셀 포맷 로그용.
    private func claimFormatLog() -> Bool {
        lock.lock(); defer { lock.unlock() }
        if _formatLogged { return false }
        _formatLogged = true
        return true
    }

}

/// OSType(FourCC)을 사람이 읽는 문자열로. 'x420' 같은 값이 그대로 보여야 진단이 된다.
private func fourCC(_ v: OSType) -> String {
    let b = [UInt8((v >> 24) & 0xff), UInt8((v >> 16) & 0xff), UInt8((v >> 8) & 0xff), UInt8(v & 0xff)]
    return String(bytes: b, encoding: .ascii) ?? "\(v)"
}

// MARK: - SCStreamOutput

nonisolated extension ScreenCapturer: SCStreamOutput {
    nonisolated func stream(_ stream: SCStream,
                            didOutputSampleBuffer sampleBuffer: CMSampleBuffer,
                            of type: SCStreamOutputType) {
        // ★ 최후 방어선: 현재 스트림이 아니면 버린다. 정지에 실패했거나 교체된 스트림이
        // 남아 있어도 그 프레임은 절대 인코더로 가지 않는다 — 남의 화면 송출의 마지막 관문.
        guard isActive(stream) else { return }
        guard type == .screen, sampleBuffer.isValid else { return }

        // 완성된 프레임만 통과 (가려짐/유휴 프레임 스킵)
        guard let attachments = CMSampleBufferGetSampleAttachmentsArray(
                sampleBuffer, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
              let info = attachments.first,
              let statusRaw = info[.status] as? Int,
              let status = SCFrameStatus(rawValue: statusRaw),
              status == .complete else {
            return
        }

        guard let pb = sampleBuffer.imageBuffer else { return }
        let w = CVPixelBufferGetWidth(pb)
        let h = CVPixelBufferGetHeight(pb)
        if claimFormatLog() {
            let fmt = fourCC(CVPixelBufferGetPixelFormatType(pb))
            let tf = CVBufferCopyAttachment(pb, kCVImageBufferTransferFunctionKey, nil) as? String ?? "-"
            let pr = CVBufferCopyAttachment(pb, kCVImageBufferColorPrimariesKey, nil) as? String ?? "-"
            // 마스터링 휘도: SCK가 붙여 주면 그대로 인코더에 넘기면 되고(추정 불필요),
            // 안 붙여 주면 우리가 EDR 헤드룸에서 계산해 넣어야 한다. 어느 쪽인지부터 확인한다.
            let mdcv = CVBufferCopyAttachment(pb, kCVImageBufferMasteringDisplayColorVolumeKey, nil) as? Data
            let cll = CVBufferCopyAttachment(pb, kCVImageBufferContentLightLevelInfoKey, nil) as? Data
            log.notice("capture 첫 프레임 \(w)x\(h) pixelFormat=\(fmt) primaries=\(pr) transfer=\(tf) | mastering=\(mdcv.map { "\($0.count)B" } ?? "없음") contentLightLevel=\(cll.map { "\($0.count)B" } ?? "없음")")
        }
        onFrame?(CapturedFrame(sampleBuffer: sampleBuffer, width: w, height: h))
    }
}

// MARK: - SCStreamDelegate

nonisolated extension ScreenCapturer: SCStreamDelegate {
    nonisolated func stream(_ stream: SCStream, didStopWithError error: Error) {
        log.error("stream stopped with error: \(error.localizedDescription)")
    }
}
