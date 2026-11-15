package com.peri.display.share

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log

private const val TAG = "EncoderCaps"

/**
 * **벤더 LTR 파라미터 한 벌** — 셋이 다 있어야 성립한다.
 *
 * 표준 안드로이드 API에는 LTR이 없다. SDK 37.1의 `MediaFormat` 키 107개, `MediaCodec`의
 * `PARAMETER_KEY_*` 10개, `CodecCapabilities.FEATURE_*` 16개 어디에도 없다. 실리콘엔
 * 있고 커널 V4L2 표준에도 있지만(`V4L2_CID_MPEG_VIDEO_LTR_COUNT` 외 2종) `MediaCodec`
 * 층으로는 올라오지 않았다 — Google이 실리콘을 만들지 않으니 전 벤더가 구현하기 전에는
 * 표준 키로 세울 수가 없다. 벤더가 자기 네임스페이스로 노출할 뿐이다:
 *
 *     vendor.qti-ext-enc-ltr-count.num-ltr-frames
 *     vendor.qti-ext-enc-ltr.mark-frame
 *     vendor.qti-ext-enc-ltr.use-frame
 *
 * ⚠ **그런데 이름을 하드코딩하지 않는다.** 퀄컴 이름을 박아 두면 삼성·미디어텍에서 조용히
 * 죽고, 퀄컴이 이름을 바꿔도 조용히 죽는다. `getSupportedVendorParameters()`가 이 기기의
 * 인코더가 실제로 받는 이름을 **열거해 주므로** 거기서 찾는다. 벤더별 분기가 아니라
 * 벤더별 발견이고, 그래서 우리가 모르는 칩에서도 이름만 맞으면 그냥 된다.
 */
class LtrKeys(
    /** 설정 시점(`MediaFormat`): 슬롯을 몇 개 둘 것인가. */
    val count: String,
    /** 런타임(`setParameters`): 다음 프레임을 이 슬롯에 새긴다. */
    val mark: String,
    /** 런타임(`setParameters`): 다음 프레임이 이 슬롯을 참조한다. */
    val use: String,
    /**
     * 인코더가 **되알려 주는** 이름(없을 수 있다).
     *
     * 위 셋은 우리가 **쓰는** 곳이고 이건 인코더가 **쓰는** 곳이다. `setParameters`에는
     * 프레임을 지목할 방법이 없어서, "어느 프레임에 새겨졌는가"는 인코더가 프레임별 출력
     * 포맷에 실어 보내 줘야만 안다. 퀄컴은 `KEY_LTR_RESPONSE`가 그 자리다.
     *
     * **없으면 LTR 자체가 성립하지 않는다** — 확인되지 않은 앵커는 참조하지 않기로 했으므로
     * 새겨 봐야 쓸 수 없다. 그래서 [matchLtrKeys]가 이걸 못 찾으면 아예 null을 돌려준다.
     */
    val info: String,
) {
    companion object {
        /**
         * 이 코덱이 받는 벤더 파라미터에서 LTR 세 짝을 찾는다. **`configure()` 전에** 부른다.
         *
         * 발견 API는 API 31부터다(minSdk 29라 가드가 필요하다). 못 찾으면 null이고,
         * 그러면 공유 경로는 인트라 리프레시 + 요청 IDR로만 복구한다.
         */
        fun discover(codec: MediaCodec): LtrKeys? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                Log.i(TAG, "LTR 조사 생략 — 벤더 파라미터 발견 API는 API 31부터 (지금 ${Build.VERSION.SDK_INT})")
                return null
            }
            val names = runCatching { codec.supportedVendorParameters }.getOrElse {
                Log.i(TAG, "벤더 파라미터 열거 실패: ${it.message}")
                return null
            }
            if (names.isEmpty()) {
                Log.i(TAG, "벤더 파라미터 없음 — LTR 불가")
                return null
            }

            // 정수를 받는 파라미터만 후보다. 이름이 맞아도 타입이 다르면 우리가 쓸 수 없고,
            // 확인 없이 던지면 기기에 따라 예외가 난다.
            fun intParam(name: String): Boolean =
                runCatching { codec.getParameterDescriptor(name)?.type == MediaFormat.TYPE_INTEGER }
                    .getOrDefault(false)

            val keys = matchLtrKeys(names, ::intParam)
            if (keys == null) {
                Log.i(TAG, "벤더 파라미터 ${names.size}개에서 LTR 세 짝을 못 찾았다")
                return null
            }
            Log.i(TAG, "✅ 벤더 LTR 발견 — count=${keys.count} mark=${keys.mark} " +
                "use=${keys.use} 응답=${keys.info}")
            return keys
        }
    }
}

