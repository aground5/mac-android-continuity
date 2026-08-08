package com.peri.display.transport

import android.util.Log
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

private const val TAG = "Stun"

/**
 * STUN(RFC 5389) Binding 요청/응답 — Mac `Stun.swift`와 대칭.
 *
 * ★ 불변식: **STUN은 실제 미디어를 나를 소켓/포트에서 보내야 한다.** 다른 포트에서 얻은 매핑은
 * NAT가 그 포트에 대해 만든 것이라 펀칭에 쓸 수 없다. 그래서 API가 전부 "쓸 소켓/로컬 포트"를 받는다.
 *
 * **포트 보존은 가정하지 않는다** — Mac 쪽 실측에서 UDP는 보존됐지만 TCP는 로컬≠외부였다.
 * 후보는 항상 STUN이 알려준 포트를 그대로 쓴다.
 */
data class StunServer(val host: String, val port: Int, val tcp: Boolean = false) {
    val display: String get() = "$host:$port"

    companion object {
        /** 기본 목록 — 실측으로 고름. TCP 응답 서버가 있어야 srflx-tcp 후보를 얻는다. */
        val DEFAULTS = listOf(
            StunServer("stun.l.google.com", 19302),
            StunServer("stun.cloudflare.com", 3478),
            StunServer("stun.nextcloud.com", 3478, tcp = true),
            StunServer("stun.antisip.com", 3478, tcp = true),
            StunServer("stun.voip.blackberry.com", 3478, tcp = true),
        )
    }
}

/** IPv4 엔드포인트. 후보·프로브·펀칭 공용. */
data class Ipv4Endpoint(val address: String, val port: Int) {
    override fun toString(): String = "$address:$port"
}

object Stun {
    const val MAGIC_COOKIE = 0x2112A442
    private const val TYPE_BINDING_REQUEST = 0x0001
    private const val TYPE_BINDING_SUCCESS = 0x0101
    private const val ATTR_MAPPED_ADDRESS = 0x0001
    private const val ATTR_XOR_MAPPED_ADDRESS = 0x0020
    const val HEADER_SIZE = 20

    private val rng = SecureRandom()

    /** Binding Request 한 통 + 응답 대조용 트랜잭션 ID. */
    fun bindingRequest(): Pair<ByteArray, ByteArray> {
        val txid = ByteArray(12).also { rng.nextBytes(it) }
        val m = ByteArray(HEADER_SIZE)
        putU16(m, 0, TYPE_BINDING_REQUEST)
        putU16(m, 2, 0)                       // attribute 없음
        putU32(m, 4, MAGIC_COOKIE)
        System.arraycopy(txid, 0, m, 8, 12)
        return m to txid
    }

    /** 성공 응답에서 매핑 주소를 꺼낸다. txid 불일치·형식 오류면 null. */
    fun parseBindingResponse(d: ByteArray, txid: ByteArray): Ipv4Endpoint? {
        if (d.size < HEADER_SIZE) return null
        if (u16(d, 0) != TYPE_BINDING_SUCCESS) return null
        if (u32(d, 4) != MAGIC_COOKIE) return null
        for (i in 0 until 12) if (d[8 + i] != txid[i]) return null

        val length = u16(d, 2)
        if (d.size < HEADER_SIZE + length) return null

        var i = HEADER_SIZE
        val end = HEADER_SIZE + length
        while (i + 4 <= end) {
            val atype = u16(d, i)
            val alen = u16(d, i + 2)
            val v = i + 4
            if (v + alen > d.size) return null

            // family(1B) == 0x01 → IPv4. 앞 1바이트는 예약.
            if (alen >= 8 && (d[v + 1].toInt() and 0xFF) == 0x01) {
                when (atype) {
                    ATTR_XOR_MAPPED_ADDRESS -> {
                        val port = u16(d, v + 2) xor (MAGIC_COOKIE ushr 16)
                        val addr = u32(d, v + 4) xor MAGIC_COOKIE
                        return Ipv4Endpoint(ipv4String(addr), port)
                    }
                    ATTR_MAPPED_ADDRESS -> return Ipv4Endpoint(ipv4String(u32(d, v + 4)), u16(d, v + 2))
                }
            }
            // 속성은 4바이트 경계로 패딩된다.
            i = v + alen + ((4 - alen % 4) % 4)
        }
        return null
    }

