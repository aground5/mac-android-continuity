package com.peri.display.vm

/**
 * 연결 수명주기 상태 기계 — **순수 함수**. Android·코루틴·소켓에 의존하지 않는다.
 *
 * 왜 갈라냈나: 예전에는 `ConnState` 열거형 하나 옆에 `restarting`, `restartQueued`,
 * `userClosed`, `switchingBearer`, `switchedFromLan`, `fatalError`, `pendingUdp`,
 * `pendingNetwork`, `reconnectAttempts`, `noNetworkRetry`… 15개의 플래그가 흩어져 있었고,
 * 실제 분기는 그 조합이 결정했다. `ConnState`는 UI 라벨로 전락해서 "STREAMING인데 세션이
 * 없다", "IDLE인데 재협상이 돌고 있다" 같은 조합이 문법적으로 가능했다. 그런 조합은 주석으로만
 * 막혀 있었고, 실제로 몇 번 새어 나와 실기기 버그가 됐다.
 *
 * 여기서는 서로 **직교하는 네 축**으로 나눈다:
 *
 *  - [Intent] : 돌아야 하는가. 사용자와 치명오류만 바꾼다. 다른 모든 축을 이긴다.
 *  - [Sess]   : 암호 세션의 수명. **연결보다 오래 산다** — 이 프로젝트의 핵심 전제다.
 *  - [Path]   : 제어 채널의 수명. 세션과 무관하게 죽고 되살아난다.
 *  - [Nego]   : BLE 재협상 라운드. 예전 플래그 7개를 이 하나가 대체한다.
 *
 * UI가 보던 [ConnState]는 이제 이 넷에서 **계산되는 값**이다([MirrorState.conn]).
 * 대입할 방법이 없으므로 라벨과 실제 상태가 다시 어긋날 수 없다.
 *
 * [reduce]는 순수하다 — 상태와 사건을 받아 새 상태와 [Fx] 목록을 낸다. 부작용 실행은
 * `MirrorController`의 몫이다. 덕분에 실기기에서만 재현되던 시나리오(겹침 없는 핸드오버,
 * 라운드 중 재전환, 재개 직후 keepalive 유실…)를 JVM 테스트로 고정할 수 있다 —
 * `MirrorMachineTest`가 그 목록이다.
 */

/**
 * UI가 보는 5개 상태. **이제 파생값 전용이다** — [MirrorState.conn]이 유일한 생산자이고
 * 대입하는 코드는 어디에도 없다.
 */
enum class ConnState { IDLE, CONNECTING, HANDSHAKING, STREAMING, ERROR }

/** 어느 전송인가. */
enum class Via { LAN, AOA }

/** 돌아야 하는가 — 사용자와 치명오류만 바꾼다. */
sealed interface Intent {
    data object Running : Intent

    /** 사용자가 끊었다 / Mac이 Bye를 보냈다 / 앱이 종료된다. 자동 복구 금지. */
    data object StoppedByUser : Intent

    /**
     * 신원 인증 실패처럼 **재시도가 무의미한** 거부.
     *
     * 예전 `fatalError`는 1회용 변수라 `onDisconnected`가 읽는 순간 사라졌고, 그래서 ERROR로
     * 멈춘 뒤 망이 한 번만 바뀌면 거부당한 그 Mac에게 다시 붙으러 갔다. 이제 래치라 막힌다.
     */
    data class StoppedFatal(val reason: String) : Intent
}

/** 암호 세션 — 전송이 죽어도 살아남는다(무손실 재개의 근거). */
sealed interface Sess {
    data object None : Sess

    /** ClientHello를 보냈고 ServerHello를 기다린다. */
    data object Establishing : Sess
    data class Live(val peer: String) : Sess
}

/** 제어 채널(TCP/AOA)의 수명. */
sealed interface Path {
    data object None : Path

    data class Connecting(val via: Via) : Path
    data class Live(val via: Via) : Path

    /** 같은 주소로 재시도하기 전 대기. [attempt]는 **이미 실패한** 횟수다. */
    data class Backoff(val attempt: Int) : Path

    /** 더 시도할 수단이 없다. [Intent]는 아직 Running이라 새 사건이 오면 되살아난다. */
    data class Failed(val reason: String) : Path
}

