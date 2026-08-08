package com.peri.display.transport

import com.peri.display.proto.ControlMessage
import java.io.ByteArrayOutputStream

/**
 * 와이어 포맷 (Mac WireFormat.swift 와 동일, DESIGN.md §2 + §3).
 * 프레임: [4B big-endian length N][1B type][payload]
 *   type 0 = HANDSHAKE : payload = plaintext ControlMessage (ClientHello/ServerHello)
 *   type 1 = CONTROL   : payload = [12B nonce][ciphertext||tag]
 *   type 2 = VIDEO     : payload = [12B VideoHeader][12B nonce][ciphertext||tag]
 *   type 3 = RESUME    : payload = [12B nonce][sealed(16B 펀치 토큰)]  (Android → Mac 전용)
 *
 * 파서는 복호화하지 않는다 (키는 세션 계층에 있음). raw 프레임만 분리해 넘긴다.
 */
enum class WireType(val raw: Int) {
    HANDSHAKE(0), CONTROL(1), VIDEO(2),

    /**
     * 핸드오버로 새로 뚫은 연결의 **첫 프레임** — "이 연결은 살아 있는 그 세션의 것이다".
     *
     * IP가 바뀌면 TCP는 4-tuple이 깨져 못 살리지만, 살릴 필요가 있는 건 연결이 아니라
     * 세션이다(AEAD 키·가상 디스플레이·인코더는 전송에 독립적). Mac이 살아 있는 세션들의
     * 수신키로 시도복호화해 검증하고, 통과하면 제어 채널만 갈아끼운다 — 재핸드셰이크도,
     * 키프레임도, 검은 화면도 없다.
     */
    RESUME(3);
    companion object { fun from(b: Int): WireType? = entries.firstOrNull { it.raw == b } }
}

/** UDP 데이터그램 타입 (길이 프리픽스 없음). */
enum class UdpType(val raw: Int) {
    HELLO(0), VIDEO(1);
    companion object { fun from(b: Int): UdpType? = entries.firstOrNull { it.raw == b } }
}

/** 파싱된 Mini-RTP 패킷 (UDP 영상). nonce는 frame_id로 유도 — 미전송. */
data class MiniRtpPacket(
    val keyframe: Boolean,
    val marker: Boolean,
    val payloadType: Int,    // 0 H264 / 1 H265
    val isLtr: Boolean,
    val packetSeq: Int,      // 16b 전역 패킷 시퀀스
    val frameId: Long,       // 32b
    val fragIndex: Int,
    val fragCount: Int,
    val timestampMs: Long,
    val chunk: ByteArray,
)

const val NONCE_LEN = 12
const val MINI_RTP_HEADER = 16

/** 영상 12바이트 평문 헤더. [1B flags][3B seq][4B ts_ms][2B frag_idx][2B frag_total] */
data class VideoHeader(
    val keyframe: Boolean,
    val codec: Int,
    val seq: Int,
    val timestampMs: Long,
    val fragIndex: Int,
    val fragTotal: Int,
) {
    /** 12바이트로 굽는다. 화면 공유(Android → Mac)에서 처음 필요해졌다. */
    fun encode(): ByteArray {
        val d = ByteArray(BYTE_COUNT)
        d[0] = (((if (keyframe) 1 else 0)) or ((codec and 0x03) shl 1)).toByte()
        d[1] = ((seq ushr 16) and 0xFF).toByte()
        d[2] = ((seq ushr 8) and 0xFF).toByte()
        d[3] = (seq and 0xFF).toByte()
        d[4] = ((timestampMs ushr 24) and 0xFF).toByte()
        d[5] = ((timestampMs ushr 16) and 0xFF).toByte()
        d[6] = ((timestampMs ushr 8) and 0xFF).toByte()
        d[7] = (timestampMs and 0xFF).toByte()
        d[8] = ((fragIndex ushr 8) and 0xFF).toByte()
        d[9] = (fragIndex and 0xFF).toByte()
        d[10] = ((fragTotal ushr 8) and 0xFF).toByte()
        d[11] = (fragTotal and 0xFF).toByte()
        return d
    }

    companion object {
        const val BYTE_COUNT = 12
        fun decode(b: ByteArray, off: Int = 0): VideoHeader {
            val flags = b[off].toInt() and 0xFF
            val seq = ((b[off + 1].toInt() and 0xFF) shl 16) or
                      ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)
            val ts = ((b[off + 4].toLong() and 0xFF) shl 24) or ((b[off + 5].toLong() and 0xFF) shl 16) or
                     ((b[off + 6].toLong() and 0xFF) shl 8) or (b[off + 7].toLong() and 0xFF)
            val fragIdx = ((b[off + 8].toInt() and 0xFF) shl 8) or (b[off + 9].toInt() and 0xFF)
            val fragTot = ((b[off + 10].toInt() and 0xFF) shl 8) or (b[off + 11].toInt() and 0xFF)
            return VideoHeader((flags and 0x01) != 0, (flags shr 1) and 0x03, seq, ts, fragIdx, fragTot)
        }
    }
}

