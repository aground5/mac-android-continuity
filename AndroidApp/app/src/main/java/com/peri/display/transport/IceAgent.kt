package com.peri.display.transport

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "IceAgent"

/**
 * 후보 수집 + 홀펀칭 (Android = controlled 역할). Mac `IceAgent.swift`와 대칭.
 *
 * 펀칭 원리 — NAT는 "안에서 나간 적 있는 상대"의 패킷만 들여보낸다. 양쪽이 **동시에** 먼저 쏘면
 * 각자의 첫 패킷은 상대 NAT에서 버려지지만 **자기 쪽 NAT에 매핑을 뚫는다.** 그 뒤 도착하는
 * 상대 패킷은 통과한다.
 *
 * TCP는 비대칭이다: **Android는 listen하지 않는다.** 고정 로컬 포트에 bind 후 Mac의 srflx-tcp로
 * connect를 반복하고, Mac이 자기 포트에서 outbound SYN을 쏴 매핑을 열어 두면 이 SYN이 Mac의
 * listener에 도달해 연결이 성립한다. 덕분에 Android엔 `SO_REUSEPORT`가 필요 없다
 * (Linux에서 listen+connect 포트 공유는 까다롭다 — 그 제약을 설계로 우회했다).
 *
 * 토큰은 경로 인증용이 아니다. 진짜 인증은 뒤따르는 ML-KEM 핸드셰이크 + 신원서명이 한다.
 */
object IceUdpType {
    const val PROBE = 2
    const val PROBE_ACK = 3
}

/** 펀칭으로 확정된 경로. */
data class SelectedPath(val endpoint: Ipv4Endpoint, val rttMs: Int)

class IceAgent {

    private val lock = Any()
    private var token: ByteArray? = null
    private var winner: SelectedPath? = null

    // MARK: 후보 수집

