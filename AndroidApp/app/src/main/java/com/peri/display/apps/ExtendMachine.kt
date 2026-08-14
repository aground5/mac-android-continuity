package com.peri.display.apps

/**
 * **앱 #1(화면 확장, Mac → Android)의 상태 기계** — 순수 함수. JVM 테스트로 전부 고정된다.
 *
 * 이 앱이 하는 일은 하나다: **디코더를 언제 세우고 언제 내리는가.**
 *
 * 여태 그 판단은 `MirrorController` 안에 네 조각으로 흩어져 있었다 — `ensureDecoder`
 * (없으면 만든다), `prepareDecoder`(연결 직전에 새로 만든다), `setSurface`의
 * `pendingConnect` 플래그, 그리고 `dropSession`·`onDisplayChanged`의 직접 `stop()`.
 * 네 조각이 같은 질문에 각자 답하다 보니 조합이 어긋났다:
 *
 *  - `setSurface(null)`이 디코더를 그대로 뒀다. Surface가 파괴된 뒤에도 MediaCodec이
 *    죽은 표면에 그리고 있었고, 화면이 돌아오면 `pendingConnect`가 false라 아무도
 *    다시 세우지 않아 **검은 화면**이 남았다.
 *  - `prepareDecoder`도 연결 기계의 디코더 지시도 각자 `extendRunning`을 다시 봤다.
 *    같은 조건이 두 곳에 있으면 언젠가 한쪽만 바뀐다.
 *
 * 여기서는 **두 축과 하나의 파생값**뿐이다:
 *
 *     wanted  — Mac의 런처가 이 앱을 띄웠는가
 *     surface — 그릴 곳이 있는가 (SurfaceView의 수명)
 *     ────────────────────────────────────────────
 *     decoder = wanted ∧ surface        ← 파생, 어긋날 수 없다
 *
 * `pendingConnect`는 축이 아니라 이 파생값의 다른 이름이었다(`wanted ∧ ¬surface`).
 * 축으로 두면 셋이 서로 모순될 수 있고, 실제로 그랬다.
 */

/** 확장 앱의 전부. [decoder]만 기계가 관리하는 **실제 자원**이고 나머지는 관측한 사실이다. */
data class ExtendState(
    /** Mac의 런처가 이 앱을 띄웠는가. **경로와 무관하게 산다.** */
    val wanted: Boolean = false,
    /** 말할 채널이 있는가. 없어도 앱은 켜져 있을 수 있다(경로 복귀를 기다리는 중). */
    val linked: Boolean = false,
    /** 그릴 곳이 있는가. */
    val surface: Boolean = false,
    /** 디코더가 **실제로** 서 있는가 — 실행기가 [ExtendFx.StartDecoder]를 수행한 결과. */
    val decoder: Boolean = false,
) {
    /** UI가 보는 값. 세션이 붙었다고 참이 되지 않는다 — Mac이 아이콘을 눌러야 한다. */
    val isRunning: Boolean get() = wanted

    /**
     * 디코더가 서 있어야 하는가. **유일한 생산자**다.
     *
     * ★ [linked]가 **없다.** 경로가 잠깐 끊긴 동안에도 디코더를 들고 있는다 — Mac이 같은
     * 순간에 인코더와 가상 디스플레이를 들고 있기 때문이다(`ExtendMachine.swift`의
     * `unlinked`). 한쪽만 버리면 돌아올 때마다 코덱을 새로 세우고 키프레임을 기다리는
     * 검은 화면이 생기는데, 그건 무손실 재개라는 말을 무의미하게 만든다.
     */
    val decoderWanted: Boolean get() = wanted && surface

    /**
     * 사용자에게 보일 보조 문구. 켜져 있는데 화면이 안 오는 이유를 이 값이 말한다 —
     * 없으면 검은 화면만 남아 "연결이 안 됐다"로 읽힌다.
     */
    val detail: String? get() = when {
        !wanted -> null
        !surface -> "화면을 기다리는 중…"
        !linked -> "경로를 기다리는 중…"
        else -> null
    }
}

sealed interface ExtendEv {
    /** Mac이 확장을 띄웠다. */
    data object Launched : ExtendEv

    /** Mac이 확장을 내렸다. */
    data class Quit(val reason: String) : ExtendEv

    /**
     * 경로만 사라졌다 — 재접속·재협상·전송 전환. **아무것도 버리지 않는다.**
     *
     * Mac은 같은 순간 인코더와 가상 디스플레이를 들고 있다. 여기서 디코더를 버리면
     * 돌아올 때 반드시 키프레임을 기다려야 하고, 그 대기가 곧 검은 화면이다.
     * 유예 안에 돌아오면 아무 일도 없었던 게 된다.
     */
    data object Unlinked : ExtendEv

    /**
     * 기기가 떠났다 — 키도 채널도 돌아오지 않는다. 전부 놓는다.
     *
     * [Quit]과 갈라 두는 이유는 **보고 여부** 하나다. 여기서는 알릴 채널 자체가 없고,
     * Mac 쪽은 자기 `detach`에서 같은 결론에 이미 도달해 있다.
     */
    data object SessionLost : ExtendEv

    /** SurfaceView가 생겼다/사라졌다. */
    data class SurfaceChanged(val present: Boolean) : ExtendEv

    /**
     * 경로가 (다시) 살아났다 — 세션 확립·무손실 교체 공통.
     *
     * 있어야 하는데 없으면 세운다. 이미 서 있으면 아무 일도 안 한다(그게 무손실이다).
     */
    data object PathLive : ExtendEv

