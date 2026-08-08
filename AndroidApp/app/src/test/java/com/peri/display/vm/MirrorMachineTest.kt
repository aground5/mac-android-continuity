package com.peri.display.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 상태 기계 불변식 — **각 테스트가 실기기에서 한 번씩 겪은 버그다.**
 *
 * 예전에는 이 시나리오들을 검증할 방법이 LTE↔Wi-Fi를 손으로 왕복시키며 logcat을 읽는 것뿐이었다.
 * 재현이 확률적이라(겹침 창의 유무, 망이 뜨는 타이밍) 고쳤는지 확인하는 데만 수십 분이 걸렸고,
 * 고친 뒤 다른 경로가 깨졌는지는 사실상 확인할 수 없었다. 이제 전부 여기서 초 단위로 돈다.
 */
class MirrorMachineTest {

    // MARK: 픽스처

    private fun idle() = MirrorState()

    /** 정상 스트리밍 중. */
    private fun streaming() = MirrorState(sess = Sess.Live("Mac"), path = Path.Live(Via.LAN))

    private fun MirrorState.step(ev: Ev): Step = reduce(this, ev)

    private fun MirrorState.after(vararg evs: Ev): MirrorState =
        evs.fold(this) { st, e -> reduce(st, e).state }

    private fun MirrorState.fxOf(ev: Ev): List<Fx> = reduce(this, ev).fx

    // MARK: 콜드 경로

    @Test
    fun `콜드 연결은 IDLE→CONNECTING→HANDSHAKING→STREAMING을 지난다`() {
        var s = idle()
        assertEquals(ConnState.IDLE, s.conn)
        s = s.after(Ev.StartConnect(Via.LAN))
        assertEquals(ConnState.CONNECTING, s.conn)
        s = s.after(Ev.TransportUp)
        assertEquals(ConnState.HANDSHAKING, s.conn)
        s = s.after(Ev.SessionLive("Mac"))
        assertEquals(ConnState.STREAMING, s.conn)
    }

    @Test
    fun `세션이 서면 keepalive가 시작된다`() {
        val fx = MirrorState(sess = Sess.Establishing, path = Path.Live(Via.LAN))
            .fxOf(Ev.SessionLive("Mac"))
        assertTrue(Fx.StartKeepalive in fx)
    }

    // MARK: 무손실 재개 (핸드오버의 핵심)

    @Test
    fun `재협상 중 옛 전송이 죽어도 세션을 버리지 않는다`() {
        // 실기기: host 직결 성립 30ms 뒤·swapTo 직전에 옛 LTE 연결이 죽었고, 그때 세션이 지워져
        // 뒤이은 sendUdpHello가 조용히 빠져나갔다. Mac은 RESUME으로 재바인딩했는데 Android만
        // 세션을 버린 반쪽 상태.
        val s = streaming().after(Ev.NegoNeeded("망 전환"))
        val step = s.step(Ev.TransportDown())
        assertFalse("재협상 중에는 DropSession이 나오면 안 된다", Fx.DropSession in step.fx)
        assertTrue(step.state.sess is Sess.Live)
        assertEquals(Path.None, step.state.path)
        assertEquals(ConnState.CONNECTING, step.state.conn)
    }

    @Test
    fun `무손실 교체가 끝나면 keepalive가 반드시 되살아난다`() {
        // 재개 경로엔 핸드셰이크가 없어 startKeepalive를 부르는 곳이 없었다. 그대로 두면 Ping이
        // 끊겨 Mac의 생존감지(6s)가 방금 살려낸 세션을 도로 죽인다.
        val s = streaming()
            .after(Ev.NegoNeeded("망 전환"), Ev.TransportDown(), Ev.MacAnswered(true))
        assertTrue(s.nego is Nego.Punching && (s.nego as Nego.Punching).seamless)
        val step = s.step(Ev.PathSwapped)
        assertTrue(Fx.StartKeepalive in step.fx)
        assertEquals(ConnState.STREAMING, step.state.conn)
    }

    @Test
    fun `세션이 살아 있으면 seamless, 죽었으면 cold로 뚫는다`() {
        val live = streaming().after(Ev.NegoNeeded("망 전환"))
        assertTrue(Fx.PunchSeamless in live.fxOf(Ev.MacAnswered(true)))

        // USB 분리처럼 세션이 이미 사라진 경우.
        val dead = live.copy(sess = Sess.None, path = Path.None)
        assertTrue(Fx.PunchCold in dead.fxOf(Ev.MacAnswered(true)))
    }

