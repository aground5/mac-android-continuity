package com.peri.display.vm

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 전이 **완전성** 검사 — 눈으로 표를 그리는 대신 도달 가능한 상태를 전수로 훑는다.
 *
 * 묻는 질문은 하나다: **이 상태에서 아무 일도 일어나지 않으면 영원히 여기 머무는가?**
 *
 * 외부 사건(TransportUp, SessionLive, MacAnswered, NetworkUp…)은 영영 안 올 수 있다. Mac이
 * 잠들거나, BLE 범위를 벗어나거나, TCP는 붙었는데 상대가 ServerHello를 안 보내거나. 그래서
 * "기다리는 상태"에는 반드시 **반드시 도착하는 사건**(타이머)이 하나씩 걸려 있어야 한다.
 * 하나라도 빠지면 그 상태가 곧 영구 정지점이고, 실기기에서는 "연결 중…"에서 멎은 화면으로 나타난다.
 */
class MirrorLivenessTest {

    /** 기계 밖에서 **반드시** 도착하는 사건들 — 각 타이머가 어느 상태에서 무장되는가. */
    private fun timersArmedIn(s: MirrorState): List<String> = buildList {
        // MirrorController.armNegoWatchdog — negoRequest / MacAnswered 진입 시 무장, NegoDone에서 해제.
        if (s.nego !is Nego.Idle) add("재협상 워치독 ${NEGO_TIMEOUT_MS / 1000}s → NegoDone(false)")
        // MirrorController.scheduleBackoff — ScheduleBackoff에서 무장, CancelBackoff에서 해제.
        if (s.path is Path.Backoff) add("백오프 타이머 → BackoffElapsed")
        // MirrorController.armHandshakeWatchdog — 세션이 서기를 기다리는 동안.
        if (s.sess is Sess.Establishing) add("핸드셰이크 워치독 ${HANDSHAKE_TIMEOUT_MS / 1000}s → HandshakeTimeout")
        // MirrorController.startKeepalive — 3초간 Pong이 없으면 bearer.disconnect() → TransportDown.
        if (s.sess is Sess.Live && s.path is Path.Live) add("keepalive 3s → TransportDown")
        // LanClient.connect의 5s / AoaClient open — 실패하면 onDisconnected로 떨어진다.
        if (s.path is Path.Connecting) add("connect 타임아웃 → TransportDown")
    }

    /**
     * 여기 머물러도 되는 상태인가 — "정지가 곧 정답"인 경우.
     *
     * 사용자가 끊었거나 치명 거부로 멈춘 상태, 그리고 재시도 수단을 다 쓴 뒤의 정지는
     * 기다리는 게 맞다. 그 외에는 전부 무언가를 기다리는 중이므로 마감이 있어야 한다.
     */
    private fun mayRestIndefinitely(s: MirrorState): Boolean = when {
        s.intent !is Intent.Running -> true                 // 사용자/치명 래치
        s.conn == ConnState.IDLE -> true                    // 아무것도 안 하는 중
        s.path is Path.Failed && s.nego is Nego.Idle -> true // 수단 소진 — 사용자나 망 변화를 기다린다
        else -> false
    }

    /** 도달 가능한 상태 전부. */
    private fun reachable(): Set<MirrorState> {
        val events = listOf(
            Ev.StartConnect(Via.LAN), Ev.StartConnect(Via.AOA), Ev.AoaFromLan,
            Ev.TransportUp, Ev.TransportDown(null, true), Ev.TransportDown(null, false),
            Ev.SessionLive("Mac"), Ev.HandshakeTimeout, Ev.Fatal("거부"),
            Ev.UserStop, Ev.HostStop, Ev.SwitchToAoa,
            Ev.NegoNeeded("전환"), Ev.NetworkUp("전환"),
            Ev.MacAnswered(true), Ev.MacAnswered(false),
            Ev.NegoDone(true, "ok"), Ev.NegoDone(false, "no"),
            Ev.PathSwapped, Ev.BackoffElapsed,
        )
        val seen = LinkedHashSet<MirrorState>()
        val queue = ArrayDeque<MirrorState>()
        // 사유 문자열이 상태에 섞이면 상태 공간이 무한해진다 — 비교 전에 정규화한다.
        fun norm(s: MirrorState) = s.copy(
            nego = when (val n = s.nego) {
                is Nego.Requested -> Nego.Requested("·")
                is Nego.Punching -> Nego.Punching("·", n.seamless, n.announced)
                Nego.Idle -> Nego.Idle
            },
            queued = s.queued?.let { "·" },
            sess = (s.sess as? Sess.Live)?.let { Sess.Live("·") } ?: s.sess,
            path = when (val p = s.path) {
                is Path.Failed -> Path.Failed("·")
                else -> p
            },
            intent = (s.intent as? Intent.StoppedFatal)?.let { Intent.StoppedFatal("·") } ?: s.intent,
        )
        queue += norm(MirrorState())
        seen += norm(MirrorState())
        while (queue.isNotEmpty()) {
            val s = queue.removeFirst()
            for (ev in events) {
                val next = norm(reduce(s, ev).state)
                if (seen.add(next)) queue += next
            }
        }
        return seen
    }

