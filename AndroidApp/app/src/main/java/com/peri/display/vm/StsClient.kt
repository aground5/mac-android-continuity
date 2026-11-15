package com.peri.display.vm

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.protobuf.ByteString
import com.peri.display.CodecSupport
import com.peri.display.DisplaySpec
import com.peri.display.crypto.PeriCert
import com.peri.display.crypto.PeriCryptoSession
import com.peri.display.crypto.PeriHandshake
import com.peri.display.crypto.PeriIdentity
import com.peri.display.crypto.PeriSts
import com.peri.display.crypto.TrustStore
import com.peri.display.crypto.TrustedPeer
import com.peri.display.proto.ClientNetwork
import com.peri.display.proto.ControlMessage
import com.peri.display.proto.DisplayCaps
import com.peri.display.proto.Orientation
import com.peri.display.proto.StsAuth
import com.peri.display.proto.StsConfirm
import com.peri.display.proto.StsHello
import com.peri.display.proto.StsHelloCore
import com.peri.display.proto.StsResponseCore
import com.peri.display.transport.WireFormat

private const val TAG = "StsClient"

/**
 * **PQ-STS 세 패스의 클라이언트 절반.** 신원을 걸고 세션 키를 세우는 절차 전부.
 *
 * `crypto/PeriSts.kt`는 이 프로토콜의 **원시 연산**(파생·전사·봉인·서명 검증)을 갖는다.
 * 여기 있는 건 그 연산들을 **어떤 순서로, 무엇을 믿고** 부르는가다 — 신원 조회와 신뢰
 * 판단이 섞여 있어 순수 암호로 내려보낼 수 없고, 앱도 경로도 알 필요가 없는 일이라
 * 오케스트레이터에 남겨 둘 이유도 없다.
 *
 * ## 왜 갈라냈나
 *
 * 이 절차의 실수는 **조용하다.** 서명 검증을 빼먹어도, 전사를 다시 직렬화해도, 의도한
 * 상대인지 대조하지 않아도 연결은 멀쩡히 성립한다 — 틀린 상대와. 그런 코드가 재접속·
 * 백오프·워치독 사이에 끼어 있으면 읽는 사람이 그 셋을 건너뛰며 따라가야 한다.
 *
 * ## 세 패스
 *
 *     Pass 1 (우리)  임시 X25519 + ML-KEM 공개키. **신원은 아직 싣지 않는다.**
 *     Pass 2 (Mac)   상대 공개키 + KEM 암호문 + **봉인된** 신원·서명
 *     Pass 3 (우리)  **봉인된** 우리 서명 — 이걸로 상호 인증이 닫힌다
 *
 * 신원을 봉인해 나르는 것이 요점이다. 같은 망의 관찰자에게 누가 누구와 붙는지 새지 않고,
 * 서명이 덮는 전사가 `core` 바이트 전체라 코덱·해상도·HDR·버전 강등이 불가능하다.
 * 그리고 봉인이 열렸다는 사실 자체가 상대도 같은 K에 도달했다는 증거다(키 확인).
 */
class StsClient(context: Context) {

    private val appContext = context.applicationContext
    private val identity by lazy { PeriIdentity.get(appContext) }
    private val trustStore by lazy { TrustStore.get(appContext) }

    /** 이 기기의 신원 지문 — Mac 로그의 `dev[…]`와 같은 값이라 두 로그를 묶는 키가 된다. */
    val fingerprint: String by lazy {
        runCatching { identity.makeCert(deviceName).fingerprint() }.getOrDefault("????")
    }

    private val deviceName: String get() = Build.MODEL ?: "Android"

    /**
     * 내가 걸려던 Mac의 공개키. null이면 대조하지 않는다.
     *
     * 주소는 **봉인된 BLE 채널 안에서 그 Mac에게 받은** 후보 묶음에서 온다 — 즉 개시할 때
     * 상대가 누군지 이미 알고 있다. 그 정보를 버리면 STS는 "내 신뢰 목록의 누구인가"까지만
     * 확인하게 되고, Mac을 둘 페어링해 뒀을 때 엉뚱한 쪽에 조용히 붙는다.
     *
     * 케이블이나 주소 직접 입력처럼 의도가 없는 경로에서는 null이다 — 그때는 대조할 의도가
     * 애초에 없으므로 검사를 건너뛰는 것이 맞다(막는 게 아니라 없는 것이다).
     *
     * `MirrorController.beginPunch`가 BLE 라운드마다 새로 채우고, 케이블·주소 직접 입력
     * 경로는 명시적으로 비운다. **라운드마다 덮어쓰므로 낡을 수 없다.**
     */
    @Volatile
    var expectedPeer: ByteArray? = null

