package com.peri.display.ble

/**
 * BLE 페어링의 **순수 상태 기계** — Android(응답자) 쪽.
 *
 * Mac의 `PairingMachine.swift`와 **같은 어휘**를 쓴다. 두 쪽이 거울상이라 어휘가 갈리면
 * 대응을 사람이 눈으로 맞춰야 하고, 그 어긋남이 이 코드베이스의 오래된 버그였다.
 *
 * ## v2에서 무엇이 사라졌나
 *
 * `PairMode`(SAS/OTP/REAUTH)와 `VerifyMethod`가 통째로 없다. 그 두 축은 "이번 라운드에
 * 사람을 부를 것인가, 부른다면 어떤 방식으로"를 **양쪽이 합의**하기 위해 있었다. 합의가
 * 어긋나면 두 기기가 서로 다른 프레임을 기다리며 멈췄다 — Mac에서 페어링을 해제하면 Mac은
 * "SAS 필요"로 우리는 "재인증"으로 갈라지던 그 버그다.
 *
 * v2는 그 질문 자체를 없앴다. **모든 페어링 라운드가 PIN을 거친다.** 협상할 게 없으니
 * 어긋날 것도 없고, 모드를 실어 나르던 프레임(VERIFY_REQUIRED·SERVER_AUTH·CLIENT_AUTH·
 * CLIENT_CONFIRM·CONFIRM·ABORT)도 전부 사라졌다.
 *
 * 더 중요한 건 안전성이다. 옛 SAS는 코드가 **키 교환을 전혀 묶지 않아서**, 중간자가 양쪽
 * nonce·인증서를 그대로 흘리며 x25519와 ML-KEM만 갈아끼우면 두 기기에 같은 6자리가 떴다.
 * OTP도 봉인해 보내는 값이라 중간자가 풀었다 다시 봉하면 그만이었다. v2는 PAKE라 그 구멍이
 * 원리적으로 없다 — **세션 키 자체가 PIN에서 나온다.**
 *
 * ## 실패는 끊김으로 말한다
 *
 * PIN이 틀리면 그걸 아는 건 **Mac**이다(우리가 보낸 PAKE2를 검증하지 못한다). 그 시점에
 * Mac에겐 세션 키가 없어서 봉인된 사유를 보낼 수단이 없고, 평문 사유를 허용하면 아무나
 * 남의 라운드를 닫을 수 있다. 그래서 Mac은 그냥 링크를 끊고, 우리는 [PairEv.LinkLost]
 * 또는 마감으로 라운드를 닫는다. 다시 하려면 처음부터고, 그러면 **새 PIN**이 뽑힌다.
 */

/** 프로토콜 진행 단계. **대기 단계에는 전부 마감이 있다** — [deadlineMs]. */
sealed interface PairPhase {
    /** 광고 중. 사건을 기다리는 게 정상인 상태. */
    data object Idle : PairPhase
    /** HELLO를 받아 OFFER를 보냈다. 화면에 PIN이 떠 있고 PAKE1을 기다린다. */
    data object Offered : PairPhase
    /**
     * HELLO의 TOTP가 아는 Mac을 짚었다 → STS1을 보내고 STS2를 기다린다.
     *
     * **PIN이 없는 유일한 경로다.** 여기까지 온 이유는 상대가 링크 키를 증명했기 때문이고,
     * 그 링크 키는 예전에 사람이 PIN을 옮겨 적어서 생긴 값이다.
     */
    data object StsProposing : PairPhase
    /** PAKE2를 보냈고 PAKE3를 기다린다. 여기서 Mac이 PIN을 알았는지 갈린다. */
    data object Proving : PairPhase
    /** PAKE가 끝나 채널이 봉인됐다. Mac 신원을 기다린다. */
    data object Identifying : PairPhase
    /** 서로 핀했다. Mac의 후보를 기다린다. */
    data object AwaitingParams : PairPhase
    /** 내 후보를 보냈고 펀칭 개시 신호를 기다린다. */
    data object AwaitingPunch : PairPhase
    data object Done : PairPhase
    data class Failed(val reason: String) : PairPhase
}