    companion object {
        /**
         * 로컬 후보를 모은다. **STUN은 반드시 udp 인자로 받은 그 소켓에서** 나간다 —
         * 다른 포트에서 얻은 매핑은 그 포트의 것이라 펀칭에 못 쓴다.
         *
         * 서버를 여러 개 도는 건 중복 확인이 아니라 가용성 때문이다(일부는 응답이 없다). 첫 성공에서 멈춘다.
         *
         * [transports]는 이번에 모을 전송이다. 세션 수립은 `{TCP}`, 데이터그램 채널을 열 때는
         * `{UDP}`, fast UDP 모드에서 접속할 때만 둘 다다. **STUN 왕복이 전송마다 따로** 일어나므로
         * 이 구분이 곧 지연이다 — TCP만 필요한데 UDP까지 물으면 그만큼 늦는다.
         */
        fun gather(
            udp: DatagramSocket,
            tcpPort: Int,
            servers: List<StunServer> = StunServer.DEFAULTS,
            deprioritized: Set<String> = emptySet(),
            transports: Set<CandidateTransport> = setOf(CandidateTransport.TCP),
        ): List<IceCandidate> {
            val out = ArrayList<IceCandidate>()
            val wantUdp = CandidateTransport.UDP in transports
            val wantTcp = CandidateTransport.TCP in transports

            // host — Wi-Fi와 셀룰러가 동시에 잡히면 여러 개. 상대가 어느 쪽에 닿을지 모르니 전부 싣되,
            // 셀룰러 주소는 뒤로 민다(`deprioritized`) — 상대가 앞에서부터 두드리기 때문이다.
            for (ip in LocalCandidates.hostAddresses(deprioritized)) {
                if (wantUdp) {
                    out.add(IceCandidate(CandidateTransport.UDP, CandidateKind.HOST, Ipv4Endpoint(ip, udp.localPort)))
                }
                if (wantTcp) {
                    out.add(IceCandidate(CandidateTransport.TCP, CandidateKind.HOST, Ipv4Endpoint(ip, tcpPort)))
                }
            }

            // srflx-udp — 미디어 소켓 그대로 사용.
            //
            // 서버 **2곳 이상**에 물어 매핑을 비교한다. 홀펀칭이 가능한지는 오직 이 한 가지로
            // 갈린다: 목적지가 달라도 외부 매핑이 같으면(endpoint-independent) 뚫린다.
            // 목적지마다 다르면 symmetric NAT — 상대가 우리 매핑을 미리 알 수 없어 **코드로는
            // 해결 불가**이고 TURN 릴레이가 필요하다. 캐리어 CGNAT에서 흔하므로 반드시 잰다.
            val mappings = ArrayList<Pair<String, Ipv4Endpoint>>()
            if (wantUdp) {
                for (s in servers) {
                    val ep = StunClient.query(udp, s) ?: continue
                    mappings.add(s.display to ep)
                    Log.i(TAG, "srflx-udp $ep via ${s.display}")
                    if (mappings.size >= 2) break
                }
                when {
                    mappings.isEmpty() -> Log.w(TAG, "NAT 판정 불가 — STUN 응답 없음")
                    mappings.size == 1 ->
                        Log.w(TAG, "NAT 판정 불가 — 응답한 STUN 서버가 1곳뿐 (${mappings[0].first})")
                    mappings.map { it.second.port }.distinct().size == 1 ->
                        Log.i(TAG, "★ NAT: endpoint-independent (매핑 ${mappings[0].second.port} 일치) → 홀펀칭 가능")
                    else ->
                        Log.e(TAG, "★ NAT: SYMMETRIC — 목적지마다 매핑이 다름 " +
                            mappings.joinToString { "${it.first}→${it.second.port}" } +
                            " → 홀펀칭 원리적으로 불가(릴레이 필요)")
                }
                mappings.firstOrNull()?.let {
                    out.add(IceCandidate(CandidateTransport.UDP, CandidateKind.SRFLX, it.second))
                }
            }

            // srflx-tcp — TCP를 받는 서버만 가능(공개 STUN 다수가 UDP 전용).
            if (wantTcp) {
                for (s in servers.filter { it.tcp }) {
                    val ep = StunClient.queryTcp(s, tcpPort)
                    if (ep != null) {
                        out.add(IceCandidate(CandidateTransport.TCP, CandidateKind.SRFLX, ep))
                        Log.i(TAG, "srflx-tcp $ep via ${s.display}")
                        break
                    }
                }
            }

            if (out.none { it.kind == CandidateKind.SRFLX }) {
                Log.w(TAG, "srflx 후보를 하나도 못 얻음 — 같은 LAN(host)에서만 연결 가능")
            }
            return out
        }

        /** 프로브 패킷: [1B type][16B 공유토큰][8B BE stamp_us] — Mac과 동일 레이아웃. */
        fun probePacket(type: Int, token: ByteArray, stampUs: Long): ByteArray {
            val d = ByteArray(1 + CandidateBundle.TOKEN_LENGTH + 8)
            d[0] = type.toByte()
            System.arraycopy(token, 0, d, 1, CandidateBundle.TOKEN_LENGTH)
            var v = stampUs
            for (i in 7 downTo 0) {
                d[1 + CandidateBundle.TOKEN_LENGTH + i] = (v and 0xFF).toByte()
                v = v ushr 8
            }
            return d
        }

        /** 토큰이 다르면 우리 것이 아니다 → null. */
        fun parseProbe(d: ByteArray, expecting: ByteArray): Pair<Int, Long>? {
            val want = 1 + CandidateBundle.TOKEN_LENGTH + 8
            if (d.size < want) return null
            val type = d[0].toInt() and 0xFF
            if (type != IceUdpType.PROBE && type != IceUdpType.PROBE_ACK) return null
            for (i in 0 until CandidateBundle.TOKEN_LENGTH) {
                if (d[1 + i] != expecting[i]) return null
            }
            var stamp = 0L
            val s = 1 + CandidateBundle.TOKEN_LENGTH
            for (i in 0 until 8) stamp = (stamp shl 8) or (d[s + i].toLong() and 0xFF)
            return type to stamp
        }
    }

    // MARK: UDP 펀칭

    /**
     * 상대의 udp 후보 전부에 프로브를 반복 발사하고 ACK가 오는 경로를 고른다.
     * 수신은 호출자가 [handleInbound]로 넘겨준다 — 이 소켓엔 영상도 흐르므로 여기서 가로채지 않는다.
     */
    fun punchUdp(
        udp: DatagramSocket,
        remote: List<IceCandidate>,
        sharedToken: ByteArray,
        timeoutMs: Long = 10000,
    ): SelectedPath? {
        if (remote.isEmpty()) return null
        synchronized(lock) { token = sharedToken; winner = null }

        val deadline = System.currentTimeMillis() + timeoutMs
        var round = 0
        while (System.currentTimeMillis() < deadline) {
            synchronized(lock) { winner }?.let { return it }

            // 어느 후보가 뚫릴지 미리 알 수 없으니 전부에 동시에 쏜다.
            val stamp = System.currentTimeMillis() * 1000
            val pkt = probePacket(IceUdpType.PROBE, sharedToken, stamp)
            for (c in remote) {
                runCatching {
                    udp.send(DatagramPacket(pkt, pkt.size,
                        InetAddress.getByName(c.endpoint.address), c.endpoint.port))
                }
            }
            round++
            if (round == 1) Log.i(TAG, "udp punch 시작 → ${remote.size}개 후보")
            Thread.sleep(50)
        }
        Log.w(TAG, "udp 펀칭 실패 (${timeoutMs}ms 안에 응답 없음)")
        return null
    }

