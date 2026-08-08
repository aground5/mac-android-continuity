package com.peri.display.share

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

private const val TAG = "ScreenEncoder"

/**
 * 화면이 멈춰 있을 때 마지막 프레임을 되풀이하는 간격(µs).
 *
 * 1초로 잡은 근거: 이보다 짧으면 정지 화면에서 쓸데없는 프레임이 계속 나가고(그래도 P
 * 프레임이라 몇백 바이트다), 길면 2초 IDR 주기와 맞물려 새로 붙은 수신측이 최대 3초를
 * 기다린다. 1초면 IDR 주기 안에 반드시 한 번은 흐름이 생긴다.
 */
private const val REPEAT_STILL_FRAME_US = 1_000_000L

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
 */
class ScreenEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrateKbps: Int,
    /** true=H.265, false=H.264. 기기가 H.265 인코더를 못 만들면 스스로 H.264로 내려간다. */
    preferHevc: Boolean,
    /** 완성된 접근 단위 하나. **인코더 스레드에서** 불린다. */
    private val onFrame: (bitstream: ByteArray, keyframe: Boolean, ptsMs: Long) -> Unit,
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
            // 아무것도 안 내놓고, 주기 IDR조차 나오지 않아 지금 막 붙은 Mac은 영원히 검은
            // 화면을 본다("공유는 켜졌는데 아무것도 안 보인다"). 마지막 프레임을 되풀이해
            // 흐름이 끊기지 않게 한다.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_STILL_FRAME_US)
            // 주기 IDR은 2초. 확장 경로처럼 LTR 복구를 쓰지 않으므로(공유는 짧게 쓰고 끄는
            // 성격이다) 손실이 나면 다음 IDR을 기다리는 게 가장 단순한 복구다.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BITRATE_MODE,
                       MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            // B 프레임 금지 — 있으면 표시 순서와 디코드 순서가 갈려 저지연 표시가 무너진다.
            setInteger(MediaFormat.KEY_LATENCY, 1)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            }
        }
        val c = MediaCodec.createEncoderByType(mime)
        c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val s = c.createInputSurface()
        c.start()
        codec = c
        Log.i(TAG, "인코더 $mime ${width}x${height}@${fps} ${bitrateKbps}kbps")
        s
    }.getOrElse {
        Log.e(TAG, "인코더 구성 실패($mime): ${it.message}")
        runCatching { codec?.release() }
        codec = null
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
    }

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
                            onFrame(au, keyframe, info.presentationTimeUs / 1000)
                        }
                    }
                    runCatching { c.releaseOutputBuffer(idx, false) }
                }
            }
        }
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
