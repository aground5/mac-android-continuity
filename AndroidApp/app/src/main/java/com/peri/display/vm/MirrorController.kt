package com.peri.display.vm

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.protobuf.ByteString
import com.peri.display.DisplaySpec
import com.peri.display.apps.AppHost
import com.peri.display.apps.DetachReason
import com.peri.display.apps.SessionChannel
import com.peri.display.proto.Bye
import com.peri.display.proto.ClientNetwork
import com.peri.display.proto.ControlMessage
import com.peri.display.proto.Orientation
import com.peri.display.proto.Ping
import com.peri.display.proto.Renegotiate
import com.peri.display.proto.TransportFeedback
import com.peri.display.proto.UdpAccept
import com.peri.display.proto.UdpClose
import com.peri.display.proto.UdpOpen
import com.peri.display.transport.AoaClient
import com.peri.display.transport.Bearer
import com.peri.display.transport.CandidateBundle
import com.peri.display.transport.CandidateTransport
import com.peri.display.transport.LanClient
import com.peri.display.transport.MiniRtpPacket
import com.peri.display.transport.NetworkWatcher
import com.peri.display.transport.PathNegotiator
import com.peri.display.transport.WireFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "MirrorController"

/**
 * 영상이 흐를 때의 Ping 간격. 지터버퍼가 먹는 RTT의 신선도가 여기서 나온다.
 */
private const val STREAM_PING_MS = 1000L

/**
 * 영상이 없을 때의 Ping 간격 — **세션 유지 비용의 대부분이 여기 있다.**
 *
 * 알림만 받는 세션에서 Ping이 하는 일은 생존 확인뿐이다. 1초로 두면 분당 60번 라디오를
 * 깨우고 그 사이 Wi-Fi가 절전으로 못 들어간다. 5초면 12번이다.
 *
 * 더 늘리지 않는 이유: 이 값이 곧 **끊긴 걸 알아채는 데 걸리는 시간**([DEAD_AFTER_PERIODS]
 * 배)이고, 그 사이 Mac 화면에는 붙어 있는 것처럼 보인다.
 */
private const val IDLE_PING_MS = 5000L

/** 답이 이만큼의 주기 동안 없으면 죽은 것으로 본다. */
private const val DEAD_AFTER_PERIODS = 3

/**
 * 영상 앱이 떠 있는데 데이터그램이 이만큼 안 오면 **경로가 어긋난 것으로 보고 재등록한다.**
 *
 * 짧게 잡을 수 없다: 키프레임 간격과 일시적 혼잡을 굶주림으로 오해하면 멀쩡한 스트림에
 * HELLO를 계속 쏘게 된다. 길게도 못 잡는다 — 이 값이 곧 **화면이 얼어 있는 시간**이다.
 * 2초면 사람이 "끊겼나?" 하기 직전이고, 30fps 기준 60프레임이라 정상 흐름에서는 절대
 * 닿지 않는다.
 */
private const val VIDEO_STARVED_MS = 2000L

/**
 * 재등록을 이만큼 해 보고도 영상이 없으면 **전제가 틀린 것**으로 보고 라운드를 다시 돈다.
 *
 * 재등록이 고치는 건 "교체 직후 쏜 HELLO 한 발이 유실됐다" 하나뿐이고, 그건 한두 번이면
 * 닿는다. 그 이상은 세대가 갈렸거나 구멍이 닫힌 것이라 같은 HELLO를 아무리 반복해도 안 된다.
 */
private const val MAX_HELLO_RETRIES = 2

/**
 * **세션 오케스트레이터.** 전송을 붙이고, 키를 세우고, 프레임을 임자에게 넘긴다.
 *
 * 이 클래스가 하지 **않는** 일이 정체성이다:
 *
 *  · **연결 수명주기 판단** — 전부 [MirrorMachine]의 순수 [reduce]가 한다. 여기는 관측한
 *    사실을 [Ev]로 올리고 돌려받은 [Fx]를 실행하는 실행기다.
 *  · **앱의 절반** — 확장·공유·알림은 [AppHost] 아래에 각자의 순수 기계와 실행기를 갖는다.
 *    세션은 앱의 이름을 부르지 않고, `AppControl`과 자기 것이 아닌 제어 메시지를 통째로
 *    등록부에 넘길 뿐이다. **새 앱 추가에 이 파일은 0줄 바뀐다.**
 *  · **경로를 뚫는 일** — STUN·펀칭·소켓 소유권은 [PathNegotiator]가 갖는다.
 *  · **봉인** — 세션 키와 세대 키, 그리고 그 둘 중 무엇을 쓸지는 [SessionCrypto]가 정한다.
 *  · **원시 수치** — 손실·왕복·클럭은 [LinkMetrics]에 모인다.
 *  · **신원과 세션 키를 세우는 절차** — PQ-STS 세 패스는 [StsClient]가 끝까지 끌고 간다.
 *
 * 남은 것은 그 여섯을 잇는 배선이다. 그래서 이 파일에서 무언가를 찾을 수 없다면 그건
 * 대개 **여기 있으면 안 되는 것**이고, 어느 이웃의 것인지가 위 목록에 적혀 있다.
 */
class MirrorController(context: Context) {

    private val appContext = context.applicationContext
    // (제거됨) `requirePairing` — 미페어링 Mac을 거부할지의 토글.
    //
    // 값은 줄곧 true였고 false 가지는 TOFU 자동핀(처음 보는 Mac을 사람 확인 없이 신뢰)이었다.
    // 이제 신뢰는 **BLE 페어링에서만** 생긴다.

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = LanClient(scope)
    /**
     * 현재 활성 전송(LAN 기본, AOA면 교체).
     *
     * (제거됨) 별도의 `aoa` 필드. 마지막으로 연 `AoaClient`를 따로 붙들고 `bearer === aoa`로
     * 케이블 여부를 판단했는데, 그 판단은 `bearer is AoaClient` 한 줄과 같으면서 두 필드가
     * 어긋날 여지만 만들었다.
     */
    private var bearer: Bearer = client

    /** 기본망 감시 — LTE↔Wi-Fi 핸드오버를 keepalive 타임아웃보다 먼저 잡는다. */
    private val netWatcher = NetworkWatcher(context)

    private val crypto = SessionCrypto()
    private val metrics = LinkMetrics()

    /**
     * 신원과 세션 키를 세우는 절차 전부. **로그 지문의 출처이기도 하다** — 그래서 다른
     * 필드보다 먼저 선다(뒤에 두면 초기화 순서가 지문을 읽는 코드와 얽힌다).
     */
    private val sts = StsClient(appContext)

    private val paths = PathNegotiator(client, netWatcher) { lifecycle(it) }

    /**
     * **앱이 세션에 대해 아는 전부.** 하나만 있고 컨트롤러와 수명을 같이한다 — 이 기기는
     * 한 번에 한 Mac과만 붙으므로 Mac처럼 세션마다 새로 만들 이유가 없다. 대신 전송을
     * 매번 지금 값으로 물으므로 LAN↔AOA 전환과 무손실 교체를 그대로 살아남는다.
     */
    private val channel = SessionChannel(crypto) { bearer }

    /** 이 기기의 앱 등록부. 세션은 여기 있는 앱들의 이름을 모른다. */
    private val apps = AppHost(scope, appContext, channel)

