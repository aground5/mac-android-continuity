package com.peri.display.crypto

import com.peri.display.transport.UdpType
import com.peri.display.transport.WireFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 데이터그램 세대 키 상호운용 — **기대값은 Mac 구현이 낸 것**이다.
 *
 * 이 키가 어긋나면 UDP로 오는 모든 것이 조용히 안 열린다. 증상은 "붙기는 했는데 화면이
 * 검다"이고, 어느 쪽 잘못인지 로그만으로는 알 수 없다 — 그래서 여기서 고정한다.
 *
 * 세대 키가 왜 따로 있는지는 [PeriDatagramKeys] 주석 참조. 요약하면 둘이다: `UdpOpen` 하나로
 * 양쪽이 즉시 같은 키에 도달하고(0-RTT), 채널을 닫았다 다시 열 때 frame_id가 되감겨도
 * (키, nonce)가 겹치지 않는다.
 *
 * 벡터 생성:
 * ```
 * swiftc -O "MacApp/.../Crypto/PeriCrypto.swift" main.swift && ./a.out
 * ```
 * `main.swift`는 `PeriCryptoSession.sts(ikm:).datagram(channel:)`의 sender(=m2a)로 봉인한다.
 */
class PeriDatagramKeysTest {

    /** `ikm = k_ECDH ‖ k_PQC`. STS는 salt를 비우므로 이 값 하나가 세션을 정한다. */
    private val ecdh = ByteArray(32) { it.toByte() }
    private val kem = ByteArray(32) { (it + 32).toByte() }

    /** Mac이 세대마다 새로 뽑는 16바이트. 키 유도의 소금이자 HELLO의 평문 접두다. */
    private val channelId = ByteArray(16) { (0xA0 + it).toByte() }
    private val otherId = ByteArray(16) { (0x10 + it).toByte() }

    private fun keys(id: ByteArray = channelId): PeriDatagramKeys =
        PeriSts.derive(ecdh, kem).session.datagram(id)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    /**
     * ★ Mac이 m2a 세대 키로 봉인한 것을 우리가 연다. 이게 통과하면 마스터 유도·소금·라벨이
     * 세 개 다 일치한다는 뜻이다 — 하나라도 어긋나면 태그 검증에서 죽는다.
     */
    @Test
    fun `Mac이 세대 키로 봉인한 것을 연다`() {
        val nonce = unhex("000000000000000000000000")
        val body = unhex("EBE6161E1D58A12D80D9B5E224D3A80824D73C3F3B077AE7106C41217575E329")
        assertArrayEquals(channelId, keys().open(nonce, body))
    }

    /** 영상 nonce는 frame_id에서 유도돼 선에 실리지 않는다 — 그 규칙도 세대 키에서 같다. */
    @Test
    fun `Mac이 세대 키로 봉인한 영상 프레임을 연다`() {
        val body = unhex("E5D4FEEA56D7BB220050B9B0985AEFF1C8E7DE23EB0AFFD9890B6E7A78")
        assertEquals("peri-datagram", String(keys().openVideo(body, 7), Charsets.UTF_8))
    }

    /**
     * ★ **세대가 다르면 키가 다르다** — 이 검사가 이 설계의 존재 이유다.
     *
     * 채널을 닫았다 다시 열면 frame_id가 0부터 다시 도는데, 키가 그대로면 같은 (키, nonce)로
     * 두 번 봉인하게 되고 GCM은 그 자리에서 무너진다. 소금이 실제로 키를 가르는지 봐야 한다.
     */
    @Test
    fun `세대 id가 다르면 다른 키가 나온다`() {
        val nonce = unhex("000000000000000000000000")
        val body = unhex("EBE6161E1D58A12D80D9B5E224D3A80824D73C3F3B077AE7106C41217575E329")
        assertThrows(javax.crypto.AEADBadTagException::class.java) {
            keys(otherId).open(nonce, body)
        }
        // Mac이 다른 세대 id로 같은 평문을 봉인한 값 — 우리도 그걸 열 수 있어야 한다.
        assertArrayEquals(
            channelId,
            keys(otherId).open(
                nonce, unhex("D10838033D14BD2E7615591A7D98794A2FFB7D65D34F69F329274D5096D29AF8"),
            ),
        )
    }