    /**
     * 수신 데이터그램이 프로브/ACK면 처리하고 true. (호출자는 그럼 영상 파서로 넘기지 않는다)
     *  - PROBE 수신 → 같은 stamp를 실어 ACK 회신. **회신 자체가 상대에게 경로를 알려준다.**
     *  - PROBE_ACK 수신 → 그 주소가 살아 있는 경로. 되돌아온 stamp로 RTT 계산.
     */
    fun handleInbound(data: ByteArray, from: Ipv4Endpoint, udp: DatagramSocket): Boolean {
        val tk = synchronized(lock) { token } ?: return false
        val (type, stamp) = parseProbe(data, tk) ?: return false

        when (type) {
            IceUdpType.PROBE -> {
                val ack = probePacket(IceUdpType.PROBE_ACK, tk, stamp)
                runCatching {
                    udp.send(DatagramPacket(ack, ack.size,
                        InetAddress.getByName(from.address), from.port))
                }
            }
            IceUdpType.PROBE_ACK -> {
                val rtt = ((System.currentTimeMillis() * 1000 - stamp) / 1000).toInt()
                record(from, if (rtt < 0) 0 else rtt)
            }
        }
        return true
    }

    /** 여러 경로가 살아나면 더 빠른 쪽을 남긴다. */
    private fun record(endpoint: Ipv4Endpoint, rttMs: Int) {
        synchronized(lock) {
            val w = winner
            if (w != null && w.rttMs <= rttMs) return
            winner = SelectedPath(endpoint, rttMs)
        }
        Log.i(TAG, "udp 경로 확보 $endpoint rtt=${rttMs}ms")
    }

    // MARK: TCP 펀칭 (Android = bind 후 connect 반복, listen 없음)

    /**
     * Mac의 tcp 후보로 connect를 반복 시도. 성공한 소켓을 돌려주면 호출자가 제어 채널로 쓴다.
     *
     * 매 시도마다 새 소켓을 **같은 로컬 포트**에 bind한다(`reuseAddress`) — 그래야 STUN으로 알아낸
     * srflx-tcp 매핑과 같은 포트에서 나가고, Mac이 그 주소로 매핑을 열어 둔 게 유효해진다.
     */
    fun punchTcp(
        localPort: Int,
        remote: List<IceCandidate>,
        timeoutMs: Long = 10000,
    ): Socket? {
        // ★ **srflx만 노린다.** 상대의 host 후보(사설 IP)는 같은 LAN일 때만 닿고 그 경우는
        // 평범한 접속 경로가 처리한다. 순차 시도에서 그 도달 불가 주소에 몇 초를 버리면,
        // 그동안 상대도 우리 host 후보를 붙잡고 있어 **양쪽이 서로 다른 쌍을 두드리게** 된다.
        // simultaneous open은 같은 쌍을 동시에 두드려야 성립하므로 그러면 안 뚫린다(실기기 확인).
        val all = remote.filter { it.transport == CandidateTransport.TCP }
        val srflx = all.filter { it.kind == CandidateKind.SRFLX }
        val targets = srflx.ifEmpty { all }
        if (targets.isEmpty()) return null

        val deadline = System.currentTimeMillis() + timeoutMs
        var attempts = 0
        while (System.currentTimeMillis() < deadline) {
            for (c in targets) {
                attempts++
                val s = Socket()
                try {
                    s.reuseAddress = true
                    s.tcpNoDelay = true
                    s.bind(InetSocketAddress(localPort))
                    // 짧은 타임아웃으로 여러 후보를 빠르게 순회한다.
                    s.connect(InetSocketAddress(c.endpoint.address, c.endpoint.port), 700)
                    Log.i(TAG, "tcp 펀칭 성공 ${c.endpoint} (${attempts}회 시도)")
                    return s
                } catch (_: Throwable) {
                    runCatching { s.close() }   // 대부분 실패한다 — 정상이다
                }
            }
            Thread.sleep(150)
        }
        Log.w(TAG, "tcp 펀칭 실패 (${attempts}회 시도)")
        return null
    }

    fun reset() {
        synchronized(lock) { token = null; winner = null }
    }
}
