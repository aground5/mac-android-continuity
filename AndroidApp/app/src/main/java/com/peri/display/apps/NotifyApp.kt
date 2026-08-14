package com.peri.display.apps

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.peri.display.notify.FailReason
import com.peri.display.notify.NotifyAccess
import com.peri.display.notify.NotifyBridge
import com.peri.display.notify.NotifyProto
import com.peri.display.notify.NotifySnapshot
import com.peri.display.proto.AppId
import com.peri.display.proto.ControlMessage
import com.peri.display.proto.NotificationAction
import com.peri.display.proto.NotificationActionFailed
import com.peri.display.proto.NotificationRemoved
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "NotifyApp"

/**
 * **알림 앱의 Android 절반** — 이 기기의 알림을 Mac으로 나른다.
 *
 * 셋 중 유일하게 **세션이 끊겨도 아무것도 버리지 않는** 앱이다. 리스너는 시스템이
 * 붙들고 있고 권한도 그대로라, 다시 붙으면 그 자리에서 이어진다. 확장은 디코더를 놓고
 * 공유는 동의를 통째로 잃는 것과 대비된다 — 앱마다 재개 정책이 다르다는 것이 이 계층을
 * 가른 이유이고, 여기가 그 세 번째 증거다.
 */
class NotifyApp(
    private val scope: CoroutineScope,
    private val context: Context,
    private val ch: AppChannel,
) : PeriApp {

    override val id: AppId get() = AppId.APP_ID_NOTIFY

    /** 알림 전달이 켜져 있는가 — UI가 권한 안내를 띄울지 정하는 근거. */
    var running by mutableStateOf(false)
        private set

    override var onChanged: (() -> Unit)? = null

    private val runner = AppRunner<NotifyState, NotifyEv, NotifyFx>(
        initial = NotifyState(),
        step = { s, e -> reduceNotify(s, e).let { it.state to it.fx } },
        exec = ::exec,
        // ★ UI가 보는 값은 [NotifyState.wanted]다 — `isRunning`(= wanted ∧ granted)이 아니라.
        // 권한이 없어서 안 도는 바로 그 경우에 안내를 띄워야 하는데, 곱을 보면 그 순간
        // 화면에서 사라진다.
        settled = { s ->
            scope.launch(Dispatchers.Main) { running = s.wanted }
            onChanged?.invoke()
        },
    )

    init {
        wireBridge()
    }

    // MARK: - 세션이 부르는 것

    /** 붙을 때 권한을 다시 관측한다 — 세션이 없는 동안 사람이 설정에서 켰을 수 있다. */
    override fun attach() =
        runner.send(NotifyEv.PermissionChanged(NotifyAccess.isGranted(context)))

    /** 놓을 것이 없다. 리스너도 권한도 세션과 무관하게 산다 — 사유를 볼 이유조차 없다. */
    override fun detach(reason: DetachReason, detail: String) = Unit

    override fun control(launch: Boolean, reason: String) = runner.send(
        if (launch) NotifyEv.Launched(NotifyAccess.isGranted(context))
        else NotifyEv.Quit(reason),
    )

    /** 알림은 영상을 나르지 않는다 — 라디오를 깨워 둘 이유가 없다. */
    override val isStreaming: Boolean get() = false

    override fun receive(msg: ControlMessage): Boolean = when {
        msg.hasNotificationSync() -> { NotifyBridge.syncNow(); true }
        msg.hasNotificationAction() -> { act(msg.notificationAction); true }
        else -> false
    }

    // MARK: - 부작용

    private fun exec(fx: NotifyFx) {
        when (fx) {
            is NotifyFx.SetBridgeWanted -> NotifyBridge.wanted = fx.on
            is NotifyFx.Report -> ch.reportApp(id, fx.running, fx.detail)
            is NotifyFx.Log -> Log.w(TAG, fx.msg)
        }
    }

    private fun act(a: NotificationAction) {
        val l = NotifyBridge.listener ?: run {
            Log.w(TAG, "알림 리스너 미연결 — 지시 무시 (${a.kind})")
            return
        }
        when (a.kind) {
            NotificationAction.Kind.KIND_OPEN -> l.open(a.key)
            NotificationAction.Kind.KIND_DISMISS -> l.dismiss(a.key)
            // Mac이 고른 액션 번호는 +1 되어 있다(0 = 안 골랐다). 리스너가 확인하고 쓴다.
            NotificationAction.Kind.KIND_REPLY -> l.reply(a.key, a.actionIndex, a.text)
            else -> {}
        }
    }

    // MARK: - 리스너와의 접점

    /**
     * 리스너가 내는 사실들을 이 앱에 잇는다. **리스너 스레드에서** 불린다.
     *
     * 옮겨 적기만 한다 — 번역은 [NotifyProto]가, 무엇을 보여줄지는 Mac이 정한다.
     */
    private fun wireBridge() {
        NotifyBridge.onPosted = { s -> send(s) }
        NotifyBridge.onRemoved = { key ->
            ch.sendReliable(
                ControlMessage.newBuilder().setNotificationRemoved(
                    NotificationRemoved.newBuilder().setKey(key).build()
                ).build()
            )
        }
        NotifyBridge.onActionFailed = { key, reason, detail ->
            ch.sendReliable(
                ControlMessage.newBuilder().setNotificationActionFailed(
                    NotificationActionFailed.newBuilder()
                        .setKey(key)
                        .setReason(when (reason) {
                            FailReason.LOCKED -> NotificationActionFailed.Reason.REASON_LOCKED
                            FailReason.NOT_PERMITTED ->
                                NotificationActionFailed.Reason.REASON_NOT_PERMITTED
                            FailReason.GONE -> NotificationActionFailed.Reason.REASON_GONE
                        })
                        .setDetail(detail)
                        .build()
                ).build()
            )
        }
    }

    private fun send(s: NotifySnapshot) {
        ch.sendReliable(
            ControlMessage.newBuilder().setNotificationPosted(NotifyProto.posted(s)).build()
        )
    }
}