    private var handshake: PeriHandshake? = null

    /** Pass 1로 **실제 나간 바이트**. 전사에 그대로 들어간다(재직렬화 금지). */
    private var helloCore: ByteArray? = null

    /**
     * 세션이 사라졌다 — 다음 HELLO는 새 임시 키로 시작한다.
     *
     * ★ [expectedPeer]는 **건드리지 않는다.** 전송이 죽은 것과 "누구에게 걸려던 것인가"는
     * 다른 축이다. 재접속·재협상은 같은 Mac에게 다시 붙는 일이므로 그 의도가 살아 있어야
     * 하고, 여기서 비우면 복구 경로 전체에서 대조가 조용히 꺼진다.
     */
    fun reset() {
        handshake = null
        helloCore = null
    }

    /** 결과는 세 갈래뿐이다. */
    sealed interface Outcome {
        /**
         * 상호 인증이 닫혔다. [confirm]을 그대로 선에 올리면 Pass 3이 끝난다 —
         * 프레임을 여기서 만들고 보내지 않는 이유는 어느 전송으로 나갈지가 세션의 사정이라서다.
         */
        data class Established(
            val session: PeriCryptoSession,
            val peerName: String,
            val confirm: ByteArray,
        ) : Outcome

        /** 재시도가 원리상 무의미한 거부 — 호출부는 치명 래치로 간다. */
        data class Rejected(val reason: String) : Outcome

        /** 우리 상태가 아니다(HELLO를 보낸 적 없다 / 이미 정리됐다). 조용히 버린다. */
        data object Stale : Outcome
    }

    /**
     * Pass 1 프레임 — 우리 임시 X25519 + ML-KEM 공개키와 이 기기의 능력.
     *
     * 능력을 여기 싣는 이유: 서명이 이 바이트 전체를 덮으므로, 협상 결과를 중간에서
     * 깎아내리는 것이 불가능해진다. 능력 목록이 곧 인증 대상의 일부다.
     */
    fun hello(network: ClientNetwork, display: DisplaySpec?): ByteArray {
        val hs = PeriHandshake()
        handshake = hs
        val core = StsHelloCore.newBuilder()
            .setProtocolVersion(PeriSts.PROTOCOL_VERSION)
            .setSessionId(ByteString.copyFrom(hs.sessionId))
            .setX25519Pub(ByteString.copyFrom(hs.x25519PubBytes))
            .setMlkem768Pub(ByteString.copyFrom(hs.mlkemPubBytes))
            .setClientName(deviceName)
            .setNetwork(network)
        CodecSupport.supportedDecoders().forEach { core.addSupportedCodecs(it) }
        display?.let { d ->
            core.addAllDecoders(d.decoders)
            core.display = DisplayCaps.newBuilder()
                .setWidthPx(d.widthPx)
                .setHeightPx(d.heightPx)
                .setDensityDpi(d.densityDpi)
                .setRefreshRate(d.refreshRate)
                .setOrientation(
                    if (d.isLandscape) Orientation.ORIENTATION_LANDSCAPE
                    else Orientation.ORIENTATION_PORTRAIT
                )
                .setMaxWidthPx(d.maxWidthPx)
                .setMaxHeightPx(d.maxHeightPx)
                .addAllSupportedDynamicRanges(d.dynamicRanges)
                .build()
        }
        // ★ 직렬화한 **이 바이트**가 서명 대상이다. 다시 만들지 않고 들고 있는다 —
        // protobuf 재직렬화가 바이트 단위로 같다는 보장이 없다([PeriSts] 주석).
        val bytes = core.build().toByteArray()
        helloCore = bytes
        return WireFormat.handshakeFrame(
            ControlMessage.newBuilder()
                .setStsHello(StsHello.newBuilder().setCore(ByteString.copyFrom(bytes)))
                .build()
        )
    }