    // MARK: - 세션 유지 비용
    //
    // 라디오를 깨워 두는 것도(Wi-Fi 락) 주기적으로 두드리는 것도(keepalive), 근거는 둘의
    // **곱**이다: 기계가 세션을 원하는가 ∧ 지금 영상이 흐르는가.
    //
    // 기계는 서비스 어휘를 모르므로(원칙 1) "세션이 섰다"까지만 말할 수 있는데,
    // `WIFI_MODE_FULL_LOW_LATENCY`가 필요한 건 **영상이 흐를 때**다. 알림만 받는 세션에
    // 라디오 절전을 꺼 두면 아무 이득 없이 배터리만 먹는다. 그래서 기계의 뜻과 앱의 요구를
    // 여기서 AND 한다 — 기계는 그대로 두고(모델검사도 그대로) 앱을 아는 층에서 결정이 난다.

    private val wifiLock: WifiManager.WifiLock? by lazy {
        val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wm?.createWifiLock(mode, "PeriDisplay:stream")
    }

    /** 기계가 세션을 위해 락을 원하는가. **이것만으로는 안 잡는다.** */
    private var sessionWantsLock = false

    private fun updateWifiLock() {
        if (sessionWantsLock && apps.streaming) acquireWifiLock() else releaseWifiLock()
    }

    /**
     * 영상 앱이 서거나 누웠다 — **세션 유지 비용을 다시 맞춘다.**
     *
     * ★ 영상 채널을 **여기서 열지 않는다.** 세대를 여는 쪽은 Mac이다(`onUdpOpen`) — 앱을
     * 띄우고 내리는 결정이 거기 있고, 세대 이름을 부여하는 쪽도 하나여야 한다.
     */
    private fun appsChanged() {
        updateWifiLock()
        // 주기가 바뀌었으면 곧바로 반영한다. 안 그러면 확장을 켠 뒤에도 다음 바퀴가 돌 때까지
        // (최대 [IDLE_PING_MS]) 느린 채로 돈다 — 정작 RTT가 가장 필요한 구간이다.
        if (keepaliveJob != null) startKeepalive()
    }

    // 락은 성능 최적화일 뿐 — 실패(권한 등)가 연결 파이프라인을 죽여선 안 되므로 방어적으로.
    private fun acquireWifiLock() {
        runCatching { wifiLock?.let { if (!it.isHeld) it.acquire() } }
            .onFailure { Log.w(TAG, "wifiLock acquire 실패: ${it.message}") }
    }

    private fun releaseWifiLock() {
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
            .onFailure { Log.w(TAG, "wifiLock release 실패: ${it.message}") }
    }

    // MARK: - UI가 보는 것

    /** 확장 앱이 켜져 있는가 — UI가 전체화면으로 갈 조건. */
    val extendRunning: Boolean get() = apps.extend.running

    /** 화면 공유가 이 기기에서 도는 중인가. */
    val shareRunning: Boolean get() = apps.share.running

    /** 알림 전달이 켜져 있는가 — UI가 권한 안내를 띄울지 정하는 근거. */
    val notifyRunning: Boolean get() = apps.notify.running

    fun setSurface(surface: Surface?) = apps.extend.setSurface(surface)

    private var displaySpec: DisplaySpec? = null

    // 접속 정보 (자동 재접속용). 비어 있으면 = 돌아갈 주소가 없다(무손실 경로로 붙은 세션).
    private var host = ""
    private var port = 0

    @Volatile private var lastAoaSwitchFailAt = 0L // 전환 실패 시각 → 쿨다운 (LAN↔AOA 오실레이션 방지)
    private val aoaSwitchCooldownMs = 20_000L

    private var keepaliveJob: Job? = null

    // MARK: - 상태 기계

    /**
     * 유일한 상태. 락 안에서만 바뀌므로 순수 기계와 실행기 사이에 레이스가 없다.
     * Compose에는 [state]/[statusMessage]로 **미러링**한다(계산값이라 대입 경로가 없다).
     */
    @Volatile private var machine = MirrorState()

    var state by mutableStateOf(ConnState.IDLE)
        private set
    var statusMessage by mutableStateOf("연결 안 됨")
        private set

    /** 진단 화면이 상태를 다시 읽게 만드는 틱. 기계 전이·1초 통계마다 오른다. */
    var diagTick by mutableStateOf(0)
        private set

    /**
     * 진단 스냅샷 — 뷰가 내부를 직접 보지 않게 여기서 한 번에 찍어 준다.
     *
     * 기계 축은 [MirrorState] 그대로다. 뷰가 보는 값과 기계가 결정에 쓰는 값이 같은 값이라
     * 어긋날 수가 없다는 게 이 설계의 요점이고, 진단에서 특히 중요하다.
     */
    fun connDiagnostics(): ConnDiagnostics {
        val m = machine
        return ConnDiagnostics(
            conn = m.conn,
            summary = m.status,
            intent = m.intent.toString(),
            sess = m.sess.toString(),
            path = m.path.toString(),
            nego = m.nego.toString(),
            queued = m.queued,
            switch = m.switch.toString(),
            retries = m.retries,
            via = m.via?.toString() ?: "—",
            fingerprint = sts.fingerprint,
            negoRound = negoRound,
        )
    }

    /** 기계가 모르는 원시 수치 — 디코더·망·피드백. */
    fun linkDiagnostics(): LinkDiagnostics {
        val st = apps.extend.stats()
        return LinkDiagnostics(
            transport = netWatcher.transport,
            demotedAddresses = netWatcher.deprioritizedAddresses().size,
            decoderUp = apps.extend.decoderUp,
            playoutBufferMs = apps.extend.bufferMs,
            renderOnArrival = apps.extend.renderOnArrival,
            queueDelayMs = st?.queueDelayMs ?: 0,
            pipeNetMs = st?.pipeNetMs ?: 0,
            pipeJbufMs = st?.pipeJbufMs ?: 0,
            pipeDecMs = st?.pipeDecMs ?: 0,
            rttMs = metrics.rttMs,
            sincePongMs = metrics.sincePongMs,
            fbReceived = metrics.received,
            fbSpan = metrics.span,
        )
    }

    // MARK: - 로그 형식
    //
    // 한 줄 문법:  `[dev:<지문8>][r:<라운드>] 사건 → 상태 (축…)`
    //
    // 왜 이 셋인가 — 실기기 디버깅에서 실제로 막혔던 것들이다:
    //  · **지문**: Mac 로그는 `dev[5901-AB78-…]`로 기기를 키잉하는데 Android는 아무 키도 안 찍어서,
    //    두 로그를 타임스탬프로 눈대중 정렬해야 했다. 같은 키를 양쪽에 찍으면 grep 한 번으로 붙는다.
    //  · **라운드**: 재협상이 겹치면(예약 → 재실행) 어느 라운드의 줄인지 구분할 수 없었다.
    //  · **사실만**: `bye 전송` 같은 줄이 실제로는 "큐에 넣었다"였다. 그 거짓말 때문에 원인을
    //    찾는 데 한참 걸렸다 — 이제 일어난 뒤에만 찍는다.

    /** 재협상 라운드 일련번호. 겹친 라운드를 구분하는 유일한 수단. */
    private var negoRound = 0

    private val logPrefix: String
        get() = "[dev:${sts.fingerprint.take(8)}]" + if (negoRound > 0) "[r:$negoRound]" else ""

    private fun lifecycle(msg: String) = Log.i(TAG, "$logPrefix $msg")

    private val evLock = Any()
    private val evQueue = ArrayDeque<Ev>()
    private var draining = false

