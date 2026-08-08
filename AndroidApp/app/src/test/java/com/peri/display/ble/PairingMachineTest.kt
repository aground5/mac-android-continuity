package com.peri.display.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BLE 페어링 기계 v2 — Android(응답자) 쪽.
 *
 * ★ [정상_경로는_순서대로만_진행된다]는 Mac `MachineTests/PairingTests.swift`의
 * `testPairingHappyPath`와 **거울상**이다. 두 피어가 보조를 맞춰야 하는 흐름이라 한쪽만
 * 맞는 건 아무 의미가 없다 — 구현이 갈라지면 어느 한쪽 테스트가 깨지고, 실기기에서
 * 어긋남을 발견하기 전에 여기서 걸린다.
 *
 * v1에서 이 파일이 지키던 성질 중 넷(신뢰 조합 대응표, SAS 다운그레이드 금지, 양쪽 사람
 * 확인, 방식 협상)은 **문제 자체가 사라져서** 없어졌다. 그 넷은 전부 "이번 라운드에 사람을
 * 부를까, 어떻게 부를까"를 두 피어가 합의하는 데서 나온 성질이고, v2는 모든 라운드가 PIN을
 * 거치므로 합의할 것이 없다.
 */
class PairingMachineTest {

    private fun PairState.after(vararg evs: PairEv): PairState =
        evs.fold(this) { s, e -> reduce(s, e).state }

    private fun PairState.step(ev: PairEv): PairStep = reduce(this, ev)

    /** HELLO를 받아 OFFER를 보낸 직후 — 화면에 PIN이 떠 있다. */
    private fun offered(): PairState = PairState().after(PairEv.Hello)

    // ── 정상 경로 ────────────────────────────────────────────────────────────

    @Test
    fun `정상 경로는 순서대로만 진행된다`() {
        val hello = PairState().step(PairEv.Hello)
        assertEquals(PairPhase.Offered, hello.state.phase)
        assertTrue("PIN을 뽑아 띄우고 OFFER를 보낸다", PairFx.SendOffer in hello.fx)
        assertTrue("화면에 PIN이 떠야 한다", hello.state.showsPin)

        val pake1 = hello.state.step(PairEv.Pake1)
        assertEquals(PairPhase.Proving, pake1.state.phase)
        assertTrue(PairFx.SendPake2 in pake1.fx)
        assertTrue("사람은 이미 옮겨 적었다 — PIN을 지운다", PairFx.HidePin in pake1.fx)
        assertFalse(pake1.state.showsPin)
        assertFalse("아직 아무것도 증명되지 않았다", PairFx.PinPeer in pake1.fx)

        val pake3 = pake1.state.step(PairEv.Pake3Verified)
        assertEquals(PairPhase.Identifying, pake3.state.phase)
        assertFalse("Mac 신원을 보기 전에는 핀하지 않는다", PairFx.PinPeer in pake3.fx)

        val ident = pake3.state.step(PairEv.PeerIdentity)
        assertEquals(PairPhase.AwaitingParams, ident.state.phase)
        assertTrue("Mac 인증서를 핀한다", PairFx.PinPeer in ident.fx)
        assertTrue("우리 인증서를 봉인해 보낸다", PairFx.SendIdentity in ident.fx)
    }

    /** 힌트가 아는 Mac을 짚어 STS1을 보낸 직후. */
    private fun stsProposing(): PairState = PairState().after(PairEv.HelloSts)

    @Test
    fun `재연결 경로에는 PIN이 없다`() {
        // Mac `PairingTests.testStsReconnectHasNoPin`의 거울상이다.
        val hello = PairState().step(PairEv.HelloSts)
        assertEquals(PairPhase.StsProposing, hello.state.phase)
        assertTrue("임시키를 만들어 STS1을 보낸다", PairFx.SendStsHello in hello.fx)
        assertFalse("★ 화면에 PIN이 뜨면 안 된다", hello.state.showsPin)
        assertFalse("PIN을 뽑지 않는다", PairFx.SendOffer in hello.fx)

        val ok = hello.state.step(PairEv.StsResponseOk)
        assertEquals(PairPhase.AwaitingParams, ok.state.phase)
        assertTrue("우리 서명을 봉인해 보낸다", PairFx.SendStsConfirm in ok.fx)
        // ★ 재연결은 신뢰를 새로 세우지 않는다 — 인증서가 오지도 않는다.
        assertFalse("재연결이 핀을 만들면 안 된다", PairFx.PinPeer in ok.fx)
        assertFalse("인증서를 보내지 않는다", PairFx.SendIdentity in ok.fx)
    }