/**
 * BLE 재협상 라운드.
 *
 * 예전의 `restarting` + `restartQueued` + `noNetworkRetry` + `restartWatchdog` +
 * `pendingUdp`/`pendingTcpPort`/`pendingNetwork` 7개가 이 하나로 접힌다. 라운드가
 * **한 번에 하나**라는 불변식이 타입으로 표현돼, 두 라운드가 겹쳐 소켓 소유권이 꼬이는 일이
 * 구조적으로 불가능하다.
 */
sealed interface Nego {
    data object Idle : Nego

    /**
     * 광고에 비트를 세우고 Mac이 붙어 오기를 기다린다.
     *
     * **기본망이 없어도 라운드를 돈다.** 기본망이 없다는 건 인터넷 경로가 없다는 뜻이지
     * 연결할 수 없다는 뜻이 아니다 — 폰이 핫스팟 AP이고 Mac이 거기 붙어 있으면 업스트림이
     * 없어도 host 후보로 곧장 뚫린다. 대신 그때는 STUN을 건너뛴다(어차피 닿지 않아 타임아웃만
     * 쌓인다). 판단은 후보 수집 쪽(`MirrorController.gatherCandidates`)이 한다.
     */
    data class Requested(val reason: String) : Nego

    /**
     * Mac 후보를 받아 실제로 뚫는 중. [seamless]면 세션을 유지한 채 전송만 갈아끼운다.
     *
     * [announced]는 이 라운드가 **우리가 광고 비트를 올려서** 시작됐는지. Mac이 먼저 말을
     * 걸어온 경우(첫 연결, Mac 주도 재연결)는 false다 — 그때 라운드 끝에 비트를 내리면
     * BLE 광고를 괜히 껐다 켜게 되고, 연결돼 있던 central이 떨어질 수 있다.
     */
    data class Punching(val reason: String, val seamless: Boolean, val announced: Boolean) : Nego
}

/** LAN↔AOA 전환 — 예전 `switchingBearer`/`switchedFromLan` 두 플래그. */
enum class Switch {
    None,

    /** LAN을 의도적으로 내리는 중. 이때의 끊김은 사고가 아니다. */
    ToAoa,

    /** 지금 AOA는 동작하던 LAN에서 넘어온 것 — 끊기면 무선으로 되돌아간다. */
    AoaFromLan,
}

data class MirrorState(
    val intent: Intent = Intent.Running,
    val sess: Sess = Sess.None,
    val path: Path = Path.None,
    val nego: Nego = Nego.Idle,
    /** 라운드가 도는 동안 들어온 재협상 사유 — 끝나는 즉시 한 번 더 돈다. */
    val queued: String? = null,
    val switch: Switch = Switch.None,
    /**
     * **마지막으로 세션이 선 이후** 실패한 재접속 횟수.
     *
     * [Path]와 따로 두는 이유: TCP가 붙는 것과 세션이 서는 것은 다르다. 카운터를 경로에 실으면
     * `TransportUp`에서 리셋돼, Mac 앱이 떠 있지 않아 fd만 열리고 핸드셰이크가 영영 안 오는
     * 상황에서 무한히 재접속한다. 리셋 지점은 오직 [Ev.SessionLive]다.
     */
    val retries: Int = 0,
) {

    /** 지금 붙어 있(으려)는 전송. */
    val via: Via?
        get() = when (path) {
            is Path.Connecting -> path.via
            is Path.Live -> path.via
            else -> null
        }

    val negoBusy: Boolean get() = nego !is Nego.Idle

    /** 사용자에게 보일 상태 — **계산값**이다. 직접 대입하는 경로는 존재하지 않는다. */
    val conn: ConnState
        get() = when {
            intent is Intent.StoppedByUser -> ConnState.IDLE
            intent is Intent.StoppedFatal -> ConnState.ERROR
            path is Path.Failed -> ConnState.ERROR
            sess is Sess.Live && path is Path.Live -> ConnState.STREAMING
            sess is Sess.Establishing && path is Path.Live -> ConnState.HANDSHAKING
            sess is Sess.None && path is Path.None && nego is Nego.Idle -> ConnState.IDLE
            else -> ConnState.CONNECTING
        }

    /**
     * 상태 문구도 계산값이다. 예전엔 25곳에서 제각기 대입해서 문구와 실제 상태가 따로 놀았다
     * (재협상 실패 뒤 "기존 경로 유지"라고 떴는데 정작 경로가 없던 경우 등).
     */
    val status: String
        get() = when {
            intent is Intent.StoppedFatal -> intent.reason
            intent is Intent.StoppedByUser -> "연결 안 됨"
            nego is Nego.Requested -> "망 전환 감지 — Mac 호출 중…"
            nego is Nego.Punching && nego.seamless -> "새 경로로 전환 중…"
            nego is Nego.Punching -> "새 경로 뚫는 중…"
            path is Path.Failed -> path.reason
            path is Path.Backoff -> "재접속 ${path.attempt + 1}/$MAX_BACKOFF · ${(1L shl path.attempt)}s 후…"
            sess is Sess.Live && path is Path.Live ->
                if (path.via == Via.AOA) "USB 연결됨 (${sess.peer})" else "암호화 세션 (${sess.peer})"
            sess is Sess.Live -> "세션 유지 중 · 경로 재탐색"
            path is Path.Live -> "핸드셰이크 중 (양자내성 키교환)…"
            path is Path.Connecting -> if (path.via == Via.AOA) "USB 액세서리 연결 중…" else "연결 중…"
            else -> "연결 안 됨"
        }
}