    /**
     * 사건을 기계에 넣고 돌려받은 부작용을 실행한다.
     *
     * 두 가지를 동시에 지켜야 한다:
     *
     *  1. **직렬화** — 부작용 실행 중에 또 dispatch가 일어난다(`PunchCold` → `coldPunch` →
     *     `StartConnect`, `CloseTransport` → `onDisconnected` → `TransportDown`). 그때 곧바로
     *     reduce를 돌리면 부작용 목록이 뒤섞여 순서 의미(Bye는 전송을 닫기 **전에**)가 깨진다.
     *     그래서 큐에 넣고 바깥 루프가 순서대로 처리한다.
     *  2. **부작용은 락 밖에서** — `LanClient`는 자기 락을 쥔 채 `onDisconnected`를 부르고,
     *     여기서 `bearer.disconnect()`를 부르면 그 락을 기다린다. 상태 락을 쥔 채로 부작용을
     *     실행하면 정확히 반대 순서가 만들어져 교착한다(Controller→LanClient vs LanClient→Controller).
     */
    private fun dispatch(ev: Ev) {
        synchronized(evLock) {
            evQueue.addLast(ev)
            if (draining) return
            draining = true
        }
        while (true) {
            // 상태 전이는 락 안에서 원자적으로. 큐가 비면 그 자리에서 draining을 내린다 —
            // 락 밖에서 내리면 그 틈에 들어온 사건이 "이미 누가 돌리는 중"으로 보여 유실된다.
            val step = synchronized(evLock) {
                val e = evQueue.removeFirstOrNull()
                if (e == null) {
                    draining = false
                    null
                } else {
                    val before = machine
                    val st = reduce(before, e)
                    machine = st.state
                    if (before != st.state) {
                        lifecycle("$e → ${st.state.conn} " +
                            "(sess=${st.state.sess} path=${st.state.path} nego=${st.state.nego})")
                    }
                    st
                }
            } ?: break
            step.fx.forEach { fx ->
                runCatching { runFx(fx) }.onFailure { Log.e(TAG, "fx $fx 실패: ${it.message}", it) }
            }
        }
        publish()
    }

    private fun publish() {
        val m = machine
        // `diagTick`은 진단 화면이 스냅샷을 다시 읽게 만드는 신호다. 축만 바뀌고 `conn`·`status`가
        // 그대로인 전이도 있어서(예: nego 갈래), 그것만으로는 화면이 낡은 값을 붙들고 있다.
        scope.launch(Dispatchers.Main) { state = m.conn; statusMessage = m.status; diagTick++ }
    }

    private fun runFx(fx: Fx) {
        when (fx) {
            // 기계의 뜻만 적어 둔다 — 실제로 드는지는 [updateWifiLock]이 앱 요구와 같이 정한다.
            Fx.AcquireWifiLock -> { sessionWantsLock = true; updateWifiLock() }
            Fx.ReleaseWifiLock -> { sessionWantsLock = false; updateWifiLock() }
            Fx.DropSession -> dropSession()
            Fx.StartKeepalive -> startKeepalive()
            // ★ **세션이 섰다고 디코더를 세우지 않는다.** 확장 앱이 떠야 화면이 온다.
            // 여기서 하는 일은 "말할 채널이 생겼다"를 알리는 것뿐이고, 무엇을 세울지는
            // 앱마다 다르다(확장은 디코더, 공유는 동의 재요청 대기, 알림은 권한 재확인).
            Fx.AttachApps -> apps.attach()
            Fx.StopKeepalive -> { keepaliveJob?.cancel(); keepaliveJob = null }
            Fx.CloseTransport -> bearer.disconnect()
            Fx.SendBye -> sendBye(pendingByeReason)
            is Fx.ScheduleBackoff -> scheduleBackoff(fx.delayMs)
            Fx.CancelBackoff -> { backoffJob?.cancel(); backoffJob = null }
            Fx.ReconnectNow -> reconnectNow()
            Fx.RaiseNegoBit -> {
                negoRound++   // 이 줄 이후의 모든 로그가 이 라운드 번호를 단다
                onIceRestartNeeded?.invoke("재협상") ?: Log.e(TAG, "BLE 코디네이터 미배선 — 재협상 신호를 보낼 수 없다")
            }
            Fx.ClearNegoBit -> onRestartFinished?.invoke()
            is Fx.ArmNegoWatchdog -> armNegoWatchdog(fx.ms)
            Fx.CancelNegoWatchdog -> { negoWatchdog?.cancel(); negoWatchdog = null }
            is Fx.ArmHandshakeWatchdog -> armHandshakeWatchdog(fx.ms)
            Fx.CancelHandshakeWatchdog -> { handshakeWatchdog?.cancel(); handshakeWatchdog = null }
            Fx.ReleaseRoundSockets -> paths.releaseRound()
            Fx.PunchSeamless -> pendingRemote?.let { seamlessPunch(it) }
            Fx.PunchCold -> {
                val r = pendingRemote
                if (r != null) coldPunch(r)
                else Log.e(TAG, "콜드 펀칭 요청인데 후보가 없다")
            }
            Fx.ResetCounters -> resetMediaReceive()
            is Fx.Log -> lifecycle(fx.msg)
        }
    }

    /** 세션·핸드셰이크·앱을 놓는다. **재협상 중에는 기계가 이 지시를 내리지 않는다.** */
    private fun dropSession() {
        sts.reset()
        // 세대는 세션보다 오래 살 수 없다 — 키가 세션 재료에서 갈라 나오기 때문이다.
        // 알릴 채널은 이미 없으므로 조용히 놓는다.
        closeDatagram("세션 종료")
        crypto.clear()
        resetMediaReceive()
        // 앱에게 **왜** 끊겼는지 말한다. 무엇을 버릴지는 앱이 각자 정하지만, 잠깐 끊긴
        // 것과 떠난 것을 가르는 근거는 세션만 갖고 있다.
        //
        // 기계가 이미 그 답을 들고 있다: `intent`가 아직 Running이면 복구 중이고(재접속·
        // 재협상·전송 전환), 아니면 사람이 끝냈거나 치명 거부다. 부작용은 상태 전이 **뒤에**
        // 실행되므로 여기서 읽는 값은 이번 사건이 정한 값이다.
        val gone = machine.intent !is Intent.Running
        apps.detach(
            if (gone) DetachReason.DEVICE_GONE else DetachReason.PATH_LOST,
            "연결이 끊겼습니다",
        )
    }

    private var backoffJob: Job? = null
    private var negoWatchdog: Job? = null
    private var handshakeWatchdog: Job? = null
    private var pendingByeReason = Bye.Reason.USER_DISCONNECT

    /** [Fx.PunchSeamless]/[Fx.PunchCold]가 쓸 Mac 후보 — [beginPunch]가 채워 둔다. */
    private var pendingRemote: CandidateBundle? = null

    private fun scheduleBackoff(delayMs: Long) {
        backoffJob?.cancel()
        backoffJob = scope.launch {
            delay(delayMs)
            dispatch(Ev.BackoffElapsed)
        }
    }

    private fun reconnectNow() {
        apps.extend.restart("재접속")
        client.connect(host, port)
    }

    /**
     * 지금 타고 있는 망의 종류 — Mac 표시용.
     *
     * AOA(USB)일 땐 IP 망 종류가 의미 없다. 케이블 자체가 전송이고, 그때 기본망이 뭐든
     * 영상은 그리로 안 간다. Mac은 세션이 AOA인 걸 이미 알므로 여기선 미지정을 보낸다.
     */
    private fun currentClientNetwork(): ClientNetwork = when {
        bearer !== client -> ClientNetwork.CLIENT_NETWORK_UNSPECIFIED
        else -> when (netWatcher.transport) {
            NetworkWatcher.Transport.WIFI -> ClientNetwork.CLIENT_NETWORK_WIFI
            NetworkWatcher.Transport.CELLULAR -> ClientNetwork.CLIENT_NETWORK_CELLULAR
            NetworkWatcher.Transport.ETHERNET -> ClientNetwork.CLIENT_NETWORK_ETHERNET
            NetworkWatcher.Transport.NONE -> ClientNetwork.CLIENT_NETWORK_UNSPECIFIED
            NetworkWatcher.Transport.OTHER -> ClientNetwork.CLIENT_NETWORK_OTHER
        }
    }