/**
 * 이 단계에 머무를 수 있는 상한(ms). null = 마감 없음.
 *
 * ★ 이 함수가 존재하는 이유는 옛 버그다. 예전엔 **어떤 대기에도 마감이 없었다.** 모드가
 * 어긋나면 양쪽이 서로 다른 프레임을 기다리며 영원히 앉아 있었고, 사용자가 취소하기 전까지
 * 아무 일도 일어나지 않았다.
 */
val PairPhase.deadlineMs: Long?
    get() = when (this) {
        PairPhase.Idle, PairPhase.Done, is PairPhase.Failed -> null
        // 사람이 화면을 보고 Mac에 옮겨 적는 시간 — 넉넉하되 무한은 아니다.
        PairPhase.Offered -> 120_000
        // 사람이 낄 일이 없다 — 순수 왕복이라 짧게 잡는다.
        PairPhase.StsProposing -> 15_000
        // Argon2id가 양쪽에서 한 번씩 도므로(64MiB) 왕복보다 계산이 오래 걸린다.
        PairPhase.Proving -> 15_000
        PairPhase.Identifying -> 10_000
        PairPhase.AwaitingParams -> 20_000
        // Mac의 펀칭 창(13s)보다 길게. 개시 신호를 놓치면 이 마감이 라운드를 닫는다.
        PairPhase.AwaitingPunch -> 20_000
    }

/**
 * 지금 **라운드 안**인가.
 *
 * 라운드는 GATT 링크 하나 위에서만 산다. `Idle`은 아직 시작 안 했고, `Done`·`Failed`는 이미
 * 끝났다 — 셋 다 "새 HELLO를 받아도 되는" 상태다. 그 사이의 단계들만 가로채면 안 된다
 * (사람이 PIN을 옮겨 적는 중일 수 있다).
 *
 * ★ 이 구분이 없어서 실제로 물렸다. Mac이 '연결 해제'를 누르면 라운드가 `Done`으로 끝나고
 * BLE 링크도 끊기는데, 그 뒤로 `Done`에서 나올 길이 `Advertise`(사용자가 광고 토글) 하나뿐이라
 * **Mac이 다시 걸어도 HELLO가 전부 "단계 밖"으로 버려졌다.** 로그에는 힌트를 알아본
 * `★ 재연결 — … (PIN 없음)` 바로 다음 줄에 `단계 밖 HELLO(STS) 무시`가 찍혔고, Mac 쪽은
 * `offering 단계 응답 없음`으로만 보였다.
 */
val PairPhase.inRound: Boolean
    get() = when (this) {
        PairPhase.Idle, PairPhase.Done, is PairPhase.Failed -> false
        else -> true
    }

data class PairState(
    val phase: PairPhase = PairPhase.Idle,
) {
    /** UI 한 줄 — **계산값**이다. 예전엔 문자열을 9곳에서 직접 대입했다. */
    val summary: String
        get() = when (phase) {
            PairPhase.Idle -> "대기"
            PairPhase.Offered -> "이 PIN을 Mac에 입력하세요 (기회 1회)"
            PairPhase.StsProposing -> "Mac 확인 중…"
            PairPhase.Proving -> "PIN 확인 중…"
            PairPhase.Identifying -> "신원 교환 중…"
            PairPhase.AwaitingParams -> "연결 경로 교환 중…"
            PairPhase.AwaitingPunch -> "경로 연결 중…"
            // ★ "연결됨"이라고 하면 안 된다. 이 단계가 뜻하는 건 **페어링 라운드가 끝났다**는
            // 것뿐이고, 실제 연결 상태는 이 기계가 모른다(전송은 MirrorController 담당).
            PairPhase.Done -> "연결 준비 완료"
            is PairPhase.Failed -> phase.reason
        }

    /** PIN을 띄워야 하는 상태인가 — UI의 유일한 기준. */
    val showsPin: Boolean get() = phase == PairPhase.Offered
}

sealed interface PairEv {
    /** 광고 시작(라운드 초기화). */
    data object Advertise : PairEv
    /** HELLO 도착(PAKE 모드) — Mac의 논스와 ML-KEM 공개키를 받았다. */
    data object Hello : PairEv