/** 기계에 들어오는 사건 — 전부 외부에서 **관측된 사실**이다(의도가 아니라 사실). */
sealed interface Ev {
    /** 연결 시작 — 콜드 경로(첫 연결·콜드 펀칭·AOA). */
    data class StartConnect(val via: Via) : Ev

    /** 제어 채널이 붙었다(TCP connect 완료 / AOA fd 열림). */
    data object TransportUp : Ev

    /**
     * 제어 채널이 죽었다.
     *
     * [canRetryAddress]는 "같은 주소로 다시 붙어볼 수 있는가" — 무손실 교체로 성립한 세션은
     * host/port를 남기지 않아 false다(예전엔 이걸 몰라 `::1:0`으로 5초 타임아웃을 세 번 태웠다).
     */
    data class TransportDown(val err: String? = null, val canRetryAddress: Boolean = true) : Ev
    data class SessionLive(val peer: String) : Ev

    /**
     * 전송은 붙었는데 세션이 안 선다 — ServerHello가 안 온다.
     *
     * 예전엔 이 마감이 **AOA에만** 있었다(`connectAoa`의 8초 launch). LAN에서 Mac이 TCP는
     * 받고 ServerHello를 안 보내면 keepalive는 아직 시작 전이고 재협상 워치독은 이미 해제된
     * 뒤라, 아무 타이머도 없이 HANDSHAKING에 영원히 머물렀다. 이제 전송 종류와 무관하다.
     */
    data object HandshakeTimeout : Ev

    /** 재시도가 무의미한 거부(신원 인증 실패, 키 파생 실패). */
    data class Fatal(val reason: String) : Ev
    data object UserStop : Ev
    data object HostStop : Ev

    /** 재협상이 필요하다(망 전환 / USB 분리 / 돌아갈 주소 없음). */
    data class NegoNeeded(val reason: String) : Ev

    /** 기본망이 (다시) 생겼다. */
    data class NetworkUp(val reason: String) : Ev

    /** Mac 후보 도착 → 펀칭 개시. [candidatesFresh]가 false면 후보가 이미 낡았다. */
    data class MacAnswered(val candidatesFresh: Boolean) : Ev

    /** 라운드 종료(성공·실패·타임아웃 공통 출구). */
    data class NegoDone(val ok: Boolean, val note: String) : Ev

    /** 무손실 교체 완료 — 세션 유지, 전송만 새것. */
    data object PathSwapped : Ev

    /** 백오프 타이머 만료 → 지금 다시 붙어라. */
    data object BackoffElapsed : Ev

    /** LAN을 내리고 AOA로 넘어간다. */
    data object SwitchToAoa : Ev

    /** AOA 연결이 시작됐고, 그게 동작하던 LAN에서 넘어온 것이다. */
    data object AoaFromLan : Ev
}

/** 기계가 지시하는 부작용. 실행은 `MirrorController`가 한다. */
sealed interface Fx {
    data object AcquireWifiLock : Fx
    data object ReleaseWifiLock : Fx

    /** 세션·디코더·재조립기를 버린다. **재협상 중에는 절대 나오지 않는다.** */
    data object DropSession : Fx
    data object StartKeepalive : Fx
    data object StopKeepalive : Fx

