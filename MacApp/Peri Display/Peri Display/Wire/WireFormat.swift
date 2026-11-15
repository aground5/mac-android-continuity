//
//  WireFormat.swift
//  Peri Display
//
//  와이어 포맷 (DESIGN.md §2 + §3). 프레임: [4B big-endian length N][1B type][payload].
//
//    type 0 = HANDSHAKE : payload = plaintext serialized ControlMessage
//                         (ClientHello/ServerHello — 키 합의 전이라 평문)
//    type 1 = CONTROL   : payload = [12B nonce][ciphertext||tag]
//                         → 복호화 시 ControlMessage
//    type 2 = VIDEO     : payload = [12B VideoHeader][12B nonce][ciphertext||tag]
//                         → 복호화 시 encoded bitstream (헤더는 평문, 재조립용)
//
//  슬라이스 단계: 영상도 동일 TCP 연결로 전송(이후 UDP 분리). 단편화 frag_total=1.
//

import Foundation
import SwiftProtobuf

nonisolated enum WireType: UInt8 {
    case handshake = 0
    case control = 1
    case video = 2   // (TCP 슬라이스 잔재; 영상은 UDP로 이전)
    /// Android → Mac, 새 TCP 연결의 **첫 프레임**: [12B nonce][sealed(16B 펀치 토큰)].
    ///
    /// 핸드오버(LTE↔Wi-Fi)로 IP가 바뀌면 TCP는 4-tuple이 깨져 못 살린다. 하지만 살릴
    /// 필요가 있는 건 연결이 아니라 **세션**이고, 세션(AEAD 키·가상 디스플레이·인코더)은
    /// 전송에 독립적이다. 이 프레임은 "새로 뚫은 이 연결은 그 세션의 것"이라는 주장이고,
    /// Mac은 살아 있는 세션들의 수신키로 시도복호화해서 검증한다(UDP HELLO 디먹스와 동일한 방식).
    /// 통과하면 핸드셰이크 없이 제어 채널만 갈아끼운다 — 재협상도, 키프레임도, 검은 화면도 없다.
    case resume = 3
}

/// UDP 데이터그램 타입 (길이 프리픽스 없음 — 데이터그램 경계 = 패킷).
/// 2·3은 `IceUdpType`(PROBE/PROBE_ACK)이 쓴다 — 겹치면 안 된다.
nonisolated enum UdpType: UInt8 {
    /// Android → Mac : [1B 0][16B channel_id][12B nonce][sealed(channel_id)]
    ///
    /// **채널 id가 평문으로 앞에 온다.** 데이터그램은 연결이 없어 "누가 보냈나"를 스스로
    /// 말하지 못하는데, 그 답이 곧 **어느 키로 열어야 하는가**다. 예전엔 살아 있는 세션들의
    /// 키로 하나씩 시도복호화해 알아냈다 — 세션이 몇 개 안 되니 값은 쌌지만, 답을 아는
    /// 방법이 "다 해 보기"였다. 이제 Mac이 세대마다 부여한 이름이 붙어 오므로 조회 한 번이다.
    ///
    /// 평문 id로 무엇이 새는가: 같은 세대의 데이터그램들이 서로 묶인다는 것뿐이다. 그건
    /// 어차피 출발지 주소로 이미 묶이고, id는 세대마다 새 난수라 기기를 가리키지 않는다.
    /// 그 뒤의 봉인이 진짜 검증이다 — id만 베껴 봐야 열리지 않는다.
    case hello = 0
    case video = 1   // Mac → Android : [1B 1][12B VideoHeader][12B nonce][chunk]
    /// Android → Mac : 경로 생사 확인. 와이어 모양은 `hello`와 동일(피어 재등록 겸용) —
    /// 타입 바이트만 다르다. Mac은 이걸 받으면 [pong]으로 답한다.
    case ping = 4
    /// Mac → Android : `ping`의 답. `[1B 5][12B nonce][sealed(세대 id)]` — HELLO의 봉인과
    /// 같은 평문(세대 id)을 실어, Android가 늦게 온 지난 세대의 PONG을 걸러낼 수 있게 한다.
    case pong = 5
}

