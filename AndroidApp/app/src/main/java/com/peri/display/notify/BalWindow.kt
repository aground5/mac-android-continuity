package com.peri.display.notify

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager

private const val TAG = "PeriNotify"

/**
 * 백그라운드 액티비티 시작(BAL) 예외를 **실제로 만족시키기 위한** 1픽셀 투명 창.
 *
 * 왜 권한만으로는 안 되나
 * ─────────────────────
 * 문서는 "`SYSTEM_ALERT_WINDOW` 권한이 있으면" 예외라고 적지만, 런타임 판정은 더 엄격하다.
 * 실기기 로그가 그 필드를 그대로 보여준다:
 *
 *     realCallingUidHasNonAppVisibleWindow: false   ← 권한은 켰는데 창이 없다
 *     balAllowedByPiSender: BSP.ALLOW_BAL           ← 옵트인은 됐다
 *     resultIfPiSenderAllowsBal: BAL_BLOCK          ← 그래도 막혔다
 *
 * 즉 검사하는 건 권한이 아니라 **"지금 이 앱의 창이 화면에 있는가"** 다. 그래서 보낼 때만
 * 창을 하나 띄운다.
 *
 * 왜 액티비티가 아니라 오버레이 창인가
 * ──────────────────────────────────
 * 액티비티로 앞에 서려면 그 액티비티 시작부터 BAL 검사를 받는다(닭과 달걀 — 실제로 그렇게
 * 한 번 막혔다). 오버레이 창은 액티비티가 아니라 `WindowManager` 창이라 BAL이 아니라
 * `SYSTEM_ALERT_WINDOW`만 본다. 그래서 이 경로만 열려 있다.
 *
 * 창은 1×1에 투명하고 입력을 받지 않는다 — 사용자에게 보이지도, 걸리지도 않는다.
 */
object BalWindow {

    private val main = Handler(Looper.getMainLooper())

    /**
     * 창을 띄운 상태에서 [block]을 실행한다. `block`은 창이 실제로 붙었는지를 인자로 받는다.
     *
     * **메인 스레드로 넘긴다.** `WindowManager.addView`는 Looper가 있는 스레드를 요구하는데
     * 이 호출은 전송 큐에서 온다.
     *
     * 창을 붙인 **다음 프레임에** 보낸다: `addView`는 즉시 반환하지만 창이 시스템에 반영되는
     * 건 그 뒤라, 같은 틱에 보내면 아직 `hasNonAppVisibleWindow`가 false다.
     */
    fun whileVisible(ctx: Context, block: (shown: Boolean) -> Unit) {
        if (!Settings.canDrawOverlays(ctx)) {
            block(false)
            return
        }
        main.post {
            val wm = ctx.getSystemService(WindowManager::class.java)
            val view = View(ctx)
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            )
            val added = runCatching { wm.addView(view, lp) }
                .onFailure { Log.w(TAG, "BAL 창 추가 실패: ${it.message}") }
                .isSuccess

            main.postDelayed({
                runCatching { block(added) }
                    .onFailure { Log.w(TAG, "BAL 창 안에서 실패: ${it.message}") }
                // 실행이 시작될 틈을 준 뒤 걷는다. 너무 일찍 걷으면 그 사이 판정이 뒤집힌다.
                if (added) {
                    main.postDelayed({ runCatching { wm.removeView(view) } }, LINGER_MS)
                }
            }, SETTLE_MS)
        }
    }

    /** 창이 시스템에 반영될 때까지의 여유. 한 프레임(≈16ms)보다 넉넉하게. */
    private const val SETTLE_MS = 120L

    /** 보낸 뒤 창을 유지하는 시간. 시작 판정이 끝날 때까지만 있으면 된다. */
    private const val LINGER_MS = 1500L
}
