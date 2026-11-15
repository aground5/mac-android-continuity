package com.peri.display.apps

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.peri.display.proto.AppId
import com.peri.display.proto.ControlMessage
import com.peri.display.proto.KeyframeRequest
import com.peri.display.proto.ShareParams
import com.peri.display.proto.ShareRequest
import com.peri.display.proto.ShareStatus
import com.peri.display.proto.VideoCodec
import com.peri.display.share.LTR_SLOTS
import com.peri.display.share.ScreenShare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "ShareApp"

/**
 * 공유 프레임 번호의 상한 = 와이어 `VideoHeader.seq`의 폭(24비트).
 *
 * 수신측은 **그 값으로** nonce를 만든다. 봉인에 쓴 값이 그보다 넓으면 24비트를 넘는 순간
 * 복호가 통째로 실패하고, 그렇다고 잘라서 봉인하면 같은 키에 같은 nonce가 두 번 나온다 —
 * 그건 복호 실패보다 나쁘다(GCM은 nonce 재사용에 무너진다).
 *
 * 그래서 넘기 전에 **공유를 멈춘다.** 30fps로 155시간이라 실제로 닿을 일은 없고, 닿는다면
 * 그건 조용히 깨질 자리가 아니라 멈춰야 할 자리다.
 */
private const val SHARE_FRAME_LIMIT = 0xFF_FFFFL

/**
 * **공유 앱의 Android 절반** — 이 기기의 화면을 Mac으로 보낸다.
 *
 * 시작 버튼은 Mac에 있지만 **켜는 결정은 여기 사람의 것이다**(MediaProjection 동의).
 * 그 비대칭이 이 앱의 전부이고, [reduceShare]가 그것만 다룬다.
 *
 * 이 클래스가 소유하는 것은 **프레임 번호와 PTS 기준점** 둘이다. 왜 세션이 아니라 앱이
 * 갖는가: 프레임 번호는 봉인 nonce의 근거라 되돌릴 수 있는 지점이 정확히 하나뿐인데
 * (키가 바뀌는 순간), 그 규칙을 아는 곳과 번호를 매기는 곳이 갈리면 언젠가 어긋난다.
 */
