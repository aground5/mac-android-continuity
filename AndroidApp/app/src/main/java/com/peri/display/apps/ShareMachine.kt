package com.peri.display.apps

/**
 * **앱 #2(화면 공유, Android → Mac)의 상태 기계** — 순수 함수.
 *
 * Mac `ShareMachine.swift`의 **거울상**이다. 거기서는 Mac이 청하고 기다리는 쪽이고,
 * 여기서는 우리가 **사람에게 묻고 화면을 내주는** 쪽이다. 시작 버튼은 Mac에 있지만
 * 실제로 줄지 말지는 이 기기의 사람이 정한다:
 *
 *     Mac이 요청 → 사람에게 묻는다(MediaProjection 동의) → 인코더 기동 → 프레임
 *
 * ⚠ **동의는 세션보다 수명이 짧다.** Android 14+는 `mediaProjection` 타입 포그라운드
 * 서비스가 선행돼야 하고 동의 토큰이 1회용이다. 무손실 재개로 **세션은 살아남아도 이
 * 앱은 못 살아난다** — 재동의가 필요하다. 그래서 [ShareEv.Unlinked]가 확장에서는
 * 디코더만 버리지만 여기서는 동의를 통째로 무효로 만든다. 앱마다 재개 정책이 다르다는
 * 걸 보여주는 실제 사례이고, 기계를 갈라 뒀기 때문에 그 차이가 코드로 드러난다.
 *
 * ## 동의의 실제 상태는 [com.peri.display.share.ScreenShare]가 쥐고 있다
 *
 * MediaProjection 토큰·포그라운드 서비스·대화상자 중복 억제는 전부 거기 있고, 여기는
 * **Mac이 믿고 있는 그림**을 관리한다. 둘을 합치지 않는 이유: 토큰의 수명은 안드로이드
 * 플랫폼이 정하고 보고의 수명은 프로토콜이 정한다. 예전에는 그 둘이 섞여서, 사람이
 * 대화상자를 아예 못 본 경우(앱이 뒤에 있어 알림으로 갔는데 Mac이 곧바로 STOP을 보낸
 * 경우)에 `awaitingConsent`만 조용히 지워지고 Mac에는 아무 답도 안 갔다.
 */

/** 상대(= 이 기기의 사람)가 화면을 내주기로 했는가. **사람이 움직이는 축**이다. */
enum class Consent { NONE, ASKED, GRANTED }

/** Mac에 알리는 공유 단계. 와이어 enum(`ShareStatus.Phase`)으로의 변환은 실행기가 한다. */
enum class SharePhase { GRANTED, DENIED, STOPPED }

data class ShareState(
    val consent: Consent = Consent.NONE,
    /** 말할 채널이 있는가. */
    val linked: Boolean = false,
) {
    val isRunning: Boolean get() = consent == Consent.GRANTED

    val detail: String? get() = when {
        !linked -> null
        consent == Consent.ASKED -> "화면 공유를 허용해 주세요"
        else -> null
    }
}

sealed interface ShareEv {
    /** Mac이 화면 공유를 청했다(`ShareRequest.ACTION_START`). */
    data object Requested : ShareEv

    /** Mac이 그만하라고 했다(`ACTION_STOP` 또는 `AppControl(launch=false)`). */
    data class StopRequested(val reason: String) : ShareEv

    /** 사람이 허락했고 인코더가 실제로 섰다. */
    data object Granted : ShareEv

    /** 사람이 거부했거나 동의를 받을 수 없었다. */
    data class Denied(val why: String) : ShareEv

    /** 돌던 공유가 멎었다(사람이 중지 · 인코더 오류 · 시스템 회수 · 프레임 한도). */
    data class Stopped(val why: String) : ShareEv

    data object Linked : ShareEv

    /** 세션이 사라졌다 — 동의도 같이 사라진다(위 ⚠ 참조). */
    data class Unlinked(val reason: String) : ShareEv
}

sealed interface ShareFx {
    /** 사람에게 묻는다. 중복 억제는 `ScreenShare`가 한다. */
    data object AskConsent : ShareFx

    /**
     * 캡처를 접는다. [notifyPeer]가 false면 `ScreenShare`가 종료 콜백을 내지 않는다 —
     * 알릴 채널이 이미 없는 경우다.
     */
    data class StopCapture(val reason: String, val notifyPeer: Boolean) : ShareFx

    /** Mac에 공유 단계를 알린다(`ShareStatus`). */
    data class ReportPhase(val phase: SharePhase, val reason: String) : ShareFx

    /** Mac에 앱 상태를 알린다(`AppState`). */
    data class Report(val running: Boolean, val detail: String) : ShareFx