    // 바이트 유틸 (모두 big-endian)
    private fun putU16(b: ByteArray, i: Int, v: Int) {
        b[i] = ((v ushr 8) and 0xFF).toByte(); b[i + 1] = (v and 0xFF).toByte()
    }
    private fun putU32(b: ByteArray, i: Int, v: Int) {
        b[i] = ((v ushr 24) and 0xFF).toByte(); b[i + 1] = ((v ushr 16) and 0xFF).toByte()
        b[i + 2] = ((v ushr 8) and 0xFF).toByte(); b[i + 3] = (v and 0xFF).toByte()
    }
    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, i: Int) =
        ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
        ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
    private fun ipv4String(v: Int) =
        "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"
}

object StunClient {

    /**
     * 호스트명 → **IPv4** 주소. IPv6를 쓰면 안 되는 이유:
     *
     * STUN 서버에 IPv6로 물으면 IPv6 매핑(XOR-MAPPED-ADDRESS family 0x02)이 돌아오는데,
     * 우리 후보 체계는 처음부터 끝까지 IPv4라 그 값을 쓸 수 없다. 파서가 거부하면 그 서버는
     * "응답 없음"으로 처리돼 조용히 버려진다 — 실기기에서 Google·Cloudflare가 IPv6로 풀려
     * 전부 실패하고 IPv4로 풀리는 서버 한 곳만 남아 **NAT 판정 자체가 불가능**했다
     * (로그 증거: `UDP recv #1 44B from 2001:4860:4864:5:8000::1:19302`).
     * Mac은 `getaddrinfo`에 `AF_INET`을 박아 둬 이 문제가 없었다.
     */
    private fun resolveIpv4(host: String): InetAddress? =
        runCatching {
            InetAddress.getAllByName(host).firstOrNull { it is java.net.Inet4Address }
        }.getOrNull()

    /**
     * **이미 열려 있는 UDP 소켓**으로 Binding 요청 → 그 소켓의 공인 매핑.
     * 미디어에 쓸 소켓을 그대로 넘겨야 매핑이 유효하다. 소켓의 SoTimeout은 호출자가 관리.
     */
    fun query(socket: DatagramSocket, server: StunServer, timeoutMs: Int = 2000): Ipv4Endpoint? {
        val (msg, txid) = Stun.bindingRequest()
        return try {
            val addr = resolveIpv4(server.host) ?: run {
                Log.w(TAG, "udp stun ${server.display}: IPv4 주소 없음 — 건너뜀")
                return null
            }
            socket.send(DatagramPacket(msg, msg.size, addr, server.port))

            val prev = socket.soTimeout
            socket.soTimeout = 200
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(1500)
            try {
                while (System.currentTimeMillis() < deadline) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(pkt)
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    }
                    // 이 소켓엔 미디어도 흐를 수 있으므로 STUN 응답만 골라낸다.
                    val r = Stun.parseBindingResponse(pkt.data.copyOf(pkt.length), txid)
                    if (r != null) return r
                }
                null
            } finally {
                socket.soTimeout = prev
            }
        } catch (t: Throwable) {
            Log.w(TAG, "udp stun ${server.display} 실패: ${t.message}")
            null
        }
    }

    /**
     * **지정한 로컬 포트에서** TCP Binding (RFC 5389 §7.2.2 — TCP 위에서도 같은 프레이밍).
     * 펀칭에 쓸 로컬 포트를 넘겨야 얻은 매핑이 유효하다.
     */
    fun queryTcp(server: StunServer, fromLocalPort: Int, timeoutMs: Int = 3000): Ipv4Endpoint? {
        var sock: Socket? = null
        return try {
            val addr = resolveIpv4(server.host) ?: run {
                Log.w(TAG, "tcp stun ${server.display}: IPv4 주소 없음 — 건너뜀")
                return null
            }
            val s = Socket()
            sock = s
            s.reuseAddress = true
            s.bind(InetSocketAddress(fromLocalPort))
            s.connect(InetSocketAddress(addr, server.port), timeoutMs)
            s.soTimeout = timeoutMs

            val (msg, txid) = Stun.bindingRequest()
            s.getOutputStream().apply { write(msg); flush() }

            val buf = ByteArray(1500)
            val n = DataInputStream(s.getInputStream()).read(buf)
            if (n <= 0) null else Stun.parseBindingResponse(buf.copyOf(n), txid)
        } catch (t: Throwable) {
            Log.w(TAG, "tcp stun ${server.display} 실패: ${t.message}")
            null
        } finally {
            runCatching { sock?.close() }
        }
    }
}
