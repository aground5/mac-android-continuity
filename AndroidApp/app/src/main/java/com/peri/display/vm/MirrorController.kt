package com.peri.display.vm

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.protobuf.ByteString
import com.peri.display.CodecSupport
import com.peri.display.DisplaySpec
import com.peri.display.crypto.PeriSts
import com.peri.display.crypto.TrustedPeer
import com.peri.display.crypto.PeriCert
import com.peri.display.crypto.DATAGRAM_CHANNEL_ID_BYTES
import com.peri.display.crypto.PeriCryptoSession
import com.peri.display.crypto.PeriDatagramKeys
import com.peri.display.crypto.PeriHandshake
import com.peri.display.crypto.PeriIdentity
import com.peri.display.crypto.TrustState
import com.peri.display.crypto.TrustStore
import com.peri.display.decode.VideoDecoder
import com.peri.display.decode.VideoReassembler
import com.peri.display.proto.AppControl
import com.peri.display.proto.AppId
import com.peri.display.proto.AppState
import com.peri.display.proto.StsAuth
import com.peri.display.proto.StsConfirm
import com.peri.display.proto.StsHello
import com.peri.display.proto.StsHelloCore
import com.peri.display.proto.StsResponseCore
import com.peri.display.proto.ClientNetwork
import com.peri.display.proto.Bye
import com.peri.display.proto.ControlMessage
import com.peri.display.proto.ShareParams
import com.peri.display.proto.ShareRequest
import com.peri.display.proto.ShareStatus
import com.peri.display.proto.InboxStyle
import com.peri.display.proto.MessagingMessage
import com.peri.display.proto.MessagingStyle
import com.peri.display.proto.NotificationAction
import com.peri.display.proto.NotificationActionFailed
import com.peri.display.proto.NotificationActionInfo
import com.peri.display.proto.NotificationImages
import com.peri.display.proto.NotificationPerson
import com.peri.display.proto.NotificationPosted
import com.peri.display.proto.NotificationRemoved
import com.peri.display.proto.NotificationStyle
import com.peri.display.proto.NotificationText
import com.peri.display.proto.VideoCodec
import com.peri.display.notify.FailReason
import com.peri.display.notify.NotifyAccess
import com.peri.display.notify.NotifyBridge
import com.peri.display.notify.NotifyMessage
import com.peri.display.notify.NotifyPerson
import com.peri.display.notify.NotifySnapshot
import com.peri.display.notify.NotifyStyleKind
import com.peri.display.share.ScreenShare
import com.peri.display.proto.DisplayCaps
import com.peri.display.proto.DynamicRange
import com.peri.display.proto.KeyframeRequest
import com.peri.display.proto.LtrAck
import com.peri.display.proto.Orientation
import com.peri.display.proto.Ping
import com.peri.display.proto.Renegotiate
import com.peri.display.proto.TransportFeedback
import com.peri.display.proto.UdpAccept
import com.peri.display.proto.UdpClose
import com.peri.display.proto.UdpOpen
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import com.peri.display.transport.AoaClient
import com.peri.display.transport.Bearer
import com.peri.display.transport.CandidateBundle
import com.peri.display.transport.CandidateKind
import com.peri.display.transport.CandidateTransport
import com.peri.display.transport.IceAgent
import com.peri.display.transport.Ipv4Endpoint
import com.peri.display.transport.LanClient
import com.peri.display.transport.MiniRtpPacket
import com.peri.display.transport.NetworkWatcher
import com.peri.display.transport.StunServer
import com.peri.display.transport.VideoHeader
import com.peri.display.transport.WireFormat
import com.peri.display.transport.WireFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

private const val TAG = "MirrorController"

/**
 * 펀칭이 먼저 뚫렸을 때 host 직결에게 주는 유예.
 *
 * host 직결이 성립할 수 있는 링크(같은 LAN·핫스팟)에서 connect는 한 자릿수 ms다. 이만큼
 * 안에 답이 없으면 사실상 타임아웃으로 갈 운명이라, 더 기다려 봐야 무손실 교체만 늦어진다.
 * 이 유예를 쓰는 경우 자체가 드물다 — host가 이미 실패했으면 0ms이고, host가 성립할 링크면
 * 보통 펀칭보다 먼저 끝난다.
 */
private const val HOST_GRACE_MS = 400L

/**
 * 공유 프레임 번호의 상한 = 와이어 `VideoHeader.seq`의 폭(24비트).
 * 넘어가면 nonce가 재사용되므로 공유를 멈춘다 — 자세한 이유는 `shareFrameId` 주석.
 */
private const val SHARE_FRAME_LIMIT = 0xFF_FFFFL

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
 * 수신측 오케스트레이터: LanClient → 핸드셰이크(X25519+ML-KEM) → 복호화 → VideoDecoder.
 * + keepalive(Ping/Pong) 생존감지·자동재접, Transport-CC 손실보고, 회전 재협상.
 *
 * **연결 수명주기 판단은 여기 없다.** 전부 [MirrorMachine]의 순수 [reduce]가 하고, 이 클래스는
 * 관측한 사실을 [Ev]로 올리고 돌려받은 [Fx]를 실행하는 실행기다. 예전에는 이 파일 안에
 * `restarting`/`userClosed`/`switchingBearer`/`fatalError` 같은 플래그 15개가 흩어져 있었고
 * 분기마다 그 조합을 손으로 맞춰야 했다 — 한 곳을 고치면 다른 곳이 깨지는 구조였다.
 */
class MirrorController(context: Context) {

    private val appContext = context.applicationContext
    private val identity by lazy { PeriIdentity.get(appContext) }
    private val trustStore by lazy { TrustStore.get(appContext) }
    // (제거됨) `requirePairing` — 미페어링 Mac을 거부할지의 토글.
    //
    // 값은 줄곧 true였고 false 가지는 TOFU 자동핀(처음 보는 Mac을 사람 확인 없이 신뢰)이었다.
    // "BLE-PAKE 들어오면 없앤다"는 임시 조치였는데, 그 페어링이 이제 양방향 SAS/OTP까지
    // 갖췄으므로 지운다. 토글로 남겨 두면 한 글자로 그 사슬이 무력화된다.
    // 이제 신뢰는 **BLE 페어링에서만** 생긴다.

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = LanClient(scope)
    private var aoa: AoaClient? = null
    private var bearer: Bearer = client   // 현재 활성 전송(LAN 기본, AOA면 교체)

    /** 기본망 감시 — LTE↔Wi-Fi 핸드오버를 keepalive 타임아웃보다 먼저 잡는다. */
    private val netWatcher = NetworkWatcher(context)

    // Wi-Fi 저지연 락: 연결 동안 라디오 절전(power-save) 차단 → idle 구간 주기적 RTT 스파이크(비콘 대기) 제거 + 전반 지연↓.
    private val wifiLock: WifiManager.WifiLock? by lazy {
        val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wm?.createWifiLock(mode, "PeriDisplay:stream")
    }
    /**
     * 기계가 세션을 위해 락을 원하는가.
     *
     * ★ **이것만으로는 안 잡는다.** 기계는 서비스 어휘를 모르므로(원칙 1) "세션이 섰다"까지만
     * 말할 수 있는데, `WIFI_MODE_FULL_LOW_LATENCY`가 필요한 건 **영상이 흐를 때**다. 알림만
     * 받는 세션에 라디오 절전을 꺼 두면 아무 이득 없이 배터리만 먹는다.
     *
     * 그래서 기계의 뜻과 앱의 요구를 **여기서 AND** 한다. 기계는 그대로 두고(모델검사도
     * 그대로), 앱을 아는 층에서 결정이 난다.
     */
    private var sessionWantsLock = false

    /** 영상을 나르는 앱이 도는가 — 저지연이 실제로 필요한 유일한 경우. */
    @Volatile private var extendStreaming = false
    @Volatile private var shareStreaming = false
    private val streaming: Boolean get() = extendStreaming || shareStreaming

    /** 두 조건이 다 맞을 때만 락을 든다. 어느 쪽이 바뀌든 여기로 모인다. */
    private fun updateWifiLock() {
        if (sessionWantsLock && streaming) acquireWifiLock() else releaseWifiLock()
    }

    /**
     * 영상 앱이 서거나 누웠다 — **세션 유지 비용을 다시 맞춘다.**
     *
     * 라디오를 깨워 두는 것도(Wi-Fi 락) 주기적으로 두드리는 것도(keepalive), 근거는 하나다:
     * 지금 영상이 흐르는가. 그 답이 바뀌는 유일한 지점이 여기라 둘을 같이 고친다.
     */
    private fun appsChanged() {
        updateWifiLock()
        // 주기가 바뀌었으면 곧바로 반영한다. 안 그러면 확장을 켠 뒤에도 다음 바퀴가 돌 때까지
        // (최대 [IDLE_PING_MS]) 느린 채로 돈다 — 정작 RTT가 가장 필요한 구간이다.
        if (keepaliveJob != null) startKeepalive()
        // ★ 영상 채널을 **여기서 열지 않는다.** 세대를 여는 쪽은 Mac이다(`onUdpOpen`) —
        // 앱을 띄우고 내리는 결정이 거기 있고, 세대 이름을 부여하는 쪽도 하나여야 한다.
        // 이 함수에 남은 일은 "영상이 흐르는가"에 딸린 비용 조절뿐이다.
    }

    /** 확장(Mac→폰 영상)이 서고 눕는 자리. UI 플래그와 저지연 판정을 같이 맞춘다. */
    private fun noteExtendRunning(on: Boolean) {
        extendStreaming = on
        scope.launch(Dispatchers.Main) { extendRunning = on }
        appsChanged()
    }

