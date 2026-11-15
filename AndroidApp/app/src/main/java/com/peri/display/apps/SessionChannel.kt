package com.peri.display.apps

import android.util.Log
import com.peri.display.decode.VideoReassembler
import com.peri.display.proto.ControlMessage
import com.peri.display.transport.AoaClient
import com.peri.display.transport.Bearer
import com.peri.display.transport.MiniRtpPacket
import com.peri.display.transport.VideoHeader
import com.peri.display.transport.WireFormat
import com.peri.display.transport.WireFrame
import com.peri.display.vm.SessionCrypto

private const val TAG = "SessionChannel"

/** 영상 페이로드 앞에 붙는 지연 계측 임베드 — `[ptsHostUs 8B][macSendUs 8B]`. */
private const val INSTRUMENT_BYTES = 16

/**
 * [AppChannel]의 실체 — Mac `ServiceChannel.swift`의 대칭.
 *
 * 이 파일이 **앱의 어휘와 전송의 어휘가 만나는 유일한 지점**이다. 여기 아래로는 키프레임도
 * 코덱도 없고(전부 바이트와 소켓), 여기 위로는 소켓도 세대 키도 없다(전부 "반드시 도착"과
 * "최신이면 된다"). 세션 오케스트레이터가 이 둘 중 어느 쪽도 아니라는 것이 요점이라,
 * 그 어휘가 오케스트레이터에 새지 않도록 여기 가둔다 — `scripts/check-layering.sh`가
 * 그걸 기계로 검사한다.
 *
 * 전송과 키를 **매번 지금 값으로** 읽으므로 LAN↔AOA 전환과 무손실 교체를 그대로 살아남는다.
 */