    /**
     * 앱들에게 "말할 채널이 생겼다"를 알린다 — **세션이 서는 모든 문에서 나온다.**
     *
     * 무엇을 세울지는 앱마다 다르다: 확장은 디코더를, 공유는 재동의 대기를, 알림은 권한
     * 재확인을 한다. 기계는 그 어휘를 모르고 알 필요도 없다(원칙 1).
     *
     * 예전엔 디코더 생성이 기계 밖(`startPunch`/`connect`/`reconnectNow`)에 흩어져 있었고,
     * 그 사이에 `DropSession`이 끼면 아무도 다시 만들지 않았다. 영상은 도착하는데 그릴 곳이
     * 없어 검은 화면이 됐다. 세션 확립·무손실 재개 둘 다 여기를 지난다.
     */
    data object AttachApps : Fx
    data object CloseTransport : Fx
    data object SendBye : Fx

    /** 같은 주소로 [delayMs] 뒤 재접속. */
    data class ScheduleBackoff(val attempt: Int, val delayMs: Long) : Fx
    data object CancelBackoff : Fx
    data object ReconnectNow : Fx

    /** BLE 광고의 재협상 요청 비트. */
    data object RaiseNegoBit : Fx
    data object ClearNegoBit : Fx
    data class ArmNegoWatchdog(val ms: Long) : Fx
    data object CancelNegoWatchdog : Fx

    /** 세션이 서기를 기다리는 동안의 마감. */
    data class ArmHandshakeWatchdog(val ms: Long) : Fx
    data object CancelHandshakeWatchdog : Fx

    /** 라운드용 소켓/포트를 버린다. */
    data object ReleaseRoundSockets : Fx

    /** 세션을 유지한 채 전송만 갈아끼우는 펀칭. */
    data object PunchSeamless : Fx

    /** 새 핸드셰이크를 전제로 하는 펀칭. */
    data object PunchCold : Fx
    data object ResetCounters : Fx
    data class Log(val msg: String) : Fx
}

const val MAX_BACKOFF = 3
const val NEGO_TIMEOUT_MS = 30_000L

/**
 * 전송이 붙은 뒤 세션이 서기까지의 상한.
 *
 * 재협상 워치독보다 짧아야 한다 — 재협상으로 뚫은 경로에서 핸드셰이크가 막히면 라운드 자체를
 * 실패로 접기 전에 이 마감이 먼저 걸려 전송을 정리해야 한다.
 */
const val HANDSHAKE_TIMEOUT_MS = 8_000L

/** 새 상태 + 그 상태로 가며 실행할 부작용. */
data class Step(val state: MirrorState, val fx: List<Fx> = emptyList())

/**
 * 전이 함수. **순수**하며 모든 (상태, 사건) 쌍에 대해 정의된다.
 *
 * 규칙 하나: [Fx.DropSession]을 내보내는 경로는 사용자 종료·치명오류·복구 불가로 끝난
 * 재협상뿐이다. 재협상 중 옛 전송이 죽는 건 **예상된 일**이므로 세션을 건드리지 않는다 —
 * 예전엔 이 구분이 없어서, Mac은 RESUME을 받아 채널을 재바인딩했는데 Android만 세션을 버린
 * 반쪽 상태가 났다.
 */
