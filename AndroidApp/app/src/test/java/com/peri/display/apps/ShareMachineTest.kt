package com.peri.display.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 공유 앱의 불변식 — **사람의 동의가 세션보다 짧다**는 사실이 만드는 규칙들.
 */
class ShareMachineTest {

    private fun ShareState.after(vararg evs: ShareEv): ShareState =
        evs.fold(this) { s, e -> reduceShare(s, e).state }

    private fun ShareState.fxOf(ev: ShareEv): List<ShareFx> = reduceShare(this, ev).fx

    private fun linked() = ShareState(linked = true)

    private fun sharing() = linked().after(ShareEv.Requested, ShareEv.Granted)

    // MARK: 평범한 흐름

    @Test
    fun `Mac이 청하면 사람에게 묻는다`() {
        val step = reduceShare(linked(), ShareEv.Requested)
        assertEquals(Consent.ASKED, step.state.consent)
        assertTrue(ShareFx.AskConsent in step.fx)
    }

    @Test
    fun `허락하면 PTS만 되잡고 두 갈래로 보고한다`() {
        val step = reduceShare(linked().after(ShareEv.Requested), ShareEv.Granted)
        assertTrue(step.state.isRunning)
        assertTrue(ShareFx.ResetPts in step.fx)
        assertTrue(ShareFx.ReportPhase(SharePhase.GRANTED, "") in step.fx)
        assertTrue(ShareFx.Report(true, "") in step.fx)
        assertFalse("프레임 번호는 세션 키에 매여 있다 — 여기서 되돌리면 nonce가 겹친다",
                    ShareFx.ResetFrameIds in step.fx)
    }

    @Test
    fun `거절하면 동의를 놓고 사유를 실어 보고한다`() {
        val step = reduceShare(linked().after(ShareEv.Requested), ShareEv.Denied("기기에서 거부했습니다"))
        assertEquals(Consent.NONE, step.state.consent)
        assertTrue(ShareFx.ReportPhase(SharePhase.DENIED, "기기에서 거부했습니다") in step.fx)
    }

    // MARK: 중지 — 어느 단계였느냐가 보고 경로를 가른다

    @Test
    fun `돌던 공유를 멈추라 하면 보고는 종료 콜백이 낸다`() {
        // `ScreenShare.stop`이 `onStopped`를 내고, 그게 [ShareEv.Stopped]로 돌아온다.
        // 여기서 미리 보고하면 Mac에 같은 말이 두 번 간다.
        val step = reduceShare(sharing(), ShareEv.StopRequested("Mac에서 중지"))
        assertTrue(ShareFx.StopCapture("Mac에서 중지", notifyPeer = true) in step.fx)
        assertFalse(step.fx.any { it is ShareFx.ReportPhase })
    }

    @Test
    fun `동의를 기다리던 중 취소되면 여기서 보고한다`() {
        // ★ `ScreenShare.stop`은 돌고 있지 않으면 아무 콜백도 내지 않는다(멈출 것이 없으니).
        // 그래서 예전엔 Mac이 요청 직후 취소하면 답이 통째로 사라져 동의 대기 화면이 남았다.
        val step = reduceShare(linked().after(ShareEv.Requested), ShareEv.StopRequested("Mac에서 중지"))
        assertEquals(Consent.NONE, step.state.consent)
        assertTrue(ShareFx.ReportPhase(SharePhase.STOPPED, "Mac에서 중지") in step.fx)
        assertTrue(ShareFx.Report(false, "Mac에서 중지") in step.fx)
    }

    @Test
    fun `늦게 도착한 종료 통보는 같은 말을 두 번 하지 않는다`() {
        val stopped = sharing().after(ShareEv.Stopped("사용자 중지"))
        val step = reduceShare(stopped, ShareEv.Stopped("사용자 중지"))
        assertFalse(step.fx.any { it is ShareFx.ReportPhase })
    }

    // MARK: 재개 정책 — 확장과 갈리는 지점

    @Test
    fun `세션이 끊기면 동의가 통째로 무효다`() {
        // MediaProjection 토큰은 1회용이고 포그라운드 서비스도 그 연결과 함께 죽는다.
        // 확장은 같은 사건에서 디코더만 놓지만, 여기서는 사람에게 다시 물어야 한다.
        val step = reduceShare(sharing(), ShareEv.Unlinked("연결이 끊겼습니다"))
        assertEquals(Consent.NONE, step.state.consent)
        assertFalse(step.state.linked)
        assertTrue(ShareFx.StopCapture("연결이 끊겼습니다", notifyPeer = false) in step.fx)
    }

    @Test
    fun `프레임 번호는 세션이 갈릴 때만 되돌린다`() {
        // 새 세션 = 새 키 → nonce 공간이 처음부터 다시 열린다. 그 밖의 어느 지점에서
        // 되돌려도 같은 키에 같은 nonce를 두 번 쓰게 된다 — GCM은 거기서 무너진다.
        assertTrue(ShareFx.ResetFrameIds in sharing().fxOf(ShareEv.Unlinked("끊김")))
        assertFalse(ShareFx.ResetFrameIds in sharing().fxOf(ShareEv.Stopped("사용자 중지")))
        assertFalse(ShareFx.ResetFrameIds in linked().after(ShareEv.Requested).fxOf(ShareEv.Granted))
    }

    @Test
    fun `세션이 끊기면 상대에게 알리지 않는다`() {
        val fx = sharing().fxOf(ShareEv.Unlinked("연결이 끊겼습니다"))
        assertFalse(fx.any { it is ShareFx.ReportPhase })
        assertFalse(fx.any { it is ShareFx.Report })
    }

    // MARK: 복구 경로

    @Test
    fun `이미 공유 중인데 다시 청하면 그대로 흘려보낸다`() {
        // `ScreenShare`가 "이미 공유 중"을 보고 상태를 다시 통보하는 복구 경로 — 여기서
        // 걸러 버리면 Mac이 상태를 놓쳤을 때 되찾을 방법이 없어진다.
        val step = reduceShare(sharing(), ShareEv.Requested)
        assertEquals(Consent.GRANTED, step.state.consent)
        assertTrue(ShareFx.AskConsent in step.fx)
    }
}