class SessionChannel(
    private val crypto: SessionCrypto,
    /** 지금 활성 전송. 세션이 갈아끼우므로 붙들지 않고 매번 묻는다. */
    private val bearer: () -> Bearer,
) : AppChannel {

    /**
     * 전역 패킷 순번(16비트 wrap) — **프레임이 아니라 패킷** 단위다. 수신측이 손실을 세는
     * 축이라 프레임마다 0으로 돌아가면 안 된다.
     */
    private var packetSeq = 0

    @Synchronized
    private fun nextPacketSeq(): Int {
        packetSeq = (packetSeq + 1) and 0xFFFF
        return packetSeq
    }

    /**
     * 조각 재조립. **채널이 소유한다** — 조각이 빠졌다는 건 전송의 사고이지 앱이 알아야 할
     * 사실이 아니다. 앱은 완성된 것만 본다.
     */
    private val reassembler = VideoReassembler()

    private var frameCount = 0

    override val reliableStream: Boolean get() = bearer() is AoaClient

    // MARK: - 송신

    override fun sendReliable(msg: ControlMessage) {
        val frame = crypto.controlFrame(msg) ?: return
        bearer().sendControl(frame)
    }

    override fun sendMedia(plain: ByteArray, meta: MediaMeta): Int {
        val reliable = reliableStream
        val body = crypto.sealVideo(plain, meta.frameId, reliable) ?: return 0
        val b = bearer()
        if (reliable) {
            // 신뢰 파이프 — 조각화도 페이싱도 없다.
            val header = VideoHeader(
                keyframe = meta.keyframe, codec = if (meta.hevc) 1 else 0,
                seq = meta.frameId.toInt(), timestampMs = meta.timestampMs,
                fragIndex = 0, fragTotal = 1,
            )
            b.sendVideo(WireFormat.videoFrame(header, body))
            return 1
        }
        val packets = WireFormat.packetizeVideo(
            frameId = meta.frameId, keyframe = meta.keyframe,
            payloadType = if (meta.hevc) 1 else 0, timestampMs = meta.timestampMs,
            body = body, isLtr = meta.isLtr, nextPacketSeq = { nextPacketSeq() },
        )
        for (p in packets) b.sendVideo(p)
        return packets.size
    }

    // MARK: - 수신
    //
    // 재조립과 복호가 **둘 다 여기 있는 이유**는 순서가 하나뿐이기 때문이다. 봉인은 프레임
    // 전체를 한 덩이로 덮으므로 조각이 다 모이기 전에는 풀 수 없고, 따라서 재조립을 앱에
    // 맡기면 앱이 세션 키를 만져야 한다. 그러면 "암호는 세션 계층"이라는 선이 앱 수만큼 뚫린다.
    //
    // nonce는 선에 실리지 않는다 — 양쪽이 frame_id에서 같은 값을 유도한다. 그래서 조각이
    // 통째로 유실돼도 그 다음 프레임은 멀쩡히 풀린다.

    /** LAN 조각 하나 → **완성되고 봉인이 풀린** 미디어. 아직 덜 모였으면 null. */
    fun openMedia(pkt: MiniRtpPacket): Media? {
        // 세션 키가 아니라 **이 세대의 키**로 연다. 세대가 없으면 열려 있는 UDP 경로가
        // 없다는 뜻이고, 그때 오는 데이터그램은 지난 세대의 잔향이거나 남의 것이다.
        if (!crypto.hasEpoch) return null
        val f = reassembler.offer(pkt) ?: return null
        val plain = crypto.openVideo(f.body, f.frameId, reliablePipe = false) ?: return null
        return strip(plain, f.keyframe, f.timestampMs * 1000, f.payloadType, f.frameId, f.isLtr)
    }

    /**
     * 케이블 통짜 프레임 → 같은 모양.
     *
     * 재조립도 손실 기전도 없다 — 순서가 보장된 신뢰 파이프라 조각이 빠질 수가 없다.
     * 그래서 세대 키도 없다(`reliablePipe`): 채널을 열었다 닫았다 하지 않으므로 frame_id가
     * 되감기며 (키, nonce)가 겹칠 일이 구조적으로 없다.
     */
    fun openMedia(frame: WireFrame.Video): Media? {
        val h = frame.header
        val plain = crypto.openVideo(frame.body, h.seq.toLong(), reliablePipe = true) ?: return null
        return strip(plain, h.keyframe, h.timestampMs * 1000, h.codec, h.seq.toLong(), isLtr = false)
    }

    /**
     * 수신 상태를 비운다(세대가 갈리거나 화면이 재구성될 때). 붙들고 있던 미완성 조각은
     * 다음 세대의 프레임과 섞이면 안 된다.
     */
    fun resetMediaReceive() = reassembler.clear()

    /** 계측 임베드를 떼고 앱이 볼 모양으로 만든다. 두 경로가 여기서 만난다. */
    private fun strip(
        plain: ByteArray, keyframe: Boolean, ptsUs: Long, codec: Int,
        frameId: Long, isLtr: Boolean,
    ): Media? {
        if (plain.size < INSTRUMENT_BYTES) return null
        val media = Media(
            bitstream = plain.copyOfRange(INSTRUMENT_BYTES, plain.size),
            keyframe = keyframe,
            ptsUs = ptsUs,
            codec = codec,
            frameId = frameId,
            isLtr = isLtr,
            ptsHostUs = be64(plain, 0),
            macSendUs = be64(plain, 8),
        )
        frameCount++
        if (frameCount <= 3 || frameCount % 120 == 0) {
            Log.i(TAG, "media #$frameCount id=$frameId key=$keyframe ltr=$isLtr " +
                "${media.bitstream.size}B")
        }
        return media
    }

    private fun be64(b: ByteArray, o: Int): Long {
        var v = 0L; for (i in 0 until 8) v = (v shl 8) or (b[o + i].toLong() and 0xFF); return v
    }
}

/**
 * 최신성 레인으로 **도착한** 미디어 한 덩이 — 완성되고 봉인이 풀린 접근 단위 하나.
 *
 * 앱은 어느 파이프로 왔는지 모르고 알 필요도 없다: UDP 조각이든 케이블 통짜든 여기 도착할
 * 때는 같은 모양이다.
 */
class Media(
    val bitstream: ByteArray,
    val keyframe: Boolean,
    val ptsUs: Long,
    val codec: Int,
    val frameId: Long,
    /** 장기 참조 프레임인가 — 받았다고 알려 줘야 상대가 그걸 기준으로 다음을 굽는다. */
    val isLtr: Boolean,
    /** 지연 계측 임베드 — Mac이 프레임을 만든 시각과 선에 올린 시각. */
    val ptsHostUs: Long,
    val macSendUs: Long,
)
