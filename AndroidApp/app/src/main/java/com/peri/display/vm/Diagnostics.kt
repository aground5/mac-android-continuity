package com.peri.display.vm

import com.peri.display.ble.PairPhase
import com.peri.display.transport.NetworkWatcher

/**
 * 진단 화면이 읽는 **읽기 전용 스냅샷** — Mac `Diagnostics.swift`와 같은 구성.
 *
 * 왜 스냅샷인가: 뷰가 `MirrorController`/`PairingCoordinator`의 내부를 직접 들여다보게 하면
 * 진단을 위해 캡슐화를 뚫는 구멍이 영구히 남는다. 대신 각 소유자가 "지금 이렇다"를 한 번에
 * 찍어 건네고, 뷰는 그리기만 한다.
 *
 * 세 스냅샷의 성격이 다르다는 점이 중요하다:
 *
 *  - [ConnDiagnostics]는 **연결 상태 기계**에서 나온다. [MirrorState]가 순수 타입이라
 *    뷰가 보는 값과 기계가 결정에 쓰는 값이 같다. 어긋날 수가 없다.
 *  - [PairDiagnostics]는 **페어링 상태 기계**에서 나온다. 위와 독립된 축이다 —
 *    BLE는 시그널링이고 미러링은 LAN/USB로 흐른다. 이 둘을 한 화면에 나란히 두는 게
 *    이 진단의 요점이다(예전엔 그걸 섞어 보여줘서 혼란이 났다).
 *  - [LinkDiagnostics]는 **기계가 모르는 원시 수치**다. 디코더·망·피드백.
 */

/** 연결 상태 기계의 축들. */
data class ConnDiagnostics(
    val conn: ConnState,
    val summary: String,
    val intent: String,
    val sess: String,
    val path: String,
    val nego: String,
    val queued: String?,
    val switch: String,
    val retries: Int,
    val via: String,
    /** 이 기기의 신원 지문 — Mac 로그의 `dev[…]`와 같은 값이라 두 로그를 묶는 키다. */
    val fingerprint: String,
    val negoRound: Int,
) {
    /**
     * 상태와 문구가 서로 맞는가. 어긋나면 기계나 실행기 중 한쪽이 틀린 것이다.
     *
     * Mac 진단의 같은 검사와 짝이다 — "기계는 송출 중이라는데 실물은 죽어 있는" 종류의
     * 어긋남을 사람이 눈으로 잡아내려면 대조할 기준이 화면에 있어야 한다.
     */
    val mismatch: String?
        get() = when {
            conn == ConnState.STREAMING && !sess.startsWith("Live") ->
                "송출 중인데 세션이 확립되지 않았다"
            conn == ConnState.STREAMING && !path.startsWith("Live") ->
                "송출 중인데 경로가 살아 있지 않다"
            conn == ConnState.IDLE && path.startsWith("Live") ->
                "유휴인데 경로가 살아 있다"
            else -> null
        }
}

/** 페어링 상태 기계의 축들. 연결과 **독립**이다. */
data class PairDiagnostics(
    val phase: PairPhase,
    val summary: String,
    /** PIN이 화면에 떠 있는가 — 사람을 기다리는 유일한 단계. */
    val showsPin: Boolean,
    /** 이 단계에 걸린 마감(ms). null = 마감 없음(머물러도 되는 상태). */
    val deadlineMs: Long?,
)

/** 기계가 모르는 원시 수치 — 디코더·망·피드백. */
data class LinkDiagnostics(
    val transport: NetworkWatcher.Transport,
    /** 셀룰러 주소는 후보에서 뒤로 밀린다. 몇 개가 그 대상인지. */
    val demotedAddresses: Int,
    val decoderUp: Boolean,
    val playoutBufferMs: Int,
    val renderOnArrival: Boolean,
    val queueDelayMs: Int,
    val pipeNetMs: Int,
    val pipeJbufMs: Int,
    val pipeDecMs: Int,
    val rttMs: Int,
    /** 마지막 Pong 이후 경과(ms). 생존 감지의 근거값. -1 = 아직 한 번도 못 받음. */
    val sincePongMs: Long,
    /** 이번 보고 구간의 수신/전체 패킷. 손실률의 원자료. */
    val fbReceived: Int,
    val fbSpan: Int,
) {
    val totalMs: Int get() = pipeNetMs + pipeJbufMs + pipeDecMs
    val hasLatency: Boolean get() = pipeNetMs > 0 || pipeDecMs > 0
    val lossPct: Int get() = if (fbSpan <= 0) 0 else (100 - fbReceived * 100 / fbSpan).coerceIn(0, 100)
}