class ShareApp(
    private val scope: CoroutineScope,
    context: Context,
    private val ch: AppChannel,
) : PeriApp {

    override val id: AppId get() = AppId.APP_ID_SHARE

    /** 화면 공유가 이 기기에서 도는 중인가 — UI 표시용. */
    var running by mutableStateOf(false)
        private set

    override var onChanged: (() -> Unit)? = null

    private val runner = AppRunner<ShareState, ShareEv, ShareFx>(
        initial = ShareState(),
        step = { s, e -> reduceShare(s, e).let { it.state to it.fx } },
        exec = ::exec,
        settled = { s ->
            scope.launch(Dispatchers.Main) { running = s.isRunning }
            onChanged?.invoke()
        },
    )

    /** 프레임 번호와 PTS 기준점을 함께 지킨다 — 인코더 스레드와 제어 스레드가 같이 만진다. */
    private val counters = Any()
    private var frameId = 0L

    /**
     * 슬롯마다 지금 그 자리에 새겨진 프레임 번호(-1 = 빈 슬롯).
     *
     * **왜 여기 있나:** 인코더는 슬롯만 알고 프레임 번호는 모른다(번호를 매기는 건 이 앱의
     * 일이다). Mac은 반대로 프레임 번호만 알고 슬롯은 모른다 — 선에 실리는 건 "이 프레임이
     * 앵커다"라는 비트 하나뿐이다. 둘을 잇는 자리가 정확히 여기다.
     */
    private val slotFrame = LongArray(LTR_SLOTS) { -1L }

    /**
     * Mac이 확인해 준 프레임 번호가 **아직 살아 있는 앵커인가** — 아니면 -1.
     *
     * 그 사이에 같은 슬롯을 다른 프레임이 덮었으면 늦은 ACK이다. 그걸 그대로 확정하면
     * 인코더가 이미 사라진 참조를 가리키게 된다.
     */
    private fun slotOf(ackedFrame: Int): Int {
        if (ackedFrame <= 0) return -1
        return slotFrame.indexOfFirst { it == ackedFrame.toLong() }
    }

    /**
     * 첫 프레임의 PTS. 이 값을 빼서 스트림이 0에서 시작하게 한다.
     *
     * 인코더가 주는 PTS는 부팅 이후 시각이라 값이 크고, 32비트 필드에 담으면 기기가
     * 49일쯤 켜져 있었을 때 넘친다. 0 기준으로 옮기면 그 경계가 아예 사라진다.
     */
    private var ptsBaseMs = -1L

    init {
        ScreenShare.attach(context)
        wireScreenShare()
    }

    // MARK: - 세션이 부르는 것

    override fun attach() = runner.send(ShareEv.Linked)

    /**
     * ★ 확장과 달리 **사유를 보지 않는다.** 경로만 끊겼어도 동의는 통째로 무효다 —
     * MediaProjection 토큰이 1회용이고 포그라운드 서비스도 그 연결과 함께 죽는다.
     * 지키고 싶어도 플랫폼이 허락하지 않는 자리라, 여기서는 선택의 여지가 없다.
     */
    override fun detach(reason: DetachReason, detail: String) =
        runner.send(ShareEv.Unlinked(detail))

    /**
     * Mac의 런처가 지시했다.
     *
     * **내리라는 지시만 즉시 따른다** — 켜는 쪽은 사람 확인을 건너뛸 수 없으므로 실제
     * 방아쇠는 `ShareRequest`다(동의를 물어야 한다).
     */
    override fun control(launch: Boolean, reason: String) {
        if (!launch) runner.send(ShareEv.StopRequested(reason.ifEmpty { "Mac에서 종료" }))
    }

    override val isStreaming: Boolean get() = runner.state.isRunning

    /**
     * ★ `KeyframeRequest`·`LtrAck`은 **확장이 쓰던 어휘를 그대로 되쓴다.**
     *
     * 와이어 v1에는 `serviceId`가 없어 등록부가 앱들에게 순서대로 물어보는데, 확장의
     * Android 절반은 이 둘을 **보내기만 하고 받지 않는다**(Mac이 굽고 우리가 푸니까).
     * 그래서 이 기기에 도착한 갱신 요청과 LTR ACK은 임자가 하나뿐이다 — 공유다.
     *
     * 방향이 뒤집혔을 뿐 뜻이 같으므로 새 메시지를 만들 이유가 없다. proto를 안 늘린 만큼
     * 옛 Mac과도 그대로 붙는다.
     */
    override fun receive(msg: ControlMessage): Boolean = when {
        msg.hasShareRequest() -> {
            when (msg.shareRequest.action) {
                ShareRequest.Action.ACTION_START -> {
                    Log.i(TAG, "Mac이 화면 공유를 요청")
                    runner.send(ShareEv.Requested)
                }
                ShareRequest.Action.ACTION_STOP -> runner.send(ShareEv.StopRequested("Mac에서 중지"))
                else -> {}
            }
            true
        }

        msg.hasKeyframeRequest() -> {
            val req = msg.keyframeRequest
            val needIdr = req.need == KeyframeRequest.Need.NEED_IDR
            Log.i(TAG, "Mac이 갱신을 청함 — ${req.need.name} (사유=${req.cause.name}, " +
                "마지막 온전한 프레임=${req.lastGoodFrame})")
            ScreenShare.requestRefresh(needIdr, req.cause.name)
            true
        }

        msg.hasLtrAck() -> {
            // 프레임 번호는 여기서 매겼으므로 슬롯을 되찾는 것도 여기 일이다.
            val acked = msg.ltrAck.frameId
            val slot = synchronized(counters) { slotOf(acked) }
            if (slot < 0) {
                // 앵커를 덮어쓴 뒤에 도착한 늦은 ACK — 버리는 게 맞다. 그 슬롯은 이미
                // 다른 프레임이 차지했고, 옛 프레임을 가리키면 화면이 깨진다.
                Log.i(TAG, "지난 LTR ACK 무시 — frame=$acked")
            } else {
                ScreenShare.confirmLtr(slot)
            }
            true
        }

        else -> false
    }

    // MARK: - 부작용

    private fun exec(fx: ShareFx) {
        when (fx) {
            ShareFx.AskConsent -> ScreenShare.requestConsent()
            is ShareFx.StopCapture -> ScreenShare.stop(fx.reason, notifyPeer = fx.notifyPeer)
            is ShareFx.ReportPhase -> sendStatus(fx.phase, fx.reason)
            is ShareFx.Report -> ch.reportApp(id, fx.running, fx.detail)
            ShareFx.ResetPts -> synchronized(counters) { ptsBaseMs = -1L }
            // 번호가 처음으로 돌아가면 슬롯 장부도 같이 비워야 한다 — 안 그러면 옛 번호에
            // 대한 ACK이 새 스트림의 같은 번호와 우연히 맞아 사라진 참조를 확정해 버린다.
            ShareFx.ResetFrameIds -> synchronized(counters) {
                frameId = 0L; ptsBaseMs = -1L; slotFrame.fill(-1L)
            }
            is ShareFx.Log -> Log.i(TAG, fx.msg)
        }
    }

    private fun sendStatus(phase: SharePhase, reason: String) {
        val wire = when (phase) {
            SharePhase.GRANTED -> ShareStatus.Phase.PHASE_GRANTED
            SharePhase.DENIED -> ShareStatus.Phase.PHASE_DENIED
            SharePhase.STOPPED -> ShareStatus.Phase.PHASE_STOPPED
        }
        ch.sendReliable(
            ControlMessage.newBuilder().setShareState(
                ShareStatus.newBuilder().setPhase(wire).setReason(reason).build()
            ).build()
        )
    }

    // MARK: - 인코더와의 접점

    /** [ScreenShare]가 바깥으로 내는 사실들을 이 앱에 잇는다. */
    private fun wireScreenShare() {
        ScreenShare.onFrame = { au, key, pts, slot -> sendFrame(au, key, pts, slot) }
        ScreenShare.onGranted = { runner.send(ShareEv.Granted) }
        ScreenShare.onDenied = { why -> runner.send(ShareEv.Denied(why)) }
        ScreenShare.onStopped = { why -> runner.send(ShareEv.Stopped(why)) }
        ScreenShare.onParams = { w, h, fps, hevc, kbps, physW, physH ->
            Log.i(TAG, "공유 제원 통보 ${w}x${h}@$fps ${if (hevc) "H265" else "H264"} " +
                "실물 ${physW / 1000}x${physH / 1000}mm")
            ch.sendReliable(
                ControlMessage.newBuilder().setShareParams(
                    ShareParams.newBuilder()
                        .setWidth(w).setHeight(h).setFps(fps)
                        .setCodec(if (hevc) VideoCodec.VIDEO_CODEC_H265 else VideoCodec.VIDEO_CODEC_H264)
                        .setBitrateKbps(kbps)
                        .setPhysicalWidthUm(physW).setPhysicalHeightUm(physH)
                        .build()
                ).build()
            )
        }
    }

    /**
     * 인코더가 만든 AU 하나를 Mac으로 보낸다. **인코더 스레드에서** 불린다.
     *
     * 봉인도 경로 선택도 조각화도 [AppChannel.sendMedia] 안쪽 일이다 — 이 앱은 번호를
     * 매기고 한도를 지킬 뿐이다.
     */
    private fun sendFrame(bitstream: ByteArray, keyframe: Boolean, ptsMs: Long, ltrSlot: Int) {
        val (id, pts) = synchronized(counters) {
            if (ptsBaseMs < 0) ptsBaseMs = ptsMs
            val next = frameId + 1
            frameId = next
            // 앵커라면 슬롯 장부를 지금 갱신한다 — ACK이 돌아올 때 되짚을 유일한 근거다.
            if (ltrSlot in slotFrame.indices) slotFrame[ltrSlot] = next
            next to (ptsMs - ptsBaseMs)
        }
        if (id > SHARE_FRAME_LIMIT) {
            ScreenShare.stop("공유가 너무 오래 이어졌습니다 — 다시 시작해 주세요")
            return
        }
        ch.sendMedia(
            bitstream,
            MediaMeta(frameId = id, timestampMs = pts, keyframe = keyframe,
                      hevc = ScreenShare.isHevcNow, isLtr = ltrSlot >= 0),
        )
    }
}