fun reduce(s: MirrorState, ev: Ev): Step = when (ev) {

    is Ev.StartConnect -> Step(
        MirrorState(
            intent = Intent.Running,   // 사용자가 다시 시작한다 = 정지 래치를 푼다
            sess = Sess.None,
            path = Path.Connecting(ev.via),
            // 재협상 중에 콜드 연결이 시작될 수 있다(USB 분리 직후 등) — 라운드는 그대로 둔다.
            nego = s.nego,
            queued = s.queued,
            // 전환 의도는 여기까지다. AOA가 LAN에서 넘어온 것이라는 사실은 뒤이은
            // [Ev.AoaFromLan]이 따로 세운다 — 상태를 물려받지 않아야 오독이 안 생긴다.
            switch = Switch.None,
        ),
        buildList {
            add(Fx.CancelBackoff)
            if (s.sess !is Sess.None) add(Fx.DropSession)
            add(Fx.AcquireWifiLock)
        },
    )

    is Ev.AoaFromLan -> Step(s.copy(switch = Switch.AoaFromLan))

    is Ev.TransportUp -> {
        val via = s.via ?: Via.LAN
        // 사용자가 끊었거나 치명 거부로 멈춘 뒤에 뒤늦게 붙은 소켓 — 받아들이면 정지 래치가
        // 걸린 채로 전송만 살아 있는 모순된 상태가 된다. 그냥 닫는다.
        if (s.intent !is Intent.Running) {
            Step(s, listOf(Fx.CloseTransport, Fx.Log("정지 상태에서 뒤늦게 붙은 전송 — 폐기")))
        } else if (s.sess is Sess.Live) {
            // 세션이 이미 살아 있으면 이건 무손실 재개다 — 핸드셰이크를 새로 하지 않는다.
            Step(s.copy(path = Path.Live(via)))
        } else {
            Step(
                s.copy(sess = Sess.Establishing, path = Path.Live(via)),
                listOf(Fx.ArmHandshakeWatchdog(HANDSHAKE_TIMEOUT_MS)),
            )
        }
    }

    // 전송은 살아 있는데 세션이 안 선다 → 그 전송을 버린다. 뒤따르는 TransportDown이
    // 재접속이냐 재협상이냐를 평소대로 결정한다 — 여기서 따로 정하지 않는다.
    is Ev.HandshakeTimeout ->
        if (s.sess is Sess.Establishing) {
            Step(s, listOf(Fx.CloseTransport, Fx.Log("핸드셰이크 응답 없음 (${HANDSHAKE_TIMEOUT_MS / 1000}s) → 링크 정리")))
        } else {
            Step(s)   // 이미 세션이 섰거나 전송이 죽었다 — 낡은 타이머
        }

    // 전송이 사라졌다 = 핸드셰이크를 기다릴 이유도 사라졌다. 어느 갈래로 가든 공통이라
    // 바깥에서 한 번에 붙인다(갈래마다 손으로 붙이면 언젠가 하나를 빠뜨린다).
    is Ev.TransportDown -> transportDown(s, ev).let { Step(it.state, listOf(Fx.CancelHandshakeWatchdog) + it.fx) }

    is Ev.BackoffElapsed -> {
        val p = s.path
        // 그 사이 상황이 바뀌었으면 낡은 타이머는 버린다.
        if (s.intent !is Intent.Running || s.negoBusy || p !is Path.Backoff) Step(s)
        else Step(s.copy(path = Path.Connecting(Via.LAN)), listOf(Fx.ReconnectNow))
    }

    // 세션이 섰다 = 이 경로가 실제로 쓸모 있다 → 재시도 예산을 되돌린다.
    is Ev.SessionLive ->
        if (s.path is Path.Live) {
            Step(
                s.copy(sess = Sess.Live(ev.peer), retries = 0),
                listOf(Fx.CancelHandshakeWatchdog, Fx.AttachApps, Fx.StartKeepalive),
            )
        } else {
            // ★ 경로가 이미 죽은 뒤에 도착한 ServerHello. read 루프가 프레임을 넘긴 직후
            // 소켓이 죽으면 `TransportDown`이 먼저 처리되고 이게 나중에 온다. 그대로 받으면
            // "세션은 살아 있는데 실을 전송이 없고 아무 타이머도 없는" 상태에 갇힌다 —
            // keepalive는 세션이 서야 시작하는데 보낼 채널이 없으니 감지도 못 한다.
            Step(s, listOf(Fx.CancelHandshakeWatchdog, Fx.Log("경로가 죽은 뒤 도착한 세션 확립 — 폐기")))
        }

    is Ev.Fatal -> Step(
        MirrorState(intent = Intent.StoppedFatal(ev.reason)),
        listOf(
            Fx.StopKeepalive, Fx.DropSession, Fx.CloseTransport, Fx.CancelBackoff,
            Fx.ClearNegoBit, Fx.CancelNegoWatchdog, Fx.CancelHandshakeWatchdog,
            Fx.ReleaseRoundSockets, Fx.ReleaseWifiLock,
        ),
    )

    is Ev.UserStop, is Ev.HostStop -> Step(
        MirrorState(intent = Intent.StoppedByUser),
        // Bye는 세션·전송이 **살아 있을 때** 먼저 나가야 한다 — 목록의 순서가 곧 의미다.
        buildList {
            if (ev is Ev.UserStop && s.sess is Sess.Live) add(Fx.SendBye)
            addAll(
                listOf(
                    Fx.StopKeepalive, Fx.CloseTransport, Fx.DropSession, Fx.CancelBackoff,
                    Fx.ClearNegoBit, Fx.CancelNegoWatchdog, Fx.CancelHandshakeWatchdog,
                    Fx.ReleaseRoundSockets, Fx.ReleaseWifiLock,
                ),
            )
        },
    )

    // 전환은 **동작 중인 LAN 경로가 있을 때만** 의미가 있다. 이미 AOA로 붙어 있거나 정지
    // 상태인데 전환 의도를 세우면, 그 뒤에 오는 진짜 AOA 끊김이 "의도된 전환"으로 오독돼
    // 무선 복귀가 건너뛰어진다.
    is Ev.SwitchToAoa ->
        if (s.intent is Intent.Running && (s.path as? Path.Live)?.via == Via.LAN) {
            Step(s.copy(switch = Switch.ToAoa), listOf(Fx.CloseTransport))
        } else {
            Step(s, listOf(Fx.Log("AOA 전환 요청이지만 갈아탈 LAN 경로가 없다 — 무시")))
        }

    is Ev.NegoNeeded -> if (s.intent !is Intent.Running) Step(s) else negoRequest(s, ev.reason)

    is Ev.NetworkUp -> when {
        s.intent !is Intent.Running -> Step(s)
        // 애초에 복구할 게 없으면 아무것도 하지 않는다.
        s.conn == ConnState.IDLE -> Step(s)
        // 돌고 있으면 예약된다(삼키지 않는다) — 그게 negoRequest의 계약이다.
        else -> negoRequest(s, ev.reason)
    }

    is Ev.MacAnswered -> when {
        // ★ 치명 거부만 막는다. 사용자 정지는 **여기서 풀린다.**
        //
        // Mac이 BLE로 붙어 와 후보까지 줬다 = 사용자가 Mac에서 다시 연결한 것이다. 그걸
        // 무시하면 '끊기' 이후 영영 못 붙는다 — 래치를 푸는 유일한 사건이 [Ev.StartConnect]인데
        // 그건 `PunchCold`가 부르고, `PunchCold`는 이 갈래에서만 나오기 때문이다(순환).
        // 예전 코드가 `startPunch`에서 `userClosed = false`를 하던 게 이 역할이었다.
        s.intent is Intent.StoppedFatal ->
            Step(s, listOf(Fx.Log("치명 거부 상태 — Mac의 연결 시도 무시")))

        // 라운드가 도는 사이 망이 또 바뀌어 후보가 죽었다 → 버리고 새로 돈다.
        // 래치 해제는 아래 갈래와 **똑같이** 먼저 한다 — 한쪽에만 넣었더니 정지 상태인데
        // 라운드만 도는 조합이 생겨 모델 검사에 걸렸다.
        !ev.candidatesFresh -> negoRequest(unlatched(s).copy(nego = Nego.Idle), "후보 낡음").let {
            Step(it.state, listOf(Fx.ReleaseRoundSockets, Fx.CancelNegoWatchdog) + it.fx)
        }

        else -> {
            val base = unlatched(s)
            // **세션이 살아 있는가만 본다.** 상태(STREAMING)까지 요구하면 라운드 도중에 옛 전송이
            // 죽어 경로가 사라진 순간 콜드로 떨어져, 멀쩡한 세션을 두고 새 핸드셰이크를 한다.
            val seamless = base.sess is Sess.Live
            val (reason, announced) = when (val n = base.nego) {
                is Nego.Requested -> n.reason to true
                is Nego.Punching -> n.reason to n.announced
                Nego.Idle -> "Mac 주도 연결" to false
            }
            Step(
                base.copy(nego = Nego.Punching(reason, seamless, announced)),
                listOf(
                    // ★ 펀칭 단계에도 마감을 다시 건다. Mac이 먼저 말을 걸어와 라운드가
                    // `Idle → Punching`으로 바로 들어오는 경로(첫 연결, Mac 주도 재협상)에는
                    // 애초에 워치독이 없었고, 펀칭 스레드가 예외로 죽으면 아무도 라운드를
                    // 끝내지 않아 그대로 멎었다.
                    Fx.ArmNegoWatchdog(NEGO_TIMEOUT_MS),
                    if (seamless) Fx.PunchSeamless else Fx.PunchCold,
                ),
            )
        }
    }

    // 재개 경로엔 핸드셰이크가 없어 keepalive를 되살릴 곳이 여기뿐이다. 빠뜨리면 Ping이 끊겨
    // Mac의 생존감지(6s)가 방금 살려낸 세션을 도로 죽인다(실기기에서 겪음).
    is Ev.PathSwapped ->
        if (s.sess is Sess.Live) {
            Step(s.copy(path = Path.Live(Via.LAN)), listOf(Fx.AttachApps, Fx.StartKeepalive))
        } else {
            // ★ 재개할 세션이 없는데 교체 성공 보고가 왔다 = 워치독(30s)이 먼저 터져 라운드를
            // 접은 뒤 펀칭 스레드가 뒤늦게 성공한 경합이다. 그 소켓은 Mac 쪽에서 RESUME으로
            // 붙었지만 우리에겐 실을 세션이 없어 쓸 수 없다 — 받아들이면 "전송은 살아 있는데
            // 세션도 핸드셰이크도 없고 아무 타이머도 없는" 상태에 갇힌다.
            Step(s, listOf(Fx.CloseTransport, Fx.Log("세션 없이 도착한 교체 성공 — 늦은 보고, 폐기")))
        }

    is Ev.NegoDone -> {
        // ★ 우리가 올린 적 없는 비트를 내리지 않는다. `clearIceRestart`는 광고를 껐다 켜는데,
        // Mac이 먼저 말을 걸어온 라운드(첫 연결)에서 그러면 붙어 있는 central을 떨어뜨릴 수 있다.
        // 예전 코드에서 이 정리가 재협상 라운드에서만 돌았던 건 우연이 아니라 필요였다.
        val announced = when (val n = s.nego) {
            is Nego.Requested -> true
            is Nego.Punching -> n.announced
            Nego.Idle -> false
        }
        val cleanup = listOfNotNull(
            Fx.ReleaseRoundSockets, Fx.CancelNegoWatchdog,
            if (announced) Fx.ClearNegoBit else null,
            Fx.Log("라운드 종료: ${if (ev.ok) "성공" else "실패"} — ${ev.note}"),
        )
        val idle = s.copy(nego = Nego.Idle)
        val q = s.queued
        when {
            // 라운드 중에 망이 또 바뀌었다 — 방금 결과는 이미 낡았을 수 있으므로 무조건 한 번 더.
            q != null -> negoRequest(idle.copy(queued = null), q).let { Step(it.state, cleanup + it.fx) }

            // ★ 성공 보고는 **경로가 있을 때만** 성공이다 — 다만 "있다"에는 아직 수립 중인
            // 경로도 포함된다.
            //
            // 이걸 `Path.Live`로만 좁혔더니 콜드 펀칭이 통째로 깨졌다. 펀칭이 성공한 그 순간
            // 경로는 `Connecting`이다 — 소켓을 채택하고 read 루프가 붙어 `TransportUp`이
            // 올라오는 건 한 박자 뒤다. 그 틈에 도착한 성공 보고를 실패로 접으면 `DropSession`이
            // 돌아 디코더를 날리고, 뒤이어 핸드셰이크는 정상적으로 끝나 영상은 오는데 그릴
            // 디코더가 없는 검은 화면이 된다(실기기에서 겪음).
            //
            // 반대로 `Path.None`이면 진짜로 실을 곳이 없다 — 워치독이 먼저 터져 라운드를 접은
            // 뒤 도착한 늦은 성공 보고가 그렇고, 그건 받아들이면 영구 정지점이 된다.
            ev.ok && idle.path !is Path.None -> Step(idle, cleanup)

            // 실패 보고여도 옛 경로가 살아 있으면 잃은 게 없다.
            idle.path is Path.Live -> Step(idle, cleanup)

            // 돌아갈 경로가 없다. 여기서 멈추지 않으면 CONNECTING에 영원히 매달린다.
            else -> Step(
                idle.copy(
                    sess = Sess.None,
                    path = Path.Failed("연결 실패 — Mac이 응답하지 않습니다 (${ev.note})"),
                ),
                cleanup + listOf(Fx.DropSession, Fx.CancelHandshakeWatchdog, Fx.ReleaseWifiLock),
            )
        }
    }
}

