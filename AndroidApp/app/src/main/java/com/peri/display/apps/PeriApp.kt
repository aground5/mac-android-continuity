package com.peri.display.apps

import com.peri.display.proto.AppId
import com.peri.display.proto.AppState
import com.peri.display.proto.ControlMessage

/**
 * **앱 모델(기기 쪽 절반).** Mac `PeriApp.swift`의 대칭이되, 대칭이 아닌 지점이 하나 있다:
 *
 * **여기엔 런처가 없다.** 아이콘은 Mac에 있고 배타 규칙(확장 ∧ 공유)도 거기서 막는다.
 * 이 기기가 하는 일은 "그 앱의 Android 절반"을 세우고 내리는 것뿐이라, 사용자가 앱에게
 * 하는 말(`launch`/`quit`)이 여기서는 **Mac이 하는 말**([PeriApp.control])이 된다.
 *
 * 세션이 앱에게 하는 말은 Mac과 똑같이 세 마디다:
 *
 *     attach(채널)    — 이 Mac과 말할 수 있게 됐다
 *     detach(사유)     — 채널이 끊겼다
 *     receive(메시지)  — 상대가 보냈다
 *
 * 이 셋이 [control]과 **직교한다**는 게 요점이다. 앱은 채널이 없어도 켜져 있을 수 있고
 * (경로 복귀를 기다리는 중), 채널이 있어도 꺼져 있을 수 있다(Mac 홈 화면).
 *
 * ## 왜 갈라냈나
 *
 * 예전에는 세 앱의 절반이 전부 `MirrorController` 안에 있었다. 그러면 앱 하나를 더할
 * 때마다 세션 오케스트레이터를 뜯어야 하고, 실제로 그렇게 자라서 한 파일이 2200줄이
 * 됐다. 더 나쁜 건 **앱마다 다른 재개 정책**이 한 함수(`dropSession`) 안에 뭉쳐 있었다는
 * 점이다 — 확장은 디코더만 버리면 되고, 공유는 동의가 통째로 무효이며(MediaProjection
 * 토큰이 1회용), 알림은 아무것도 안 버려도 된다. 그 차이가 코드로 드러나지 않으면
 * 한쪽을 고칠 때 다른 쪽이 조용히 깨진다.
 */

/**
 * 최신성 레인으로 나가는 미디어 한 덩이에 딸린 메타데이터.
 *
 * `frameId`가 **봉인의 nonce 근거**라 이 값과 헤더에 실리는 값이 갈리면 상대가 프레임을
 * 통째로 못 푼다. 그래서 자르는 일이 채널 안쪽에 없다 — 한도는 [ShareState]가 지킨다.
 */
data class MediaMeta(
    val frameId: Long,
    val timestampMs: Long,
    val keyframe: Boolean,
    val hevc: Boolean,
    /**
     * 이 프레임이 **장기 참조 앵커**인가.
     *
     * 받는 쪽이 이 비트를 보고 ACK을 돌려주면, 보낸 쪽은 그 지점을 손실 복구의 되돌아갈
     * 자리로 삼는다. 손실이 구조적으로 없는 신뢰 파이프(AOA)에서는 의미가 없어 헤더에
     * 자리조차 없다 — 그래서 최신성 레인에서만 실려 나간다.
     */
    val isLtr: Boolean = false,
)

/**
 * **앱이 세션에 대해 아는 전부** — Mac `ServiceChannel`의 대칭.
 *
 * 앱은 TCP도 UDP도 AOA도 모른다. "반드시 도착해야 한다"와 "최신이면 된다"만 말한다
 * (DESIGN.md §1). 봉인·세대 키 선택·조각화가 전부 구현 안쪽에서 끝나므로, 앱 수만큼
 * 세션 키를 만지는 손이 늘어나는 일이 없다.
 */
