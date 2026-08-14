package com.peri.display.transport

import com.peri.display.proto.ControlMessage

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

    companion object {
        /**
         * raw 바이트 → 타입. **선형 탐색이 아니다.**
         *
         * 프레임마다 불리는 자리다. `entries.firstOrNull { … }`은 호출마다 이터레이터를
         * 하나씩 낳고 항목을 훑는데, 표를 미리 깔아 두면 인덱싱 한 번이고 할당이 없다.
         *
         * 표를 `entries`에서 만드는 게 요점이다 — `when (b) { 0 -> … }`으로 직접 적으면
         * 나중에 `raw` 값을 바꿨을 때 조용히 어긋난다.
         */
        private val BY_RAW: Array<WireType?> =
            arrayOfNulls<WireType>(entries.maxOf { it.raw } + 1)
                .also { t -> entries.forEach { t[it.raw] = it } }

        fun from(b: Int): WireType? = BY_RAW.getOrNull(b)
    }
}

/** UDP 데이터그램 타입 (길이 프리픽스 없음). */
enum class UdpType(val raw: Int) {
    HELLO(0), VIDEO(1);

    companion object {
        private val BY_RAW: Array<UdpType?> =
            arrayOfNulls<UdpType>(entries.maxOf { it.raw } + 1)
                .also { t -> entries.forEach { t[it.raw] = it } }

        fun from(b: Int): UdpType? = BY_RAW.getOrNull(b)
    }
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

/** 길이 필드(4B) — 이 값 **뒤로** 몇 바이트가 오는지를 담는다. */
private const val LENGTH_PREFIX = 4

/** 길이(4B) + 타입(1B). payload는 여기서부터 시작한다. */
private const val FRAME_PREFIX = 5

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
    fun encode(): ByteArray = ByteArray(BYTE_COUNT).also { encodeInto(it, 0) }

