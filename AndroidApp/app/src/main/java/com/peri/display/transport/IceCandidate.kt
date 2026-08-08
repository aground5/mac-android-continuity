package com.peri.display.transport

import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * 연결 후보 + BLE 직렬화 — Mac `IceCandidate.swift`와 **바이트 호환**이어야 한다.
 *
 * 포맷(이미 BLE 세션 키로 sealed 되므로 평문 텍스트 — 디버깅 편의):
 * ```
 * v1 <32 hex = 16B 펀치 토큰>
 * f fastudp                          (선택 — 아래 참조)
 * c tcp host  192.168.0.5   7437
 * c udp srflx 203.0.113.9  51234
 * ```
 * 줄바꿈 \n, 필드 구분 공백. 모르는 줄은 무시한다(필드가 늘어도 안 깨지게).
 *
 * 펀치 토큰은 경로 인증용이 아니라(진짜 인증은 뒤따르는 ML-KEM 핸드셰이크) 엉뚱한 트래픽을
 * 걸러내기 위한 값이다.
 *
 * **기본은 tcp 후보뿐이다.** 세션 수립에 UDP가 필요 없어졌다 — 데이터그램 경로는 영상을
 * 쓰는 앱이 실제로 뜰 때 제어 채널 위에서 따로 연다(`UdpOpen`). NAT의 UDP 매핑은 수명이
 * 짧아 붙들고 있으려면 계속 두드려야 하는데, TCP 매핑은 훨씬 오래 살아 세션만 유지하는 데는
 * 그쪽이 압도적으로 싸다.
 *
 * `f fastudp`는 Mac이 그 기본을 뒤집는 스위치다. **세션이 서는 순간 영상 앱이 곧바로 붙어야
 * 하는 경우**를 위한 것이고(지난번에 켜 둔 앱이 그런 앱이거나, 망이 바뀌어 다시 붙는 중),
 * 켜도 수명 규칙은 그대로다 — 마지막 영상 앱이 내려가면 UDP는 닫힌다.
 *
 * 플래그를 명시하는 이유는 "UDP 후보가 없다"와 "UDP 후보를 못 얻었다"가 선에서 똑같이
 * 보이기 때문이다 — 그 둘을 구분해야 우리 쪽 수집을 생략할지 정할 수 있다.
 */
enum class CandidateTransport(val wire: String) {
    TCP("tcp"), UDP("udp");
    companion object { fun from(s: String) = entries.firstOrNull { it.wire == s } }
}

/** host > srflx — 같은 LAN이면 굳이 공인 경로로 돌지 않는다. */
enum class CandidateKind(val wire: String, val priority: Int) {
    HOST("host", 100), SRFLX("srflx", 50);
    companion object { fun from(s: String) = entries.firstOrNull { it.wire == s } }
}

data class IceCandidate(
    val transport: CandidateTransport,
    val kind: CandidateKind,
    val endpoint: Ipv4Endpoint,
) {
    override fun toString() = "${transport.wire}/${kind.wire} $endpoint"
}

