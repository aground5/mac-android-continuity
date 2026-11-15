package com.peri.display.share

import android.media.MediaCodecInfo.CodecProfileLevel as PL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 코덱 레벨 고르기 — **표를 옮겨 적다 틀리는 걸 막는 것이 전부다.**
 *
 * 레벨은 "이 스트림을 풀려면 이만한 디코더가 필요하다"는 선언이라 크게 부르면 거짓말이 되고,
 * 작게 부르면 `configure()`가 실패한다. 실기기 두 대의 **실제 제원**으로 못박아 둔다.
 */
class LevelPickerTest {

    /** 기기가 흔히 광고하는 범위 — 표에 있는 건 다 있다고 본다. */
    private val allHevc = HEVC_LEVELS_FOR_TEST
    private val allAvc = AVC_LEVELS_FOR_TEST

    @Test
    fun `퀄컴 폰 590x1280@30 은 HEVC 레벨 3_1`() {
        // 755,200 샘플/장 ≤ 983,040(L3.1), 22,656,000 샘플/초 ≤ 33,177,600
        // 예전엔 여기에 High tier 6.2(8K용)를 적고 있었다.
        assertEquals(
            PL.HEVCMainTierLevel31,
            pickLevel(hevc = true, width = 590, height = 1280, fps = 30, advertised = allHevc),
        )
    }

    @Test
    fun `미디어텍 태블릿 800x1280@30 은 HEVC 레벨 4`() {
        // 1,024,000 샘플/장 > 983,040 이라 3.1로는 못 담는다 → 4(2,228,224)
        assertEquals(
            PL.HEVCMainTierLevel4,
            pickLevel(hevc = true, width = 800, height = 1280, fps = 30, advertised = allHevc),
        )
    }

    @Test
    fun `H_264는 매크로블록으로 센다`() {
        // 800x1280 → 50x80 = 4,000 MB. L3.1의 3,600을 넘으므로 3.2(5,120).
        assertEquals(
            PL.AVCLevel32,
            pickLevel(hevc = false, width = 800, height = 1280, fps = 30, advertised = allAvc),
        )
    }

    @Test
    fun `프레임레이트가 오르면 레벨도 오른다`() {
        // 같은 크기라도 초당 샘플이 상한을 넘으면 다음 레벨로 간다.
        // 800x1280 = 1,024,000/장. @60 = 61,440,000/초 ≤ 66,846,720 → 여전히 L4.
        assertEquals(
            PL.HEVCMainTierLevel4,
            pickLevel(hevc = true, width = 800, height = 1280, fps = 60, advertised = allHevc),
        )
        // @120 = 122,880,000/초 > 66,846,720 → L4.1(133,693,440)
        assertEquals(
            PL.HEVCMainTierLevel41,
            pickLevel(hevc = true, width = 800, height = 1280, fps = 120, advertised = allHevc),
        )
    }

    @Test
    fun `광고하지 않는 레벨은 건너뛴다`() {
        // 3.1이면 담기는 제원이지만 인코더가 4부터 광고한다면 4를 골라야 한다.
        val fromLevel4 = setOf(PL.HEVCMainTierLevel4, PL.HEVCMainTierLevel41)
        assertEquals(
            PL.HEVCMainTierLevel4,
            pickLevel(hevc = true, width = 590, height = 1280, fps = 30, advertised = fromLevel4),
        )
    }

    @Test
    fun `담을 레벨이 없으면 프로파일도 포기한다`() {
        // 8K를 낮은 레벨만 광고하는 인코더에 물리면 담을 곳이 없다. 담지도 못할 레벨을
        // 적느니 프로파일을 통째로 인코더 기본값에 맡기는 게 맞다(호출부가 그렇게 한다).
        val low = setOf(PL.HEVCMainTierLevel1, PL.HEVCMainTierLevel2)
        assertNull(
            pickLevel(hevc = true, width = 7680, height = 4320, fps = 60, advertised = low),
        )
    }
}

/**
 * 벤더 LTR 이름 고르기 — **하드코딩하지 않기로 한 대가로 생긴 규칙**의 시험대.
 *
 * 표준 API에 LTR이 없으니 벤더 네임스페이스에서 찾을 수밖에 없는데, 이름을 박아 두면
 * 모르는 칩에서 조용히 죽는다. 그래서 기기가 열거해 주는 목록에서 고르는데, 고르는 규칙이
 * 틀리면 **더 나쁘게 죽는다**: 엉뚱한 키에 슬롯 수를 쓰고는 LTR이 켜졌다고 믿게 된다.
 */