    private fun armNegoWatchdog(ms: Long) {
        negoWatchdog?.cancel()
        negoWatchdog = scope.launch {
            delay(ms)
            dispatch(Ev.NegoDone(false, "Mac 응답 없음 (${ms / 1000}s)"))
        }
    }

    /**
     * 전송은 붙었는데 세션이 안 서는 경우의 마감.
     *
     * 예전엔 이게 [connectAoa] 안의 8초 launch로 **AOA에만** 있었다. LAN에서 Mac이 TCP는
     * 받고 ServerHello를 안 보내면(앱이 막 죽었거나 다른 세션에 물려 있으면) keepalive는
     * 세션이 서야 시작하고 재협상 워치독은 이미 해제된 뒤라, 아무 타이머도 없이
     * "핸드셰이크 중…"에 영원히 머물렀다.
     */
    private fun armHandshakeWatchdog(ms: Long) {
        handshakeWatchdog?.cancel()
        handshakeWatchdog = scope.launch {
            delay(ms)
            dispatch(Ev.HandshakeTimeout)
        }
    }

    init {
        wireBearer(client)
        client.onVideoPacket = { pkt -> onVideoPacket(pkt) }   // LAN UDP 영상 (AOA는 WireFrame.Video)
        netWatcher.onChanged = { _, reason -> onNetworkChanged(reason) }
        netWatcher.start()
        apps.onChanged = { appsChanged() }
        // 확장이 디코더를 새로 세울 때마다 수신 상태도 같이 비운다 — 재조립기와 손실 기준점은
        // 세션이 소유하지만, 비워야 할 시점을 아는 쪽은 앱이다.
        apps.extend.onResetReceive = ::resetMediaReceive
    }

    /**
     * "재협상이 필요하다" — MainActivity가 BLE 코디네이터에 배선한다.
     *
     * 여기서 직접 재펀칭할 수 없는 이유: 홀펀칭은 **양쪽이 거의 동시에** 쏴야 성립하므로
     * 새 후보를 Mac에 전달할 신호 채널이 필요한데, IP가 바뀌는 마당에 IP에 독립적인 경로는
     * BLE뿐이다. 그래서 이 콜백은 신호만 올리고 후보 교환은 BLE가 맡는다.
     */
    var onIceRestartNeeded: ((reason: String) -> Unit)? = null

    /** 재협상이 끝났다 — MainActivity가 BLE 광고 요청 비트를 내리도록 배선한다. */
    var onRestartFinished: (() -> Unit)? = null

    /**
     * 기본망이 바뀌었다 → ICE 재협상.
     *
     * **옛 경로는 일부러 안 건드린다.** 셀룰러는 Wi-Fi가 기본망이 된 뒤에도 한동안 살아 있어
     * 그동안 영상이 계속 흐른다. 새 경로가 확정된 뒤에 갈아타면 검은 화면이 없다
     * (make-before-break). 겹침 창이 없는 전환이면 어차피 keepalive가 끊김을 잡고,
     * 그때는 같은 재협상 경로가 '복구'로 동작한다 — 코드가 하나다.
     */
    private fun onNetworkChanged(reason: String) {
        if (bearer !== client) return   // AOA(USB)는 IP와 무관 — 케이블이 죽어야 반응한다
        dispatch(Ev.NetworkUp(reason))
    }

    /** 핸드셰이크/제어 콜백을 활성 bearer에 배선 (LAN/AOA 공용). */
    private fun wireBearer(b: Bearer) {
        b.onConnected = {
            dispatch(Ev.TransportUp)
            // 세션이 이미 살아 있으면 재개다 — ClientHello를 보내면 새 세션이 생겨 무손실이 깨진다.
            // (LanClient.swapTo가 announce=false로 붙이므로 여기까지 오지 않지만, AOA 등
            //  다른 경로가 생겨도 안전하도록 조건을 남긴다.)
            if (!crypto.isLive) bearer.sendControl(sts.hello(currentClientNetwork(), displaySpec))
        }
        b.onDisconnected = handler@{ err ->
            // 출처가 현재 활성 bearer가 아니면 무시 — LanClient는 로컬 disconnect 시 콜백이
            // 2회(close 직접 + read 루프 예외) 올 수 있고, 교체된 구 AoaClient의 늦은 콜백도 있다.
            if (b !== bearer) {
                Log.d(TAG, "stale bearer 콜백 무시 (${b.javaClass.simpleName})")
                return@handler
            }
            // 여기서 하는 판단은 딱 둘이다: **누가 죽었나**(위의 stale 검사)와 **같은 주소로
            // 다시 붙어볼 수 있나**. 나머지(세션을 버릴지, 재접속할지, 재협상할지, USB에서
            // 무선으로 되돌아갈지)는 전부 기계가 정한다.
            val canRetryAddress = host.isNotEmpty() && port != 0
            if (bearer is AoaClient) {
                // AOA 죽음 직후엔 accessoryList에 유령 항목이 남아 open까지 성공할 수 있어
                // (write만 ENODEV) 전환을 잠시 억제한다. 진짜 재삽입은 ATTACHED intent가
                // onAccessoryAttached()로 쿨다운을 즉시 해제해 구분된다.
                lastAoaSwitchFailAt = System.currentTimeMillis()
            }
            err?.let { Log.i(TAG, "전송 종료: ${it.message}") }
            dispatch(Ev.TransportDown(err?.message, canRetryAddress))
            // 무선으로 되돌아가기로 했다면 bearer도 되돌린다(기계는 소켓을 모른다).
            if (machine.nego is Nego.Requested && bearer !== client) bearer = client
        }
        b.onFrame = { frame -> onFrame(frame) }
    }

    // MARK: - ICE (P2P NAT 트래버설)

    /**
     * 내 후보를 모은다 — BLE 코디네이터가 부른다. 실제 수집은 [PathNegotiator]가 한다.
     *
     * 여기 남은 이유는 하나다: `fastUdp` 플래그가 붙은 묶음을 그대로 되돌려야 하고, 그
     * 판단(“지금 세대가 열려 있는가”)은 세션만 안다 — 다만 Mac이 켜서 보낸 값을 우리는
     * 되비추기만 하므로 실제로는 통과다.
     */
    fun gatherCandidates(remote: CandidateBundle): CandidateBundle = paths.gather(remote)

    /**
     * Mac 후보 도착 → 무손실 교체와 콜드 재연결 중 무엇을 할지는 **기계가 정한다**.
     *
     * 판단 근거는 "세션이 살아 있는가" 하나다. 라운드가 도는 사이에 망이 또 바뀌었으면
     * 후보가 이미 죽었으므로 그 사실만 실어 보낸다.
     *
     * @param expecting 이 후보를 준 Mac의 장기 공개키 — BLE 라운드에서 신원이 확인된 값이다.
     *   뒤이은 핸드셰이크가 **그 Mac이 맞는지** 대조하는 근거가 된다. 여기서 넘기지 않으면
     *   STS는 "내 신뢰 목록의 누구인가"까지만 확인하고, Mac을 둘 페어링해 뒀을 때 엉뚱한
     *   쪽에 조용히 붙는다.
     */
    fun beginPunch(remote: CandidateBundle, display: DisplaySpec, expecting: ByteArray?) {
        this.displaySpec = display
        pendingRemote = remote
        // 핸드셰이크는 이 뒤에 열리는 TCP 위에서 일어난다 — 지금 세워 두면 늦지 않는다.
        sts.expectedPeer = expecting
        dispatch(Ev.MacAnswered(candidatesFresh = paths.candidatesStillValid()))
    }

