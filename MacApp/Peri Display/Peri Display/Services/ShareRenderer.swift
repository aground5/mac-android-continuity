//
//  ShareRenderer.swift
//  Peri Display
//
//  **Android가 보낸 인코딩된 프레임을 화면에 그린다.**
//
//  `VTDecompressionSession`을 직접 몰지 않는다. `AVSampleBufferDisplayLayer`가 인코딩된
//  `CMSampleBuffer`를 그대로 받아 디코드·표시·타이밍까지 해 준다 — 우리가 할 일은 Annex-B
//  비트스트림을 AVCC(길이 접두)로 바꾸고 파라미터 세트로 포맷 서술자를 만드는 것뿐이다.
//
//  왜 Annex-B → AVCC 변환이 필요한가
//  ─────────────────────────────────
//  Android의 MediaCodec은 시작 코드(00 00 00 01)로 NAL을 구분한 Annex-B를 뱉는데,
//  CoreMedia는 4바이트 길이 접두를 기대한다. 둘은 같은 데이터의 다른 포장이다.
//  파라미터 세트(SPS/PPS/VPS)는 페이로드에 남기지 않고 **포맷 서술자로 올린다** —
//  거기 남겨 두면 디코더가 알 수 없는 NAL로 보고 프레임을 통째로 버린다.
//
//  ★ **메인 액터에 있지 않다.** 프레임은 전송 큐(LAN/AOA)에서 도착하는데, 거기서 메인으로
//  넘기면 프레임마다 홉이 하나 붙고 — 더 나쁜 건 `Task`들 사이의 **순서가 보장되지 않는다**는
//  점이다. 디코더에 프레임이 뒤바뀌어 들어가면 참조가 깨져 화면이 뭉개진다.
//  그래서 이 클래스는 자기 락으로 스스로를 지키고, 어느 스레드에서 불려도 된다.
//  (`AVSampleBufferDisplayLayer`는 직렬화만 지키면 배경 큐에서 먹여도 되는 레이어다.)
//

import AVFoundation
import CoreMedia
import Foundation
import OSLog

private nonisolated let log = Logger(subsystem: "com.peri.display.mac", category: "share")