interface AppChannel {
    /**
     * 반드시, 순서대로. 세션 키가 아직 없으면 조용히 버린다 — 호출부가 매번
     * `?: return`을 반복하지 않게 하려는 것이고, 그 구간의 제어 메시지는 파이프라인이
     * 서면 어차피 다시 나간다.
     */
    fun sendReliable(msg: ControlMessage)

    /**
     * 최신성 우선. 봉인·경로 선택·조각화가 여기서 끝난다.
     * 반환값은 실제로 나간 조각 수(0 = 경로 없음 / 봉인 실패) — 호출부의 로그용.
     */
    fun sendMedia(plain: ByteArray, meta: MediaMeta): Int

    /**
     * 손실도 혼잡도 구조적으로 없는 전송인가(AOA 케이블).
     *
     * **앱이 아니라 채널이 답해야 하는 질문이다.** 앱은 기기에 매여 있어 LAN↔AOA 전환을
     * 그대로 살아남는데, 이 값을 앱이 붙들고 있으면 케이블을 꽂은 뒤에도 옛 전송의
     * 성질로 행동한다.
     */
    val reliableStream: Boolean
}

/**
 * 채널이 끊긴 이유 — Mac `DetachReason`의 대칭.
 *
 * **이 구분이 없으면 잠깐 끊긴 것과 떠난 것이 같은 일이 된다.** 실제로 그랬다: 세션이
 * 사라지면 무조건 앱을 내렸는데, 전송 전환(LAN↔USB)이나 재접속은 세션이 새로 서는 일이라
 * 그때마다 확장이 꺼졌다 켜졌다. Mac은 같은 순간에 파이프라인과 가상 디스플레이를 **일부러
 * 들고 있는데**(그게 무중단의 근거다) 폰만 전부 버리니, 돌아올 때마다 디코더를 새로 세우고
 * 키프레임을 기다리는 검은 화면을 다시 겪었다.
 */
enum class DetachReason {
    /**
     * 경로만 사라졌다. 세션도 기기도 곧 돌아올 수 있다 — 재접속·재협상·전송 전환이 전부
     * 여기다. **붙잡고 있을 수 있는 건 붙잡는다.**
     */
    PATH_LOST,

    /**
     * 기기가 떠났다(사용자 종료 · Mac의 Bye · 치명 거부). 돌아오지 않으므로 전부 놓는다.
     */
    DEVICE_GONE,
}

/** 기기의 홈 화면에 있는 앱 하나 — 다만 홈 화면은 Mac에 있다. */
interface PeriApp {

    /** 와이어의 앱 번호. Mac의 런처가 이 번호로 지시를 보낸다. */
    val id: AppId

    /**
     * 말할 채널이 살아났다. 세션이 갈려도 앱은 살아 있으므로 **여러 번 불린다**.
     *
     * Mac과 달리 채널을 인자로 받지 않는다 — 이 기기는 한 번에 한 Mac과만 붙으므로
     * 채널이 하나뿐이고, 앱은 생성될 때 그것을 받는다. 그래서 여기 남는 건 "지금은 말이
     * 통한다"는 사실뿐이다.
     */
    fun attach()

    /**
     * 채널이 끊겼다. **무엇을 지키고 무엇을 버릴지는 앱이 스스로 정한다** — 세션은
     * 사실만 전한다. 이 한 줄이 세 앱에서 서로 다른 뜻이 되는 것이 앱을 가른 이유다.
     */
    fun detach(reason: DetachReason, detail: String)

    /** Mac의 런처가 띄우라/내리라고 했다(`AppControl`). */
    fun control(launch: Boolean, reason: String)

    /** 상대가 보낸 메시지. **자기 것이면 소비하고 true.** */
    fun receive(msg: ControlMessage): Boolean

    /** 최신성 레인으로 완성된 미디어 하나가 도착했다. 임자가 아니면 그냥 흘려보낸다. */
    fun receiveMedia(m: Media) = Unit

