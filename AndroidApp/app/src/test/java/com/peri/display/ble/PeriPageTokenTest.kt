package com.peri.display.ble

import com.peri.display.crypto.TrustedPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 호출 토큰 상호운용 — **기대값은 Mac 구현이 낸 것**이다.
 *
 * 이 벡터가 어긋나면 Mac이 부르는 걸 이 기기가 못 알아듣는다는 뜻이고, 그 증상은 실기기에서
 * "가끔 안 깨어난다"로 나타난다. 두 구현이 같은 바이트를 낸다는 건 여기서만 확인할 수 있다
 * (`IceCandidateInteropTest`와 같은 취지).
 *
 * 벡터 생성:
 * ```
 * swiftc -O "MacApp/.../BLE/PeriPageToken.swift" main.swift && ./a.out
 * ```
 */
class PeriPageTokenTest {

    /** 페어링이 남긴 공유 비밀. **이걸 가진 쪽만 이 기기를 부를 수 있다.** */
    private val linkKey = ByteArray(32) { it.toByte() }

    /** 신원 확인은 **어느 링크 키로 맞았는가**로 한다 — 이 값은 토큰에 안 들어간다. */
    private val macPub = ByteArray(32) { (0xA0 + it).toByte() }

    /** (nonce, epoch, Mac `PeriPageToken.uuid(...)`가 낸 값). */
    private val vectors = listOf(
        Triple(byteArrayOf(0, 0, 0, 0), 0L, "50455249-0000-0000-25AF-1D0E87BB17FD"),
        Triple(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
               175_400_000L, "50455249-DEAD-BEEF-D4B2-7B2803003852"),
        Triple(byteArrayOf(-1, -1, -1, -1), 1L, "50455249-FFFF-FFFF-E456-74C60074BDD6"),
    )

    /** 그 창이 '지금'인 것처럼 보이게 하는 시각. */
    private fun at(epoch: Long) = epoch * PeriPageToken.EPOCH_MS

    private fun mac(pub: ByteArray = macPub) = TrustedPeer(pub, "Mac", 0, 0)

    /** 어떤 Mac에 어떤 링크 키가 붙어 있는지 — 목록에 없으면 null(= 부를 자격이 없다). */
    private fun keys(vararg e: Pair<ByteArray, ByteArray>): (TrustedPeer) -> ByteArray? =
        { p -> e.firstOrNull { it.first.contentEquals(p.pub) }?.second }

    private val mine = keys(macPub to linkKey)

    @Test
    fun `Mac이 낸 UUID와 바이트가 같다`() {
        for ((nonce, epoch, expected) in vectors) {
            val dk = PeriPageToken.deriveKey(linkKey, nonce)
            val otp = PeriPageToken.otp(dk, epoch)
            val tail = ByteArray(8)
            var bits = UUID.fromString(expected).leastSignificantBits
            for (i in 7 downTo 0) { tail[i] = (bits and 0xFF).toByte(); bits = bits ushr 8 }
            assertEquals(
                "nonce=${nonce.joinToString("") { "%02x".format(it) }} epoch=$epoch",
                tail.joinToString("") { "%02x".format(it) },
                otp.joinToString("") { "%02x".format(it) },
            )
        }
    }

    @Test
    fun `나를 부르는 광고면 누가 부르는지까지 나온다`() {
        for ((_, epoch, uuid) in vectors) {
            val who = PeriPageToken.resolve(
                UUID.fromString(uuid), listOf(mac()), mine, at(epoch),
            )
            assertNotNull("Mac이 만든 UUID를 못 알아봤다", who)
            assertEquals("Mac", who!!.mac.name)
        }
    }

    @Test
    fun `시계가 한 창 어긋나도 알아본다`() {
        // ±1을 여는 이유. 이게 없으면 정상 호출이 시계 오차로 조용히 무시된다.
        val (_, epoch, uuid) = vectors[1]
        for (skew in listOf(-1L, 0L, 1L)) {
            assertNotNull(
                "창 $skew 에서 못 알아봤다",
                PeriPageToken.resolve(UUID.fromString(uuid), listOf(mac()), mine, at(epoch + skew)),
            )
        }
    }