/// 영상 UDP 단편화 파라미터.
nonisolated enum UdpVideo {
    /// 데이터그램당 최대 청크 바이트 (헤더 제외, 보수적 MTU).
    static let maxChunk = 1100
}

/// Peri Mini-RTP 헤더 (16B). RTP 핵심만 추림 (DESIGN). nonce는 frame_id로 유도(미전송).
/// byte0: [V:2][K:1][M:1][PT:4]  byte1: [.. isLtr:bit0]
/// 2–3 packet_seq | 4–7 frame_id | 8–9 frag_index | 10–11 frag_count | 12–15 timestamp_ms
nonisolated struct MiniRtpHeader: Sendable {
    var keyframe: Bool
    var marker: Bool            // 프레임의 마지막 프래그먼트
    var payloadType: UInt8      // 0 H264 / 1 H265
    var isLtr: Bool             // 이 프레임은 LTR → 수신측 ACK 필요
    var packetSeq: UInt16       // 전역 패킷 시퀀스 (손실 감지)
    var frameId: UInt32
    var fragIndex: UInt16
    var fragCount: UInt16
    var timestampMs: UInt32

    static let byteCount = 16
    static let version: UInt8 = 1

    func encode() -> Data {
        var d = Data(capacity: Self.byteCount)
        let b0 = (Self.version << 6) | (UInt8(keyframe ? 1 : 0) << 5)
               | (UInt8(marker ? 1 : 0) << 4) | (payloadType & 0x0F)
        d.append(b0)
        d.append(isLtr ? 0x01 : 0x00)
        d.appendBE(packetSeq)
        d.appendBE(frameId)
        d.appendBE(fragIndex)
        d.appendBE(fragCount)
        d.appendBE(timestampMs)
        return d
    }
}

/// 영상 12바이트 평문 헤더. [1B flags][3B seq][4B timestamp_ms][2B frag_index][2B frag_total]
nonisolated struct VideoHeader: Equatable, Sendable {
    var keyframe: Bool
    var codec: PeriVideoCodec
    var seq: UInt32
    var timestampMs: UInt32
    var fragIndex: UInt16
    var fragTotal: UInt16

    static let byteCount = 12

    private var flags: UInt8 {
        var f: UInt8 = keyframe ? 0x01 : 0x00
        f |= (Self.codecBits(codec) << 1)
        return f
    }
    private static func codecBits(_ c: PeriVideoCodec) -> UInt8 {
        switch c { case .h264: return 0; case .h265: return 1; case .vp9: return 2; default: return 0 }
    }
    private static func codec(fromBits bits: UInt8) -> PeriVideoCodec {
        switch bits & 0x03 { case 0: return .h264; case 1: return .h265; case 2: return .vp9; default: return .h264 }
    }

    func encode() -> Data {
        var d = Data(capacity: Self.byteCount)
        d.append(flags)
        d.append(UInt8((seq >> 16) & 0xFF)); d.append(UInt8((seq >> 8) & 0xFF)); d.append(UInt8(seq & 0xFF))
        d.appendBE(timestampMs); d.appendBE(fragIndex); d.appendBE(fragTotal)
        return d
    }

    static func decode(_ d: Data) -> VideoHeader? {
        guard d.count >= byteCount else { return nil }
        let b = [UInt8](d.prefix(byteCount))
        let seq = (UInt32(b[1]) << 16) | (UInt32(b[2]) << 8) | UInt32(b[3])
        let ts = (UInt32(b[4]) << 24) | (UInt32(b[5]) << 16) | (UInt32(b[6]) << 8) | UInt32(b[7])
        let fragIndex = (UInt16(b[8]) << 8) | UInt16(b[9])
        let fragTotal = (UInt16(b[10]) << 8) | UInt16(b[11])
        return VideoHeader(keyframe: (b[0] & 0x01) != 0, codec: codec(fromBits: b[0] >> 1),
                           seq: seq, timestampMs: ts, fragIndex: fragIndex, fragTotal: fragTotal)
    }
}