/**
 * 벤더 파라미터 이름 목록에서 LTR 세 짝을 골라낸다. **순수 함수 — 그래서 테스트할 수 있다.**
 *
 * [LtrKeys.discover]는 `MediaCodec` 인스턴스를 요구해 JVM 테스트가 불가능한데, 정작 틀리기
 * 쉬운 곳은 이름을 고르는 이 규칙이다. 그래서 규칙만 떼어 낸다.
 *
 * @param isInt 그 이름이 정수를 받는가. 이름이 맞아도 타입이 다르면 우리가 쓸 수 없다.
 */
// ── LTR 이름 규약 ──────────────────────────────────────────────────────────────
//
// **접두(네임스페이스)만 다르고 뒤는 같다.** 퀄컴 이름은 벤더 헤더에서 확인했고
// (`QMediaExtensions.java`, quic/android-on-snapdragon), 미디어텍·엑시노스와 WebRTC 계열이
// 쓰는 공통 `rtc-ext`도 같은 꼬리를 쓴다:
//
//   vendor.qti-ext-enc-ltr.mark-frame     ← 확인됨 (KEY_LTR_MARK_FRAME)
//   vendor.mtk-ext-enc-ltr.mark-frame
//   vendor.sec-ext-enc-ltr.mark-frame
//   vendor.rtc-ext-enc-ltr.mark-frame     ← 여러 벤더가 함께 쓰는 공통 네임스페이스
//
// 그래서 접두를 나열하지 않고 **꼬리로 찾는다.** 모르는 칩이라도 규약만 따르면 그냥 된다.
//
// ⚠ 퀄컴 외의 이름은 2차 출처라 확실하지 않다. 다만 **틀려도 해롭지 않다** — 우리는 기기가
// 열거해 준 목록에서만 고르므로, 없는 이름은 그냥 안 걸린다. 지어낸 이름을 던지는 일이 없다.
private const val SUFFIX_COUNT = "-ext-enc-ltr-count.num-ltr-frames"
private const val SUFFIX_MARK = "-ext-enc-ltr.mark-frame"
private const val SUFFIX_USE = "-ext-enc-ltr.use-frame"
private const val SUFFIX_INFO = "-ext-enc-info-ltr.ltr-use-mark"

/**
 * 코덱 레벨 한 칸 — `(안드로이드 상수, 화면 한 장의 상한, 초당 상한)`.
 *
 * 레벨은 **디코더에게 요구하는 최소 능력**을 비트스트림에 적어 두는 값이다. 크게 부르면
 * 손해가 없어 보이지만 스트림을 잘못 기술하는 것이고, 까다로운 수신측은 그걸 보고 거절한다.
 * 실제로 1MP 30fps 스트림에 8K용 레벨(6.1/6.2)을 적고 있었다 — 인코더가 광고하는 레벨 중
 * **가장 큰 값**을 골랐기 때문이다.
 */
private class LevelLimit(val constant: Int, val maxPerPicture: Long, val maxPerSecond: Long)

/**
 * HEVC 메인 티어 (H.265 Table A.6/A.8). 단위는 **루마 샘플**.
 *
 * 하이 티어는 쓰지 않는다 — 같은 레벨에서 비트레이트 상한만 몇 배로 큰 등급이라 화면 공유가
 * 쓸 이유가 없고, 하이 티어를 고르면 그것대로 과장이다.
 */
private val HEVC_LEVELS = listOf(
    LevelLimit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel1, 36_864, 552_960),
    LevelLimit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel2, 122_880, 3_686_400),
    LevelLimit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel21, 245_760, 7_372_800),
    LevelLimit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel3, 552_960, 16_588_800),
    LevelLimit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31, 983_040, 33_177_600),
    LevelLimit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4, 2_228_224, 66_846_720),
    LevelLimit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41, 2_228_224, 133_693_440),
    LevelLimit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5, 8_912_896, 267_386_880),
    LevelLimit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51, 8_912_896, 534_773_760),
    LevelLimit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel52, 8_912_896, 1_069_547_520),
    LevelLimit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel6, 35_651_584, 1_069_547_520),
    LevelLimit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel61, 35_651_584, 2_139_095_040),
    LevelLimit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel62, 35_651_584, 4_278_190_080),
)