/** [Ev.TransportDown]의 갈래들 — 공통 뒷정리는 호출부가 붙인다. */
private fun transportDown(s: MirrorState, ev: Ev.TransportDown): Step = when {
        // 사용자/치명 정지 중의 끊김은 정리 과정의 일부다.
        s.intent !is Intent.Running ->
            Step(s.copy(sess = Sess.None, path = Path.None), listOf(Fx.StopKeepalive, Fx.DropSession))

        // LAN→AOA 전환의 의도된 끊김. 세션은 어차피 새로 맺으므로 버린다.
        //
        // ★ 여기서 `switch`를 반드시 내린다. 남겨 두면 그 뒤에 일어나는 **진짜** AOA 끊김
        // (케이블 분리)이 "의도된 전환"으로 오독돼 이 갈래로 다시 들어오고, 무선 복귀
        // 재협상이 통째로 건너뛰어진다. 전환 의도는 이 끊김 하나만 덮는다.
        s.switch == Switch.ToAoa -> Step(
            s.copy(sess = Sess.None, path = Path.None, switch = Switch.None),
            listOf(Fx.StopKeepalive, Fx.DropSession, Fx.ReleaseWifiLock),
        )

        // ★ 재협상 중이고 세션이 살아 있다 — 옛 전송의 죽음은 예상된 일이다.
        s.negoBusy && s.sess is Sess.Live -> Step(
            s.copy(path = Path.None),
            listOf(Fx.StopKeepalive, Fx.Log("재협상 중 옛 전송 종료 — 세션·디코더 유지")),
        )

        // AOA가 죽었다(케이블 분리). 스트리밍 중이었거나 LAN에서 넘어온 것이면 무선으로 복귀.
        s.via == Via.AOA -> {
            val fallback = s.sess is Sess.Live || s.switch == Switch.AoaFromLan
            val base = s.copy(sess = Sess.None, path = Path.None, switch = Switch.None)
            val cleanup = listOf(Fx.StopKeepalive, Fx.DropSession)
            if (fallback) {
                // 망 전환과 **완전히 같은 사건**으로 다룬다: BLE로 새 후보를 교환해 다시 뚫는다.
                val next = negoRequest(base, "USB 분리")
                Step(next.state, cleanup + Fx.AcquireWifiLock + next.fx)
            } else {
                Step(
                    base.copy(path = Path.Failed("USB 링크 종료")),
                    cleanup + Fx.ReleaseWifiLock,
                )
            }
        }

        // 평범한 LAN 끊김.
        else -> {
            val cleanup = listOf(Fx.StopKeepalive, Fx.DropSession, Fx.ResetCounters)
            val bare = s.copy(sess = Sess.None, path = Path.None)
            when {
                // 재협상이 이미 예약/진행 중이면 확정적으로 죽은 옛 주소를 두드리지 않는다.
                s.negoBusy -> Step(bare, cleanup)

                // 돌아갈 주소가 없다(무손실 경로로 붙은 세션) → 유일한 복구 수단은 재협상.
                !ev.canRetryAddress -> negoRequest(bare, "재접속 주소 없음")
                    .let { Step(it.state, cleanup + it.fx) }

                s.retries >= MAX_BACKOFF -> Step(
                    bare.copy(path = Path.Failed("재접속 실패 (${MAX_BACKOFF}회) · '연결'로 재시도")),
                    cleanup + Fx.ReleaseWifiLock,
                )

                else -> Step(
                    bare.copy(path = Path.Backoff(s.retries), retries = s.retries + 1),
                    cleanup + Fx.ScheduleBackoff(s.retries, 1000L shl s.retries),
                )
            }
        }
    }