nonisolated enum WireFormat {
    static func frame(type: WireType, payload: Data) -> Data {
        var out = Data(capacity: 5 + payload.count)
        out.appendBE(UInt32(payload.count + 1))
        out.append(type.rawValue)
        out.append(payload)
        return out
    }

    /// 평문 핸드셰이크 프레임 (ClientHello/ServerHello).
    static func handshakeFrame(_ message: PeriControlMessage) throws -> Data {
        frame(type: .handshake, payload: try message.serializedData())
    }

    /// 암호화 제어 프레임. body = ciphertext||tag.
    static func controlFrame(nonce: Data, body: Data) -> Data {
        var p = nonce; p.append(body)
        return frame(type: .control, payload: p)
    }

    /// 암호화 영상 프레임. header 평문 + nonce + body(ciphertext||tag).
    static func videoFrame(header: VideoHeader, nonce: Data, body: Data) -> Data {
        var p = header.encode(); p.append(nonce); p.append(body)
        return frame(type: .video, payload: p)
    }

    // MARK: - UDP (Mini-RTP)

    /// 봉인된 프레임 body를 Mini-RTP 데이터그램 배열로 패킷화.
    /// 각 데이터그램 = [1B UdpType.video][16B MiniRtpHeader][chunk].
    static func packetizeVideo(frameId: UInt32, keyframe: Bool, isLtr: Bool,
                               payloadType: UInt8, timestampMs: UInt32, body: Data,
                               nextPacketSeq: () -> UInt16) -> [Data] {
        let total = max(1, (body.count + UdpVideo.maxChunk - 1) / UdpVideo.maxChunk)
        var out: [Data] = []
        out.reserveCapacity(total)
        for i in 0..<total {
            let start = i * UdpVideo.maxChunk
            let end = min(start + UdpVideo.maxChunk, body.count)
            let chunk = body.subdata(in: (body.startIndex + start)..<(body.startIndex + end))
            let header = MiniRtpHeader(
                keyframe: keyframe, marker: (i == total - 1), payloadType: payloadType,
                isLtr: isLtr, packetSeq: nextPacketSeq(), frameId: frameId,
                fragIndex: UInt16(i), fragCount: UInt16(total), timestampMs: timestampMs
            )
            var d = Data(capacity: 1 + MiniRtpHeader.byteCount + chunk.count)
            d.append(UdpType.video.rawValue)
            d.append(header.encode())
            d.append(chunk)
            out.append(d)
        }
        return out
    }

    /// 수신 UDP 데이터그램의 타입 + 나머지 페이로드 분리.
    static func parseUdp(_ d: Data) -> (type: UdpType, rest: Data)? {
        guard d.count >= 1, let t = UdpType(rawValue: d[d.startIndex]) else { return nil }
        return (t, d.subdata(in: (d.startIndex + 1)..<d.endIndex))
    }

    /// HELLO 본문(`parseUdp`가 타입 바이트를 뗀 나머지) → 세대 id · nonce · 봉인.
    ///
    /// 길이만 본다 — 내용 검증은 봉인이 한다. 여기서 하는 일은 "이 바이트들이 어느 자리에
    /// 있는가"를 정하는 것뿐이고, 그 셋 중 하나라도 모자라면 우리 프레임이 아니다.
    ///
    /// **PING도 이 함수로 판다.** 와이어 모양이 HELLO와 동일하다(피어 재등록 겸용) —
    /// 타입 바이트로 이미 갈렸으니 본문 파서를 둘로 나눌 이유가 없다.
    static func parseUdpHello(_ rest: Data) -> (channel: Data, nonce: Data, body: Data)? {
        let head = datagramChannelIDBytes + 12
        guard rest.count > head + 16 else { return nil }   // 최소 태그 16B는 있어야 한다
        let s = rest.startIndex
        return (channel: rest.subdata(in: s..<(s + datagramChannelIDBytes)),
                nonce: rest.subdata(in: (s + datagramChannelIDBytes)..<(s + head)),
                body: rest.subdata(in: (s + head)..<rest.endIndex))
    }

    /// PONG 데이터그램: `[1B 5][12B nonce][sealed(세대 id)]`. Android의 PING에 대한 답 —
    /// 세대 id를 봉인해 실으면 Android가 지금 세대의 것인지 직접 대조할 수 있다.
    static func pongDatagram(nonce: Data, body: Data) -> Data {
        var out = Data(capacity: 1 + nonce.count + body.count)
        out.append(UdpType.pong.rawValue)
        out.append(nonce)
        out.append(body)
        return out
    }

    // MARK: - 수신 정규화 (화면 공유: Android → Mac)

    /// 신뢰 경로(AOA)로 **통짜로** 온 영상 프레임 payload → 조각 하나.
    /// payload = [12B VideoHeader][12B nonce 자리][ciphertext||tag].
    ///
    /// nonce 12바이트는 자리만 차지한다 — 영상 nonce는 frame_id에서 유도되지 전송되지 않는다.
    /// 그 자리를 없애지 않은 건 프레임 모양을 방향마다 다르게 만들지 않기 위해서다.
    static func parseVideoFrame(_ payload: Data) -> MediaFragment? {
        let head = VideoHeader.byteCount + 12
        guard payload.count > head, let h = VideoHeader.decode(payload) else { return nil }
        let body = payload.subdata(in: (payload.startIndex + head)..<payload.endIndex)
        return MediaFragment(frameId: h.seq, timestampMs: h.timestampMs, codec: h.codec,
                             keyframe: h.keyframe, isLtr: false,
                             fragIndex: h.fragIndex, fragTotal: h.fragTotal, body: body)
    }

    /// 손실 허용 경로(UDP)로 온 Mini-RTP 데이터그램의 나머지 → 조각 하나.
    /// rest = [16B MiniRtpHeader][chunk] (앞의 1B 타입은 `parseUdp`가 이미 떼 냈다).
    static func parseMiniRtp(_ rest: Data) -> MediaFragment? {
        guard rest.count > MiniRtpHeader.byteCount else { return nil }
        let b = [UInt8](rest.prefix(MiniRtpHeader.byteCount))
        guard (b[0] >> 6) == MiniRtpHeader.version else { return nil }
        let payloadType = b[0] & 0x0F
        let frameId = (UInt32(b[4]) << 24) | (UInt32(b[5]) << 16) | (UInt32(b[6]) << 8) | UInt32(b[7])
        let fragIndex = (UInt16(b[8]) << 8) | UInt16(b[9])
        let fragCount = (UInt16(b[10]) << 8) | UInt16(b[11])
        let ts = (UInt32(b[12]) << 24) | (UInt32(b[13]) << 16) | (UInt32(b[14]) << 8) | UInt32(b[15])
        let chunk = rest.subdata(in: (rest.startIndex + MiniRtpHeader.byteCount)..<rest.endIndex)
        return MediaFragment(frameId: frameId, timestampMs: ts,
                             codec: payloadType == 1 ? .h265 : .h264,
                             keyframe: (b[0] & 0x20) != 0, isLtr: (b[1] & 0x01) != 0,
                             fragIndex: fragIndex, fragTotal: fragCount, body: chunk)
    }
}

