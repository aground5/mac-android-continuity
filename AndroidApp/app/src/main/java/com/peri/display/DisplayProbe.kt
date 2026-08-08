package com.peri.display

import android.app.Activity
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import com.peri.display.proto.DecoderCaps
import com.peri.display.proto.DynamicRange
import com.peri.display.proto.VideoCodec

/**
 * 수신측 디스플레이/창 특성. Mac이 가상 디스플레이/인코더를 이 값에 맞춘다.
 * width/height = *현재 앱 창이 차지하는 영역*(전체화면=전체, 분할/팝업=창). 보통 SurfaceView 크기에서 옴.
 * maxWidth/maxHeight = 전체 디스플레이(가상 디스플레이 framebuffer 상한 결정).
 */
data class DisplaySpec(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val refreshRate: Float,
    val isLandscape: Boolean,
    val maxWidthPx: Int,
    val maxHeightPx: Int,
    /** **화면이** 표시할 수 있는 다이내믹 레인지(선호순). 비어 있으면 SDR만. */
    val dynamicRanges: List<DynamicRange> = emptyList(),
    /** **디코더가** 실제로 감당하는 한계 (코덱별). Mac이 화면 능력과 교집합을 낸다. */
    val decoders: List<DecoderCaps> = emptyList(),
)

object DisplayProbe {

    /** 전체 디스플레이 해상도/밀도/주사율 측정. width/height·max 모두 전체값(초기 폴백용). */
    @Suppress("DEPRECATION")
    fun probe(activity: Activity): DisplaySpec {
        val densityDpi = activity.resources.configuration.densityDpi
        val w: Int
        val h: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = activity.windowManager.maximumWindowMetrics.bounds
            w = bounds.width()
            h = bounds.height()
        } else {
            val real = DisplayMetrics()
            activity.windowManager.defaultDisplay.getRealMetrics(real)
            w = real.widthPixels
            h = real.heightPixels
        }
        // 현재 모드 값이 아니라 패널이 지원하는 *최대* 주사율을 보고 (120폰이 60Hz 모드여도 120 협상).
        val refresh = maxRefreshRate(activity)
        return DisplaySpec(w, h, densityDpi, refresh, w >= h, maxWidthPx = w, maxHeightPx = h,
                           dynamicRanges = CodecSupport.displayDynamicRanges(displayOf(activity)),
                           decoders = CodecSupport.decoderCaps(w, h))
    }

    /** API 30+는 Activity.display, 그 이하는 defaultDisplay. */
    @Suppress("DEPRECATION")
    private fun displayOf(activity: Activity): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display
        else activity.windowManager.defaultDisplay

    /** 현재 해상도에서 지원되는 최대 주사율 (없으면 현재값, 그것도 없으면 60).
     *  해상도가 다른 고주사율 모드는 제외 — requestMaxRefresh가 해상도를 유지하므로 값이 일관돼야 함. */
    fun maxRefreshRate(activity: Activity): Float {
        val display = displayOf(activity) ?: return 60f
        val best = bestModeAtCurrentResolution(display)
        return best?.refreshRate ?: display.refreshRate
    }

    /** 현재 해상도를 유지한 채 최대 주사율 모드를 창에 요청 → Mac이 보내는 고fps가 실제 표시됨
     *  (패널이 60에 머물면 절반이 컴포지터에서 드롭). 해상도 다운그레이드는 피한다. */
    fun requestMaxRefresh(activity: Activity) {
        val display = displayOf(activity) ?: return
        val best = bestModeAtCurrentResolution(display) ?: return
        activity.window.attributes = activity.window.attributes.apply {
            preferredDisplayModeId = best.modeId
        }
    }

    /** 현재 물리 해상도와 같은 모드 중 최고 주사율. 매칭이 없으면 전역 최고로 폴백. */
    private fun bestModeAtCurrentResolution(display: Display): Display.Mode? {
        val modes = display.supportedModes
        if (modes.isNullOrEmpty()) return null
        val cur = display.mode
        val sameRes = modes.filter {
            it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight
        }
        return (sameRes.ifEmpty { modes.toList() }).maxByOrNull { it.refreshRate }
    }
}

