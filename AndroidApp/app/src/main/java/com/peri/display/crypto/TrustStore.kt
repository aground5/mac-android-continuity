package com.peri.display.crypto

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class TrustState { UNKNOWN, TRUSTED }

data class TrustedPeer(
    val pub: ByteArray,
    val name: String,
    val pairedAtMillis: Long,
    val lastSeenMillis: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrustedPeer) return false
        return pub.contentEquals(other.pub) && name == other.name &&
                pairedAtMillis == other.pairedAtMillis && lastSeenMillis == other.lastSeenMillis
    }

    override fun hashCode(): Int {
        var r = pub.contentHashCode()
        r = 31 * r + name.hashCode()
        r = 31 * r + pairedAtMillis.hashCode()
        r = 31 * r + lastSeenMillis.hashCode()
        return r
    }
}

/**
 * 핀된(신뢰된) 상대 신원 저장소. 첫 페어링에서 **사람이 확인한**(코드 비교 또는 패스코드
 * 입력) 상대 cert를 trust()로 고정하고, 이후 state()로 신뢰를 확인한다. 키(pub) 자체로
 * 신뢰 → 모르는 키는 자동연결 불가(fail-closed). 키가 바뀐 기기는 '모르는 기기'로 보여
 * 다시 페어링을 요구하게 된다.
 *
 * ★ TOFU가 **아니다.** 한때 LAN 핸드셰이크에서 처음 보는 신원을 자동으로 핀하는 임시
 * 경로가 있었지만(BLE 페어링 완성 전의 부트스트랩) 지금은 지워졌다. 여기 항목을 만드는
 * 곳은 BLE 페어링 하나뿐이고, 그 경로는 사람의 확인 없이 끝나지 않는다.
 *
 * 저장: filesDir/trust.json. pub는 공개값이라 비밀유지가 아니라 무결성이 관심사 —
 * 로컬 변조 방지 강화는 후속 과제(TODO). Mac TrustStore.swift 와 대칭.
 *
 * ★ **링크 키만은 이 파일에 없다.** 페어링이 남기는 32바이트 공유 비밀은 진짜 비밀이라
 * 평문 JSON에 둘 수 없다 — [SecretVault](AndroidKeyStore로 감싼 SharedPreferences)에 상대
 * 공개키별로 따로 넣고, trust.json에는 아무 흔적도 남기지 않는다.
 */
class TrustStore private constructor(
    private val file: File,
    private val vault: SecretVault,
) {

    private val lock = Any()
    private val peers = LinkedHashMap<String, TrustedPeer>()   // key = base64(pub)

    init { load() }

    fun state(pub: ByteArray): TrustState = synchronized(lock) {
        if (peers.containsKey(b64(pub))) TrustState.TRUSTED else TrustState.UNKNOWN
    }

    fun peer(pub: ByteArray): TrustedPeer? = synchronized(lock) { peers[b64(pub)] }

    /**
     * PIN 인증을 통과한 cert를 핀. 자기서명이 유효해야 받아들인다. 성공 시 true.
     *
     * [linkKey]는 이번 페어링이 남기는 공유 비밀이다 — 있으면 상대 신원 옆에 같이 붙는다.
     * 케이블(AOA) 부트스트랩처럼 PAKE를 거치지 않은 경로는 null로 부르고, 그때는 기존에
     * 저장돼 있던 키를 **지우지 않는다**. 신원이 같으면 예전에 사람이 확인한 그 키가 여전히
     * 유효하고, 새 근거가 없다고 해서 옛 근거를 버릴 이유는 없다.
     */
    fun trust(cert: PeriCert, linkKey: ByteArray? = null): Boolean {
        if (!cert.verifySelfSignature()) return false
        val now = System.currentTimeMillis()
        if (linkKey != null && linkKey.isNotEmpty()) vault.put(b64(cert.pub), linkKey)
        synchronized(lock) {
            val key = b64(cert.pub)
            val existing = peers[key]
            peers[key] = TrustedPeer(cert.pub, cert.name, existing?.pairedAtMillis ?: now, now)
        }
        save()
        return true
    }

    /** 이 상대와 공유하는 링크 키. 없으면 null — 페어링 전이거나, 케이블로만 만난 사이다. */
    fun linkKey(pub: ByteArray): ByteArray? = vault.get(b64(pub))

    /** 신뢰된 상대를 봤을 때 lastSeen 갱신. */
    fun touch(pub: ByteArray) {
        synchronized(lock) {
            val p = peers[b64(pub)] ?: return
            peers[b64(pub)] = p.copy(lastSeenMillis = System.currentTimeMillis())
        }
        save()
    }

    fun revoke(pub: ByteArray): Boolean {
        val removed = synchronized(lock) { peers.remove(b64(pub)) != null }
        // 신뢰를 거두면 **비밀도 같이 사라져야 한다.** 목록에서만 지우고 금고에 두면, 해제한
        // 기기와 다시 만났을 때 사람 확인 없이 되살아날 재료가 남는다.
        vault.remove(b64(pub))
        if (removed) save()
        return removed
    }

    fun all(): List<TrustedPeer> = synchronized(lock) {
        peers.values.sortedByDescending { it.lastSeenMillis }
    }

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun load() {
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            synchronized(lock) {
                peers.clear()
                for (j in 0 until arr.length()) {
                    val o = arr.getJSONObject(j)
                    val pub = Base64.decode(o.getString("pub"), Base64.NO_WRAP)
                    peers[o.getString("pub")] = TrustedPeer(
                        pub, o.getString("name"), o.getLong("pairedAt"), o.getLong("lastSeen"),
                    )
                }
            }
        } catch (e: Exception) { /* 손상 시 빈 상태로 시작 */ }
    }

    private fun save() {
        val arr = JSONArray()
        synchronized(lock) {
            for (p in peers.values) {
                arr.put(
                    JSONObject()
                        .put("pub", b64(p.pub))
                        .put("name", p.name)
                        .put("pairedAt", p.pairedAtMillis)
                        .put("lastSeen", p.lastSeenMillis),
                )
            }
        }
        try { file.writeText(arr.toString()) } catch (e: Exception) { /* best-effort */ }
    }

    companion object {
        @Volatile private var instance: TrustStore? = null

        fun get(context: Context): TrustStore =
            instance ?: synchronized(this) {
                instance ?: TrustStore(
                    File(context.applicationContext.filesDir, "trust.json"),
                    SecretVault(context, "peri_link_keys"),
                ).also { instance = it }
            }
    }
}
