package com.peri.display.share

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager

private const val TAG = "ScreenShare"

/**
 * 화면 공유(앱 #2)의 Android 쪽 절반 — **동의 → 포그라운드 서비스 → 캡처 → 인코딩**.
 *
 * 왜 프로세스 단일 객체인가
 * ─────────────────────────
 * 동의 대화상자는 Activity가 띄우고, `MediaProjection` 자체는 포그라운드 서비스가 얻어야 하며
 * (Android 14+), 프레임을 실어 보내는 건 컨트롤러다. 셋이 서로 다른 수명을 사는데 이 흐름은
 * 하나로 이어져야 한다 — 중간에 화면을 회전하면 Activity가 재생성되지만 공유는 끊기면 안 된다.
 *
 * 흐름
 * ────
 *   Mac: ShareRequest(START)
 *     → [requestConsent]  ─ 앱이 앞에 있으면 즉시 대화상자, 아니면 알림으로 부른다
 *     → 사용자가 허용      ─ [onConsentResult]
 *     → mediaProjection FGS 시작 ([ScreenShareService])
 *     → 서비스가 projection 획득 → [onProjectionReady]
 *     → VirtualDisplay + 인코더 → onParams / onFrame
 *
 * ⚠ **순서가 규칙이다.** Android 14+는 `mediaProjection` 타입 포그라운드 서비스가 **먼저**
 * 떠 있지 않으면 `getMediaProjection`이 SecurityException을 던진다. 그래서 결과 Intent를
 * 여기서 붙들고 있다가 서비스에 넘긴다 — Activity가 직접 projection을 만들지 않는다.
 */
object ScreenShare {

    // ── 바깥으로 나가는 사실들 (MirrorController가 건다) ────────────────────

    /** 완성된 AU 하나. **인코더 스레드에서** 불린다. */
    @Volatile var onFrame: ((bitstream: ByteArray, keyframe: Boolean, ptsMs: Long) -> Unit)? = null
    /**
     * 보낼 영상의 제원이 정해졌다 — Mac이 이게 있어야 디코더를 세운다.
     *
     * `physWidthUm`/`physHeightUm`은 **화면의 실제 물리 크기**(µm)다. 캡처 해상도와 별개로
     * 나르는 이유는 Mac이 창을 실물 크기로 띄우기 때문이다(`share.proto` 주석 참조).
     */
    @Volatile var onParams: ((
        width: Int, height: Int, fps: Int, hevc: Boolean, kbps: Int,
        physWidthUm: Int, physHeightUm: Int,
    ) -> Unit)? = null
    /** 사람이 허용했다. */
    @Volatile var onGranted: (() -> Unit)? = null
    /** 사람이 거부했거나 동의를 받을 수 없다. */
    @Volatile var onDenied: ((String) -> Unit)? = null
    /** 돌다가 멈췄다(사용자 중지 · 인코더 오류 · 시스템 회수). */
    @Volatile var onStopped: ((String) -> Unit)? = null

    /**
     * 동의 대화상자를 띄워 달라 — Activity가 건다(`ActivityResultLauncher`가 거기 있다).
     * 앱이 앞에 없으면 이 훅은 불리지 않고 알림 경로로 간다.
     */
    @Volatile var onNeedConsentUi: (() -> Unit)? = null

    /** Activity가 화면에 떠 있는가. 대화상자를 바로 띄울 수 있는지의 유일한 근거. */
    @Volatile var activityResumed = false

    // ── 내부 상태 ──────────────────────────────────────────────────────────

    @Volatile private var projection: MediaProjection? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var encoder: ScreenEncoder? = null
    @Volatile private var inputSurface: Surface? = null
    @Volatile private var appContext: Context? = null

    /** 서비스에 넘길 동의 결과. FGS가 뜬 **뒤에** 소비된다. */
    @Volatile private var pendingResultCode = 0
    @Volatile private var pendingResultData: Intent? = null

    /** 지금 공유 중인가 — Mac의 중복 요청을 흘려보내는 근거. */
    val isSharing: Boolean get() = virtualDisplay != null

    /**
     * 지금 굽고 있는 코덱이 H.265인가. **인코더가 정한 사실**이다 — 우리가 요청한 값이 아니라.
     * H.265를 못 세운 기기는 스스로 H.264로 내려가는데, 그걸 모르고 헤더에 H.265라고 적으면
     * Mac은 HEVC 포맷 서술자로 AVC 비트스트림을 풀려다 프레임을 통째로 버린다.
     */
    val isHevcNow: Boolean get() = encoder?.isHevc ?: false

    /** 동의를 기다리는 중인가. */
    @Volatile private var awaitingConsent = false

    fun attach(context: Context) { appContext = context.applicationContext }

    // ── Mac이 부르는 것 ────────────────────────────────────────────────────