    /**
     * Pass 2를 검증하고 Pass 3을 만든다.
     *
     * @param cable 케이블(AOA)로 붙어 있는가. **부트스트랩의 유일한 근거**다 — 케이블에는
     *              지나가는 관찰자가 없으므로, 그때만 처음 보는 Mac의 신원을 받아 신뢰를 세운다.
     */
    fun accept(respCore: ByteArray, sealedAuth: ByteArray, cable: Boolean): Outcome {
        val hs = handshake ?: return Outcome.Stale
        val mine = helloCore ?: return Outcome.Stale

        val core = runCatching { StsResponseCore.parseFrom(respCore) }.getOrNull()
            ?: return Outcome.Rejected("핸드셰이크 형식 오류")
        val (ecdh, kem) = runCatching {
            hs.rawSecrets(core.x25519Pub.toByteArray(), core.mlkem768Ciphertext.toByteArray())
        }.getOrElse {
            Log.e(TAG, "key derive failed: ${it.message}", it)
            return Outcome.Rejected("키 파생 실패: ${it.message}")
        }
        val keys = PeriSts.derive(ecdh, kem)
        val th = PeriSts.transcript(mine, respCore)

        val peer = when (
            val who = identifyServer(
                authKey = keys.auth,
                sealed = sealedAuth,
                signedBytes = PeriSts.serverSigningBytes(th),
                trusted = trustStore.all(),
                cable = cable,
                expected = expectedPeer,
            )
        ) {
            ServerIdentity.Unknown -> {
                Log.e(TAG, "server identity verification FAILED")
                return Outcome.Rejected("서버(Mac) 인증 실패 — 연결 거부")
            }

            is ServerIdentity.WrongPeer -> {
                Log.e(TAG, "다른 Mac이 응답했다 — 기대한 키가 아니라 ${who.got.name}이다")
                return Outcome.Rejected("연결하려던 Mac이 아닙니다")
            }

            // 케이블이 채널을 인증했다 — 신뢰를 여기서 세운다. 저장은 부작용이라 순수
            // 판정 밖에 있어야 하고, 그래서 결정과 기록이 갈려 있다.
            is ServerIdentity.Bootstrap -> {
                trustStore.trust(who.cert)
                Log.i(TAG, "USB 케이블로 Mac 신뢰 설정: ${who.cert.name} [${who.cert.fingerprint()}]")
                who.peer
            }

            is ServerIdentity.Known -> who.peer
        }
        trustStore.touch(peer.pub)

        // Pass 3 — 우리 신원도 봉인해 보낸다. 이걸로 상호 인증이 닫힌다.
        val auth = StsAuth.newBuilder()
            .setSignature(ByteString.copyFrom(identity.sign(PeriSts.clientSigningBytes(th))))
            .also {
                // ★ 공개키를 싣지 않는다. Mac은 저장된 키로 시도검증해 우리를 찾는다.
                // 예외는 케이블 부트스트랩뿐 — 그때만 Mac에게 조회할 게 없다.
                if (cable) {
                    it.identityCert = ByteString.copyFrom(identity.makeCert(deviceName).serialized())
                }
            }
            .build()
        val confirm = WireFormat.handshakeFrame(
            ControlMessage.newBuilder().setStsConfirm(
                StsConfirm.newBuilder().setSealedAuth(
                    ByteString.copyFrom(
                        PeriSts.sealAuth(keys.auth, auth.toByteArray(), PeriSts.Role.CLIENT))),
            ).build(),
        )
        return Outcome.Established(keys.session, peer.name, confirm)
    }

}

/** 봉인을 연 결과가 **누구인가** — 또는 왜 아무도 아닌가. */
sealed interface ServerIdentity {

    /** 신뢰 목록에 있는 상대이고, 걸려던 상대와도 맞는다. */
    data class Known(val peer: TrustedPeer) : ServerIdentity

    /**
     * 케이블로 처음 붙는 상대. 호출부가 [cert]로 신뢰를 세워야 한다 — 저장은 부작용이라
     * 순수 판정 안에서 하지 않는다.
     */
    data class Bootstrap(val cert: PeriCert, val peer: TrustedPeer) : ServerIdentity

    /** 봉인이 안 열렸거나, 서명이 저장된 어느 키와도 맞지 않는다. */
    data object Unknown : ServerIdentity

    /** 열렸고 서명도 맞는데 — **내가 걸려던 그 Mac이 아니다.** */
    data class WrongPeer(val got: TrustedPeer) : ServerIdentity
}