    /**
     * 디코더를 **새로** 세워야 한다 — 회전(새 해상도) 또는 콜드 재연결 직전.
     *
     * 굳어 있는 설정(해상도·색공간)을 바꾸려면 MediaCodec을 다시 만드는 수밖에 없다.
     */
    data class Restart(val reason: String) : ExtendEv
}

sealed interface ExtendFx {
    /** 디코더를 **새로** 만든다(기존 것이 있으면 버리고). */
    data object StartDecoder : ExtendFx
    data object StopDecoder : ExtendFx

    /**
     * 갱신(키프레임)을 청한다 — **새 디코더가 서는 모든 문에서 나온다.**
     *
     * 새 `MediaCodec`은 SPS/PPS를 키프레임에서만 얻을 수 있어 그 전 프레임을 전부 버린다.
     *
     * Mac의 확장 인코더는 2초마다 IDR을 내므로(`ExtendService`가 `keyframeIntervalSec: 2`로
     * 연다) 청하지 않아도 **언젠가는** 풀린다. 그래서 이건 교착을 푸는 장치가 아니라
     * **검은 화면을 최대 2초에서 왕복 한 번으로 줄이는** 장치다.
     *
     * 그래도 기계에 두는 이유: 디코더를 세우는 갈래가 셋인데(켜짐·화면 복귀·재구성) 갈래마다
     * 손으로 붙이면 언젠가 하나를 빠뜨리고, 빠뜨린 그 갈래만 유난히 오래 검다.
     */
    data object RequestKeyframe : ExtendFx

    /** 재조립기를 비운다 — 다음 세대·다음 해상도의 조각과 섞이면 안 된다. */
    data object ClearReassembly : ExtendFx

    /** 내 절반의 상태를 Mac에 알린다. */
    data class Report(val running: Boolean, val detail: String) : ExtendFx
    data class Log(val msg: String) : ExtendFx
}

data class ExtendStep(val state: ExtendState, val fx: List<ExtendFx> = emptyList())

/**
 * 파생 규칙을 **한 곳에서** 적용한다: 디코더는 [ExtendState.decoderWanted]와 같아야 한다.
 *
 * 모든 갈래가 여기를 지나므로 "이 갈래에서 디코더를 세우는 걸 빠뜨렸다"가 구조적으로
 * 불가능하다. [head]는 재구성처럼 **먼저** 일어나야 하는 정리이고, [tail]은 Mac 보고처럼
 * 자원 조작이 끝난 뒤에 나가야 하는 것이다.
 */
private fun settle(
    s: ExtendState,
    head: List<ExtendFx> = emptyList(),
    tail: List<ExtendFx> = emptyList(),
): ExtendStep = when {
    // ★ 세우는 것과 청하는 것은 **한 벌이다.** 새 디코더는 키프레임 없이는 한 장도 못 그리고,
    // Mac은 청하지 않으면 키프레임을 내지 않는다. 둘을 갈라 두면 언젠가 한쪽만 남는다.
    s.decoderWanted && !s.decoder ->
        ExtendStep(
            s.copy(decoder = true),
            head + ExtendFx.StartDecoder + ExtendFx.RequestKeyframe + tail,
        )

    !s.decoderWanted && s.decoder ->
        ExtendStep(
            s.copy(decoder = false),
            head + ExtendFx.StopDecoder + ExtendFx.ClearReassembly + tail,
        )

    else -> ExtendStep(s, head + tail)
}

/** 전이 함수. **순수**하며 모든 (상태, 사건) 쌍에 대해 정의된다. */
fun reduceExtend(s: ExtendState, ev: ExtendEv): ExtendStep = when (ev) {

    // 중복 지시여도 **보고는 다시 한다.** Mac이 상태를 놓쳐 다시 보낸 경우가 흔하고,
    // 그때 조용히 있으면 Mac은 영영 우리 절반이 섰는지 모른다.
    ExtendEv.Launched -> settle(
        s.copy(wanted = true),
        tail = listOf(ExtendFx.Report(true, s.copy(wanted = true).detail ?: "")),
    )

    is ExtendEv.Quit -> settle(
        s.copy(wanted = false),
        tail = listOf(ExtendFx.Report(false, ev.reason)),
    )

    // 알릴 채널이 없다 — 보고하지 않는다.
    ExtendEv.SessionLost -> settle(s.copy(wanted = false, linked = false))

    // ★ **아무것도 안 버린다.** 디코더는 `decoderWanted`에 [ExtendState.linked]가 없으므로
    // 그대로 서 있고, 경로가 돌아오면 프레임이 다시 흐르기 시작할 뿐이다.
    ExtendEv.Unlinked -> settle(s.copy(linked = false), tail = listOf(
        ExtendFx.Log("경로 상실 — 디코더 유지, 복귀를 기다린다"),
    ))

    is ExtendEv.SurfaceChanged -> settle(s.copy(surface = ev.present))

    ExtendEv.PathLive -> settle(s.copy(linked = true))

    is ExtendEv.Restart -> settle(
        s.copy(decoder = false),
        head = listOfNotNull(
            if (s.decoder) ExtendFx.StopDecoder else null,
            ExtendFx.ClearReassembly,
            ExtendFx.Log("디코더 재구성 — ${ev.reason}"),
        ),
    )
}