    private fun coldPunch(remote: CandidateBundle) {
        dispatch(Ev.StartConnect(Via.LAN))
        bearer = client
        paths.coldPunch(
            remote = remote,
            // 연결 직전에 디코더를 **새로** 세운다 — 확장이 살아남은 경로에서만 실제로 선다.
            prepareDecoder = { scope.launch { apps.extend.restart("경로 재수립") } },
            epochOpen = { crypto.hasEpoch },
            sendHello = ::sendUdpHello,
            done = { ok, note -> dispatch(Ev.NegoDone(ok, note)) },
        )
    }

    private fun seamlessPunch(remote: CandidateBundle) {
        // ★ 데이터그램 세대가 열려 있을 때만 UDP를 같이 옮긴다.
        //
        // 세대가 없다는 건 지금 영상을 나르는 앱이 없다는 뜻이고, 그때 UDP를 뚫어 봐야
        // 아무도 안 쓰는 구멍을 새 망에 다시 여는 일이다. Mac도 그 경우 후보에 UDP를 안 싣는다.
        val movingDatagram = crypto.hasEpoch &&
            remote.ordered(CandidateTransport.UDP).isNotEmpty()
        paths.seamlessPunch(
            remote = remote,
            movingDatagram = movingDatagram,
            resumeFrame = { token -> crypto.resumeFrame(token) },
            afterSwap = {
                // 새 UDP 경로를 Mac에 등록 — 이게 있어야 영상이 새 주소로 온다.
                sendUdpHello()
                // 경로가 살아났음을 기계에 알린다. keepalive 재개는 이 사건의 부작용이다 —
                // 재개 경로엔 핸드셰이크가 없어 그걸 부르는 곳이 여기뿐이고, 빠뜨리면 Ping이
                // 끊겨 Mac의 생존감지(6s)가 방금 살려 낸 세션을 도로 죽인다.
                dispatch(Ev.PathSwapped)
            },
            done = { ok, note -> dispatch(Ev.NegoDone(ok, note)) },
        )
    }

    // MARK: - 접속 진입점

    fun connect(host: String, port: Int, display: DisplaySpec) {
        this.host = host
        this.port = port
        this.displaySpec = display
        // 주소를 직접 입력한 경로 — **의도한 상대가 없다.** 주소는 누가 거기 있는지 말해
        // 주지 않으므로 대조할 것이 없고, 앞선 BLE 라운드의 기대를 물려받으면 엉뚱한 상대를
        // 기대하며 멀쩡한 연결을 거부하게 된다.
        sts.expectedPeer = null
        dispatch(Ev.StartConnect(Via.LAN))
        bearer = client
        apps.extend.restart("연결 시작")
        client.connect(host, port)
    }

    /**
     * MainActivity의 3초 액세서리 폴링 훅 — USB 우선 정책의 진입점.
     * 유휴/에러면 AOA 연결, LAN 스트리밍 중이면 AOA로 전환(쿨다운으로 왕복 방지).
     */
    fun onAccessoryTick(accessory: UsbAccessory, display: DisplaySpec) {
        val m = machine
        if (m.intent !is Intent.Running || m.switch == Switch.ToAoa) return
        when {
            m.conn == ConnState.IDLE || m.conn == ConnState.ERROR ->
                connectAoa(accessory, display)
            m.conn == ConnState.STREAMING && bearer === client &&
                System.currentTimeMillis() - lastAoaSwitchFailAt > aoaSwitchCooldownMs ->
                switchToAoa(accessory, display)
        }
    }

    /** USB_ACCESSORY_ATTACHED intent — 진짜 (재)열거 신호 → 전환 쿨다운 즉시 해제 (유령 항목과 구분). */
    fun onAccessoryAttached() {
        Log.i(TAG, "ATTACHED intent → AOA 전환 쿨다운 해제")
        lastAoaSwitchFailAt = 0L
    }

    /** 동작 중인 LAN 세션을 곱게 내리고 AOA로 전환 (USB 우선). 실패 시 onDisconnected가 LAN 복귀. */
    private fun switchToAoa(accessory: UsbAccessory, display: DisplaySpec) {
        Log.i(TAG, "bearer 전환: LAN → AOA")
        dispatch(Ev.SwitchToAoa)   // → CloseTransport, 뒤따르는 끊김은 '사고 아님'으로 해석된다
        scope.launch {
            var waited = 0
            while (machine.conn != ConnState.IDLE && waited < 1000) { delay(100); waited += 100 }
            delay(300)   // Mac 쪽 LAN 세션 teardown 여유 (동일 디스플레이 신원 재생성 충돌 방지)
            connectAoa(accessory, display)
            dispatch(Ev.AoaFromLan)   // 이 AOA가 실패하면 무선으로 되돌아간다
        }
    }

    /** AOA(USB) 액세서리로 연결 — LAN과 동일 핸드셰이크를 AOA 신뢰 파이프 위에서 구동. */
    fun connectAoa(accessory: UsbAccessory, display: DisplaySpec) {
        this.displaySpec = display
        val a = AoaClient(scope, appContext)
        bearer = a
        wireBearer(a)
        // 케이블에는 **의도한 상대가 없다** — 꽂힌 그 Mac이 곧 상대이고, 처음 보는 Mac이면
        // 케이블 자체가 채널을 인증한 근거가 되어 여기서 신뢰가 세워진다(부트스트랩).
        // 앞선 BLE 라운드의 기대를 물려받으면 그 부트스트랩이 통째로 막힌다.
        sts.expectedPeer = null
        dispatch(Ev.StartConnect(Via.AOA))
        apps.extend.restart("USB 연결")
        a.open(accessory)
        // (제거됨) AOA 전용 8초 핸드셰이크 타임아웃. 같은 마감이 이제 [Fx.ArmHandshakeWatchdog]로
        // LAN·AOA 공통이다. 다만 fd가 열리기만 하고 `onConnected`조차 안 오면 그 워치독은
        // 무장되지 않는다(전송이 아직 Live가 아니므로) — 그건 AoaClient의 open 실패 경로가 잡는다.
    }

    /**
     * 사용자 '끊기' / 앱 종료 — 이 기기가 **떠난다**. 전송 전환과 명확히 다른 경로다
     * (전환은 controller.disconnect()가 아니라 bearer.disconnect()만 부른다).
     *
     * Bye 송신은 [Fx.SendBye]로 나가며, 기계가 그걸 **전송을 닫기 전에** 배치한다.
     * 순서가 뒤집히면 Mac은 "또 끊겼나 보다"로 보고 유예 시간만큼 유령 디스플레이를 든다.
     */
    fun disconnect(reason: Bye.Reason = Bye.Reason.USER_DISCONNECT) {
        pendingByeReason = reason
        dispatch(Ev.UserStop)
    }

    /**
     * Mac이 Bye를 보냈다 — **사유에 따라 갈린다.**
     *
     * 예전엔 사유를 보지 않고 전부 [Ev.HostStop]으로 처리했다. 그러면 페어링이 해제돼도
     * 평범한 '연결 해제'와 똑같이 취급돼, 화면엔 그냥 "연결 안 됨"이 뜨고 다음 기회에
     * 다시 붙으려 든다. `AUTH_REJECTED`는 **재시도가 원리상 무의미한** 경우라 치명 래치로
     * 가야 한다 — 그래야 Mac이 후보를 줘도 무시하고([Ev.MacAnswered]의 첫 갈래), 사용자에게
     * 왜 안 되는지가 표시된다.
     */
    private fun onHostBye(reason: Bye.Reason) {
        if (reason == Bye.Reason.AUTH_REJECTED) {
            Log.w(TAG, "Mac이 신원을 거부(bye=AUTH_REJECTED) → 치명 정지, 재시도하지 않는다")
            dispatch(Ev.Fatal("Mac에서 페어링이 해제되었습니다"))
        } else {
            Log.i(TAG, "Mac에서 연결 종료(bye=$reason) 수신 → 재접속 없이 대기")
            dispatch(Ev.HostStop)
        }
    }

