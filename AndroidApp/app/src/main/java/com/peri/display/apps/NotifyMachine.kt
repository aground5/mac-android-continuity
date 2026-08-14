package com.peri.display.apps

/**
 * **앱 #4(알림 전달, Android → Mac)의 상태 기계** — 순수 함수.
 *
 * 셋 중 가장 얇지만 갈라 둘 값이 확실히 있다. 이 앱만 **켜 달라는 지시만으로는 켜지지
 * 않기 때문이다** — `NotificationListenerService` 접근 권한은 사람이 시스템 설정에서
 * 따로 켜야 하고, 없으면 알림이 **한 건도** 오지 않는다.
 *
 *     wanted  — Mac의 런처가 이 앱을 띄웠는가
 *     granted — 알림 접근 권한이 있는가 (사람이 시스템 설정에서 켠다)
 *     ─────────────────────────────────────────────────────
 *     running = wanted ∧ granted        ← Mac에 보고하는 값
 *
 * 그 곱을 **사실대로** 보고하는 것이 이 기계의 전부다. 예전에는 `wanted`만 보고했고,
 * 그러면 Mac 화면에는 "알림 켜짐"이라고 뜨는데 아무것도 안 와서 원인이 어디에도
 * 보이지 않았다. 이제 `detail`에 왜인지가 실려 간다.
 *
 * 확장·공유와 달리 **세션이 끊겨도 아무것도 버리지 않는다.** 리스너는 시스템이 붙들고
 * 있고 권한도 그대로다 — 다시 붙으면 그 자리에서 이어진다. 앱마다 재개 정책이 다르다는
 * 세 번째 사례다.
 */

data class NotifyState(
    /** Mac의 런처가 이 앱을 띄웠는가. */
    val wanted: Boolean = false,
    /** 알림 접근 권한이 있는가. */
    val granted: Boolean = false,
) {
    /** 실제로 알림이 흐를 수 있는가 — Mac에 보고하는 값. */
    val isRunning: Boolean get() = wanted && granted

    /**
     * UI가 권한 안내를 띄울지 정하는 근거.
     *
     * [isRunning]이 아니라 [wanted]를 보는 게 요점이다 — 권한이 없어서 안 도는 바로 그
     * 경우에 안내가 필요하다.
     */
    val needsPermission: Boolean get() = wanted && !granted

    val detail: String get() = if (needsPermission) "기기에서 알림 접근을 허용해야 합니다" else ""
}

sealed interface NotifyEv {
    /** Mac이 알림 앱을 띄웠다. [granted]는 그 순간 관측한 권한 상태다. */
    data class Launched(val granted: Boolean) : NotifyEv

    /** Mac이 알림 앱을 내렸다. */
    data class Quit(val reason: String) : NotifyEv

    /** 권한이 바뀌었다(사람이 설정에서 켜거나 껐다 / 리스너가 뒤늦게 붙었다). */
    data class PermissionChanged(val granted: Boolean) : NotifyEv
}

sealed interface NotifyFx {
    /**
     * 리스너 다리의 스위치. 이 값이 false면 리스너는 알림을 받아도 위로 올리지 않는다 —
     * 서비스 자체는 시스템이 붙들고 있어 우리가 끌 수 없기 때문에, 문은 여기서 닫는다.
     */
    data class SetBridgeWanted(val on: Boolean) : NotifyFx

    data class Report(val running: Boolean, val detail: String) : NotifyFx
    data class Log(val msg: String) : NotifyFx
}

data class NotifyStep(val state: NotifyState, val fx: List<NotifyFx> = emptyList())

/** 전이 함수. **순수**하며 모든 (상태, 사건) 쌍에 대해 정의된다. */
fun reduceNotify(s: NotifyState, ev: NotifyEv): NotifyStep = when (ev) {

    is NotifyEv.Launched -> {
        val n = s.copy(wanted = true, granted = ev.granted)
        NotifyStep(
            n,
            listOfNotNull(
                NotifyFx.SetBridgeWanted(true),
                // ★ 여기서 동기화를 **걸지 않는다.** Mac이 앱을 켜면서 `NotificationSync`를
                // 따로 보내는데 여기서도 밀면 켤 때마다 목록이 **두 번** 간다(실기기 로그에
                // 같은 패키지가 두 줄씩 찍혔다). 알림 하나에 아이콘이 셋 실리므로 그냥
                // 낭비가 아니라 초기 폭이 두 배가 되는 일이다.
                NotifyFx.Report(n.isRunning, n.detail),
                if (n.needsPermission) NotifyFx.Log("알림 접근 미허용 — 설정에서 켜야 한다") else null,
            ),
        )
    }

    is NotifyEv.Quit -> NotifyStep(
        s.copy(wanted = false),
        listOf(NotifyFx.SetBridgeWanted(false), NotifyFx.Report(false, ev.reason)),
    )

    // 권한이 바뀌어도 **켜져 있지 않으면 알릴 것이 없다.** 그 사실은 다음 [NotifyEv.Launched]가
    // 어차피 새로 관측한다.
    is NotifyEv.PermissionChanged ->
        if (s.granted == ev.granted) NotifyStep(s)
        else {
            val n = s.copy(granted = ev.granted)
            NotifyStep(n, if (n.wanted) listOf(NotifyFx.Report(n.isRunning, n.detail)) else emptyList())
        }
}
