package com.peri.display.apps

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.peri.display.proto.AppId
import com.peri.display.proto.ControlMessage
import com.peri.display.proto.ShareParams
import com.peri.display.proto.ShareRequest
import com.peri.display.proto.ShareStatus
import com.peri.display.proto.VideoCodec
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

    override val isRunning: Boolean get() = runner.state.isRunning

    override val isStreaming: Boolean get() = runner.state.isRunning

    override fun receive(msg: ControlMessage): Boolean {
        if (!msg.hasShareRequest()) return false
        when (msg.shareRequest.action) {
            ShareRequest.Action.ACTION_START -> {
                Log.i(TAG, "Mac이 화면 공유를 요청")
                runner.send(ShareEv.Requested)
            }
            ShareRequest.Action.ACTION_STOP -> runner.send(ShareEv.StopRequested("Mac에서 중지"))
            else -> {}
        }
        return true
    }

    // MARK: - 부작용

    private fun exec(fx: ShareFx) {
        when (fx) {
            ShareFx.AskConsent -> ScreenShare.requestConsent()
            is ShareFx.StopCapture -> ScreenShare.stop(fx.reason, notifyPeer = fx.notifyPeer)
            is ShareFx.ReportPhase -> sendStatus(fx.phase, fx.reason)
            is ShareFx.Report -> ch.reportApp(id, fx.running, fx.detail)
            ShareFx.ResetPts -> synchronized(counters) { ptsBaseMs = -1L }
            ShareFx.ResetFrameIds -> synchronized(counters) { frameId = 0L; ptsBaseMs = -1L }
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
        ScreenShare.onFrame = { au, key, pts -> sendFrame(au, key, pts) }
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
    private fun sendFrame(bitstream: ByteArray, keyframe: Boolean, ptsMs: Long) {
        val (id, pts) = synchronized(counters) {
            if (ptsBaseMs < 0) ptsBaseMs = ptsMs
            frameId++ to (ptsMs - ptsBaseMs)
        }
        if (id > SHARE_FRAME_LIMIT) {
            ScreenShare.stop("공유가 너무 오래 이어졌습니다 — 다시 시작해 주세요")
            return
        }
        ch.sendMedia(
            bitstream,
            MediaMeta(frameId = id, timestampMs = pts, keyframe = keyframe,
                      hevc = ScreenShare.isHevcNow),
        )
    }
}
