package com.peri.display.decode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.peri.display.CodecSupport
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

private const val TAG = "VideoDecoder"

/**
 * 프레임을 넣고 있는데 이만큼 출력이 없으면 **코덱이 안에서 멎은 것으로 본다.**
 *
 * 짧게 잡을 수 없다: `output.delay`가 20 안팎이라(실기기 로그에서 25) 정상 기동에도
 * 수십 프레임이 들어간 뒤에야 첫 출력이 나온다. 2초면 30fps 기준 60프레임이라 그 지연을
 * 넉넉히 덮으면서, 사람이 "멈췄나?" 하기 직전에 스스로 복구한다.
 */
private const val OUTPUT_STALL_NS = 2_000_000_000L

/**
 * Annex-B H.264/H.265 비트스트림을 받아 Surface에 디코딩 렌더링한다 (슬라이스1).
 *
 * 첫 키프레임에서 SPS/PPS(+VPS)를 추출해 MediaCodec을 설정한다. 키프레임 전
 * 프레임은 폐기. 동기 모드 + 전용 디코드 스레드.
 */
class VideoDecoder(private val surface: Surface) {

    private data class Frame(val data: ByteArray, val keyframe: Boolean, val ptsUs: Long,
                             val codec: Int, val arrivalNs: Long,
                             val ptsHostUs: Long, val macSendUs: Long, val qDepth: Int)

    private val queue = LinkedBlockingQueue<Frame>(60)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    /** Mac↔Android 클럭 오프셋(µs) = androidµs - macµs. MirrorController가 Pong NTP로 갱신.
     *  Mac host 타임스탬프에 더하면 Android 시계로 매핑됨. 0이면 미동기(계측 로그 스킵). */
    @Volatile var clockOffsetUs: Long = 0
    @Volatile var clockSynced: Boolean = false

    /** 이번 세션이 HDR10(10비트 PQ/Rec.2020)인가. Mac의 SessionParams가 **확정한** 값이다
     *  (Mac이 실제로 HDR 파이프라인을 세우는 데 성공했을 때만 HDR로 온다).
     *  SessionParams는 첫 영상 프레임보다 먼저 도착하므로 configure() 시점엔 이미 정해져 있다. */
    @Volatile private var hdr10: Boolean = false

    /** HDR 정적 메타데이터(CTA-861.3 Static Metadata Descriptor 원본, 25B).
     *  Mac이 소스 마스터링 조건을 기술해 보낸 값 — 없으면 폰이 기본값(보통 1000nit 마스터링)을
     *  가정해 톤매핑한다. Mac이 이미 KEY_HDR_STATIC_INFO 형식으로 만들어 주므로 해석하지 않는다. */
    @Volatile private var hdrStaticInfo: ByteArray? = null

    /** 디코더 설정 세대. 다이내믹 레인지가 바뀌면 올라가고, 디코드 루프가 다음 키프레임에서
     *  MediaCodec을 **새로 만든다**.
     *
     *  ★ 왜 새로 만들어야 하나: MediaCodec은 configure() 시점의 색상 설정을 그대로 붙들고 있다.
     *  HDR10으로 한 번 설정되면 KEY_COLOR_TRANSFER=ST2084 / KEY_COLOR_STANDARD=BT2020 /
     *  KEY_HDR_STATIC_INFO가 박힌 채 남아서, Mac이 SDR로 바꿔 보내도 8비트 감마 영상을
     *  PQ로 해석해 색이 통째로 뒤틀린다. 새 SPS를 csd로 줘도 명시 설정이 이긴다. */
    private val configGen = java.util.concurrent.atomic.AtomicInteger(0)

    /** Mac SessionParams가 확정한 다이내믹 레인지를 반영한다. 값이 바뀌면 디코더를 다시 만든다.
     *  @return 재구성이 필요해졌으면 true (호출자가 키프레임을 요청해야 한다 — 확장 경로의
     *          주기 IDR은 2초라 그냥 두면 그만큼 화면이 멈춘다). */
    fun applyDynamicRange(hdr10: Boolean, staticInfo: ByteArray?): Boolean {
        val changed = (this.hdr10 != hdr10) || !(this.hdrStaticInfo contentEquals staticInfo)
        this.hdr10 = hdr10
        this.hdrStaticInfo = staticInfo
        if (changed) {
            configGen.incrementAndGet()
            // 곧 버려질 디코더다 — 참조는 **지금** 없어진 것으로 본다. 루프가 실제로
            // 다시 세울 때까지 기다리면 그 사이의 갱신 요청이 "참조가 있다"고 거짓말한다.
            hasReferences = false
        }
        return changed
    }

