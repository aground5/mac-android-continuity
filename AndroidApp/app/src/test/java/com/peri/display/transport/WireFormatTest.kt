package com.peri.display.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 와이어 포맷 불변식.
 *
 * 이 파일이 생긴 이유: [FrameAssembler]가 "도착할 때마다 배열을 새로 만드는" 방식에서
 * **채워 쓰는 버퍼 + 인덱스**로 바뀌었다. 성능은 나아졌지만 인덱스 산술이 들어왔고,
 * 그 종류의 실수는 조용하다 — 프레임 한 장이 한 바이트 밀려도 복호가 실패할 뿐이라
 * 로그에는 "복호 실패"만 뜨고 원인은 어디에도 안 보인다.
 *
 * 그래서 **선에 나가는 바이트를 못박는다.** 성능을 고치다 포맷을 바꾸면 상대(Mac)가
 * 못 읽는데, 그건 실기기에 올려 봐야만 드러난다.
 */
class WireFormatTest {

    // MARK: 굽기 — 바이트가 그대로여야 한다

    @Test
    fun `제어 프레임은 길이·타입·nonce·본문 순이다`() {
        val nonce = ByteArray(NONCE_LEN) { (it + 1).toByte() }
        val body = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val f = WireFormat.controlFrame(nonce, body)

        assertEquals(4 + 1 + NONCE_LEN + body.size, f.size)
        assertEquals(1 + NONCE_LEN + body.size, be32(f, 0))   // 길이 = 타입 + payload
        assertEquals(WireType.CONTROL.raw, f[4].toInt())
        assertArrayEquals(nonce, f.copyOfRange(5, 5 + NONCE_LEN))
        assertArrayEquals(body, f.copyOfRange(5 + NONCE_LEN, f.size))
    }

    @Test
    fun `영상 프레임은 헤더 뒤에 nonce 자리를 0으로 비워 둔다`() {
        // nonce는 frame_id에서 유도되므로 싣지 않는다. 그래도 자리를 남기는 건 프레임
        // 모양을 방향마다 다르게 만들지 않기 위해서다 — 그 약속을 여기서 못박는다.
        val header = VideoHeader(keyframe = true, codec = 1, seq = 0x123456,
                                 timestampMs = 0x0A0B0C0D, fragIndex = 0, fragTotal = 1)
        val body = ByteArray(300) { it.toByte() }
        val f = WireFormat.videoFrame(header, body)

        assertEquals(4 + 1 + VideoHeader.BYTE_COUNT + NONCE_LEN + body.size, f.size)
        assertEquals(WireType.VIDEO.raw, f[4].toInt())
        assertArrayEquals(header.encode(), f.copyOfRange(5, 5 + VideoHeader.BYTE_COUNT))
        val nonceAt = 5 + VideoHeader.BYTE_COUNT
        assertArrayEquals(ByteArray(NONCE_LEN), f.copyOfRange(nonceAt, nonceAt + NONCE_LEN))
        assertArrayEquals(body, f.copyOfRange(nonceAt + NONCE_LEN, f.size))
    }

    @Test
    fun `헤더는 제자리로 구워도 따로 구운 것과 같다`() {
        val h = VideoHeader(keyframe = false, codec = 2, seq = 0xFFFFFF,
                            timestampMs = 0xFFFFFFFFL, fragIndex = 7, fragTotal = 9)
        val whole = ByteArray(4 + VideoHeader.BYTE_COUNT)
        h.encodeInto(whole, 4)
        assertArrayEquals(h.encode(), whole.copyOfRange(4, whole.size))
        assertEquals(h, VideoHeader.decode(whole, 4))
    }

    @Test
    fun `HELLO 데이터그램은 타입·채널id·nonce·봉인 순이다`() {
        val id = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(NONCE_LEN) { (0x40 + it).toByte() }
        val body = byteArrayOf(1, 2, 3)
        val d = WireFormat.udpHelloDatagram(id, nonce, body)

        assertEquals(UdpType.HELLO.raw, d[0].toInt())
        assertArrayEquals(id, d.copyOfRange(1, 17))
        assertArrayEquals(nonce, d.copyOfRange(17, 17 + NONCE_LEN))
        assertArrayEquals(body, d.copyOfRange(17 + NONCE_LEN, d.size))
    }