/** H.264 (Table A-1). 단위는 **매크로블록**(16×16). */
private val AVC_LEVELS = listOf(
    LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel1, 99, 1_485),
    LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel11, 396, 3_000),
    LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel12, 396, 6_000),
    LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel13, 396, 11_880),
    LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel2, 396, 11_880),
    LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel21, 792, 19_800),
    LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel22, 1_620, 20_250),
    LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel3, 1_620, 40_500),
    LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel31, 3_600, 108_000),
    LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel32, 5_120, 216_000),
    LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel4, 8_192, 245_760),
    LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel41, 8_192, 245_760),
    LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel42, 8_704, 522_240),
    LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel5, 22_080, 589_824),
    LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel51, 36_864, 983_040),
    LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel52, 36_864, 2_073_600),
)

/**
 * 이 제원을 담을 수 있는 **가장 낮은** 레벨을 고른다. 인코더가 광고하지 않는 레벨은 건너뛴다.
 *
 * 광고 목록에 쓸 만한 게 없으면(우리 표보다 낮은 것만 있다) null — 그러면 호출부가 프로파일
 * 자체를 설정하지 않는다. 프로파일을 정하면 레벨도 같이 정해야 하는데, 담지도 못할 레벨을
 * 적느니 인코더 기본값에 맡기는 게 맞다.
 */
/** 표에 있는 레벨 전부 — 테스트가 "기기가 다 광고한다"를 표현할 때 쓴다. */
internal val HEVC_LEVELS_FOR_TEST: Set<Int> get() = HEVC_LEVELS.map { it.constant }.toSet()
internal val AVC_LEVELS_FOR_TEST: Set<Int> get() = AVC_LEVELS.map { it.constant }.toSet()

internal fun pickLevel(
    hevc: Boolean,
    width: Int,
    height: Int,
    fps: Int,
    advertised: Set<Int>,
): Int? {
    // H.264는 매크로블록(16×16) 단위, HEVC는 루마 샘플 단위로 센다.
    val perPicture = if (hevc) {
        width.toLong() * height
    } else {
        ((width + 15) / 16).toLong() * ((height + 15) / 16)
    }
    val perSecond = perPicture * fps
    val table = if (hevc) HEVC_LEVELS else AVC_LEVELS
    return table.firstOrNull {
        it.constant in advertised && perPicture <= it.maxPerPicture && perSecond <= it.maxPerSecond
    }?.constant
}

internal fun matchLtrKeys(names: List<String>, isInt: (String) -> Boolean): LtrKeys? {
    // ★ **네임스페이스를 섞지 않는다.** 한 기기가 `mtk-ext`와 `rtc-ext`를 같이 노출할 수
    // 있는데, 새김은 이쪽 참조는 저쪽으로 던지면 서로 다른 구현을 반씩 건드리게 된다.
    // 네 개가 **같은 접두로** 모두 있을 때만 성립으로 본다.
    //
    // ⚠ **낱말로 짐작하지 않는다.** 한때 "이름에 mark가 들어가면 새김"式으로 찾았는데,
    // 실기기의 `…-ext-enc-info-ltr.ltr-use-mark`(읽기 전용 계측)가 "use"와 "mark"를 둘 다
    // 담고 있어서 새김·참조 양쪽에 잡혔다. 계측 필드에 값을 쓰니 아무 일도 일어나지 않았고,
    // 그 필드가 우리가 쓴 값을 되비추는 바람에 **확인까지 성공한 것처럼 보였다** — 로그는
    // 완벽했고 복구는 하나도 일어나지 않았다. 규약을 아는 지금 짐작할 이유가 없고,
    // 규약 밖의 칩은 LTR 없이 인트라 리프레시로 가는 게 옳다(조용히 반쪽으로 도는 것보다).
    for (mark in names.filter { it.endsWith(SUFFIX_MARK, ignoreCase = true) && isInt(it) }) {
        val ns = mark.dropLast(SUFFIX_MARK.length)
        fun sibling(suffix: String) =
            names.firstOrNull { it.equals(ns + suffix, ignoreCase = true) && isInt(it) }
        val count = sibling(SUFFIX_COUNT) ?: continue
        val use = sibling(SUFFIX_USE) ?: continue
        val info = sibling(SUFFIX_INFO) ?: continue
        return LtrKeys(count = count, mark = mark, use = use, info = info)
    }
    return null
}