    /** Bye 전송 — 실패해도 종료는 진행한다(Mac은 하트비트 유실 유예로 결국 정리한다). */
    private fun sendBye(reason: Bye.Reason) {
        val frame = crypto.controlFrame(
            ControlMessage.newBuilder()
                .setBye(Bye.newBuilder().setReason(reason).build())
                .build()
        ) ?: return   // 핸드셰이크 전이면 보낼 채널이 없다
        // ★ 로그는 **바이트가 실제로 나간 뒤에** 찍는다. 예전엔 큐에 넣자마자 "bye 전송"을
        // 찍었는데, 곧바로 소켓이 닫혀 Bye가 유실돼도 로그는 보낸 것처럼 보였다.
        //
        // (제거됨) `if (b is LanClient)` 분기. 완료 통보가 LAN에만 있어서 AOA는 그냥 큐에 넣고
        // "전달"이라고 찍었는데, 정작 **AOA에서도 같은 유실이 나고 있었다**. 이제 `Bearer`의
        // 공통 계약이라 전송을 구분하지 않는다.
        bearer.sendControl(frame) { lifecycle("bye 송신 완료 (reason=$reason)") }
    }

    fun release() {
        disconnect(Bye.Reason.APP_EXIT)
        // Bye가 선에 오를 틈을 준다. 송신 큐는 `scope`의 Job을 공유하므로 곧바로 `scope.cancel()`을
        // 하면 예약된 Bye까지 취소된다 — 그러면 Mac이 60초 유예를 도는 유령 디스플레이가 남는다.
        runCatching { Thread.sleep(80) }
        netWatcher.stop()
        scope.cancel()
    }

    /**
     * 화면 회전 등으로 디스플레이 특성이 바뀜 → 재협상.
     * 디코더를 리셋(새 해상도 키프레임 대기)하고 Renegotiate를 보낸다.
     */
    fun onDisplayChanged(spec: DisplaySpec) {
        val prev = displaySpec
        Log.i(TAG, "onDisplayChanged ${spec.widthPx}x${spec.heightPx} state=${machine.conn} " +
            "prev=${prev?.let { "${it.widthPx}x${it.heightPx}" }}")
        displaySpec = spec
        if (machine.conn != ConnState.STREAMING) return
        if (prev != null && prev.widthPx == spec.widthPx && prev.heightPx == spec.heightPx) {
            Log.i(TAG, "onDisplayChanged: 해상도 동일 → 재협상 스킵 (probe가 회전 미반영?)")
            return
        }
        Log.i(TAG, "display changed → renegotiate ${spec.widthPx}x${spec.heightPx}")
        apps.extend.restart("회전 ${spec.widthPx}x${spec.heightPx}")
        channel.sendReliable(
            ControlMessage.newBuilder().setRenegotiate(
                Renegotiate.newBuilder()
                    .setWidth(spec.widthPx)
                    .setHeight(spec.heightPx)
                    .setOrientation(
                        if (spec.isLandscape) Orientation.ORIENTATION_LANDSCAPE
                        else Orientation.ORIENTATION_PORTRAIT
                    )
                    .setReason("rotation")
                    .build()
            ).build()
        )
    }

    // MARK: - 핸드셰이크
    //
    // 절차 자체는 [StsClient]에 있다. 여기 남는 건 **결과를 세션에 반영하는 일**뿐이다 —
    // 어느 전송으로 Pass 3을 내보낼지, 거부를 어떤 사건으로 올릴지는 세션의 사정이다.

    /** 세션 확립 → keepalive·앱 붙이기는 이 사건의 부작용이다(콜드·재개 공통). */
    private fun onStsResponse(respCore: ByteArray, sealedAuth: ByteArray) {
        when (val out = sts.accept(respCore, sealedAuth, cable = channel.reliableStream)) {
            is StsClient.Outcome.Stale ->
                Log.i(TAG, "보낸 적 없는 HELLO에 대한 응답 — 버린다")

            is StsClient.Outcome.Rejected -> {
                // 래치다 — 이후 망이 몇 번 바뀌어도 이 Mac에 다시 붙지 않는다.
                dispatch(Ev.Fatal(out.reason))
            }

            is StsClient.Outcome.Established -> {
                crypto.start(out.session)
                dispatch(Ev.SessionLive(out.peerName))
                bearer.sendControl(out.confirm)
                // ★ **여기서 UDP를 열지 않는다.** 세션 수립은 TCP만으로 끝난다 — 영상 채널은
                // 그걸 쓰는 앱(확장·공유)이 실제로 뜰 때 연다.
            }
        }
    }

    // MARK: - 프레임 라우팅

    private fun onFrame(frame: WireFrame) {
        when (frame) {
            is WireFrame.Handshake -> {
                val msg = runCatching { ControlMessage.parseFrom(frame.payload) }.getOrNull() ?: return
                // 모르는 내용이면 **말하고** 버린다. 조용히 버리면 "상대가 응답이 없다"로만
                // 보이는데, 실제로는 응답이 와서 갈래를 못 찾은 것이다 — Mac 쪽에서 정확히
                // 그 버그가 났다(Pass 3을 다른 프레임 갈래에서 기다리고 있었다).
                if (!msg.hasStsResponse()) {
                    Log.e(TAG, "핸드셰이크 프레임에 STS pass2가 아닌 내용이 왔다 — 버린다")
                    return
                }
                onStsResponse(msg.stsResponse.core.toByteArray(),
                              msg.stsResponse.sealedAuth.toByteArray())
            }

            is WireFrame.Control -> {
                val msg = crypto.openControl(frame.nonce, frame.body) ?: return
                when {
                    // ── 세션 자신의 것 ────────────────────────────────────────
                    msg.hasPong() -> onPong(msg.pong.timestampUs, msg.pong.macRecvUs, msg.pong.macSendUs)
                    msg.hasUdpOpen() -> onUdpOpen(msg.udpOpen)
                    msg.hasUdpClose() -> onUdpClose(msg.udpClose)
                    msg.hasAppControl() -> apps.control(msg.appControl)
                    msg.hasBye() -> onHostBye(msg.bye.reason)
                    // ── 그 밖은 전부 앱의 것이다 ──────────────────────────────
                    //
                    // 세션이 앱의 이름을 부르지 않는 자리가 여기다. 임자가 없으면 조용히
                    // 흘려보낸다 — 상대가 우리보다 새 버전일 수 있고, 그건 오류가 아니다.
                    else -> apps.receive(msg)
                }
            }

            // 케이블(신뢰·순서보장 파이프)로 온 미디어. 채널이 봉인을 풀어 앱이 볼 모양으로
            // 만들고, 임자가 가져간다.
            is WireFrame.Video -> {
                val media = channel.openMedia(frame)
                if (media != null) apps.receiveMedia(media)
            }
        }
    }

    /**
     * 데이터그램 하나가 도착했다 — 세는 것과 여는 것.
     *
     * ★ **구멍은 프레임과 따로 알린다.** 조각이 빠지면 그 프레임은 영영 완성되지 않아
     * [SessionChannel.openMedia]가 null을 준다. 유실을 프레임에 실어 보내면 정작 유실이
     * 일어난 순간에만 그 소식이 사라진다.
     */
    private fun onVideoPacket(pkt: MiniRtpPacket) {
        if (metrics.notePacket(pkt.packetSeq)) apps.mediaGap()
        val media = channel.openMedia(pkt) ?: return
        apps.receiveMedia(media)
    }

