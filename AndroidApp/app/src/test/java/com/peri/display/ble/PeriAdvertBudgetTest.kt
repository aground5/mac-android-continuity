package com.peri.display.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 광고 한 패킷이 31바이트 안에 드는가 — **세어서** 확인한다.
 *
 * ## 왜 검사로 만들었나
 *
 * 예산이 주석에만 있었고 그 덧셈이 틀려 있었다. `setConnectable(true)` 광고에 스택이 강제로
 * 붙이는 **AD Flags 3바이트**가 빠져 있었는데, 그 필드는 우리 `AdvertiseData.Builder`
 * 어디에도 안 나타나므로 코드를 아무리 봐도 안 보인다.
 *
 * 결과: 호출 응답 광고가 34바이트가 되어 `ADVERTISE_FAILED_DATA_TOO_LARGE`로 **조용히
 * 거절**됐다. 예외도 로그도 없고, Mac 쪽에서는 그냥 "폰이 응답을 안 한다"로만 보인다.
 * 상시 광고는 우연히 31바이트로 딱 맞아서 멀쩡했기 때문에 더 안 보였다.
 *
 * 이건 세면 알 수 있는 종류의 사실이다. 그래서 세는 일을 사람이 아니라 여기가 한다.
 *
 * ## 여유가 없다는 것을 함께 고정한다
 *
 * 상시 광고도 **정확히 31바이트**다. 여유가 0이라는 사실 자체가 지켜야 할 성질이라
 * "≤ 31"이 아니라 "== 31"로 못박는다 — 어딘가 한 바이트가 늘면 그 자리에서 알아야 한다.
 */
class PeriAdvertBudgetTest {

    /** 우리가 안 쓰는데 스택이 붙이는 몫 + 서비스 UUID + 제조사 데이터 머리. */
    private val fixed = PeriAdvertToken.FLAGS_FIELD_BYTES +
        PeriAdvertToken.SERVICE_UUID_FIELD_BYTES +
        PeriAdvertToken.MANUFACTURER_HEADER_BYTES

    private fun totalWith(body: ByteArray) = fixed + body.size

    @Test
    fun `예산 상수끼리 앞뒤가 맞는다`() {
        assertEquals(31, PeriAdvertToken.LEGACY_ADVERT_LIMIT)
        assertEquals(6, PeriAdvertToken.MAX_MANUFACTURER_BODY)
        assertEquals(5, PeriAdvertToken.RESPONSE_BYTES)
        assertEquals(
            PeriAdvertToken.LEGACY_ADVERT_LIMIT,
            fixed + PeriAdvertToken.MAX_MANUFACTURER_BODY,
        )
    }

    @Test
    fun `상시 신원 광고가 한도에 정확히 맞는다`() {
        val body = PeriAdvertToken.payload(ByteArray(32) { it.toByte() }, PeriAdvertToken.FLAG_ICE_RESTART)
        assertEquals("본문 = 버전 1 + 토큰 4 + 플래그 1", 6, body.size)
        assertEquals(PeriAdvertToken.LEGACY_ADVERT_LIMIT, totalWith(body))
    }

    @Test
    fun `호출 응답 광고가 한도를 넘지 않는다`() {
        // ★ 이 검사가 실제 버그를 잡는 자리다. 8바이트를 싣던 때 여기서 34가 나온다.
        val response = PeriPageToken.response(
            linkKey = ByteArray(32) { it.toByte() },
            nonce = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
        )
        assertEquals(PeriAdvertToken.RESPONSE_BYTES, response.size)
        val body = PeriAdvertToken.responsePayload(response)
        assertEquals("본문 = 버전 1 + 응답 5", 6, body.size)
        assertTrue(
            "응답 광고가 ${totalWith(body)}B — 한도 ${PeriAdvertToken.LEGACY_ADVERT_LIMIT}B 초과",
            totalWith(body) <= PeriAdvertToken.LEGACY_ADVERT_LIMIT,
        )
    }

    @Test
    fun `두 모드의 본문 길이가 같다`() {
        // 같아야 할 이유가 있어서가 아니라, 예산이 하나뿐이라 둘 다 그 자리를 꽉 채우기
        // 때문이다. 한쪽만 늘리는 변경은 반드시 다른 쪽 예산을 건드린다.
        val beacon = PeriAdvertToken.payload(ByteArray(32), 0)
        val response = PeriAdvertToken.responsePayload(ByteArray(PeriAdvertToken.RESPONSE_BYTES))
        assertEquals(beacon.size, response.size)
        assertEquals(PeriAdvertToken.MAX_MANUFACTURER_BODY, beacon.size)
    }
}