/**
 * **이 기기의 인코더가 실제로 무엇을 할 수 있는가.**
 *
 * Mac은 `VTCompressionSessionCreate`에 저지연 플래그 하나를 넘기면 재정렬 제거·빠른 rate
 * 적응·LTR·max frame QP가 한꺼번에 열린다 — Apple이 API와 실리콘을 같이 소유하니 가능한
 * 일이다. 안드로이드에는 그런 **모드**가 없고(`KEY_LOW_LATENCY`는 문서가 "applies only to
 * decoders"라고 못박는다) 개별 손잡이만 있는데, 그마저 기기마다 있고 없다.
 *
 * 그래서 세우기 전에 물어본다. 여기서 확인한 것만 켠다.
 */
class EncoderCaps(
    val codecName: String,
    val mime: String,
    val hardware: Boolean,
    /** 점진적 인트라 리프레시 — 안드로이드에서 LTR의 목적에 가장 가까운 표준 기능. */
    val intraRefresh: Boolean,
    /** QP 상한을 걸 수 있는가 (Mac의 `MaxAllowedFrameQP` 대응). */
    val qpBounds: Boolean,
    /** 실제로 쓸 비트레이트 모드 — CBR_FD → CBR → VBR 사다리의 결과. */
    val bitrateMode: Int,
    /** 0이면 못 정했다(설정하지 않는다). */
    val profile: Int,
    val level: Int,
) {
    /** 로그 한 줄 — 실기기에서 무엇이 켜졌는지 이 줄만 보면 된다. */
    fun describe(): String = buildString {
        append(codecName)
        append(if (hardware) " [HW]" else " [SW]")
        append(" ").append(bitrateModeName(bitrateMode))
        if (profile != 0) append(" profile=$profile/level=$level") else append(" profile=기본값")
        if (intraRefresh) append(" +인트라리프레시")
        if (qpBounds) append(" +QP상한")
    }

    companion object {
        private fun bitrateModeName(m: Int) = when (m) {
            EncoderCapabilities.BITRATE_MODE_CBR_FD -> "CBR_FD"
            EncoderCapabilities.BITRATE_MODE_CBR -> "CBR"
            EncoderCapabilities.BITRATE_MODE_VBR -> "VBR"
            else -> "mode=$m"
        }

        /**
         * 이 MIME을 구울 인코더를 고르고 능력을 조사한다. 없으면 null.
         *
         * ★ **하드웨어를 명시적으로 고른다.** `createEncoderByType`은 목록의 첫 번째를
         * 집는데 그게 소프트웨어 인코더일 수 있다(`c2.android.*`). 화면 전체를 30fps로
         * 굽는 일을 CPU가 하면 폰이 뜨거워지고 프레임이 밀린다. Mac은 저지연 rate control을
         * 요구하는 것만으로 하드웨어 인코더가 선택되는데, 여기서는 우리가 골라야 한다.
         *
         * @param hdr10 10비트 프로파일을 원하는가 (지금 공유 경로는 SDR이라 항상 false).
         */
        fun probe(
            mime: String,
            width: Int,
            height: Int,
            fps: Int,
            hdr10: Boolean = false,
        ): EncoderCaps? {
            val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }
                .getOrElse {
                    Log.e(TAG, "코덱 목록을 못 읽었다: ${it.message}")
                    return null
                }
            val candidates = infos.filter { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
            if (candidates.isEmpty()) {
                Log.w(TAG, "$mime 인코더가 없다")
                return null
            }
            // 하드웨어 우선, 없으면 소프트웨어라도. 폴백을 막지는 않는다 — H.265 인코더가
            // 소프트웨어뿐인 기기는 어차피 아래에서 H.264로 내려간다.
            val info = candidates.firstOrNull { it.isHardwareAccelerated } ?: candidates.first()
            if (!info.isHardwareAccelerated) {
                Log.w(TAG, "${info.name}은 소프트웨어 인코더다 — 발열·지연을 감수한다")
            }

            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrElse {
                Log.w(TAG, "${info.name} 능력표를 못 읽었다: ${it.message}")
                return null
            }
            val (profile, level) = pickProfile(caps, mime, hdr10, width, height, fps)
            return EncoderCaps(
                codecName = info.name,
                mime = mime,
                hardware = info.isHardwareAccelerated,
                intraRefresh = caps.isFeatureSupportedSafe(CodecCapabilities.FEATURE_IntraRefresh),
                qpBounds = caps.isFeatureSupportedSafe(CodecCapabilities.FEATURE_QpBounds),
                bitrateMode = pickBitrateMode(caps),
                profile = profile,
                level = level,
            )
        }

        /**
         * 실시간에 맞는 비트레이트 모드를 고른다.
         *
         * `CBR_FD`(API 31)는 **프레임을 떨어뜨려서라도** 목표 비트레이트를 지키는 모드다.
         * 화면 공유에서 이게 옳다 — 순간적으로 화면 전체가 바뀌었다고 큐를 채우면 그 뒤로
         * 몇 초가 밀린다. VBR은 그 반대로 순간 폭을 허용한다(파일 저장용 기본값이다).
         * Mac의 저지연 rate control이 하는 일이 대체로 이것이다.
         */
        private fun pickBitrateMode(caps: CodecCapabilities): Int {
            val enc = caps.encoderCapabilities ?: return EncoderCapabilities.BITRATE_MODE_VBR
            fun ok(m: Int) = runCatching { enc.isBitrateModeSupported(m) }.getOrDefault(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ok(EncoderCapabilities.BITRATE_MODE_CBR_FD)
            ) return EncoderCapabilities.BITRATE_MODE_CBR_FD
            if (ok(EncoderCapabilities.BITRATE_MODE_CBR)) return EncoderCapabilities.BITRATE_MODE_CBR
            return EncoderCapabilities.BITRATE_MODE_VBR
        }

        /**
         * 프로파일·레벨을 **명시적으로** 고른다.
         *
         * SDK 문서가 직접 권고한다 — *"It is recommended that the profile is set for all
         * encoders."* 안 정하면 인코더가 고르고, **기기마다 다르다.** H.264에서 Baseline이
         * 잡히면 CABAC이 빠져 같은 비트레이트로 화질이 눈에 띄게 내려가는데, 지금은 무엇이
         * 잡혔는지 알 방법조차 없다. Mac은 High/Main을 못박고 SPS를 되읽어 확인까지 한다.
         *
         * 레벨은 **이 제원을 담을 수 있는 가장 낮은 값**을 고른다([pickLevel]).
         *
         * 한때 광고된 것 중 가장 큰 값을 골랐는데, 그러면 1MP 30fps 스트림에 8K용 레벨
         * (High tier 6.1/6.2)이 적혔다. 레벨은 "이 스트림을 풀려면 이만한 디코더가
         * 필요하다"는 선언이라, 크게 부르는 건 손해가 없는 게 아니라 **거짓말**이다.
         * (프로파일을 정하면 레벨도 같이 정해야 한다 — SDK 문서의 요구다. 그래서 담을 레벨을
         * 못 찾으면 프로파일도 함께 포기한다.)
         */
        private fun pickProfile(
            caps: CodecCapabilities,
            mime: String,
            hdr10: Boolean,
            width: Int,
            height: Int,
            fps: Int,
        ): Pair<Int, Int> {
            val levels = caps.profileLevels ?: return 0 to 0
            val hevc = mime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
            // 선호 순서. 앞에 있는 걸 기기가 광고하면 그걸 쓴다.
            val wanted = when {
                hevc && hdr10 -> intArrayOf(
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
                )
                hevc -> intArrayOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                else -> intArrayOf(
                    MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
                )
            }
            for (p in wanted) {
                val advertised = levels.filter { it.profile == p }.map { it.level }.toSet()
                if (advertised.isEmpty()) continue
                val level = pickLevel(hevc, width, height, fps, advertised)
                if (level != null) return p to level
                Log.w(TAG, "프로파일 $p 에 ${width}x${height}@$fps 를 담을 레벨이 없다")
            }
            Log.w(TAG, "프로파일·레벨을 정하지 못했다 — 인코더 기본값에 맡긴다 " +
                "(광고된 프로파일: ${levels.map { it.profile }.distinct().joinToString()})")
            return 0 to 0
        }

        /** 기기에 따라 모르는 기능 이름에 예외를 내는 구현이 있다. */
        private fun CodecCapabilities.isFeatureSupportedSafe(name: String): Boolean =
            runCatching { isFeatureSupported(name) }.getOrDefault(false)
    }
}
