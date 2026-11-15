package com.peri.display.apps

import android.content.Context
import android.util.Log
import com.peri.display.proto.AppControl
import com.peri.display.proto.ControlMessage
import kotlinx.coroutines.CoroutineScope

private const val TAG = "AppHost"

/**
 * **이 기기의 앱 등록부** — Mac `AppHost`의 대칭이되 런처가 아니다.
 *
 * 아이콘도 배타 규칙(확장 ∧ 공유)도 Mac에 있다. 여기가 하는 일은 셋뿐이다:
 *
 *  1. Mac의 지시(`AppControl`)를 해당 앱에게 넘긴다.
 *  2. 세션이 자기 것이 아닌 제어 메시지를 넘기면 임자를 찾아 준다.
 *  3. **세션 유지 비용의 근거**를 한 값으로 모은다([streaming]).
 *
 * **새 앱 추가 = 파일 둘(기계 + 실행기) + 여기 한 줄.** 세션 오케스트레이터는 0줄 바뀐다 —
 * 그게 이 계층을 만든 이유다. 예전에는 세 앱의 절반이 전부 `MirrorController` 안에 있어서,
 * 앱을 하나 더할 때마다 연결 수명주기를 다루는 그 파일을 뜯어야 했다.
 */
class AppHost(scope: CoroutineScope, context: Context, channel: AppChannel) {

    val extend = ExtendApp(scope, channel)
    val share = ShareApp(scope, context, channel)
    val notify = NotifyApp(scope, context, channel)

    /** ★ 등록 한 줄. 새 앱은 여기 한 줄이 전부다. */
    private val apps: List<PeriApp> = listOf(extend, share, notify)

    /**
     * 어느 앱이든 상태가 바뀌었다 — **세션 유지 비용을 다시 맞춰야 한다.**
     *
     * 세션이 이 훅을 걸고 Wi-Fi 락과 keepalive 주기를 다시 정한다. 앱은 라디오도
     * keepalive도 모르고, 세션은 어느 앱이 왜 영상을 쓰는지 모른다.
     */
    var onChanged: (() -> Unit)? = null

    init {
        for (app in apps) app.onChanged = { onChanged?.invoke() }
    }

    /**
     * 영상을 나르는 앱이 하나라도 도는가.
     *
     * Wi-Fi 저지연 락도 빠른 keepalive도 근거가 이것 하나다. 알림만 받는 세션에 라디오
     * 절전을 꺼 두면 아무 이득 없이 배터리만 먹는다.
     */
    val streaming: Boolean get() = apps.any { it.isStreaming }

    /** 채널이 살아났다. 세션이 갈려도 앱은 살아 있으므로 **여러 번 불린다**. */
    fun attach() {
        for (app in apps) app.attach()
    }

    /**
     * 채널이 끊겼다. **무엇을 지키고 무엇을 버릴지는 앱이 정한다** — 확장은 디코더를 놓고,
     * 공유는 동의를 통째로 잃고, 알림은 아무것도 안 버린다.
     */
    fun detach(reason: DetachReason, detail: String) {
        Log.i(TAG, "채널 끊김($reason) — $detail")
        for (app in apps) app.detach(reason, detail)
    }

    /** Mac의 런처가 앱 하나를 띄우라/내리라고 했다. */
    fun control(c: AppControl) {
        Log.i(TAG, "앱 지시: ${c.app} launch=${c.launch} ${c.reason}")
        val app = apps.firstOrNull { it.id == c.app }
        if (app == null) {
            Log.i(TAG, "모르는 앱 지시 무시: ${c.app}")
            return
        }
        app.control(c.launch, c.reason)
    }

    /**
     * 세션이 자기 것이 아닌 메시지를 통째로 넘긴다. 임자가 있으면 true.
     *
     * 와이어 v1에는 `serviceId`가 없어 등록부가 순서대로 물어본다. 앱이 셋뿐이라 비용이
     * 없고, 먹스가 들어오면 이 순회가 곧 조회 한 번이 된다.
     */
    fun receive(msg: ControlMessage): Boolean = apps.any { it.receive(msg) }

    /** 완성된 미디어 하나 — 임자가 알아서 가져간다. */
    fun receiveMedia(m: Media) {
        for (app in apps) app.receiveMedia(m)
    }

    /** 최신성 레인에 구멍이 났다. */
    fun mediaGap() {
        for (app in apps) app.onMediaGap()
    }
}