/** 파싱된 raw 와이어 프레임 (복호화 전). */
sealed interface WireFrame {
    data class Handshake(val payload: ByteArray) : WireFrame
    data class Control(val nonce: ByteArray, val body: ByteArray) : WireFrame
    data class Video(val header: VideoHeader, val nonce: ByteArray, val body: ByteArray) : WireFrame
}

object WireFormat {
    /** 평문 핸드셰이크 프레임 (ClientHello). */
    fun handshakeFrame(message: ControlMessage): ByteArray =
        frame(WireType.HANDSHAKE, message.toByteArray())

    /** 암호화 제어 프레임. body = ciphertext||tag. */
    fun controlFrame(nonce: ByteArray, body: ByteArray): ByteArray =
        frame(WireType.CONTROL, nonce + body)

    /**
     * 세션 재개 프레임 — 새로 뚫은 TCP의 첫 바이트로 나간다. body = sealed(펀치 토큰).
     * Mac은 이걸로 "이 연결을 어느 세션에 붙일지"를 정한다(핸드셰이크 대신).
     */
    fun resumeFrame(nonce: ByteArray, body: ByteArray): ByteArray =
        frame(WireType.RESUME, nonce + body)

    /**
     * UDP HELLO 데이터그램: `[1B 0][16B channel_id][12B nonce][sealed(channel_id)]`.
     *
     * **채널 id가 평문으로 앞에 온다.** 데이터그램은 연결이 없어 "누가 보냈나"를 스스로
     * 말하지 못하는데, 그 답이 곧 **어느 키로 열어야 하는가**다. Mac이 세대마다 부여한
     * 이름을 붙여 보내면 Mac은 조회 한 번으로 세션을 찾고, 뒤따르는 봉인이 검증을 한다 —
     * id만 베껴 보내 봐야 열리지 않는다.
     *
     * 봉인의 평문도 채널 id다. 그래야 "앞의 평문 id"와 "키를 가진 자가 넣은 id"가 같은지
     * 대조할 수 있고, 그게 곧 평문 접두를 바꿔치기하지 못한다는 뜻이 된다.
     */
    fun udpHelloDatagram(channelId: ByteArray, nonce: ByteArray, body: ByteArray): ByteArray =
        byteArrayOf(UdpType.HELLO.raw.toByte()) + channelId + nonce + body

    // ── 화면 공유 송신 (Android → Mac) ──────────────────────────────────────
    //
    // Mac이 확장을 보낼 때 쓰는 것과 **똑같은 포맷**이다. 방향만 뒤집혔지 프레임 모양을
    // 새로 만들 이유가 없고, 그래야 양쪽 파서가 하나로 유지된다.

    /**
     * 신뢰 파이프(AOA)용 통짜 영상 프레임: [12B VideoHeader][12B nonce 자리][ciphertext||tag].
     *
     * nonce 12바이트는 **자리만 차지한다** — 영상 nonce는 frame_id에서 유도되고 선에 싣지
     * 않는다. 그래도 자리를 남기는 건 프레임 모양을 방향마다 다르게 만들지 않기 위해서다.
     */
    fun videoFrame(header: VideoHeader, body: ByteArray): ByteArray =
        frame(WireType.VIDEO, header.encode() + ByteArray(NONCE_LEN) + body)

    /**
     * 손실 허용 경로(UDP)용 Mini-RTP 패킷화: 각 데이터그램 = [1B 1][16B 헤더][chunk].
     *
     * `nextPacketSeq`는 **전역** 패킷 순번이다(프레임이 아니라 패킷 단위) — 수신측이 손실을
     * 세는 축이라 프레임마다 0으로 돌아가면 안 된다.
     */
    fun packetizeVideo(
        frameId: Long,
        keyframe: Boolean,
        payloadType: Int,
        timestampMs: Long,
        body: ByteArray,
        nextPacketSeq: () -> Int,
    ): List<ByteArray> {
        val total = maxOf(1, (body.size + MAX_UDP_CHUNK - 1) / MAX_UDP_CHUNK)
        val out = ArrayList<ByteArray>(total)
        for (i in 0 until total) {
            val start = i * MAX_UDP_CHUNK
            val end = minOf(start + MAX_UDP_CHUNK, body.size)
            val d = ByteArray(1 + MINI_RTP_HEADER + (end - start))
            d[0] = UdpType.VIDEO.raw.toByte()
            val b0 = (VERSION shl 6) or ((if (keyframe) 1 else 0) shl 5) or
                     ((if (i == total - 1) 1 else 0) shl 4) or (payloadType and 0x0F)
            d[1] = b0.toByte()
            d[2] = 0                                  // isLtr — 공유 경로는 LTR을 쓰지 않는다
            val seq = nextPacketSeq()
            d[3] = ((seq ushr 8) and 0xFF).toByte(); d[4] = (seq and 0xFF).toByte()
            d[5] = ((frameId ushr 24) and 0xFF).toByte(); d[6] = ((frameId ushr 16) and 0xFF).toByte()
            d[7] = ((frameId ushr 8) and 0xFF).toByte(); d[8] = (frameId and 0xFF).toByte()
            d[9] = ((i ushr 8) and 0xFF).toByte(); d[10] = (i and 0xFF).toByte()
            d[11] = ((total ushr 8) and 0xFF).toByte(); d[12] = (total and 0xFF).toByte()
            d[13] = ((timestampMs ushr 24) and 0xFF).toByte(); d[14] = ((timestampMs ushr 16) and 0xFF).toByte()
            d[15] = ((timestampMs ushr 8) and 0xFF).toByte(); d[16] = (timestampMs and 0xFF).toByte()
            body.copyInto(d, 1 + MINI_RTP_HEADER, start, end)
            out.add(d)
        }
        return out
    }

