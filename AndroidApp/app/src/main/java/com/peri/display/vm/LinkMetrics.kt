package com.peri.display.vm

/**
 * **기계가 모르는 원시 수치** — 손실·왕복·클럭.
 *
 * 이 값들은 결정에 쓰이지 않는다. 손실은 갱신 요청의 방아쇠이고, 나머지는 Mac에게
 * 보고하거나 지연을 계측하는 데 쓰인다. 그래서 [MirrorState]에 넣지 않았고, 반대로
 * 오케스트레이터의 필드로 흩어 두지도 않았다 — 여섯 개의 정수가 서로 짝을 맞춰야
 * 의미가 생기는데(윈도우는 base·high·recv가 한 벌, NTP는 네 시각이 한 벌) 그 짝을
 * 필드 이름으로만 유지하면 언젠가 한쪽만 초기화된다.
 *
 * 전부 **관측 결과를 받아 적을 뿐** 아무것도 보내지 않는다. 보고를 누가 언제 보낼지는
 * keepalive의 몫이다.
 */
class LinkMetrics {

    // MARK: - 손실 감지 (packet_seq 갭)

    private var lastPacketSeq = -1

    /**
     * 마지막 영상 데이터그램이 **도착한** 시각(ms). 0 = 이 세대에서 아직 한 장도 못 받음.
     *
     * 왜 따로 두는가: 손실 감지도 갱신 요청도 전부 **도착한 패킷**이 방아쇠다. 한 장도
     * 안 오면 그 어느 것도 울리지 않는다 — 제어 채널은 멀쩡하니 keepalive도 조용하고,
     * 화면만 마지막 프레임에 얼어붙는다. 망 전환 뒤 실기기에서 정확히 그랬다.
     *
     * "안 온다"는 사실을 알아채려면 도착이 아니라 **시간**을 봐야 한다.
     */
    @Volatile
    var lastPacketAt = 0L
        private set

    /** 마지막 영상 데이터그램 이후 경과(ms). -1 = 아직 한 장도 못 받음. */
    val sinceVideoMs: Long
        get() = if (lastPacketAt == 0L) -1 else System.currentTimeMillis() - lastPacketAt

    /**
     * 패킷 하나가 도착했다. **갱신(키프레임)을 요청해야 하면 true.**
     *
     * 재전송이 아니라 forward 복구다 — 이미 지나간 패킷을 다시 달라고 하지 않고,
     * 지금부터 그릴 수 있는 프레임을 달라고 한다.
     */
    fun notePacket(seq: Int): Boolean {
        lastPacketAt = System.currentTimeMillis()
        var lost = false
        if (lastPacketSeq >= 0) {
            val expected = (lastPacketSeq + 1) and 0xFFFF
            if (seq != expected) {
                val diff = (seq - expected) and 0xFFFF   // 16b 모듈러: forward 갭만
                lost = diff in 1..1000
            }
        }
        lastPacketSeq = seq
        // 보고 윈도우 집계: base = 구간 첫 seq, high = 최신 seq, recv = 수신 수.
        if (fbBaseSeq < 0) fbBaseSeq = seq
        fbHighSeq = seq
        fbRecv++
        return lost
    }

    // MARK: - Transport-CC 보고 윈도우 (1초 주기 집계)

    private var fbBaseSeq = -1
    private var fbHighSeq = -1
    private var fbRecv = 0

    /** 한 번 보고할 만큼 모였는가. */
    val hasWindow: Boolean get() = fbBaseSeq >= 0 && fbHighSeq >= 0 && fbRecv > 0

    /** 진단 화면이 읽는 현재 윈도우(소비하지 않는다). */
    val received: Int get() = fbRecv
    val span: Int get() = if (fbBaseSeq < 0 || fbHighSeq < 0) 0 else fbHighSeq - fbBaseSeq + 1

    /** 보고에 실을 윈도우를 **꺼내고 비운다**. 모인 게 없으면 null. */
    fun takeWindow(): Window? {
        if (!hasWindow) return null
        val w = Window(base = fbBaseSeq, high = fbHighSeq, received = fbRecv)
        fbBaseSeq = -1; fbHighSeq = -1; fbRecv = 0
        return w
    }

    data class Window(val base: Int, val high: Int, val received: Int)

    /** 세션·세대가 갈렸다 — 순번 기준점이 통째로 새로 잡힌다. */
    fun reset() {
        lastPacketSeq = -1
        lastPacketAt = 0L
        fbBaseSeq = -1; fbHighSeq = -1; fbRecv = 0
    }

    // MARK: - 왕복과 클럭

    /** 마지막 Pong 도착 시각(ms). 0 = 아직 한 번도 못 받음. */
    @Volatile
    var lastPongAt = 0L
        private set

    /** 마지막 Ping→Pong 왕복(ms). 피드백에 실려 Mac 상세 화면에 표시된다. */
    @Volatile
    var rttMs = 0
        private set

    /** androidµs − macµs. 지연 계측용으로 디코더에 주입된다. */
    var clockOffsetUs = 0L
        private set
    var clockSynced = false
        private set

    private var bestNtpDeltaUs = Long.MAX_VALUE
    private var ntpResetAtMs = 0L

    /** 마지막 Pong 이후 경과(ms). -1 = 아직 한 번도 못 받음. */
    val sincePongMs: Long
        get() = if (lastPongAt == 0L) -1 else System.currentTimeMillis() - lastPongAt

    /** keepalive가 죽음을 판정할 때 쓴다 — 여기서는 시각만 관리한다. */
    fun markAlive() {
        lastPongAt = System.currentTimeMillis()
    }

    /**
     * Pong 하나. 네 시각으로 왕복과 클럭 오프셋을 갱신하고, **클럭이 갱신됐으면 true**.
     *
     *  - `t1` Android send · `t2` Mac recv · `t3` Mac send · `t4` Android recv
     *
     * 최소 왕복(delta)의 교환을 채택해 큐잉 오차를 줄이고, 10초마다 다시 추적한다(드리프트).
     * Mac이 시각을 안 실어 보내면(`t2`/`t3`가 0) 왕복만 갱신한다.
     */
    fun notePong(t1: Long, t2: Long, t3: Long, t4: Long): Boolean {
        markAlive()
        val rttUs = t4 - t1
        // 말이 안 되는 값은 버린다 — 클럭이 튀거나 Pong이 아주 늦게 온 경우다.
        if (rttUs in 0..10_000_000) rttMs = (rttUs / 1000).toInt()
        if (t2 <= 0 || t3 <= 0) return false

        val delta = (t4 - t1) - (t3 - t2)          // 순수 왕복(Mac 처리시간 제외)
        val theta = ((t2 - t1) + (t3 - t4)) / 2    // Mac − Android
        val now = System.currentTimeMillis()
        if (now - ntpResetAtMs > 10_000) { bestNtpDeltaUs = Long.MAX_VALUE; ntpResetAtMs = now }
        if (delta !in 0..bestNtpDeltaUs) return false   // 더 낮은 왕복 = 더 정확한 오프셋

        bestNtpDeltaUs = delta
        clockOffsetUs = -theta
        clockSynced = true
        return true
    }
}