    /**
     * 이 디코더에 **참조 프레임이 있는가** — 키프레임을 받아 실제로 서 있는 상태인가.
     *
     * 갱신을 청할 때 무엇을 청할지가 이 값 하나로 갈린다. 참조가 있으면 작은 LTR-P로
     * 이어 그릴 수 있지만, 없으면 SPS/PPS가 실린 IDR이라야 한다 — LTR-P는 아무리 받아도
     * 풀 수가 없어서 화면이 검은 채로 남는다.
     *
     * **디코더가 답해야 하는 질문이다.** 호출부가 "지금쯤 참조가 있겠지"로 고르면 언젠가
     * 틀리고, 틀린 그 경우가 정확히 검은 화면이다.
     */
    @Volatile
    var hasReferences: Boolean = false
        private set

    // 지연 계측 1초 집계 (구간별 µs 누적 + 카운트). recordTiming에서 사용.
    private var tMacEnc = 0L; private var tNet = 0L; private var tJbuf = 0L
    private var tDec = 0L; private var tTotal = 0L; private var tQDepth = 0L
    private var tCount = 0; private var tLastLogNs = 0L

    // ★ 고정 지터 버퍼 + 지연 인식형 비트레이트(제어를 수신→송신으로 반전):
    //   버퍼 깊이를 *고정*해 렌더 타임라인을 완전히 안정화(버퍼 변화가 스스로 지터가 되는 문제를
    //   원천 제거)하고, 대신 *비트레이트*(Mac)를 조절해 실제 큐잉지연 q가 이 고정 예산 안에 들게
    //   유지한다. 프레임 온타임 조건: arrivalNs ≤ ptsNs+dMin+buffer ⟺ q(=d-dMin) ≤ buffer.
    //   → q를 버퍼 예산 밑으로 유지하면 hitch/펄싱 없음. 버퍼는 링크 고유(innocent) 지터 흡수용.
    //   dMin = 최근 최소 d(PTS→로컬시계 앵커; leak로 드리프트 추종). q(EWMA)는 Mac 비트레이트
    //   신호로 노출(queueDelayMs). 각 프레임을 renderNs까지 입력 큐에서 대기 후 디코드→즉시 렌더.
    //   ※ 출력 버퍼를 미래로 잡는 releaseOutputBuffer(_, 미래시각)는 SurfaceView BufferQueue
    //     (슬롯 3~4개)를 굶겨 디코더 정지→지연 폭증·깨짐 → 반드시 입력측에서.
    //   ※ Mac BitrateController의 highMs/lowMs 임계는 이 버퍼 값과 커플링(예산 대비 q 유지).
    private val driftLeakNs = 500L           // dMin 프레임당 상향 누수(클럭 양의 드리프트 추종)
    private var dMinNs = Long.MIN_VALUE       // 미설정 sentinel
    private var qAvgNs = 0.0                  // 큐잉지연 EWMA(ns) — Mac 비트레이트 제어 신호
    private var lastPtsUs = Long.MIN_VALUE

    /** 큐잉지연 평활 평균(ms). 지연 인식형 비트레이트 제어(Mac)용 — 일시 스파이크가 아닌
     *  지속적 큐 쌓임을 반영하도록 peak가 아닌 EWMA를 보고한다. */
    @Volatile var queueDelayMs: Int = 0
        private set

    /** 파이프라인 구간 평균(ms, 최근 1초) — recordTiming 플러시 때 갱신. TransportFeedback에
     *  실어 Mac 상세 화면의 지연 스택에 표시(제어에는 미사용). 0 = 아직 미측정/클럭 미동기. */
    @Volatile var pipeNetMs: Int = 0
        private set
    @Volatile var pipeJbufMs: Int = 0
        private set
    @Volatile var pipeDecMs: Int = 0
        private set

    /** 고정 지터 버퍼 깊이(ms) = 추가 지연 예산. Mac PlayoutConfig(UI 슬라이더)로 라이브 조절.
     *  렌더 타임라인 안정 위해 상수처럼 유지하고, q를 이 밑으로 두는 건 Mac 비트레이트 몫. */
    @Volatile var bufferMs: Int = 50

