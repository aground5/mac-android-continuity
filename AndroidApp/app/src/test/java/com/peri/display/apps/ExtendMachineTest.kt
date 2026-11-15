package com.peri.display.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 확장 앱의 불변식 — **디코더는 언제 서고 언제 눕는가.**
 *
 * 이 판단은 예전에 `MirrorController` 안에 네 조각(`ensureDecoder`·`prepareDecoder`·
 * `pendingConnect`·직접 `stop()`)으로 흩어져 있어서 검증할 방법이 실기기뿐이었다.
 * 증상이 전부 "검은 화면"이라 어느 조각이 틀렸는지 로그로도 구분이 안 됐다.
 */
class ExtendMachineTest {

    private fun ExtendState.after(vararg evs: ExtendEv): ExtendState =
        evs.fold(this) { s, e -> reduceExtend(s, e).state }

    private fun ExtendState.fxOf(ev: ExtendEv): List<ExtendFx> = reduceExtend(this, ev).fx

    /** 화면도 채널도 있고 앱은 아직 안 떴다 — 세션이 막 선 직후의 평범한 상태. */
    private fun ready() = ExtendState(surface = true, linked = true)

    // MARK: 파생 규칙

    @Test
    fun `디코더는 켜짐과 화면의 곱이다`() {
        // 이 넷이 기계의 전부다. 하나라도 어긋나면 아래 시나리오가 전부 무너진다.
        for (wanted in listOf(false, true)) {
            for (surface in listOf(false, true)) {
                val s = ExtendState().after(
                    ExtendEv.SurfaceChanged(surface),
                ).let { if (wanted) it.after(ExtendEv.Launched) else it }
                assertEquals("wanted=$wanted surface=$surface", wanted && surface, s.decoder)
            }
        }
    }

    @Test
    fun `세션이 붙었다고 디코더가 서지 않는다`() {
        // Mac이 확장을 안 띄웠으면 프레임은 애초에 안 온다. 미리 세우면 MediaCodec 하나를
        // 아무 이유 없이 붙들고 있게 된다.
        val s = ready().after(ExtendEv.PathLive)
        assertFalse(s.decoder)
        assertTrue(ready().fxOf(ExtendEv.PathLive).isEmpty())
    }

    @Test
    fun `Mac이 확장을 띄우면 디코더가 선다`() {
        val fx = ready().fxOf(ExtendEv.Launched)
        assertTrue(ExtendFx.StartDecoder in fx)
        assertTrue(ExtendFx.Report(true, "") in fx)
    }

    // MARK: 키프레임 — 검은 화면의 원인이었던 자리

    @Test
    fun `디코더를 세우는 모든 문에서 키프레임을 청한다`() {
        // ★ 실기기 버그: 새 MediaCodec은 키프레임 전까지 한 장도 못 그리는데 Mac은 주기
        // IDR을 내지 않는다. 청하지 않으면 그 대기가 끝나지 않고, **로그도 안 나온다** —
        // 패킷은 도착하고 복호도 성공하는데 화면만 검다.
        //
        // 세우는 갈래가 셋이라 갈래마다 손으로 붙이면 언젠가 하나를 빠뜨린다. 그 하나가
        // 곧 "어떤 경우에만 검은 화면"이 되고, 재현 조건을 찾는 데 하루가 간다.
        val starts = listOf(
            "켜짐" to ready().fxOf(ExtendEv.Launched),
            "화면 복귀" to ExtendState(wanted = true).fxOf(ExtendEv.SurfaceChanged(true)),
            "재구성" to ready().after(ExtendEv.Launched).fxOf(ExtendEv.Restart("회전")),
        )
        for ((why, fx) in starts) {
            assertTrue("$why: 디코더를 세운다", ExtendFx.StartDecoder in fx)
            assertTrue("$why: 키프레임을 청한다", ExtendFx.RequestKeyframe in fx)
            assertTrue("$why: 세운 뒤에 청해야 한다",
                       fx.indexOf(ExtendFx.StartDecoder) < fx.indexOf(ExtendFx.RequestKeyframe))
        }
    }

