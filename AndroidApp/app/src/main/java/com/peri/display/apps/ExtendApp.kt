package com.peri.display.apps

import android.util.Log
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.peri.display.decode.VideoDecoder
import com.peri.display.proto.AppId
import com.peri.display.proto.ControlMessage
import com.peri.display.proto.DynamicRange
import com.peri.display.proto.KeyframeRequest
import com.peri.display.proto.LtrAck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "ExtendApp"

/** 갱신 요청을 이 간격보다 자주 보내지 않는다. 손실은 몰려서 오므로 안 막으면 폭주한다. */
private const val REFRESH_THROTTLE_MS = 300L

/**
 * **확장 앱의 Android 절반** — Mac이 보낸 화면을 받아 그린다.
 *
 * 수명 판단은 여기 없다. 전부 [reduceExtend]가 하고, 이 클래스는 관측한 사실을
 * [ExtendEv]로 올리고 돌려받은 [ExtendFx]를 실행하는 실행기다.
 *
 * 이 앱이 소유하는 것은 **디코더 하나와 그것을 세우는 데 필요한 설정들**이다. 설정이
 * 여기 있어야 하는 이유: `SessionParams`(HDR·해상도)도 `PlayoutConfig`(버퍼·즉시렌더)도
 * 디코더를 다시 세울 때마다 **처음부터 다시 적용돼야** 하는 값이다. 세션이 들고 있으면
 * 디코더가 오르내릴 때마다 세션에게 물어보는 손이 생긴다.
 */
