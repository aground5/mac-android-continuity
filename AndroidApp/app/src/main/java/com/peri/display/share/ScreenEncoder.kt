package com.peri.display.share

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

private const val TAG = "ScreenEncoder"

/**
 * 화면이 멈춰 있을 때 마지막 프레임을 되풀이하는 간격(µs).
 *
 * 1초로 잡은 근거: 이보다 짧으면 정지 화면에서 쓸데없는 프레임이 계속 나가고(그래도 P
 * 프레임이라 몇백 바이트다), 길면 새로 붙은 수신측이 첫 그림까지 그만큼 더 기다린다.
 */
private const val REPEAT_STILL_FRAME_US = 1_000_000L

/** 복구 수단이 아무것도 없을 때의 주기 IDR(초) — 손실이 나면 이걸 기다리는 수밖에 없다. */
private const val IDR_INTERVAL_BARE_SEC = 2

/**
 * 복구 수단이 있을 때의 주기 IDR(초).
 *
 * IDR은 스트림에서 가장 큰 버스트(평균 프레임의 5~10배)라 2초마다 내보내면 Wi-Fi 큐가
 * 주기적으로 포화된다. 인트라 리프레시나 LTR로 손실을 복구할 수 있으면 주기 IDR은
 * **안전망**으로 물러나도 된다 — Mac이 같은 이유로 30초를 쓴다. 여기서 더 짧은 10초를
 * 쓰는 건 Mac이 요청을 못 보내는 경우(옛 피어)에도 10초 안에는 저절로 회복시키기 위해서다.
 */
private const val IDR_INTERVAL_RECOVERABLE_SEC = 10

/**
 * LTR 슬롯 수. **둘이 최소값이다.**
 *
 * 하나면 새 앵커를 새기는 순간 옛 앵커가 사라지고, 하필 그 새 프레임이 손실되면 되돌아갈
 * 곳이 없어진다. 둘을 번갈아 쓰면 Mac이 확인해 준 앵커를 붙들고 있는 채로 다음 앵커를
 * 세울 수 있다.
 */
internal const val LTR_SLOTS = 2

/**
 * 화면 공유 인코더 — `MediaProjection`의 VirtualDisplay가 그리는 Surface를 그대로 먹는다.
 *
 * 확장 경로의 `VideoDecoder`와 정확히 반대다. 거기선 Mac이 인코딩한 걸 우리가 풀었고,
 * 여기선 우리가 굽고 Mac이 푼다. 둘 다 같은 Annex-B 비트스트림을 주고받는다.
 *
 * 왜 Surface 입력인가: VirtualDisplay는 GPU가 채우는 Surface로만 나온다. 그걸 바이트로
 * 내렸다가 다시 올리면 프레임마다 전체 화면을 CPU로 복사하는 셈이라 폰이 곧 뜨거워진다.
 * `createInputSurface()`로 인코더의 입력 Surface를 받아 VirtualDisplay에 바로 물리면
 * 픽셀이 GPU 밖으로 나오지 않는다.
 *
 * ⚠ **파라미터 세트(SPS/PPS/VPS)를 키프레임마다 다시 붙인다.** Mac은 최신성 경로로 받으므로
 * 첫 프레임을 놓칠 수 있고, 그러면 포맷 서술자를 못 만들어 영영 검은 화면이 된다.
 * `KEY_PREPEND_HEADER_TO_SYNC_FRAMES`가 있으면 그걸 쓰고, 없으면 우리가 직접 앞에 붙인다.
 *
 * ## 복구 모델
 *
 * Mac 인코더는 저지연 모드에서 LTR을 켜고 주기 IDR을 사실상 끈다 — 손실이 나면 ACK된
 * 장기 참조를 가리키는 작은 LTR-P로 때운다. 여기서는 같은 일을 **세 단계로 나눠** 한다:
 *
 *  1. `KEY_INTRA_REFRESH_PERIOD` — 표준이고 벤더 무관. 매 프레임 일부 매크로블록을 인트라로
 *     구워 손실이 한 주기 안에 저절로 씻긴다. 요청도 ACK도 필요 없다.
 *  2. **벤더 LTR** — 있으면 Mac과 같은 앵커 구조가 된다([LtrKeys] 참조).
 *  3. `PARAMETER_KEY_REQUEST_SYNC_FRAME` — 최후 수단. 참조가 아예 없는 수신측용.
 *
 * 셋 다 없으면 2초 주기 IDR로 되돌아간다(예전 동작).
 */
class ScreenEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrateKbps: Int,
    /** true=H.265, false=H.264. 기기가 H.265 인코더를 못 만들면 스스로 H.264로 내려간다. */
    preferHevc: Boolean,
    /**
     * 완성된 접근 단위 하나. **인코더 스레드에서** 불린다.
     *
     * `ltrSlot`은 이 프레임이 새겨진 LTR 슬롯 번호(-1 = 앵커 아님). 받는 쪽이 이 프레임을
     * 온전히 받았다고 알려 주면 그 슬롯이 복구 지점이 된다 — 프레임 번호를 매기는 곳이
     * [com.peri.display.apps.ShareApp]이므로 슬롯↔프레임 대응도 거기서 짝지어진다.
     */
    private val onFrame: (bitstream: ByteArray, keyframe: Boolean, ptsMs: Long, ltrSlot: Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    /** 실제로 성립한 코덱 — 폴백이 일어났을 수 있으므로 Mac에는 이 값을 알린다. */
    var isHevc: Boolean = preferHevc
        private set

    private var codec: MediaCodec? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    /** 파라미터 세트. 키프레임 앞에 직접 붙일 때 쓴다(플랫폼이 안 해 주는 기기용). */
    @Volatile private var csd: ByteArray? = null

    /** 이 인코더로 확인된 능력 — 무엇을 켰는지의 유일한 근거. */
    @Volatile private var caps: EncoderCaps? = null

    /** 벤더 LTR 파라미터. null이면 이 기기엔 LTR이 없다. */
    @Volatile private var ltr: LtrKeys? = null

    /** 이 기기에서 손실을 복구할 방법이 있는가 — 주기 IDR을 늘려도 되는지의 근거. */
    val hasRecoveryPath: Boolean
        get() = ltr != null || caps?.intraRefresh == true

    // ── LTR 슬롯 장부 (인코더 스레드 + 제어 스레드가 함께 만진다) ──────────────

    private val ltrLock = Any()
    /** 새겨 달라고 요청했지만 **아직 어느 프레임에 붙었는지 모르는** 슬롯. -1 = 없음. */
    private var pendingMark = -1
    /** Mac이 받았다고 확인해 준 슬롯. -1 = 없음 — 그러면 LTR로 복구할 수 없다. */
    private var confirmedSlot = -1
    /** 마지막으로 새기기를 요청한 시각(프레임 수 기준). */
    private var framesSinceMark = 0
    /** 새기기를 청했는데 인코더가 한 번도 확인해 주지 않았다 — 한 번만 경고한다. */
    private var warnedUnconfirmed = false

    /** 인코더를 세우고 입력 Surface를 돌려준다. 실패하면 null. */
    fun start(): Surface? {
        val (mime, hevc) = if (isHevc) MediaFormat.MIMETYPE_VIDEO_HEVC to true
                           else MediaFormat.MIMETYPE_VIDEO_AVC to false
        val surface = tryStart(mime) ?: run {
            if (!hevc) return null
            // H.265를 못 세웠다 — H.264는 모든 기기가 보장한다(그래서 폴백이 성립한다).
            Log.w(TAG, "H.265 인코더 실패 → H.264로 폴백")
            isHevc = false
            tryStart(MediaFormat.MIMETYPE_VIDEO_AVC)
        } ?: return null

        running = true
        thread = Thread({ drain() }, "peri-share-encoder").also { it.start() }
        return surface
    }

    private fun tryStart(mime: String): Surface? = runCatching {
        val cap = EncoderCaps.probe(mime, width, height, fps) ?: run {
            Log.w(TAG, "$mime 인코더를 못 찾았다")
            return null
        }
        val c = MediaCodec.createByCodecName(cap.codecName)
        // ⚠ **만들자마자 붙들어 둔다.** 아래 어디서든 예외가 나면 `getOrElse`가 치우는데,
        // 그때 이 필드가 비어 있으면 코덱이 통째로 샌다. 하드웨어 인코더는 프로세스당
        // 몇 개 안 되므로, 새면 H.264 폴백조차 못 세우게 된다.
        codec = c

        // ★ **벤더 파라미터는 `configure()` 전에 물어봐야 한다.** 슬롯 개수가 설정 시점
        // 키(`MediaFormat`)라서, 나중에 알면 이미 늦다.
        val keys = LtrKeys.discover(c)

        val fmt = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                       MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // ★ **`KEY_FRAME_RATE`는 프레임을 버려 주지 않는다.** Surface 입력에서 그 값은
            // 비트레이트 배분용 힌트일 뿐이고, 인코더는 VirtualDisplay가 그리는 대로 전부
            // 굽는다 — 120Hz 패널이면 120fps가 된다. 30fps 예산으로 잡은 비트레이트를
            // 네 배의 프레임에 나눠 쓰게 되니 화질은 무너지고 폰은 뜨거워진다.
            //
            // 실제로 입력을 솎아 내는 건 이 키다.
            setInteger(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps)
            // 화면이 멈춰 있으면 VirtualDisplay는 **프레임을 아예 안 만든다.** 그러면 인코더도
            // 아무것도 안 내놓고, 지금 막 붙은 Mac은 영원히 검은 화면을 본다("공유는 켜졌는데
            // 아무것도 안 보인다"). 마지막 프레임을 되풀이해 흐름이 끊기지 않게 한다.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_STILL_FRAME_US)
            setInteger(MediaFormat.KEY_BITRATE_MODE, cap.bitrateMode)

            // ── Mac의 저지연 모드에 대응하는 것들 ──────────────────────────────
            //
            // Mac은 `EnableLowLatencyRateControl` 하나로 재정렬 제거·빠른 rate 적응이
            // 한꺼번에 열린다. 안드로이드에는 인코더용 저지연 **모드**가 없어서
            // (`KEY_LOW_LATENCY`는 문서가 "applies only to decoders"라고 못박는다)
            // 같은 자세를 개별 키로 만들어야 한다.

            // `kVTCompressionPropertyKey_RealTime`의 짝. 0 = 실시간 우선순위 —
            // 코덱 리소스 계획에서 best-effort가 아니라 실시간으로 잡힌다.
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            // `AllowFrameReordering = false`의 짝. 기본값도 0이지만 명시한다 —
            // 예전엔 아래 `KEY_LATENCY`가 이 일을 한다고 주석에 적혀 있었는데 그건 틀렸다.
            // `KEY_LATENCY`는 "인코더가 출력 전에 물고 있을 프레임 수"다.
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_LATENCY, 1)
            if (cap.profile != 0) {
                // 안 정하면 인코더가 고르고 **기기마다 다르다.** H.264에서 Baseline이 잡히면
                // CABAC이 빠져 같은 비트레이트로 화질이 내려간다. SDK 문서도 전 인코더에
                // 프로파일을 설정하라고 권고하고, 정할 때는 레벨도 같이 정하라고 요구한다.
                setInteger(MediaFormat.KEY_PROFILE, cap.profile)
                setInteger(MediaFormat.KEY_LEVEL, cap.level)
            }
            if (cap.qpBounds) {
                // `MaxAllowedFrameQP = 40`의 짝 — 화면 콘텐츠의 텍스트 선명도를 지킨다.
                setInteger(MediaFormat.KEY_VIDEO_QP_MAX, 40)
            }

            // ── 복구 구조 ────────────────────────────────────────────────────
            if (cap.intraRefresh) {
                // 매 프레임 일부 매크로블록을 인트라로 굽는다. 한 주기가 지나면 화면 전체가
                // 새로 그려진 셈이라 손실이 저절로 씻긴다 — **요청도 ACK도 없이.**
                // 주기를 2초어치 프레임으로 잡아 예전 2초 IDR과 같은 복구 리듬을 유지하되,
                // 그 일을 한 프레임에 몰아 터뜨리지 않고 고르게 편다.
                setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD, fps * 2)
            }
            if (keys != null) {
                setInteger(keys.count, LTR_SLOTS)
            }
            val idrSec = if (keys != null || cap.intraRefresh) IDR_INTERVAL_RECOVERABLE_SEC
                         else IDR_INTERVAL_BARE_SEC
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, idrSec)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            }
        }
        c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        // 새김 결과를 프레임별 출력 포맷으로 되받으려면 **인코더의 응답 자리**를 구독한다.
        // (우리가 쓰는 `mark`를 구독하면 우리 값이 그대로 되비쳐 확인이 늘 성공한다.)
        if (keys != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { c.subscribeToVendorParameters(listOf(keys.info)) }
                .onFailure { Log.w(TAG, "LTR 응답 구독 실패: ${it.message}") }
        }

        val s = c.createInputSurface()
        c.start()
        codec = c
        caps = cap
        ltr = keys
        synchronized(ltrLock) {
            pendingMark = -1; confirmedSlot = -1; framesSinceMark = 0; warnedUnconfirmed = false
        }
        Log.i(TAG, "인코더 ${cap.describe()} ${width}x${height}@${fps} ${bitrateKbps}kbps " +
            (if (keys != null) "+LTR(${LTR_SLOTS}슬롯)" else "LTR없음") +
            " 주기IDR=${if (keys != null || cap.intraRefresh) IDR_INTERVAL_RECOVERABLE_SEC else IDR_INTERVAL_BARE_SEC}초")
        s
    }.getOrElse {
        Log.e(TAG, "인코더 구성 실패($mime): ${it.message}")
        runCatching { codec?.release() }
        codec = null
        caps = null
        ltr = null
        null
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        csd = null
        caps = null
        ltr = null
    }

    // ── 복구 요청 (제어 스레드에서 불린다) ─────────────────────────────────────

    /**
     * 참조가 아예 없는 수신측을 위한 IDR. **Mac의 `requestKeyframe()` 대응.**
     *
     * 디코더를 방금 세운 쪽에는 참조 프레임이 하나도 없으므로 LTR-P로는 아무것도 못 그린다 —
     * 확장 경로에서 실기기의 검은 화면이 정확히 그 이유였다.
     */
    fun requestKeyframe(why: String) {
        val c = codec ?: return
        runCatching {
            c.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }.onFailure { Log.w(TAG, "IDR 요청 실패: ${it.message}"); return }
        Log.i(TAG, "IDR 요청 — $why")
    }

    /**
     * 확인된 앵커를 참조하는 작은 프레임을 낸다. **Mac의 `forceLTRRefresh()` 대응.**
     *
     * @return 앵커가 없으면 false — 그러면 호출자가 [requestKeyframe]으로 물러나야 한다.
     */
    fun refreshFromLtr(why: String): Boolean {
        val c = codec ?: return false
        val keys = ltr ?: return false
        val slot = synchronized(ltrLock) { confirmedSlot }
        if (slot < 0) return false
        runCatching {
            c.setParameters(Bundle().apply { putInt(keys.use, slot) })
        }.onFailure { Log.w(TAG, "LTR 참조 요청 실패: ${it.message}"); return false }
        Log.i(TAG, "LTR 복구 — 슬롯 $slot 참조 ($why)")
        return true
    }

    /**
     * Mac이 이 슬롯의 앵커 프레임을 온전히 받았다. **Mac의 `acknowledgeLTR()` 대응.**
     *
     * 확인되기 전의 슬롯은 절대 참조하지 않는다 — 받는 쪽에 없는 프레임을 가리키면 화면이
     * 조용히 깨지고, 그건 IDR 한 번 더 보내는 것보다 나쁘다.
     */
    fun confirmLtr(slot: Int) {
        if (slot !in 0 until LTR_SLOTS) return
        synchronized(ltrLock) {
            if (confirmedSlot == slot) return
            confirmedSlot = slot
        }
        Log.i(TAG, "LTR 앵커 확인 — 슬롯 $slot")
    }

    // ── 인코더 출력 루프 ───────────────────────────────────────────────────────

    /** 인코더 출력 루프. 완성된 AU를 그대로 위로 올린다(패킷화는 전송 계층의 일). */
    private fun drain() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (running) {
            val idx = try {
                c.dequeueOutputBuffer(info, 10_000)
            } catch (t: Throwable) {
                if (running) onError("인코더 오류: ${t.message}")
                return
            }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // csd-0/csd-1에 파라미터 세트가 들어 있다. 플랫폼이 키프레임에 안 붙여 주는
                    // 기기를 위해 여기서 보관해 뒀다가 우리가 붙인다.
                    val f = c.outputFormat
                    val sets = listOfNotNull(f.getByteBufferOrNull("csd-0"),
                                             f.getByteBufferOrNull("csd-1"),
                                             f.getByteBufferOrNull("csd-2"))
                    if (sets.isNotEmpty()) {
                        val out = java.io.ByteArrayOutputStream()
                        for (b in sets) { val a = ByteArray(b.remaining()); b.get(a); out.write(a) }
                        csd = out.toByteArray()
                        Log.i(TAG, "파라미터 세트 ${csd?.size}B 확보")
                    }
                }
                idx >= 0 -> {
                    val buf = c.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        val keyframe = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (!isConfig) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            var au = ByteArray(info.size).also { buf.get(it) }
                            // 키프레임인데 파라미터 세트가 안 붙어 있으면 우리가 붙인다.
                            // **판정은 실제 바이트로 한다** — `KEY_PREPEND_HEADER_TO_SYNC_FRAMES`를
                            // 요청했다고 해서 받아들여졌다는 보장이 없고(무시하는 기기가 있다),
                            // 플래그를 믿고 안 붙이면 그 기기에선 Mac이 영영 검은 화면이 된다.
                            // 반대로 무조건 붙이면 이미 붙여 주는 기기에선 중복이 된다.
                            val sets = csd
                            if (keyframe && sets != null && !startsWithParameterSet(au)) {
                                au = sets + au
                            }
                            val slot = settleLtr(c, idx)
                            onFrame(au, keyframe, info.presentationTimeUs / 1000, slot)
                        }
                    }
                    runCatching { c.releaseOutputBuffer(idx, false) }
                }
            }
        }
    }

    /**
     * 이 프레임의 LTR 상태를 정리한다 — 새길 때가 됐으면 청하고, 새겨졌으면 확인한다.
     *
     * @return 이 프레임이 새겨진 슬롯 번호(-1 = 앵커 아님).
     */
    private fun settleLtr(c: MediaCodec, outIndex: Int): Int {
        val keys = ltr ?: return -1

        // ① 새김이 진행 중이면 이 프레임이 그 프레임인지 확인한다.
        //
        // ★ **확인되지 않은 새김은 절대 앵커로 쓰지 않는다.** `setParameters`에는 프레임을
        // 지목할 방법이 없어서(Mac은 `VTCompressionSessionEncodeFrame(frameProperties:)`로
        // 정확히 그 프레임에 붙인다) "어느 프레임에 붙었는지"를 인코더가 되알려 줘야만
        // 안다. 안 알려 주는 기기에서는 LTR이 조용히 꺼진 채로 남는다 — 그게 옳다.
        // 없는 참조를 가리키면 화면이 깨지는데 받는 쪽은 그 사실을 알 수조차 없다.
        val pending = synchronized(ltrLock) { pendingMark }
        var marked = -1
        val info = keys.info
        if (pending >= 0) {
            // ⚠ **인코더가 쓰는 자리를 읽는다.** 우리가 쓴 `mark`를 되읽으면 우리 값이 그대로
            // 나와서 확인이 언제나 성공하고, LTR이 도는 것처럼 보이는 채로 아무 일도 안 한다.
            val reported = runCatching { c.getOutputFormat(outIndex) }.getOrNull()
                ?.let { f -> if (f.containsKey(info)) f.getInteger(info) else null }
            if (reported == pending) {
                marked = pending
                synchronized(ltrLock) { pendingMark = -1 }
                Log.i(TAG, "LTR 앵커 새김 — 슬롯 $marked (Mac 확인 대기)")
            }
        }

        // ② 새길 때가 됐는가. 1초에 한 번이면 충분하다 — 앵커는 촘촘할수록 좋은 게 아니라
        //    "손실 직전의 온전한 지점"이면 되고, 자주 새기면 그만큼 P 프레임이 커진다.
        val due = synchronized(ltrLock) {
            framesSinceMark++
            if (pendingMark >= 0 || framesSinceMark < fps) return@synchronized -1
            framesSinceMark = 0
            // 확인된 앵커는 덮지 않는다 — 새 앵커가 확인되기 전에 옛것을 잃으면
            // 그 사이에 난 손실을 복구할 수 없다.
            val next = if (confirmedSlot == 0) 1 else 0
            pendingMark = next
            next
        }
        if (due >= 0) {
            runCatching { c.setParameters(Bundle().apply { putInt(keys.mark, due) }) }
                .onFailure {
                    Log.w(TAG, "LTR 새김 요청 실패: ${it.message}")
                    synchronized(ltrLock) { pendingMark = -1 }
                }
        }

        // ③ 청하기만 하고 확인이 한 번도 안 온다 — 이 인코더는 응답을 안 준다.
        //
        // 그러면 **LTR을 통째로 내린다.** 반쯤 켜 둔 채로 두면 매 프레임 출력 포맷을 읽으며
        // 영영 오지 않을 확인을 기다리게 되고, 무엇보다 "LTR이 있다"고 믿는 상태가 남는다.
        // 여기서 끄면 [refreshFromLtr]이 false를 돌려주고 복구는 IDR로 깨끗하게 물러난다.
        if (marked < 0 && pending >= 0) {
            val giveUp = synchronized(ltrLock) {
                if (warnedUnconfirmed || framesSinceMark < fps * 3) false
                else { warnedUnconfirmed = true; pendingMark = -1; true }
            }
            if (giveUp) {
                ltr = null
                Log.w(TAG, "LTR 새김이 확인되지 않는다 — `${keys.mark}`에 쓴 슬롯이 " +
                    "`$info`로 돌아오지 않는다. LTR을 끄고 인트라 리프레시/IDR로만 간다.")
            }
        }
        return marked
    }

    /**
     * 이 AU가 파라미터 세트로 시작하는가 — 첫 시작 코드 뒤의 NAL 종류로 판정한다.
     * H.264: SPS(7)/PPS(8), H.265: VPS(32)/SPS(33)/PPS(34).
     */
    private fun startsWithParameterSet(au: ByteArray): Boolean {
        if (au.size < 5 || au[0].toInt() != 0 || au[1].toInt() != 0) return false
        val head = when {
            au[2].toInt() == 1 -> 3
            au[2].toInt() == 0 && au[3].toInt() == 1 -> 4
            else -> return false
        }
        if (head >= au.size) return false
        val b = au[head].toInt() and 0xFF
        return if (isHevc) ((b shr 1) and 0x3F) in 32..34 else (b and 0x1F) in 7..8
    }

    private fun MediaFormat.getByteBufferOrNull(key: String): ByteBuffer? =
        runCatching { if (containsKey(key)) getByteBuffer(key) else null }.getOrNull()
}