    /**
     * HELLO 도착(재연결 모드) — 실린 TOTP가 **아는 Mac을 짚었다.**
     *
     * ★ 이 사건은 힌트가 실제로 맞았을 때만 난다. HELLO의 모드 바이트는 통보일 뿐이라
     * 그것만 보고 갈라지면 아무나 "STS로 하자"고 말할 수 있다 — 실행기가 대조한 뒤에 넣는다.
     */
    data object HelloSts : PairEv

    /** STS2 도착 + Mac 서명 검증 성공 — **그 Mac이 맞다는 게 여기서 끝난다.** */
    data object StsResponseOk : PairEv
    /** PAKE1 도착 + step2 성공. */
    data object Pake1 : PairEv
    /** PAKE3 도착 + step4 성공 — **Mac이 PIN을 알았다는 증명**이 여기서 끝난다. */
    data object Pake3Verified : PairEv
    /** 봉인된 채널로 Mac 인증서가 도착했다. */
    data object PeerIdentity : PairEv
    /** Mac 후보(PARAMS) 도착·파싱 성공. */
    data object ServerParams : PairEv
    /** 내 후보를 다 보냈다(STUN 왕복 끝). */
    data object CandidatesSent : PairEv
    /** 펀칭 개시 신호 도착. */
    data object PunchStart : PairEv

    data object LinkLost : PairEv
    /** 현재 단계의 마감이 지났다. 실행기가 [deadlineMs]로 무장한다. */
    data object DeadlineElapsed : PairEv
    data class Failed(val reason: String) : PairEv
}

sealed interface PairFx {
    /**
     * 새 PIN을 뽑아 화면에 띄우고 OFFER(논스 ‖ 암호문 ‖ 파라미터)를 보낸다.
     *
     * ⚠ Argon2id가 64MiB를 돌린다 — BLE 콜백 스레드를 막지 않도록 실행기가 옮겨 실행한다.
     */
    data object SendOffer : PairFx
    data object SendPake2 : PairFx

    /** 임시 X25519 + ML-KEM 키쌍을 만들어 STS1을 보낸다. */
    data object SendStsHello : PairFx
    /** 우리 서명을 봉인해 STS3를 보낸다. 이걸로 재연결 인증이 닫힌다. */
    data object SendStsConfirm : PairFx
    /** 우리 인증서를 봉인해 보낸다. PAKE가 끝난 뒤에만 가능하다. */
    data object SendIdentity : PairFx
    /** Mac 인증서를 핀한다(신뢰저장소 기록). */
    data object PinPeer : PairFx

    /**
     * 화면의 PIN을 지운다. **화면만** 건드린다.
     *
     * ★ 여기에 PAKE 상태 정리를 같이 넣으면 안 된다. 사람이 옮겨 적은 뒤(PAKE1 도착)
     * 화면에서 숫자를 치우는 건 맞지만, 그때 PAKE는 아직 절반밖에 안 끝났다 — step4가
     * 남아 있다. 실기기에서 정확히 그 버그가 났다: PIN을 지우면서 `spake`를 닫아 버려
     * PAKE3가 조용히 무시됐고, 세션이 안 선 채로 신원 프레임이 도착해 죽었다.
     */
    data object HidePin : PairFx

    /** 라운드가 끝났다 — 키 재료를 전부 지운다. [HidePin]과 달리 되돌릴 수 없다. */
    data object EndRound : PairFx
    data object SendCandidates : PairFx
    data object BeginPunch : PairFx
    data class ArmDeadline(val ms: Long) : PairFx
    data object CancelDeadline : PairFx
    data class Log(val msg: String) : PairFx
}

data class PairStep(val state: PairState, val fx: List<PairFx> = emptyList())

/**
 * 단계를 옮기며 마감 무장/해제를 **자동으로** 끼워 넣는다.
 *
 * 손으로 붙이게 두면 언젠가 하나를 빠뜨리고, 그게 곧 무한 대기다.
 */