/// TCP 스트림 길이-프리픽스 프레임 재조립.
nonisolated final class FrameReader {
    private var buffer = Data()
    func append(_ data: Data) { buffer.append(data) }

    func next() -> (type: WireType, payload: Data)? {
        guard buffer.count >= 4 else { return nil }
        let len = buffer.readBE(at: 0) as UInt32
        let total = 4 + Int(len)
        guard buffer.count >= total else { return nil }
        guard len >= 1 else { buffer.removeFirst(total); return nil }
        let typeByte = buffer[buffer.startIndex + 4]
        let payload = buffer.subdata(in: (buffer.startIndex + 5)..<(buffer.startIndex + total))
        buffer.removeFirst(total)
        guard let type = WireType(rawValue: typeByte) else { return nil }
        return (type, payload)
    }
}

private extension Data {
    mutating func appendBE(_ v: UInt32) {
        append(UInt8((v >> 24) & 0xFF)); append(UInt8((v >> 16) & 0xFF))
        append(UInt8((v >> 8) & 0xFF)); append(UInt8(v & 0xFF))
    }
    mutating func appendBE(_ v: UInt16) { append(UInt8((v >> 8) & 0xFF)); append(UInt8(v & 0xFF)) }
    func readBE(at offset: Int) -> UInt32 {
        let i = startIndex + offset
        return (UInt32(self[i]) << 24) | (UInt32(self[i+1]) << 16) | (UInt32(self[i+2]) << 8) | UInt32(self[i+3])
    }
}