    /** Mac이 화면을 요청했다. 사람에게 묻는 건 여기부터다. */
    fun requestConsent() {
        if (isSharing) {
            // 이미 돌고 있다 — Mac이 상태를 놓친 것뿐이니 사실을 다시 알린다.
            Log.i(TAG, "이미 공유 중 — 상태만 재통보")
            onGranted?.invoke()
            republishParams()
            return
        }
        if (awaitingConsent) { Log.i(TAG, "이미 동의 대기 중 — 중복 요청 무시"); return }
        awaitingConsent = true

        val ui = onNeedConsentUi
        if (activityResumed && ui != null) {
            Log.i(TAG, "앱이 앞에 있다 → 동의 대화상자 즉시")
            ui.invoke()
        } else {
            // ★ 뒤에 있는 앱은 대화상자를 띄울 수 없다(백그라운드 Activity 시작 제한).
            // 알림으로 사람을 부른다 — 누르면 앱이 앞으로 오고 거기서 대화상자가 뜬다.
            Log.i(TAG, "앱이 뒤에 있다 → 알림으로 동의 요청")
            val ctx = appContext
            if (ctx == null) {
                awaitingConsent = false
                onDenied?.invoke("앱이 준비되지 않았습니다")
            } else {
                ShareNotifications.postConsentRequest(ctx)
            }
        }
    }

    /** Mac이 그만하라고 했다 / 세션이 끊겼다. */
    fun stop(reason: String, notifyPeer: Boolean = true) {
        val wasSharing = isSharing
        awaitingConsent = false
        teardown()
        appContext?.let { ShareNotifications.cancelConsentRequest(it) }
        appContext?.let { ScreenShareService.stop(it) }
        if (wasSharing && notifyPeer) onStopped?.invoke(reason)
    }

    // ── Activity가 부르는 것 ───────────────────────────────────────────────

    /**
     * 동의 대화상자의 결과. **여기서 projection을 만들지 않는다** — 서비스가 뜬 뒤라야 한다.
     */
    fun onConsentResult(resultCode: Int, data: Intent?) {
        awaitingConsent = false
        val ctx = appContext
        if (resultCode != Activity.RESULT_OK || data == null || ctx == null) {
            Log.i(TAG, "동의 거부됨 (code=$resultCode)")
            onDenied?.invoke("기기에서 거부했습니다")
            return
        }
        pendingResultCode = resultCode
        pendingResultData = data
        ShareNotifications.cancelConsentRequest(ctx)
        // FGS 먼저. 서비스가 뜨면 스스로 [onProjectionReady]까지 이어 간다.
        ScreenShareService.start(ctx)
    }

    /** 앱이 앞으로 왔는데 아직 동의를 기다리고 있다면 지금 물어본다(알림을 눌러 들어온 경우). */
    fun resumeConsentIfPending() {
        if (awaitingConsent) onNeedConsentUi?.invoke()
    }

    // ── 서비스가 부르는 것 ─────────────────────────────────────────────────

    /** FGS가 떴다 → 붙들고 있던 동의 결과로 projection을 만들어 달라. */
    fun consumeConsent(): Pair<Int, Intent>? {
        val data = pendingResultData ?: return null
        val code = pendingResultCode
        pendingResultData = null
        pendingResultCode = 0
        return code to data
    }

