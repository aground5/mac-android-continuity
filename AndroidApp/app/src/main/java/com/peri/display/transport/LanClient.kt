package com.peri.display.transport

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "LanClient"

/**
 * UDP 수신 타임아웃(ms). 취소를 확인할 창을 열어 두는 용도 — 값 자체는 지연에 영향이 없다
 * (패킷이 있으면 즉시 반환한다). **루프를 열 때 미리** 걸어야 한다: 나중에 `setSoTimeout()`을
 * 부르면 `receive()`가 쥔 소켓 락을 기다리다 호출 스레드가 영구히 멎는다.
 */
private const val RECV_TIMEOUT_MS = 200

/**
 * LAN Bearer (Android = 클라이언트). 하나의 Bearer가 두 채널을 소유한다:
 *   - 제어/핸드셰이크 : TCP (Mac에 접속)
 *   - 영상           : UDP (HELLO로 자신을 등록, 영상 프래그먼트 수신)
 */
class LanClient(private val scope: CoroutineScope) : Bearer {

    override var onFrame: ((WireFrame) -> Unit)? = null          // TCP 제어/핸드셰이크
    override var onConnected: (() -> Unit)? = null
    override var onDisconnected: ((Throwable?) -> Unit)? = null
    var onVideoPacket: ((MiniRtpPacket) -> Unit)? = null  // UDP 영상 (Mini-RTP)

    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var udp: DatagramSocket? = null
    private var readJob: Job? = null
    private var udpJob: Job? = null

    /**
     * 제어 연결 세대. `attach` 때마다 올라가고, read 루프는 자기 세대를 들고 돈다.
     *
     * 왜 필요한가: 핸드오버 재협상은 새 연결을 세운 **뒤에** 옛 연결을 닫는다. 그러면 옛 read
     * 루프가 그제서야 예외로 깨어나 종료 콜백을 부르는데, 그게 그대로 통과하면 방금 세운
     * 새 채널을 `onDisconnected`로 날려버린다(=재접속 루프 진입). 세대가 다르면 무시한다.
     */
    @Volatile private var generation = 0

    private var host: String = ""
    private var port: Int = 0

    /**
     * ICE 프로브 후킹. true를 돌려주면 그 데이터그램은 소비된 것으로 보고 영상 파서로 넘기지 않는다.
     * MirrorController가 IceAgent에 연결한다.
     */
    var onUdpProbe: ((ByteArray, Ipv4Endpoint) -> Boolean)? = null

