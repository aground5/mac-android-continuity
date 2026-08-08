package com.peri.display.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.peri.display.MainActivity
import com.peri.display.R

private const val TAG = "ShareService"

/**
 * 화면 공유가 도는 동안 사는 포그라운드 서비스.
 *
 * **선택이 아니라 요건이다.** Android 14+는 `mediaProjection` 타입 포그라운드 서비스가 떠
 * 있지 않으면 `getMediaProjection`이 SecurityException을 던진다. 그리고 그 순서까지 정해져
 * 있다 — 서비스가 먼저 뜨고, 그 안에서 projection을 얻는다.
 *
 * 그래서 이 서비스가 projection을 얻는 유일한 자리다. Activity는 동의 결과(Intent)를
 * [ScreenShare]에 맡겨 두기만 하고, 여기서 꺼내 쓴다.
 *
 * 알림을 지울 수 없게 만든 것도 요건이다 — 사용자가 "내 화면이 지금 나가고 있다"는 사실을
 * 언제든 볼 수 있어야 하고, 그 알림이 곧 중지 버튼이다.
 */
class ScreenShareService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "알림에서 중지")
            ScreenShare.stop("기기에서 중지했습니다")
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground()

        val consent = ScreenShare.consumeConsent()
        if (consent == null) {
            // 이미 소비됐거나(중복 start) 결과가 없다. 돌고 있으면 그대로 두고, 아니면 내린다.
            if (!ScreenShare.isSharing) {
                Log.w(TAG, "동의 결과가 없다 → 서비스 종료")
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = runCatching { mgr.getMediaProjection(consent.first, consent.second) }
            .getOrElse {
                Log.e(TAG, "getMediaProjection 실패: ${it.message}")
                null
            }
        if (projection == null) {
            ScreenShare.stop("화면 캡처 권한을 얻지 못했습니다")
            stopSelf()
            return START_NOT_STICKY
        }
        ScreenShare.onProjectionReady(projection)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        ScreenShare.onServiceDestroyed()
    }

    private fun startInForeground() {
        ShareNotifications.ensureChannels(this)
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = Notification.Builder(this, ShareNotifications.CHANNEL_ACTIVE)
            .setContentTitle(getString(R.string.share_active_title))
            .setContentText(getString(R.string.share_active_text))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(
                null, getString(R.string.share_stop), stopIntent).build())
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        private const val ACTION_STOP = "com.peri.display.SHARE_STOP"
        private const val NOTIF_ID = 0x5EA1

        fun start(ctx: Context) {
            val i = Intent(ctx, ScreenShareService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, ScreenShareService::class.java)) }
        }
    }
}

/** 공유가 쓰는 알림 두 가지 — "물어보는 것"과 "돌고 있다는 것". */
object ShareNotifications {
    const val CHANNEL_ACTIVE = "peri.share.active"
    const val CHANNEL_CONSENT = "peri.share.consent"
    private const val CONSENT_ID = 0x5EA2

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ACTIVE, ctx.getString(R.string.share_channel_active),
            NotificationManager.IMPORTANCE_LOW))
        // 동의 요청은 **사람을 불러야** 하므로 소리가 나는 등급이다. 낮추면 폰을 주머니에
        // 넣어 둔 사이 Mac이 45초 마감으로 포기한다.
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_CONSENT, ctx.getString(R.string.share_channel_consent),
            NotificationManager.IMPORTANCE_HIGH))
    }

    /**
     * 앱이 뒤에 있을 때의 동의 요청. 누르면 앱이 앞으로 오고 거기서 대화상자가 뜬다.
     *
     * 왜 알림을 거치나: 백그라운드에서 Activity를 띄우는 건 막혀 있다. 시스템 대화상자를
     * 직접 부를 방법이 없으니 사람을 불러오는 수밖에 없다.
     */
    fun postConsentRequest(ctx: Context) {
        ensureChannels(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val open = PendingIntent.getActivity(
            ctx, 2,
            Intent(ctx, MainActivity::class.java)
                .setAction(MainActivity.ACTION_SHARE_CONSENT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(ctx, CHANNEL_CONSENT)
            .setContentTitle(ctx.getString(R.string.share_consent_title))
            .setContentText(ctx.getString(R.string.share_consent_text))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setFullScreenIntent(open, true)   // 잠금화면/사용 중에도 눈에 띄게
            .build()
        runCatching { nm.notify(CONSENT_ID, n) }
    }

    fun cancelConsentRequest(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(CONSENT_ID) }
    }
}