    @Test
    fun `창을 벗어난 재전송은 안 깨운다`() {
        // ★ epoch이 있는 이유. 없으면 한 번 캡처한 광고를 언제든 재생해 "그 폰이 여기 있나"를
        // 물을 수 있고, 그건 링크 키를 모르는 공격자에게도 열린 존재 확인 오라클이다.
        val (_, epoch, uuid) = vectors[1]
        for (skew in listOf(-2L, 2L, 100L, 8_640L)) {
            assertNull(
                "창 $skew 의 재전송이 통과했다",
                PeriPageToken.resolve(UUID.fromString(uuid), listOf(mac()), mine, at(epoch + skew)),
            )
        }
    }

    @Test
    fun `여러 Mac 중에서 부른 쪽을 짚는다`() {
        val (_, epoch, uuid) = vectors[1]
        val otherPub = ByteArray(32) { (0x10 + it).toByte() }
        val other = mac(otherPub)
        val who = PeriPageToken.resolve(
            UUID.fromString(uuid), listOf(other, mac()),
            keys(otherPub to ByteArray(32) { 0x5A }, macPub to linkKey), at(epoch),
        )
        assertEquals("Mac", who?.mac?.name)
    }

    @Test
    fun `링크 키가 다르면 안 깨운다`() {
        // ★ 이게 v2의 핵심이다. 예전엔 유도 재료가 폰 **공개키**라, 그 값을 한 번이라도 본
        // 사람이면 누구나 이 기기를 깨울 수 있었다. 이제는 페어링이 남긴 비밀을 가진 쪽만이다.
        val (_, epoch, uuid) = vectors[1]
        val wrong = keys(macPub to ByteArray(32) { (0x77 + it).toByte() })
        assertNull(PeriPageToken.resolve(UUID.fromString(uuid), listOf(mac()), wrong, at(epoch)))
    }

    @Test
    fun `링크 키가 없는 Mac은 건너뛴다`() {
        // 케이블로만 신뢰를 세운 Mac이 이렇다 — PAKE를 안 거쳐 공유 비밀이 없다.
        // 여기서 죽지 않고 조용히 지나가야 다른 후보를 마저 본다.
        val (_, epoch, uuid) = vectors[1]
        assertNull(PeriPageToken.resolve(UUID.fromString(uuid), listOf(mac()), { null }, at(epoch)))
    }

    @Test
    fun `모르는 Mac이 부르면 안 깨운다`() {
        // "모르는 Mac" = **링크 키를 나눠 갖지 않은 상대**다. 공개키는 판정에 안 쓰이므로,
        // 낯섦을 정의하는 건 오직 그 32바이트를 공유하지 않는다는 사실뿐이다.
        val (_, epoch, uuid) = vectors[1]
        val strangerPub = ByteArray(32) { (0x33 + it).toByte() }
        assertNull(
            PeriPageToken.resolve(
                UUID.fromString(uuid), listOf(mac(strangerPub)),
                keys(strangerPub to ByteArray(32) { (0x91 + it).toByte() }), at(epoch),
            ),
        )
    }

    @Test
    fun `접두가 다르면 우리 것이 아니다`() {
        assertNull(
            PeriPageToken.resolve(
                UUID.fromString("00000000-DEAD-BEEF-9712-A619508333A2"), listOf(mac()), mine,
            ),
        )
    }

    @Test
    fun `nonce가 바뀌면 값이 바뀐다 — 불연결성`() {
        // 같은 상대끼리라도 광고마다 달라 보여야 한다. 안 그러면 고정 식별자가 된다.
        val a = PeriPageToken.otp(PeriPageToken.deriveKey(linkKey, vectors[0].first), 7)
        val b = PeriPageToken.otp(PeriPageToken.deriveKey(linkKey, vectors[1].first), 7)
        assertEquals(false, a.contentEquals(b))
    }

    @Test
    fun `창이 바뀌면 값이 바뀐다 — 신선도`() {
        val dk = PeriPageToken.deriveKey(linkKey, vectors[0].first)
        assertEquals(false, PeriPageToken.otp(dk, 7).contentEquals(PeriPageToken.otp(dk, 8)))
    }