    /** AOA(신뢰·저지터 파이프): true면 PTS 고정버퍼 대기를 생략하고 도착 즉시 렌더 — USB는 전송
     *  지터가 사실상 0이라 버퍼가 순수 지연 낭비. 큐잉지연 q는 계속 *측정만* 해서 Mac 포화 가드
     *  신호(queueDelayMs)로 노출한다(측정 ≠ 버퍼링). LAN은 false(고정버퍼로 Wi-Fi 스파이크 흡수). */
    @Volatile var renderOnArrival: Boolean = false

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread({ loop() }, "peri-decode").also { it.start() }
    }

    fun submit(data: ByteArray, keyframe: Boolean, ptsUs: Long, codec: Int,
               ptsHostUs: Long = 0, macSendUs: Long = 0) {
        if (!running.get()) return
        // 도착(submit) 시각 + 현재 입력 큐 깊이 기록 (수신측 버퍼링 계측)
        val frame = Frame(data, keyframe, ptsUs, codec, System.nanoTime(), ptsHostUs, macSendUs, queue.size)
        // 큐가 가득 차면 오래된 프레임을 버려 지연 누적을 막는다.
        if (!queue.offer(frame)) {
            queue.poll()
            queue.offer(frame)
        }
    }

    fun stop() {
        running.set(false)
        hasReferences = false
        thread?.interrupt()
        thread = null
        queue.clear()
    }

    /** 키프레임을 기다리며 버린 프레임 수 — 검은 화면의 길이가 곧 이 값이다. */
    private var droppedWaiting = 0
    private var waitingSinceNs = 0L
    private var lastWaitLogNs = 0L

    /** 마지막으로 **화면에 나간** 시각. 코덱이 안에서 멎었는지 판정하는 유일한 근거. */
    private var lastOutputNs = 0L

    /**
     * 디코더가 스스로 복구할 수 없어 **IDR이 필요하다**. 확장 앱이 갱신 요청으로 잇는다.
     *
     * 왜 디코더가 청하는가: 손상을 실제로 만나는 곳이 여기뿐이다. 세션은 패킷이 도착했다는
     * 것까지만 알고, 그 패킷이 그려졌는지는 모른다.
     *
     * 사유를 **자기 어휘로** 넘긴다([Fault]) — 와이어 enum을 여기서 알면 미디어 부품이
     * 프로토콜에 매인다. 옮겨 적는 건 확장 앱의 몫이다.
     */
    @Volatile
    var onNeedIdr: ((Fault) -> Unit)? = null

    /** 디코더가 스스로 못 고치는 두 가지. 둘 다 결과는 같다 — 코덱을 버리고 다시 세운다. */
    enum class Fault {
        /** 손상된 비트스트림에 코덱이 예외를 냈다. */
        DECODE_ERROR,

        /** 넣는데 안 나온다 — 코덱이 안에서 멎었다. */
        OUTPUT_STALL,
    }

    private fun loop() {
        var codec: MediaCodec? = null
        var codecGen = -1
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val frame = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue

                // 다이내믹 레인지가 바뀌었으면 색상 설정이 굳어 있는 디코더를 버린다.
                if (codec != null && codecGen != configGen.get()) {
                    Log.i(TAG, "다이내믹 레인지 변경 → 디코더 재구성")
                    runCatching { codec.stop() }
                    runCatching { codec.release() }
                    codec = null
                }

                // 첫 키프레임 전까지는 설정 불가 → 폐기.
                //
                // ★ **이 대기를 반드시 소리 내어 말한다.** 예전엔 조용히 `continue`였고,
                // 그래서 "검은 화면인데 로그가 하나도 안 나온다"가 됐다. 실패가 아니라
                // **아무 일도 안 일어나는** 상태라 어디에도 흔적이 없었다 — 패킷은 도착하고,
                // 복호도 성공하고, 통계도 돌고, 화면만 검다.
                //
                // 이 줄이 진단에서 하는 일이 정확히 하나 있다: **검은 화면의 원인을 둘로
                // 가른다.** 확장 인코더는 2초마다 IDR을 내므로, 이 경고가 2초 넘게 이어지면
                // 프레임이 오긴 오는데 키프레임이 안 오는 것이고, 이 경고가 아예 안 찍히면
                // 프레임 자체가 안 오는 것이다(그때는 `영상이 …ms째 없다`가 대신 찍힌다).
                if (codec == null) {
                    if (!frame.keyframe) {
                        droppedWaiting++
                        val now = System.nanoTime()
                        if (waitingSinceNs == 0L) waitingSinceNs = now
                        if (now - lastWaitLogNs > 1_000_000_000L) {
                            lastWaitLogNs = now
                            Log.w(TAG, "키프레임 대기 중 — ${droppedWaiting}프레임 폐기 " +
                                "(${(now - waitingSinceNs) / 1_000_000}ms). 갱신 요청이 나갔는지 확인할 것")
                        }
                        continue
                    }
                    codecGen = configGen.get()
                    codec = configure(frame) ?: continue
                    hasReferences = true
                    lastOutputNs = System.nanoTime()   // 출력 감시의 기준점
                    if (droppedWaiting > 0) {
                        Log.i(TAG, "키프레임 도착 — ${droppedWaiting}프레임 버리고 재생 시작 " +
                            "(${(System.nanoTime() - waitingSinceNs) / 1_000_000}ms 대기)")
                    }
                    droppedWaiting = 0
                    waitingSinceNs = 0L
                }

                pace(frame.ptsUs, frame.arrivalNs)   // 적응형 PTS 페이싱 (입력측 지터 흡수)
                val releaseNs = System.nanoTime()    // 지터버퍼 대기 끝 → 디코드 투입 시각
                try {
                    feed(codec, frame)
                    drain(codec, bufferInfo)
                } catch (e: IllegalStateException) {
                    // ★ `MediaCodec.CodecException`이 여기 포함된다(그 부모가 IllegalState다).
                    //
                    // 예전엔 이 예외가 루프 **전체**를 빠져나갔다. 그러면 스레드가 죽는데
                    // `decoder` 참조는 non-null로 남고 `hasReferences`도 true인 채라, 바깥에서
                    // 보기에 디코더는 멀쩡히 서 있다. 아무도 다시 세우지 않고, 갱신을 청해도
                    // "참조가 있다"고 답한다 — 로그 한 줄과 영구 검은 화면.
                    //
                    // 손상된 비트스트림 한 장에 세션이 끝나서는 안 된다. 코덱만 버리고
                    // 처음부터 다시 세운다.
                    codec = rebuild(codec, Fault.DECODE_ERROR, e.message ?: "?")
                    continue
                }
                recordTiming(frame, releaseNs, System.nanoTime())   // 구간별 지연 계측

                // 넣기만 하고 안 나온다 = 코덱이 안에서 멎었다. 입력 예외도 아니라서
                // 위 갈래에 안 걸리고, 그대로 두면 화면이 마지막 프레임에 얼어붙는다.
                val sinceOutputMs = (System.nanoTime() - lastOutputNs) / 1_000_000
                if (sinceOutputMs > OUTPUT_STALL_NS / 1_000_000) {
                    codec = rebuild(codec, Fault.OUTPUT_STALL, "${sinceOutputMs}ms째 출력 없음")
                }
            }
        } catch (_: InterruptedException) {
            // stop()
        } catch (t: Throwable) {
            Log.e(TAG, "decode loop error: ${t.message}", t)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    private fun configure(keyframe: Frame): MediaCodec? {
        val mime = if (keyframe.codec == 1) MediaFormat.MIMETYPE_VIDEO_HEVC
                   else MediaFormat.MIMETYPE_VIDEO_AVC
        val nals = splitNals(keyframe.data)

        // 실제 해상도는 SPS에서 디코더가 읽어 INFO_OUTPUT_FORMAT_CHANGED로 보정한다.
        val format = MediaFormat.createVideoFormat(mime, 1280, 720)

        if (mime == MediaFormat.MIMETYPE_VIDEO_AVC) {
            val sps = nals.firstOrNull { nalType(it, false) == 7 }
            val pps = nals.firstOrNull { nalType(it, false) == 8 }
            if (sps != null) format.setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(sps)))
            if (pps != null) format.setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(pps)))
        } else {
            // HEVC: VPS(32)+SPS(33)+PPS(34)를 csd-0에 연결
            val vps = nals.firstOrNull { nalType(it, true) == 32 }
            val sps = nals.firstOrNull { nalType(it, true) == 33 }
            val pps = nals.firstOrNull { nalType(it, true) == 34 }
            if (vps != null && sps != null && pps != null) {
                val csd = withStartCode(vps) + withStartCode(sps) + withStartCode(pps)
                format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            }
        }

        // HDR10이면 디코더를 **직접 고른다**. createDecoderByType은 프로파일을 안 보고 첫 HEVC
        // 디코더를 주고, findDecoderForFormat도 하드웨어 여부·KEY_PROFILE을 선택에 반영하지
        // 않는다 → 10비트를 SW로 풀게 되면 그대로 프레임 드롭이다.
        // (SDR 경로는 손대지 않는다 — 이미 동작하는 선택 로직을 HDR 때문에 흔들 이유가 없다.)
        val hdrDecoderName = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC && hdr10) {
            format.setInteger(MediaFormat.KEY_PROFILE,
                              MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
            hdrStaticInfo?.let {
                format.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, ByteBuffer.wrap(it))
            }
            CodecSupport.main10DecoderName()
        } else null

        return try {
            val codec = if (hdrDecoderName != null) MediaCodec.createByCodecName(hdrDecoderName)
                        else MediaCodec.createDecoderByType(mime)
            codec.apply {
                configure(format, surface, null, 0)
                start()
                // 하드웨어 가속 여부를 실제로 확인해 남긴다 — "HW Main10 디코더가 존재한다"와
                // "지금 그놈을 쓰고 있다"는 다른 얘기고, 갈리면 조용히 느려질 뿐이라 눈에 안 띈다.
                val accel = runCatching { codecInfo.isHardwareAccelerated }.getOrNull()
                Log.i(TAG, "decoder configured: $mime ${if (hdr10) "HDR10(Main10/PQ)" else "SDR"} " +
                           "codec=${runCatching { name }.getOrNull() ?: "?"} " +
                           "hwAccelerated=${accel ?: "?"}")
                if (hdr10 && accel == false) {
                    Log.w(TAG, "HDR인데 소프트웨어 디코더가 잡혔다 — 프레임 드롭 위험")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "configure failed: ${t.message}", t)
            null
        }
    }

    private fun feed(codec: MediaCodec, frame: Frame) {
        val index = codec.dequeueInputBuffer(10_000)
        if (index < 0) return
        val input = codec.getInputBuffer(index) ?: return
        input.clear()
        input.put(frame.data)
        codec.queueInputBuffer(index, 0, frame.data.size, frame.ptsUs, 0)
    }

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> {
                    codec.releaseOutputBuffer(index, true)   // Surface에 즉시 렌더
                    lastOutputNs = System.nanoTime()         // 살아 있다는 유일한 증거
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    Log.i(TAG, "output format: ${codec.outputFormat}")
                else -> return
            }
        }
    }

    /**
     * 코덱을 버리고 처음부터 다시 세운다 — **IDR을 청하고 돌아온다.**
     *
     * 참조를 먼저 내리는 순서가 중요하다. [onNeedIdr]이 부르는 쪽은 [hasReferences]를 보고
     * 무엇을 청할지 정하는데, 그 전에 내려두지 않으면 "참조가 있다"고 답해 작은 LTR-P를
     * 받게 되고 — 방금 코덱을 버린 우리는 그걸 풀 수 없다.
     */
    private fun rebuild(codec: MediaCodec?, fault: Fault, detail: String): MediaCodec? {
        Log.w(TAG, "디코더 재구성 — $fault ($detail)")
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        hasReferences = false
        onNeedIdr?.invoke(fault)
        return null
    }

    /**
     * 입력측 고정-버퍼 PTS 페이싱. 디코드 전에 표시 시각(renderNs = ptsNs + dMin + 고정버퍼)까지
     * 대기 → 렌더 타임라인이 완전히 안정(버퍼가 스스로 지터가 되지 않음). 큐잉지연 q는 EWMA로
     * 산출해 Mac 비트레이트 제어 신호로 노출(Mac이 q를 버퍼 예산 밑으로 유지). PTS 역행/재구성 시 리셋.
     */
    private fun pace(ptsUs: Long, arrivalNs: Long) {
        val ptsNs = ptsUs * 1000
        val d = arrivalNs - ptsNs
        if (dMinNs == Long.MIN_VALUE || ptsUs < lastPtsUs || ptsUs - lastPtsUs > 2_000_000) {
            dMinNs = d; qAvgNs = 0.0; lastPtsUs = ptsUs      // 첫 프레임/불연속 → 리셋
        } else {
            lastPtsUs = ptsUs
            dMinNs += driftLeakNs                  // 양의 클럭 드리프트 추종(천천히 상향)
            if (d < dMinNs) dMinNs = d             // 최단경로 갱신(즉시 하향)
        }
        val q = (d - dMinNs).coerceAtLeast(0L).toDouble()   // 큐잉지연(ns)
        qAvgNs += 0.05 * (q - qAvgNs)                       // EWMA → Mac 비트레이트 제어 신호
        queueDelayMs = (qAvgNs / 1_000_000.0).toInt()
        if (renderOnArrival) return                         // AOA: 측정만, 버퍼 대기 없이 즉시 렌더
        val renderNs = ptsNs + dMinNs + bufferMs.toLong() * 1_000_000L   // 고정 버퍼(라이브 설정값)
        val waitNs = renderNs - System.nanoTime()
        if (waitNs > 0) LockSupport.parkNanos(waitNs)
    }

    /**
     * 캡처→표시 경로를 구간 분해해 1초마다 평균 로그 — Mac/네트워크/Android 어디서 지연·버퍼링인지.
     *  macEnc = Mac 인코드+egress(캡처→송신, Mac단) · net = 네트워크+재조립(Wi-Fi 큐, offset로 매핑)
     *  · jbuf = 수신 지터버퍼 대기(입력큐+park, peripheral단) · dec = 디코드 · total = 캡처→디코드출력
     *  전체. qd = submit 시 입력큐 깊이(프레임 단위 수신 버퍼링). 클럭 미동기면 스킵.
     */
    private fun recordTiming(frame: Frame, releaseNs: Long, decodeOutNs: Long) {
        if (!clockSynced || frame.macSendUs <= 0 || frame.ptsHostUs <= 0) return
        val off = clockOffsetUs
        val submitUs = frame.arrivalNs / 1000
        val macEnc = frame.macSendUs - frame.ptsHostUs           // Mac 클럭(캡처→송신)
        val net = submitUs - (frame.macSendUs + off)             // 네트워크+재조립
        val jbuf = (releaseNs - frame.arrivalNs) / 1000          // 수신 지터버퍼 대기
        val dec = (decodeOutNs - releaseNs) / 1000               // 디코드
        val total = decodeOutNs / 1000 - (frame.ptsHostUs + off) // 캡처→디코드출력
        tMacEnc += macEnc; tNet += net; tJbuf += jbuf; tDec += dec; tTotal += total
        tQDepth += frame.qDepth; tCount++
        if (tLastLogNs == 0L) tLastLogNs = decodeOutNs
        if (decodeOutNs - tLastLogNs >= 1_000_000_000L) {
            val n = tCount
            Log.i(TAG, "PIPE macEnc=${tMacEnc / n / 1000}ms net=${tNet / n / 1000}ms " +
                    "jbuf=${tJbuf / n / 1000}ms dec=${tDec / n / 1000}ms total=${tTotal / n / 1000}ms " +
                    "| qd=${tQDepth / n} off=${off / 1000}ms n=$n")
            pipeNetMs = (tNet / n / 1000).toInt().coerceAtLeast(0)
            pipeJbufMs = (tJbuf / n / 1000).toInt().coerceAtLeast(0)
            pipeDecMs = (tDec / n / 1000).toInt().coerceAtLeast(0)
            tMacEnc = 0; tNet = 0; tJbuf = 0; tDec = 0; tTotal = 0; tQDepth = 0; tCount = 0
            tLastLogNs = decodeOutNs
        }
    }

    // MARK: - Annex-B NAL helpers

    /** Annex-B 버퍼를 start code 기준으로 NAL(코드 제외)들로 분리. */
    private fun splitNals(data: ByteArray): List<ByteArray> {
        val nals = ArrayList<ByteArray>()
        var i = 0
        var start = -1
        while (i < data.size - 3) {
            val isCode4 = data[i].toInt() == 0 && data[i + 1].toInt() == 0 &&
                          data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1
            val isCode3 = data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1
            if (isCode4 || isCode3) {
                val codeLen = if (isCode4) 4 else 3
                if (start >= 0) nals.add(data.copyOfRange(start, i))
                start = i + codeLen
                i += codeLen
            } else i++
        }
        if (start in 0 until data.size) nals.add(data.copyOfRange(start, data.size))
        return nals
    }

    private fun nalType(nal: ByteArray, hevc: Boolean): Int {
        if (nal.isEmpty()) return -1
        return if (hevc) (nal[0].toInt() and 0x7E) shr 1
               else nal[0].toInt() and 0x1F
    }

    private fun withStartCode(nal: ByteArray): ByteArray =
        byteArrayOf(0, 0, 0, 1) + nal
}