    @Test
    fun `기다리는 모든 상태에는 마감이 있다`() {
        val stuck = reachable().filter { !mayRestIndefinitely(it) && timersArmedIn(it).isEmpty() }
        assertTrue(
            "마감 없이 외부 사건만 기다리는 상태가 있다 — 영구 정지점이다:\n" +
                stuck.joinToString("\n") { "  · conn=${it.conn} sess=${it.sess} path=${it.path} nego=${it.nego}" },
            stuck.isEmpty(),
        )
    }

    @Test
    fun `모든 상태는 유휴로 돌아갈 수 있다`() {
        // 사용자가 '끊기'를 누르면 어떤 상태에서든 정리되어야 한다. 하나라도 예외가 있으면
        // 그 상태에 갇힌 사용자는 앱을 강제 종료하는 수밖에 없다.
        val bad = reachable().filter { reduce(it, Ev.UserStop).state.conn != ConnState.IDLE }
        assertTrue("'끊기'로 정리되지 않는 상태: $bad", bad.isEmpty())
    }

    @Test
    fun `치명 거부는 어떤 상태에서도 즉시 멈춘다`() {
        val bad = reachable().filter {
            val after = reduce(it, Ev.Fatal("거부")).state
            after.conn != ConnState.ERROR || after.negoBusy || after.sess !is Sess.None
        }
        assertTrue("치명 거부가 완전히 멈추지 못하는 상태: $bad", bad.isEmpty())
    }

    @Test
    fun `타임아웃 값들이 서로 모순되지 않는다`() {
        // 재협상 워치독은 그 안에서 도는 펀칭(제어 채널 확보 12s)보다 길어야 한다.
        // 짧으면 펀칭이 성공하기도 전에 라운드를 실패로 접고, 뒤늦게 도착한 성공이
        // 이미 정리된 상태 위에 얹힌다.
        assertTrue("재협상 워치독($NEGO_TIMEOUT_MS)이 펀칭 상한(12s)보다 짧다", NEGO_TIMEOUT_MS > 12_000)
        // 핸드셰이크 워치독은 keepalive 감지(3s)보다 길어야 한다 — 세션이 서기 전에는
        // keepalive가 없으므로 이쪽이 유일한 마감이고, 너무 짧으면 느린 망에서 오탐한다.
        assertTrue("핸드셰이크 워치독이 너무 짧다", HANDSHAKE_TIMEOUT_MS in 5_000..15_000)
        assertTrue("핸드셰이크 워치독이 재협상 워치독보다 길다", HANDSHAKE_TIMEOUT_MS < NEGO_TIMEOUT_MS)
    }

    /**
     * **도달하면 안 되는 조합**은 도달할 수 없다.
     *
     * 상태 개수 자체는 축의 곱이라 임의 임계값을 두는 게 의미가 없다. 진짜로 확인할 것은
     * "이 조합은 말이 안 된다"는 규칙들이 전부 지켜지는가다. 하나라도 뚫리면, 그 조합을
     * 전제로 짠 다른 갈래(특히 어떤 타이머가 무장돼 있는지)가 통째로 틀리게 된다.
     */
    @Test
    fun `모순된 조합은 도달할 수 없다`() {
        val rules = listOf<Pair<String, (MirrorState) -> Boolean>>(
            "정지 래치 중에는 재협상이 돌 수 없다" to
                { it.intent is Intent.Running || it.nego is Nego.Idle },
            "정지 래치 중에는 세션이 살아 있을 수 없다" to
                { it.intent is Intent.Running || it.sess is Sess.None },
            "정지 래치 중에는 전송이 살아 있을 수 없다" to
                { it.intent is Intent.Running || it.path !is Path.Live },
            // 이게 깨지면 핸드셰이크 워치독이 마감을 보장한다는 전제가 무너진다 —
            // 전송 없이 Establishing이면 CloseTransport를 해도 아무 일도 안 일어난다.
            "핸드셰이크 대기는 전송이 살아 있을 때만 가능하다" to
                { it.sess !is Sess.Establishing || it.path is Path.Live },
            "AOA로 전환하는 중에 AOA가 이미 붙어 있을 수는 없다" to
                { it.switch != Switch.ToAoa || (it.path as? Path.Live)?.via != Via.AOA },
            "예약된 재협상은 라운드가 돌고 있을 때만 존재한다" to
                { it.queued == null || it.negoBusy },
        )
        val all = reachable()
        println("도달 가능한 상태: ${all.size}개")
        for ((name, holds) in rules) {
            val bad = all.filter { !holds(it) }
            assertTrue(
                "위반: $name\n" + bad.take(5).joinToString("\n") {
                    "  · sess=${it.sess} path=${it.path} nego=${it.nego} switch=${it.switch} intent=${it.intent}"
                },
                bad.isEmpty(),
            )
        }
    }

    @Test
    fun `전이표 출력`() {
        // 실패하지 않는다 — 검토용 표를 남긴다. `gradlew test --info`로 볼 수 있다.
        val states = reachable().sortedBy { it.conn.ordinal }
        println("=== 도달 가능 상태와 마감 ===")
        for (s in states) {
            val timers = timersArmedIn(s)
            val mark = when {
                mayRestIndefinitely(s) -> "정지 OK"
                timers.isEmpty() -> "★ 마감 없음"
                else -> timers.joinToString(", ")
            }
            println("${s.conn}\tsess=${s.sess} path=${s.path} nego=${s.nego} → $mark")
        }
    }
}
