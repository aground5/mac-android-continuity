package com.peri.display.transport

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

private const val TAG = "PathNegotiator"

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
 * **경로를 뚫는 일 전부.** 후보 수집(STUN) · 라운드 소켓 소유 · 제어 채널 지명 · 펀칭.
 *
 * 세션 오케스트레이터에서 갈라낸 이유는 두 가지다.
 *
 * **첫째, 소켓의 소유권이 여기 있다.** 라운드마다 새 UDP 소켓과 새 TCP 포트를 잡고, 그중
 * 하나가 승자가 되면 [LanClient]로 소유권이 넘어가고 나머지는 닫힌다. 이 규칙이 오케스트
 * 레이터의 필드 넷(`pendingUdp`·`pendingTcpPort`·`pendingNetwork`·`ice`)으로 흩어져 있으면
 * "누가 이 소켓을 닫는가"가 호출 경로마다 달라진다 — 실제로 그래서 FD가 새고 두 스레드가
 * 같은 소켓에서 패킷을 나눠 먹는 일이 있었다.
 *
 * **둘째, 세 흐름이 같은 뼈대를 공유한다.** 콜드 접속 · 무손실 교체 · 세대 열기는 전부
 * "후보를 모으고 → 동시에 두드리고 → 이긴 경로를 채택한다"이고, 다른 건 무엇을 채택하느냐
 * (새 세션 / 기존 세션 / UDP만)뿐이다. 한 파일에 두면 그 공통점이 보이고, 흩어 두면 세
 * 곳에 같은 버그를 세 번 고치게 된다(`pickUdpTarget`이 콜드와 재개에서 정반대였던 것이
 * 그 사례다).
 *
 * **여기에 판단은 없다.** 언제 어느 흐름을 탈지는 `MirrorMachine`이 정하고, 결과는
 * `done` 콜백 하나로 되돌아간다.
 */