    @Test
    fun `전송이 죽어 CONNECTING으로 내려가도 seamless를 유지한다`() {
        // 상태(STREAMING)를 조건으로 걸면 여기서 콜드로 떨어져, 멀쩡한 세션을 두고
        // 새 핸드셰이크·새 가상 디스플레이를 만든다.
        val s = streaming().after(Ev.NegoNeeded("망 전환"), Ev.TransportDown())
        assertEquals(ConnState.CONNECTING, s.conn)
        assertTrue(Fx.PunchSeamless in s.fxOf(Ev.MacAnswered(true)))
    }

    // MARK: 라운드 겹침 / 예약

    @Test
    fun `라운드 중 들어온 재협상은 삼켜지지 않고 예약된다`() {
        // 실기기: 후보 0개 라운드가 도는 중에 진짜 망이 왔는데 "이미 진행 중"이라 무시됐고,
        // 그 라운드가 실패로 끝난 뒤 아무도 재시도하지 않아 영구 정지했다.
        val s = streaming().after(Ev.NegoNeeded("첫 전환"), Ev.NegoNeeded("두 번째 전환"))
        assertEquals("두 번째 전환", s.queued)
        assertTrue(s.nego is Nego.Requested)

        val done = s.step(Ev.NegoDone(ok = false, note = "타임아웃"))
        assertTrue("예약분이 즉시 새 라운드가 돼야 한다", done.state.nego is Nego.Requested)
        assertEquals("두 번째 전환", (done.state.nego as Nego.Requested).reason)
        assertEquals(null, done.state.queued)
        assertTrue(Fx.RaiseNegoBit in done.fx)
    }

    @Test
    fun `성공한 라운드여도 예약이 있으면 한 번 더 돈다`() {
        // 성공했더라도 그건 낡은 망 기준이라 이미 무효일 수 있다.
        val s = streaming().after(Ev.NegoNeeded("첫 전환"), Ev.NegoNeeded("두 번째 전환"))
        val done = s.step(Ev.NegoDone(ok = true, note = "성공"))
        assertTrue(done.state.nego is Nego.Requested)
    }

    @Test
    fun `라운드는 한 번에 하나뿐이다`() {
        val s = streaming().after(
            Ev.NegoNeeded("a"), Ev.NegoNeeded("b"), Ev.NegoNeeded("c"), Ev.NegoNeeded("d"),
        )
        assertTrue(s.nego is Nego.Requested)
        assertEquals("마지막 요청만 예약된다", "d", s.queued)
    }

    @Test
    fun `후보가 낡았으면 그 라운드를 버리고 새로 돈다`() {
        val s = streaming().after(Ev.NegoNeeded("망 전환"))
        val step = s.step(Ev.MacAnswered(candidatesFresh = false))
        assertTrue(Fx.ReleaseRoundSockets in step.fx)
        assertTrue(step.state.nego is Nego.Requested)
        assertEquals("후보 낡음", (step.state.nego as Nego.Requested).reason)
    }

    // MARK: 정지 래치

    @Test
    fun `치명 거부는 래치라 망이 바뀌어도 되살아나지 않는다`() {
        // 예전 fatalError는 1회용이라, ERROR로 멈춘 뒤 망이 한 번만 바뀌면 거부당한 그 Mac에게
        // 다시 붙으러 갔다.
        var s = streaming().after(Ev.Fatal("서버(Mac) 인증 실패 — 연결 거부"))
        assertEquals(ConnState.ERROR, s.conn)
        s = s.after(Ev.NetworkUp("기본망 전환"), Ev.NegoNeeded("망 전환"), Ev.TransportDown())
        assertTrue("치명 정지가 풀리면 안 된다", s.intent is Intent.StoppedFatal)
        assertEquals(Nego.Idle, s.nego)
        assertEquals(ConnState.ERROR, s.conn)
    }

    @Test
    fun `사용자 종료도 래치다`() {
        var s = streaming().after(Ev.UserStop)
        assertEquals(ConnState.IDLE, s.conn)
        s = s.after(Ev.NetworkUp("기본망 전환"), Ev.NegoNeeded("망 전환"))
        assertEquals(Nego.Idle, s.nego)
        assertEquals(ConnState.IDLE, s.conn)
    }

