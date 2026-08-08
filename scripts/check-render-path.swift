// 진짜 H.264 비트스트림 한 장을 만들어(VideoToolbox 인코더) Annex-B로 바꾼 뒤,
// ShareRenderer가 하는 것과 **똑같은 경로**로 CMSampleBuffer까지 만들어 본다.
//
// 목적: "프레임이 도착했다"와 "그려질 수 있는 샘플이 됐다" 사이의 구간을 실제로 검사한다.
// 검은 화면의 원인이 정확히 그 구간이었고, 로그로는 전혀 안 보였다.

import AVFoundation
import CoreMedia
import VideoToolbox
import Foundation

func splitAnnexB(_ data: Data) -> [Data] {
    var out: [Data] = []
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
        } else { i += 1 }
    }
    if let s = start, s < bytes.count { out.append(Data(bytes[s...])) }
    return out.filter { !$0.isEmpty }
}

/// VideoToolbox로 한 프레임 인코딩 → AVCC를 Annex-B로 변환해 돌려준다.
func makeAnnexBKeyframe(width: Int, height: Int) -> Data? {
    var session: VTCompressionSession?
    guard VTCompressionSessionCreate(allocator: nil, width: Int32(width), height: Int32(height),
                                     codecType: kCMVideoCodecType_H264, encoderSpecification: nil,
                                     imageBufferAttributes: nil, compressedDataAllocator: nil,
                                     outputCallback: nil, refcon: nil,
                                     compressionSessionOut: &session) == noErr,
          let session else { return nil }
    VTSessionSetProperty(session, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
    VTCompressionSessionPrepareToEncodeFrames(session)

    var pool: CVPixelBuffer?
    CVPixelBufferCreate(kCFAllocatorDefault, width, height,
                        kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, nil, &pool)
    guard let px = pool else { return nil }
    CVPixelBufferLockBaseAddress(px, [])
    if let base = CVPixelBufferGetBaseAddressOfPlane(px, 0) {
        memset(base, 128, CVPixelBufferGetBytesPerRowOfPlane(px, 0) * height)
    }
    CVPixelBufferUnlockBaseAddress(px, [])

    var result: Data?
    let sem = DispatchSemaphore(value: 0)
    VTCompressionSessionEncodeFrame(
        session, imageBuffer: px, presentationTimeStamp: CMTime(value: 0, timescale: 1000),
        duration: .invalid, frameProperties: nil, infoFlagsOut: nil
    ) { status, _, sbuf in
        defer { sem.signal() }
        guard status == noErr, let sbuf,
              let fmt = CMSampleBufferGetFormatDescription(sbuf),
              let bb = CMSampleBufferGetDataBuffer(sbuf) else { return }
        var out = Data()
        // 파라미터 세트 → Annex-B
        var count = 0
        CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fmt, parameterSetIndex: 0, parameterSetPointerOut: nil,
                                                           parameterSetSizeOut: nil, parameterSetCountOut: &count,
                                                           nalUnitHeaderLengthOut: nil)
        for i in 0..<count {
            var ptr: UnsafePointer<UInt8>?
            var size = 0
            CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fmt, parameterSetIndex: i, parameterSetPointerOut: &ptr,
                                                               parameterSetSizeOut: &size, parameterSetCountOut: nil,
                                                               nalUnitHeaderLengthOut: nil)
            if let ptr {
                out.append(contentsOf: [0, 0, 0, 1])
                out.append(ptr, count: size)
            }
        }
        // AVCC 페이로드 → Annex-B
        var len = 0
        var dataPtr: UnsafeMutablePointer<Int8>?
        CMBlockBufferGetDataPointer(bb, atOffset: 0, lengthAtOffsetOut: nil,
                                    totalLengthOut: &len, dataPointerOut: &dataPtr)
        if let dataPtr {
            let raw = UnsafeRawPointer(dataPtr).assumingMemoryBound(to: UInt8.self)
            var o = 0
            while o + 4 <= len {
                let n = (Int(raw[o]) << 24) | (Int(raw[o+1]) << 16) | (Int(raw[o+2]) << 8) | Int(raw[o+3])
                o += 4
                guard n > 0, o + n <= len else { break }
                out.append(contentsOf: [0, 0, 0, 1])
                out.append(raw + o, count: n)
                o += n
            }
        }
        result = out
    }
    VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: .invalid)
    _ = sem.wait(timeout: .now() + 5)
    VTCompressionSessionInvalidate(session)
    return result
}

// ── 검사 ────────────────────────────────────────────────────────────────────

guard let annexB = makeAnnexBKeyframe(width: 320, height: 240) else {
    print("❌ 테스트 비트스트림을 못 만들었다"); exit(1)
}
print("Annex-B 키프레임 \(annexB.count)B")