    /**
     * 이미 잡아 둔 배열에 **제자리로** 굽는다.
     *
     * [encode]가 따로 있는데 이게 필요한 이유: 영상 프레임은 헤더·nonce 자리·본문이 한
     * 배열에 이어 붙어야 하는데, 조각마다 배열을 만들어 `+`로 이으면 프레임 한 장에
     * 임시 배열이 서넛씩 생긴다. 60fps에서는 그게 곧 GC 파즈다.
     */
    fun encodeInto(d: ByteArray, off: Int) {
        d[off] = (((if (keyframe) 1 else 0)) or ((codec and 0x03) shl 1)).toByte()
        d[off + 1] = ((seq ushr 16) and 0xFF).toByte()
        d[off + 2] = ((seq ushr 8) and 0xFF).toByte()
        d[off + 3] = (seq and 0xFF).toByte()
        d[off + 4] = ((timestampMs ushr 24) and 0xFF).toByte()
        d[off + 5] = ((timestampMs ushr 16) and 0xFF).toByte()
        d[off + 6] = ((timestampMs ushr 8) and 0xFF).toByte()
        d[off + 7] = (timestampMs and 0xFF).toByte()
        d[off + 8] = ((fragIndex ushr 8) and 0xFF).toByte()
        d[off + 9] = (fragIndex and 0xFF).toByte()
        d[off + 10] = ((fragTotal ushr 8) and 0xFF).toByte()
        d[off + 11] = (fragTotal and 0xFF).toByte()
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
        frame(WireType.CONTROL, nonce, body)

    /**
     * 세션 재개 프레임 — 새로 뚫은 TCP의 첫 바이트로 나간다. body = sealed(펀치 토큰).
     * Mac은 이걸로 "이 연결을 어느 세션에 붙일지"를 정한다(핸드셰이크 대신).
     */
    fun resumeFrame(nonce: ByteArray, body: ByteArray): ByteArray =
        frame(WireType.RESUME, nonce, body)

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
    fun udpHelloDatagram(channelId: ByteArray, nonce: ByteArray, body: ByteArray): ByteArray {
        val out = ByteArray(1 + channelId.size + nonce.size + body.size)
        out[0] = UdpType.HELLO.raw.toByte()
        channelId.copyInto(out, 1)
        nonce.copyInto(out, 1 + channelId.size)
        body.copyInto(out, 1 + channelId.size + nonce.size)
        return out
    }

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
    fun videoFrame(header: VideoHeader, body: ByteArray): ByteArray {
        // ★ 조각을 이어 붙이지 않고 **최종 크기로 한 번** 잡는다. `header.encode() +
        // ByteArray(NONCE_LEN) + body`는 임시 배열을 셋 만들고, 그걸 스트림에 밀어 넣으면
        // 내부 버퍼와 `toByteArray()` 복사까지 다섯 번이 된다 — 한 장에 다섯 번이다.
        val out = ByteArray(FRAME_PREFIX + VideoHeader.BYTE_COUNT + NONCE_LEN + body.size)
        writePrefix(out, WireType.VIDEO)
        header.encodeInto(out, FRAME_PREFIX)
        // nonce 자리는 0으로 남긴다 — `ByteArray`가 이미 0이라 쓸 것이 없다.
        body.copyInto(out, FRAME_PREFIX + VideoHeader.BYTE_COUNT + NONCE_LEN)
        return out
    }

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

    /**
     * 수신 Mini-RTP 데이터그램 파싱: [1B 1][16B header][chunk].
     *
     * [length]를 따로 받는 이유: 수신 버퍼는 재사용되므로 `d.size`는 **이 패킷의 길이가
     * 아니라 버퍼의 크기**다. 그걸 쓰면 (1) 뒤에 남은 지난 패킷의 잔해까지 청크에 딸려
     * 들어가고, (2) 호출부가 매 데이터그램마다 `copyOf(length)`로 잘라 넘겨야 해서 복사가
     * 한 번 더 는다. 초당 1000장이 넘는 경로라 그 한 번이 그대로 GC 압력이다.
     */
    fun parseUdpVideo(d: ByteArray, length: Int = d.size): MiniRtpPacket? {
        val min = 1 + MINI_RTP_HEADER
        if (length < min) return null
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
        // 청크만은 반드시 뜬다 — 재조립기가 붙들고 있는 동안 수신 버퍼는 다음 패킷으로 덮인다.
        val chunk = d.copyOfRange(min, length)
        return MiniRtpPacket(keyframe, marker, payloadType, isLtr, packetSeq, frameId,
                             fragIndex, fragCount, timestampMs, chunk)
    }

    /**
     * 프레임 하나를 **한 번의 할당으로** 굽는다: `[4B 길이][1B 타입][조각들…]`.
     *
     * 예전엔 `ByteArrayOutputStream`에 밀어 넣고 `toByteArray()`로 꺼냈다. 그러면 조각을
     * `+`로 잇는 임시 배열들 + 스트림 내부 버퍼 + 마지막 복사가 겹쳐, 프레임 한 장에
     * 배열이 여럿 생긴다. 여기서 미리 합을 세면 정확히 하나다.
     */
    private fun frame(type: WireType, vararg parts: ByteArray): ByteArray {
        var n = FRAME_PREFIX
        for (p in parts) n += p.size
        val out = ByteArray(n)
        writePrefix(out, type)
        var o = FRAME_PREFIX
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }

    /**
     * 길이·타입 머리를 쓴다. 길이는 **자기 자신(4B)을 뺀 나머지 전부**이므로 배열 크기에서
     * 곧바로 나온다 — 따로 세면 언젠가 조각 하나를 빠뜨린다.
     */
    private fun writePrefix(out: ByteArray, type: WireType) {
        val len = out.size - LENGTH_PREFIX
        out[0] = ((len ushr 24) and 0xFF).toByte()
        out[1] = ((len ushr 16) and 0xFF).toByte()
        out[2] = ((len ushr 8) and 0xFF).toByte()
        out[3] = (len and 0xFF).toByte()
        out[4] = type.raw.toByte()
    }
}

/** 처음 잡는 버퍼 크기. 제어 메시지 몇 개는 자라지 않고 들어간다. */
private const val ASSEMBLER_INITIAL = 16 * 1024

/**
 * TCP·USB 스트림의 길이-프리픽스 프레임 재조립.
 *
 * **버퍼를 다시 잡지 않는다.** 예전엔 `buffer += data.copyOf(n)`으로 도착할 때마다 새 배열을
 * 만들어 **지금까지 모인 전부를 다시 복사**했고, 프레임을 꺼낼 때도 payload와 남은 꼬리를
 * 각각 새로 떴다. 제어 메시지만 흐를 땐 티가 안 났는데 **USB 영상이 같은 파이프를 타면서**
 * 드러났다: 200KB 키프레임이 16KB씩 열세 번에 나눠 도착하면 `append`만으로 1.3MB를
 * 복사하고(O(n²)), 거기에 payload 복사와 꼬리 복사가 더 붙는다. 60fps에서는 그게 곧 GC 파즈다.
 *
 * 지금은 채워 쓰는 버퍼 하나에 [head]·[tail]만 움직인다. 한 번 자란 버퍼는 그대로 재사용되고,
 * 다 꺼내면 인덱스를 앞으로 되돌린다. 정상 상태에서 할당은 **프레임당 payload 조각들뿐**이다.
 *
 * 스레드 안전하지 않다 — 하나의 읽기 루프가 [append]와 [next]를 번갈아 부르는 전제다.
 */
class FrameAssembler {
    private var buf = ByteArray(ASSEMBLER_INITIAL)