class ExtendApp(
    private val scope: CoroutineScope,
    private val ch: AppChannel,
) : PeriApp {

    override val id: AppId get() = AppId.APP_ID_EXTEND

    /**
     * 확장 앱이 켜져 있는가 — **UI가 전체화면으로 갈 조건**.
     *
     * 세션이 붙었다고 화면을 받는 게 아니다. Mac이 확장을 안 띄웠으면 프레임은 애초에
     * 안 오는데, 그때 검은 전체화면을 띄운 채 기다리면 사용자는 무엇이 잘못됐는지 모른다.
     */
    var running by mutableStateOf(false)
        private set

    /** 재조립기·손실 카운터를 비워 달라 — 세션이 소유하는 자원이라 훅으로 청한다. */
    var onResetReceive: (() -> Unit)? = null

    override var onChanged: (() -> Unit)? = null

    private val runner = AppRunner<ExtendState, ExtendEv, ExtendFx>(
        initial = ExtendState(),
        step = { s, e -> reduceExtend(s, e).let { it.state to it.fx } },
        exec = ::exec,
        settled = { s ->
            scope.launch(Dispatchers.Main) { running = s.isRunning }
            onChanged?.invoke()
        },
    )

    // MARK: - 디코더와 그것을 세우는 데 필요한 것들

    @Volatile private var decoder: VideoDecoder? = null
    @Volatile private var surface: Surface? = null

    /** Mac `PlayoutConfig`의 최신값 — 새 디코더에 그대로 적용된다. */
    private var playoutBufferMs = 50

    /** Mac 프로파일(저지연 = 버퍼 생략). null이면 전송 기본값을 따른다. */
    private var renderOnArrivalPref: Boolean? = null

    /** `SessionParams`가 확정한 다이내믹 레인지와 CTA-861.3 마스터링 메타데이터. */
    private var hdr10 = false
    private var hdrStaticInfo: ByteArray? = null

    private var clockOffsetUs = 0L
    private var clockSynced = false

    /**
     * 갱신 요청의 발사 창을 지킨다. 손실 감지(영상 수신 스레드)·디코더 고장(디코드 스레드)·
     * 기계 지시(러너 스레드)가 모두 여기로 들어오므로 세 축이 같은 락을 본다.
     */
    private val refreshLock = Any()
    private var lastRefreshReqMs = 0L

    /** 창이 닫힐 때까지 미뤄 둔 사유. 창 안의 요청들은 여기로 접힌다(마지막 것이 남는다). */
    private var pendingCause: KeyframeRequest.Cause? = null
    private var pendingJob: Job? = null

    // MARK: - 세션이 부르는 것

    override fun attach() = runner.send(ExtendEv.PathLive)

    /**
     * 채널이 끊겼다 — **왜 끊겼는지가 무엇을 버릴지를 가른다.**
     *
     * 경로만 사라진 것이면 아무것도 놓지 않는다(Mac도 같은 순간 인코더와 가상 디스플레이를
     * 들고 있다). 기기가 떠난 것이면 키가 영영 없으므로 전부 놓는다.
     */
    override fun detach(reason: DetachReason, detail: String) {
        if (reason == DetachReason.DEVICE_GONE) {
            // ★ 다이내믹 레인지는 **그 기기가 알려 준 사실**이지 우리 설정이 아니다.
            // 물려받으면 다음 기기의 첫 디코더가 남의 값으로 서고, 그 순간 색이 틀어진다.
            // 경로만 끊긴 경우에는 같은 기기가 돌아오므로 그대로 둔다.
            hdr10 = false
            hdrStaticInfo = null
        }
        runner.send(
            when (reason) {
                DetachReason.PATH_LOST -> ExtendEv.Unlinked
                DetachReason.DEVICE_GONE -> ExtendEv.SessionLost
            }
        )
    }

    override fun control(launch: Boolean, reason: String) =
        runner.send(if (launch) ExtendEv.Launched else ExtendEv.Quit(reason))

    /** 확장이 켜져 있으면 곧 영상이 흐른다 — 저지연이 실제로 필요한 경우다. */
    override val isStreaming: Boolean get() = runner.state.isRunning

    override fun receive(msg: ControlMessage): Boolean = when {
        msg.hasSessionParams() -> {
            val sp = msg.sessionParams
            // Mac이 HDR 파이프라인 구성에 실패하면 SDR로 강등한 SessionParams를 다시 보낸다
            // → 마지막에 온 값이 언제나 진실이다. 이미 선 디코더에도 반영해 둔다.
            hdr10 = sp.dynamicRange == DynamicRange.DYNAMIC_RANGE_HDR10
            hdrStaticInfo = if (sp.hdrStaticInfo.isEmpty) null else sp.hdrStaticInfo.toByteArray()
            // 레인지가 바뀌면 디코더를 새로 만들어야 한다(색상 설정이 굳어 있다). Mac의 주기
            // IDR은 30초 간격이라 그냥 두면 그만큼 화면이 멈추므로 직접 청한다.
            // 제어 채널과 영상 채널은 순서 보장이 없어서, 새 키프레임이 SessionParams보다
            // 먼저 왔을 수 있다는 점도 이걸로 덮인다.
            if (decoder?.applyDynamicRange(hdr10, hdrStaticInfo) == true) {
                requestRefresh(KeyframeRequest.Cause.CAUSE_DYNAMIC_RANGE)
            }
            Log.i(TAG, "송출 파라미터 ${sp.width}x${sp.height} ${sp.codec.name} " +
                (if (hdr10) "HDR10" else "SDR") +
                (hdrStaticInfo?.let { " mastering=${it.size}B" } ?: ""))
            true
        }

        msg.hasPlayoutConfig() -> {   // Mac UI 프로파일/슬라이더 → 재생 정책 라이브 조절
            val pc = msg.playoutConfig
            playoutBufferMs = pc.bufferMs.coerceIn(5, 300)
            renderOnArrivalPref = pc.renderOnArrival
            decoder?.bufferMs = playoutBufferMs
            decoder?.renderOnArrival = pc.renderOnArrival
            Log.d(TAG, "playout buffer=${playoutBufferMs}ms renderOnArrival=${pc.renderOnArrival}")
            true
        }

        else -> false
    }

    // MARK: - 바깥이 부르는 것

    fun setSurface(s: Surface?) {
        // ★ 필드를 **먼저** 채운다. 기계가 곧바로 [ExtendFx.StartDecoder]를 낼 수 있고,
        // 그때 이 값이 아직 낡아 있으면 디코더가 죽은 표면에 붙는다.
        surface = s
        Log.i(TAG, "setSurface ${if (s == null) "null" else "valid"} state=${runner.state}")
        runner.send(ExtendEv.SurfaceChanged(s != null))
    }

    /**
     * 디코더를 **새로** 세워야 한다 — 회전(새 해상도) 또는 콜드 재연결 직전.
     *
     * 콜드 연결에서는 확장이 아직 안 떠 있어 아무 일도 안 한다(세션이 서고 Mac이
     * `AppControl`을 보내야 뜬다). 무손실 재개·전송 전환처럼 앱이 살아남은 경로에서만
     * 실제로 새 디코더가 선다 — 그 경로는 화면이 끊기면 안 되는 자리다.
     */
    fun restart(reason: String) = runner.send(ExtendEv.Restart(reason))

    /**
     * 완성된 프레임 하나. **영상 수신 스레드에서** 불린다.
     *
     * 장기 참조 프레임이면 받았다고 알린다 — Mac은 그 답을 기준으로 다음 프레임을 굽는다.
     * 이 응답을 세션이 대신 보내면 안 되는 이유: 우리가 **그릴 수 있었는가**가 근거인데,
     * 그건 디코더를 가진 쪽만 안다.
     */
    override fun receiveMedia(m: Media) {
        decoder?.submit(m.bitstream, m.keyframe, m.ptsUs, m.codec, m.ptsHostUs, m.macSendUs)
        if (m.isLtr) sendLtrAck(m.frameId)
    }

    /** 최신성 레인에 구멍이 났다 → 갱신을 청한다. */
    override fun onMediaGap() = requestRefresh(KeyframeRequest.Cause.CAUSE_PACKET_LOSS)

    /**
     * 갱신 요청. 재전송이 아니라 forward 복구다.
     *
     * ★ **무엇이 필요한지 말한다.** 이걸 말하지 않던 때가 실기기의 검은 화면이었다: Mac은
     * 갱신 요청을 전부 LTR-P로 답하는데(손실 복구에는 그게 맞다 — IDR은 스트림에서 가장 큰
     * 버스트다), 방금 선 디코더에는 **참조 프레임이 하나도 없어서** LTR-P를 아무리 받아도
     * 못 푼다. 그대로 계속 버리다가 인코더가 우연히 IDR을 낼 때까지 검다(로그: 3963ms).
     *
     * 무엇이 필요한지는 **디코더에게 묻는다**. 호출부가 "지금쯤 참조가 있겠지"로 고르면
     * 언젠가 틀리고, 틀린 그 경우가 정확히 검은 화면이다.
     */
    fun requestRefresh(cause: KeyframeRequest.Cause) {
        val d = decoder ?: run {
            // 그릴 곳이 없으면 청해 봐야 Mac만 헛수고한다. 다만 **조용히 넘기지 않는다** —
            // 갱신이 필요한 상황이었다는 사실 자체가 진단 정보다.
            Log.d(TAG, "갱신 요청 생략($cause) — 디코더 없음")
            return
        }
        // 참조가 없으면 창을 무시하고 지금 보낸다. 이건 안 나가면 화면이 검은 채로 남는다 —
        // 직전에 손실로 한 번 청했다는 이유로 미루면 그 지연이 그대로 검은 화면이다.
        if (!d.hasReferences) {
            // 창을 무시하되 **다시 열어 둔다** — 안 그러면 뒤이은 손실 요청이 낡은 시각을
            // 보고 곧바로 한 발 더 나간다.
            synchronized(refreshLock) { lastRefreshReqMs = System.currentTimeMillis() }
            send(KeyframeRequest.Need.NEED_IDR, cause, note = ", 참조 없음")
            return
        }

        var sendNow = false
        var scheduleIn = -1L
        var joined = false
        synchronized(refreshLock) {
            val since = System.currentTimeMillis() - lastRefreshReqMs
            when {
                since >= REFRESH_THROTTLE_MS -> sendNow = true

                // ★ 창 안이다 — **버리지 않고 창 끝으로 미룬다.**
                //
                // 예전엔 여기서 그냥 반환했다(선두 스로틀). 그러면 창 안에 들어온 손실은
                // 요청이 아예 안 나가고, 앞선 요청의 LTR-P가 그 손실보다 먼저 만들어졌으면
                // 복구할 방법이 사라진다 — 다음 손실이 창 밖에서 또 나기 전까지 화면이
                // 깨진 채로 남는다. 손실은 몰려 오므로 누르는 건 맞지만, 누르는 것과
                // 버리는 것은 다르다.
                pendingJob != null -> { pendingCause = cause; joined = true }

                else -> {
                    pendingCause = cause
                    val wait = REFRESH_THROTTLE_MS - since
                    scheduleIn = wait
                    pendingJob = scope.launch { delay(wait); firePending() }
                }
            }
            if (sendNow) lastRefreshReqMs = System.currentTimeMillis()
        }

        when {
            sendNow -> send(KeyframeRequest.Need.NEED_REFRESH, cause, note = "")
            joined -> Log.d(TAG, "갱신 합류($cause) — 예약된 요청에 묶인다")
            else -> Log.d(TAG, "갱신 예약($cause) — ${scheduleIn}ms 뒤 (창 안)")
        }
    }

    /**
     * 미뤄 둔 요청을 창 끝에서 실제로 낸다.
     *
     * 사유를 꺼내 [requestRefresh]로 **다시 넣는** 게 요점이다. 그새 디코더가 사라졌거나
     * 참조를 잃었을 수 있고, 그러면 보내야 할 것이 LTR-P가 아니라 IDR이다 — 여기서
     * 직접 보내면 미룰 때의 판단이 굳어 버린다.
     */
    private fun firePending() {
        val cause = synchronized(refreshLock) {
            pendingJob = null
            pendingCause.also { pendingCause = null }
        } ?: return
        requestRefresh(cause)
    }

    /** 예약된 요청을 버린다 — 디코더가 내려가면 보낼 이유도 같이 사라진다. */
    private fun cancelPending() {
        synchronized(refreshLock) {
            pendingJob?.cancel()
            pendingJob = null
            pendingCause = null
        }
    }

    /** 실제 송신 — 창 관리는 호출부가 이미 끝냈다(그 자리가 곧 창을 claim하는 지점이다). */
    private fun send(need: KeyframeRequest.Need, cause: KeyframeRequest.Cause, note: String) {
        // ★ 실제로 나간 요청은 전부 이 한 줄을 남긴다. 갱신은 화면이 검거나 멈췄을 때만
        // 일어나므로, 이 줄의 유무와 사유가 곧 그 순간의 진단이다.
        Log.i(TAG, "갱신 요청 → $need (사유=$cause$note)")
        ch.sendReliable(
            ControlMessage.newBuilder()
                .setKeyframeRequest(
                    KeyframeRequest.newBuilder()
                        .setLastGoodFrame(0)
                        .setNeed(need)
                        .setCause(cause)
                        .build()
                )
                .build()
        )
    }

    fun sendLtrAck(frameId: Long) {
        ch.sendReliable(
            ControlMessage.newBuilder()
                .setLtrAck(LtrAck.newBuilder().setFrameId(frameId.toInt()).build())
                .build()
        )
    }

    /** NTP 추정이 갱신됐다 — 지연 계측에 쓰이므로 선 디코더에도 바로 주입한다. */
    fun applyClock(offsetUs: Long, synced: Boolean) {
        clockOffsetUs = offsetUs
        clockSynced = synced
        decoder?.let { it.clockOffsetUs = offsetUs; it.clockSynced = synced }
    }

    /**
     * 디코더가 **잰** 수치. 디코더가 없으면 null.
     *
     * 우리가 설정한 값(버퍼 깊이·즉시렌더)은 여기 없다 — 그건 디코더가 없어도 답할 수
     * 있는 값이라 따로 노출한다. 섞어 두면 "디코더가 없으니 버퍼도 모른다"가 된다.
     */
    fun stats(): Stats? = decoder?.let {
        Stats(
            queueDelayMs = it.queueDelayMs,
            pipeNetMs = it.pipeNetMs,
            pipeJbufMs = it.pipeJbufMs,
            pipeDecMs = it.pipeDecMs,
        )
    }

    data class Stats(
        val queueDelayMs: Int,
        val pipeNetMs: Int,
        val pipeJbufMs: Int,
        val pipeDecMs: Int,
    )

    /** 디코더가 서 있는가 — 진단 표시용. */
    val decoderUp: Boolean get() = decoder != null

    /** 마지막으로 Mac이 정한 재생 버퍼(ms). 디코더가 없어도 값은 남는다. */
    val bufferMs: Int get() = playoutBufferMs

    /** 지금 정책상 즉시 렌더인가. 디코더가 없어도 답할 수 있어야 진단이 빈칸이 안 된다. */
    val renderOnArrival: Boolean get() = renderOnArrivalPref ?: ch.reliableStream

    // MARK: - 부작용

    private fun exec(fx: ExtendFx) {
        when (fx) {
            ExtendFx.StartDecoder -> startDecoder()
            ExtendFx.StopDecoder -> { decoder?.stop(); decoder = null; cancelPending() }
            // 방금 세운 디코더는 `hasReferences=false`라 IDR을 청하게 된다.
            ExtendFx.RequestKeyframe -> requestRefresh(KeyframeRequest.Cause.CAUSE_DECODER_STARTED)
            ExtendFx.ClearReassembly -> onResetReceive?.invoke()
            is ExtendFx.Report -> ch.reportApp(id, fx.running, fx.detail)
            is ExtendFx.Log -> Log.i(TAG, fx.msg)
        }
    }

    private fun startDecoder() {
        val s = surface ?: return   // 기계가 이미 걸렀다 — 경합에 대비한 방어선일 뿐이다
        decoder?.stop()
        decoder = VideoDecoder(s).also {
            // 손상을 실제로 만나는 곳은 디코더뿐이다 — 스스로 복구할 수 없을 때 청하게 잇는다.
            // 디코더의 어휘를 여기서 와이어 어휘로 옮긴다.
            it.onNeedIdr = { fault ->
                requestRefresh(
                    when (fault) {
                        VideoDecoder.Fault.DECODE_ERROR -> KeyframeRequest.Cause.CAUSE_DECODE_ERROR
                        VideoDecoder.Fault.OUTPUT_STALL -> KeyframeRequest.Cause.CAUSE_OUTPUT_STALL
                    }
                )
            }
            it.bufferMs = playoutBufferMs
            // Mac 프로파일 우선(PlayoutConfig 도착 후), 없으면 전송 기본(케이블 = 즉시 렌더).
            it.renderOnArrival = renderOnArrivalPref ?: ch.reliableStream
            it.clockOffsetUs = clockOffsetUs
            it.clockSynced = clockSynced
            it.applyDynamicRange(hdr10, hdrStaticInfo)  // SessionParams가 먼저 왔을 수 있다
            it.start()
        }
    }
}