    @Test
    fun `끊은 뒤 Mac에서 다시 연결하면 래치가 풀린다`() {
        // 실기기: '끊기' 후 Mac에서 재연결하면 BLE 라운드는 도는데 Android가 통째로 무시했다.
        // 래치를 푸는 사건이 StartConnect뿐인데 그건 PunchCold가 부르고, PunchCold는
        // MacAnswered에서만 나오므로 순환이었다.
        val stopped = streaming().after(Ev.UserStop)
        assertEquals(ConnState.IDLE, stopped.conn)
        val step = stopped.step(Ev.MacAnswered(candidatesFresh = true))
        assertEquals("Mac이 붙어 오면 정지가 풀려야 한다", Intent.Running, step.state.intent)
        assertTrue(Fx.PunchCold in step.fx)
    }

    @Test
    fun `치명 거부는 Mac이 붙어 와도 안 풀린다`() {
        val fatal = streaming().after(Ev.Fatal("인증 실패"))
        val step = fatal.step(Ev.MacAnswered(candidatesFresh = true))
        assertTrue(step.state.intent is Intent.StoppedFatal)
        assertTrue(step.fx.none { it is Fx.PunchCold || it is Fx.PunchSeamless })
    }

    @Test
    fun `Mac의 페어링 해제는 치명이라 다시 붙지 않는다`() {
        // Mac에서 '페어링 해제' → Bye(AUTH_REJECTED). 이건 평범한 HostStop과 달라야 한다:
        // 재시도가 원리상 무의미하므로(신원이 거부됐다) Mac이 후보를 줘도 무시해야 한다.
        //
        // 예전엔 `onHostBye`가 사유를 안 보고 전부 HostStop으로 처리했다 — 화면엔 그냥
        // "연결 안 됨"이 뜨고, Mac이 다시 후보를 주면 붙으러 갔다.
        val revoked = streaming().after(Ev.Fatal("Mac에서 페어링이 해제되었습니다"))
        assertEquals(ConnState.ERROR, revoked.conn)
        assertEquals("사유가 사용자에게 보여야 한다",
                     "Mac에서 페어링이 해제되었습니다", revoked.status)

        val step = revoked.step(Ev.MacAnswered(candidatesFresh = true))
        assertTrue("해제된 뒤 Mac이 붙어 와도 무시한다", step.state.intent is Intent.StoppedFatal)
        assertTrue(step.fx.none { it is Fx.PunchCold || it is Fx.PunchSeamless })

        // 반면 평범한 '연결 해제'(HOST_DISCONNECT)는 래치가 풀려야 한다 — 대칭 확인.
        val parted = streaming().after(Ev.HostStop)
        assertEquals(ConnState.IDLE, parted.conn)
        assertTrue(Fx.PunchCold in parted.step(Ev.MacAnswered(candidatesFresh = true)).fx)
    }

    @Test
    fun `우리가 올리지 않은 광고 비트는 내리지 않는다`() {
        // clearIceRestart는 광고를 껐다 켠다. Mac이 먼저 말을 걸어온 라운드에서 그러면
        // 붙어 있는 central이 떨어져 후보 응답이 전달되지 못한다(실기기).
        val macLed = idle().after(Ev.MacAnswered(true))
        assertFalse(Fx.ClearNegoBit in macLed.fxOf(Ev.NegoDone(true, "ok")))

        // 우리가 요청한 라운드는 반드시 내린다.
        val ours = streaming().after(Ev.NegoNeeded("망 전환"), Ev.MacAnswered(true))
        assertTrue(Fx.ClearNegoBit in ours.fxOf(Ev.NegoDone(true, "ok")))
    }

    @Test
    fun `사용자가 다시 연결하면 래치가 풀린다`() {
        val s = streaming()
            .after(Ev.Fatal("인증 실패"), Ev.StartConnect(Via.LAN))
        assertEquals(Intent.Running, s.intent)
        assertEquals(ConnState.CONNECTING, s.conn)
    }

