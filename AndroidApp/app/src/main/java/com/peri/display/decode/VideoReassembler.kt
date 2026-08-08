package com.peri.display.decode

import com.peri.display.transport.MiniRtpPacket
import java.io.ByteArrayOutputStream

/**
 * Mini-RTP 프래그먼트를 frame_id별로 재조립한다. frag_count가 모두 모이면 완성 프레임 반환.
 * 일부 손실로 미완성인 프레임은 타임아웃 후 폐기(드롭).
 */
class VideoReassembler(private val timeoutMs: Long = 500L) {

    data class CompleteFrame(
        val frameId: Long,
        val keyframe: Boolean,
        val isLtr: Boolean,
        val payloadType: Int,
        val timestampMs: Long,
        val body: ByteArray,
    )

    private class Pending(
        val total: Int, val keyframe: Boolean, val isLtr: Boolean,
        val payloadType: Int, val timestampMs: Long, val createdAt: Long,
    ) {
        val chunks = arrayOfNulls<ByteArray>(total)
        var count = 0
    }

    private val pending = HashMap<Long, Pending>()

    fun offer(p: MiniRtpPacket): CompleteFrame? {
        if (p.fragCount <= 1) {
            return CompleteFrame(p.frameId, p.keyframe, p.isLtr, p.payloadType, p.timestampMs, p.chunk)
        }
        prune(System.currentTimeMillis())

        val entry = pending.getOrPut(p.frameId) {
            Pending(p.fragCount, p.keyframe, p.isLtr, p.payloadType, p.timestampMs,
                    System.currentTimeMillis())
        }
        if (p.fragIndex in 0 until entry.total && entry.chunks[p.fragIndex] == null) {
            entry.chunks[p.fragIndex] = p.chunk
            entry.count++
        }
        if (entry.count == entry.total) {
            pending.remove(p.frameId)
            val out = ByteArrayOutputStream()
            for (c in entry.chunks) if (c != null) out.write(c)
            return CompleteFrame(p.frameId, entry.keyframe, entry.isLtr,
                                 entry.payloadType, entry.timestampMs, out.toByteArray())
        }
        return null
    }

    fun clear() = pending.clear()

    private fun prune(now: Long) {
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.createdAt > timeoutMs) it.remove()
        }
    }
}