    @Test
    fun `디코더를 세우지 않으면 키프레임도 청하지 않는다`() {
        // 청은 공짜가 아니다 — Mac이 LTR-P를 굽는다. 그릴 곳도 없는데 청하면 낭비다.
        assertFalse(ExtendFx.RequestKeyframe in ExtendState().fxOf(ExtendEv.Launched))
        assertFalse(ExtendFx.RequestKeyframe in ready().fxOf(ExtendEv.PathLive))
        assertFalse(ExtendFx.RequestKeyframe in
            ready().after(ExtendEv.Launched).fxOf(ExtendEv.SurfaceChanged(false)))
    }

    @Test
    fun `이미 서 있는 디코더에는 다시 청하지 않는다`() {
        val up = ready().after(ExtendEv.Launched)
        assertFalse(ExtendFx.RequestKeyframe in up.fxOf(ExtendEv.PathLive))
        assertFalse(ExtendFx.RequestKeyframe in up.fxOf(ExtendEv.Launched))
    }

    // MARK: 화면(Surface)의 수명

    @Test
    fun `화면이 사라지면 디코더도 눕는다`() {
        // ★ 실기기 버그: 예전엔 `setSurface(null)`이 디코더를 그대로 뒀다. MediaCodec이
        // 파괴된 표면에 계속 그렸고, 화면이 돌아와도 `pendingConnect`가 false라 아무도
        // 다시 세우지 않아 검은 화면이 남았다.
        val up = ready().after(ExtendEv.Launched)
        assertTrue(up.decoder)
        val step = reduceExtend(up, ExtendEv.SurfaceChanged(false))
        assertFalse(step.state.decoder)
        assertTrue(ExtendFx.StopDecoder in step.fx)
    }

    @Test
    fun `화면이 돌아오면 디코더를 다시 세운다`() {
        val s = ready().after(ExtendEv.Launched, ExtendEv.SurfaceChanged(false))
        val step = reduceExtend(s, ExtendEv.SurfaceChanged(true))
        assertTrue(step.state.decoder)
        assertTrue(ExtendFx.StartDecoder in step.fx)
    }

    @Test
    fun `화면이 없는 동안 켜도 앱은 켜진 것이다`() {
        // 옛 `pendingConnect`가 표현하려던 상태 — 이제 축이 아니라 파생값이다.
        val s = ExtendState().after(ExtendEv.Launched)
        assertTrue(s.isRunning)
        assertFalse(s.decoder)
        assertEquals("화면을 기다리는 중…", s.detail)
    }

    // MARK: 재구성

    @Test
    fun `회전은 디코더를 새로 세우고 재조립을 비운다`() {
        // 해상도는 MediaCodec에 굳어 있어 다시 만드는 수밖에 없고, 옛 해상도의 조각이
        // 남아 있으면 새 프레임과 섞인다.
        val step = reduceExtend(ready().after(ExtendEv.Launched), ExtendEv.Restart("회전"))
        assertTrue(step.state.decoder)
        val stop = step.fx.indexOfFirst { it is ExtendFx.StopDecoder }
        val start = step.fx.indexOfFirst { it is ExtendFx.StartDecoder }
        assertTrue("정리가 먼저다", stop in 0 until start)
        assertTrue(ExtendFx.ClearReassembly in step.fx)
    }

    @Test
    fun `앱이 안 떠 있으면 재구성은 아무것도 세우지 않는다`() {
        // 콜드 연결 직전이 이 경우다 — 세션이 서고 Mac이 AppControl을 보내야 뜬다.
        val step = reduceExtend(ready(), ExtendEv.Restart("연결 시작"))
        assertFalse(step.state.decoder)
        assertFalse(step.fx.any { it is ExtendFx.StartDecoder })
    }

    // MARK: 종료 경로 — 보고 여부가 갈린다

    @Test
    fun `Mac이 내리면 사유를 실어 보고한다`() {
        val fx = ready().after(ExtendEv.Launched).fxOf(ExtendEv.Quit("Mac에서 종료"))
        assertTrue(ExtendFx.StopDecoder in fx)
        assertTrue(ExtendFx.Report(false, "Mac에서 종료") in fx)
    }