    @Test
    fun `Bye는 세션과 전송을 내리기 전에 나간다`() {
        // ★ **두 전송 모두** 확인한다. 기계는 전송을 모르므로 순서는 원래 같지만, 실기기에서
        // USB '끊기'가 Bye 없이 끊긴 적이 있다 — 원인은 실행기(AoaClient)가 송신을 큐에 넣자마자
        // fd를 닫은 것이었고, 호출부의 `if (b is LanClient)` 분기가 그 구멍을 가리고 있었다.
        // 기계 계약이 전송에 무관하다는 걸 여기서 못 박아 두면 다음 bearer도 같은 기준을 진다.
        for (via in listOf(Via.LAN, Via.AOA)) {
            val fx = MirrorState(sess = Sess.Live("Mac"), path = Path.Live(via)).fxOf(Ev.UserStop)
            val bye = fx.indexOf(Fx.SendBye)
            val close = fx.indexOf(Fx.CloseTransport)
            val drop = fx.indexOf(Fx.DropSession)
            assertTrue("[$via] Bye가 있어야 한다", bye >= 0)
            assertTrue("[$via] Bye는 전송을 닫기 전에", bye < close)
            assertTrue("[$via] Bye는 세션을 버리기 전에", bye < drop)
        }
    }

    @Test
    fun `세션이 없으면 Bye를 보내지 않는다`() {
        val fx = MirrorState(path = Path.Connecting(Via.LAN)).fxOf(Ev.UserStop)
        assertFalse(Fx.SendBye in fx)
    }

    // MARK: 재접속 백오프

    @Test
    fun `평범한 끊김은 지수 백오프로 3회 재시도한 뒤 멈춘다`() {
        var s = streaming()
        val delays = mutableListOf<Long>()
        repeat(MAX_BACKOFF) {
            val down = s.step(Ev.TransportDown(canRetryAddress = true))
            delays += (down.fx.filterIsInstance<Fx.ScheduleBackoff>().single()).delayMs
            s = down.state.after(Ev.BackoffElapsed, Ev.TransportUp)
        }
        assertEquals(listOf(1000L, 2000L, 4000L), delays)

        val exhausted = s.step(Ev.TransportDown(canRetryAddress = true))
        assertTrue(exhausted.state.path is Path.Failed)
        assertEquals(ConnState.ERROR, exhausted.state.conn)
        assertTrue(Fx.ReleaseWifiLock in exhausted.fx)
    }

    @Test
    fun `세션이 서야 백오프 카운터가 리셋된다`() {
        val s = streaming()
            .after(Ev.TransportDown(), Ev.BackoffElapsed, Ev.TransportUp, Ev.SessionLive("Mac"))
        val again = s.step(Ev.TransportDown())
        assertEquals(0, (again.state.path as Path.Backoff).attempt)
    }

    @Test
    fun `TCP만 붙고 핸드셰이크가 안 오면 재시도 예산이 소진된다`() {
        // Mac 앱이 안 떠 있으면 fd/소켓은 열리지만 ServerHello가 영영 안 온다. 카운터를 경로에
        // 실으면 TransportUp마다 리셋돼 무한 재접속이 된다.
        var s = MirrorState(path = Path.Connecting(Via.LAN))
        repeat(MAX_BACKOFF) {
            s = s.after(Ev.TransportUp, Ev.TransportDown(), Ev.BackoffElapsed)
        }
        val dead = s.after(Ev.TransportUp).step(Ev.TransportDown())
        assertTrue(dead.state.path is Path.Failed)
    }

    @Test
    fun `돌아갈 주소가 없으면 백오프 대신 재협상한다`() {
        // 무손실 교체로 붙은 세션은 host/port를 남기지 않는다. 예전엔 `::1:0`으로 5초 타임아웃 ×3.
        val step = streaming().step(Ev.TransportDown(canRetryAddress = false))
        assertTrue(step.fx.none { it is Fx.ScheduleBackoff })
        assertTrue(step.state.nego is Nego.Requested)
    }

    @Test
    fun `재협상 중에는 옛 주소를 두드리지 않는다`() {
        // 죽은 게 확정인 주소를 5초 ×3으로 두드리면 22초를 버리고 진짜 복구를 가린다.
        val s = streaming().after(Ev.NegoNeeded("망 전환"))
        val step = s.copy(sess = Sess.None).step(Ev.TransportDown(canRetryAddress = true))
        assertTrue(step.fx.none { it is Fx.ScheduleBackoff })
    }

    @Test
    fun `재협상이 시작되면 예약된 재접속이 취소된다`() {
        val s = streaming().after(Ev.TransportDown())
        assertTrue(s.path is Path.Backoff)
        assertTrue(Fx.CancelBackoff in s.fxOf(Ev.NegoNeeded("망 전환")))
    }

    // MARK: ERROR는 종착이 아니다