/**
 * 재협상 요청의 공통 처리 — 삼키지 않고 **예약**한다.
 *
 * 예전엔 진행 중이면 그냥 `return`했다. 그런데 진행 중인 라운드는 낡은 망에서 후보를 모았을 수
 * 있어 실패할 운명인데, 그 사이 도착한 새 망 소식을 버리면 아무도 다시 시도하지 않아 영구히
 * 멈췄다(실기기 로그: 라운드가 도는 중에 온 `기본망 전환 824 → 825`가 무시되고 그대로 정지).
 */
/**
 * 사용자 정지 래치를 푼다 — **Mac이 붙어 온 경우에만** 쓴다.
 *
 * 치명 거부는 여기서 안 풀린다(그건 [Ev.StartConnect]로 사용자가 직접 다시 시작해야 한다).
 */
private fun unlatched(s: MirrorState): MirrorState =
    if (s.intent is Intent.StoppedByUser) s.copy(intent = Intent.Running) else s

private fun negoRequest(s: MirrorState, reason: String): Step = when {
    s.negoBusy -> Step(s.copy(queued = reason), listOf(Fx.Log("재협상 예약: $reason")))

    else -> Step(
        s.copy(nego = Nego.Requested(reason)),
        listOf(
            // 옛 주소 재접속은 확정적으로 죽은 주소를 두드리는 것이라 즉시 걷어낸다.
            Fx.CancelBackoff, Fx.RaiseNegoBit, Fx.ArmNegoWatchdog(NEGO_TIMEOUT_MS),
            Fx.Log("★ 재협상 요청: $reason"),
        ),
    )
}