class PathNegotiator(
    private val client: LanClient,
    private val netWatcher: NetworkWatcher,
    /** 오케스트레이터의 수명주기 로그로 흘려보낸다 — 라운드 번호가 붙어야 겹친 라운드가 구분된다. */
    private val log: (String) -> Unit,
) {

    /** 콜드 접속 라운드의 ICE 에이전트 — 미디어 소켓에 앉아 프로브를 받는다. */
    private val ice = IceAgent()

    // MARK: - 라운드 소켓
    //
    // 한 라운드가 쓰는 자원 셋이 **함께** 살고 **함께** 죽는다. 따로 두면 하나만 남아
    // 다음 라운드의 소켓과 섞인다.

    /** 이 라운드에서 새로 연 미디어 소켓 — 경로가 확정되면 [LanClient]로 넘어간다. */
    private var pendingUdp: DatagramSocket? = null
    private var pendingTcpPort = 0

    /**
     * 이 라운드의 후보를 모은 망. 펀칭 직전에 지금 망과 대조한다 — 다르면 후보가 이미 낡았다.
     *
     * BLE 라운드가 도는 도중에 망이 바뀔 수 있다(실기기 로그: 후보 수집 시작 2초 뒤 전환).
     * 그대로 진행하면 죽은 망의 매핑으로 10초를 두드리다 실패한다.
     */
    private var pendingNetwork: android.net.Network? = null

    @Synchronized
    fun releaseRound() {
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
    fun candidatesStillValid(): Boolean {
        val now = netWatcher.current
        // null끼리도 "같다"로 본다 — 기본망 없는 상태(핫스팟 AP 등)는 그 자체로 유효한
        // 상황이고, host 후보는 여전히 쓸 수 있다. 어긋난 경우만 걸러낸다.
        if (pendingNetwork == now) return true
        Log.w(TAG, "후보가 낡음 — 수집 망 $pendingNetwork ≠ 현재 $now")
        return false
    }

    // MARK: - 후보 수집

    /**
     * 내 후보를 모은다. 토큰은 Mac이 준 값을 그대로 되돌린다(세션 공유).
     *
     * ★ **기본은 TCP 후보뿐이다.** 세션 수립에 UDP가 필요 없다 — 데이터그램 경로는 영상을
     * 쓰는 앱이 뜰 때 Mac이 제어 채널로 따로 연다(`UdpOpen`). Mac이 `f fastudp`를 켜서
     * 보냈을 때만 여기서도 UDP를 함께 모은다. STUN 왕복이 전송마다 따로 일어나므로 이
     * 구분이 곧 접속 지연이다.
     *
     * **항상 지금 기본망에 바인드한 새 소켓에서 모은다.** 예전엔 첫 연결이면 기존
     * `mediaSocket()`을, 재협상이면 새 소켓을 쓰는 두 갈래였는데 둘 다 문제가 있었다:
     *
     *  - 기존 소켓은 **만들어질 당시의 망에 묶여 있다.** 그 망이 사라지면 STUN이
     *    `ENETUNREACH`로 죽고 UDP 후보가 통째로 비어 영상 경로가 영영 안 열린다.
     *  - 기존 소켓에는 **영상 수신 루프가 앉아 있다.** STUN이 같은 소켓에서 응답을 직접
     *    읽으므로 두 리더가 경합해 서로의 패킷을 훔친다.
     *
     * 새 소켓에서 모으면 둘 다 사라진다. 매핑이 유효해야 한다는 원칙은 그대로 지켜진다 —
     * 이 소켓이 곧 미디어 소켓이 되기 때문이다([coldPunch]가 채택하거나 [seamlessPunch]가
     * 인계). 세션이 살아 있으면 옛 소켓은 그대로 둬 영상이 계속 흐른다(make-before-break).
     *
     * BLE 콜백이 아닌 백그라운드 스레드에서 호출된다(STUN은 네트워크 왕복).
     */
    fun gather(remote: CandidateBundle): CandidateBundle {
        // TCP 후보 포트도 **시도마다 새로 잡는다.** 재사용하면 이전 세션의 펀칭 연결이
        // TIME_WAIT로 그 포트를 물고 있어 TCP STUN·펀칭이 EADDRINUSE로 죽는다.
        val tcpPort = ServerSocket(0).use { it.localPort }
        releaseRound()
        pendingTcpPort = tcpPort
        pendingNetwork = netWatcher.current
        val transports = if (remote.fastUdp) {
            setOf(CandidateTransport.TCP, CandidateTransport.UDP)
        } else {
            setOf(CandidateTransport.TCP)
        }
        val sock = client.openMediaSocketOn(pendingNetwork).also { pendingUdp = it }
        // 셀룰러 주소는 후보 목록 뒤로 민다 — 상대는 앞에서부터 두드린다.
        val demote = netWatcher.deprioritizedAddresses()
        log("후보 수집 (망=${netWatcher.transportName}, ${transports.joinToString("+") { it.wire }}"
            + (if (demote.isEmpty()) ")" else ", 셀룰러 주소 ${demote.size}개 후순위)"))
        val list = IceAgent.gather(sock, tcpPort, stunServers(), demote, transports)
        // 우리 묶음에도 같은 플래그를 실어 되돌린다 — 그래야 로그 한 줄만 보고도 양쪽이
        // 같은 모드로 돌았는지 알 수 있다.
        return CandidateBundle(remote.token, list, fastUdp = remote.fastUdp)
    }

    /**
     * ★ 기본망이 없으면 STUN을 건너뛴다.
     *
     * 기본망이 없다는 건 **인터넷 경로가 없다**는 뜻이지 연결할 수 없다는 뜻이 아니다 —
     * 폰이 핫스팟 AP이고 Mac이 거기 붙어 있으면 업스트림 없이도 host 후보로 곧장 뚫린다.
     * 그때 공개 STUN을 두드려 봐야 서버마다 타임아웃만 쌓이고 얻는 게 없다.
     */
    private fun stunServers(): List<StunServer> {
        if (netWatcher.current != null) return StunServer.DEFAULTS
        Log.i(TAG, "기본망 없음 — STUN 생략, host 후보로만 시도 (핫스팟 등 로컬 링크)")
        return emptyList()
    }

    /**
     * 영상 UDP 대상 선택 — **콜드·재개·세대 공통 규칙**.
     *
     *  1. 펀칭이 왕복을 검증한 주소가 최우선. 실제로 뚫린 구멍이다.
     *  2. 없으면 **host**. 펀칭이 아직/영영 성립하지 않았다는 건 srflx로는 구멍이 없다는
     *     뜻이라, 그리로 보내면 확실히 버려진다. 반면 host는 같은 LAN이면 구멍 없이도 닿는다.
     *  3. 그래도 없으면 남은 첫 후보.
     *
     * 예전엔 콜드가 `SRFLX ?: first`, 재개가 `HOST ?: first`로 **정반대**였다. 콜드 쪽이
     * 틀렸다 — 핸드셰이크가 펀칭보다 먼저 끝나면 HELLO가 공인 주소로 나가 영영 안 닿았다.
     * 규칙이 한 곳에 있어야 그런 갈림이 다시 안 생긴다.
     */
    fun pickUdpTarget(remote: CandidateBundle, punched: Ipv4Endpoint?): Ipv4Endpoint? {
        val cands = remote.ordered(CandidateTransport.UDP)
        return punched
            ?: cands.firstOrNull { it.kind == CandidateKind.HOST }?.endpoint
            ?: cands.firstOrNull()?.endpoint
    }

    // MARK: - 제어 채널 지명

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
     * 진행 중일 때만 그만큼 기다린다.
     *
     * **펀칭 타이밍은 손대지 않는다.** TCP simultaneous open은 양쪽이 거의 동시에 두드려야
     * 성립하므로 지연이나 순서 변경은 위험하다. host 시도를 옆에 하나 더 붙일 뿐이다.
     * host 연결은 **커널 배정 포트**에서 나간다 — 펀치 포트를 같이 쓰면 서로 충돌한다.
     */
    private fun openControlChannel(agent: IceAgent, remote: CandidateBundle, punchPort: Int): Socket? {
        val tcpCands = remote.ordered(CandidateTransport.TCP)
        val hostCand = tcpCands.firstOrNull { it.kind == CandidateKind.HOST }?.endpoint

        val lock = ReentrantLock()
        val changed = lock.newCondition()
        var host: Socket? = null      // 1순위 — 로컬 링크
        var punched: Socket? = null   // 2순위 — NAT 통과(셀룰러면 CGNAT)
        var hostPending = hostCand != null     // host 시도가 아직 결과를 안 냈다
        var punchPending = true
        var committed = false                  // 승자 확정 — 이후 도착분은 즉시 닫는다

        fun offer(isHost: Boolean, s: Socket?) {
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
                    Socket().apply {
                        tcpNoDelay = true
                        connect(InetSocketAddress(hostCand.address, hostCand.port), 3000)
                    }
                }.getOrNull()
                if (s != null) Log.i(TAG, "host 직결 성공 $hostCand")
                offer(true, s)
            }.apply { isDaemon = true; start() }
        }

        // 둘 다 실패하면 펀칭 타임아웃(10s)까지 기다렸다가 포기한다.
        val deadline = System.currentTimeMillis() + 12_000
        var graceUntil = Long.MAX_VALUE   // 펀칭이 먼저 이겼을 때만 좁혀진다
        val chosen: Socket?
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

    // MARK: - 흐름 ①: 콜드 접속

    /**
     * 새 세션을 전제로 경로를 연다. **후보를 모은 그 소켓/포트로 갈아탄 뒤에** 뚫는다.
     *
     * `gather`는 지금 기본망에 바인드한 새 소켓에서 STUN을 쐈으므로, 상대에게 알린 매핑은
     * 그 소켓의 것이다. 옛 소켓으로 펀칭하면 출발지가 달라 구멍이 무효가 된다(증상 없이 실패).
     *
     * @param prepareDecoder 제어 채널을 기다리는 동안 화면 쪽을 미리 세운다(메인 스레드 몫).
     * @param epochOpen      지금 데이터그램 세대가 열려 있는가 — HELLO를 다시 보낼지의 근거.
     * @param sendHello      영상 경로를 (재)등록한다.
     * @param done           라운드 종료. 성공·실패 공통 출구.
     */
    fun coldPunch(
        remote: CandidateBundle,
        prepareDecoder: () -> Unit,
        epochOpen: () -> Boolean,
        sendHello: () -> Unit,
        done: (ok: Boolean, note: String) -> Unit,
    ) {
        // ★ UDP 후보가 없으면 이 라운드에 UDP는 없다 — 기본 모드에서는 그게 정상이다.
        // 데이터그램 경로는 영상 앱이 뜰 때 Mac이 `UdpOpen`으로 따로 연다.
        val wantUdp = remote.ordered(CandidateTransport.UDP).isNotEmpty()
        // 영상 UDP 대상을 **미리** 잡아 둔다. 핸드셰이크가 UDP 펀칭보다 먼저 끝나면 HELLO가
        // udpTarget=null인 채로 나가 TCP 주소로 잘못 가기 때문이다. 펀칭이 확정되면 덮어쓴다.
        if (wantUdp) client.udpTarget = pickUdpTarget(remote, null)

        Thread {
            // ★ 소켓 조작은 **메인이 아닌 여기서** 한다. 소켓 close/교체는 다른 스레드의
            // 블로킹 `receive()`와 얽힐 수 있는데, 메인이 거기 걸리면 앱 전체가 멎는다(ANR).
            pendingUdp?.let { client.adoptMediaSocket(it); pendingUdp = null }
            if (pendingTcpPort != 0) { client.reserveTcpPort(pendingTcpPort); pendingTcpPort = 0 }

            val sock = client.mediaSocket()

            // UDP 펀칭은 영상 경로를 여는 것 — 결과와 무관하게 TCP 제어가 붙어야 세션이 산다.
            if (wantUdp) {
                client.onUdpProbe = { data, len, from -> ice.handleInbound(data, len, from, sock) }
                // 후보 수집(STUN)은 끝났으니 이제 수신 루프를 연다 — 프로브를 받으려면 필수.
                client.startUdpReceive()
                Thread {
                    val path = ice.punchUdp(sock, remote.ordered(CandidateTransport.UDP), remote.token)
                        ?: return@Thread
                    // ★ 영상은 **이 주소로** 보낸다. TCP 제어 채널과 포트가 다르다 —
                    // TCP 주소로 HELLO를 보내면 Mac의 UDP 소켓에 안 닿아 게이트가 안 열린다.
                    val before = client.udpTarget
                    client.udpTarget = pickUdpTarget(remote, path.endpoint)
                    Log.i(TAG, "영상 UDP 대상 확정 ${path.endpoint} (rtt=${path.rttMs}ms)")
                    // Mac은 HELLO의 **출발지**로 영상 피어를 링크한다. 미리 잡아 둔 주소가
                    // 틀렸으면 첫 HELLO는 그대로 유실된다(영상 없는 검은 화면).
                    if (before != client.udpTarget && epochOpen()) {
                        Log.i(TAG, "UDP 대상 변경 $before → ${client.udpTarget} — HELLO 재전송")
                        sendHello()
                    }
                }.start()
            }

            // 펀칭과 host 직결을 **병렬로** — 같은 LAN이면 host가 ~10ms에 이기고,
            // 망이 다르면 펀칭이 이긴다. 순차로 두면 같은 LAN에서 매번 10초를 버린다.
            val opened = openControlChannel(ice, remote, client.tcpLocalPort)
            prepareDecoder()
            if (opened != null) {
                Log.i(TAG, "제어 채널 확보 ${opened.inetAddress}:${opened.port}")
                // 펀칭으로 붙은 주소는 재접속에 쓸 수 없다(NAT 매핑은 그 순간의 것). host
                // 직결이면 유효하지만 구분이 어려우므로, 복구는 재협상 한 갈래로 통일한다.
                client.adoptPunchedSocket(opened)
                done(true, "콜드 재연결 성공")
            } else {
                Log.e(TAG, "펀칭·host 모두 실패 — 연결 불가")
                done(false, "제어 채널 확보 실패")
            }
        }.start()
    }

    // MARK: - 흐름 ②: 무손실 교체 (make-before-break)

    /**
     * 새 망에서 경로를 뚫고 **세션을 유지한 채** 전송만 갈아끼운다.
     *
     * 핵심은 순서다. 새 TCP가 뚫리면 그 소켓으로 먼저 RESUME 프레임을 보낸다 — Mac은 그걸
     * 시도복호해서 "이 연결은 살아 있는 그 세션의 것"임을 확인하고 제어 채널만 교체한다.
     * 핸드셰이크가 없으니 새 가상 디스플레이도, 키프레임 대기도, 검은 화면도 없다.
     * 그 다음 UDP 대상까지 새 주소로 옮기고 HELLO를 한 발 보내면 Mac이 영상 피어를 재링크한다.
     *
     * @param movingDatagram 지금 데이터그램 세대가 열려 있고 상대도 UDP 후보를 실어 보냈는가.
     *                       세대가 없으면 UDP를 뚫어 봐야 아무도 안 쓰는 구멍을 새 망에 다시
     *                       여는 일이다 — Mac도 그 경우 후보에 UDP를 안 싣는다.
     * @param resumeFrame    세션 키로 봉인한 RESUME 프레임. null이면 세션이 사라진 것이다.
     * @param afterSwap      교체 직후 — 영상 경로 재등록과 상태 기계 통보.
     */
    fun seamlessPunch(
        remote: CandidateBundle,
        movingDatagram: Boolean,
        resumeFrame: (token: ByteArray) -> ByteArray?,
        afterSwap: () -> Unit,
        done: (ok: Boolean, note: String) -> Unit,
    ) {
        val udpSock = pendingUdp
        val tcpPort = pendingTcpPort
        if (udpSock == null || tcpPort == 0) {
            done(false, "준비된 소켓이 없다"); return
        }

        val agent = IceAgent()
        // 새 소켓 전용 프로브 수신 루프 — 옛 소켓의 루프는 그대로 둔다(영상이 아직 그리로 온다).
        //
        // ★ 중단은 플래그 + 짧은 soTimeout으로만 된다. `interrupt()`도 `Job.cancel()`도
        // 블로킹 `DatagramSocket.receive()`를 깨우지 못해서, 안 그러면 이 스레드가 소켓에
        // 그대로 앉아 교체 뒤에 올라오는 영상 수신 루프와 패킷을 나눠 먹는다.
        val stopProbe = AtomicBoolean(false)
        val probeReader = Thread {
            val buf = ByteArray(2048)
            runCatching { udpSock.soTimeout = 100 }   // 중단 확인 주기
            while (!stopProbe.get() && !udpSock.isClosed) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    udpSock.receive(pkt)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (_: Throwable) {
                    return@Thread   // 소켓이 닫혔다 = 라운드 종료
                }
                val from = Ipv4Endpoint(pkt.address?.hostAddress ?: "", pkt.port)
                agent.handleInbound(pkt.data, pkt.length, from, udpSock)
            }
        }.apply { isDaemon = true; start() }

        /** 프로브 리더를 확실히 내린다 — 소켓을 넘겨주기 전에 반드시. */
        fun stopProbeReader() {
            stopProbe.set(true)
            probeReader.join(500)
        }

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
                done(false, "새 망에서 제어 채널 확보 실패")
                return@Thread
            }

            // ★ RESUME을 **swapTo보다 먼저**, 그리고 이 소켓의 첫 바이트로 보낸다.
            // Mac은 아직 이 연결이 누구 것인지 모르는 상태라, 다른 프레임이 먼저 가면 버려진다.
            val frame = resumeFrame(remote.token)
            val ok = frame != null && runCatching {
                tcp.getOutputStream().apply { write(frame); flush() }
            }.isSuccess
            if (!ok) {
                runCatching { tcp.close() }
                stopProbeReader()
                done(false, if (frame == null) "세션이 사라졌다" else "RESUME 송신 실패")
                return@Thread
            }

            // UDP가 안 뚫렸으면 Mac의 **UDP 후보**로 보낸다.
            //
            // ★ 제어(TCP) 소켓의 주소를 쓰면 안 된다. 포트가 유동이라 Mac의 제어 포트와 영상
            // 포트는 애초에 다른 번호고, 펀칭 경로면 한 번 더 갈린다. 그리로 HELLO를 보내면
            // Mac의 UDP 소켓에 닿지 않아 영상 게이트가 영영 안 열린다.
            //
            // 세대가 없으면(영상 앱 없음) 보낼 곳도 필요 없다 — 옛 코드는 그때도 UDP 대상을
            // 요구해서, 후보에 UDP가 없다는 이유만으로 멀쩡한 제어 채널 교체를 실패시켰다.
            val target = if (movingDatagram) pickUdpTarget(remote, newUdpTarget) else null
            if (movingDatagram && target == null) {
                runCatching { tcp.close() }
                stopProbeReader()
                done(false, "UDP 펀칭 실패 + UDP 후보 없음 — 영상 경로 없음")
                return@Thread
            }
            // ★ 소켓을 넘기기 전에 프로브 리더를 확실히 내린다. 살려 두면 swapTo가 띄우는
            // 영상 수신 루프와 같은 소켓에 두 리더가 앉아 패킷을 나눠 먹는다.
            stopProbeReader()
            pendingUdp = null   // swapTo가 소유권을 가져간다 — releaseRound가 닫지 않게
            client.swapTo(tcp, udpSock, target)

            afterSwap()
            done(true, "udp=${newUdpTarget ?: "제어 주소 사용"} " +
                "tcp=${tcp.inetAddress?.hostAddress}:${tcp.port}")
        }.start()
    }

    // MARK: - 흐름 ③: 데이터그램 세대 열기

    /**
     * 이 세대의 UDP 경로를 세운다. 접속 라운드가 하던 일과 같되 **UDP만** 한다.
     *
     * 순서가 곧 정확성이다:
     *  1. 후보 수집(STUN)은 **수신 루프를 열기 전에** — 같은 소켓에서 응답을 직접 읽으므로
     *     루프가 먼저 돌면 그 응답을 삼켜 STUN이 타임아웃한다(실기기에서 9초를 잃었다).
     *  2. 응답을 보낸 **직후** 펀칭을 시작한다 — Mac도 그 응답을 받는 순간 시작하므로
     *     양쪽 시작 시각이 제어 채널 편도 지연만큼만 어긋난다.
     *  3. HELLO는 펀칭을 기다리지 않는다 — 같은 LAN이면 host 후보로 이미 닿고, 그 경우
     *     펀칭은 원리상 성립하지 않는다(10초를 기다렸다 보내면 그만큼 검은 화면이다).
     *
     * @param stillOpen 세대가 아직 그 세대인가. 앱이 곧바로 내려가면 중간에 거짓이 된다.
     * @param accept    직렬화한 우리 후보 묶음 — 호출부가 `UdpAccept`로 되돌린다.
     */
    fun datagramRound(
        remote: CandidateBundle,
        stillOpen: () -> Boolean,
        accept: (candidates: ByteArray) -> Unit,
        sendHello: () -> Unit,
    ) {
        val sock = client.openMediaSocketOn(netWatcher.current)
        val mine = IceAgent.gather(
            sock, tcpPort = 0, servers = stunServers(),
            deprioritized = netWatcher.deprioritizedAddresses(),
            transports = setOf(CandidateTransport.UDP),
        )
        // 세대가 그새 닫혔으면(앱이 곧바로 내려감) 여기서 멈춘다 — 소켓만 정리한다.
        if (!stillOpen()) {
            runCatching { sock.close() }
            return
        }
        client.adoptMediaSocket(sock)
        accept(CandidateBundle(remote.token, mine).serialized())

        val agent = IceAgent()
        client.onUdpProbe = { data, len, from -> agent.handleInbound(data, len, from, sock) }
        client.startUdpReceive()

        // 펀칭이 확정하기 전에도 보낼 곳을 정해 둔다. 같은 LAN이면 이 host 주소로 이미 닿는다.
        client.udpTarget = pickUdpTarget(remote, null)
        sendHello()

        val path = agent.punchUdp(sock, remote.ordered(CandidateTransport.UDP), remote.token)
        if (path == null || !stillOpen()) return
        val before = client.udpTarget
        client.udpTarget = pickUdpTarget(remote, path.endpoint)
        Log.i(TAG, "영상 UDP 대상 확정 ${path.endpoint} (rtt=${path.rttMs}ms)")
        // Mac은 HELLO의 **출발지**로 영상 피어를 링크한다. 미리 잡아 둔 주소가 틀렸으면
        // 첫 HELLO는 그대로 유실되므로, 대상이 바뀌었으면 반드시 다시 보낸다.
        if (before != client.udpTarget) sendHello()
    }
}
