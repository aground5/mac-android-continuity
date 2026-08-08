package com.peri.display.crypto

/**
 * SPAKE2+EE — 짧은 PIN 하나로 BLE 첫 페어링의 **능동 중간자**를 막는다.
 *
 * 왜 이게 필요했나
 * ───────────────
 * 예전 방식(commitment + 6자리 SAS 비교)은 코드가 **키 교환을 전혀 묶지 않았다.** 양쪽
 * nonce와 인증서만 해시했기 때문에, 중간자가 그 둘을 그대로 흘려보내면서 x25519·ML-KEM만
 * 자기 것으로 갈아끼우면 두 기기 화면에 **같은 6자리가 뜬다.** 사람이 눈으로 맞다고 확인해도
 * 실제로는 각자 공격자와 키를 나눠 가진 상태다. OTP 방식도 마찬가지였다 — 봉인해서 보내 봐야
 * 중간자가 자기 키로 풀었다가 다시 봉해서 넘기면 그만이다.
 *
 * PAKE는 그 구멍이 원리적으로 없다. **세션 키 자체가 PIN에서 나오므로** PIN을 모르는 중간자는
 * 어느 쪽과도 키를 세우지 못한다. 오프라인 대입도 불가능하다 — 틀린 추측은 아무 정보도 남기지
 * 않는다. 남는 건 온라인 추측 한 번뿐이고, 그건 라운드를 폐기해서 막는다.
 *
 * 역할
 * ────
 * SPAKE2+EE는 augmented라 비대칭이다. **PIN 원문을 쥔 쪽이 client, 검증자를 쥔 쪽이 server**다.
 * PIN을 만들어 화면에 띄우는 건 이 기기이므로 여기가 **server**이고, 사람이 옮겨 적는 Mac이
 * client다. (client 쪽도 구현해 둔 건 자체 검증 테스트가 한 프로세스에서 전 과정을 돌리기
 * 위해서다 — 실제 페어링에서 이 기기는 server 함수만 쓴다.)
 *
 * `clientId`/`serverId`에는 **장기 신원키 지문**을 싣는다. 그래서 PAKE가 통과했다는 사실이
 * 곧 "이 PIN을 본 사람이 이 신원키의 주인"이라는 증명이 된다 — 중간자가 인증서를 바꿔치면
 * id가 어긋나 [Server.step4] / [Client.finish]가 실패한다.
 *
 * ⚠ **기회는 한 번이다.** 틀린 PIN이 들어오면 이 라운드는 죽고 PIN도 버려야 한다. 재시도를
 * 허용하면 온라인 추측이 반복 가능해져 4자리(1/10⁴)가 금방 뚫린다. 그 규칙은 여기가 아니라
 * 페어링 기계가 지킨다 — 여기는 "맞았다/틀렸다"만 돌려준다.
 *
 * 실패는 전부 null이다. 이유를 나누지 않는 것도 설계다 — PAKE에서 "왜 틀렸는지"는 오라클이다.
 */
object Spake2 {

    init { System.loadLibrary("peri_spake") }

    // ── 선에 실리는 값들의 길이 (ThirdParty/spake2-ee/crypto_spake.h 와 일치) ──
    const val PUBLIC_DATA_BYTES = 36
    const val RESPONSE1_BYTES = 32
    const val RESPONSE2_BYTES = 64
    const val RESPONSE3_BYTES = 32
    const val SHARED_KEY_BYTES = 32

    /**
     * Argon2id 파라미터 — **양쪽에 못 박고 받은 값을 대조한다.**
     *
     * PIN이 일회용이라 암호 해싱이 오프라인 내성을 사 주는 건 아니다(그건 PAKE가 이미 한다).
     * 그런데 이 값이 선을 타고 오므로, 대조하지 않으면 상대가 memlimit에 4GiB를 적어 보내
     * 우리를 죽일 수 있다. 그래서 libsodium의 INTERACTIVE 기본값에 고정하고 벗어나면 거부한다.
     */
    const val OPSLIMIT = 2L
    const val MEMLIMIT = 67_108_864L   // 64 MiB

    /** Argon2id13. 네이티브에서 읽어 온다 — 상수를 두 곳에 적으면 언젠가 어긋난다. */
    val alg: Int by lazy { nativeAlgDefault() }

    /** 상대가 보낸 파라미터가 우리가 기대하는 그것인가. */
    fun accepts(publicData: ByteArray): Boolean =
        publicData.size == PUBLIC_DATA_BYTES &&
            nativeValidatePublicData(publicData, alg, OPSLIMIT, MEMLIMIT)