    // ── 라운드가 끝난 뒤 ─────────────────────────────────────────────────────
    //
    // 이 기계는 **응답자**라 스스로 라운드를 시작하지 못한다. 그래서 "끝난 뒤 어떤 상태로
    // 남는가"가 곧 "다시 붙을 수 있는가"이고, 그걸 틀리면 증상이 실기기에서만 보인다.

    /** 후보 교환까지 마치고 Mac이 BLE를 놓은 뒤 — 정상 성공 경로의 종착점. */
    private fun roundFinished(): PairState =
        PairState().after(PairEv.HelloSts, PairEv.StsResponseOk, PairEv.CandidatesSent,
                          PairEv.PunchStart, PairEv.LinkLost)

    @Test
    fun `끝난 라운드는 재료를 놓는다`() {
        val done = PairState().after(PairEv.HelloSts, PairEv.StsResponseOk,
                                     PairEv.CandidatesSent, PairEv.PunchStart)
        assertEquals(PairPhase.Done, done.phase)
        val lost = done.step(PairEv.LinkLost)
        assertEquals("결과 문구는 남는다", PairPhase.Done, lost.state.phase)
        assertTrue("링크가 끊겼으면 라운드 비밀을 들고 있을 이유가 없다",
                   PairFx.EndRound in lost.fx)
    }

    /**
     * ★ **Mac이 '연결 해제'한 뒤 다시 걸어오는 경로.** 실기기에서 여기서 막혔다.
     *
     * 정상 성공은 `AwaitingPunch → (LinkLost) → Done`으로 끝나는데, 그 뒤로는 링크가 이미
     * 없어 `LinkLost`가 다시 오지 않는다. 예전엔 `Done`에서 나올 길이 사용자의 광고 토글
     * 하나뿐이라 **Done이 빠져나올 수 없는 방**이었다. Mac이 다시 걸면 힌트는 알아보는데
     * (`★ 재연결 — … (PIN 없음)`) 바로 다음 줄이 `단계 밖 HELLO(STS) 무시`였고, Mac 쪽에는
     * `offering 단계 응답 없음`으로만 보였다.
     */
    @Test
    fun `끝난 라운드 뒤에도 새 HELLO를 받는다`() {
        for (start in listOf(roundFinished(),
                             PairState().after(PairEv.HelloSts, PairEv.Failed("아무 이유")))) {
            val sts = start.step(PairEv.HelloSts)
            assertEquals("${start.phase}에서 재연결을 못 받았다",
                         PairPhase.StsProposing, sts.state.phase)
            assertTrue(PairFx.SendStsHello in sts.fx)

            val pake = start.step(PairEv.Hello)
            assertEquals("${start.phase}에서 첫 페어링을 못 받았다",
                         PairPhase.Offered, pake.state.phase)
            assertTrue(PairFx.SendOffer in pake.fx)
        }
    }

    @Test
    fun `라운드 중에는 새 HELLO가 가로채지 못한다`() {
        // 위 완화가 "언제나 받는다"가 되면 안 된다 — 사람이 PIN을 옮겨 적는 중에 HELLO가
        // 오면 그 화면을 날려버리고, 그건 그대로 강등 경로가 된다.
        for (mid in listOf(offered(), stsProposing(),
                           PairState().after(PairEv.Hello, PairEv.Pake1))) {
            assertEquals("${mid.phase}가 HELLO에 가로채였다",
                         mid.phase, mid.step(PairEv.Hello).state.phase)
            assertEquals("${mid.phase}가 HELLO(STS)에 가로채였다",
                         mid.phase, mid.step(PairEv.HelloSts).state.phase)
        }
    }

    @Test
    fun `두 경로는 서로를 대신하지 못한다`() {
        // PAKE 라운드에 STS2가 끼어들어도, 재연결 라운드에 PAKE 프레임이 와도 전진하지 않는다.
        assertEquals(
            "PIN 라운드가 STS로 새어 나갔다",
            PairPhase.Offered, offered().step(PairEv.StsResponseOk).state.phase,
        )
        assertEquals(
            "재연결 라운드가 PAKE로 강등됐다",
            PairPhase.StsProposing, stsProposing().step(PairEv.Pake1).state.phase,
        )
        // HELLO는 라운드의 시작이다 — 이미 시작한 라운드를 다시 열 수 없다.
        assertEquals(
            "진행 중인 재연결에 HELLO가 끼어들었다",
            PairPhase.StsProposing, stsProposing().step(PairEv.Hello).state.phase,
        )
    }