    @Test
    fun `재접속이 소진돼 ERROR로 멈춘 뒤에도 망 전환이 복구를 건다`() {
        // 겹침 없는 전환에서 새 망이 14초 뒤에 뜬 실기기 사례 — 그때 이미 ERROR였다.
        var s = streaming()
        repeat(MAX_BACKOFF + 1) { s = s.after(Ev.TransportDown(), Ev.BackoffElapsed, Ev.TransportUp) }
        s = s.after(Ev.TransportDown())
        assertEquals(ConnState.ERROR, s.conn)

        val step = s.step(Ev.NetworkUp("기본망 재부착"))
        assertTrue("ERROR에서도 재협상이 걸려야 한다", step.state.nego is Nego.Requested)
    }

    @Test
    fun `진짜 유휴 상태에서는 망 전환을 무시한다`() {
        val step = idle().step(Ev.NetworkUp("기본망 전환"))
        assertEquals(Nego.Idle, step.state.nego)
        assertTrue(step.fx.isEmpty())
    }

    // MARK: 라운드 실패의 뒷정리

    @Test
    fun `라운드가 실패해도 옛 경로가 살아 있으면 아무것도 버리지 않는다`() {
        val s = streaming().after(Ev.NegoNeeded("망 전환"))
        val step = s.step(Ev.NegoDone(ok = false, note = "제어 채널 확보 실패"))
        assertFalse(Fx.DropSession in step.fx)
        assertEquals(ConnState.STREAMING, step.state.conn)
    }

    @Test
    fun `돌아갈 경로 없이 라운드가 실패하면 ERROR로 확정한다`() {
        // 여기서 멈추지 않으면 CONNECTING에 영원히 매달린다.
        val s = streaming().after(Ev.NegoNeeded("망 전환"), Ev.TransportDown())
        val step = s.step(Ev.NegoDone(ok = false, note = "Mac 응답 없음"))
        assertTrue(Fx.DropSession in step.fx)
        assertTrue(Fx.ReleaseWifiLock in step.fx)
        assertEquals(ConnState.ERROR, step.state.conn)
    }

    @Test
    fun `펀칭 성공은 경로가 아직 수립 중이어도 성공이다`() {
        // 실기기 검은 화면의 원인. 콜드 펀칭이 성공한 그 순간 경로는 Connecting이다 —
        // 소켓을 채택하고 read 루프가 붙어 TransportUp이 올라오는 건 한 박자 뒤다.
        // 이걸 실패로 접으면 DropSession이 돌아 디코더가 날아가고, 뒤이어 핸드셰이크는
        // 정상적으로 끝나 영상은 오는데 그릴 디코더가 없다.
        val s = idle().after(Ev.MacAnswered(true), Ev.StartConnect(Via.LAN))
        assertTrue(s.path is Path.Connecting)
        val step = s.step(Ev.NegoDone(ok = true, note = "콜드 재연결 성공"))
        assertFalse("성공 보고인데 세션을 버리면 안 된다", Fx.DropSession in step.fx)
        assertEquals(ConnState.CONNECTING, step.state.conn)
        // 이어서 전송이 올라오면 평소대로 핸드셰이크로 간다.
        assertEquals(ConnState.HANDSHAKING, step.state.after(Ev.TransportUp).conn)
    }

    @Test
    fun `경로가 아예 없는 늦은 성공 보고는 실패로 다룬다`() {
        // 워치독이 먼저 터져 라운드를 접은 뒤 도착한 성공. 실을 곳이 없으므로 받아들이면
        // 아무 타이머도 없는 영구 정지점이 된다.
        val s = streaming().after(Ev.NegoNeeded("망 전환"), Ev.TransportDown())
        assertEquals(Path.None, s.path)
        val step = s.step(Ev.NegoDone(ok = true, note = "늦은 성공"))
        assertTrue(step.state.path is Path.Failed)
    }

    @Test
    fun `세션이 서는 모든 문에서 디코더가 보장된다`() {
        // 디코더 생성이 기계 밖에 흩어져 있어서, 그 사이에 DropSession이 끼면 아무도 다시
        // 만들지 않았다 — 영상은 도착하는데 그릴 곳이 없는 검은 화면.
        val cold = MirrorState(sess = Sess.Establishing, path = Path.Live(Via.LAN))
        assertTrue(Fx.EnsureDecoder in cold.fxOf(Ev.SessionLive("Mac")))

        val resume = streaming().after(Ev.NegoNeeded("망 전환"), Ev.TransportDown(), Ev.MacAnswered(true))
        assertTrue(Fx.EnsureDecoder in resume.fxOf(Ev.PathSwapped))
    }