    // MARK: 타입 조회

    @Test
    fun `타입 조회는 표에서 나오고 범위 밖은 null이다`() {
        for (t in WireType.entries) assertEquals(t, WireType.from(t.raw))
        for (t in UdpType.entries) assertEquals(t, UdpType.from(t.raw))
        assertNull(WireType.from(99))
        assertNull(WireType.from(-1))     // 표 인덱싱이 음수에서 터지지 않아야 한다
        assertNull(UdpType.from(2))       // ICE 프로브 타입 — 영상 파서가 물러나는 근거
    }

    // MARK: 재조립 — 어떻게 쪼개져 와도 결과가 같아야 한다

    @Test
    fun `한 번에 온 프레임을 그대로 꺼낸다`() {
        val nonce = ByteArray(NONCE_LEN) { 7 }
        val body = ByteArray(64) { it.toByte() }
        val a = FrameAssembler()
        a.append(WireFormat.controlFrame(nonce, body), 4 + 1 + NONCE_LEN + body.size)

        val f = a.next() as WireFrame.Control
        assertArrayEquals(nonce, f.nonce)
        assertArrayEquals(body, f.body)
        assertNull("하나뿐이었다", a.next())
    }

    @Test
    fun `한 바이트씩 와도 같은 프레임이 나온다`() {
        // TCP는 어디서든 쪼갤 수 있다. 인덱스 산술의 실수는 대개 여기서 드러난다.
        val body = ByteArray(200) { (it * 3).toByte() }
        val wire = WireFormat.controlFrame(ByteArray(NONCE_LEN) { 1 }, body)
        val a = FrameAssembler()
        val one = ByteArray(1)
        for (i in wire.indices) {
            assertNull("아직 다 안 왔다", a.next())
            one[0] = wire[i]
            a.append(one, 1)
        }
        assertArrayEquals(body, (a.next() as WireFrame.Control).body)
    }

    @Test
    fun `한 번에 여러 프레임이 와도 순서대로 꺼낸다`() {
        val a = FrameAssembler()
        val parts = (1..3).map { n ->
            WireFormat.controlFrame(ByteArray(NONCE_LEN) { n.toByte() }, ByteArray(n) { n.toByte() })
        }
        val joined = parts.reduce { x, y -> x + y }
        a.append(joined, joined.size)

        for (n in 1..3) {
            val f = a.next() as WireFrame.Control
            assertEquals(n, f.body.size)
            assertEquals(n.toByte(), f.body[0])
        }
        assertNull(a.next())
    }

    @Test
    fun `처음 버퍼보다 큰 프레임도 받는다`() {
        // USB 영상이 같은 파이프를 탄다 — 키프레임은 수백 KB다. 버퍼가 자라야 하고,
        // 자라는 과정에서 이미 모인 바이트가 하나도 어긋나면 안 된다.
        val body = ByteArray(300_000) { (it % 251).toByte() }
        val wire = WireFormat.videoFrame(
            VideoHeader(true, 1, 42, 1000, 0, 1), body,
        )
        val a = FrameAssembler()
        var off = 0
        val chunk = 16 * 1024
        while (off < wire.size) {
            val n = minOf(chunk, wire.size - off)
            a.append(wire.copyOfRange(off, off + n), n)
            off += n
        }
        val f = a.next() as WireFrame.Video
        assertEquals(42, f.header.seq)
        assertTrue(f.header.keyframe)
        assertArrayEquals(body, f.body)
    }

