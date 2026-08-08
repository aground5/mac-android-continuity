package com.peri.display.ble

import android.content.Context
import android.os.Build
import android.util.Log
import com.peri.display.crypto.PeriBleHandshake
import com.peri.display.crypto.PeriBleSts
import com.peri.display.crypto.PeriCert
import com.peri.display.crypto.PeriCryptoSession
import com.peri.display.crypto.PeriHandshake
import com.peri.display.crypto.PeriIdentity
import com.peri.display.crypto.PeriSts
import com.peri.display.crypto.Spake2
import com.peri.display.crypto.TrustStore
import com.peri.display.crypto.TrustedPeer
import com.peri.display.transport.CandidateBundle
import com.peri.display.vm.PairDiagnostics
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val TAG = "PairingCoordinator"

/**
 * BLE 페어링 — Android = 응답자/peripheral. **상태는 여기 없다.**
 *
 * 이 파일은 [PairingMachine]의 **실행기**다. 단계 판단·순서 제약·마감은 전부 순수 리듀서에
 * 있고, 여기 남은 건 셋뿐이다:
 *
 *  1. 프레임을 파싱해 **사실**로 바꿔 기계에 넣는다 (`dispatch`)
 *  2. 기계가 지시한 부작용을 실행한다 (`run`)
 *  3. 부작용에 필요한 재료(KEM·PAKE·세션·인증서·후보)를 들고 있는다
 *
 * 프로토콜은 [PeriBleHandshake] 주석 참조. Mac `PairingCoordinator.swift`와 대칭이다.
 *
 * v1과의 차이: COMMIT·SAS·OTP·재인증 서명이 전부 없다. 그 넷은 "사람을 부를까, 어떻게
 * 부를까"를 양쪽이 합의하기 위한 장치였는데, v2는 **모든 라운드가 PIN을 거치므로** 합의할
 * 것이 없다. 그리고 옛 SAS는 키 교환을 묶지 않아 능동 중간자에 뚫렸다.
 */
class PairingCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val ble = BlePeripheral(appContext)
    private val identity by lazy { PeriIdentity.get(appContext) }
    private val trustStore by lazy { TrustStore.get(appContext) }

    /** UI 한 줄 — 기계의 요약이 유일한 출처다. */
    var onStatus: ((String) -> Unit)? = null

    /**
     * 화면에 띄울 PIN(널이면 감춘다). Mac 사용자가 보고 옮겨 적는다.
     *
     * ★ **crypto-random이라야 한다.** 전사에서 파생하면 상대가 스스로 계산할 수 있어
     * "화면을 봐야만 통과한다"는 성질이 통째로 사라진다.
     */
    var onPin: ((String?) -> Unit)? = null

    /**
     * 내 후보 수집기. 인자는 **Mac이 보낸 묶음 전체**다 — 세션 공유 펀치 토큰(그대로 실어
     * 돌려줘야 한다)과, 이 라운드에서 UDP까지 뚫는지(`fastUdp`)가 거기 들어 있다.
     *
     * 토큰만 넘기던 때가 있었는데, 그러면 우리 쪽이 무엇을 모을지 정할 근거가 없어 항상
     * TCP·UDP를 다 모았다. 세션 수립에 UDP가 필요 없어진 뒤로는 그게 STUN 왕복 낭비다.
     *
     * STUN은 **실제 미디어를 나를 소켓/포트**에서 나가야 매핑이 유효하므로 전송 계층이 구현한다.
     */
    var candidateProvider: ((CandidateBundle) -> CandidateBundle)? = null

    /** Mac 후보 도착 + 개시 신호 → 홀펀칭 개시. */
    var onRemoteCandidates: ((CandidateBundle) -> Unit)? = null

    // 실행기가 들고 있는 재료
    private var machine = PairState()

    // ★ 전부 @Volatile이다. 이 재료들은 [worker] 스레드에서 만들어지고 BLE 콜백 스레드에서
    // 읽힌다(OFFER를 워커가 계산해 넣고, 곧이어 PAKE1이 콜백으로 들어온다). 가시성 보장이
    // 없으면 "방금 넣었는데 null이더라"가 드물게 난다.
    @Volatile private var macNonce: ByteArray? = null
    @Volatile private var macKemPub: ByteArray? = null
    @Volatile private var deviceNonce: ByteArray? = null
    @Volatile private var kemSecret: ByteArray? = null
    @Volatile private var transcript: ByteArray? = null

    /** 이번 라운드의 PAKE. PIN을 만든 쪽이라 우리가 server 역할이다. */
    @Volatile private var spake: Spake2.Server? = null
    /** step2가 낸 조각. [PairFx.SendPake2]가 꺼내 쓴다. */
    @Volatile private var pendingPake2: ByteArray? = null

    // ── 재연결(STS) 경로 ──────────────────────────────────────────────────
    //
    // HELLO의 TOTP가 아는 Mac을 짚었을 때만 채워진다. 이 경로에는 PIN도 인증서도 없다.

    /** TOTP가 짚어 준 Mac. **이 한 사람의 공개키로만 서명을 검증한다.** */
    @Volatile private var stsPeer: TrustedPeer? = null
    @Volatile private var stsKex: PeriHandshake? = null
    /** 전사에 들어갈 **선에 나간 그대로의** 바이트들. */
    @Volatile private var helloBytes: ByteArray? = null
    @Volatile private var stsHelloBytes: ByteArray? = null
    @Volatile private var pendingStsConfirm: ByteArray? = null

    @Volatile private var session: PeriCryptoSession? = null
    /** 이 라운드가 남길 링크 키. Mac 신원이 도착하면 그 옆에 저장된다([PairFx.PinPeer]). */
    @Volatile private var linkKey: ByteArray? = null
    @Volatile private var peerCert: PeriCert? = null
    @Volatile private var pendingRemote: CandidateBundle? = null

    private val timer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "peri-pair-deadline").apply { isDaemon = true }
    }
    private var deadline: ScheduledFuture<*>? = null
    /** Argon2id(64MiB)와 STUN 왕복을 여기서 돈다 — BLE 콜백 스레드를 막으면 안 된다. */
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "peri-pair-worker").apply { isDaemon = true }
    }

    val isSupported: Boolean get() = ble.isSupported

    init {
        // BLE 저수준 문구는 라운드가 시작되기 전에만 노출한다 — 그 뒤로는 기계의 요약이 이긴다.
        ble.onStatus = { s -> if (machine.phase == PairPhase.Idle) onStatus?.invoke(s) }
        ble.onFrame = { handle(it) }
        // 라운드가 돌던 중에 Mac이 떨어지면 기계가 알아야 한다. 이 배선이 없어서 기계가
        // 종료 단계(Done)에 영원히 머물렀고, 그 라운드의 문구가 화면에 잔재로 남았다.
        ble.onCentralDisconnected = { dispatch(PairEv.LinkLost) }
    }

    fun startAdvertising() { dispatch(PairEv.Advertise); ble.start() }
    fun stop() { ble.stop(); dispatch(PairEv.Advertise) }

    /**
     * Mac의 호출에 답한다 — 이름 없는 짧은 응답 광고를 띄운다.
     *
     * 라운드를 초기화하고 시작한다. 이 뒤에 붙는 Mac은 HELLO에 TOTP를 실어 올 것이고,
     * 그 값이 맞으면 PIN 없이 STS로 간다([PeriBleSts]).
     */
    fun respondToPage(response: ByteArray) {
        dispatch(PairEv.Advertise)
        ble.respondToPage(response)
    }

    /** 사용자가 페어링을 취소했다. */
    fun cancel() { dispatch(PairEv.Failed("페어링 취소")) }

    /**
     * 진단 스냅샷. 뷰가 기계 내부를 직접 보지 않게 여기서 찍어 준다.
     *
     * 연결 축과 **나란히** 놓이는 게 요점이다 — BLE는 시그널링이고 미러링은 LAN/USB로 흐르는
     * 독립된 축인데, 예전엔 이 둘을 한 문구로 섞어 보여줘서 "연결됨"이 잔재로 남았다.
     */
    fun diagnostics(): PairDiagnostics = machine.let {
        PairDiagnostics(
            phase = it.phase,
            summary = it.summary,
            showsPin = it.showsPin,
            deadlineMs = it.phase.deadlineMs,
        )
    }

    /**
     * 망이 바뀌었다 → 광고에 재협상 요청 비트를 세워 Mac을 부른다.
     *
     * 이게 핸드오버 복구의 **유일한 개시 수단**이다. IP가 바뀌는 순간 IP 기반 채널은 전부 못
     * 믿게 되는데, BLE 광고는 IP와 무관하고 이미 상시 돌고 있다.
     *
     * ⚠ v2에서는 이 경로도 **PIN을 요구한다.** v1의 재인증(서명 기반 자동 인증)이 사라졌기
     * 때문이다. 핀된 신원으로 PAKE를 건너뛰는 재연결 경로를 뒤에 따로 얹어야 한다.
     */
    fun requestIceRestart() {
        Log.i(TAG, "★ ICE 재협상 요청 — 광고 비트 ON")
        ble.setIceRestartWanted(true)
    }

    /** 재협상이 끝났거나 더는 필요 없다 → 요청 비트를 내린다. */
    fun clearIceRestart() { ble.setIceRestartWanted(false) }

    // MARK: - 기계 구동

    private val evQueue = ArrayDeque<PairEv>()
    private var draining = false

    /** 사건 → 리듀서 → 상태 반영 → 부작용 실행. 부작용이 또 dispatch할 수 있어 직렬화한다. */
    @Synchronized
    private fun dispatch(ev: PairEv) {
        evQueue.addLast(ev)
        if (draining) return
        draining = true
        try {
            while (true) {
                val e = evQueue.removeFirstOrNull() ?: break
                val before = machine
                val step = reduce(before, e)
                machine = step.state
                if (before != step.state) {
                    Log.i(TAG, "[pair] $e → ${step.state.phase}")
                    onStatus?.invoke(step.state.summary)
                }
                step.fx.forEach { fx ->
                    runCatching { run(fx) }.onFailure {
                        Log.e(TAG, "부작용 실패 $fx: ${it.message}")
                    }
                }
            }
        } finally {
            draining = false
        }
    }

    private fun run(fx: PairFx) {
        when (fx) {
            is PairFx.SendOffer -> {
                val macPub = macKemPub ?: return
                val mNonce = macNonce ?: return
                // Argon2id + KEM. 여기가 이 파일에서 유일하게 무거운 지점이다.
                worker.execute {
                    val pin = PeriBleHandshake.newPin()
                    val server = Spake2.server(pin) ?: run {
                        dispatch(PairEv.Failed("PIN 준비 실패")); return@execute
                    }
                    val (ct, secret) = runCatching { PeriBleHandshake.encapsulate(macPub) }
                        .getOrElse {
                            dispatch(PairEv.Failed("키 교환 실패: ${it.message}")); return@execute
                        }
                    val nonce = PeriBleHandshake.randomNonce32()
                    spake = server
                    kemSecret = secret
                    deviceNonce = nonce
                    transcript = PeriBleHandshake.transcript(
                        mNonce, macPub, nonce, ct, server.publicData,
                    )
                    onPin?.invoke(pin)

                    val out = ByteArrayOutputStream()
                    out.write(T_OFFER)
                    appendLp(out, nonce); appendLp(out, ct); appendLp(out, server.publicData)
                    ble.send(out.toByteArray())
                }
            }

            is PairFx.SendStsHello -> {
                // ML-KEM 키쌍 생성 — BLE 콜백 스레드에서 돌릴 일은 아니다.
                worker.execute {
                    val kex = PeriHandshake()
                    stsKex = kex
                    val out = ByteArrayOutputStream()
                    appendLp(out, kex.x25519PubBytes); appendLp(out, kex.mlkemPubBytes)
                    val body = out.toByteArray()
                    stsHelloBytes = body        // 전사는 선에 나간 바이트 그대로를 덮는다
                    val framed = ByteArrayOutputStream()
                    framed.write(T_STS_HELLO); framed.write(body)
                    ble.send(framed.toByteArray())
                }
            }

            is PairFx.SendStsConfirm -> {
                val body = pendingStsConfirm ?: return
                pendingStsConfirm = null
                val out = ByteArrayOutputStream()
                out.write(T_STS_CONFIRM); out.write(body)
                ble.send(out.toByteArray())
            }

            is PairFx.SendPake2 -> {
                val body = pendingPake2 ?: return
                pendingPake2 = null
                val out = ByteArrayOutputStream()
                out.write(T_PAKE2); out.write(body)
                ble.send(out.toByteArray())
            }

            is PairFx.SendIdentity -> {
                val s = session ?: return
                // 신원은 **봉인된 뒤에** 나간다. 평문 flight에 인증서를 실으면 지나가는
                // 스캐너에게 두 기기의 신원이 그대로 새어 나간다.
                val cert = identity.makeCert(Build.MODEL ?: "Android")
                val (n, b) = s.seal(cert.serialized())
                val out = ByteArrayOutputStream()
                out.write(T_IDENTITY); out.write(n); out.write(b)
                ble.send(out.toByteArray())
            }

            is PairFx.PinPeer -> {
                val cert = peerCert ?: return
                // 신원과 링크 키를 **한 번에** 넣는다. 둘을 따로 저장하면 신원만 남고 키가 없는
                // (또는 그 반대의) 반쪽 상태가 생기고, 다음 재연결이 그 위에서 갈린다.
                trustStore.trust(cert, linkKey)
                linkKey = null
                Log.i(TAG, "paired with ${cert.name} [${cert.fingerprint()}]")
            }

            is PairFx.HidePin -> onPin?.invoke(null)

            is PairFx.EndRound -> {
                spake?.close(); spake = null
                session = null; linkKey = null
                kemSecret = null; transcript = null
                macNonce = null; macKemPub = null; deviceNonce = null
                pendingPake2 = null; peerCert = null; pendingRemote = null
                stsPeer = null; stsKex = null; pendingStsConfirm = null
                helloBytes = null; stsHelloBytes = null
            }

            is PairFx.SendCandidates -> {
                val s = session ?: return
                val bundle = pendingRemote ?: return
                val provider = candidateProvider ?: run {
                    Log.w(TAG, "후보 제공자 미설정 — 응답 못 함"); return
                }
                // STUN은 네트워크 왕복이라 BLE 콜백 스레드를 막으면 안 된다.
                worker.execute {
                    val mine = provider(bundle)
                    val (n, b) = s.seal(mine.serialized())
                    val out = ByteArrayOutputStream()
                    out.write(T_CANDIDATES); out.write(n); out.write(b)
                    ble.send(out.toByteArray())
                    Log.i(TAG, "내 후보 ${mine.candidates.size}개 전송: ${mine.candidates.joinToString()}")
                    dispatch(PairEv.CandidatesSent)
                    // 펀칭은 여기서 시작하지 않는다 — Mac의 개시 신호를 기다린다(시작 시각 동기화).
                }
            }

            is PairFx.BeginPunch -> {
                val bundle = pendingRemote ?: run {
                    Log.w(TAG, "개시 신호가 왔는데 Mac 후보가 없다"); return
                }
                pendingRemote = null
                onRemoteCandidates?.invoke(bundle)
            }

            is PairFx.ArmDeadline -> {
                deadline?.cancel(false)
                deadline = timer.schedule({ dispatch(PairEv.DeadlineElapsed) },
                                          fx.ms, TimeUnit.MILLISECONDS)
            }

            is PairFx.CancelDeadline -> { deadline?.cancel(false); deadline = null }

            is PairFx.Log -> Log.i(TAG, "[pair] ${fx.msg}")
        }
    }

    // MARK: - 프레임 → 사실

    private fun handle(data: ByteArray) {
        if (data.isEmpty()) return
        val type = data[0].toInt() and 0xFF
        val payload = data.copyOfRange(1, data.size)
        when (type) {
            T_HELLO -> onHello(payload)
            T_PAKE1 -> onPake1(payload)
            T_PAKE3 -> onPake3(payload)
            T_IDENTITY -> onIdentity(payload)
            T_PARAMS -> onParams(payload)
            T_PUNCH -> onPunch(payload)
            T_STS_RESPONSE -> onStsResponse(payload)
        }
    }

    /**
     * 1) HELLO — `[1B 모드][논스][ML-KEM 공개키 또는 TOTP 힌트]`.
     *
     * ★ **모드 바이트만 보고 갈라지지 않는다.** 그걸 믿으면 아무나 "재연결로 하자"고 말할 수
     * 있고, 반대로 이미 아는 Mac을 PIN 경로로 끌어내릴 수도 있다. 재연결로 가는 건 힌트가
     * 실제로 우리가 아는 Mac의 링크 키로 맞아떨어질 때뿐이다.
     *
     * 그리고 **아무것도 기억하지 않는다.** "아까 호출당했다" 같은 깃발을 두면 그 깃발이 선
     * 짧은 창에 아무나 끼어들어 소비할 수 있다. 매 연결이 스스로 자격을 증명한다.
     */
    private fun onHello(payload: ByteArray) {
        if (payload.isEmpty()) return
        val mode = payload[0].toInt() and 0xFF
        val f = readLpFields(payload.copyOfRange(1, payload.size), 2) ?: return
        macNonce = f[0]

        if (mode == MODE_STS) {
            val store = trustStore
            val who = PeriBleSts.resolveHint(f[1], f[0], store.all(), { store.linkKey(it.pub) })
            if (who == null) {
                // 힌트가 아무것도 안 짚었다. **PAKE로 물러서지 않는다** — 그게 강등이다.
                // 사유는 알리지 않는다. "너를 모른다"와 "PIN이 틀렸다"를 구분해 주면
                // 익명 프로버가 우리 신뢰 목록의 유무를 알아낸다.
                Log.w(TAG, "재연결 힌트 불일치 — 라운드 폐기")
                dispatch(PairEv.Failed("연결을 확인하지 못했습니다"))
                return
            }
            Log.i(TAG, "★ 재연결 — ${who.name} (PIN 없음)")
            stsPeer = who
            helloBytes = payload
            dispatch(PairEv.HelloSts)
            return
        }

        // 모르는 모드는 PAKE로 취급하지 않는다. 관대하게 넘기면 "모드를 안 적으면 PIN 라운드"가
        // 되고, 그건 모드 바이트를 없애는 것과 같다.
        if (mode != MODE_PAKE) {
            Log.w(TAG, "알 수 없는 HELLO 모드 $mode — 라운드 폐기")
            dispatch(PairEv.Failed("연결을 확인하지 못했습니다"))
            return
        }
        macKemPub = f[1]
        dispatch(PairEv.Hello)
    }

    /** 3') STS2 — 캡슐 해제 + 전사 확정 + **Mac 서명 검증**. 여기서 상대가 확정된다. */
    private fun onStsResponse(payload: ByteArray) {
        val kex = stsKex ?: return
        val who = stsPeer ?: return
        val hello = helloBytes ?: return
        val sts1 = stsHelloBytes ?: return
        val f = readLpFields(payload, 3) ?: return
        // core = 봉인 블롭 **앞**까지. 재직렬화하지 않고 실려 온 바이트를 그대로 자른다 —
        // 한 바이트라도 다르게 인코딩하면 전사가 갈라진다.
        val core = payload.copyOfRange(0, 2 + f[0].size + 2 + f[1].size)
        val th = PeriBleSts.transcript(hello, sts1, core)
        val (ecdh, kem) = runCatching { kex.rawSecrets(f[0], f[1]) }.getOrElse {
            dispatch(PairEv.Failed("키 교환 실패")); return
        }
        val keys = PeriBleSts.derive(ecdh, kem, th)
        val sig = PeriSts.openAuth(keys.auth, f[2], PeriSts.Role.SERVER)
        if (sig == null) {
            dispatch(PairEv.Failed("Mac 신원을 확인하지 못했습니다")); return
        }
        // 서명이 **실제로 누구 것인지** 따로 알아낸다. `who.pub` 하나로만 검증해도 결과는
        // 같지만, 그건 "힌트가 짚은 상대 = 서명한 상대"를 코드 구조에 숨겨 두는 것이다.
        //
        // 두 신원이 갈릴 수 있는 경로가 실제로 있다: 힌트는 8바이트라 충돌이 가능하고,
        // 복원·중복 등록으로 링크 키가 두 항목에 걸릴 수도 있다. 그때 [PeriBleSts.resolveHint]
        // 는 `firstOrNull`이라 **먼저 걸린 쪽**을 짚는다. 대조를 밖으로 꺼내 두면 그 상황이
        // "확인 실패"가 아니라 무엇이 어긋났는지로 로그에 남는다.
        val signer = PeriSts.resolveSigner(sig, PeriBleSts.macSigningBytes(th), trustStore.all())
        if (signer == null || !signer.pub.contentEquals(who.pub)) {
            Log.e(TAG, "힌트는 ${who.name}을 짚었는데 서명은 ${signer?.name ?: "모르는 키"}다")
            dispatch(PairEv.Failed("Mac 신원을 확인하지 못했습니다")); return
        }
        session = keys.session
        pendingStsConfirm = PeriSts.sealAuth(
            keys.auth, identity.sign(PeriBleSts.androidSigningBytes(th)), PeriSts.Role.CLIENT,
        )
        trustStore.touch(who.pub)
        dispatch(PairEv.StsResponseOk)
    }

    /** 3) PAKE1 — step2를 돌려 PAKE2 재료를 만든다. 아직 검증되는 건 없다. */
    private fun onPake1(payload: ByteArray) {
        val server = spake ?: return
        val th = transcript ?: return
        val out = server.step2(payload, PeriBleHandshake.clientId(th), PeriBleHandshake.serverId(th))
            ?: run { dispatch(PairEv.Failed("PAKE 계산 실패")); return }
        pendingPake2 = out
        dispatch(PairEv.Pake1)
    }

    /**
     * 5) PAKE3 — step4. **여기서 Mac이 PIN을 알았다는 게 증명된다.**
     *
     * 실패는 곧 중간자거나 엉뚱한 상대다. 사유를 돌려주지 않는다 — PAKE에서 "왜 틀렸는지"는
     * 그 자체로 오라클이다.
     */
    private fun onPake3(payload: ByteArray) {
        val server = spake ?: return
        val keys = server.step4(payload)
            ?: run { dispatch(PairEv.Failed("PIN이 맞지 않습니다")); return }
        val secret = kemSecret ?: return
        val mNonce = macNonce ?: return
        val dNonce = deviceNonce ?: return
        session = PeriBleHandshake.session(secret, keys, mNonce, dNonce)
        // 링크 키도 **여기서** 뽑는다 — PAKE 재료가 살아 있는 유일한 순간이다. 상대가 누구인지는
        // 아직 모르므로 들고만 있다가, 신원이 도착하면 그 옆에 붙인다.
        linkKey = PeriBleHandshake.linkKey(secret, keys, mNonce, dNonce)
        dispatch(PairEv.Pake3Verified)
    }

    /** 6) 봉인된 채널로 온 Mac 인증서. 열렸다는 것 자체가 Mac이 PIN을 알았다는 증거다. */
    private fun onIdentity(payload: ByteArray) {
        val plain = open(payload) ?: run {
            dispatch(PairEv.Failed("Mac 신원을 열지 못했습니다")); return
        }
        val cert = PeriCert.parse(plain)
        if (cert == null || !cert.verifySelfSignature()) {
            dispatch(PairEv.Failed("Mac 인증서 무효")); return
        }
        peerCert = cert
        dispatch(PairEv.PeerIdentity)
    }

    /**
     * (자동연결) PARAMS 수신 → 복호화 → **연결 후보 묶음** 파싱.
     *
     * Mac이 host·srflx 후보 목록 + 세션 공유 펀치 토큰을 보낸다. 같은 LAN이면 host 후보로
     * 곧장 붙고, 망이 다르면 srflx로 홀펀칭한다.
     */
    private fun onParams(payload: ByteArray) {
        val plain = open(payload) ?: return
        val bundle = CandidateBundle.parse(plain) ?: run {
            Log.e(TAG, "Mac 후보 파싱 실패 — 접속 불가")
            dispatch(PairEv.Failed("연결 정보를 이해하지 못했습니다 (버전 불일치?)"))
            return
        }
        Log.i(TAG, "Mac 후보 ${bundle.candidates.size}개: ${bundle.candidates.joinToString()}")
        pendingRemote = bundle
        dispatch(PairEv.ServerParams)
    }

    /**
     * Mac의 펀칭 개시 신호 → **이 순간** 펀칭을 시작한다.
     *
     * 예전엔 후보를 보낸 뒤 각자 알아서 시작해 시작 시각이 수백 ms~수 초 어긋났다. UDP는
     * 창이 넓어 관대했지만 **TCP simultaneous open은 양쪽이 같은 쌍을 거의 동시에 두드려야**
     * 성립한다. 이 신호로 어긋남을 BLE 편도 지연(연결 간격 30ms 수준)까지 좁힌다.
     */
    private fun onPunch(payload: ByteArray) {
        if (open(payload) == null) {
            Log.w(TAG, "개시 신호 복호화 실패 — 무시"); return
        }
        Log.i(TAG, "펀칭 개시 신호 수신 → 즉시 시작")
        dispatch(PairEv.PunchStart)
    }

    /** [12B nonce][ciphertext‖tag] → 평문. 세션이 없거나 열리지 않으면 null. */
    private fun open(payload: ByteArray): ByteArray? {
        val s = session ?: return null
        if (payload.size < 12) return null
        return runCatching {
            s.open(payload.copyOfRange(0, 12), payload.copyOfRange(12, payload.size))
        }.getOrNull()
    }

    private fun appendLp(out: ByteArrayOutputStream, f: ByteArray) {
        out.write((f.size ushr 8) and 0xFF); out.write(f.size and 0xFF); out.write(f)
    }

    private fun readLpFields(d: ByteArray, count: Int): List<ByteArray>? {
        val out = ArrayList<ByteArray>(count); var i = 0
        repeat(count) {
            if (i + 2 > d.size) return null
            val len = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF); i += 2
            if (i + len > d.size) return null
            out.add(d.copyOfRange(i, i + len)); i += len
        }
        return out
    }

    companion object {
        // Mac `PairingCoordinator.swift`의 FrameType과 값이 일치해야 한다.
        //
        // 값이 하나뿐인 프레임은 payload가 곧 그 값이다(길이가 프로토콜 상수라 경계가 없어도
        // 된다). 값이 여럿이면 2B BE 길이프리픽스로 나눈다. 봉인된 프레임은 [12B nonce][본문].
        /**
         * HELLO 첫 바이트 — 이 라운드가 PIN인지 재연결인지. Mac의 `modePake`/`modeSts`와
         * 값이 같아야 한다.
         *
         * **요청이 아니라 통보다.** 우리가 이 바이트를 믿고 갈라지지는 않는다 — 재연결로 가는
         * 건 실린 TOTP가 실제로 맞아떨어질 때뿐이다([onHello]).
         */
        private const val MODE_PAKE = 1
        private const val MODE_STS = 2

        private const val T_HELLO = 1       // Mac → 우리 : [모드][논스][공개키 또는 힌트]
        private const val T_OFFER = 2       // 우리 → Mac : 논스 ‖ 암호문 ‖ 암호해싱 파라미터
        private const val T_PAKE1 = 3       // Mac → 우리
        private const val T_PAKE2 = 4       // 우리 → Mac
        private const val T_PAKE3 = 5       // Mac → 우리
        private const val T_IDENTITY = 6    // 양방향 (sealed) : 장기 신원 인증서
        private const val T_PARAMS = 7      // Mac → 우리 (sealed) : 후보 묶음 + 펀치 토큰
        private const val T_CANDIDATES = 8  // 우리 → Mac (sealed) : 후보 묶음 (같은 토큰 에코)
        private const val T_PUNCH = 9       // Mac → 우리 (sealed) : 지금 펀칭 시작
        // 재연결(STS) 경로. PAKE 프레임과 번호를 나눠 써서 섞일 수 없다.
        private const val T_STS_HELLO = 10     // 우리 → Mac : X_A ‖ PK_A
        private const val T_STS_RESPONSE = 11  // Mac → 우리 : X_B ‖ ct ‖ 봉인된 Mac 서명
        private const val T_STS_CONFIRM = 12   // 우리 → Mac : 봉인된 우리 서명
    }
}