    /**
     * PTS 기준점만 다시 잡는다. **프레임 번호는 건드리지 않는다** — 그건 세션 키에 매인
     * nonce의 근거라, 공유를 껐다 켤 때마다 0으로 되돌리면 같은 키에 같은 nonce가 두 번 쓰인다.
     */
    data object ResetPts : ShareFx

    /**
     * 프레임 번호를 되돌린다. **세션이 갈릴 때만** 나온다 — 새 세션 = 새 키라서 nonce
     * 공간이 처음부터 다시 열리는 유일한 지점이다.
     */
    data object ResetFrameIds : ShareFx

    data class Log(val msg: String) : ShareFx
}

data class ShareStep(val state: ShareState, val fx: List<ShareFx> = emptyList())

/** 동의와 그에 딸린 것을 전부 놓는다. 재개 정책이 확장과 갈리는 지점이라 한 곳에 모은다. */
private fun dropConsent(s: ShareState): ShareState = s.copy(consent = Consent.NONE)

/** 전이 함수. **순수**하며 모든 (상태, 사건) 쌍에 대해 정의된다. */
fun reduceShare(s: ShareState, ev: ShareEv): ShareStep = when (ev) {

    // ★ 이미 GRANTED여도 그대로 흘려보낸다. `ScreenShare`는 "이미 공유 중"을 보면
    // 상태를 다시 통보하는데(Mac이 상태를 놓친 경우의 복구 경로), 여기서 걸러 버리면
    // 그 복구가 통째로 막힌다.
    ShareEv.Requested -> ShareStep(
        if (s.consent == Consent.GRANTED) s else s.copy(consent = Consent.ASKED),
        listOf(ShareFx.AskConsent),
    )

    is ShareEv.StopRequested -> when (s.consent) {
        // 돌고 있었다 → `ScreenShare`가 종료 콜백을 내고, 보고는 그 [ShareEv.Stopped]에서 나간다.
        Consent.GRANTED -> ShareStep(s, listOf(ShareFx.StopCapture(ev.reason, notifyPeer = true)))

        // ★ 사람의 답을 기다리던 중이었다면 **여기서 보고해야 한다.**
        //
        // `ScreenShare.stop`은 `awaitingConsent`만 조용히 내리고 아무 콜백도 내지 않는다
        // (돌고 있지 않았으니 "멈췄다"고 할 것도 없다). 그래서 예전에는 Mac이 요청을
        // 보내고 곧바로 취소하면 Mac 쪽에 아무 답도 가지 않아 동의 대기 화면이 남았다.
        Consent.ASKED -> ShareStep(
            dropConsent(s),
            listOf(
                ShareFx.StopCapture(ev.reason, notifyPeer = true),
                ShareFx.ReportPhase(SharePhase.STOPPED, ev.reason),
                ShareFx.Report(false, ev.reason),
            ),
        )

        // 켜져 있지도 않았다. 그래도 정리는 시킨다 — 동의 요청 알림과 포그라운드 서비스가
        // 남아 있을 수 있고, 그건 사람 눈에 보이는 찌꺼기다.
        Consent.NONE -> ShareStep(s, listOf(ShareFx.StopCapture(ev.reason, notifyPeer = true)))
    }

    ShareEv.Granted -> ShareStep(
        s.copy(consent = Consent.GRANTED),
        listOf(
            ShareFx.ResetPts,
            ShareFx.ReportPhase(SharePhase.GRANTED, ""),
            ShareFx.Report(true, ""),
        ),
    )

    is ShareEv.Denied -> ShareStep(
        dropConsent(s),
        listOf(
            ShareFx.ReportPhase(SharePhase.DENIED, ev.why),
            ShareFx.Report(false, ev.why),
        ),
    )

    is ShareEv.Stopped ->
        // 이미 놓은 뒤에 도착한 늦은 종료 통보. 받아들이면 Mac에 같은 말을 두 번 한다.
        if (s.consent == Consent.NONE) ShareStep(s, listOf(ShareFx.Log("낡은 종료 통보 — 무시")))
        else ShareStep(
            dropConsent(s),
            listOf(
                ShareFx.ReportPhase(SharePhase.STOPPED, ev.why),
                ShareFx.Report(false, ev.why),
            ),
        )

    ShareEv.Linked -> ShareStep(s.copy(linked = true))

    // 알릴 채널이 없으므로 보고하지 않는다. Mac 쪽은 자기 끊김에서 같은 결론에 도달해 있다.
    is ShareEv.Unlinked -> ShareStep(
        dropConsent(s).copy(linked = false),
        listOf(
            ShareFx.StopCapture(ev.reason, notifyPeer = false),
            ShareFx.ResetFrameIds,
            ShareFx.Log("경로 상실 — 동의 무효, 복귀 시 재요청"),
        ),
    )
}