    @Test
    fun `프레임을 계속 흘려도 어긋나지 않는다`() {
        // 버퍼를 재사용하며 head·tail을 앞으로 되돌리는 경로. 한 번 어긋나면 그 뒤로
        // 전부 밀리므로, 마지막까지 맞는지가 곧 압축이 옳다는 증거다.
        val a = FrameAssembler()
        for (i in 0 until 500) {
            val body = ByteArray(1 + (i % 97)) { i.toByte() }
            val wire = WireFormat.controlFrame(ByteArray(NONCE_LEN) { i.toByte() }, body)
            a.append(wire, wire.size)
            val f = a.next() as WireFrame.Control
            assertEquals("i=$i", body.size, f.body.size)
            assertEquals("i=$i", i.toByte(), f.body[0])
            assertEquals("i=$i", i.toByte(), f.nonce[0])
        }
    }

    @Test
    fun `모르는 타입도 프레임 하나만큼 건너뛴다`() {
        // 소비를 빠뜨리면 그 자리에서 스트림이 영원히 멎는다 — 다음 프레임을 절대 못 읽는다.
        val a = FrameAssembler()
        val unknown = byteArrayOf(0, 0, 0, 3, 9, 1, 2)   // len=3, type=9(모름), payload 2B
        a.append(unknown, unknown.size)
        val good = WireFormat.controlFrame(ByteArray(NONCE_LEN) { 5 }, byteArrayOf(42))
        a.append(good, good.size)

        assertNull("모르는 타입은 버린다", a.next())
        assertEquals(42, (a.next() as WireFrame.Control).body[0].toInt())
    }

    @Test
    fun `길이가 0인 프레임도 건너뛴다`() {
        val a = FrameAssembler()
        val empty = byteArrayOf(0, 0, 0, 0)
        a.append(empty, empty.size)
        val good = WireFormat.controlFrame(ByteArray(NONCE_LEN) { 5 }, byteArrayOf(7))
        a.append(good, good.size)

        assertNull(a.next())
        assertEquals(7, (a.next() as WireFrame.Control).body[0].toInt())
    }

    @Test
    fun `음수 길이는 스트림을 버리고 멈춘다`() {
        // 손상된 스트림. 그냥 진행하면 `head + total`이 음수가 되어 인덱싱이 터진다.
        val a = FrameAssembler()
        val broken = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 1)
        a.append(broken, broken.size)
        assertNull(a.next())
        assertNull("버린 뒤에도 멀쩡히 살아 있어야 한다", a.next())
    }

    @Test
    fun `너무 짧은 제어 payload는 프레임을 삼키지 않는다`() {
        val a = FrameAssembler()
        val short = byteArrayOf(0, 0, 0, 4, WireType.CONTROL.raw.toByte(), 1, 2, 3)  // nonce 미만
        a.append(short, short.size)
        val good = WireFormat.controlFrame(ByteArray(NONCE_LEN) { 5 }, byteArrayOf(11))
        a.append(good, good.size)

        assertNull(a.next())
        assertEquals(11, (a.next() as WireFrame.Control).body[0].toInt())
    }

    // MARK: UDP 파싱 — 재사용 버퍼

    @Test
    fun `재사용 버퍼에서도 이 패킷의 길이만 읽는다`() {
        // ★ 수신 버퍼는 재사용된다. `d.size`로 자르면 지난 패킷의 잔해가 청크에 딸려 온다.
        val body = ByteArray(50) { (it + 1).toByte() }
        val packets = WireFormat.packetizeVideo(
            frameId = 9, keyframe = true, payloadType = 1, timestampMs = 77,
            body = body, nextPacketSeq = { 5 },
        )
        assertEquals(1, packets.size)

        val recvBuf = ByteArray(2048) { 0xEE.toByte() }   // 잔해로 가득 찬 버퍼
        packets[0].copyInto(recvBuf, 0)

        val p = WireFormat.parseUdpVideo(recvBuf, packets[0].size)!!
        assertEquals(9L, p.frameId)
        assertEquals(5, p.packetSeq)
        assertEquals(77L, p.timestampMs)
        assertTrue(p.keyframe)
        assertArrayEquals(body, p.chunk)
    }

    @Test
    fun `머리보다 짧은 데이터그램은 버린다`() {
        assertNull(WireFormat.parseUdpVideo(ByteArray(2048), length = 4))
    }

    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
}