    /** 순번 기준점과 미완성 조각을 함께 버린다 — 둘은 언제나 같이 낡는다. */
    private fun resetMediaReceive() {
        channel.resetMediaReceive()
        metrics.reset()
    }

    private fun onPong(t1: Long, macRecvUs: Long, macSendUs: Long) {
        val t4 = System.nanoTime() / 1000         // Android recv (t4)
        if (metrics.notePong(t1, macRecvUs, macSendUs, t4)) {
            apps.extend.applyClock(metrics.clockOffsetUs, metrics.clockSynced)
        }
        Log.d(TAG, "RTT ${metrics.rttMs}ms")
    }

    // MARK: - keepalive (Ping/Pong) + Transport-CC

    /**
     * Ping 간격 — **영상이 흐를 때만 빠르다.**
     *
     * 이 Ping이 하는 일은 셋이다: 생존 확인, RTT 측정, 망 변경 통지. 뒤의 둘은 영상이 흐를
     * 때 값을 한다(지터버퍼가 RTT를 먹고, 핸드오버는 스트림이 끊기는 사건이다). 알림만 받는
     * 세션에서는 생존 확인만 남는데, 그건 1초마다 할 일이 아니다.
     */
    private val keepalivePeriodMs: Long
        get() = if (apps.streaming) STREAM_PING_MS else IDLE_PING_MS

    private fun startKeepalive() {
        keepaliveJob?.cancel()
        metrics.markAlive()
        keepaliveJob = scope.launch {
            while (isActive) {
                val period = keepalivePeriodMs
                delay(period)
                if (!crypto.isLive) continue
                // Ping (생존 + RTT)
                channel.sendReliable(
                    ControlMessage.newBuilder().setPing(
                        Ping.newBuilder()
                            .setTimestampUs(System.nanoTime() / 1000)
                            // 재개는 ClientHello를 새로 안 보내므로, 망이 바뀐 사실을 Mac에
                            // 알리는 통로가 여기뿐이다.
                            .setNetwork(currentClientNetwork())
                            .build()
                    ).build()
                )
                sendFeedback()
                watchDatagramPath()
                // 죽음 판정도 **주기를 따라간다.** 5초에 한 번 보내면서 3초 안에 답을 요구하면
                // 멀쩡한 링크를 매번 스스로 끊는다.
                val deadline = period * DEAD_AFTER_PERIODS
                if (metrics.sincePongMs > deadline) {
                    Log.w(TAG, "keepalive timeout (${deadline}ms) → 링크를 끊어 복구를 태운다")
                    // 끊으면 onDisconnected → Ev.TransportDown이 되고, 그때 기계가 재접속·재협상
                    // 중 무엇을 할지 정한다. 여기서 판단하지 않는다.
                    bearer.disconnect()
                    break
                }
            }
        }
    }

    /** 마지막으로 HELLO를 다시 보낸 시각 — 굶주림이 이어져도 초당 한 발을 넘기지 않는다. */
    private var lastHelloRetryAt = 0L

    /** 이 굶주림 구간에서 재등록을 몇 번 시도했나. 프레임이 오면 0으로 돌아간다. */
    private var helloRetries = 0

    /**
     * **영상이 흐르기로 되어 있는데 한 장도 안 온다** — 경로를 다시 등록한다.
     *
     * 이 감시가 없으면 그 상태를 알아챌 방법이 아예 없다. 영상 경로의 복구 수단이 전부
     * **도착한 패킷**을 방아쇠로 삼기 때문이다: 손실 감지도, 갱신 요청도 패킷이 와야 운다.
     * 한 장도 안 오면 아무것도 울리지 않고, 제어 채널은 TCP라 멀쩡하니 keepalive도 조용하다.
     * 화면만 마지막 프레임에 얼어붙는다 — 실기기에서 망 전환 뒤에 정확히 그랬다.
     *
     * 왜 HELLO인가: Mac은 **HELLO의 출발지 주소로** 영상 피어를 링크한다. 망이 갈리면 그
     * 주소가 바뀌는데, 교체 직후 보내는 HELLO는 UDP 한 발이라 재전송이 없다 — 하필 가장
     * 잃기 쉬운 순간에 한 번만 쏘고 만다. 그게 유실되면 Mac은 죽은 주소로 계속 보낸다.
     *
     * 세션도 세대도 건드리지 않는다. **등록만 다시 한다** — 경로가 진짜로 죽었으면 그건
     * keepalive와 재협상의 몫이고, 여기서 판단할 일이 아니다.
     */
    private fun watchDatagramPath() {
        if (!apps.streaming || !crypto.hasEpoch) return
        // 케이블은 세대도 HELLO도 없다 — 영상이 제어와 같은 신뢰 파이프로 온다.
        if (channel.reliableStream) return
        // 이미 새 경로를 찾는 중이면 기다린다. 라운드가 도는 동안 HELLO를 쏴 봐야 옛
        // 소켓에서 나가고, 재협상을 또 청하면 예약만 쌓인다.
        if (machine.negoBusy) return
        val since = metrics.sinceVideoMs
        if (since in 0 until VIDEO_STARVED_MS) {
            helloRetries = 0   // 영상이 흐른다 — 다음 굶주림은 처음부터 센다
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastHelloRetryAt < VIDEO_STARVED_MS) return
        lastHelloRetryAt = now

        // ★ 보낼 주소가 아예 없다 = 경로 교체가 UDP를 못 옮겼다(상대 후보에 UDP가 없었다).
        //
        // 이때 HELLO를 쏘면 `udpTarget`이 없어 **제어 채널 주소로** 나간다 — Mac의 UDP
        // 소켓에 닿지 않으니 영원히 무의미하다. 필요한 건 등록이 아니라 새 구멍이다.
        if (client.udpTarget == null) {
            escalate("영상 경로에 보낼 주소가 없다")
            return
        }

        // ★ **재등록은 몇 번만 해 보고 포기한다.**
        //
        // HELLO 재전송이 고치는 건 딱 하나다: 교체 직후 한 발 쏜 HELLO가 유실돼 Mac이 옛
        // 주소로 보내고 있는 경우. 그건 한두 번이면 닿는다. 그런데도 안 오면 전제가 틀린
        // 것이다 — 세대가 갈렸거나, 구멍이 닫혔거나, 애초에 잘못된 주소로 쏘고 있다.
        // 그때 같은 HELLO를 영원히 반복하는 건 복구가 아니라 그냥 로그 낭비다(실기기에서
        // 2초마다 같은 줄이 무한히 찍혔다). 라운드를 다시 도는 것만이 그 셋을 다 고친다.
        helloRetries++
        if (helloRetries > MAX_HELLO_RETRIES) {
            escalate("HELLO를 ${MAX_HELLO_RETRIES}번 보냈는데 영상이 없다")
            return
        }
        lifecycle(
            (if (since < 0) "영상 앱이 떴는데 첫 프레임이 없다" else "영상이 ${since}ms째 없다") +
                " — 경로 재등록 $helloRetries/$MAX_HELLO_RETRIES (Mac이 옛 주소로 보내는 중일 수 있다)"
        )
        sendUdpHello()
    }

    /** 재등록으로는 안 되는 상황 — 라운드를 통째로 다시 돈다. */
    private fun escalate(why: String) {
        helloRetries = 0
        lifecycle("$why — 재협상으로 UDP를 다시 뚫는다 (세대 ${epochHex()}, 대상 ${client.udpTarget})")
        dispatch(Ev.NegoNeeded("영상 경로 없음"))
    }

    private var feedbackSeq = 0