class EncoderCapsTest {

    /**
     * **실기기가 실제로 열거한 목록**(스냅드래곤, 알파벳순 그대로).
     *
     * 이 목록은 지어낸 게 아니라 로그에서 그대로 옮겼다. 앞서 지어낸 목록으로 테스트했을 때
     * 미끼 이름을 `caps-ltr.max-frames`로 적었는데 실제로는 `max-count`였고, 그 한 글자
     * 차이로 매처가 읽기 전용 값을 골라 LTR이 통째로 무효가 됐다. 추측한 이름으로 만든
     * 테스트는 통과해도 아무것도 지켜 주지 않는다.
     */
    private val qualcomm = listOf(
        "vendor.qti-ext-enc-caps-ltr.max-count",
        "vendor.qti-ext-enc-info-ltr.counter",
        "vendor.qti-ext-enc-info-ltr.ltr-use-mark",
        "vendor.qti-ext-enc-ltr-count.num-ltr-frames",
        "vendor.qti-ext-enc-ltr.mark-frame",
        "vendor.qti-ext-enc-ltr.use-frame",
    )

    private val allInt: (String) -> Boolean = { true }

    @Test
    fun `실기기 목록에서 쓰기 가능한 셋만 고른다`() {
        val k = matchLtrKeys(qualcomm, allInt)!!
        assertEquals("vendor.qti-ext-enc-ltr-count.num-ltr-frames", k.count)
        assertEquals("vendor.qti-ext-enc-ltr.mark-frame", k.mark)
        assertEquals("vendor.qti-ext-enc-ltr.use-frame", k.use)
    }

    @Test
    fun `인코더가 되알려 주는 자리를 따로 찾는다`() {
        // 새김이 **어느 프레임에** 붙었는지는 이쪽으로만 알 수 있다. 우리가 쓰는 mark를
        // 되읽으면 우리 값이 그대로 나와서 확인이 언제나 성공해 버린다.
        val k = matchLtrKeys(qualcomm, allInt)!!
        assertEquals("vendor.qti-ext-enc-info-ltr.ltr-use-mark", k.info)
    }

    @Test
    fun `읽기 전용 능력값을 슬롯 수로 착각하지 않는다`() {
        // ★ `caps-ltr.max-count`는 "최대 몇 개까지 되는가"를 **알려 주는** 값인데 이름에
        // "count"가 들어 있다. 거기에 슬롯 수를 쓰면 아무 일도 일어나지 않는다.
        val noRealCount = qualcomm.filter { "num-ltr-frames" !in it }
        assertNull("슬롯 수를 쓸 곳이 없으면 LTR은 성립하지 않는다", matchLtrKeys(noRealCount, allInt))
    }

    @Test
    fun `계측 필드를 새김이나 참조로 착각하지 않는다`() {
        // ★★ 실기기를 무너뜨린 바로 그 이름. `info-ltr.ltr-use-mark`는 "use"와 "mark"를
        // 둘 다 담고 있어서 새김·참조 양쪽에 잡혔고, 심지어 우리가 쓴 값을 되비추기까지 해서
        // **확인도 성공한 것처럼 보였다.** 로그는 완벽했고 복구는 하나도 일어나지 않았다.
        val onlyInfo = qualcomm.filter { "-ltr.mark-frame" !in it && "-ltr.use-frame" !in it }
        assertNull(matchLtrKeys(onlyInfo, allInt))
    }

    @Test
    fun `규약을 안 따르는 이름은 짐작하지 않고 접는다`() {
        // 뜻이 통해 보여도 꼬리가 다르면 고르지 않는다. 짐작이 바로 실기기를 무너뜨린
        // 방식이고, 규약 밖의 칩은 LTR 없이 인트라 리프레시로 가는 게 안전하다.
        val plausible = listOf(
            "vendor.acme-enc-ltr.count",
            "vendor.acme-enc-ltr.mark",
            "vendor.acme-enc-ltr.use",
            "vendor.acme-enc-info-ltr.response",
        )
        assertNull(matchLtrKeys(plausible, allInt))
    }

    @Test
    fun `mark만 있고 use가 없으면 성립하지 않는다`() {
        // 새기기만 하고 참조할 수 없으면 앵커는 장식이다. 반쪽으로 켜 두면 "새겼다고
        // 믿지만 복구는 영영 안 되는" 상태가 된다.
        val noUse = qualcomm.filter { "-ltr.use-frame" !in it }
        assertNull(matchLtrKeys(noUse, allInt))
    }

