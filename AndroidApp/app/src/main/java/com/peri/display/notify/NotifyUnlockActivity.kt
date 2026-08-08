package com.peri.display.notify

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.util.Log

private const val TAG = "PeriNotify"

/**
 * 잠금 화면 위에 떠서 **잠금 해제를 청하고**, 풀리면 원래 알림을 연다.
 *
 * 음성 어시스턴트가 "먼저 잠금을 해제해 주세요"라고 할 때 쓰는 흐름과 같다:
 * `KeyguardManager.requestDismissKeyguard()`가 시스템의 잠금 해제 UI를 띄우고, 사용자가
 * 풀면 콜백이 온다.
 *
 * 왜 이 우회로가 필요한가
 * ─────────────────────
 * 잠긴 화면에서는 우리 오버레이 창이 잠금 화면 **아래** 깔려서 "보이는 창"으로 세지 않는다.
 * 그래서 백그라운드 액티비티 시작(BAL) 예외가 성립하지 않고, 앱을 열 수 없다(실기기 로그:
 * `창=true`인데 `hasNonAppVisibleWindow: false`).
 *
 * 이 액티비티는 그 벽을 뚫지 않는다 — **사용자를 거쳐 간다.** 기기가 알림을 하나 띄우고,
 * 사용자가 그걸 누르면 시스템이 우리를 실행한다(= 시스템이 보낸 PendingIntent, BAL 예외).
 * 그때는 우리가 포그라운드라 원래 앱을 여는 것도 막히지 않는다.
 *
 * 잠긴 폰에서 조용히 앱이 열리지 않는다는 성질은 그대로다. 사람의 확인이 한 번 들어갈 뿐이다.
 */
class NotifyUnlockActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 잠금 화면 **위에** 서고 화면을 켠다. 이 둘이 없으면 잠금 뒤에 가려져
        // 사용자는 아무것도 못 본다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        // 알림은 **여기까지 오기 위한 탈것**이었다. 우리가 떴다는 건 그 일이 끝났다는 뜻이니
        // 바로 지운다 — 전체 화면 인텐트로 곧장 올라온 경우 사용자는 알림을 아예 못 본다.
        // (예전엔 잠금이 풀린 뒤에야 지워서, 해제를 취소하면 쓸모없는 알림이 남았다.)
        ShareNotifyPrompt.cancel(this)

        val key = intent?.getStringExtra(EXTRA_KEY)
        if (key == null) { finish(); return }

        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked) {
            // 이미 풀려 있다 — 곧장 연다.
            openAndFinish(key)
            return
        }
        Log.i(TAG, "잠금 해제 요청 ($key)")
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                Log.i(TAG, "잠금 해제됨 → 연다 ($key)")
                openAndFinish(key)
            }
            override fun onDismissCancelled() {
                Log.i(TAG, "잠금 해제 취소됨 ($key)")
                finishQuietly()
            }
            override fun onDismissError() {
                Log.w(TAG, "잠금 해제 실패 ($key)")
                finishQuietly()
            }
        })
    }

    private fun openAndFinish(key: String) {
        // 여기는 포그라운드 액티비티다 — 백그라운드 시작 제한을 받지 않는다.
        NotifyBridge.listener?.open(key)
            ?: Log.w(TAG, "리스너가 없다 — 열 수 없다 ($key)")
        finishQuietly()
    }

    private fun finishQuietly() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_KEY = "com.peri.display.notify.UNLOCK_KEY"
    }
}
