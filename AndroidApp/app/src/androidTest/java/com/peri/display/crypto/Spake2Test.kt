package com.peri.display.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SPAKE2+EE 왕복 — **계기 테스트여야 한다.** JNI와 libsodium이 걸려 있어 JVM 단위 테스트로는
 * 못 돈다(`.so`가 없다).
 *
 * 여기서 확인하는 건 셋이다:
 *  1. PIN이 같으면 양쪽이 **같은 키 두 개**에 도달한다.
 *  2. PIN이 다르면 도달하지 못한다 — 그게 이 프로토콜을 들여온 이유 전부다.
 *  3. 신원 지문(clientId/serverId)이 어긋나도 못 한다 — 중간자가 인증서를 바꿔치는 경우다.
 */
@RunWith(AndroidJUnit4::class)
class Spake2Test {

    private val macId = "mac-fingerprint".toByteArray()
    private val androidId = "android-fingerprint".toByteArray()

    /** 전 과정을 돌린다. 성공하면 (server 키, client 키). */
    private fun round(
        serverPin: String,
        clientPin: String,
        clientId: ByteArray = macId,
        serverId: ByteArray = androidId,
    ): Pair<SpakeKeys, SpakeKeys>? {
        val server = Spake2.server(serverPin) ?: return null
        try {
            val (client, response1) = Spake2.client(server.publicData, clientPin) ?: return null
            try {
                val response2 = server.step2(response1, clientId, serverId) ?: return null
                val (response3, clientKeys) =
                    client.finish(response2, macId, androidId) ?: return null
                val serverKeys = server.step4(response3) ?: return null
                return serverKeys to clientKeys
            } finally {
                client.close()
            }
        } finally {
            server.close()
        }
    }

    @Test fun 같은_PIN이면_같은_키에_도달한다() {
        val (serverKeys, clientKeys) = requireNotNull(round("4821", "4821"))
        assertArrayEquals(serverKeys.clientSk, clientKeys.clientSk)
        assertArrayEquals(serverKeys.serverSk, clientKeys.serverSk)
        // 방향별 키는 서로 달라야 한다 — 같으면 양방향이 같은 키를 쓰게 된다.
        assertFalse(serverKeys.clientSk.contentEquals(serverKeys.serverSk))
        assertEquals(Spake2.SHARED_KEY_BYTES, serverKeys.clientSk.size)
    }

    @Test fun 다른_PIN이면_실패한다() {
        // 한 자리만 달라도 끝이다. 어느 단계에서 죽든 상관없다 — 키에 도달하지만 않으면 된다.
        assertNull(round("4821", "4822"))
    }

    @Test fun 신원_지문이_어긋나면_실패한다() {
        // 중간자가 인증서를 바꿔친 경우. PIN은 맞지만 id가 다르므로 검증이 깨진다.
        assertNull(round("4821", "4821", clientId = "someone-else".toByteArray()))
    }

    @Test fun 공개_파라미터는_우리가_박은_값이어야_한다() {
        val server = requireNotNull(Spake2.server("4821"))
        try {
            assertEquals(Spake2.PUBLIC_DATA_BYTES, server.publicData.size)
            assertTrue(Spake2.accepts(server.publicData))

            // memlimit 자리를 키운 것 — 상대가 우리를 메모리로 죽이려는 경우다.
            val tampered = server.publicData.copyOf()
            tampered[19] = (tampered[19] + 1).toByte()
            assertFalse(Spake2.accepts(tampered))

            assertFalse(Spake2.accepts(ByteArray(Spake2.PUBLIC_DATA_BYTES)))
            assertFalse(Spake2.accepts(ByteArray(0)))
        } finally {
            server.close()
        }
    }

    @Test fun 라운드마다_다른_키가_나온다() {
        // 같은 PIN이어도 임시값이 새로 뽑히므로 세션 키가 재사용되면 안 된다.
        val a = requireNotNull(round("4821", "4821")).first
        val b = requireNotNull(round("4821", "4821")).first
        assertFalse(a.clientSk.contentEquals(b.clientSk))
    }

    @Test fun 조각_길이가_스펙과_같다() {
        val server = requireNotNull(Spake2.server("4821"))
        try {
            val (client, response1) =
                requireNotNull(Spake2.client(server.publicData, "4821"))
            try {
                assertEquals(Spake2.RESPONSE1_BYTES, response1.size)
                val response2 =
                    requireNotNull(server.step2(response1, macId, androidId))
                assertEquals(Spake2.RESPONSE2_BYTES, response2.size)
                val (response3, _) =
                    requireNotNull(client.finish(response2, macId, androidId))
                assertEquals(Spake2.RESPONSE3_BYTES, response3.size)
                assertNotNull(server.step4(response3))
            } finally {
                client.close()
            }
        } finally {
            server.close()
        }
    }
}