    @Test
    fun `라운드가 끝나면 광고 비트를 반드시 내린다`() {
        val s = streaming().after(Ev.NegoNeeded("망 전환"))
        listOf(true, false).forEach { ok ->
            assertTrue(Fx.ClearNegoBit in s.fxOf(Ev.NegoDone(ok, "note")))
        }
    }

    // MARK: AOA(USB)

    @Test
    fun `USB 분리는 망 전환과 같은 사건으로 다뤄 재협상한다`() {
        val usb = MirrorState(sess = Sess.Live("Mac"), path = Path.Live(Via.AOA))
        val step = usb.step(Ev.TransportDown())
        assertTrue(step.state.nego is Nego.Requested)
        assertEquals("USB 분리", (step.state.nego as Nego.Requested).reason)
        assertTrue(Fx.DropSession in step.fx)
        assertTrue(Fx.AcquireWifiLock in step.fx)
    }

    @Test
    fun `스트리밍 중이 아니던 AOA가 끊기면 그냥 끝난다`() {
        val usb = MirrorState(path = Path.Connecting(Via.AOA))
        val step = usb.step(Ev.TransportDown())
        assertEquals(Nego.Idle, step.state.nego)
        assertTrue(step.state.path is Path.Failed)
    }

    @Test
    fun `LAN에서 넘어온 AOA가 실패하면 무선으로 되돌아간다`() {
        val s = MirrorState(path = Path.Connecting(Via.AOA)).after(Ev.AoaFromLan)
        val step = s.step(Ev.TransportDown())
        assertTrue(step.state.nego is Nego.Requested)
    }

    @Test
    fun `LAN을 내리고 AOA로 넘어가는 중의 끊김은 사고가 아니다`() {
        val step = streaming().after(Ev.SwitchToAoa).step(Ev.TransportDown())
        assertTrue(step.fx.none { it is Fx.ScheduleBackoff })
        assertEquals(Nego.Idle, step.state.nego)
        assertEquals(ConnState.IDLE, step.state.conn)
    }

    // MARK: 전역 불변식

    @Test
    fun `어떤 사건 순열에서도 세션이 살아 있는데 재협상 중 DropSession이 나오지 않는다`() {
        val events = listOf(
            Ev.NegoNeeded("a"), Ev.TransportDown(), Ev.NetworkUp("b"),
            Ev.MacAnswered(true), Ev.TransportUp, Ev.BackoffElapsed,
        )
        // 6개 사건의 모든 3-순열을 스트리밍 상태에 적용한다.
        for (a in events) for (b in events) for (c in events) {
            var s = streaming()
            for (ev in listOf(a, b, c)) {
                val step = reduce(s, ev)
                if (s.negoBusy && s.sess is Sess.Live && step.state.sess is Sess.Live) {
                    assertFalse(
                        "세션 유지 전이인데 DropSession이 나왔다: $s --$ev-->",
                        Fx.DropSession in step.fx,
                    )
                }
                s = step.state
            }
        }
    }

    @Test
    fun `STREAMING은 언제나 살아 있는 세션과 살아 있는 경로를 뜻한다`() {
        val events = listOf(
            Ev.StartConnect(Via.LAN), Ev.TransportUp, Ev.SessionLive("M"), Ev.TransportDown(),
            Ev.NegoNeeded("x"), Ev.MacAnswered(true), Ev.PathSwapped, Ev.NegoDone(true, "ok"),
            Ev.NegoDone(false, "no"), Ev.NetworkUp("n"), Ev.BackoffElapsed, Ev.UserStop,
            Ev.Fatal("f"), Ev.SwitchToAoa, Ev.HostStop, Ev.AoaFromLan,
        )
        var s = idle()
        // 결정적 의사난수 순회 — 모든 도달 가능 상태에서 파생값이 모순되지 않아야 한다.
        var seed = 1L
        repeat(20_000) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val ev = events[((seed ushr 33).toInt() and 0x7FFFFFFF) % events.size]
            s = reduce(s, ev).state
            if (s.conn == ConnState.STREAMING) {
                assertTrue(s.sess is Sess.Live)
                assertTrue(s.path is Path.Live)
                assertTrue(s.intent is Intent.Running)
            }
            if (s.intent !is Intent.Running) {
                assertEquals("정지 래치 중에는 라운드가 없어야 한다", Nego.Idle, s.nego)
            }
        }
    }
}