    /**
     * 영상·펀칭·STUN이 **공유하는 단일 UDP 소켓**.
     *
     * 왜 미리 열어 두는가: STUN 매핑은 **그 소켓/포트**에 대해 만들어지므로, 후보를 수집할 때
     * 쓴 소켓을 나중에 미디어에도 그대로 써야 한다. 예전엔 `startVideo`에서 그때그때 새로 열어
     * 매핑이 달라졌다(포트가 매번 바뀜) — 그러면 상대가 뚫어 둔 구멍이 무용지물이 된다.
     */
    @Synchronized
    fun mediaSocket(): DatagramSocket {
        udp?.let { if (!it.isClosed) return it }
        val s = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(0))   // 포트는 커널이 배정 — 값은 후보에 실어 상대에게 알린다
            // 키프레임은 수백 프래그먼트가 버스트로 도착 → 버퍼를 크게(Mac도 8MB로 맞춰 둠).
            runCatching { receiveBufferSize = 8 * 1024 * 1024 }
        }
        udp = s
        Log.i(TAG, "media udp socket :${s.localPort} recvBuf=${s.receiveBufferSize}")
        return s
    }

    /**
     * UDP 수신 루프 시작 — 펀칭 프로브와 영상 패킷을 함께 받는다.
     *
     * ★ **후보 수집(STUN)이 끝난 뒤에** 불러야 한다. STUN 질의는 같은 소켓에서 응답을 직접
     * 읽는데, 이 루프가 먼저 돌면 그 응답을 가로채 STUN이 타임아웃한다(실기기 로그에서
     * 구글 STUN 응답을 루프가 삼켜 9초 지연 발생).
     *
     * 반대로 **펀칭 전에는 반드시** 시작해야 한다 — 예전엔 `startVideo()`(=핸드셰이크 후)에서
     * 열어서 펀칭 내내 소켓을 아무도 읽지 않았고, 그래서 프로브에 ACK를 못 보내 양쪽이 대칭으로
     * 실패했다.
     */
    /**
     * 수신 루프 정지. 루프는 다음 수신 타임아웃(≤`RECV_TIMEOUT_MS`) 안에 스스로 빠져나온다.
     *
     * ★ **여기서 `soTimeout`을 건드리면 안 된다.** `Job.cancel()`이 블로킹 `receive()`를
     * 깨우지 못하는 건 맞지만, 그렇다고 뒤늦게 `setSoTimeout()`을 부르면 **그 호출이
     * `receive()`가 쥔 소켓 락을 기다리며 영구히 블록된다.** 실기기에서 이걸로 메인 스레드가
     * 멎어 ANR이 났다(`Input dispatching timed out`, 그 뒤 펀칭이 시작조차 못 함 —
     * beginPunch가 Dispatchers.Main에서 돌기 때문). 그래서 타임아웃은 **루프를 열 때 미리**
     * 걸어 두고, 여기서는 플래그만 내린다.
     */
    @Synchronized
    fun stopUdpReceive() {
        val job = udpJob ?: return
        udpJob = null
        job.cancel()
    }

    @Synchronized
    fun startUdpReceive() {
        if (udpJob?.isActive == true) return
        val sock = mediaSocket()
        udpJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(2048)
            var count = 0
            // 취소를 확인할 유일한 창 — 무한 대기로 두면 이 루프는 영영 안 끝난다.
            // 영상이 흐르는 동안엔 패킷이 계속 와서 타임아웃이 거의 발생하지 않는다.
            runCatching { sock.soTimeout = RECV_TIMEOUT_MS }
            while (true) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                } catch (_: java.net.SocketTimeoutException) {
                    // 타임아웃은 정상 — 다만 이때가 취소를 확인할 유일한 지점이다.
                    if (!isActive) { Log.i(TAG, "udp recv 루프 종료(취소)"); return@launch }
                    continue
                } catch (t: Throwable) {
                    Log.i(TAG, "udp recv 루프 종료: ${t.message}")
                    return@launch
                }
                if (!isActive) { Log.i(TAG, "udp recv 루프 종료(취소)"); return@launch }
                val data = pkt.data.copyOf(pkt.length)
                val from = Ipv4Endpoint(pkt.address?.hostAddress ?: "", pkt.port)

                // ICE 프로브를 먼저 본다 — 프로브 타입(2·3)은 UdpType(0·1)에 없어
                // 영상 파서가 어차피 null을 준다.
                if (onUdpProbe?.invoke(data, from) == true) continue

                count++
                if (count <= 3 || count % 120 == 0) {
                    Log.i(TAG, "UDP recv #$count ${pkt.length}B from $from")
                }
                val parsed = WireFormat.parseUdpVideo(data)
                if (parsed == null) Log.w(TAG, "UDP parse null (${data.size}B, type=${data.getOrNull(0)})")
                else onVideoPacket?.invoke(parsed)
            }
        }
    }

    /** 후보에 실을 TCP 로컬 포트. 펀칭 때 이 포트에 bind해 connect한다. */
    @Volatile var tcpLocalPort: Int = 0
        private set

    /**
     * 영상 UDP를 보낼 상대 주소. **TCP 제어 채널과 포트가 다르다.**
     *
     * 예전엔 Mac이 제어(TCP)와 영상(UDP)을 같은 고정 포트로 열어 `host:port`를 그대로 써도
     * 맞았다. 지금은 둘 다 커널 배정 유동 포트라 애초에 다르고, 펀칭 경로에선 TCP가 전용
     * 펀치 포트로 뚫려 한 번 더 갈린다.
     * 그때 TCP 주소로 HELLO를 보내면 Mac의 UDP 소켓에 닿지 않아 영상 게이트가 영영 안 열린다
     * (증상: 검은 화면 + 비트레이트 0 + 지연 분해에 인코딩만 표시 — 나머지는 수신측 보고값이라
     * 영상이 안 오면 0이다). 그래서 UDP 펀칭이 확정한 주소를 여기에 따로 담는다.
     */
    @Volatile var udpTarget: Ipv4Endpoint? = null

    /// 후보 수집 전에 TCP 포트를 확정해 둔다 — STUN이 알려준 매핑과 실제 connect 포트가 같아야 한다.
    fun reserveTcpPort(port: Int) { tcpLocalPort = port }

    /** 펀칭으로 이미 성립한 소켓을 제어 채널로 채택한다(NAT 경로). */
    fun adoptPunchedSocket(s: Socket) {
        this.host = s.inetAddress?.hostAddress ?: ""
        this.port = s.port
        this.tcpLocalPort = s.localPort
        attach(s, announce = true)
    }

    /**
     * 핸드오버 재협상용 **여분의** 미디어 소켓을 새 망에 바인드해 연다 — 기존 소켓은 그대로 둔다.
     *
     * 기존 소켓은 옛 망으로 뚫린 구멍을 물고 있고 그 경로로 아직 영상이 흐른다. 새 망의
     * 매핑은 새 소켓에서 STUN을 쏴야 유효하므로 둘을 잠시 같이 들고 있다가 갈아끼운다.
     *
     * ★ `Network.bindSocket`이 **필수**다. wildcard 소켓은 커널 라우팅을 따르는데 핸드오버
     * 직후엔 기본망이 바뀌는 중이라 어느 인터페이스로 나갈지가 레이스다. 어긋나면 STUN으로
     * 얻은 매핑과 실제 미디어 경로가 달라져 펀칭이 조용히 실패한다.
     */
    fun openMediaSocketOn(network: android.net.Network?): DatagramSocket {
        val s = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(0))
            runCatching { receiveBufferSize = 8 * 1024 * 1024 }
        }
        network?.let {
            runCatching { it.bindSocket(s) }
                .onFailure { e -> Log.w(TAG, "udp bindSocket 실패: ${e.message}") }
        }
        Log.i(TAG, "재협상용 udp 소켓 :${s.localPort} (network=$network)")
        return s
    }

    /**
     * 후보 수집에 쓴 소켓을 미디어 소켓으로 **채택**한다(콜드 재협상 경로).
     *
     * 무손실 경로(`swapTo`)와 달리 여기선 어차피 세션을 새로 맺으므로 옛 소켓을 살려 둘
     * 이유가 없다. 반드시 갈아끼워야 하는 이유는 따로 있다: 상대에게 알린 STUN 매핑은
     * **후보를 모은 그 소켓**의 것이라, 다른 소켓에서 펀칭하면 상대가 뚫어 둔 구멍과
     * 우리가 쏘는 출발지가 어긋나 조용히 실패한다.
     */
    @Synchronized
    fun adoptMediaSocket(s: DatagramSocket) {
        stopUdpReceive()
        val old = udp
        udp = s
        runCatching { old?.close() }
        Log.i(TAG, "미디어 소켓 교체 → :${s.localPort}")
    }

    /**
     * 데이터그램 세대가 닫혔다 → 소켓과 수신 루프를 놓는다.
     *
     * NAT 매핑을 없애려고 닫는 게 아니다(매핑은 아무도 안 두드리면 스스로 만료된다). 다음
     * 세대가 **새 소켓에서** 매핑을 다시 얻어야 하기 때문이다 — 옛 포트의 매핑은 그 포트의
     * 것이라 재사용할 수 없고, 그동안 스레드 하나가 쓰지 않는 소켓에 앉아 있을 이유도 없다.
     */
    @Synchronized
    fun releaseMediaSocket() {
        stopUdpReceive()
        val old = udp
        udp = null
        udpTarget = null
        runCatching { old?.close() }
        if (old != null) Log.i(TAG, "미디어 소켓 반납 :${old.localPort}")
    }

    /**
     * 새로 뚫은 경로로 제어(TCP)·영상(UDP)을 갈아끼운다. **세션은 유지된다.**
     *
     * 호출 전에 새 TCP로 RESUME 프레임이 나가 있어야 한다 — 그게 있어야 Mac이 이 연결을
     * 기존 세션에 붙이고, 없으면 핸드셰이크를 기다리다 버린다.
     *
     * 순서가 중요하다: **새 것을 먼저 세우고 옛 것을 나중에 닫는다.** 반대로 하면 그 사이
     * 영상이 끊겨 make-before-break의 의미가 없어진다. 옛 read 루프는 세대가 어긋나
     * 종료 콜백이 무시되므로 재접속 루프를 오발동하지 않는다.
     */
    @Synchronized
    fun swapTo(tcp: Socket, newUdp: DatagramSocket, target: Ipv4Endpoint?) {
        val oldTcp = socket
        val oldUdp = udp

        stopUdpReceive()
        udp = newUdp
        udpTarget = target
        // 옮길 데이터그램 세대가 없으면(영상 앱 없음) 수신 루프를 열지 않는다 — 아무도
        // 안 쓰는 소켓에 스레드를 앉혀 두는 일이다. 세대가 열릴 때 `openMediaSocketOn`이
        // 새 소켓을 잡고 이 소켓은 그때 닫힌다.
        if (target != null) startUdpReceive()

        this.host = tcp.inetAddress?.hostAddress ?: ""
        this.port = tcp.port
        this.tcpLocalPort = tcp.localPort
        // announce=false: 재개는 핸드셰이크가 아니다. onConnected를 부르면 ClientHello가 나가
        // 새 세션이 만들어지고, 그러면 무손실 교체가 아니라 그냥 재접속이 된다.
        attach(tcp, announce = false)

        runCatching { oldTcp?.close() }
        runCatching { oldUdp?.close() }
        Log.i(TAG, "전송 교체 완료 → tcp $host:$port (local=$tcpLocalPort), udp $target")
    }

    fun connect(host: String, port: Int) {
        this.host = host
        this.port = port
        val gen = bumpGeneration()
        readJob = scope.launch(Dispatchers.IO) {
            try {
                val sock = Socket()
                sock.tcpNoDelay = true
                sock.reuseAddress = true
                // 고정 로컬 포트에서 나간다 — 후보에 실어 상대에게 알린 srflx-tcp 매핑과 같은
                // 포트여야 상대가 뚫어 둔 구멍이 유효하다. 0이면 커널 배정 후 값을 기록.
                if (tcpLocalPort != 0) runCatching { sock.bind(InetSocketAddress(tcpLocalPort)) }
                sock.connect(InetSocketAddress(host, port), 5000)
                tcpLocalPort = sock.localPort
                Log.i(TAG, "TCP connected to $host:$port (local=${sock.localPort})")
                socket = sock
                output = sock.getOutputStream()
                readLoop(sock, gen, announce = true)
            } catch (t: Throwable) {
                Log.e(TAG, "connection error: ${t.message}")
                close(t, gen)
            }
        }
    }

    /**
     * 이미 연결된 소켓을 읽기 루프에 붙인다(펀칭·재개).
     *
     * 세대 부여와 `output` 설정은 **동기로** 한다. 코루틴 안에서 하면 `swapTo`가 곧바로
     * 옛 소켓을 닫을 때 아직 세대가 안 올라가 있어, 옛 루프의 종료가 현재 세대로 오인된다.
     */
    @Synchronized
    private fun attach(sock: Socket, announce: Boolean) {
        val gen = bumpGeneration()
        socket = sock
        output = runCatching { sock.getOutputStream() }.getOrNull()
        readJob = scope.launch(Dispatchers.IO) {
            try {
                readLoop(sock, gen, announce)
            } catch (t: Throwable) {
                Log.e(TAG, "attach error: ${t.message}")
                close(t, gen)
            }
        }
    }

    @Synchronized private fun bumpGeneration(): Int = ++generation

    /** 프레임 읽기 루프. 호출자는 IO 디스패처에 있어야 한다. */
    private suspend fun readLoop(sock: Socket, gen: Int, announce: Boolean) {
        val input = sock.getInputStream()
        if (announce) withContext(Dispatchers.Main) { onConnected?.invoke() }

        val assembler = FrameAssembler()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            assembler.append(buf, n)
            while (true) {
                val frame = assembler.next() ?: break
                onFrame?.invoke(frame)
            }
        }
        close(null, gen)
    }

    /** 제어/핸드셰이크 프레임 송신 (TCP). IO 스레드. */
    /**
     * 제어 프레임 송신 전용 **직렬** 디스패처.
     *
     * 왜 병렬이면 안 되나: 종료 직전에 보내는 Bye가 실제로 선 위에 올라가기 전에 소켓이 닫히면
     * 그냥 사라진다. 실기기에서 정확히 그랬다 — `bye 전송` 로그가 찍히고 2ms 뒤 소켓이 닫혀
     * (`EBADF`), Mac은 Bye를 못 받고 "그냥 끊겼나 보다"로 60초 유예를 돌았다(로그의 RST가 그 결과).
     * 닫기도 이 디스패처에 얹으면 제출 순서 = 실행 순서라 그 경합이 사라진다.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val txScope = CoroutineScope(scope.coroutineContext + Dispatchers.IO.limitedParallelism(1))

    override fun sendControl(frame: ByteArray) { sendControl(frame, onSent = null) }

    /**
     * [onSent]는 바이트가 **실제로 선에 오른 뒤** 불린다.
     *
     * 이게 필요한 이유: 이 함수는 큐에 넣고 즉시 반환한다. 호출부에서 곧바로 "보냈다"고 찍으면
     * 거짓말이 된다 — 실기기에서 `bye 전송` 2ms 뒤 소켓이 닫혀 Bye가 통째로 유실됐는데,
     * 로그만 보면 보낸 것처럼 보여 원인을 한참 못 찾았다.
     */
    override fun sendControl(frame: ByteArray, onSent: (() -> Unit)?) {
        txScope.launch {
            try {
                val out = output ?: run { Log.w(TAG, "tcp send 무시 — 채널 없음"); return@launch }
                out.write(frame); out.flush()
                onSent?.invoke()
            } catch (t: Throwable) {
                Log.e(TAG, "tcp send error: ${t.message}")
            }
        }
    }

    /**
     * 영상 UDP 채널 시작: HELLO 데이터그램을 Mac:port/udp로 보내 자신을 등록하고
     * 영상 프래그먼트 수신 루프를 연다. 핸드셰이크 후 호출.
     */
    /// 영상 채널 개시 — HELLO 한 발만 보낸다. 수신 루프는 `mediaSocket()`이 이미 돌리고 있다.
    ///
    /// **후보 수집 때 쓴 그 소켓**을 그대로 쓴다. 새로 열면 포트가 바뀌어 상대가 뚫어 둔 NAT
    /// 구멍이 무용지물이 된다.
    fun startVideo(helloDatagram: ByteArray) {
        startUdpReceive()   // 펀칭 없이 곧장 LAN 접속한 경우엔 여기서 처음 열린다
        scope.launch(Dispatchers.IO) {
            try {
                val sock = mediaSocket()
                // 펀칭 경로면 UDP로 뚫린 주소로, 아니면 제어 채널과 같은 주소로 보낸다.
                val target = udpTarget ?: Ipv4Endpoint(host, port)
                val macAddr = InetAddress.getByName(target.address)
                sock.send(DatagramPacket(helloDatagram, helloDatagram.size, macAddr, target.port))
                Log.i(TAG, "UDP HELLO sent → $target (local=${sock.localPort})")
            } catch (t: Throwable) {
                Log.e(TAG, "udp hello 실패: ${t.message}", t)
            }
        }
    }

    /**
     * 화면 공유 영상 데이터그램 송신 — HELLO를 보낸 **그 소켓**으로 나간다.
     *
     * 같은 소켓이어야 하는 이유가 둘이다: NAT/펀칭 구멍이 그 포트에 뚫려 있고, Mac이 수신
     * 영상을 **출발 주소로 디먹스**하기 때문이다(HELLO로 등록된 피어만 인정한다).
     * 다른 소켓으로 보내면 Mac은 모르는 주소로 보고 조용히 버린다.
     *
     * 실패는 로그도 남기지 않는다 — 프레임마다 부르는 경로라 한 번 막히면 로그가 폭주하고,
     * 어차피 다음 프레임이 곧 온다.
     */
    override fun sendVideo(payload: ByteArray) {
        val sock = udp ?: return
        if (sock.isClosed) return
        val target = udpTarget ?: Ipv4Endpoint(host, port)
        runCatching {
            sock.send(DatagramPacket(payload, payload.size,
                                     InetAddress.getByName(target.address), target.port))
        }
    }

    override fun disconnect() {
        readJob?.cancel()
        udpJob?.cancel()
        runCatching { udp?.close() }
        udp = null
        // ★ TCP 닫기는 **송신 큐 뒤에** 줄 세운다. 바로 닫으면 방금 예약된 Bye가 선에 오르기도
        //   전에 output이 사라져 그대로 유실된다(실기기에서 겪음). 같은 직렬 디스패처라
        //   앞에 예약된 write가 전부 끝난 뒤에 이 close가 돈다.
        val gen = generation
        txScope.launch { close(null, gen) }
    }

    /**
     * 연결 종료 처리. **자기 세대일 때만** 상태를 지우고 콜백을 올린다.
     *
     * 교체된 옛 연결이 뒤늦게 깨어나 여기로 들어오는 일이 정상 경로다(swapTo가 새 채널을
     * 세운 뒤 옛 소켓을 닫으므로). 그때 그냥 통과시키면 방금 세운 채널의 socket/output을
     * 지우고 `onDisconnected`까지 올려 재접속 루프를 오발동한다.
     */
    @Synchronized
    private fun close(error: Throwable?, gen: Int) {
        if (gen != generation) {
            Log.i(TAG, "교체된 옛 연결의 종료 무시 (gen=$gen, 현재=$generation)")
            return
        }
        val s = socket
        socket = null
        output = null
        runCatching { s?.close() }
        scope.launch(Dispatchers.Main) { onDisconnected?.invoke(error) }
    }
}