private fun goto(s: PairState, phase: PairPhase, fx: List<PairFx> = emptyList()): PairStep {
    val out = ArrayList<PairFx>(fx.size + 2)
    out.add(PairFx.CancelDeadline)
    out.addAll(fx)
    phase.deadlineMs?.let { out.add(PairFx.ArmDeadline(it)) }
    return PairStep(s.copy(phase = phase), out)
}

fun reduce(s: PairState, ev: PairEv): PairStep {
    // 실패한 라운드는 그 자리에서 되살아나지 않는다. 다만 **새 라운드는 언제든 시작될 수
    // 있다** — Mac이 다시 걸어오는 것(HELLO)이 광고 토글만큼이나 정상적인 시작이다.
    if (s.phase is PairPhase.Failed &&
        ev !is PairEv.Advertise && ev !is PairEv.Hello && ev !is PairEv.HelloSts &&
        ev !is PairEv.LinkLost
    ) {
        return PairStep(s)
    }

    return when (ev) {

        is PairEv.Advertise ->
            goto(PairState(), PairPhase.Idle, listOf(PairFx.HidePin, PairFx.EndRound))

        // ★ **라운드 중이 아니면 새 HELLO를 받는다** — `Idle`뿐 아니라 `Done`·`Failed`에서도.
        //
        // 여기서 `EndRound`를 부르지 **않는** 것이 중요하다. 실행기는 HELLO를 이미 파싱해
        // 그 라운드의 재료(macNonce·helloBytes·상대 신원)를 채워 놓고 이 사건을 올린다 —
        // 지금 청소하면 방금 받은 것을 지운다. 청소는 링크가 끊길 때(LinkLost) 한다.
        is PairEv.Hello -> {
            if (s.phase.inRound) {
                PairStep(s, listOf(PairFx.Log("단계 밖 HELLO 무시")))
            } else {
                goto(s, PairPhase.Offered, listOf(PairFx.SendOffer))
            }
        }

        is PairEv.HelloSts -> {
            if (s.phase.inRound) {
                PairStep(s, listOf(PairFx.Log("단계 밖 HELLO(STS) 무시")))
            } else {
                goto(s, PairPhase.StsProposing, listOf(PairFx.SendStsHello))
            }
        }

        is PairEv.StsResponseOk -> {
            // 핀하지 않는다 — 이미 신뢰된 Mac이고 인증서도 오지 않는다. PAKE 경로의
            // PeerIdentity와 갈라 두어야 "PAKE 없이 신원이 고정되는" 경로가 안 생긴다.
            if (s.phase != PairPhase.StsProposing) {
                PairStep(s, listOf(PairFx.Log("단계 밖 STS2 무시")))
            } else {
                goto(s, PairPhase.AwaitingParams, listOf(PairFx.SendStsConfirm))
            }
        }

        is PairEv.Pake1 -> {
            if (s.phase != PairPhase.Offered) {
                PairStep(s, listOf(PairFx.Log("단계 밖 PAKE1 무시")))
            } else {
                // PIN은 여기서 역할이 끝난다 — 사람은 이미 옮겨 적었다.
                goto(s, PairPhase.Proving, listOf(PairFx.HidePin, PairFx.SendPake2))
            }
        }

        is PairEv.Pake3Verified -> {
            if (s.phase != PairPhase.Proving) {
                PairStep(s, listOf(PairFx.Log("단계 밖 PAKE3 무시")))
            } else {
                goto(s, PairPhase.Identifying)
            }
        }

        is PairEv.PeerIdentity -> {
            // ★ **핀이 나오는 경로는 여기 하나뿐이다.** 다른 데서 핀하면 그게 곧 PAKE 없이
            // 신원이 고정되는 구멍이 된다 — 검사가 그걸 본다.
            if (s.phase != PairPhase.Identifying) {
                PairStep(s, listOf(PairFx.Log("단계 밖 신원 무시")))
            } else {
                goto(s, PairPhase.AwaitingParams, listOf(PairFx.PinPeer, PairFx.SendIdentity))
            }
        }

        is PairEv.ServerParams -> {
            if (s.phase != PairPhase.AwaitingParams) {
                PairStep(s, listOf(PairFx.Log("단계 밖 후보 무시")))
            } else {
                PairStep(s, listOf(PairFx.SendCandidates))
            }
        }

        is PairEv.CandidatesSent -> {
            if (s.phase != PairPhase.AwaitingParams) {
                PairStep(s)
            } else {
                goto(s, PairPhase.AwaitingPunch)
            }
        }

        is PairEv.PunchStart -> {
            // 개시 신호가 후보 전송 완료보다 먼저 올 수 있다(STUN 왕복이 우리 쪽에서 더 길다).
            if (s.phase != PairPhase.AwaitingPunch && s.phase != PairPhase.AwaitingParams) {
                PairStep(s, listOf(PairFx.Log("단계 밖 개시 신호 무시")))
            } else {
                goto(s, PairPhase.Done, listOf(PairFx.BeginPunch))
            }
        }

        is PairEv.DeadlineElapsed -> when (s.phase) {
            PairPhase.Offered ->
                goto(s, PairPhase.Failed("PIN 입력 시간 초과"),
                     listOf(PairFx.HidePin, PairFx.EndRound))
            PairPhase.Proving ->
                // 이 마감은 "Mac이 PAKE3를 못 보냈다"이고, PIN이 틀렸을 때와 구분되지 않는다.
                // 사용자에게 할 말이 같으므로 한 문구로 합친다.
                goto(s, PairPhase.Failed("PIN이 맞지 않거나 응답이 없습니다"), listOf(PairFx.EndRound))
            PairPhase.Identifying ->
                goto(s, PairPhase.Failed("신원 교환 응답 없음"), listOf(PairFx.EndRound))
            PairPhase.StsProposing ->
                goto(s, PairPhase.Failed("Mac 확인 응답 없음"), listOf(PairFx.EndRound))
            PairPhase.AwaitingParams, PairPhase.AwaitingPunch ->
                // 후보가 없어도 실패가 아니다 — 같은 LAN이면 host 후보로 붙는다.
                goto(s, PairPhase.Done, listOf(PairFx.Log("경로 교환 시간 초과 — host 후보로 시도")))
            else -> PairStep(s)   // 마감이 없는 단계의 낡은 타이머
        }

        // ★ **라운드는 이 링크 위에서만 산다.** 링크가 끊기면 라운드는 끝났고, 다음 라운드를
        // 받을 준비가 돼 있어야 한다.
        //
        // 예전엔 종료 상태(`Done`·`Failed`)에서 이 사건을 무시했다. 그런데 정상 성공 경로가
        // 정확히 그 모양이다 — 후보를 다 주고받으면 Mac이 BLE를 놓으므로 `AwaitingPunch →
        // (LinkLost) → Done`이 되고, 그 뒤로는 링크가 이미 없어 `LinkLost`가 다시 오지 않는다.
        // 즉 `Done`이 **빠져나올 수 없는 방**이 됐고, 유일한 출구가 사용자의 광고 토글이었다.
        // Mac이 '연결 해제' 뒤에 다시 걸면 HELLO가 전부 "단계 밖"으로 버려진 이유가 이것이다.
        is PairEv.LinkLost -> when (s.phase) {
            PairPhase.Idle -> PairStep(s)
            // 이미 끝난 라운드 — 상태는 그대로 두고 **재료만 놓는다.** 화면 문구는 사용자가
            // 마지막으로 본 결과를 유지해야 하고, 다음 HELLO는 어차피 받을 수 있다(`inRound`).
            PairPhase.Done, is PairPhase.Failed -> PairStep(s, listOf(PairFx.EndRound))
            // 후보 교환까지 끝났으면 링크가 끊겨도 성공이다 — 그 다음은 전송 계층의 일이다.
            PairPhase.AwaitingParams, PairPhase.AwaitingPunch ->
                goto(s, PairPhase.Done, listOf(PairFx.EndRound))
            else -> goto(s, PairPhase.Failed("연결이 끊어졌습니다"),
                         listOf(PairFx.HidePin, PairFx.EndRound))
        }

        is PairEv.Failed ->
            goto(s, PairPhase.Failed(ev.reason), listOf(PairFx.HidePin, PairFx.EndRound))
    }
}