    /** 데이터그램당 최대 청크 바이트(헤더 제외, 보수적 MTU) — Mac의 `UdpVideo.maxChunk`와 같다. */
    private const val MAX_UDP_CHUNK = 1100
    private const val VERSION = 1

    /** 수신 Mini-RTP 데이터그램 파싱: [1B 1][16B header][chunk]. */
    fun parseUdpVideo(d: ByteArray): MiniRtpPacket? {
        val min = 1 + MINI_RTP_HEADER
        if (d.size < min) return null
        if (UdpType.from(d[0].toInt() and 0xFF) != UdpType.VIDEO) return null
        fun u8(i: Int) = d[i].toInt() and 0xFF
        val b0 = u8(1)
        val keyframe = (b0 ushr 5) and 1 == 1
        val marker = (b0 ushr 4) and 1 == 1
        val payloadType = b0 and 0x0F
        val isLtr = (u8(2) and 0x01) == 1
        val packetSeq = (u8(3) shl 8) or u8(4)
        val frameId = ((u8(5).toLong() shl 24) or (u8(6).toLong() shl 16) or
                       (u8(7).toLong() shl 8) or u8(8).toLong())
        val fragIndex = (u8(9) shl 8) or u8(10)
        val fragCount = (u8(11) shl 8) or u8(12)
        val timestampMs = ((u8(13).toLong() shl 24) or (u8(14).toLong() shl 16) or
                           (u8(15).toLong() shl 8) or u8(16).toLong())
        val chunk = d.copyOfRange(min, d.size)
        return MiniRtpPacket(keyframe, marker, payloadType, isLtr, packetSeq, frameId,
                             fragIndex, fragCount, timestampMs, chunk)
    }

    private fun frame(type: WireType, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(5 + payload.size)
        val len = payload.size + 1
        out.write((len ushr 24) and 0xFF); out.write((len ushr 16) and 0xFF)
        out.write((len ushr 8) and 0xFF); out.write(len and 0xFF)
        out.write(type.raw)
        out.write(payload)
        return out.toByteArray()
    }
}

/** TCP 스트림 길이-프리픽스 프레임 재조립. */
class FrameAssembler {
    private var buffer = ByteArray(0)

    fun append(data: ByteArray, length: Int) { buffer += data.copyOf(length) }

    fun next(): WireFrame? {
        if (buffer.size < 4) return null
        val len = ((buffer[0].toInt() and 0xFF) shl 24) or ((buffer[1].toInt() and 0xFF) shl 16) or
                  ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
        val total = 4 + len
        if (buffer.size < total) return null
        if (len < 1) { buffer = buffer.copyOfRange(total, buffer.size); return null }

        val typeByte = buffer[4].toInt() and 0xFF
        val payload = buffer.copyOfRange(5, total)
        buffer = buffer.copyOfRange(total, buffer.size)

        return when (WireType.from(typeByte)) {
            WireType.HANDSHAKE -> WireFrame.Handshake(payload)
            WireType.CONTROL -> {
                if (payload.size < NONCE_LEN) null
                else WireFrame.Control(
                    payload.copyOfRange(0, NONCE_LEN),
                    payload.copyOfRange(NONCE_LEN, payload.size),
                )
            }
            WireType.VIDEO -> {
                val min = VideoHeader.BYTE_COUNT + NONCE_LEN
                if (payload.size < min) null
                else WireFrame.Video(
                    VideoHeader.decode(payload, 0),
                    payload.copyOfRange(VideoHeader.BYTE_COUNT, VideoHeader.BYTE_COUNT + NONCE_LEN),
                    payload.copyOfRange(min, payload.size),
                )
            }
            // Mac은 RESUME을 보내지 않는다(Android → Mac 단방향). 와도 해석하지 않는다.
            WireType.RESUME -> null
            null -> null
        }
    }
}