    /** 공유(폰→Mac 영상)가 서고 눕는 자리. */
    private fun noteShareRunning(on: Boolean) {
        shareStreaming = on
        scope.launch(Dispatchers.Main) { shareRunning = on }
        appsChanged()
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

    private var decoder: VideoDecoder? = null
    private var surface: Surface? = null
    private var pendingConnect = false
    private var playoutBufferMs = 50   // Mac PlayoutConfig 최신값 (새 디코더에 적용)
    private var renderOnArrivalPref: Boolean? = null   // Mac 프로파일(저지연=버퍼 생략); null이면 bearer 기본
    private var sessionHdr10 = false   // Mac SessionParams가 확정한 다이내믹 레인지 (새 디코더에 적용)
    private var sessionHdrStaticInfo: ByteArray? = null   // CTA-861.3 마스터링 메타데이터 (동상)
    private var clockOffsetUs = 0L     // androidµs - macµs (Pong NTP) — 지연 계측용
    private var clockSynced = false
    private var bestNtpDeltaUs = Long.MAX_VALUE
    private var ntpResetAtMs = 0L

    private var handshake: PeriHandshake? = null
    /** Pass 1로 **실제 나간 바이트**. 전사에 그대로 들어간다(재직렬화 금지). */
    private var helloCore: ByteArray? = null

    /**
     * 내가 걸려던 Mac의 공개키. null이면 대조하지 않는다.
     *
     * 주소는 **봉인된 BLE 채널 안에서 그 Mac에게 받은** 후보 묶음에서 온다 — 즉 개시할 때
     * 상대가 누군지 이미 알고 있다. 그 정보를 버리면 STS는 "내 신뢰 목록의 누구인가"까지만
     * 확인하게 되고, Mac을 둘 페어링해 뒀을 때 엉뚱한 쪽에 조용히 붙는다.
     *
     * 케이블이나 주소 직접 입력처럼 의도가 없는 경로에서는 null이다.
     */
    @Volatile private var expectedPeerPub: ByteArray? = null
    @Volatile private var session: PeriCryptoSession? = null

    /**
     * 지금 열려 있는 데이터그램 세대 — Mac이 부여한 id와 거기서 갈라 나온 키.
     *
     * 세션은 TCP 하나로 오래 살고, 이 세대는 **영상을 나르는 앱이 떠 있는 동안만** 산다.
     * null = 지금 UDP 경로가 없다(소켓도 수신 루프도 NAT 매핑도 없다).
     *
     * 여는 쪽은 Mac이다 — 데이터그램은 연결이 없어 받는 쪽이 "이건 어느 세션의 것인가"를
     * 먼저 알아야 열어 볼 키를 고를 수 있고, 그 이름을 부여하는 쪽이 하나여야 안 겹친다.
     */
    @Volatile private var datagramChannelId: ByteArray? = null
    @Volatile private var datagram: PeriDatagramKeys? = null
    private val reassembler = VideoReassembler()
    private var displaySpec: DisplaySpec? = null

    // 접속 정보 (자동 재접속용). 비어 있으면 = 돌아갈 주소가 없다(무손실 경로로 붙은 세션).
    private var host = ""
    private var port = 0

    @Volatile private var lastAoaSwitchFailAt = 0L // 전환 실패 시각 → 쿨다운 (LAN↔AOA 오실레이션 방지)
    private val aoaSwitchCooldownMs = 20_000L

    // keepalive
    private var keepaliveJob: Job? = null
    @Volatile private var lastPongAt = 0L

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

    /** 사용자가 '끊기'로 종료했는지 — UI의 AOA 자동 연결 루프가 재시도 중단 판단에 읽는다. */
    val userClosed: Boolean get() = machine.intent !is Intent.Running

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
            fingerprint = selfFp,
            negoRound = negoRound,
        )
    }

    /** 기계가 모르는 원시 수치 — 디코더·망·피드백. */
    fun linkDiagnostics(): LinkDiagnostics {
        val d = decoder
        val span = if (fbBaseSeq < 0 || fbHighSeq < 0) 0 else (fbHighSeq - fbBaseSeq + 1)
        return LinkDiagnostics(
            transport = netWatcher.transport,
            demotedAddresses = netWatcher.deprioritizedAddresses().size,
            decoderUp = d != null,
            playoutBufferMs = playoutBufferMs,
            renderOnArrival = renderOnArrivalPref ?: (bearer is AoaClient),
            queueDelayMs = d?.queueDelayMs ?: 0,
            pipeNetMs = d?.pipeNetMs ?: 0,
            pipeJbufMs = d?.pipeJbufMs ?: 0,
            pipeDecMs = d?.pipeDecMs ?: 0,
            rttMs = lastRttMs,
            sincePongMs = if (lastPongAt == 0L) -1 else System.currentTimeMillis() - lastPongAt,
            fbReceived = fbRecv,
            fbSpan = span,
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

    /** 이 기기의 신원 지문 — Mac 로그의 `dev[…]`와 같은 값이라 두 로그를 묶는 키가 된다. */
    private val selfFp: String by lazy {
        runCatching { identity.makeCert(Build.MODEL ?: "Android").fingerprint() }.getOrDefault("????")
    }

    /** 재협상 라운드 일련번호. 겹친 라운드를 구분하는 유일한 수단. */
    private var negoRound = 0

    private val logPrefix: String
        get() = "[dev:${selfFp.take(8)}]" + if (negoRound > 0) "[r:$negoRound]" else ""

    private fun lifecycle(msg: String) = Log.i(TAG, "$logPrefix $msg")

    private val evLock = Any()
    private val evQueue = ArrayDeque<Ev>()
    private var draining = false

    /**
     * 사건을 기계에 넣고 돌려받은 부작용을 실행한다.
     *
     * 두 가지를 동시에 지켜야 한다:
     *
     *  1. **직렬화** — 부작용 실행 중에 또 dispatch가 일어난다(`PunchCold` → `startPunch` →
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
            // 그 지시는 `AppControl`로 오고, 거기서 [ensureDecoder]를 부른다.
            // 여기서 미리 세워 두면 Mac이 확장을 안 띄운 채로도 MediaCodec 하나를 잡고 있게 된다.
            Fx.EnsureDecoder -> if (extendRunning) ensureDecoder()
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
            Fx.ReleaseRoundSockets -> closePendingSockets()
            Fx.PunchSeamless -> pendingRemote?.let { startRestartPunch(it) }
            Fx.PunchCold -> {
                val r = pendingRemote
                val d = displaySpec
                if (r != null && d != null) startPunch(r, d)
                else Log.e(TAG, "콜드 펀칭 요청인데 후보/스펙이 없다 (remote=$r spec=$d)")
            }
            Fx.ResetCounters -> { reassembler.clear(); resetCounters() }
            is Fx.Log -> lifecycle(fx.msg)
        }
    }

    /** 세션·핸드셰이크·디코더를 버린다. **재협상 중에는 기계가 이 지시를 내리지 않는다.** */
    private fun dropSession() {
        session = null
        handshake = null
        // 세대는 세션보다 오래 살 수 없다 — 키가 세션 재료에서 갈라 나오기 때문이다.
        // 알릴 채널은 이미 없으므로 조용히 놓는다.
        closeDatagram("세션 종료")
        reassembler.clear()
        resetCounters()
        decoder?.stop(); decoder = null
        // 앱도 같이 내린다. 세션이 사라졌다는 건 **키가 사라졌다**는 뜻이라, 지금부터 굽는
        // 프레임은 봉인할 수도 보낼 수도 없다. 상대에게 알리지 않는 건 알릴 채널이 없기
        // 때문이고, Mac 쪽은 자기 `detach`에서 같은 결론에 이미 도달해 있다.
        noteExtendRunning(false)
        ScreenShare.stop("연결이 끊겼습니다", notifyPeer = false)
        // 새 세션 = 새 키 → nonce 공간이 처음부터 다시 열린다. 번호를 되돌려도 안전한
        // 유일한 지점이 여기다.
        synchronized(shareLock) { shareFrameId = 0L; sharePacketSeq = 0; sharePtsBaseMs = -1L }
    }

    private var backoffJob: Job? = null
    private var negoWatchdog: Job? = null
    private var handshakeWatchdog: Job? = null
    private var pendingByeReason = Bye.Reason.USER_DISCONNECT
    /** [Fx.PunchSeamless]/[Fx.PunchCold]가 쓸 Mac 후보 — `beginPunch`가 채워 둔다. */
    private var pendingRemote: CandidateBundle? = null

    private fun scheduleBackoff(delayMs: Long) {
        backoffJob?.cancel()
        backoffJob = scope.launch {
            delay(delayMs)
            dispatch(Ev.BackoffElapsed)
        }
    }

    private fun reconnectNow() {
        prepareDecoder()
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
        wireScreenShare()
        wireNotifications()
    }

    /**
     * "재협상이 필요하다" — MainActivity가 BLE 코디네이터에 배선한다.
     *
     * 여기서 직접 재펀칭할 수 없는 이유: 홀펀칭은 **양쪽이 거의 동시에** 쏴야 성립하므로
     * 새 후보를 Mac에 전달할 신호 채널이 필요한데, IP가 바뀌는 마당에 IP에 독립적인 경로는
     * BLE뿐이다. 그래서 이 콜백은 신호만 올리고 후보 교환은 BLE가 맡는다.
     */
    var onIceRestartNeeded: ((reason: String) -> Unit)? = null

    /**
     * 기본망이 바뀌었다 → ICE 재협상.
     *
     * **옛 경로는 일부러 안 건드린다.** 셀룰러는 Wi-Fi가 기본망이 된 뒤에도 한동안 살아 있어
     * 그동안 영상이 계속 흐른다. 새 경로가 확정된 뒤에 갈아타면 검은 화면이 없다
     * (make-before-break). 겹침 창이 없는 전환이면 어차피 keepalive가 끊김을 잡고,
     * 그때는 같은 재협상 경로가 '복구'로 동작한다 — 코드가 하나다.
     *
     * 어떤 상태에서 받아들일지·겹친 요청을 어떻게 처리할지는 전부 기계가 정한다. 예전엔 여기서
     * `state`를 손으로 걸렀는데, 그 필터가 정작 재협상이 가장 필요한 순간(ERROR로 떨어진 뒤
     * 14초 만에 새 망이 뜬 경우)을 조용히 삼켰다.
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
            if (session == null) sendStsHello()
        }
        b.onDisconnected = handler@{ err ->
            // 출처가 현재 활성 bearer가 아니면 무시 — LanClient는 로컬 disconnect 시 콜백이
            // 2회(close 직접 + read 루프 예외) 올 수 있고, 교체된 구 AoaClient의 늦은 콜백도 있다.
            // 전환 후 도착한 중복이 LAN 폴백을 오발동하는 것을 막는다.
            if (b !== bearer) {
                Log.d(TAG, "stale bearer 콜백 무시 (${b.javaClass.simpleName})")
                return@handler
            }
            // 여기서 하는 판단은 딱 둘이다: **누가 죽었나**(위의 stale 검사)와 **같은 주소로
            // 다시 붙어볼 수 있나**. 나머지(세션을 버릴지, 재접속할지, 재협상할지, USB에서
            // 무선으로 되돌아갈지)는 전부 기계가 정한다.
            //
            // 특히 "재협상 중 옛 전송의 죽음은 예상된 일이라 세션을 유지한다"는 규칙이 예전엔
            // 이 함수 맨 앞의 4중 조건문이었다. 실기기에서 host 직결 성립 30ms 뒤·`swapTo`
            // 직전에 옛 LTE 연결이 죽었고, 그때 세션이 지워져 Mac은 RESUME으로 재바인딩했는데
            // Android만 세션을 버린 반쪽 상태가 났다. 이제 `reduce`의 한 갈래이고 테스트가 있다.
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

    fun setSurface(surface: Surface?) {
        Log.i(TAG, "setSurface ${if (surface == null) "null" else "valid"} state=$state pending=$pendingConnect")
        this.surface = surface
        if (surface != null && pendingConnect) {
            pendingConnect = false
            startDecoder(surface)
        }
    }

    // MARK: ICE (P2P NAT 트래버설)

    private val ice = IceAgent()

    /**
     * 내 후보를 모은다. 토큰은 Mac이 준 값을 그대로 되돌린다(세션 공유).
     *
     * ★ **기본은 TCP 후보뿐이다.** 세션 수립에 UDP가 필요 없어졌다 — 데이터그램 경로는
     * 영상을 쓰는 앱이 뜰 때 Mac이 제어 채널로 따로 연다(`UdpOpen`). Mac이 `f fastudp`를
     * 켜서 보냈을 때만 여기서도 UDP를 함께 모은다. STUN 왕복이 전송마다 따로 일어나므로
     * 이 구분이 곧 접속 지연이다.
     *
     * **항상 지금 기본망에 바인드한 새 소켓에서 모은다.** 예전엔 첫 연결이면 기존
     * `mediaSocket()`을, 재협상이면 새 소켓을 쓰는 두 갈래였는데 둘 다 문제가 있었다:
     *
     *  - 기존 소켓은 **만들어질 당시의 망에 묶여 있다.** 그 망이 사라지면 STUN이
     *    `ENETUNREACH`로 죽고 UDP 후보가 통째로 비어 영상 경로가 영영 안 열린다
     *    (실기기 로그: 수집 중에 망이 바뀌자 `udp stun 실패: ENETUNREACH` → `내 후보 1개
     *    전송: tcp/srflx만` → `udp hello 실패: ENETUNREACH`).
     *  - 기존 소켓에는 **영상 수신 루프가 앉아 있다.** STUN이 같은 소켓에서 응답을 직접
     *    읽으므로 두 리더가 경합해 서로의 패킷을 훔친다(로그: `Long monitor contention ...
     *    DatagramSocket.receive ... 763ms`, `feedback recv=4/2817`).
     *
     * 새 소켓에서 모으면 둘 다 사라진다. 매핑이 유효해야 한다는 원칙은 그대로 지켜진다 —
     * 이 소켓이 곧 미디어 소켓이 되기 때문이다(`startPunch`가 채택하거나 `swapTo`가 인계).
     * 세션이 살아 있으면 옛 소켓은 그대로 둬 영상이 계속 흐른다(make-before-break).
     *
     * BLE 콜백이 아닌 백그라운드 스레드에서 호출된다(STUN은 네트워크 왕복).
     */
    fun gatherCandidates(remote: CandidateBundle): CandidateBundle {
        // TCP 후보 포트도 **시도마다 새로 잡는다.** 재사용하면 이전 세션의 펀칭 연결이
        // TIME_WAIT로 그 포트를 물고 있어 TCP STUN·펀칭이 EADDRINUSE로 죽는다.
        val tcpPort = java.net.ServerSocket(0).use { it.localPort }
        closePendingSockets()
        pendingTcpPort = tcpPort
        pendingNetwork = netWatcher.current
        val transports = if (remote.fastUdp) {
            setOf(CandidateTransport.TCP, CandidateTransport.UDP)
        } else {
            setOf(CandidateTransport.TCP)
        }
        val sock = client.openMediaSocketOn(pendingNetwork).also { pendingUdp = it }
        // ★ 기본망이 없으면 STUN을 건너뛴다.
        //
        // 기본망이 없다는 건 **인터넷 경로가 없다**는 뜻이지 연결할 수 없다는 뜻이 아니다 —
        // 폰이 핫스팟 AP이고 Mac이 거기 붙어 있으면 업스트림 없이도 host 후보로 곧장 뚫린다.
        // 그때 공개 STUN을 두드려 봐야 서버마다 타임아웃만 쌓이고 얻는 게 없다.
        val servers = if (pendingNetwork == null) {
            Log.i(TAG, "기본망 없음 — STUN 생략, host 후보로만 시도 (핫스팟 등 로컬 링크)")
            emptyList()
        } else {
            StunServer.DEFAULTS
        }
        // 셀룰러 주소는 후보 목록 뒤로 민다 — 상대는 앞에서부터 두드린다.
        val demote = netWatcher.deprioritizedAddresses()
        lifecycle("후보 수집 (망=${netWatcher.transportName}, ${transports.joinToString("+") { it.wire }}"
            + (if (demote.isEmpty()) ")" else ", 셀룰러 주소 ${demote.size}개 후순위)"))
        val list = IceAgent.gather(sock, tcpPort, servers, demote, transports)
        // 우리 묶음에도 같은 플래그를 실어 되돌린다 — 그래야 로그 한 줄만 보고도
        // 양쪽이 같은 모드로 돌았는지 알 수 있다.
        return CandidateBundle(remote.token, list, fastUdp = remote.fastUdp)
    }

    /**
     * 이 라운드의 후보를 모은 망. 펀칭 직전에 지금 망과 대조한다 — 다르면 후보가 이미 낡았다.
     *
     * BLE 라운드가 도는 도중에 망이 바뀔 수 있다(실기기 로그: 후보 수집 시작 2초 뒤 전환).
     * 그대로 진행하면 죽은 망의 매핑으로 10초를 두드리다 실패한다.
     */
    private var pendingNetwork: android.net.Network? = null

    // MARK: 핸드오버 재협상 (make-before-break)

    /** 재협상 라운드에서 새 망에 연 미디어 소켓 — 경로가 확정되면 client로 갈아끼운다. */
    private var pendingUdp: java.net.DatagramSocket? = null
    private var pendingTcpPort = 0

    @Synchronized
    private fun closePendingSockets() {
        pendingUdp?.let { runCatching { it.close() } }
        pendingUdp = null
        pendingTcpPort = 0
        pendingNetwork = null
    }

    /**
     * 이 라운드의 후보가 아직 유효한가 — 수집한 망이 지금 기본망과 같은가.
     *
     * 다르면 펀칭해봐야 죽은 매핑을 두드리는 것이라 10초를 버린다. 대신 재협상을 다시
     * 요청해 새 망 기준으로 라운드를 새로 돈다.
     */
    private fun candidatesStillValid(): Boolean {
        val now = netWatcher.current
        // null끼리도 "같다"로 본다 — 기본망 없는 상태(핫스팟 AP 등)는 그 자체로 유효한 상황이고,
        // host 후보는 여전히 쓸 수 있다. 어긋난 경우만 걸러낸다.
        if (pendingNetwork == now) return true
        Log.w(TAG, "후보가 낡음 — 수집 망 $pendingNetwork ≠ 현재 $now")
        return false
    }

    /**
     * 재협상 라운드 종료 — 성공이든 실패든 여기로 모인다.
     *
     * 실패해도 **아무것도 무너뜨리지 않는다.** 옛 경로가 아직 살아 있으면 영상은 계속 흐르고,
     * 이미 죽었으면 그때 기계가 세션까지 정리한다. 즉 재협상은 순수한 개선 시도이지
     * 필수 경로가 아니다 — 그 판단 전체가 `reduce`의 [Ev.NegoDone] 갈래에 있다.
     */
    private fun finishRestart(success: Boolean, note: String) {
        dispatch(Ev.NegoDone(success, note))
    }

    /** 재협상이 끝났다 — MainActivity가 BLE 광고 요청 비트를 내리도록 배선한다. */
    var onRestartFinished: (() -> Unit)? = null

    /**
     * 새 제어 채널을 연다 — **홀펀칭과 host 직결을 동시에** 시도하고 **더 좋은 쪽**을 쓴다.
     *
     * 같은 LAN에서는 펀칭이 원리상 성공할 수 없다(`punchTcp`는 srflx 후보만 두드린다).
     * 그런데도 순차로 기다리면 매번 10초를 통째로 버린다 — 실기기 로그에서 UDP는 host로
     * 8ms에 뚫렸는데 TCP는 63회/10초를 돌고 실패했다. 무손실 재협상에서는 이게 치명적이라
     * 그 사이 옛 경로가 죽으면 세션이 통째로 날아간다. 그래서 병렬은 유지한다.
     *
     * ★ **먼저 끝난 쪽이 아니라 순위가 높은 쪽이 이긴다.**
     *
     * 두 시도는 같은 로컬 인터페이스를 쓰므로 여기서 "LTE냐 Wi-Fi냐"를 직접 고를 수는 없다.
     * 대신 그 시점에 실제로 아는 것으로 같은 뜻을 표현한다:
     *
     *  - **host 직결 성공 = 로컬 링크가 있다.** 같은 LAN(또는 핫스팟)이라 NAT을 안 거친다.
     *  - **srflx 펀칭 성공 = NAT을 나갔다 온다.** 셀룰러면 그게 곧 캐리어 CGNAT이다.
     *
     * 즉 host > srflx가 곧 "셀룰러 경로를 제일 뒤로"다. 예전엔 `compareAndSet`으로 먼저
     * 완주한 쪽을 잡았는데, 둘 다 성공하는 경우(같은 LAN인데 펀칭이 먼저 뚫림)에 RTT가
     * 몇 배 나쁜 srflx 경로를 채택할 수 있었다. RFC 8445의 지명(nomination)이 존재하는
     * 이유가 정확히 이거다.
     *
     * 대가는 [HOST_GRACE_MS]뿐이다 — 펀칭이 먼저 이겼을 때만, 그것도 host 시도가 아직
     * 진행 중일 때만 그만큼 기다린다. 닿는 LAN에서 host connect는 한 자릿수 ms라 이 유예를
     * 쓰는 일 자체가 드물고, host가 이미 실패했으면 0ms다.
     *
     * **펀칭 타이밍은 손대지 않는다.** TCP simultaneous open은 양쪽이 거의 동시에 두드려야
     * 성립하므로 지연이나 순서 변경은 위험하다. host 시도를 옆에 하나 더 붙일 뿐이다.
     * host 연결은 **커널 배정 포트**에서 나간다 — 펀치 포트를 같이 쓰면 서로 충돌한다.
     */
    private fun openControlChannel(
        agent: IceAgent,
        remote: CandidateBundle,
        punchPort: Int,
    ): java.net.Socket? {
        val tcpCands = remote.ordered(CandidateTransport.TCP)
        val hostCand = tcpCands.firstOrNull { it.kind == CandidateKind.HOST }?.endpoint

        val lock = java.util.concurrent.locks.ReentrantLock()
        val changed = lock.newCondition()
        var host: java.net.Socket? = null      // 1순위 — 로컬 링크
        var punched: java.net.Socket? = null   // 2순위 — NAT 통과(셀룰러면 CGNAT)
        var hostPending = hostCand != null     // host 시도가 아직 결과를 안 냈다
        var punchPending = true
        var committed = false                  // 승자 확정 — 이후 도착분은 즉시 닫는다

        fun offer(isHost: Boolean, s: java.net.Socket?) {
            lock.lock()
            try {
                if (isHost) hostPending = false else punchPending = false
                when {
                    s == null -> {}
                    // 늦게 온 소켓은 쓸 데가 없다. 안 닫으면 FD가 새고 Mac엔 붙었다 끊기는
                    // 익명 연결이 남는다(핸드셰이크 마감 10s가 바닥을 지키긴 한다).
                    committed -> runCatching { s.close() }
                    isHost -> host = s
                    else -> punched = s
                }
                changed.signalAll()
            } finally {
                lock.unlock()
            }
        }

        Thread { offer(false, agent.punchTcp(punchPort, tcpCands)) }
            .apply { isDaemon = true; start() }

        if (hostCand != null) {
            Thread {
                val s = runCatching {
                    java.net.Socket().apply {
                        tcpNoDelay = true
                        connect(java.net.InetSocketAddress(hostCand.address, hostCand.port), 3000)
                    }
                }.getOrNull()
                if (s != null) Log.i(TAG, "host 직결 성공 $hostCand")
                offer(true, s)
            }.apply { isDaemon = true; start() }
        }

        // 둘 다 실패하면 펀칭 타임아웃(10s)까지 기다렸다가 포기한다.
        val deadline = System.currentTimeMillis() + 12_000
        var graceUntil = Long.MAX_VALUE   // 펀칭이 먼저 이겼을 때만 좁혀진다
        val chosen: java.net.Socket?
        val why: String

        lock.lock()
        try {
            while (true) {
                if (host != null) break                          // 1순위 확정 — 더 볼 것 없다
                if (!hostPending && punched != null) break       // host는 끝났고 2순위가 있다
                if (!hostPending && !punchPending) break         // 둘 다 실패 — 마감까지 끌 이유가 없다
                val now = System.currentTimeMillis()
                // 펀칭이 먼저 왔다 → host가 아직 살아 있는 동안만 유예를 준다.
                // (host가 이미 실패했다면 위 조건에서 이미 빠져나갔다.)
                if (punched != null && graceUntil == Long.MAX_VALUE) {
                    graceUntil = now + HOST_GRACE_MS
                }
                if (now >= deadline || now >= graceUntil) break
                val wait = minOf(deadline, graceUntil) - now
                runCatching { changed.await(wait.coerceAtLeast(1), TimeUnit.MILLISECONDS) }
            }
            committed = true
            chosen = host ?: punched
            why = when {
                host != null && punched != null -> "host 직결 (펀칭도 성공했지만 순위가 낮다)"
                host != null -> "host 직결"
                punched != null && hostPending -> "srflx 펀칭 (host 무응답 ${HOST_GRACE_MS}ms 유예 만료)"
                punched != null -> "srflx 펀칭 (host 경로 없음)"
                else -> "없음"
            }
            // 진 소켓은 여기서 닫는다. 아직 안 온 쪽은 `committed`가 처리한다.
            if (chosen !== host) runCatching { host?.close() }
            if (chosen !== punched) runCatching { punched?.close() }
        } finally {
            lock.unlock()
        }

        Log.i(TAG, "제어 채널 지명: $why")
        return chosen
    }

    /**
     * 새 망에서 경로를 뚫고 **세션을 유지한 채** 전송만 갈아끼운다.
     *
     * 핵심은 순서다. 새 TCP가 뚫리면 그 소켓으로 먼저 RESUME 프레임을 보낸다 — Mac은 그걸
     * 시도복호화해서 "이 연결은 살아 있는 그 세션의 것"임을 확인하고 제어 채널만 교체한다.
     * 핸드셰이크가 없으니 새 가상 디스플레이도, 키프레임 대기도, 검은 화면도 없다.
     * 그 다음 UDP 대상까지 새 주소로 옮기고 HELLO를 한 발 보내면 Mac이 영상 피어를 재링크한다.
     */
    private fun startRestartPunch(remote: CandidateBundle) {
        val udpSock = pendingUdp
        val tcpPort = pendingTcpPort
        val s = session
        if (udpSock == null || tcpPort == 0 || s == null) {
            finishRestart(false, "준비된 소켓/세션이 없다"); return
        }

        val agent = IceAgent()
        // 새 소켓 전용 프로브 수신 루프 — 옛 소켓의 루프는 그대로 둔다(영상이 아직 그리로 온다).
        //
        // ★ 중단은 플래그 + 짧은 soTimeout으로만 된다. `interrupt()`도 `Job.cancel()`도
        // 블로킹 `DatagramSocket.receive()`를 깨우지 못해서, 안 그러면 이 스레드가 소켓에
        // 그대로 앉아 교체 뒤에 올라오는 영상 수신 루프와 패킷을 나눠 먹는다.
        val stopProbe = java.util.concurrent.atomic.AtomicBoolean(false)
        val probeReader = Thread {
            val buf = ByteArray(2048)
            runCatching { udpSock.soTimeout = 100 }   // 중단 확인 주기
            while (!stopProbe.get() && !udpSock.isClosed) {
                val pkt = java.net.DatagramPacket(buf, buf.size)
                try {
                    udpSock.receive(pkt)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (_: Throwable) {
                    return@Thread   // 소켓이 닫혔다 = 라운드 종료
                }
                val from = Ipv4Endpoint(pkt.address?.hostAddress ?: "", pkt.port)
                agent.handleInbound(pkt.data.copyOf(pkt.length), from, udpSock)
            }
        }.apply { isDaemon = true; start() }

        /** 프로브 리더를 확실히 내린다 — 소켓을 넘겨주기 전에 반드시. */
        fun stopProbeReader() {
            stopProbe.set(true)
            probeReader.join(500)
        }

        // ★ 데이터그램 세대가 열려 있을 때만 UDP를 같이 옮긴다.
        //
        // 세대가 없다는 건 지금 영상을 나르는 앱이 없다는 뜻이고, 그때 UDP를 뚫어 봐야
        // 아무도 안 쓰는 구멍을 새 망에 다시 여는 일이다. Mac도 그 경우 후보에 UDP를 안 싣는다
        // (열려 있는 세대가 있으면 싣는다 — 그게 두 쪽이 합의하는 방식이다).
        val movingDatagram = datagramChannelId != null &&
            remote.ordered(CandidateTransport.UDP).isNotEmpty()

        Thread {
            // UDP와 TCP는 서로를 기다리지 않는다 — 각자 뚫리는 대로 결과를 낸다.
            val udpResult = arrayOfNulls<Ipv4Endpoint>(1)
            val udpThread = if (!movingDatagram) null else Thread {
                udpResult[0] = agent.punchUdp(
                    udpSock, remote.ordered(CandidateTransport.UDP), remote.token,
                )?.endpoint
            }.apply { start() }

            // 펀칭 + host 직결 병렬. 같은 LAN으로 돌아온 경우가 흔한데(Wi-Fi 복귀) 그땐
            // 펀칭이 원리상 안 되므로 host가 없으면 재협상이 통째로 실패한다(실기기 로그).
            val tcp = openControlChannel(agent, remote, tcpPort)
            udpThread?.join(2000)
            val newUdpTarget = udpResult[0]

            if (tcp == null) {
                // 제어 채널을 못 열었으면 갈아탈 수 없다 — UDP만 뚫려도 제어가 옛 경로면
                // 그 경로가 죽는 순간 세션이 끝난다. 옛 경로를 그대로 두고 물러난다.
                stopProbeReader()
                finishRestart(false, "새 망에서 제어 채널 확보 실패")
                return@Thread
            }

            // ★ RESUME을 **swapTo보다 먼저**, 그리고 이 소켓의 첫 바이트로 보낸다.
            // Mac은 아직 이 연결이 누구 것인지 모르는 상태라, 다른 프레임이 먼저 가면 버려진다.
            val sealed = synchronized(sendLock) { s.seal(remote.token) }
            val ok = runCatching {
                tcp.getOutputStream().apply {
                    write(WireFormat.resumeFrame(sealed.first, sealed.second))
                    flush()
                }
            }.isSuccess
            if (!ok) {
                runCatching { tcp.close() }
                stopProbeReader()
                finishRestart(false, "RESUME 송신 실패")
                return@Thread
            }

            // UDP가 안 뚫렸으면 Mac의 **UDP 후보**로 보낸다.
            //
            // ★ 제어(TCP) 소켓의 주소를 쓰면 안 된다. 포트가 유동이라 Mac의 제어 포트와 영상
            // 포트는 애초에 다른 번호고, 펀칭 경로면 한 번 더 갈린다. 그리로 HELLO를 보내면
            // Mac의 UDP 소켓에 닿지 않아 영상 게이트가 영영 안 열린다(검은 화면 + 비트레이트 0).
            //
            // 세대가 없으면(영상 앱 없음) 보낼 곳도 필요 없다 — 옛 코드는 그때도 UDP 대상을
            // 요구해서, 후보에 UDP가 없다는 이유만으로 멀쩡한 제어 채널 교체를 실패시켰다.
            val target = if (movingDatagram) pickUdpTarget(remote, newUdpTarget) else null
            if (movingDatagram && target == null) {
                runCatching { tcp.close() }
                stopProbeReader()
                finishRestart(false, "UDP 펀칭 실패 + UDP 후보 없음 — 영상 경로 없음")
                return@Thread
            }
            // ★ 소켓을 넘기기 전에 프로브 리더를 확실히 내린다. 살려 두면 swapTo가 띄우는
            // 영상 수신 루프와 같은 소켓에 두 리더가 앉아 패킷을 나눠 먹는다.
            stopProbeReader()
            pendingUdp = null   // swapTo가 소유권을 가져간다 — closePendingSockets가 닫지 않게
            client.swapTo(tcp, udpSock, target)

            // 새 UDP 경로를 Mac에 등록 — 이게 있어야 영상이 새 주소로 온다.
            sendUdpHello()
            // 경로가 살아났음을 기계에 알린다. keepalive 재개는 이 사건의 부작용이다 —
            // 재개 경로엔 핸드셰이크가 없어 그걸 부르는 곳이 여기뿐이고, 빠뜨리면 Ping이 끊겨
            // Mac의 생존감지(6s)가 방금 살려 낸 세션을 도로 죽인다.
            dispatch(Ev.PathSwapped)
            finishRestart(true, "udp=${newUdpTarget ?: "제어 주소 사용"} tcp=${tcp.inetAddress?.hostAddress}:${tcp.port}")
        }.start()
    }

    /**
     * Mac 후보 도착 → 홀펀칭 후 그 경로로 접속.
     *
     * TCP가 뚫리면 그 소켓을 제어 채널로 채택하고, 아니면 host 후보로 평범하게 connect한다
     * (같은 LAN이면 이 폴백이 곧 예전 동작이다).
     */
    fun beginPunch(remote: CandidateBundle, display: DisplaySpec) {
        // 무손실 교체와 콜드 재연결 중 무엇을 할지는 **기계가 정한다** — 판단 근거는 "세션이
        // 살아 있는가" 하나다. 예전엔 여기서 `restarting && session != null`을 손으로 계산했다.
        //
        // 라운드가 도는 사이에 망이 또 바뀌었으면 후보가 이미 죽었으므로 그 사실만 실어 보낸다.
        this.displaySpec = display
        pendingRemote = remote
        dispatch(Ev.MacAnswered(candidatesFresh = candidatesStillValid()))
    }

    /**
     * 영상 UDP 대상 선택 — **콜드·재개 공통 규칙**.
     *
     *  1. 펀칭이 왕복을 검증한 주소가 최우선. 실제로 뚫린 구멍이다.
     *  2. 없으면 **host**. 펀칭이 아직/영영 성립하지 않았다는 건 srflx로는 구멍이 없다는 뜻이라,
     *     그리로 보내면 확실히 버려진다. 반면 host는 같은 LAN이면 구멍 없이도 닿는다.
     *  3. 그래도 없으면 남은 첫 후보.
     *
     * 예전엔 콜드가 `SRFLX ?: first`, 재개가 `HOST ?: first`로 **정반대**였다. 콜드 쪽이 틀렸다 —
     * 핸드셰이크가 펀칭보다 먼저 끝나면 HELLO가 공인 주소로 나가 영영 안 닿았다.
     */
    private fun pickUdpTarget(remote: CandidateBundle, punched: Ipv4Endpoint?): Ipv4Endpoint? {
        val cands = remote.ordered(CandidateTransport.UDP)
        return punched
            ?: cands.firstOrNull { it.kind == CandidateKind.HOST }?.endpoint
            ?: cands.firstOrNull()?.endpoint
    }

    private fun startPunch(remote: CandidateBundle, display: DisplaySpec) {
        this.displaySpec = display
        dispatch(Ev.StartConnect(Via.LAN))
        bearer = client

        // ★ UDP 후보가 없으면 이 라운드에 UDP는 없다 — 기본 모드에서는 그게 정상이다.
        // 데이터그램 경로는 영상 앱이 뜰 때 Mac이 `UdpOpen`으로 따로 연다.
        val wantUdp = remote.ordered(CandidateTransport.UDP).isNotEmpty()
        // 영상 UDP 대상을 **미리** 잡아 둔다. 핸드셰이크가 UDP 펀칭보다 먼저 끝나면 HELLO가
        // udpTarget=null인 채로 나가 TCP 주소로 잘못 가기 때문이다. 펀칭이 확정되면 덮어쓴다.
        if (wantUdp) client.udpTarget = pickUdpTarget(remote, null)

        Thread {
            // ★ 소켓 조작은 **메인이 아닌 여기서** 한다. 소켓 close/교체는 다른 스레드의
            // blocking `receive()`와 얽힐 수 있는데, 메인이 거기 걸리면 앱 전체가 멎고
            // (실기기 ANR) beginPunch가 Dispatchers.Main이라 펀칭이 시작조차 못 한다.
            //
            // 그리고 **후보를 모은 그 소켓/포트**로 갈아타야 한다. `gatherCandidates`는 지금
            // 기본망에 바인드한 새 소켓에서 STUN을 쐈으므로, 상대에게 알린 매핑은 그 소켓의
            // 것이다. 옛 소켓으로 펀칭하면 출발지가 달라 구멍이 무효가 된다(증상 없이 실패).
            pendingUdp?.let { client.adoptMediaSocket(it); pendingUdp = null }
            if (pendingTcpPort != 0) { client.reserveTcpPort(pendingTcpPort); pendingTcpPort = 0 }

            val sock = client.mediaSocket()

            // UDP 펀칭은 영상 경로를 여는 것 — 결과와 무관하게 TCP 제어가 붙어야 세션이 산다.
            if (wantUdp) {
                client.onUdpProbe = { data, from -> ice.handleInbound(data, from, sock) }
                // 후보 수집(STUN)은 끝났으니 이제 수신 루프를 연다 — 펀칭 프로브를 받으려면 필수.
                client.startUdpReceive()
                Thread {
                    val path = ice.punchUdp(sock, remote.ordered(CandidateTransport.UDP), remote.token)
                    if (path != null) {
                        // ★ 영상은 **이 주소로** 보낸다. TCP 제어 채널과 포트가 다르다 —
                        // TCP 주소로 HELLO를 보내면 Mac의 UDP 소켓에 안 닿아 게이트가 안 열린다.
                        val before = client.udpTarget
                        client.udpTarget = pickUdpTarget(remote, path.endpoint)
                        Log.i(TAG, "영상 UDP 대상 확정 ${path.endpoint} (rtt=${path.rttMs}ms)")
                        // ★ 대상이 바뀌었고 세대가 이미 열려 있다면 HELLO를 다시 보낸다.
                        //
                        // Mac은 HELLO의 **출발지**로 영상 피어를 링크한다. 미리 잡아 둔 주소가
                        // 틀렸으면 첫 HELLO는 그대로 유실된다(영상 없는 검은 화면).
                        if (before != client.udpTarget && datagramChannelId != null) {
                            Log.i(TAG, "UDP 대상 변경 $before → ${client.udpTarget} — HELLO 재전송")
                            sendUdpHello()
                        }
                    }
                }.start()
            }

            // 펀칭과 host 직결을 **병렬로** — 같은 LAN이면 host가 ~10ms에 이기고,
            // 망이 다르면 펀칭이 이긴다. 순차로 두면 같은 LAN에서 매번 10초를 버린다.
            val opened = openControlChannel(ice, remote, client.tcpLocalPort)
            scope.launch(Dispatchers.Main) {
                prepareDecoder()
            }
            if (opened != null) {
                Log.i(TAG, "제어 채널 확보 ${opened.inetAddress}:${opened.port}")
                // 펀칭으로 붙은 주소는 재접속에 쓸 수 없다(NAT 매핑은 그 순간의 것). host
                // 직결이면 유효하지만 구분이 어려우므로, 복구는 재협상 한 갈래로 통일한다.
                client.adoptPunchedSocket(opened)
                finishRestart(true, "콜드 재연결 성공")
            } else {
                Log.e(TAG, "펀칭·host 모두 실패 — 연결 불가")
                finishRestart(false, "제어 채널 확보 실패")
            }
        }.start()
    }

    fun connect(host: String, port: Int, display: DisplaySpec) {
        this.host = host
        this.port = port
        this.displaySpec = display
        dispatch(Ev.StartConnect(Via.LAN))
        bearer = client
        prepareDecoder()
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
        aoa = a
        bearer = a
        wireBearer(a)
        dispatch(Ev.StartConnect(Via.AOA))
        prepareDecoder()
        a.open(accessory)
        // (제거됨) AOA 전용 8초 핸드셰이크 타임아웃. 같은 마감이 이제 [Fx.ArmHandshakeWatchdog]로
        // LAN·AOA 공통이다 — 예전엔 LAN 쪽에 이 마감이 아예 없어서, Mac이 TCP만 받고
        // ServerHello를 안 보내면 영원히 "핸드셰이크 중…"에 머물렀다.
        //
        // 다만 fd가 열리기만 하고 `onConnected`조차 안 오면 핸드셰이크 워치독은 무장되지
        // 않는다(전송이 아직 Live가 아니므로). 그건 AoaClient의 open 실패 경로가 잡는다.
    }

    /**
     * 사용자 '끊기' / 앱 종료 — 이 기기가 **떠난다**. 전송 전환과 명확히 다른 경로다
     * (전환은 controller.disconnect()가 아니라 bearer.disconnect()만 부른다).
     *
     * 떠나기 전에 Bye를 보내 Mac이 유예 없이 가상 디스플레이를 정리하게 한다. 이게 없으면
     * Mac은 "또 끊겼나 보다"로 보고 유예 시간만큼 유령 디스플레이를 들고 있게 된다.
     * 반드시 bearer/session을 내리기 *전에* 보내야 한다(암호화·전송 모두 살아 있어야 함).
     */
    fun disconnect(reason: Bye.Reason = Bye.Reason.USER_DISCONNECT) {
        // Bye 송신은 [Fx.SendBye]로 나가며, 기계가 그걸 **전송을 닫기 전에** 배치한다.
        // 순서가 뒤집히면 Mac은 "또 끊겼나 보다"로 보고 유예 시간만큼 유령 디스플레이를 든다.
        pendingByeReason = reason
        dispatch(Ev.UserStop)
    }

    /**
     * Mac이 세션을 끝냈다(HOST_DISCONNECT). 우리도 정리하되 **Bye를 되보내지 않고**
     * (핑퐁 방지) 자동 재접속도 하지 않는다 — 정지 래치가 LAN 재접속과 AOA 폴링 재시도를
     * 함께 막는다. 사용자가 기기에서 '연결'을 누르거나 Mac에서 다시 연결하면 재개된다.
     */
    /**
     * Mac이 Bye를 보냈다 — **사유에 따라 갈린다.**
     *
     * 예전엔 사유를 보지 않고 전부 [Ev.HostStop]으로 처리했다. 그러면 페어링이 해제돼도
     * 평범한 '연결 해제'와 똑같이 취급돼, 화면엔 그냥 "연결 안 됨"이 뜨고 다음 기회에
     * 다시 붙으려 든다. `AUTH_REJECTED`는 **재시도가 원리상 무의미한** 경우라 치명 래치로
     * 가야 한다 — 그래야 Mac이 후보를 줘도 무시하고([Ev.MacAnswered]의 첫 갈래), 사용자에게
     * 왜 안 되는지가 표시된다.
     *
     * proto의 `Bye.Reason.AUTH_REJECTED` 주석이 이 대칭을 요구하고 있었는데 클라이언트
     * 구현이 비어 있었다.
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
        val s = session ?: return   // 핸드셰이크 전이면 보낼 채널이 없다
        val frame = runCatching {
            synchronized(sendLock) {
                val (nonce, body) = s.seal(
                    ControlMessage.newBuilder()
                        .setBye(Bye.newBuilder().setReason(reason).build())
                        .build().toByteArray(),
                )
                WireFormat.controlFrame(nonce, body)
            }
        }.getOrElse { lifecycle("bye 봉인 실패: ${it.message}"); return }
        // ★ 로그는 **바이트가 실제로 나간 뒤에** 찍는다. 예전엔 큐에 넣자마자 "bye 전송"을
        // 찍었는데, 곧바로 소켓이 닫혀 Bye가 유실돼도 로그는 보낸 것처럼 보였다.
        //
        // (제거됨) `if (b is LanClient)` 분기. 완료 통보가 LAN에만 있어서 AOA는 그냥 큐에 넣고
        // "전달"이라고 찍었는데, 정작 **AOA에서도 같은 유실이 나고 있었다** — USB로 '끊기'를
        // 누르면 Bye가 큐에 들어간 직후 fd가 닫혀 사라졌고, Mac은 8초 무응답으로 정리했다.
        // 분기가 그 구멍을 가린 셈이다. 이제 `Bearer`의 공통 계약이라 전송을 구분하지 않는다.
        bearer.sendControl(frame) { lifecycle("bye 송신 완료 (reason=$reason)") }
    }

    fun release() {
        disconnect(Bye.Reason.APP_EXIT)
        // Bye가 선에 오를 틈을 준다. 송신 큐는 `scope`의 Job을 공유하므로 곧바로 `scope.cancel()`을
        // 하면 예약된 Bye까지 취소된다 — 그러면 Mac이 60초 유예를 도는 유령 디스플레이가 남는다.
        // 앱 종료 경로라 짧은 블로킹이 허용되고, Mac 쪽 `requestDisconnect`도 같은 이유로 120ms를 쉰다.
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
        Log.i(TAG, "onDisplayChanged ${spec.widthPx}x${spec.heightPx} state=${machine.conn} prev=${prev?.let { "${it.widthPx}x${it.heightPx}" }}")
        displaySpec = spec
        if (machine.conn != ConnState.STREAMING) return
        if (prev != null && prev.widthPx == spec.widthPx && prev.heightPx == spec.heightPx) {
            Log.i(TAG, "onDisplayChanged: 해상도 동일 → 재협상 스킵 (probe가 회전 미반영?)")
            return
        }
        Log.i(TAG, "display changed → renegotiate ${spec.widthPx}x${spec.heightPx}")
        decoder?.stop(); decoder = null
        reassembler.clear()
        resetCounters()
        prepareDecoder()   // 회전이면 확장이 이미 떠 있다 → 새 해상도 키프레임을 받을 준비
        sendControlMessage(
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

    // (제거됨) `scheduleReconnect` — "재접속할까/재협상할까/포기할까"의 판단이 여기 있었고,
    // `restarting`·`host.isEmpty()`·`reconnectAttempts`를 각각 확인하는 3중 가드였다.
    // 지금은 그 판단 전체가 `reduce`의 [Ev.TransportDown] 갈래에 있고, 여기 남은 건
    // 타이머([Fx.ScheduleBackoff])와 실제 connect([Fx.ReconnectNow])뿐이다.

    // MARK: - handshake

    /** PQ-STS Pass 1 — 우리 임시 X25519 + ML-KEM 공개키. 신원은 아직 싣지 않는다. */
    private fun sendStsHello() {
        val hs = PeriHandshake()
        handshake = hs
        val core = StsHelloCore.newBuilder()
            .setProtocolVersion(PeriSts.PROTOCOL_VERSION)
            .setSessionId(ByteString.copyFrom(hs.sessionId))
            .setX25519Pub(ByteString.copyFrom(hs.x25519PubBytes))
            .setMlkem768Pub(ByteString.copyFrom(hs.mlkemPubBytes))
            .setClientName(Build.MODEL ?: "Android")
            .setNetwork(currentClientNetwork())
        CodecSupport.supportedDecoders().forEach { core.addSupportedCodecs(it) }
        displaySpec?.let { core.addAllDecoders(it.decoders) }
        displaySpec?.let { d ->
            core.display = DisplayCaps.newBuilder()
                .setWidthPx(d.widthPx)
                .setHeightPx(d.heightPx)
                .setDensityDpi(d.densityDpi)
                .setRefreshRate(d.refreshRate)
                .setOrientation(
                    if (d.isLandscape) Orientation.ORIENTATION_LANDSCAPE
                    else Orientation.ORIENTATION_PORTRAIT
                )
                .setMaxWidthPx(d.maxWidthPx)
                .setMaxHeightPx(d.maxHeightPx)
                .addAllSupportedDynamicRanges(d.dynamicRanges)
                .build()
        }
        // ★ 직렬화한 **이 바이트**가 서명 대상이다. 다시 만들지 않고 들고 있는다 —
        // protobuf 재직렬화가 바이트 단위로 같다는 보장이 없다([PeriSts] 주석).
        val coreBytes = core.build().toByteArray()
        helloCore = coreBytes
        val msg = ControlMessage.newBuilder()
            .setStsHello(StsHello.newBuilder().setCore(ByteString.copyFrom(coreBytes)))
            .build()
        bearer.sendControl(WireFormat.handshakeFrame(msg))
    }

    /**
     * 봉인을 열고 **이 Mac이 누구인지** 알아낸다. 하나라도 어긋나면 null이다.
     *
     * 보통은 **조회**다 — 서명을 저장된 공개키들로 시도검증하면 누구인지 나온다. 검증자는
     * 서명 대상(전사)을 이미 알고 있으므로 상대가 공개키를 보낼 이유가 없고, 안 보내니
     * 낯선 접속자는 봉인을 풀어도 **검증할 수 없는 64바이트**만 얻는다.
     *
     * 예외가 하나 있다: 케이블로 처음 붙는 순간. 그때는 조회할 게 없어 상대가 신원을
     * 실어 보내고, 케이블이 채널을 인증했다는 근거로 신뢰를 세운다. 케이블에는 지나가는
     * 관찰자가 없으므로 위의 유출 논리가 적용되지 않는다.
     *
     * 열림(=같은 K) · 서명 · 신뢰, 셋을 한 곳에서 본다. 나눠 두면 호출부가 하나를 빠뜨릴 수
     * 있고, 여기서 빠뜨린 하나는 곧 인증 우회다.
     */
    private fun resolveServer(
        authKey: ByteArray, sealed: ByteArray, signedBytes: ByteArray,
    ): TrustedPeer? {
        val plain = PeriSts.openAuth(authKey, sealed, PeriSts.Role.SERVER) ?: return null
        val auth = runCatching { StsAuth.parseFrom(plain) }.getOrNull() ?: return null
        val sig = auth.signature.toByteArray()

        PeriSts.resolveSigner(sig, signedBytes, trustStore.all())?.let { return it }

        // 조회 실패 = 모르는 Mac. 케이블이 아니면 여기서 끝이다(fail-closed).
        if (bearer !is AoaClient || auth.identityCert.isEmpty) return null
        val cert = PeriCert.parse(auth.identityCert.toByteArray()) ?: return null
        if (!cert.verifySelfSignature()) return null
        if (!PeriIdentity.verify(cert.pub, sig, signedBytes)) return null
        trustStore.trust(cert)
        Log.i(TAG, "USB 케이블로 Mac 신뢰 설정: ${cert.name} [${cert.fingerprint()}]")
        val now = System.currentTimeMillis()
        return TrustedPeer(cert.pub, cert.name, now, now)
    }

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
                val resp = msg.stsResponse
                val hs = handshake ?: return
                val mine = helloCore ?: return

                // PQ-STS Pass 2 검증.
                //
                // 옛 방식과의 차이가 여기 다 있다: 서버 인증서와 서명이 **봉인돼** 오므로
                // 같은 망의 관찰자에게 신원이 새지 않고, 서명이 덮는 전사가 `core` 바이트
                // 전체라 코덱·해상도·HDR·버전 강등이 불가능하다. 그리고 봉인이 열렸다는
                // 사실 자체가 상대도 같은 K에 도달했다는 증거다(키 확인).
                val core = runCatching { StsResponseCore.parseFrom(resp.core) }.getOrNull()
                    ?: run { dispatch(Ev.Fatal("핸드셰이크 형식 오류")); return }
                val (ecdh, kem) = runCatching {
                    hs.rawSecrets(core.x25519Pub.toByteArray(), core.mlkem768Ciphertext.toByteArray())
                }.getOrElse {
                    Log.e(TAG, "key derive failed: ${it.message}", it)
                    dispatch(Ev.Fatal("키 파생 실패: ${it.message}")); return
                }
                val keys = PeriSts.derive(ecdh, kem)
                val th = PeriSts.transcript(mine, resp.core.toByteArray())

                val peer = resolveServer(
                    keys.auth, resp.sealedAuth.toByteArray(), PeriSts.serverSigningBytes(th),
                ) ?: run {
                    Log.e(TAG, "server identity verification FAILED")
                    // 래치다 — 이후 망이 몇 번 바뀌어도 이 Mac에 다시 붙지 않는다.
                    dispatch(Ev.Fatal("서버(Mac) 인증 실패 — 연결 거부"))
                    return
                }

                // ★ **내가 걸려던 그 Mac인가.**
                //
                // 위 조회는 "내 신뢰 목록의 누구인가"까지만 답한다. Mac을 둘 페어링해 뒀다면
                // A에게 걸었는데 B가 응답해도 그냥 통과한다 — 주소가 재사용됐든, 경로상의
                // 누군가 돌렸든. 그래서 의도와 대조한다. 의도가 없는 경로(케이블·주소 직접
                // 입력)에서는 검사할 게 없으므로 건너뛴다.
                val want = expectedPeerPub
                if (want != null && !want.contentEquals(peer.pub)) {
                    Log.e(TAG, "다른 Mac이 응답했다 — 기대 ${want.size}B 키와 불일치")
                    dispatch(Ev.Fatal("연결하려던 Mac이 아닙니다"))
                    return
                }
                trustStore.touch(peer.pub)

                session = keys.session
                // 세션이 섰다 — keepalive 시작은 이 사건의 부작용이다(콜드·재개 공통).
                dispatch(Ev.SessionLive(peer.name))

                // Pass 3 — 우리 신원도 봉인해 보낸다. 이걸로 상호 인증이 닫힌다.
                val auth = StsAuth.newBuilder()
                    .setSignature(ByteString.copyFrom(
                        identity.sign(PeriSts.clientSigningBytes(th))))
                    .also {
                        // ★ 공개키를 싣지 않는다. Mac은 저장된 키로 시도검증해 우리를 찾는다.
                        // 예외는 케이블 부트스트랩뿐 — 그때만 Mac에게 조회할 게 없다.
                        if (bearer is AoaClient) {
                            it.identityCert = ByteString.copyFrom(
                                identity.makeCert(Build.MODEL ?: "Android").serialized())
                        }
                    }
                    .build()
                bearer.sendControl(
                    WireFormat.handshakeFrame(
                        ControlMessage.newBuilder().setStsConfirm(
                            StsConfirm.newBuilder().setSealedAuth(
                                ByteString.copyFrom(
                                    PeriSts.sealAuth(keys.auth, auth.toByteArray(),
                                                     PeriSts.Role.CLIENT))),
                        ).build(),
                    ),
                )
                // ★ **여기서 UDP를 열지 않는다.** 세션 수립은 TCP만으로 끝난다 — 영상 채널은
                // 그걸 쓰는 앱(확장·공유)이 실제로 뜰 때 연다. 예전엔 핸드셰이크 끝에 무조건
                // HELLO를 쏴서, 알림만 쓰는 세션도 UDP 소켓과 NAT 매핑을 붙들고 있었다.
            }

            is WireFrame.Control -> {
                val s = session ?: return
                val plain = runCatching { s.open(frame.nonce, frame.body) }.getOrNull() ?: return
                val msg = runCatching { ControlMessage.parseFrom(plain) }.getOrNull() ?: return
                when {
                    msg.hasSessionParams() -> {
                        val sp = msg.sessionParams
                        // Mac이 HDR 파이프라인 구성에 실패하면 SDR로 강등한 SessionParams를 다시 보낸다
                        // → 마지막에 온 값이 언제나 진실이다. 이미 선 디코더에도 반영해 둔다.
                        sessionHdr10 = sp.dynamicRange == DynamicRange.DYNAMIC_RANGE_HDR10
                        sessionHdrStaticInfo =
                            if (sp.hdrStaticInfo.isEmpty) null else sp.hdrStaticInfo.toByteArray()
                        // 레인지가 바뀌면 디코더를 새로 만들어야 한다(색상 설정이 굳어 있다).
                        // 주기 IDR이 없으므로 키프레임을 직접 요청하지 않으면 재구성이 무한정
                        // 미뤄져 화면이 멈춘다. 제어 채널과 영상 채널은 순서 보장이 없어서,
                        // 새 키프레임이 SessionParams보다 먼저 왔을 수 있다는 점도 이걸로 덮인다.
                        if (decoder?.applyDynamicRange(sessionHdr10, sessionHdrStaticInfo) == true) {
                            requestRefresh()
                        }
                        Log.i(TAG, "송출 파라미터 ${sp.width}x${sp.height} ${sp.codec.name} " +
                                   (if (sessionHdr10) "HDR10" else "SDR") +
                                   (sessionHdrStaticInfo?.let { " mastering=${it.size}B" } ?: ""))
                    }
                    msg.hasPong() -> {
                        lastPongAt = System.currentTimeMillis()
                        val t1 = msg.pong.timestampUs             // Android send (t1)
                        val t4 = System.nanoTime() / 1000         // Android recv (t4)
                        val rttUs = t4 - t1
                        if (rttUs in 0..10_000_000) {
                            Log.d(TAG, "RTT ${rttUs / 1000}ms")
                            lastRttMs = (rttUs / 1000).toInt()    // 다음 피드백에 실어 Mac 표시
                        }
                        val t2 = msg.pong.macRecvUs; val t3 = msg.pong.macSendUs  // Mac recv/send
                        if (t2 > 0 && t3 > 0) updateClockSync(t1, t2, t3, t4)     // NTP 클럭오프셋
                    }
                    msg.hasPlayoutConfig() -> {   // Mac UI 프로파일/슬라이더 → 재생 정책 라이브 조절
                        playoutBufferMs = msg.playoutConfig.bufferMs.coerceIn(5, 300)
                        renderOnArrivalPref = msg.playoutConfig.renderOnArrival  // 저지연=버퍼 생략
                        decoder?.bufferMs = playoutBufferMs
                        decoder?.renderOnArrival = msg.playoutConfig.renderOnArrival
                        Log.d(TAG, "playout buffer=${playoutBufferMs}ms renderOnArrival=${msg.playoutConfig.renderOnArrival}")
                    }
                    msg.hasUdpOpen() -> onUdpOpen(msg.udpOpen)
                    msg.hasUdpClose() -> onUdpClose(msg.udpClose)
                    msg.hasAppControl() -> onAppControl(msg.appControl)
                    msg.hasShareRequest() -> onShareRequest(msg.shareRequest)
                    msg.hasNotificationSync() -> NotifyBridge.syncNow()
                    msg.hasNotificationAction() -> onNotificationAction(msg.notificationAction)
                    msg.hasBye() -> onHostBye(msg.bye.reason)
                }
            }

            is WireFrame.Video -> {   // AOA(신뢰·순서보장 파이프) 영상 — UDP 재조립/손실기전 없이 통째
                val s = session ?: return
                val plain = runCatching { s.openVideo(frame.body, frame.header.seq.toLong()) }.getOrElse {
                    Log.e(TAG, "video(aoa) decrypt FAILED: ${it.message}")
                    return
                }
                if (plain.size < 16) return   // 계측 임베드 [ptsHostUs 8B][macSendUs 8B]
                val ptsHostUs = be64(plain, 0)
                val macSendUs = be64(plain, 8)
                val bitstream = plain.copyOfRange(16, plain.size)
                videoFrameCount++
                if (videoFrameCount <= 3 || videoFrameCount % 120 == 0) {
                    Log.i(TAG, "video(aoa) #$videoFrameCount seq=${frame.header.seq} key=${frame.header.keyframe} ${bitstream.size}B")
                }
                decoder?.submit(bitstream, frame.header.keyframe, frame.header.timestampMs * 1000,
                                frame.header.codec, ptsHostUs, macSendUs)
            }
        }
    }

    // MARK: - keepalive (Ping/Pong) + Transport-CC

    /**
     * Ping 간격 — **영상이 흐를 때만 빠르다.**
     *
     * 이 Ping이 하는 일은 셋이다: 생존 확인, RTT 측정, 망 변경 통지. 뒤의 둘은 영상이 흐를
     * 때 값을 한다(지터버퍼가 RTT를 먹고, 핸드오버는 스트림이 끊기는 사건이다). 알림만 받는
     * 세션에서는 생존 확인만 남는데, 그건 1초마다 할 일이 아니다.
     *
     * 1초는 분당 60번 라디오를 깨우는 것이고, 그 사이 Wi-Fi는 절전으로 못 들어간다.
     */
    private val keepalivePeriodMs: Long
        get() = if (streaming) STREAM_PING_MS else IDLE_PING_MS

    private fun startKeepalive() {
        keepaliveJob?.cancel()
        lastPongAt = System.currentTimeMillis()
        keepaliveJob = scope.launch {
            while (isActive) {
                val period = keepalivePeriodMs
                delay(period)
                val s = session ?: continue
                // Ping (생존 + RTT)
                sendControlMessage(
                    ControlMessage.newBuilder().setPing(
                        Ping.newBuilder()
                            .setTimestampUs(System.nanoTime() / 1000)
                            // 재개는 ClientHello를 새로 안 보내므로, 망이 바뀐 사실을 Mac에
                            // 알리는 통로가 여기뿐이다.
                            .setNetwork(currentClientNetwork())
                            .build()
                    ).build()
                )
                // Transport-CC 손실 보고
                sendFeedback()
                // 죽음 판정도 **주기를 따라간다.** 5초에 한 번 보내면서 3초 안에 답을 요구하면
                // 멀쩡한 링크를 매번 스스로 끊는다.
                val deadline = period * DEAD_AFTER_PERIODS
                if (System.currentTimeMillis() - lastPongAt > deadline) {
                    Log.w(TAG, "keepalive timeout (${deadline}ms) → 링크를 끊어 복구를 태운다")
                    // 끊으면 onDisconnected → Ev.TransportDown이 되고, 그때 기계가 재접속·재협상
                    // 중 무엇을 할지 정한다. 여기서 판단하지 않는다.
                    bearer.disconnect()
                    break
                }
            }
        }
    }

    // MARK: - video path

    private var videoFrameCount = 0
    private var lastPacketSeq = -1
    private var lastRefreshReqMs = 0L

    // Transport-CC 윈도우 (1s 주기 집계)
    private var fbBaseSeq = -1
    private var fbHighSeq = -1
    private var fbRecv = 0
    private var feedbackSeq = 0
    /** 마지막 Ping→Pong 왕복(ms). Pong 핸들러가 갱신, 피드백에 실어 Mac 상세 화면에 표시. */
    @Volatile private var lastRttMs = 0

    private fun resetCounters() {
        lastPacketSeq = -1
        fbBaseSeq = -1; fbHighSeq = -1; fbRecv = 0
    }

    /** Mini-RTP 패킷 수신 → 손실감지/집계 → 재조립 → 복호화 → 디코딩 → (LTR이면) ACK. */
    private fun onVideoPacket(pkt: MiniRtpPacket) {
        // 세션 키가 아니라 **이 세대의 키**로 연다. 세대가 없으면 열려 있는 UDP 경로가
        // 없다는 뜻이고, 그때 오는 데이터그램은 지난 세대의 잔향이거나 남의 것이다.
        val keys = datagram ?: return
        detectLoss(pkt.packetSeq)
        accountForFeedback(pkt.packetSeq)
        val frame = reassembler.offer(pkt) ?: return
        val plain = runCatching { keys.openVideo(frame.body, frame.frameId) }.getOrElse {
            Log.e(TAG, "video decrypt FAILED (m2a): ${it.message}")
            return
        }
        if (plain.size < 16) return                       // 지연 계측 임베드([ptsHostUs 8B][macSendUs 8B])
        val ptsHostUs = be64(plain, 0)
        val macSendUs = be64(plain, 8)
        val bitstream = plain.copyOfRange(16, plain.size)
        videoFrameCount++
        if (videoFrameCount <= 3 || videoFrameCount % 120 == 0) {
            Log.i(TAG, "video frame #$videoFrameCount id=${frame.frameId} key=${frame.keyframe} ltr=${frame.isLtr} ${bitstream.size}B")
        }
        decoder?.submit(bitstream, frame.keyframe, frame.timestampMs * 1000, frame.payloadType, ptsHostUs, macSendUs)
        if (frame.isLtr) sendLtrAck(frame.frameId)
    }

    private fun be64(b: ByteArray, o: Int): Long {
        var v = 0L; for (i in 0 until 8) v = (v shl 8) or (b[o + i].toLong() and 0xFF); return v
    }

    /** NTP 4-타임스탬프로 Mac↔Android 클럭오프셋 추정. 최소 왕복(delta)의 교환을 채택(큐잉오차↓),
     *  10초마다 재추적(드리프트). offset=androidµs-macµs → 디코더 계측에 주입. */
    private fun updateClockSync(t1: Long, t2: Long, t3: Long, t4: Long) {
        val delta = (t4 - t1) - (t3 - t2)          // 순수 왕복(Mac 처리시간 제외)
        val theta = ((t2 - t1) + (t3 - t4)) / 2    // Mac - Android
        val now = System.currentTimeMillis()
        if (now - ntpResetAtMs > 10_000) { bestNtpDeltaUs = Long.MAX_VALUE; ntpResetAtMs = now }
        if (delta in 0..bestNtpDeltaUs) {          // 더 낮은 왕복 = 더 정확한 오프셋
            bestNtpDeltaUs = delta
            clockOffsetUs = -theta
            clockSynced = true
            decoder?.let { it.clockOffsetUs = clockOffsetUs; it.clockSynced = true }
        }
    }

    /** packet_seq 갭으로 손실 감지 → 복구 요청 (재전송 아님, forward 복구). */
    private fun detectLoss(seq: Int) {
        if (lastPacketSeq >= 0) {
            val expected = (lastPacketSeq + 1) and 0xFFFF
            if (seq != expected) {
                val diff = (seq - expected) and 0xFFFF   // 16b 모듈러: forward 갭만
                if (diff in 1..1000) requestRefresh()
            }
        }
        lastPacketSeq = seq
    }

    /** 보고 윈도우 집계: base=구간 첫 seq, high=최신 seq, recv=수신 수. */
    private fun accountForFeedback(seq: Int) {
        if (fbBaseSeq < 0) fbBaseSeq = seq
        fbHighSeq = seq
        fbRecv++
    }

    private fun sendFeedback() {
        // AOA(신뢰 파이프): 손실 집계 없음 → queueDelay만 실어 Mac 포화 가드에 신호(불량 케이블 등
        // 파이프 포화 시 Mac이 비트레이트 백오프). base=high=0·recv=1 → Mac span=1·loss=0.
        // render-on-arrival이라 버퍼는 없지만 q(도착−PTS 초과분)는 계속 측정됨(측정 ≠ 버퍼링).
        if (bearer is AoaClient) {
            val q = decoder?.queueDelayMs ?: return
            Log.d(TAG, "feedback(aoa) qdelay=${q}ms")
            sendControlMessage(
                ControlMessage.newBuilder().setTransportFeedback(
                    TransportFeedback.newBuilder()
                        .setFeedbackSeq(feedbackSeq++)
                        .setBaseSeq(0).setHighestSeq(0).setReceivedCount(1)
                        .setQueueDelayMs(q)
                        .apply { fillPipeStats() }
                        .build()
                ).build()
            )
            return
        }
        if (fbBaseSeq < 0 || fbHighSeq < 0 || fbRecv == 0) return
        val span = (fbHighSeq - fbBaseSeq + 1).coerceAtLeast(1)
        Log.d(TAG, "feedback qdelay=${decoder?.queueDelayMs ?: 0}ms buf=${decoder?.bufferMs ?: 0}ms recv=$fbRecv/$span")
        sendControlMessage(
            ControlMessage.newBuilder().setTransportFeedback(
                TransportFeedback.newBuilder()
                    .setFeedbackSeq(feedbackSeq++)
                    .setBaseSeq(fbBaseSeq)
                    .setHighestSeq(fbHighSeq)
                    .setReceivedCount(fbRecv)
                    .setQueueDelayMs(decoder?.queueDelayMs ?: 0)  // 지연 인식형 비트레이트 제어 신호
                    .apply { fillPipeStats() }
                    .build()
            ).build()
        )
        fbBaseSeq = -1; fbHighSeq = -1; fbRecv = 0
    }

    /** 파이프라인 구간 통계(디코더 1초 평균)+RTT를 피드백에 탑재 — Mac 상세 화면 지연 스택용. */
    private fun TransportFeedback.Builder.fillPipeStats() {
        decoder?.let {
            pipeNetMs = it.pipeNetMs
            pipeJbufMs = it.pipeJbufMs
            pipeDecMs = it.pipeDecMs
        }
        rttMs = lastRttMs
    }

    /** 손실 시 refresh 요청 (Mac은 LTR-P로 응답). throttle 300ms. */
    private fun requestRefresh() {
        val now = System.currentTimeMillis()
        if (now - lastRefreshReqMs < 300) return
        lastRefreshReqMs = now
        sendControlMessage(
            ControlMessage.newBuilder()
                .setKeyframeRequest(KeyframeRequest.newBuilder().setLastGoodFrame(0).build())
                .build()
        )
    }

    private fun sendLtrAck(frameId: Long) {
        sendControlMessage(
            ControlMessage.newBuilder()
                .setLtrAck(LtrAck.newBuilder().setFrameId(frameId.toInt()).build())
                .build()
        )
    }

    private val sendLock = Any()

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
    //  곧바로 봉인돼 나간다(0-RTT). 세대마다 소금이 바뀌니 채널을 닫았다 다시 열어도
    //  예전 세대의 (키, nonce)와 겹칠 수 없다.
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Mac이 데이터그램 세대를 연다 → 키를 유도하고, 필요하면 후보를 모아 되돌려준다.
     *
     * `candidates`가 비어 있으면 **새 ICE 라운드가 없다**는 뜻이다 — fast UDP 모드에서
     * 접속할 때 이미 뚫어 둔 경로를 그대로 쓴다.
     */
    private fun onUdpOpen(open: UdpOpen) {
        val s = session ?: return
        val id = open.channelId.toByteArray()
        if (id.size != DATAGRAM_CHANNEL_ID_BYTES) {
            Log.e(TAG, "UdpOpen: 세대 id 길이가 ${id.size}B — 버린다")
            return
        }
        if (bearer !== client) {
            // 케이블로 붙어 있다 — 영상은 신뢰 파이프로 가므로 UDP를 열 이유가 없다.
            sendUdpAccept(id, ByteArray(0), "케이블(AOA)로 연결 중")
            return
        }
        // ★ 0-RTT: 키가 이 줄에서 나온다. UDP 위에서 합의할 것이 없다.
        datagramChannelId = id
        datagram = s.datagram(id)
        val remoteBytes = open.candidates.toByteArray()
        val hex = id.take(4).joinToString("") { "%02x".format(it) }

        if (remoteBytes.isEmpty()) {
            Log.i(TAG, "데이터그램 세대 $hex — 접속 때 뚫어 둔 경로 재사용")
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
        // STUN도 펀칭도 네트워크 왕복이라 제어 채널 스레드를 잡고 있으면 안 된다.
        Thread { openDatagramRound(id, remote) }.apply { isDaemon = true; start() }
    }

    /**
     * 이 세대의 UDP 경로를 실제로 세운다. 접속 라운드가 하던 일과 같되 **UDP만** 한다.
     *
     * 순서가 곧 정확성이다:
     *  1. 후보 수집(STUN)은 **수신 루프를 열기 전에** — 같은 소켓에서 응답을 직접 읽으므로
     *     루프가 먼저 돌면 그 응답을 삼켜 STUN이 타임아웃한다(실기기에서 9초를 잃었다).
     *  2. 응답을 보낸 **직후** 펀칭을 시작한다 — Mac도 그 응답을 받는 순간 시작하므로
     *     양쪽 시작 시각이 제어 채널 편도 지연만큼만 어긋난다.
     *  3. HELLO는 펀칭을 기다리지 않는다 — 같은 LAN이면 host 후보로 이미 닿고, 그 경우
     *     펀칭은 원리상 성립하지 않는다(10초를 기다렸다 보내면 그만큼 검은 화면이다).
     */
    private fun openDatagramRound(id: ByteArray, remote: CandidateBundle) {
        val sock = client.openMediaSocketOn(netWatcher.current)
        val servers = if (netWatcher.current == null) emptyList() else StunServer.DEFAULTS
        val mine = IceAgent.gather(
            sock, tcpPort = 0, servers = servers,
            deprioritized = netWatcher.deprioritizedAddresses(),
            transports = setOf(CandidateTransport.UDP),
        )
        // 세대가 그새 닫혔으면(앱이 곧바로 내려감) 여기서 멈춘다 — 소켓만 정리한다.
        if (!id.contentEquals(datagramChannelId)) {
            runCatching { sock.close() }
            return
        }
        client.adoptMediaSocket(sock)
        sendUdpAccept(id, CandidateBundle(remote.token, mine).serialized(), "")

        val ice = IceAgent()
        client.onUdpProbe = { data, from -> ice.handleInbound(data, from, sock) }
        client.startUdpReceive()

        // 펀칭이 확정하기 전에도 보낼 곳을 정해 둔다. 같은 LAN이면 이 host 주소로 이미 닿는다.
        client.udpTarget = pickUdpTarget(remote, null)
        sendUdpHello()

        val path = ice.punchUdp(sock, remote.ordered(CandidateTransport.UDP), remote.token)
        if (path != null && id.contentEquals(datagramChannelId)) {
            val before = client.udpTarget
            client.udpTarget = pickUdpTarget(remote, path.endpoint)
            Log.i(TAG, "영상 UDP 대상 확정 ${path.endpoint} (rtt=${path.rttMs}ms)")
            // Mac은 HELLO의 **출발지**로 영상 피어를 링크한다. 미리 잡아 둔 주소가 틀렸으면
            // 첫 HELLO는 그대로 유실되므로, 대상이 바뀌었으면 반드시 다시 보낸다.
            if (before != client.udpTarget) sendUdpHello()
        }
    }

    private fun sendUdpAccept(id: ByteArray, candidates: ByteArray, problem: String) {
        sendControlMessage(
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
        val id = close.channelId.toByteArray()
        if (!id.contentEquals(datagramChannelId)) {
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
        if (datagramChannelId == null) return
        Log.i(TAG, "데이터그램 세대 닫음 ($reason)")
        datagramChannelId = null
        datagram = null
        client.onUdpProbe = null
        client.releaseMediaSocket()
        reassembler.clear()   // 다음 세대의 조각과 섞이면 안 된다
    }

    /**
     * 영상 채널 개시/재등록 HELLO. `[1B 0][16B 세대 id][12B nonce][sealed(세대 id)]`.
     *
     * Mac은 평문 id로 세대를 찾고 봉인으로 검증한다. 주소가 달라져도 같은 세대로 붙으므로,
     * 핸드오버 뒤 **재등록**에도 그대로 쓴다 — 세대는 경로가 갈려도 살아남는다(세션 키가
     * 그대로이므로 키도 그대로다).
     */
    private fun sendUdpHello() {
        if (bearer !== client) return
        val keys = datagram ?: return
        val id = datagramChannelId ?: return
        val (nonce, body) = synchronized(sendLock) { keys.seal(id) }
        client.startVideo(WireFormat.udpHelloDatagram(id, nonce, body))
    }

    // ══════════════════════════════════════════════════════════════════════
    //  앱 수명 — Mac의 런처가 지시하고, 우리는 자기 쪽 절반을 켜고 끈다.
    //
    //  **여기엔 런처가 없다.** 아이콘은 Mac에 있고 배타 규칙(확장 ∧ 공유)도 거기서 막는다.
    //  이 기기가 하는 일은 "그 앱의 Android 절반"을 세우고 내리는 것뿐이다.
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 확장 앱이 켜져 있는가 — **디코더의 문**이다.
     *
     * 왜 필요한가: 세션이 붙었다고 화면을 받는 게 아니다. Mac이 확장을 안 띄웠으면 프레임은
     * 애초에 안 오지만, 그때 이 기기가 검은 전체화면을 띄운 채 기다리면 사용자는 무엇이
     * 잘못됐는지 알 수 없다. 이 값이 UI의 "지금 무엇을 하는 중인가"의 근거다.
     */
    var extendRunning by mutableStateOf(false)
        private set

    /** 화면 공유가 이 기기에서 도는 중인가 — UI 표시용. */
    var shareRunning by mutableStateOf(false)
        private set

    /** 알림 전달이 켜져 있는가 — UI가 권한 안내를 띄울지 정하는 근거. */
    var notifyRunning by mutableStateOf(false)
        private set

    private fun onAppControl(c: AppControl) {
        Log.i(TAG, "앱 지시: ${c.app} launch=${c.launch} ${c.reason}")
        when (c.app) {
            AppId.APP_ID_EXTEND -> {
                if (c.launch) {
                    noteExtendRunning(true)
                    ensureDecoder()
                    reportApp(AppId.APP_ID_EXTEND, true, "")
                } else {
                    noteExtendRunning(false)
                    // 디코더를 내린다 — 다시 띄우면 키프레임부터 새로 받는다.
                    decoder?.stop(); decoder = null
                    reassembler.clear()
                    reportApp(AppId.APP_ID_EXTEND, false, c.reason)
                }
            }
            AppId.APP_ID_SHARE -> {
                // 공유는 `ShareRequest`가 실제 방아쇠다(동의를 물어야 하므로). 여기서는
                // **내리라는 지시만** 즉시 따른다 — 켜는 쪽은 사람 확인을 건너뛸 수 없다.
                if (!c.launch) ScreenShare.stop(c.reason.ifEmpty { "Mac에서 종료" })
            }
            AppId.APP_ID_NOTIFY -> {
                NotifyBridge.wanted = c.launch
                scope.launch(Dispatchers.Main) { notifyRunning = c.launch }
                if (c.launch) {
                    // 권한이 없으면 알림은 한 건도 안 온다. 그걸 Mac에 **사실대로** 알린다 —
                    // 안 그러면 "켰는데 아무것도 안 온다"가 되고 원인이 어디에도 안 보인다.
                    val granted = NotifyAccess.isGranted(appContext)
                    reportApp(AppId.APP_ID_NOTIFY, granted,
                              if (granted) "" else "기기에서 알림 접근을 허용해야 합니다")
                    // ★ 여기서 동기화를 **걸지 않는다.** Mac이 앱을 켜면서 `NotificationSync`를
                    // 따로 보내는데, 여기서도 밀면 켤 때마다 목록이 **두 번** 간다(실기기 로그에
                    // 같은 패키지가 두 줄씩 찍혔다). 알림 하나에 아이콘 셋이 실리므로 그냥
                    // 낭비가 아니라 초기 폭이 두 배가 되는 일이다.
                    //
                    // 리스너가 **늦게** 붙는 경우는 여기가 아니라 `onListenerConnected`가 맡는다.
                    if (!granted) Log.w(TAG, "알림 접근 미허용 — 설정에서 켜야 한다")
                } else {
                    reportApp(AppId.APP_ID_NOTIFY, false, c.reason)
                }
            }
            else -> Log.i(TAG, "모르는 앱 지시 무시: ${c.app}")
        }
    }

    private fun reportApp(app: AppId, running: Boolean, detail: String) {
        sendControlMessage(
            ControlMessage.newBuilder().setAppState(
                AppState.newBuilder().setApp(app).setRunning(running).setDetail(detail).build()
            ).build()
        )
    }

    // ── 화면 공유 (앱 #2, 송신) ────────────────────────────────────────────

    private fun onShareRequest(req: ShareRequest) {
        when (req.action) {
            ShareRequest.Action.ACTION_START -> {
                Log.i(TAG, "Mac이 화면 공유를 요청")
                ScreenShare.requestConsent()
            }
            ShareRequest.Action.ACTION_STOP -> ScreenShare.stop("Mac에서 중지")
            else -> {}
        }
    }

    /**
     * 공유 영상의 프레임 번호. **재사용 금지** — nonce가 이 값에서 나온다.
     *
     * 24비트를 넘지 않는다. 와이어의 `VideoHeader.seq`가 24비트인데 수신측은 **그 값으로**
     * nonce를 만들기 때문에, 봉인에 쓴 값이 그보다 넓으면 24비트를 넘는 순간 복호가 통째로
     * 실패한다. 그렇다고 잘라서 봉인하면 같은 키에 같은 nonce가 두 번 나오고 — 그건 복호
     * 실패보다 나쁘다(GCM은 nonce 재사용에 무너진다).
     *
     * 그래서 넘기 전에 **공유를 멈춘다.** 30fps로 155시간이라 실제로 닿을 일은 없고,
     * 닿는다면 그건 조용히 깨질 자리가 아니라 멈춰야 할 자리다.
     */
    private var shareFrameId = 0L
    /** 공유 UDP 패킷의 전역 순번(16비트 wrap). */
    private var sharePacketSeq = 0
    /**
     * 첫 프레임의 PTS. 이 값을 빼서 스트림이 0에서 시작하게 한다.
     *
     * 인코더가 주는 PTS는 부팅 이후 시각이라 값이 크고, 32비트 필드에 담으면 기기가
     * 49일쯤 켜져 있었을 때 넘친다. 0 기준으로 옮기면 그 경계가 아예 사라진다.
     */
    private var sharePtsBaseMs = -1L

    /**
     * 인코더가 만든 AU 하나를 Mac으로 보낸다. **인코더 스레드에서** 불린다.
     *
     * 확장 경로가 반대 방향으로 하는 일과 정확히 같다: 봉인하고, 전송에 맞는 모양으로 나눈다.
     * 조각화 여부를 파이프가 정한다 — AOA는 MTU가 없으니 통짜, LAN은 Mini-RTP로 쪼갠다.
     */
    private fun sendShareFrame(bitstream: ByteArray, keyframe: Boolean, ptsMs: Long) {
        // 최신성 레인의 키는 경로마다 다르다: 케이블은 세션 키(세대가 없다), LAN은 세대 키.
        val sealer: (ByteArray, Long) -> ByteArray = if (bearer === aoa) {
            val s = session ?: return
            { p, i -> s.sealVideo(p, i) }
        } else {
            val k = datagram ?: return   // 세대가 없으면 보낼 UDP 경로 자체가 없다
            { p, i -> k.sealVideo(p, i) }
        }
        val (id, pts) = synchronized(shareLock) {
            if (sharePtsBaseMs < 0) sharePtsBaseMs = ptsMs
            shareFrameId++ to (ptsMs - sharePtsBaseMs)
        }
        if (id > SHARE_FRAME_LIMIT) {
            ScreenShare.stop("공유가 너무 오래 이어졌습니다 — 다시 시작해 주세요")
            return
        }
        // **봉인에 쓴 값이 곧 헤더에 실리는 값이다.** 둘이 갈리면 수신측이 만든 nonce가
        // 달라져 프레임을 통째로 못 푼다 — 그래서 자르는 일이 여기 없다(위 한도가 그 역할).
        val body = runCatching { sealer(bitstream, id) }.getOrElse {
            Log.e(TAG, "공유 프레임 봉인 실패: ${it.message}")
            return
        }
        val hevc = ScreenShare.isHevcNow
        if (bearer === aoa) {
            // 신뢰 파이프 — 조각화도 페이싱도 없다.
            val header = VideoHeader(
                keyframe = keyframe, codec = if (hevc) 1 else 0,
                seq = id.toInt(), timestampMs = pts,
                fragIndex = 0, fragTotal = 1,
            )
            bearer.sendVideo(WireFormat.videoFrame(header, body))
        } else {
            val packets = WireFormat.packetizeVideo(
                frameId = id, keyframe = keyframe, payloadType = if (hevc) 1 else 0,
                timestampMs = pts, body = body,
                nextPacketSeq = {
                    synchronized(shareLock) { sharePacketSeq = (sharePacketSeq + 1) and 0xFFFF; sharePacketSeq }
                },
            )
            for (p in packets) bearer.sendVideo(p)
        }
    }

    private val shareLock = Any()

    /** [ScreenShare]가 바깥으로 내는 사실들을 이 세션에 잇는다. */
    private fun wireScreenShare() {
        ScreenShare.attach(appContext)
        ScreenShare.onFrame = { au, key, pts -> sendShareFrame(au, key, pts) }
        ScreenShare.onGranted = {
            // PTS만 다시 잡는다. **프레임 번호는 건드리지 않는다** — 그건 세션 키에 매인
            // nonce의 근거라, 공유를 껐다 켤 때마다 0으로 되돌리면 같은 키에 같은 nonce를
            // 두 번 쓰게 된다.
            synchronized(shareLock) { sharePtsBaseMs = -1L }
            noteShareRunning(true)
            sendShareStatus(ShareStatus.Phase.PHASE_GRANTED, "")
            reportApp(AppId.APP_ID_SHARE, true, "")
        }
        ScreenShare.onDenied = { why ->
            noteShareRunning(false)
            sendShareStatus(ShareStatus.Phase.PHASE_DENIED, why)
            reportApp(AppId.APP_ID_SHARE, false, why)
        }
        ScreenShare.onStopped = { why ->
            noteShareRunning(false)
            sendShareStatus(ShareStatus.Phase.PHASE_STOPPED, why)
            reportApp(AppId.APP_ID_SHARE, false, why)
        }
        ScreenShare.onParams = { w, h, fps, hevc, kbps, physW, physH ->
            Log.i(TAG, "공유 제원 통보 ${w}x${h}@$fps ${if (hevc) "H265" else "H264"} " +
                       "실물 ${physW / 1000}x${physH / 1000}mm")
            sendControlMessage(
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

    // ── 알림 (앱 #4, 송신) ─────────────────────────────────────────────────

    private fun onNotificationAction(a: NotificationAction) {
        val l = NotifyBridge.listener ?: run {
            Log.w(TAG, "알림 리스너 미연결 — 지시 무시 (${a.kind})")
            return
        }
        when (a.kind) {
            NotificationAction.Kind.KIND_OPEN -> l.open(a.key)
            NotificationAction.Kind.KIND_DISMISS -> l.dismiss(a.key)
            // Mac이 고른 액션 번호는 +1 되어 있다(0 = 안 골랐다). 리스너가 확인하고 쓴다.
            NotificationAction.Kind.KIND_REPLY -> l.reply(a.key, a.actionIndex, a.text)
            else -> {}
        }
    }

    /**
     * 리스너가 준 알림을 Mac으로 — **옮겨 적기만 한다.** **리스너 스레드에서** 불린다.
     *
     * 여기에 판단이 없어야 [PeriNotificationListener]의 받아쓰기가 뜻을 갖는다. 값을 고르거나
     * 비었다고 건너뛰기 시작하면 필터가 이 자리로 옮겨 온 것일 뿐이다.
     */
    private fun sendNotification(s: NotifySnapshot) {
        sendControlMessage(
            ControlMessage.newBuilder().setNotificationPosted(
                NotificationPosted.newBuilder()
                    .setKey(s.key)
                    .setPackageName(s.packageName)
                    .setAppLabel(s.appLabel)
                    .setPostedAtMillis(s.postedAtMillis)
                    .setWhenMillis(s.whenMillis)
                    .setOngoing(s.ongoing)
                    .setClearable(s.clearable)
                    .setGroupSummary(s.groupSummary)
                    .setFromPeri(s.fromPeri)
                    .setIsConversation(s.isConversation)
                    .setImportance(s.importance)
                    .setCategory(s.category)
                    .setChannelId(s.channelId)
                    .setGroup(s.group)
                    .setSortKey(s.sortKey)
                    .setColor(s.color)
                    .setText(
                        NotificationText.newBuilder()
                            .setTitle(s.text.title)
                            .setTitleBig(s.text.titleBig)
                            .setText(s.text.text)
                            .setBigText(s.text.bigText)
                            .setSubText(s.text.subText)
                            .setSummaryText(s.text.summaryText)
                            .setInfoText(s.text.infoText)
                    )
                    .setStyle(styleOf(s))
                    .setImages(
                        NotificationImages.newBuilder()
                            .setAppIconPng(ByteString.copyFrom(s.images.appIconPng))
                            .setSmallIconPng(ByteString.copyFrom(s.images.smallIconPng))
                            .setLargeIconPng(ByteString.copyFrom(s.images.largeIconPng))
                    )
                    .addAllActions(s.actions.map {
                        NotificationActionInfo.newBuilder()
                            .setIndex(it.index)
                            .setTitle(it.title)
                            .setHasRemoteInput(it.hasRemoteInput)
                            .setRemoteInputLabel(it.remoteInputLabel)
                            .setSemanticAction(it.semanticAction)
                            .setContextual(it.contextual)
                            .build()
                    })
                    .build()
            ).build()
        )
    }

    private fun styleOf(s: NotifySnapshot): NotificationStyle.Builder {
        val b = NotificationStyle.newBuilder()
            .setKind(when (s.style.kind) {
                NotifyStyleKind.NONE -> NotificationStyle.Kind.KIND_UNSPECIFIED
                NotifyStyleKind.BIG_TEXT -> NotificationStyle.Kind.KIND_BIG_TEXT
                NotifyStyleKind.BIG_PICTURE -> NotificationStyle.Kind.KIND_BIG_PICTURE
                NotifyStyleKind.INBOX -> NotificationStyle.Kind.KIND_INBOX
                NotifyStyleKind.MESSAGING -> NotificationStyle.Kind.KIND_MESSAGING
                NotifyStyleKind.MEDIA -> NotificationStyle.Kind.KIND_MEDIA
                NotifyStyleKind.CALL -> NotificationStyle.Kind.KIND_CALL
                NotifyStyleKind.DECORATED -> NotificationStyle.Kind.KIND_DECORATED
                NotifyStyleKind.OTHER -> NotificationStyle.Kind.KIND_OTHER
            })
            .setTemplateClass(s.style.templateClass)

        s.style.messaging?.let { m ->
            b.setMessaging(
                MessagingStyle.newBuilder()
                    .setConversationTitle(m.conversationTitle)
                    .setGroupConversation(m.groupConversation)
                    .setUserName(m.userName)
                    .addAllMessages(m.messages.map(::messageOf))
                    .addAllHistoric(m.historic.map(::messageOf))
                    .addAllPeople(m.people.map(::personOf))
            )
        }
        if (s.style.inboxLines.isNotEmpty()) {
            b.setInbox(InboxStyle.newBuilder().addAllLines(s.style.inboxLines))
        }
        return b
    }

    private fun messageOf(m: NotifyMessage): MessagingMessage =
        MessagingMessage.newBuilder()
            .setText(m.text)
            .setTimestampMillis(m.timestampMillis)
            .setSender(m.sender)
            .setSenderKey(m.senderKey)
            .setFromMe(m.fromMe)
            .setDataMimeType(m.dataMimeType)
            .setDataUri(m.dataUri)
            .build()

    private fun personOf(p: NotifyPerson): NotificationPerson =
        NotificationPerson.newBuilder()
            .setName(p.name)
            .setKey(p.key)
            .setBot(p.bot)
            .setImportant(p.important)
            .setIconPng(ByteString.copyFrom(p.iconPng))
            .build()

    private fun wireNotifications() {
        NotifyBridge.onPosted = { s -> sendNotification(s) }
        NotifyBridge.onRemoved = { key ->
            sendControlMessage(
                ControlMessage.newBuilder().setNotificationRemoved(
                    NotificationRemoved.newBuilder().setKey(key).build()
                ).build()
            )
        }
        NotifyBridge.onActionFailed = { key, reason, detail ->
            sendControlMessage(
                ControlMessage.newBuilder().setNotificationActionFailed(
                    NotificationActionFailed.newBuilder()
                        .setKey(key)
                        .setReason(when (reason) {
                            FailReason.LOCKED -> NotificationActionFailed.Reason.REASON_LOCKED
                            FailReason.NOT_PERMITTED ->
                                NotificationActionFailed.Reason.REASON_NOT_PERMITTED
                            FailReason.GONE -> NotificationActionFailed.Reason.REASON_GONE
                        })
                        .setDetail(detail)
                        .build()
                ).build()
            )
        }
    }

    private fun sendShareStatus(phase: ShareStatus.Phase, reason: String) {
        sendControlMessage(
            ControlMessage.newBuilder().setShareState(
                ShareStatus.newBuilder().setPhase(phase).setReason(reason).build()
            ).build()
        )
    }

    private fun sendControlMessage(msg: ControlMessage) {
        val s = session ?: return
        // 채널0 seal은 카운터 nonce → 여러 스레드(keepalive/영상/회전)에서 호출되므로 직렬화.
        val frame = synchronized(sendLock) {
            val (nonce, body) = s.seal(msg.toByteArray())
            WireFormat.controlFrame(nonce, body)
        }
        bearer.sendControl(frame)
    }

    /**
     * 디코더가 없으면 세운다. Surface가 아직이면 표시만 해 두고, 붙는 순간 [setSurface]가 잇는다.
     */
    private fun ensureDecoder() {
        if (decoder != null) return
        val s = surface
        if (s != null) startDecoder(s) else pendingConnect = true
    }

    /**
     * 연결 직전에 디코더를 **새로** 세운다 — 확장 앱이 떠 있을 때만.
     *
     * 콜드 연결에서는 이 시점에 확장이 떠 있지 않아 아무 일도 안 한다(세션이 서고 Mac이
     * `AppControl`을 보내야 뜬다). 무손실 재개·전송 전환처럼 앱이 살아남은 경로에서만
     * 실제로 새 디코더가 선다 — 그 경로는 화면이 끊기면 안 되는 자리다.
     */
    private fun prepareDecoder() {
        if (!extendRunning) { pendingConnect = false; return }
        val s = surface
        if (s != null) startDecoder(s) else pendingConnect = true
    }

    private fun startDecoder(surface: Surface) {
        decoder?.stop()
        decoder = VideoDecoder(surface).also {   // 최신 버퍼값 + 클럭오프셋 적용
            it.bufferMs = playoutBufferMs
            // Mac 프로파일 우선(PlayoutConfig 도착 후), 없으면 bearer 기본(AOA=즉시 렌더).
            it.renderOnArrival = renderOnArrivalPref ?: (bearer is AoaClient)
            it.clockOffsetUs = clockOffsetUs; it.clockSynced = clockSynced
            it.applyDynamicRange(sessionHdr10, sessionHdrStaticInfo)  // SessionParams가 먼저 왔을 수 있다
            it.start()
        }
    }
}