    /**
     * 세대 키는 세션 키와도 갈려 있다. 같은 재료에서 나오지만 라벨이 다르므로 HKDF가
     * 서로를 유도하지 못한다 — 한쪽이 새도 다른 쪽은 무사하다.
     */
    @Test
    fun `세션 키로는 세대 봉인이 안 열린다`() {
        val nonce = unhex("000000000000000000000000")
        val session = PeriSts.derive(ecdh, kem).session
        // Mac이 **세션** 키로 봉인한 값 — 세션 키로는 열리고,
        assertArrayEquals(
            channelId,
            session.open(nonce, unhex("36045FEF813F1B6CC303457F4027E3030692928D7385D3FB692EE8F854B03860")),
        )
        // 같은 값을 세대 키로는 못 연다.
        assertThrows(javax.crypto.AEADBadTagException::class.java) {
            keys().open(nonce, unhex("36045FEF813F1B6CC303457F4027E3030692928D7385D3FB692EE8F854B03860"))
        }
    }

    /**
     * 방향이 갈려 있는가. 한 키를 양방향에 쓰면 두 방향이 같은 (키, nonce)를 만날 수 있고,
     * 라벨을 복사·붙여넣기하다 둘을 같게 만드는 건 흔한 사고다.
     */
    @Test
    fun `송신 키와 수신 키가 다르다`() {
        val k = keys()
        val (nonce, body) = k.seal(channelId)
        // 우리가 봉인한 것을 우리가 열 수 있으면 두 방향이 같은 키라는 뜻이다.
        assertThrows(javax.crypto.AEADBadTagException::class.java) { k.open(nonce, body) }
    }

    /** 같은 키에서 두 번 봉인하면 nonce가 앞으로 간다 — 카운터가 실제로 돈다는 확인. */
    @Test
    fun `봉인할 때마다 nonce가 하나씩 오른다`() {
        val k = keys()
        assertEquals("000000000000000000000000", hex(k.seal(channelId).first))
        assertEquals("000000000000000000000001", hex(k.seal(channelId).first))
    }

    /**
     * HELLO 데이터그램의 자리 배치. `[1B 0][16B 세대 id][12B nonce][sealed]`.
     *
     * Mac의 `WireFormat.parseUdpHello`가 같은 오프셋을 쓴다 — 접두 길이는 양쪽이
     * `DATAGRAM_CHANNEL_ID_BYTES`(Swift `datagramChannelIDBytes`)라는 같은 이름의 상수로
     * 들고 있고, **그 값이 갈리면 이 검사로는 안 잡힌다.** 한쪽만 바꾸지 말 것.
     */
    @Test
    fun `HELLO 데이터그램의 자리 배치`() {
        assertEquals(16, DATAGRAM_CHANNEL_ID_BYTES)
        val k = keys()
        val (nonce, body) = k.seal(channelId)
        val d = WireFormat.udpHelloDatagram(channelId, nonce, body)

        assertEquals(UdpType.HELLO.raw, d[0].toInt() and 0xFF)
        assertArrayEquals(channelId, d.copyOfRange(1, 17))
        assertArrayEquals(nonce, d.copyOfRange(17, 29))
        assertArrayEquals(body, d.copyOfRange(29, d.size))
        // 평문 접두와 봉인 안의 값이 같아야 Mac이 접두 바꿔치기를 걸러낼 수 있다.
        assertEquals(1 + 16 + 12 + 16 + channelId.size, d.size)
    }

    /** 세대 id 길이가 바뀌면 키가 통째로 달라진다 — 상수를 한쪽만 고치면 여기서 티가 난다. */
    @Test
    fun `세대 id 길이가 다르면 다른 키가 나온다`() {
        val short = channelId.copyOfRange(0, 8)
        assertNotEquals(hex(keys(channelId).seal(short).second), hex(keys(short).seal(short).second))
    }
}