    /** projection이 손에 들어왔다 — 캡처를 세운다. */
    fun onProjectionReady(p: MediaProjection) {
        val ctx = appContext
        if (ctx == null) { onDenied?.invoke("앱이 준비되지 않았습니다"); return }
        projection = p

        // Android 14+는 콜백 등록이 `createVirtualDisplay`보다 **먼저**여야 한다.
        // 그리고 이 콜백은 사용자가 시스템 UI에서 중지했을 때 우리가 아는 유일한 통로다.
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "projection 중지됨 (사용자/시스템)")
                stop("기기에서 중지했습니다")
            }
        }, null)

        val spec = captureSpec(ctx)
        val enc = ScreenEncoder(
            width = spec.width, height = spec.height, fps = spec.fps,
            bitrateKbps = spec.kbps, preferHevc = true,
            onFrame = { au, key, pts -> onFrame?.invoke(au, key, pts) },
            onError = { why -> stop(why) },
        )
        val surface = enc.start()
        if (surface == null) {
            onDenied?.invoke("인코더를 세우지 못했습니다")
            teardown()
            return
        }
        encoder = enc
        inputSurface = surface

        val vd = runCatching {
            p.createVirtualDisplay(
                "PeriShare", spec.width, spec.height, spec.dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null,
            )
        }.getOrElse {
            Log.e(TAG, "VirtualDisplay 생성 실패: ${it.message}")
            onDenied?.invoke("화면 캡처를 시작하지 못했습니다")
            teardown()
            return
        }
        virtualDisplay = vd

        Log.i(TAG, "공유 시작 ${spec.width}x${spec.height}@${spec.fps} ${if (enc.isHevc) "H265" else "H264"}")
        onGranted?.invoke()
        onParams?.invoke(spec.width, spec.height, spec.fps, enc.isHevc, spec.kbps,
                         spec.physWidthUm, spec.physHeightUm)
    }

    /** 서비스가 죽었다(시스템 회수·사용자 스와이프) — 캡처도 같이 내린다. */
    fun onServiceDestroyed() {
        if (isSharing) {
            Log.i(TAG, "포그라운드 서비스 종료 → 공유 중단")
            val wasSharing = isSharing
            teardown()
            if (wasSharing) onStopped?.invoke("포그라운드 서비스가 종료되었습니다")
        }
    }

    // ── 내부 ───────────────────────────────────────────────────────────────

    private fun republishParams() {
        val enc = encoder ?: return
        val ctx = appContext ?: return
        val spec = captureSpec(ctx)
        onParams?.invoke(spec.width, spec.height, spec.fps, enc.isHevc, spec.kbps,
                         spec.physWidthUm, spec.physHeightUm)
    }

    private fun teardown() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { encoder?.stop() }
        encoder = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { projection?.stop() }
        projection = null
        pendingResultData = null
        pendingResultCode = 0
    }

    private data class Spec(
        val width: Int, val height: Int, val dpi: Int, val fps: Int, val kbps: Int,
        /** 화면의 실제 물리 크기(µm). 캡처 스케일과 무관한 값이다. 0 = 알 수 없음. */
        val physWidthUm: Int, val physHeightUm: Int,
    )

    /**
     * 화면의 실제 물리 크기(µm)를 잰다.
     *
     * `xdpi`/`ydpi`는 **물리** 픽셀 밀도이지 `densityDpi`(스케일 버킷)가 아니다 — 후자는
     * 420·480처럼 반올림된 값이라 실물 크기 계산에 쓰면 몇 mm씩 틀어진다.
     *
     * ⚠ 다만 xdpi/ydpi를 엉터리로 보고하는 기기가 있다(0이거나 수천). 말이 안 되는 값이면
     * 0을 돌려주고, 실물 크기 대신 적당한 기본값을 쓰게 둔다 — 창이 화면만 하게 뜨는 것보다는
     * 낫다.
     */
    private fun physicalSizeUm(ctx: Context, wPx: Int, hPx: Int): Pair<Int, Int> {
        val dm = ctx.resources.displayMetrics
        val xdpi = dm.xdpi
        val ydpi = dm.ydpi
        if (xdpi !in SANE_DPI || ydpi !in SANE_DPI) {
            Log.w(TAG, "물리 DPI가 이상하다 (x=$xdpi y=$ydpi) → 실물 크기 미보고")
            return 0 to 0
        }
        val wUm = (wPx / xdpi * 25_400f).toInt()
        val hUm = (hPx / ydpi * 25_400f).toInt()
        return wUm to hUm
    }

    /** 손에 들 수 있는 화면이라면 이 범위 안이다. 밖이면 기기가 헛소리를 한 것이다. */
    private val SANE_DPI = 60f..1200f

    /**
     * 무엇을 어떤 크기로 보낼 것인가.
     *
     * 실제 패널 해상도 그대로 보내지 않는다 — 요즘 폰은 1440p가 흔한데 그걸 60fps로 굽는 건
     * 배터리 낭비이고, Mac 창은 어차피 그보다 작다. 긴 변을 1280으로 낮추되 **종횡비는
     * 정확히 지킨다**(안 그러면 화면이 늘어난다). 그리고 짝수로 맞춘다 — 홀수 폭은 상당수
     * 하드웨어 인코더가 거부한다.
     */
    private fun captureSpec(ctx: Context): Spec {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        var w: Int
        var h: Int
        var dpi: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            w = b.width(); h = b.height()
            dpi = ctx.resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            w = metrics.widthPixels; h = metrics.heightPixels; dpi = metrics.densityDpi
        }
        // 물리 크기는 **다운스케일 전** 해상도로 잰다 — 화면 자체의 치수이지 캡처의 치수가 아니다.
        val (physW, physH) = physicalSizeUm(ctx, w, h)

        val longSide = maxOf(w, h)
        if (longSide > TARGET_LONG_SIDE) {
            val scale = TARGET_LONG_SIDE.toDouble() / longSide
            w = (w * scale).toInt(); h = (h * scale).toInt()
            dpi = (dpi * scale).toInt()
        }
        return Spec(width = even(w), height = even(h), dpi = maxOf(dpi, 96),
                    fps = TARGET_FPS, kbps = TARGET_KBPS,
                    physWidthUm = physW, physHeightUm = physH)
    }

    private fun even(v: Int) = if (v % 2 == 0) v else v - 1

    private const val TARGET_LONG_SIDE = 1280
    private const val TARGET_FPS = 30
    private const val TARGET_KBPS = 6_000
}