data class CandidateBundle(
    val token: ByteArray,
    val candidates: List<IceCandidate>,
    /** 이 라운드에서 UDP까지 함께 뚫는가 (위 주석 참조). 기본은 false = TCP만. */
    val fastUdp: Boolean = false,
) {

    fun serialized(): ByteArray {
        val sb = StringBuilder()
        sb.append(VERSION).append(' ').append(token.joinToString("") { "%02x".format(it) })
        if (fastUdp) sb.append('\n').append("f ").append(FLAG_FAST_UDP)
        for (c in candidates) {
            sb.append('\n').append("c ").append(c.transport.wire).append(' ')
                .append(c.kind.wire).append(' ')
                .append(c.endpoint.address).append(' ').append(c.endpoint.port)
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** 시도 순서 — host 먼저. */
    fun ordered(transport: CandidateTransport): List<IceCandidate> =
        candidates.filter { it.transport == transport }.sortedByDescending { it.kind.priority }

    // data class + ByteArray → equals/hashCode 직접 구현
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CandidateBundle) return false
        return token.contentEquals(other.token) && candidates == other.candidates &&
            fastUdp == other.fastUdp
    }

    override fun hashCode(): Int =
        31 * (31 * token.contentHashCode() + candidates.hashCode()) + fastUdp.hashCode()

    companion object {
        const val TOKEN_LENGTH = 16
        private const val VERSION = "v1"
        /** 플래그 줄의 낱말. 모르는 낱말은 무시하므로 앞으로 더 붙어도 구버전이 안 깨진다. */
        private const val FLAG_FAST_UDP = "fastudp"
        private val rng = SecureRandom()

        fun randomToken(): ByteArray = ByteArray(TOKEN_LENGTH).also { rng.nextBytes(it) }

        fun parse(data: ByteArray): CandidateBundle? {
            val text = runCatching { String(data, Charsets.UTF_8) }.getOrNull() ?: return null
            var token: ByteArray? = null
            var fastUdp = false
            val out = ArrayList<IceCandidate>()

            for (raw in text.split('\n')) {
                val f = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (f.isEmpty()) continue

                if (f[0] == VERSION && f.size >= 2) {
                    token = hexToBytes(f[1])
                } else if (f[0] == "f") {
                    // 한 줄에 여러 낱말이 올 수 있고, 모르는 낱말은 그냥 지나간다.
                    if (FLAG_FAST_UDP in f.drop(1)) fastUdp = true
                } else if (f[0] == "c" && f.size >= 5) {
                    val t = CandidateTransport.from(f[1]) ?: continue   // 모르는 값은 건너뜀
                    val k = CandidateKind.from(f[2]) ?: continue
                    val port = f[4].toIntOrNull() ?: continue
                    out.add(IceCandidate(t, k, Ipv4Endpoint(f[3], port)))
                }
                // 그 밖의 줄은 미래 확장 — 무시.
            }

            val tk = token ?: return null
            if (tk.size != TOKEN_LENGTH) return null
            return CandidateBundle(tk, out, fastUdp)
        }

        private fun hexToBytes(s: String): ByteArray? {
            if (s.length % 2 != 0) return null
            val out = ByteArray(s.length / 2)
            for (i in out.indices) {
                out[i] = s.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: return null
            }
            return out
        }
    }
}

object LocalCandidates {
    /**
     * 상대가 도달할 수 없어 후보로 실어봐야 펀칭 시도만 낭비하는 주소들.
     *  - 169.254/16 링크로컬
     *  - **192.0.0/24** — 464XLAT의 CLAT 인터페이스 주소(RFC 7335). 셀룰러에서 잡히는데
     *    단말 내부 전용이라 밖에서 절대 못 온다. 실기기 로그에서 Mac이 여기로 계속 쐈다.
     *  - 192.0.2/24, 198.51.100/24, 203.0.113/24 — 문서화용 예약 대역
     */
    private val UNREACHABLE_PREFIXES = listOf(
        "169.254.", "192.0.0.", "192.0.2.", "198.51.100.", "203.0.113.",
    )

    /** 이 기기의 로컬 IPv4 전부 (루프백·도달불가 대역 제외). Wi-Fi와 셀룰러가 함께면 여러 개. */
    /**
     * 이 기기의 host 후보 주소들.
     *
     * [deprioritized]에 든 주소는 **맨 뒤로 민다.** 셀룰러 인터페이스의 주소가 그 대상이다 —
     * 캐리어 CGNAT 사설 주소(10.x)라 상대가 닿을 확률이 사실상 없는데, 앞에 실리면 상대가
     * 그걸 먼저 두드리며 연결 시간을 통째로 버린다. 지우지는 않는다: USB 테더링처럼
     * 셀룰러 망 주소로 정말 닿는 구성이 있고, 그때는 마지막 폴백으로 남아야 한다.
     */
    fun hostAddresses(deprioritized: Set<String> = emptySet()): List<String> {
        val out = ArrayList<String>()
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    val ip = addr.hostAddress ?: continue
                    if (UNREACHABLE_PREFIXES.any { ip.startsWith(it) }) continue
                    if (ip !in out) out.add(ip)
                }
            }
        }
        // 안정 정렬 — 같은 등급 안에서는 원래(인터페이스 열거) 순서를 지킨다.
        return out.sortedBy { if (it in deprioritized) 1 else 0 }
    }
}