    @Test
    fun `후보 교환과 펀칭`() {
        val ready = offered().after(PairEv.Pake1, PairEv.Pake3Verified, PairEv.PeerIdentity)

        val params = ready.step(PairEv.ServerParams)
        assertTrue(PairFx.SendCandidates in params.fx)

        val sent = params.state.step(PairEv.CandidatesSent)
        assertEquals(PairPhase.AwaitingPunch, sent.state.phase)

        val punch = sent.state.step(PairEv.PunchStart)
        assertEquals(PairPhase.Done, punch.state.phase)
        assertTrue(PairFx.BeginPunch in punch.fx)
    }

    @Test
    fun `개시 신호가 후보 전송보다 먼저 와도 받는다`() {
        // 우리 쪽 STUN 왕복이 더 길면 이 순서가 된다. 놓치면 펀칭 자체가 안 일어난다.
        val waiting = offered()
            .after(PairEv.Pake1, PairEv.Pake3Verified, PairEv.PeerIdentity, PairEv.ServerParams)
        val punch = waiting.step(PairEv.PunchStart)
        assertEquals(PairPhase.Done, punch.state.phase)
        assertTrue(PairFx.BeginPunch in punch.fx)
    }

    // ── 안전 성질 ────────────────────────────────────────────────────────────

    /**
     * ★ 핀이 나오는 경로는 **하나뿐**이다 — PAKE를 통과하고 Mac 신원을 받은 뒤.
     *
     * 다른 경로가 생기면 그게 곧 "PIN 없이 신원이 고정되는" 구멍이다. v1에서 정확히 그
     * 종류의 구멍(한쪽 확인만으로 핀)이 실제 공격이 됐다.
     */
    @Test
    fun `PAKE 전에는 어떤 사건으로도 핀하지 않는다`() {
        val everyEvent = listOf(
            PairEv.Advertise, PairEv.Hello, PairEv.Pake1, PairEv.Pake3Verified,
            PairEv.PeerIdentity, PairEv.ServerParams, PairEv.CandidatesSent,
            PairEv.PunchStart, PairEv.LinkLost, PairEv.DeadlineElapsed,
            PairEv.Failed("x"), PairEv.HelloSts, PairEv.StsResponseOk,
        )
        val before = listOf(PairState(), offered(), offered().after(PairEv.Pake1))
        for (s in before) {
            for (ev in everyEvent) {
                assertFalse(
                    "PAKE 전 단계(${s.phase})가 $ev 로 신원을 핀했다",
                    PairFx.PinPeer in reduce(s, ev).fx,
                )
            }
        }

        // 그리고 Identifying에서도 Mac 신원이 실제로 와야만 핀한다.
        val identifying = offered().after(PairEv.Pake1, PairEv.Pake3Verified)
        for (ev in everyEvent.filter { it != PairEv.PeerIdentity }) {
            assertFalse(
                "신원 교환 단계가 $ev 로 핀했다",
                PairFx.PinPeer in reduce(identifying, ev).fx,
            )
        }
    }

    /**
     * PIN이 화면에 떠 있는 단계는 **하나뿐**이고, 라운드가 어떻게 끝나든 지워진다.
     *
     * 남아 있으면 다음 라운드의 새 PIN과 헷갈리고, 그게 곧 사용자가 옛 숫자를 옮겨 적는
     * 실패다. 화면에 남은 값이 이미 쓸모없어졌다는 걸 코드가 보장해야 한다.
     */
    @Test
    fun `라운드가 끝나면 PIN이 지워진다`() {
        val shown = offered()
        assertTrue(shown.showsPin)

        for (ev in listOf(PairEv.LinkLost, PairEv.DeadlineElapsed, PairEv.Failed("x"))) {
            val end = shown.step(ev)
            assertFalse("$ev 뒤에도 PIN이 남아 있다", end.state.showsPin)
            assertTrue("$ev 가 PIN을 지우지 않았다", PairFx.HidePin in end.fx)
        }
    }

