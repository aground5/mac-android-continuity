package com.peri.display.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 알림 앱의 불변식 — **켜 달라는 지시만으로는 켜지지 않는다.**
 */
class NotifyMachineTest {

    private fun NotifyState.fxOf(ev: NotifyEv): List<NotifyFx> = reduceNotify(this, ev).fx

    @Test
    fun `권한이 있으면 켜짐으로 보고한다`() {
        val step = reduceNotify(NotifyState(), NotifyEv.Launched(granted = true))
        assertTrue(step.state.isRunning)
        assertTrue(NotifyFx.SetBridgeWanted(true) in step.fx)
        assertTrue(NotifyFx.Report(true, "") in step.fx)
    }

    @Test
    fun `권한이 없으면 켜졌다고 거짓말하지 않는다`() {
        // 예전엔 `wanted`만 보고했다. Mac 화면에는 "알림 켜짐"이라고 뜨는데 한 건도 안 와서
        // 원인이 어디에도 보이지 않았다.
        val step = reduceNotify(NotifyState(), NotifyEv.Launched(granted = false))
        assertFalse(step.state.isRunning)
        assertTrue(NotifyFx.Report(false, "기기에서 알림 접근을 허용해야 합니다") in step.fx)
        assertTrue(step.fx.any { it is NotifyFx.Log })
    }

    @Test
    fun `권한이 없어도 다리는 열어 둔다`() {
        // 사람이 설정에서 켜는 순간 리스너가 붙는다. 그때 다리가 닫혀 있으면 알림이
        // 올라오지 않고, 그걸 다시 열 사건은 다음 [NotifyEv.Launched]뿐이다.
        val step = reduceNotify(NotifyState(), NotifyEv.Launched(granted = false))
        assertTrue(NotifyFx.SetBridgeWanted(true) in step.fx)
    }

    @Test
    fun `UI 안내의 근거는 켜짐이지 곱이 아니다`() {
        val s = reduceNotify(NotifyState(), NotifyEv.Launched(granted = false)).state
        assertTrue("권한이 없어서 안 도는 바로 그 경우에 안내가 필요하다", s.needsPermission)
        assertTrue(s.wanted)
    }

    @Test
    fun `사람이 나중에 권한을 켜면 그 사실이 Mac에 간다`() {
        val s = reduceNotify(NotifyState(), NotifyEv.Launched(granted = false)).state
        val step = reduceNotify(s, NotifyEv.PermissionChanged(granted = true))
        assertTrue(step.state.isRunning)
        assertTrue(NotifyFx.Report(true, "") in step.fx)
    }

    @Test
    fun `켜지도 않은 앱의 권한 변화는 알릴 것이 없다`() {
        val step = reduceNotify(NotifyState(), NotifyEv.PermissionChanged(granted = true))
        assertTrue(step.fx.isEmpty())
    }

    @Test
    fun `같은 권한 상태는 아무 일도 일으키지 않는다`() {
        val s = reduceNotify(NotifyState(), NotifyEv.Launched(granted = true)).state
        val step = reduceNotify(s, NotifyEv.PermissionChanged(granted = true))
        assertEquals(s, step.state)
        assertTrue(step.fx.isEmpty())
    }

    @Test
    fun `내리면 다리를 닫고 사유를 실어 보고한다`() {
        val s = reduceNotify(NotifyState(), NotifyEv.Launched(granted = true)).state
        val fx = s.fxOf(NotifyEv.Quit("Mac에서 종료"))
        assertTrue(NotifyFx.SetBridgeWanted(false) in fx)
        assertTrue(NotifyFx.Report(false, "Mac에서 종료") in fx)
    }
}