    /**
     * ★ **다른 Mac 공개키를 붙여도 판정이 안 바뀐다** — 공개키가 토큰에 안 들어가기 때문이다.
     *
     * 판정을 가르는 건 링크 키 하나뿐이고, 링크 키는 상대별이라 그것으로 이미 누가 부르는지가
     * 정해진다. 이 검사가 깨지면 공개키가 어딘가로 다시 새어 들어갔다는 뜻이다.
     */
    @Test
    fun `Mac 공개키는 토큰에 영향을 주지 않는다`() {
        val (_, epoch, uuid) = vectors[1]
        val otherPub = ByteArray(32) { (0x5C + it).toByte() }
        val who = PeriPageToken.resolve(
            UUID.fromString(uuid), listOf(mac(otherPub)), keys(otherPub to linkKey), at(epoch),
        )
        assertNotNull("공개키가 달라졌다고 못 알아보면 안 된다", who)
    }

    /**
     * 응답 토큰도 Mac과 같은 바이트를 낸다 — Mac이 이걸 보고 붙는다.
     *
     * 호출 OTP(8B)보다 **짧다.** 응답은 UUID가 아니라 제조사 데이터에 실리는데 거기 남는
     * 자리가 6바이트뿐이기 때문이다([PeriAdvertBudgetTest]). 그래서 같은 HMAC의 같은
     * 오프셋에서 5바이트만 뗀다.
     */
    @Test
    fun `응답 토큰이 Mac 구현과 같다`() {
        val (nonce, epoch, _) = vectors[1]
        assertEquals(5, PeriPageToken.RESPONSE_BYTES)
        assertEquals(
            "C7F688FFB8",
            PeriPageToken.response(linkKey, nonce, at(epoch))
                .joinToString("") { "%02x".format(it) }.uppercase(),
        )
    }

    /** 호출 OTP와 응답 OTP는 **같은 절단 위치**에서 길이만 다르다 — 오프셋 규칙이 하나다. */
    @Test
    fun `응답은 호출 OTP의 앞부분과 같은 자리에서 잘린다`() {
        val (nonce, epoch, _) = vectors[1]
        val dk = PeriPageToken.responseKey(linkKey, nonce)
        val long = PeriPageToken.otp(dk, epoch, PeriPageToken.OTP_BYTES)
        val short = PeriPageToken.otp(dk, epoch, PeriPageToken.RESPONSE_BYTES)
        assertEquals(long.take(PeriPageToken.RESPONSE_BYTES), short.toList())
    }

    /**
     * ★ **동적 절단**(RFC 4226) — 잘라 낼 자리를 HMAC 자신이 정한다.
     *
     * 늘 앞 8바이트를 쓰면 그 자리에 있을지 모르는 편향이 매번 같은 자리에 그대로 나온다.
     * 오프셋이 실제로 움직이는지 봐야 이 성질이 살아 있다는 걸 알 수 있다 — 고정 절단으로
     * 되돌아가도 다른 검사는 전부 통과하기 때문이다.
     */
    @Test
    fun `절단 위치가 값에 따라 움직인다`() {
        val offsets = (0 until 200).map { e ->
            val dk = PeriPageToken.deriveKey(linkKey, vectors[0].first)
            val full = Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(dk, "HmacSHA256"))
                update(ByteBuffer.allocate(8).putLong(e.toLong()).array())
            }.doFinal()
            val off = full[full.size - 1].toInt() and 0x0F
            // 그 오프셋에서 뗀 값이 실제 otp와 같아야 한다.
            assertEquals(
                "오프셋 $off 에서 뗀 값이 다르다",
                full.copyOfRange(off, off + PeriPageToken.OTP_BYTES).toList(),
                PeriPageToken.otp(dk, e.toLong()).toList(),
            )
            off
        }
        // 200개 창이면 0~15가 고루 나온다. 하나로 고정돼 있으면 고정 절단으로 돌아간 것이다.
        assertTrue("절단 위치가 안 움직인다 — 고정 절단으로 되돌아갔다", offsets.toSet().size > 8)
    }

    @Test
    fun `필터 마스크는 접두만 덮는다`() {
        // 마스크가 뒤까지 덮으면 값이 바뀔 때마다 필터를 다시 등록해야 하고,
        // 그러면 오프로드 스캔을 쓰는 의미가 없다.
        val mask = PeriPageToken.filterMask()
        assertEquals(-0x100000000L, mask.mostSignificantBits)   // 상위 4B만 1
        assertEquals(0L, mask.leastSignificantBits)
    }
}