    /**
     * ★ 라운드 정리는 **라운드가 끝날 때만** 나온다 — 실기기에서 났던 버그의 회귀 검사.
     *
     * PAKE1을 받으면 화면의 PIN을 지우는 게 맞다(사람은 이미 옮겨 적었다). 그런데 그때
     * PAKE 상태까지 같이 지우면 step4가 조용히 무시되고, 세션이 안 선 채로 신원 프레임이
     * 도착해 "Mac 신원을 열지 못했습니다"로 죽는다. 화면 정리와 키 정리는 다른 일이다.
     */
    @Test
    fun `라운드 정리는 종료 단계에서만 나온다`() {
        val everyEvent = listOf(
            PairEv.Advertise, PairEv.Hello, PairEv.Pake1, PairEv.Pake3Verified,
            PairEv.PeerIdentity, PairEv.ServerParams, PairEv.CandidatesSent,
            PairEv.PunchStart, PairEv.LinkLost, PairEv.DeadlineElapsed, PairEv.Failed("x"),
            PairEv.HelloSts, PairEv.StsResponseOk,
        )
        val liveStates = listOf(
            PairState(), offered(), offered().after(PairEv.Pake1),
            offered().after(PairEv.Pake1, PairEv.Pake3Verified),
            offered().after(PairEv.Pake1, PairEv.Pake3Verified, PairEv.PeerIdentity),
            stsProposing(), stsProposing().after(PairEv.StsResponseOk),
        )
        for (s in liveStates) {
            for (ev in everyEvent) {
                val step = reduce(s, ev)
                if (PairFx.EndRound !in step.fx) continue
                val landed = step.state.phase
                assertFalse(
                    "${s.phase} + $ev 가 라운드를 안 끝내면서 키를 지웠다 (→ $landed)",
                    landed.inRound,
                )
            }
        }

        // 그리고 PAKE1은 화면만 정리한다 — step4가 아직 남아 있다.
        val pake1 = offered().step(PairEv.Pake1)
        assertTrue(PairFx.HidePin in pake1.fx)
        assertFalse("PAKE 중간에 키를 지웠다", PairFx.EndRound in pake1.fx)
    }

    @Test
    fun `단계 밖 프레임은 전진시키지 않는다`() {
        val cases = listOf(
            PairState() to PairEv.Pake1,
            PairState() to PairEv.Pake3Verified,
            PairState() to PairEv.PeerIdentity,
            offered() to PairEv.Pake3Verified,
            offered() to PairEv.PeerIdentity,
            offered() to PairEv.Hello,
        )
        for ((from, ev) in cases) {
            assertEquals(
                "단계 밖 $ev 가 ${from.phase} 를 전진시켰다",
                from.phase, reduce(from, ev).state.phase,
            )
        }
    }

    // ── 마감 ────────────────────────────────────────────────────────────────

    /** 모든 대기 단계에 마감이 있다 — v1의 무한 대기가 여기서 걸린다. */
    @Test
    fun `모든 대기 단계에 마감이 있다`() {
        val waits = listOf(
            PairPhase.Offered, PairPhase.Proving, PairPhase.Identifying,
            PairPhase.AwaitingParams, PairPhase.AwaitingPunch, PairPhase.StsProposing,
        )
        for (p in waits) {
            assertTrue("마감 없는 대기 단계: $p", p.deadlineMs != null)
        }
        // 단계를 옮길 때마다 마감이 **자동으로** 다시 걸려야 한다.
        assertTrue(
            "단계 전이에 마감 무장이 없다",
            PairState().step(PairEv.Hello).fx.any { it is PairFx.ArmDeadline },
        )
    }

    @Test
    fun `사람을 기다리는 단계가 제일 길다`() {
        // 사람이 기기를 보고 Mac에 옮겨 적는 시간이다. 다른 마감보다 짧으면 정상 페어링이
        // 시간 초과로 죽는다.
        val human = PairPhase.Offered.deadlineMs!!
        for (p in listOf(PairPhase.Proving, PairPhase.Identifying,
                         PairPhase.AwaitingParams, PairPhase.AwaitingPunch)) {
            assertTrue("$p 마감이 사람 대기보다 길다", p.deadlineMs!! < human)
        }
    }

    /**
     * 실패한 라운드는 **이어지지** 않는다 — 중간 사건으로 되살아나면 검증을 건너뛴 채
     * 전진하게 된다. 다만 **새 라운드가 시작되는 것은 부활이 아니다**(아래 참조).
     */
    @Test
    fun `실패는 되살아나지 않는다`() {
        val dead = PairState().after(PairEv.Failed("끝"))
        for (ev in listOf(PairEv.Pake1, PairEv.Pake3Verified, PairEv.PeerIdentity,
                          PairEv.StsResponseOk, PairEv.ServerParams, PairEv.PunchStart)) {
            assertTrue(
                "실패 상태가 $ev 로 되살아났다",
                reduce(dead, ev).state.phase is PairPhase.Failed,
            )
        }
        // 새 라운드는 광고 토글로도, **Mac이 다시 거는 것으로도** 시작한다.
        // 후자를 막아 두었더니 '연결 해제' 뒤 재연결이 영영 안 됐다.
        assertEquals(PairPhase.Idle, dead.step(PairEv.Advertise).state.phase)
        assertEquals(PairPhase.Offered, dead.step(PairEv.Hello).state.phase)
        assertEquals(PairPhase.StsProposing, dead.step(PairEv.HelloSts).state.phase)
    }
}