/**
 * 봉인을 열고 **이 Mac이 누구인지** 알아낸다.
 *
 * 보통은 **조회**다 — 서명을 저장된 공개키들로 시도검증하면 누구인지 나온다. 검증자는
 * 서명 대상(전사)을 이미 알고 있으므로 상대가 공개키를 보낼 이유가 없고, 안 보내니
 * 낯선 접속자는 봉인을 풀어도 **검증할 수 없는 64바이트**만 얻는다.
 *
 * 예외가 하나 있다: 케이블로 처음 붙는 순간. 그때는 조회할 게 없어 상대가 신원을 실어
 * 보내고, 케이블이 채널을 인증했다는 근거로 신뢰를 세운다. 케이블에는 지나가는 관찰자가
 * 없으므로 위의 유출 논리가 적용되지 않는다.
 *
 * ## 왜 클래스 밖의 순수 함수인가
 *
 * 여기서 빠뜨린 검사 하나가 곧 인증 우회이고, **그 실수는 조용하다** — 연결은 멀쩡히
 * 성립한다, 틀린 상대와. 그런 코드는 반드시 테스트로 못박아야 하는데 [StsClient]는
 * 안드로이드 키스토어와 파일 저장소에 매여 있어 JVM 테스트에서 만들 수가 없다.
 *
 * 그래서 **결정과 기록을 갈랐다.** 여기는 신뢰 목록을 인자로 받아 판정만 하고(순수),
 * 저장은 호출부가 한다. `StsClientTest`가 이 함수를 직접 돌린다.
 *
 * @param trusted  지금 신뢰 목록. 서명을 이들로 시도검증한다.
 * @param cable    케이블(AOA)로 붙어 있는가 — 부트스트랩의 **유일한** 근거.
 * @param expected 내가 걸려던 Mac의 공개키. null이면 대조하지 않는다.
 */
internal fun identifyServer(
    authKey: ByteArray,
    sealed: ByteArray,
    signedBytes: ByteArray,
    trusted: List<TrustedPeer>,
    cable: Boolean,
    expected: ByteArray?,
): ServerIdentity {
    val plain = PeriSts.openAuth(authKey, sealed, PeriSts.Role.SERVER)
        ?: return ServerIdentity.Unknown
    val auth = runCatching { StsAuth.parseFrom(plain) }.getOrNull()
        ?: return ServerIdentity.Unknown
    val sig = auth.signature.toByteArray()

    val found = PeriSts.resolveSigner(sig, signedBytes, trusted)?.let { ServerIdentity.Known(it) }
        ?: bootstrap(auth.identityCert.toByteArray(), sig, signedBytes, cable)
        ?: return ServerIdentity.Unknown

    // ★ **내가 걸려던 그 Mac인가.**
    //
    // 위 조회는 "내 신뢰 목록의 누구인가"까지만 답한다. Mac을 둘 페어링해 뒀다면 A에게
    // 걸었는데 B가 응답해도 그냥 통과한다 — 주소가 재사용됐든, 경로상의 누군가 돌렸든.
    //
    // 부트스트랩에도 **똑같이** 건다. 케이블 경로에서는 [expected]가 null이라 실제로는
    // 건너뛰지만, 조건을 갈래마다 따로 쓰면 언젠가 한쪽만 남는다.
    val peer = when (found) {
        is ServerIdentity.Known -> found.peer
        is ServerIdentity.Bootstrap -> found.peer
        else -> return ServerIdentity.Unknown
    }
    if (expected != null && !expected.contentEquals(peer.pub)) {
        return ServerIdentity.WrongPeer(peer)
    }
    return found
}

/**
 * 조회 실패 = 모르는 Mac. **케이블이 아니면 여기서 끝이다**(fail-closed).
 *
 * 케이블일 때만 상대가 실어 보낸 인증서를 본다. 자기서명이 맞고, 그 키로 이번 전사의
 * 서명이 검증돼야 한다 — 인증서만으로는 아무것도 증명되지 않는다(누구나 만들 수 있다).
 */
private fun bootstrap(
    certBytes: ByteArray, sig: ByteArray, signedBytes: ByteArray, cable: Boolean,
): ServerIdentity.Bootstrap? {
    if (!cable || certBytes.isEmpty()) return null
    val cert = PeriCert.parse(certBytes) ?: return null
    if (!cert.verifySelfSignature()) return null
    if (!PeriIdentity.verify(cert.pub, sig, signedBytes)) return null
    val now = System.currentTimeMillis()
    return ServerIdentity.Bootstrap(cert, TrustedPeer(cert.pub, cert.name, now, now))
}