object CodecSupport {

    /** 디코드 가능 코덱(선호순). HEVC HW 디코더가 있으면 [H265, H264], 없으면 [H264]. */
    fun supportedDecoders(): List<VideoCodec> {
        val list = ArrayList<VideoCodec>()
        if (hasHwDecoder(MediaFormat.MIMETYPE_VIDEO_HEVC)) list.add(VideoCodec.VIDEO_CODEC_H265)
        list.add(VideoCodec.VIDEO_CODEC_H264)   // 항상 보장(fallback)
        return list
    }

    private fun hasHwDecoder(mime: String): Boolean {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in codecs.codecInfos) {
            if (info.isEncoder) continue
            if (!info.isHardwareAccelerated) continue   // minSdk 29 → 항상 사용 가능
            if (info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) return true
        }
        return false
    }

    /**
     * **화면이 표시할 수 있는** 다이내믹 레인지(선호순).
     *
     * 디코더 능력과 곱하지 않는다 — 그건 Mac이 [decoderCaps]와 교집합을 내서 판단한다.
     * 여기서 미리 곱하면 Mac은 "화면이 SDR이라서"인지 "디코더가 8비트라서"인지 구분하지 못한다.
     */
    fun displayDynamicRanges(display: Display?): List<DynamicRange> {
        if (display == null) return emptyList()
        val hdrTypes = displayHdrTypes(display)
        val out = ArrayList<DynamicRange>()
        if (hdrTypes.contains(Display.HdrCapabilities.HDR_TYPE_HDR10)) {
            out.add(DynamicRange.DYNAMIC_RANGE_HDR10)
        }
        if (hdrTypes.contains(Display.HdrCapabilities.HDR_TYPE_HLG)) {
            out.add(DynamicRange.DYNAMIC_RANGE_HLG)
        }
        return out
    }

    /**
     * 디코더별 실제 한계. 코덱마다 최적(하드웨어 우선) 디코더 하나씩.
     *
     * @param nativeWidth/[nativeHeight] 기기 화면 해상도 — 이 크기에서 달성 가능한 fps를 잰다.
     *        Mac이 다운스케일하면 여유는 늘어날 뿐이라 상한으로 안전하다.
     */
    fun decoderCaps(nativeWidth: Int, nativeHeight: Int): List<DecoderCaps> {
        val wanted = listOf(
            VideoCodec.VIDEO_CODEC_H265 to MediaFormat.MIMETYPE_VIDEO_HEVC,
            VideoCodec.VIDEO_CODEC_H264 to MediaFormat.MIMETYPE_VIDEO_AVC,
        )
        val out = ArrayList<DecoderCaps>()
        for ((codec, mime) in wanted) {
            val info = bestDecoderFor(mime) ?: continue
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
            val vc = caps.videoCapabilities ?: continue

            // 해상도는 방향이 뒤집힐 수 있으므로(가로/세로) 두 조합 중 되는 쪽으로 잰다.
            val (w, h) = if (vc.isSizeSupported(nativeWidth, nativeHeight)) nativeWidth to nativeHeight
                         else nativeHeight to nativeWidth
            val fps = achievableFrameRate(vc, w, h)

            val ranges = ArrayList<DynamicRange>()
            ranges.add(DynamicRange.DYNAMIC_RANGE_SDR)
            if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC && caps.profileLevels.any {
                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                }) {
                ranges.add(DynamicRange.DYNAMIC_RANGE_HDR10)
            }

            out.add(
                DecoderCaps.newBuilder()
                    .setCodec(codec)
                    .setHardwareAccelerated(info.isHardwareAccelerated)
                    .setMaxWidth(vc.supportedWidths.upper)
                    .setMaxHeight(vc.supportedHeights.upper)
                    .setMaxFrameRate(fps)
                    .setMaxBitrateKbps(vc.bitrateRange.upper / 1000)
                    .addAllDynamicRanges(ranges)
                    .setMaxInstances(caps.maxSupportedInstances)
                    .build()
            )
        }
        return out
    }

    /**
     * 이 해상도에서 **실제로 낼 수 있는** fps.
     *
     * `getSupportedFrameRatesFor`는 쓰지 않는다 — 그건 코덱이 광고하는 이론적 상한(레벨 최대)이라
     * 실측 결과 8192x8192@480 같은 무의미한 값이 나온다. 그런 숫자를 협상에 쓰면 교환하는 의미가 없다.
     *
     * 정직한 순서로 시도한다:
     *  ① PerformancePoint — 기기가 공표한 "이 해상도·fps 조합을 감당한다"는 목록.
     *     Android 10 이후 출시 기기는 이걸 반드시 채워야 한다.
     *  ② getAchievableFrameRatesFor — 실측 기반. 기기가 측정을 안 올렸으면 null.
     *  ③ 둘 다 없으면 60으로 보수적 폴백. 광고 상한(480 같은)을 그대로 믿느니 낮게 잡는 게 낫다.
     */
    private fun achievableFrameRate(vc: MediaCodecInfo.VideoCapabilities, w: Int, h: Int): Int {
        val points = runCatching { vc.supportedPerformancePoints }.getOrNull()
        if (!points.isNullOrEmpty()) {
            val best = intArrayOf(240, 120, 90, 60, 30).firstOrNull { fps ->
                val want = MediaCodecInfo.VideoCapabilities.PerformancePoint(w, h, fps)
                points.any { it.covers(want) }
            }
            if (best != null) return best
        }
        runCatching { vc.getAchievableFrameRatesFor(w, h) }.getOrNull()?.let {
            return it.upper.toInt()
        }
        return 60
    }

    /** 이 MIME의 디코더 중 하드웨어 가속을 우선해 하나 고른다. */
    private fun bestDecoderFor(mime: String): MediaCodecInfo? {
        val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
            !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) }
        }
        return all.firstOrNull { it.isHardwareAccelerated } ?: all.firstOrNull()
    }

    /** 화면이 표시 가능한 HDR 종류. API 34에서 Display.getHdrCapabilities가 폐기돼 모드 쪽으로 옮겨갔다. */
    @Suppress("DEPRECATION")
    private fun displayHdrTypes(display: Display): IntArray =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            display.mode.supportedHdrTypes
        } else {
            display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
        }

    /** HEVC Main10(10비트) 디코드가 가능한 HW 디코더가 있는가. */
    private fun hasMain10Decoder(): Boolean = main10DecoderName() != null

    /**
     * HEVC Main10을 처리할 수 있는 **하드웨어** 디코더 이름. 없으면 null.
     *
     * 직접 고르는 이유: `MediaCodecList.findDecoderForFormat`은 하드웨어 여부를 보지 않고,
     * KEY_PROFILE도 선택에 반영하지 않는 것으로 알려져 있다. 그대로 맡기면
     * "HW Main10 디코더가 **존재한다**"고 광고해 놓고 정작 소프트웨어 디코더나 Main만 되는
     * 놈이 잡힐 수 있다 — 10비트 4K급 스트림을 SW로 풀면 그대로 프레임 드롭이다.
     *
     * width/height를 주면 그 크기까지 실제로 지원하는지도 확인한다(프로파일만 보면
     * 레벨 상한에 걸리는 기기를 놓친다).
     */
    fun main10DecoderName(width: Int = 0, height: Int = 0): String? {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in codecs.codecInfos) {
            if (info.isEncoder || !info.isHardwareAccelerated) continue
            if (!info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) }) continue
            val caps = runCatching {
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            }.getOrNull() ?: continue
            val main10 = caps.profileLevels.any {
                it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
            }
            if (!main10) continue
            if (width > 0 && height > 0) {
                val vc = caps.videoCapabilities ?: continue
                if (!vc.isSizeSupported(width, height)) continue
            }
            return info.name
        }
        return null
    }
}