    @Test
    fun `정수를 안 받는 이름은 후보에서 뺀다`() {
        // 이름이 맞아도 타입이 다르면 우리가 쓸 수 없고, 확인 없이 던지면 예외가 난다.
        val markIsNotInt: (String) -> Boolean = { "-ltr.mark-frame" !in it }
        assertNull(matchLtrKeys(qualcomm, markIsNotInt))
    }

    @Test
    fun `LTR이 없는 기기에서는 조용히 없다고 답한다`() {
        assertNull(matchLtrKeys(listOf("vendor.foo.bar", "vendor.baz.qux"), allInt))
        assertNull(matchLtrKeys(emptyList(), allInt))
    }

    @Test
    fun `규약을 따르면 미끼가 섞여도 흔들리지 않는다`() {
        // 꼬리로 찾으므로 낱말이 겹치는 이름이 더 있어도 상관없다.
        val noisy = qualcomm + listOf("vendor.qti-ext-enc-ltr-fake.use-mark-count")
        val k = matchLtrKeys(noisy, allInt)!!
        assertEquals("vendor.qti-ext-enc-ltr.mark-frame", k.mark)
        assertEquals("vendor.qti-ext-enc-ltr.use-frame", k.use)
    }

    @Test
    fun `응답 자리가 없으면 성립하지 않는다`() {
        // 응답이 없으면 새김이 어느 프레임에 붙었는지 알 수 없다. 퀄컴 문서도 LTR 지원
        // 판정을 네 개 전부의 존재로 하라고 적고 있다.
        val noResponse = qualcomm.filter { "info-ltr.ltr-use-mark" !in it }
        assertNull(matchLtrKeys(noResponse, allInt))
    }

    @Test
    fun `접두가 달라도 규약만 같으면 찾는다`() {
        // 미디어텍·엑시노스·WebRTC 공통 네임스페이스는 접두만 다르고 꼬리가 같다.
        // 접두를 나열하지 않으므로 우리가 모르는 칩도 규약만 따르면 그냥 된다.
        for (ns in listOf("vendor.mtk", "vendor.sec", "vendor.rtc", "vendor.brandnew")) {
            val k = matchLtrKeys(
                listOf(
                    "$ns-ext-enc-ltr-count.num-ltr-frames",
                    "$ns-ext-enc-ltr.mark-frame",
                    "$ns-ext-enc-ltr.use-frame",
                    "$ns-ext-enc-info-ltr.ltr-use-mark",
                ),
                allInt,
            )
            assertEquals("$ns-ext-enc-ltr.mark-frame", k!!.mark)
        }
    }

    @Test
    fun `네임스페이스를 섞지 않는다`() {
        // ★ 한 기기가 두 네임스페이스를 같이 노출할 수 있다. 새김은 이쪽, 참조는 저쪽으로
        // 던지면 서로 다른 구현을 반씩 건드리게 된다 — 네 개가 같은 접두일 때만 성립한다.
        val split = listOf(
            "vendor.rtc-ext-enc-ltr-count.num-ltr-frames",
            "vendor.mtk-ext-enc-ltr.mark-frame",
            "vendor.mtk-ext-enc-ltr.use-frame",
            "vendor.rtc-ext-enc-info-ltr.ltr-use-mark",
        )
        assertNull("접두가 갈리면 성립으로 보지 않는다", matchLtrKeys(split, allInt))
    }

    @Test
    fun `대소문자를 가리지 않고 기기가 쓴 그대로 돌려준다`() {
        // 돌려주는 이름은 반드시 기기가 열거한 문자열 그대로여야 한다 — 우리가 정규화한
        // 모양으로 던지면 그 기기에서 안 먹는다.
        val shouty = listOf(
            "VENDOR.QTI-EXT-ENC-LTR-COUNT.NUM-LTR-FRAMES",
            "VENDOR.QTI-EXT-ENC-LTR.MARK-FRAME",
            "VENDOR.QTI-EXT-ENC-LTR.USE-FRAME",
            "VENDOR.QTI-EXT-ENC-INFO-LTR.LTR-USE-MARK",
        )
        assertEquals("VENDOR.QTI-EXT-ENC-LTR.MARK-FRAME", matchLtrKeys(shouty, allInt)!!.mark)
    }
}