    /** 아직 안 꺼낸 첫 바이트. */
    private var head = 0

    /** 다음에 채울 자리. `tail - head`가 곧 쌓인 양이다. */
    private var tail = 0

    fun append(data: ByteArray, length: Int) {
        ensure(length)
        data.copyInto(buf, tail, 0, length)
        tail += length
    }

    /**
     * [more] 바이트가 더 들어갈 자리를 만든다.
     *
     * 순서가 요점이다: **먼저 당겨 보고, 그래도 모자랄 때만 키운다.** 프레임을 다 꺼낸
     * 직후가 흔한 상태라 대개 당기는 것으로 끝나고, 그때 복사되는 건 아직 안 꺼낸 꼬리뿐이다.
     */
    private fun ensure(more: Int) {
        if (tail + more <= buf.size) return
        val used = tail - head
        if (used + more <= buf.size) {
            buf.copyInto(buf, 0, head, tail)   // 겹쳐도 안전 — System.arraycopy가 처리한다
            head = 0; tail = used
            return
        }
        var cap = buf.size
        while (cap < used + more) cap = cap shl 1
        val grown = ByteArray(cap)
        buf.copyInto(grown, 0, head, tail)
        buf = grown; head = 0; tail = used
    }

    fun next(): WireFrame? {
        if (tail - head < LENGTH_PREFIX) return null
        val len = ((buf[head].toInt() and 0xFF) shl 24) or ((buf[head + 1].toInt() and 0xFF) shl 16) or
                  ((buf[head + 2].toInt() and 0xFF) shl 8) or (buf[head + 3].toInt() and 0xFF)
        // 길이가 음수 = 스트림이 깨졌다(2GB 프레임은 없다). 여기서 멈추지 않으면 아래
        // 인덱싱이 그대로 터진다. 동기화할 방법이 없으니 버리고 처음부터 다시 맞춘다.
        if (len < 0) { head = 0; tail = 0; return null }
        val total = LENGTH_PREFIX + len
        if (tail - head < total) return null

        val typeByte = buf[head + LENGTH_PREFIX].toInt() and 0xFF
        val start = head + FRAME_PREFIX
        val end = head + total

        // ★ 조각을 뜨기 **전에** 소비 위치를 정해 둔다. 어느 갈래로 빠지든(모르는 타입,
        // 너무 짧은 payload) 이 프레임은 이미 지나간 것이라 반드시 같이 넘어가야 한다.
        val frame = if (len < 1) null else parse(typeByte, start, end)
        head = end
        if (head == tail) { head = 0; tail = 0 }   // 다 비었다 — 앞으로 되돌린다
        return frame
    }

    /**
     * payload를 **버퍼에서 곧바로** 조각낸다.
     *
     * 예전엔 payload 전체를 한 번 뜬 뒤 거기서 다시 잘랐다. 영상이면 그 중간 배열이 곧
     * 프레임 크기라, 한 장마다 본문을 두 번 복사하는 셈이었다. 헤더는 아예 복사하지 않고
     * 제자리에서 읽는다.
     */
    private fun parse(typeByte: Int, start: Int, end: Int): WireFrame? {
        val size = end - start
        return when (WireType.from(typeByte)) {
            WireType.HANDSHAKE -> WireFrame.Handshake(buf.copyOfRange(start, end))

            WireType.CONTROL ->
                if (size < NONCE_LEN) null
                else WireFrame.Control(
                    buf.copyOfRange(start, start + NONCE_LEN),
                    buf.copyOfRange(start + NONCE_LEN, end),
                )

            WireType.VIDEO -> {
                val min = VideoHeader.BYTE_COUNT + NONCE_LEN
                if (size < min) null
                else WireFrame.Video(
                    VideoHeader.decode(buf, start),
                    buf.copyOfRange(start + VideoHeader.BYTE_COUNT, start + min),
                    buf.copyOfRange(start + min, end),
                )
            }

            // Mac은 RESUME을 보내지 않는다(Android → Mac 단방향). 와도 해석하지 않는다.
            WireType.RESUME -> null
            null -> null
        }
    }
}