    /**
     * server 라운드 시작 (PIN을 만든 쪽).
     *
     * ⚠ Argon2id가 64MiB를 돌린다 — **BLE 콜백 스레드에서 부르지 말 것.**
     */
    fun server(pin: String): Server? {
        val bytes = pin.toByteArray(Charsets.US_ASCII)
        try {
            val stored = nativeServerStore(bytes, OPSLIMIT, MEMLIMIT) ?: return null
            val blob = nativeStep0(stored) ?: run { stored.fill(0); return null }
            // 꼬리에서 자른다 — public_data 길이는 프로토콜 상수고, 앞의 상태 길이는
            // 네이티브만 알면 된다. 그래야 구조체가 바뀌어도 여기가 안 깨진다.
            val publicData = blob.copyOfRange(blob.size - PUBLIC_DATA_BYTES, blob.size)
            val state = blob.copyOfRange(0, blob.size - PUBLIC_DATA_BYTES)
            blob.fill(0)
            return Server(stored, state, publicData)
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * client 라운드 시작 (PIN을 옮겨 적은 쪽). 반환: (라운드, 상대에게 보낼 response1).
     *
     * ⚠ 여기도 Argon2id가 돈다.
     */
    fun client(publicData: ByteArray, pin: String): Pair<Client, ByteArray>? {
        if (!accepts(publicData)) return null
        val bytes = pin.toByteArray(Charsets.US_ASCII)
        try {
            val blob = nativeStep1(publicData, bytes) ?: return null
            val response1 = blob.copyOfRange(blob.size - RESPONSE1_BYTES, blob.size)
            val state = blob.copyOfRange(0, blob.size - RESPONSE1_BYTES)
            blob.fill(0)
            return Client(state) to response1
        } finally {
            bytes.fill(0)
        }
    }

    /** 돌고 있는 server 라운드. 상태를 들고 있으므로 라운드가 끝나면 [close]한다. */
    class Server internal constructor(
        private val stored: ByteArray,
        private var state: ByteArray,
        /** 상대에게 보낼 파라미터(암호 해싱 설정만 들었다 — 비밀 아님). */
        val publicData: ByteArray,
    ) {
        /** response1 수신 → 보낼 response2. */
        fun step2(response1: ByteArray, clientId: ByteArray, serverId: ByteArray): ByteArray? {
            val blob = nativeStep2(state, stored, response1, clientId, serverId) ?: return null
            val response2 = blob.copyOfRange(blob.size - RESPONSE2_BYTES, blob.size)
            val next = blob.copyOfRange(0, blob.size - RESPONSE2_BYTES)
            blob.fill(0)
            state.fill(0)
            state = next
            return response2
        }

        /**
         * response3 검증 → 공유 키. **여기서 상대가 PIN을 알았다는 게 증명된다.**
         * null이면 PIN이 틀렸거나 신원 지문이 어긋난 것이다 — 둘을 구분하지 않는다.
         */
        fun step4(response3: ByteArray): SpakeKeys? =
            nativeStep4(state, response3)?.let(::splitKeys)

        fun close() { stored.fill(0); state.fill(0) }
    }

    /** 돌고 있는 client 라운드. */
    class Client internal constructor(private val state: ByteArray) {
        /** response2 검증 → (보낼 response3, 공유 키). null이면 상대가 PIN을 몰랐다. */
        fun finish(response2: ByteArray, clientId: ByteArray, serverId: ByteArray):
            Pair<ByteArray, SpakeKeys>? {
            val blob = nativeStep3(state, response2, clientId, serverId) ?: return null
            val response3 = blob.copyOfRange(0, RESPONSE3_BYTES)
            val keys = splitKeys(blob.copyOfRange(RESPONSE3_BYTES, blob.size))
            blob.fill(0)
            return response3 to keys
        }

        fun close() { state.fill(0) }
    }

    private fun splitKeys(raw: ByteArray) = SpakeKeys(
        clientSk = raw.copyOfRange(0, SHARED_KEY_BYTES),
        serverSk = raw.copyOfRange(SHARED_KEY_BYTES, SHARED_KEY_BYTES * 2),
    ).also { raw.fill(0) }

    // ── 네이티브 (app/src/main/cpp/peri_spake_jni.c) ─────────────────────────
    // 상태는 전부 불투명 ByteArray로 오간다 — 핸들이 없으므로 라운드가 중간에 끊겨도
    // 해제할 게 없다. 여러 값을 낼 땐 이어 붙인 배열 하나로 온다.

    private external fun nativeAlgDefault(): Int
    private external fun nativeValidatePublicData(
        publicData: ByteArray, alg: Int, opslimit: Long, memlimit: Long,
    ): Boolean
    private external fun nativeServerStore(
        pin: ByteArray, opslimit: Long, memlimit: Long,
    ): ByteArray?
    private external fun nativeStep0(stored: ByteArray): ByteArray?
    private external fun nativeStep1(publicData: ByteArray, pin: ByteArray): ByteArray?
    private external fun nativeStep2(
        state: ByteArray, stored: ByteArray, response1: ByteArray,
        clientId: ByteArray, serverId: ByteArray,
    ): ByteArray?
    private external fun nativeStep3(
        state: ByteArray, response2: ByteArray,
        clientId: ByteArray, serverId: ByteArray,
    ): ByteArray?
    private external fun nativeStep4(state: ByteArray, response3: ByteArray): ByteArray?
}

/**
 * PAKE가 낸 방향별 키 두 개.
 *
 * 이걸 그대로 세션 키로 쓰지는 않는다 — ML-KEM 공유비밀과 함께 HKDF에 넣어야
 * "PIN과 양자내성 키교환 **둘 다** 깨야 한다"가 성립한다.
 */
class SpakeKeys(val clientSk: ByteArray, val serverSk: ByteArray) {
    fun close() { clientSk.fill(0); serverSk.fill(0) }
}