nonisolated final class ShareRenderer: @unchecked Sendable {

    /// 뷰가 이 레이어를 그대로 얹는다. 붙이는 일은 메인에서, 먹이는 일은 전송 큐에서 —
    /// 둘이 겹치지 않으므로 참조를 그냥 내준다.
    let layer = AVSampleBufferDisplayLayer()

    private let lock = NSLock()
    private var codec: PeriVideoCodec = .h265
    private var format: CMFormatDescription?
    /// 파라미터 세트가 모이기 전에는 아무것도 못 푼다 — 키프레임을 기다린다.
    private var sawKeyframe = false
    /// 레이어에 샘플을 한 번이라도 넣었는가 — 첫 투입만 로그로 알린다.
    private var didEnqueue = false

    init() {
        layer.videoGravity = .resizeAspect
        layer.preventsDisplaySleepDuringVideoPlayback = false
    }

    func configure(codec: PeriVideoCodec) {
        lock.lock(); defer { lock.unlock() }
        guard codec != self.codec else { return }
        self.codec = codec
        resetLocked()
    }

    func start() {
        lock.lock(); resetLocked(); lock.unlock()
    }

    func stop() {
        lock.lock(); resetLocked(); lock.unlock()
        layer.flush()
    }

    private func resetLocked() {
        format = nil
        sawKeyframe = false
        didEnqueue = false
    }

    /// 완성된 접근 단위(AU) 하나. Annex-B 시작 코드로 구분된 NAL들이 들어 있다.
    /// **호출자가 직렬화한다**(전송 큐 하나) — 이 락은 메인의 start/stop/configure와만 겨룬다.
    func enqueue(_ annexB: Data, keyframe: Bool, timestampMs: UInt32) {
        let nals = Self.splitAnnexB(annexB)
        guard !nals.isEmpty else { return }

        // 파라미터 세트는 포맷 서술자로 올리고 페이로드에서는 뺀다.
        var paramSets: [Data] = []
        var payload: [Data] = []

        lock.lock()
        for nal in nals {
            if isParameterSetLocked(nal) { paramSets.append(nal) } else { payload.append(nal) }
        }
        if !paramSets.isEmpty, let f = makeFormatLocked(paramSets) { format = f }
        let format = self.format
        if !sawKeyframe, keyframe, format != nil { sawKeyframe = true }
        let ready = sawKeyframe
        lock.unlock()

        guard let format, ready else { return }   // 아직 SPS/PPS를 못 봤거나 P 프레임부터 왔다
        guard !payload.isEmpty else { return }

        // Annex-B → AVCC (4바이트 빅엔디언 길이 접두)
        var avcc = Data()
        avcc.reserveCapacity(payload.reduce(0) { $0 + $1.count + 4 })
        for nal in payload {
            var len = UInt32(nal.count).bigEndian
            withUnsafeBytes(of: &len) { avcc.append(contentsOf: $0) }
            avcc.append(nal)
        }

        // ★ `.assureMemoryNow`가 **필수다.** 빼면 `CMBlockBuffer`는 길이만 들고 메모리는
        // "필요할 때" 잡는 상태로 생기고(헤더: "If NULL, a memory block will be allocated
        // when needed"), 그 상태에서 `withUnsafeMutableBytes`는 실패한다.
        //
        // 이게 검은 화면의 원인이었다. 실패를 조용히 삼켜서 **모든 프레임이 디코더에 닿기도
        // 전에 버려졌는데**, 수신·복호·재조립은 전부 정상이라 로그에는 아무 이상도 안 보였다.
        // 그래서 아래 실패 경로는 이제 로그를 남긴다 — 같은 침묵을 두 번 겪지 않으려고.
        guard let block = try? CMBlockBuffer(length: avcc.count, flags: .assureMemoryNow) else {
            log.error("블록 버퍼 생성 실패 (\(avcc.count)B)")
            return
        }
        let ok: ()? = try? avcc.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            try block.withUnsafeMutableBytes { dst in
                dst.copyMemory(from: raw)
            }
        }
        guard ok != nil else {
            log.error("블록 버퍼 쓰기 실패 (\(avcc.count)B)")
            return
        }

        var timing = CMSampleTimingInfo(
            duration: .invalid,
            presentationTimeStamp: CMTime(value: CMTimeValue(timestampMs), timescale: 1000),
            decodeTimeStamp: .invalid)
        var size = avcc.count
        var sample: CMSampleBuffer?
        let status = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format,
            sampleCount: 1, sampleTimingEntryCount: 1, sampleTimingArray: &timing,
            sampleSizeEntryCount: 1, sampleSizeArray: &size, sampleBufferOut: &sample)
        guard status == noErr, let sample else { return }

        // 도착 즉시 표시 — 지터버퍼를 두지 않는다. 화면 공유는 저지연이 곧 품질이고,
        // 여기서 버퍼를 두면 확장 경로에서 공들여 줄인 지연이 되살아난다.
        if let attach = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true)
            as? [CFMutableDictionary], let first = attach.first {
            CFDictionarySetValue(first,
                Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }

        // 실패한 레이어는 비워야 다시 받는다. 비운 뒤에는 파라미터 세트부터 다시 세워야
        // 하므로 키프레임 대기로 되돌린다 — 안 그러면 참조 없는 P 프레임을 계속 먹인다.
        if layer.status == .failed {
            log.error("표시 레이어 실패: \(String(describing: self.layer.error)) — 비우고 키프레임부터")
            layer.flush()
            lock.lock(); resetLocked(); lock.unlock()
            return
        }
        layer.enqueue(sample)

        // **실제로 레이어에 들어간 첫 장**을 알린다. "프레임이 도착했다"와 "그려질 수 있는
        // 모양으로 들어갔다"는 다른 사실이고, 그 둘을 구분 못 해서 원인을 한참 찾았다.
        lock.lock()
        let first = !didEnqueue
        if first { didEnqueue = true }
        lock.unlock()
        if first { log.notice("공유 표시 시작 — 첫 샘플 투입 (\(avcc.count)B)") }
    }

    // MARK: - 비트스트림 다루기

    private func isParameterSetLocked(_ nal: Data) -> Bool {
        guard let first = nal.first else { return false }
        if codec == .h265 {
            let type = (first >> 1) & 0x3F
            return type == 32 || type == 33 || type == 34    // VPS · SPS · PPS
        } else {
            let type = first & 0x1F
            return type == 7 || type == 8                    // SPS · PPS
        }
    }

    private func makeFormatLocked(_ sets: [Data]) -> CMFormatDescription? {
        // 같은 종류가 여러 번 오면 마지막 것만 쓴다(재구성 후 새 SPS가 앞의 것을 대체한다).
        var byType: [UInt8: Data] = [:]
        for s in sets {
            guard let f = s.first else { continue }
            byType[codec == .h265 ? (f >> 1) & 0x3F : f & 0x1F] = s
        }
        let ordered: [Data]
        if codec == .h265 {
            guard let vps = byType[32], let sps = byType[33], let pps = byType[34] else { return nil }
            ordered = [vps, sps, pps]
        } else {
            guard let sps = byType[7], let pps = byType[8] else { return nil }
            ordered = [sps, pps]
        }

        var out: CMFormatDescription?
        let ptrs = ordered.map { [UInt8]($0) }
        var pointers: [UnsafePointer<UInt8>] = []
        var sizes: [Int] = []
        var holders: [UnsafeMutablePointer<UInt8>] = []
        for bytes in ptrs {
            let buf = UnsafeMutablePointer<UInt8>.allocate(capacity: bytes.count)
            buf.update(from: bytes, count: bytes.count)
            holders.append(buf)
            pointers.append(UnsafePointer(buf))
            sizes.append(bytes.count)
        }
        defer { for h in holders { h.deallocate() } }

        let status: OSStatus = codec == .h265
            ? CMVideoFormatDescriptionCreateFromHEVCParameterSets(
                allocator: kCFAllocatorDefault, parameterSetCount: pointers.count,
                parameterSetPointers: &pointers, parameterSetSizes: &sizes,
                nalUnitHeaderLength: 4, extensions: nil, formatDescriptionOut: &out)
            : CMVideoFormatDescriptionCreateFromH264ParameterSets(
                allocator: kCFAllocatorDefault, parameterSetCount: pointers.count,
                parameterSetPointers: &pointers, parameterSetSizes: &sizes,
                nalUnitHeaderLength: 4, formatDescriptionOut: &out)
        if status != noErr { log.error("포맷 서술자 생성 실패: \(status)") }
        return status == noErr ? out : nil
    }

    /// Annex-B 시작 코드(3 또는 4바이트)로 NAL을 가른다.
    nonisolated static func splitAnnexB(_ data: Data) -> [Data] {
        var out: [Data] = []
        // ⚠ `bytes`는 0부터 시작하는 **새 배열**이다. 예전엔 `data.startIndex`로 훑기
        // 시작했는데, 슬라이스(비-0 시작 인덱스)가 들어오면 그대로 범위를 벗어난다.
        // 지금 호출부는 CryptoKit이 만든 Data(시작 0)라 안 터졌을 뿐이다.
        var i = 0
        var start: Int?
        let bytes = [UInt8](data)
        while i < bytes.count - 2 {
            if bytes[i] == 0, bytes[i + 1] == 0,
               (bytes[i + 2] == 1 || (i + 3 < bytes.count && bytes[i + 2] == 0 && bytes[i + 3] == 1)) {
                let scLen = bytes[i + 2] == 1 ? 3 : 4
                if let s = start, i > s { out.append(Data(bytes[s..<i])) }
                start = i + scLen
                i += scLen
            } else {
                i += 1
            }
        }
        if let s = start, s < bytes.count { out.append(Data(bytes[s...])) }
        return out.filter { !$0.isEmpty }
    }
}