    /**
     * 최신성 레인에 **구멍이 났다**(패킷 유실). 무엇을 할지는 앱이 정한다.
     *
     * 세션이 여기서 키프레임을 청하지 않는 이유: 그건 "이 앱이 무엇을 그리고 있는가"에
     * 달린 결정이다. 알림 앱에게는 유실이 아무 뜻도 없다.
     */
    fun onMediaGap() = Unit

    /**
     * 지금 **영상을 나르고 있는가** — Wi-Fi 저지연 락과 keepalive 주기의 유일한 근거.
     *
     * "켜져 있는가"와 다르다: 알림 앱은 떠 있어도 라디오를 깨워 둘 이유가 없다.
     * 그래서 등록부가 묻는 것은 이 하나뿐이고, 켜짐 여부는 각 앱이 UI에 직접 비춘다.
     */
    val isStreaming: Boolean

    /**
     * 내 상태가 바뀌었다 — 등록부가 꽂아 둔다. 앱은 등록부를 모른다.
     *
     * 이 신호가 필요한 이유는 하나다: **세션 유지 비용이 앱에 달려 있다.** 라디오를
     * 깨워 두는 것도(Wi-Fi 락) 주기적으로 두드리는 것도(keepalive), 근거는 "지금 영상이
     * 흐르는가"뿐이다. 그 답이 바뀌는 지점이 전부 앱 안에 있다.
     */
    var onChanged: (() -> Unit)?
}

/**
 * 순수 기계 하나를 돌리는 실행기 — **직렬화 + 락 밖 실행**.
 *
 * 세 앱이 똑같이 필요로 하고, 똑같이 틀리기 쉬운 부분이라 한 번만 쓴다. 지켜야 하는
 * 두 가지는 `MirrorController.dispatch`가 지키는 것과 같다:
 *
 *  1. **직렬화** — 부작용 실행 중에 또 사건이 들어온다. `AskConsent`는 이미 공유 중이면
 *     `ScreenShare`가 그 자리에서 `onGranted`를 되부르고, 그게 곧 새 사건이다. 그때
 *     곧바로 reduce를 돌리면 부작용 목록이 뒤섞여 순서 의미가 깨진다.
 *  2. **부작용은 락 밖에서** — 부작용은 소켓·MediaCodec·시스템 서비스를 만진다. 상태 락을
 *     쥔 채로 그걸 하면 반대 순서의 락 획득이 만들어져 교착한다.
 */
internal class AppRunner<S : Any, E : Any, F : Any>(
    initial: S,
    private val step: (S, E) -> Pair<S, List<F>>,
    private val exec: (F) -> Unit,
    /** 드레인이 끝날 때마다 불린다 — Compose 미러·비용 재계산처럼 **멱등한** 일만. */
    private val settled: (S) -> Unit = {},
) {
    @Volatile
    var state: S = initial
        private set

    private val lock = Any()
    private val queue = ArrayDeque<E>()
    private var draining = false

    fun send(ev: E) {
        synchronized(lock) {
            queue.addLast(ev)
            if (draining) return
            draining = true
        }
        while (true) {
            // 큐가 비면 **그 자리에서** draining을 내린다 — 락 밖에서 내리면 그 틈에 들어온
            // 사건이 "이미 누가 돌리는 중"으로 보여 유실된다.
            val fx = synchronized(lock) {
                val e = queue.removeFirstOrNull()
                if (e == null) {
                    draining = false
                    null
                } else {
                    val (next, f) = step(state, e)
                    state = next
                    f
                }
            } ?: break
            fx.forEach { f ->
                runCatching { exec(f) }
                    .onFailure { android.util.Log.e("PeriApp", "fx $f 실패: ${it.message}", it) }
            }
        }
        settled(state)
    }
}

/** `AppState` 한 줄 — "내 절반은 이렇다"를 Mac에 사실대로 알린다. */
internal fun AppChannel.reportApp(app: AppId, running: Boolean, detail: String) {
    sendReliable(
        ControlMessage.newBuilder().setAppState(
            AppState.newBuilder().setApp(app).setRunning(running).setDetail(detail).build()
        ).build()
    )
}