    /**
     * Transport-CC 손실 보고.
     *
     * AOA(신뢰 파이프)에는 손실 집계가 없다 → queueDelay만 실어 Mac 포화 가드에 신호한다
     * (불량 케이블 등 파이프 포화 시 Mac이 비트레이트를 백오프). base=high=0·recv=1로 보내
     * Mac 쪽 span=1·loss=0이 되게 한다. render-on-arrival이라 버퍼는 없지만 q(도착−PTS
     * 초과분)는 계속 측정된다(측정 ≠ 버퍼링).
     */
    private fun sendFeedback() {
        val st = apps.extend.stats()
        val b = TransportFeedback.newBuilder()
            .setFeedbackSeq(feedbackSeq++)
            .setQueueDelayMs(st?.queueDelayMs ?: 0)   // 지연 인식형 비트레이트 제어 신호
            .setPipeNetMs(st?.pipeNetMs ?: 0)
            .setPipeJbufMs(st?.pipeJbufMs ?: 0)
            .setPipeDecMs(st?.pipeDecMs ?: 0)
            .setRttMs(metrics.rttMs)

        if (channel.reliableStream) {
            // 케이블에는 손실 집계가 없어 큐잉지연이 보고의 전부다 — 그게 없으면 보낼 것도 없다.
            if (st == null) return
            Log.d(TAG, "feedback(aoa) qdelay=${st.queueDelayMs}ms")
            b.setBaseSeq(0).setHighestSeq(0).setReceivedCount(1)
        } else {
            // ★ 여기선 디코더를 요구하지 않는다. 앱이 뒤로 가 화면이 사라져도 프레임은 계속
            // 오고, 그때야말로 Mac이 손실을 알아야 한다(비트레이트를 낮출 근거가 된다).
            val w = metrics.takeWindow() ?: return
            val span = (w.high - w.base + 1).coerceAtLeast(1)
            Log.d(TAG, "feedback qdelay=${st?.queueDelayMs ?: 0}ms " +
                "buf=${apps.extend.bufferMs}ms recv=${w.received}/$span")
            b.setBaseSeq(w.base).setHighestSeq(w.high).setReceivedCount(w.received)
        }
        channel.sendReliable(
            ControlMessage.newBuilder().setTransportFeedback(b.build()).build()
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    //  데이터그램(UDP) 채널 — 세션이 아니라 **영상을 쓰는 앱**의 수명을 따른다.
    //
    //  세션 수립은 TCP만으로 끝난다. UDP를 같이 열지 않는 이유는 순전히 NAT다: UDP 매핑은
    //  수명이 짧아(흔히 30초 안팎, 캐리어 CGNAT은 더) 붙들려면 계속 두드려야 하는데, 그건
    //  아무도 안 보는 동안에도 라디오를 깨우는 일이다. TCP 매핑은 분~시간 단위로 살아 있어서
    //  제어 채널 하나만 쥐고 있으면 세션이 값싸게 유지된다.
    //
    //  **키 교환이 없다.** 채널 키는 TCP에서 이미 합의한 세션 재료에서 `channel_id`를 소금으로
    //  갈라 나오므로, `UdpOpen`을 받는 순간 우리도 키를 갖는다 — 첫 데이터그램(HELLO)이
    //  곧바로 봉인돼 나간다(0-RTT).
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Mac이 데이터그램 세대를 연다 → 키를 유도하고, 필요하면 후보를 모아 되돌려준다.
     *
     * `candidates`가 비어 있으면 **새 ICE 라운드가 없다**는 뜻이다 — fast UDP 모드에서
     * 접속할 때 이미 뚫어 둔 경로를 그대로 쓴다.
     */
    private fun onUdpOpen(open: UdpOpen) {
        val id = open.channelId.toByteArray()
        if (channel.reliableStream) {
            // 케이블로 붙어 있다 — 영상은 신뢰 파이프로 가므로 UDP를 열 이유가 없다.
            sendUdpAccept(id, ByteArray(0), "케이블(AOA)로 연결 중")
            return
        }
        // ★ 0-RTT: 키가 이 줄에서 나온다. UDP 위에서 합의할 것이 없다.
        if (!crypto.openEpoch(id)) return
        val hex = id.take(4).joinToString("") { "%02x".format(it) }

        val remoteBytes = open.candidates.toByteArray()
        if (remoteBytes.isEmpty()) {
            lifecycle("데이터그램 세대 $hex — 접속 때 뚫어 둔 경로 재사용")
            sendUdpAccept(id, ByteArray(0), "")
            client.startUdpReceive()
            sendUdpHello()
            return
        }
        val remote = CandidateBundle.parse(remoteBytes) ?: run {
            Log.e(TAG, "UdpOpen: Mac 후보 파싱 실패")
            sendUdpAccept(id, ByteArray(0), "후보를 이해하지 못했습니다")
            return
        }
        lifecycle("데이터그램 세대 $hex — 새 UDP 라운드")
        // STUN도 펀칭도 네트워크 왕복이라 제어 채널 스레드를 잡고 있으면 안 된다.
        Thread {
            paths.datagramRound(
                remote = remote,
                stillOpen = { crypto.isCurrentEpoch(id) },
                accept = { mine -> sendUdpAccept(id, mine, "") },
                sendHello = ::sendUdpHello,
            )
        }.apply { isDaemon = true; start() }
    }

    private fun sendUdpAccept(id: ByteArray, candidates: ByteArray, problem: String) {
        channel.sendReliable(
            ControlMessage.newBuilder().setUdpAccept(
                UdpAccept.newBuilder()
                    .setChannelId(ByteString.copyFrom(id))
                    .setCandidates(ByteString.copyFrom(candidates))
                    .setProblem(problem)
                    .build()
            ).build()
        )
    }

    /** Mac이 세대를 닫았다(마지막 영상 앱이 내려갔다). 지난 세대의 늦은 통보는 무시한다. */
    private fun onUdpClose(close: UdpClose) {
        if (!crypto.isCurrentEpoch(close.channelId.toByteArray())) {
            Log.i(TAG, "UdpClose: 지난 세대의 통보 — 무시")
            return
        }
        closeDatagram("Mac: ${close.reason}")
    }

    /**
     * 세대를 놓는다 — 키를 버리고 소켓·수신 루프를 정리한다.
     *
     * NAT 매핑은 아무도 안 두드리면 스스로 닫힌다. 소켓을 닫는 건 매핑을 없애기 위해서가
     * 아니라 다음 세대가 **새 소켓에서** 매핑을 다시 얻어야 하기 때문이다(옛 포트의 매핑은
     * 그 포트의 것이라 재사용할 수 없다).
     */
    private fun closeDatagram(reason: String) {
        if (crypto.channelId == null) return
        lifecycle("데이터그램 세대 닫음 (${epochHex()}, $reason)")
        crypto.closeEpoch()
        helloRetries = 0
        client.onUdpProbe = null
        client.releaseMediaSocket()
        resetMediaReceive()   // 다음 세대의 조각·순번과 섞이면 안 된다
    }

    /** 영상 채널 개시/재등록 HELLO. 케이블에는 열 세대가 없으므로 아무 일도 안 한다. */
    private fun sendUdpHello() {
        if (channel.reliableStream) return
        val hello = crypto.helloDatagram() ?: return
        // ★ 어느 세대의 HELLO인지 찍는다. Mac도 같은 자리(앞 4바이트)를 같은 형식으로
        // 찍으므로, 두 로그의 이 값을 나란히 놓으면 "세대가 갈렸다"와 "세대는 같은데
        // 안 닿는다"가 곧바로 갈린다 — 그 둘의 원인은 완전히 다른 곳에 있다.
        lifecycle("영상 경로 등록 HELLO — 세대 ${epochHex()}")
        client.startVideo(hello)
    }

    private fun epochHex(): String =
        crypto.channelId?.take(4)?.joinToString("") { "%02x".format(it) } ?: "없음"
}
