package com.peri.display.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.peri.display.R

private const val TAG = "PeriNotify"

/**
 * 잠긴 폰을 깨워 "Mac에서 이걸 열려고 한다"고 알리는 알림.
 *
 * 왜 알림을 거치나
 * ───────────────
 * 잠금 화면에서는 우리가 직접 액티비티를 띄울 수 없다(BAL). 그런데 **시스템이 대신 띄워
 * 주는 통로**가 하나 있다 — 전체 화면 인텐트다. 전화 수신 화면이 쓰는 그것이고,
 * 알림에 붙는 속성이라 **알림 없이는 존재할 수 없다.**
 *
 * 그래서 이 알림은 "사용자에게 보여 주려고" 있는 게 아니라 **액티비티를 띄우는 탈것**이다.
 * 전체 화면 알림이 허용돼 있으면 폰은 저절로 켜지고 잠금 해제 화면이 바로 뜬다 — 알림을
 * 누를 일이 없다. 그래서 [NotifyUnlockActivity]가 뜨는 즉시 이 알림을 지운다.
 *
 * 허용돼 있지 **않으면** 시스템이 조용히 보통 알림으로 낮춘다. 그때는 알림이 유일한
 * 통로이므로 그대로 남아 있어야 하고, 문구도 "눌러서"로 바뀐다.
 *
 * 채널 중요도가 높은 건 잠금 화면에 뜨게 하기 위해서다 — 낮으면 그늘 안에만 조용히 쌓여
 * 사용자가 폰을 켜 보기 전엔 모른다.
 */
object ShareNotifyPrompt {

    private const val CHANNEL = "peri.notify.unlock"
    private const val NOTIF_ID = 0x5EA3

    /** 이 요청이 유효한 시간. 지나면 시스템이 알아서 거둬 간다. */
    private const val EXPIRY_MS = 2 * 60 * 1000L

    /** 잠금 해제를 청하는 알림을 띄운다. 누르면 [NotifyUnlockActivity]로 간다. */
    fun postUnlockRequest(ctx: Context, key: String, appLabel: String) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return

        val open = PendingIntent.getActivity(
            ctx, key.hashCode(),
            Intent(ctx, NotifyUnlockActivity::class.java)
                .putExtra(NotifyUnlockActivity.EXTRA_KEY, key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // 문구는 **실제로 일어날 일**을 따라간다. 화면이 저절로 켜지는데 "누르라"고 하면
        // 거짓말이고, 안 켜지는데 안 그러면 사용자는 뭘 해야 할지 모른다.
        val wakes = canWakeScreen(ctx)
        val text = when {
            appLabel.isBlank() && wakes -> ctx.getString(R.string.notify_unlock_text)
            appLabel.isBlank()          -> ctx.getString(R.string.notify_unlock_text_tap)
            wakes -> ctx.getString(R.string.notify_unlock_text_app, appLabel)
            else  -> ctx.getString(R.string.notify_unlock_text_tap_app, appLabel)
        }

        val n = Notification.Builder(ctx, CHANNEL)
            .setContentTitle(ctx.getString(R.string.notify_unlock_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(open)
            .setAutoCancel(true)
            // 잠금 화면에 내용까지 보이게. 어차피 "Mac이 열려고 한다"까지가 전부라 민감하지 않다.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            // ★ **화면을 켜는 건 이 한 줄이다.** 평범한 알림은 소리만 나고 화면은 꺼진 채다.
            // 전화 수신 화면이 쓰는 기전이고, 대상 액티비티의 `turnScreenOn`과 짝이 되어야
            // 실제로 켜진다 — 둘 중 하나만 있으면 안 켜진다.
            //
            // 허용되지 않았으면 시스템이 알아서 보통 알림으로 낮춘다(예외도 안 난다).
            // 그래서 조건 없이 걸어 두고, 안내는 아래 [canWakeScreen]으로 따로 한다.
            .setFullScreenIntent(open, true)
            // 이건 "지금 Mac에서 눌렀다"는 순간의 사실이다. 아무도 반응하지 않으면 스스로
            // 사라져야 한다 — 안 그러면 한참 전 요청이 그늘에 남아 나중에 열린다.
            .setTimeoutAfter(EXPIRY_MS)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
            .onSuccess {
                Log.i(TAG, "잠금 해제 요청 알림 표시 ($key, 화면 켜기=${canWakeScreen(ctx)})")
            }
            .onFailure { Log.w(TAG, "잠금 해제 요청 알림 실패: ${it.message}") }
    }

    /**
     * 화면을 켤 수 있는가 (전체 화면 인텐트 허용 여부).
     *
     * Android 14+는 통화·알람 앱이 아니면 이걸 기본으로 안 준다. 없으면 알림은 그대로 뜨되
     * **화면이 안 켜진다** — 실기기에서 정확히 그 증상이었다.
     */
    fun canWakeScreen(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
        return runCatching { nm.canUseFullScreenIntent() }.getOrDefault(false)
    }

    /** 전체 화면 인텐트 설정 화면. 우리 앱으로 바로 간다. */
    fun wakeScreenSettingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
               android.net.Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun cancel(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID) }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.notify_unlock_channel),
                                NotificationManager.IMPORTANCE_HIGH))
    }
}