let nals = splitAnnexB(annexB)
print("NAL \(nals.count)개: \(nals.map { $0.count })")

var paramSets: [Data] = []
var payload: [Data] = []
for n in nals {
    let t = (n.first ?? 0) & 0x1F
    if t == 7 || t == 8 { paramSets.append(n) } else { payload.append(n) }
}
guard paramSets.count >= 2, !payload.isEmpty else {
    print("❌ 파라미터 세트/페이로드 분리 실패 (ps=\(paramSets.count) pl=\(payload.count))"); exit(1)
}
print("✅ 파라미터 세트 \(paramSets.count)개, 페이로드 \(payload.count)개")

// 포맷 서술자
var fmt: CMFormatDescription?
var pointers: [UnsafePointer<UInt8>] = []
var sizes: [Int] = []
var holders: [UnsafeMutablePointer<UInt8>] = []
for s in paramSets {
    let b = [UInt8](s)
    let buf = UnsafeMutablePointer<UInt8>.allocate(capacity: b.count)
    buf.update(from: b, count: b.count)
    holders.append(buf); pointers.append(UnsafePointer(buf)); sizes.append(b.count)
}
let fs = CMVideoFormatDescriptionCreateFromH264ParameterSets(
    allocator: kCFAllocatorDefault, parameterSetCount: pointers.count,
    parameterSetPointers: &pointers, parameterSetSizes: &sizes,
    nalUnitHeaderLength: 4, formatDescriptionOut: &fmt)
for h in holders { h.deallocate() }
guard fs == noErr, let fmt else { print("❌ 포맷 서술자 실패 \(fs)"); exit(1) }
print("✅ 포맷 서술자 \(CMVideoFormatDescriptionGetDimensions(fmt))")

// Annex-B → AVCC
var avcc = Data()
for n in payload {
    var l = UInt32(n.count).bigEndian
    withUnsafeBytes(of: &l) { avcc.append(contentsOf: $0) }
    avcc.append(n)
}

// ★ 여기가 검은 화면의 자리였다.
guard let block = try? CMBlockBuffer(length: avcc.count, flags: .assureMemoryNow) else {
    print("❌ 블록 버퍼 생성 실패"); exit(1)
}
let wrote: ()? = try? avcc.withUnsafeBytes { raw in
    try block.withUnsafeMutableBytes { dst in dst.copyMemory(from: raw) }
}
guard wrote != nil else { print("❌ 블록 버퍼 쓰기 실패 — 검은 화면의 그 버그"); exit(1) }
print("✅ 블록 버퍼 \(avcc.count)B 기록")

var timing = CMSampleTimingInfo(duration: .invalid,
                                presentationTimeStamp: CMTime(value: 0, timescale: 1000),
                                decodeTimeStamp: .invalid)
var size = avcc.count
var sample: CMSampleBuffer?
let st = CMSampleBufferCreateReady(allocator: kCFAllocatorDefault, dataBuffer: block,
                                   formatDescription: fmt, sampleCount: 1,
                                   sampleTimingEntryCount: 1, sampleTimingArray: &timing,
                                   sampleSizeEntryCount: 1, sampleSizeArray: &size,
                                   sampleBufferOut: &sample)
guard st == noErr, let sample else { print("❌ 샘플 버퍼 실패 \(st)"); exit(1) }
print("✅ 샘플 버퍼 생성")

if let attach = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true)
    as? [CFMutableDictionary], let first = attach.first {
    CFDictionarySetValue(first,
        Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
        Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
    print("✅ DisplayImmediately 부착")
} else {
    print("❌ 첨부 배열 캐스팅 실패 — 즉시 표시가 안 걸린다"); exit(1)
}

// 실제로 디코드까지 되는지 — 샘플이 '그려질 수 있는' 모양인지의 최종 증거
let sem = DispatchSemaphore(value: 0)
var decoded = false
var dsession: VTDecompressionSession?
var cb = VTDecompressionOutputCallbackRecord()
VTDecompressionSessionCreate(allocator: nil, formatDescription: fmt,
                             decoderSpecification: nil, imageBufferAttributes: nil,
                             outputCallback: &cb, decompressionSessionOut: &dsession)
if let dsession {
    VTDecompressionSessionDecodeFrame(dsession, sampleBuffer: sample, flags: [._EnableAsynchronousDecompression],
                                      infoFlagsOut: nil) { status, _, image, _, _ in
        decoded = (status == noErr && image != nil)
        sem.signal()
    }
    _ = sem.wait(timeout: .now() + 5)
    VTDecompressionSessionInvalidate(dsession)
}
print(decoded ? "✅ 디코드 성공 — 그려질 수 있는 샘플이다" : "❌ 디코드 실패")
exit(decoded ? 0 : 1)
