package com.peri.display.ble

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * BLE 광고에 싣는 **회전 신원 토큰** — Mac이 "이미 페어링된 그 기기"임을 광고만 보고 알아채게 한다.
 *
 * 왜 지문을 그대로 싣지 않나: 광고는 누구나 스캔할 수 있고 지문은 불변이라, 그대로 실으면
 * 이 폰이 영구 추적 가능한 비콘이 된다. 대신 신원 공개키를 키로 한 HMAC을 시간 구간(epoch)마다
 * 굴린다 — 공개키를 아는 쪽(=이미 페어링한 Mac)만 대조로 해석할 수 있고, 낯선 스캐너에겐
 * 15분마다 바뀌는 4바이트일 뿐이다.
 *
 * 이 성질이 UX와도 정확히 맞물린다: 해석되면 "페어링된 기기"(기존 목록에 병합),
 * 해석 안 되면 "낯선 주변 기기"(페어링 후보).
 *
 * 광고 배치: 제조사 데이터 [2B 회사ID(LE, 시스템이 부착)][1B 버전][4B 토큰][1B 플래그].
 * 서비스 데이터를 쓰면 128비트 UUID가 16B를 또 먹어 31B 광고 한도를 넘긴다.
 * 회사ID 0xFFFF = Bluetooth SIG가 내부/시험용으로 남겨둔 값(미할당 벤더).
 *
 * 본문 6B는 **한도에 딱 맞는 값**이지 넉넉해서 고른 값이 아니다 — 아래 예산 상수 참조.
 *
 * 플래그는 **토큰 뒤에** 붙였다 — 앞에 끼우면 오프셋이 밀려 구버전 파서가 토큰을 잘못 읽는다.
 * 뒤에 두면 프리픽스가 바이트 단위로 동일해서 길이만 관용적으로 보면 양쪽 다 해석된다.
 *
 * Mac PeriAdvertToken.swift 와 대칭 — 태그·epoch 길이·바이트 순서가 반드시 같아야 한다.
 */
object PeriAdvertToken {
    const val COMPANY_ID = 0xFFFF
    const val VERSION: Byte = 1

    /**
     * **호출 응답** 광고의 버전 바이트. 본문 = `[1B 버전=2][RESPONSE_BYTES B 응답]`.
     *
     * 상시 신원 토큰(버전 1)과 갈라 둔 이유: 이건 비콘이 아니라 **한 번의 대답**이다. 부른
     * Mac만 대조할 수 있고, 이름도 싣지 않으며, 잠깐 떴다 사라진다.
     */
    const val VERSION_RESPONSE: Byte = 2

    // ── 광고 한 패킷의 예산 ──────────────────────────────────────────────────
    //
    // **주석이 아니라 상수로 둔다.** 여기 덧셈이 틀렸던 적이 있고, 그때 증상은 "응답 광고가
    // 안 올라간다"가 아니라 **아무 일도 안 일어난다**였다 — 스택은 초과분을
    // `ADVERTISE_FAILED_DATA_TOO_LARGE`로 조용히 거절하고, 호출은 그냥 응답이 없는 것처럼
    // 보인다. 세면 알 수 있는 것을 세지 않아서 생긴 버그라 세는 일을 코드로 옮겼다.
    // (Mac `PeriAdvertToken.swift`와 값이 같아야 한다.)

    /** 레거시 광고 페이로드 한도. */
    const val LEGACY_ADVERT_LIMIT = 31

    /**
     * **스택이 붙이는** AD Flags. `setConnectable(true)`이고 discoverable이면 강제로 들어간다.
     *
     * 우리 `AdvertiseData.Builder` 어디에도 안 보이기 때문에 예산에서 빠뜨리기 가장 쉬운
     * 3바이트다. 실제로 그렇게 빠뜨렸었다. `setDiscoverable(false)`로 없앨 수는 있지만
     * API 33+이고 이 앱의 minSdk는 29라 그 길은 막혀 있다.
     */
    const val FLAGS_FIELD_BYTES = 3

    /** 128비트 서비스 UUID AD: len 1 + type 1 + UUID 16. */
    const val SERVICE_UUID_FIELD_BYTES = 18

    /** 제조사 데이터 AD 머리: len 1 + type 1 + 회사ID 2. */
    const val MANUFACTURER_HEADER_BYTES = 4

    /** 그래서 제조사 데이터 **본문**에 쓸 수 있는 바이트. 지금은 6이다. */
    const val MAX_MANUFACTURER_BODY =
        LEGACY_ADVERT_LIMIT - FLAGS_FIELD_BYTES - SERVICE_UUID_FIELD_BYTES - MANUFACTURER_HEADER_BYTES

    /**
     * 응답 OTP 길이 = 본문에서 버전 1바이트를 뺀 나머지 전부.
     *
     * 8바이트를 실으려던 때가 있었는데 그러면 34바이트가 되어 광고가 아예 안 올라갔다.
     * 40비트로 충분한 이유는 [PeriPageToken.RESPONSE_BYTES] 주석 참조.
     */
    const val RESPONSE_BYTES = MAX_MANUFACTURER_BODY - 1

    /**
     * 재협상 요청 비트 — "내 IP가 바뀌었으니 후보를 다시 교환하자".
     *
     * 왜 광고로 알리나: IP가 바뀌는 순간 IP 기반 채널은 전부 못 믿는다. BLE 광고는 이미
     * 상시 돌고 있고 IP와 무관하며, Mac이 스캔만 하면 **연결 없이도** 이 비트를 본다.
     * 즉 재협상 개시에 별도 신호 왕복이 필요 없다.
     */
    const val FLAG_ICE_RESTART: Byte = 0x01

    /** 토큰 회전 주기(초). Mac이 ±1 구간까지 대조하므로 실질 허용 시계 오차는 ±15분. */
    const val EPOCH_SECONDS = 900L

    private val TAG = "PeriADV-v1".toByteArray()

    fun currentEpoch(nowMillis: Long = System.currentTimeMillis()): Long =
        nowMillis / 1000L / EPOCH_SECONDS

    /** HMAC-SHA256(신원공개키, TAG ‖ epoch(8B BE)) 앞 4바이트. */
    fun token(pub: ByteArray, epoch: Long): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pub, "HmacSHA256"))
        mac.update(TAG)
        mac.update(ByteBuffer.allocate(8).putLong(epoch).array())
        return mac.doFinal().copyOfRange(0, 4)
    }

    /** 광고에 실을 제조사 데이터 본문 (회사ID는 시스템이 앞에 붙인다). */
    fun payload(
        pub: ByteArray,
        flags: Byte = 0,
        nowMillis: Long = System.currentTimeMillis(),
    ): ByteArray = byteArrayOf(VERSION) + token(pub, currentEpoch(nowMillis)) + flags

    /** 호출 응답 본문. [response]는 [PeriPageToken.response]가 낸 [RESPONSE_BYTES]바이트. */
    fun responsePayload(response: ByteArray): ByteArray = byteArrayOf(VERSION_RESPONSE) + response
}