    // MARK: 경로가 끊겼을 때 — Mac과 대칭이어야 한다

    @Test
    fun `경로만 끊기면 아무것도 버리지 않는다`() {
        // ★ 실기기 버그: 세션이 사라지면 무조건 앱을 내렸다. 그런데 전송 전환(LAN↔USB)과
        // 재접속은 **세션이 새로 서는 일**이라 그때마다 확장이 꺼졌다 켜졌다. Mac은 같은
        // 순간 인코더와 가상 디스플레이를 일부러 들고 있는데(무중단의 근거) 폰만 전부
        // 버리니, 돌아올 때마다 코덱을 새로 세우고 키프레임을 기다리는 검은 화면을 겪었다.
        val up = ready().after(ExtendEv.Launched)
        val step = reduceExtend(up, ExtendEv.Unlinked)

        assertTrue("앱은 켜진 채다", step.state.isRunning)
        assertTrue("디코더도 그대로다", step.state.decoder)
        assertFalse(step.state.linked)
        assertFalse("버리는 지시가 하나도 없어야 한다",
                    step.fx.any { it is ExtendFx.StopDecoder || it is ExtendFx.ClearReassembly })
    }

    @Test
    fun `경로가 돌아와도 디코더를 다시 세우지 않는다`() {
        // 아무것도 안 하는 게 곧 무중단이다. 여기서 재구성하면 키프레임 대기가 생기고,
        // 그건 경로를 살려 낸 의미를 통째로 없앤다.
        val paused = ready().after(ExtendEv.Launched, ExtendEv.Unlinked)
        val step = reduceExtend(paused, ExtendEv.PathLive)

        assertTrue(step.state.linked)
        assertTrue(step.state.decoder)
        assertTrue("아무 지시도 없어야 한다", step.fx.isEmpty())
    }

    @Test
    fun `경로를 기다리는 중임을 사용자에게 말한다`() {
        val paused = ready().after(ExtendEv.Launched, ExtendEv.Unlinked)
        assertEquals("경로를 기다리는 중…", paused.detail)
    }

    @Test
    fun `경로가 없는 동안 화면이 사라졌다 돌아오면 다시 세운다`() {
        // 두 축이 독립이라는 확인. 경로는 없어도 화면 축은 평소대로 돌아야 한다.
        var s = ready().after(ExtendEv.Launched, ExtendEv.Unlinked)
        s = s.after(ExtendEv.SurfaceChanged(false))
        assertFalse(s.decoder)
        val step = reduceExtend(s, ExtendEv.SurfaceChanged(true))
        assertTrue(step.state.decoder)
        assertTrue(ExtendFx.StartDecoder in step.fx)
        assertTrue("참조가 없으니 청해야 한다", ExtendFx.RequestKeyframe in step.fx)
    }

    @Test
    fun `세션이 사라지면 조용히 내린다`() {
        // 알릴 채널이 없다. 보고를 시도하면 봉인 단계에서 조용히 버려지지만, 그런 코드를
        // 남겨 두면 "보냈는데 안 갔다"를 로그로 구분할 수 없게 된다.
        val step = reduceExtend(ready().after(ExtendEv.Launched), ExtendEv.SessionLost)
        assertFalse(step.state.isRunning)
        assertTrue(ExtendFx.StopDecoder in step.fx)
        assertFalse(step.fx.any { it is ExtendFx.Report })
    }

    @Test
    fun `중복 지시에도 보고는 다시 나간다`() {
        // Mac이 상태를 놓쳐 다시 보낸 경우. 조용히 있으면 Mac은 영영 우리 절반이 섰는지 모른다.
        val up = ready().after(ExtendEv.Launched)
        val step = reduceExtend(up, ExtendEv.Launched)
        assertEquals(up, step.state)
        assertTrue(ExtendFx.Report(true, "") in step.fx)
        assertFalse("이미 서 있는 디코더를 다시 만들지 않는다",
                    step.fx.any { it is ExtendFx.StartDecoder })
    }
}
