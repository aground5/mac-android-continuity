//
//  VideoReassembler.swift
//  Peri Display
//
//  조각난 영상 프레임을 다시 붙인다. Android의 `VideoReassembler.kt`와 같은 규칙이다 —
//  **양쪽이 같은 판단을 해야** 한쪽만 프레임을 버리는 일이 없다.
//
//  왜 Mac에 이제야 생겼나: v0.1에서 Mac은 보내기만 했다. 화면 공유(앱 #2)가 방향을 뒤집으면서
//  받는 쪽 배관이 처음 필요해졌다.
//
//  규칙 셋
//  ──────
//   ① 조각이 하나라도 빠지면 **프레임을 통째로 버린다.** 부분 프레임을 디코더에 먹이면
//      복구가 아니라 오염이다 — 그 뒤 참조 프레임이 전부 깨진다.
//   ② 늦게 온 조각은 버린다. 이미 넘긴 프레임을 다시 만들 이유가 없다.
//   ③ 미완성 프레임은 오래 붙들지 않는다. 손실이 나면 그 프레임은 영원히 안 차므로,
//      상한을 두지 않으면 메모리가 샌다.
//

import Foundation

nonisolated final class VideoReassembler: @unchecked Sendable {

    private struct Entry {
        var chunks: [Data?]
        var have = 0
        let total: Int
        let keyframe: Bool
    }

    private let lock = NSLock()
    private var pending: [UInt32: Entry] = [:]
    /// 이미 완성해 넘긴 프레임 중 가장 최근 번호 — 늦은 조각을 가려낸다.
    private var lastDelivered: UInt32?

    /// 동시에 붙들 프레임 수 상한. 손실이 나면 미완성 항목이 영영 안 차기 때문에 필요하다.
    /// 프레임 간격 × 이 값이 곧 "조각이 늦게 와도 봐 주는 시간"이다.
    private static let maxPending = 8

    /// 조각 하나를 넣는다. 프레임이 완성되면 그 전체를, 아니면 nil을 돌려준다.
    func accept(_ f: MediaFragment) -> Data? {
        lock.lock(); defer { lock.unlock() }

        // 조각이 하나뿐이면 붙일 게 없다 — AOA(신뢰 파이프)가 늘 이 경로다.
        if f.fragTotal <= 1 {
            lastDelivered = f.frameId
            return f.body
        }

        // 이미 넘긴 프레임의 늦은 조각 — 버린다.
        if let last = lastDelivered, seqIsOlder(f.frameId, than: last) { return nil }

        var entry = pending[f.frameId]
            ?? Entry(chunks: Array(repeating: nil, count: Int(f.fragTotal)),
                     total: Int(f.fragTotal), keyframe: f.keyframe)

        let idx = Int(f.fragIndex)
        guard idx >= 0, idx < entry.total, entry.chunks[idx] == nil else {
            pending[f.frameId] = entry
            return nil   // 범위 밖이거나 중복
        }
        entry.chunks[idx] = f.body
        entry.have += 1

        guard entry.have == entry.total else {
            pending[f.frameId] = entry
            evictIfNeeded(keeping: f.frameId)
            return nil
        }

        pending.removeValue(forKey: f.frameId)
        lastDelivered = f.frameId
        // 완성된 프레임보다 오래된 미완성 항목은 이제 영영 안 찬다 — 같이 버린다.
        pending = pending.filter { !seqIsOlder($0.key, than: f.frameId) }

        var whole = Data()
        whole.reserveCapacity(entry.chunks.reduce(0) { $0 + ($1?.count ?? 0) })
        for c in entry.chunks { if let c { whole.append(c) } }
        return whole
    }

    func clear() {
        lock.lock(); pending.removeAll(); lastDelivered = nil; lock.unlock()
    }

    /// 상한을 넘으면 가장 오래된 미완성 프레임부터 버린다.
    private func evictIfNeeded(keeping current: UInt32) {
        guard pending.count > Self.maxPending else { return }
        // frame_id는 wrap하므로 단순 최소값이 아니라 "현재에서 가장 먼 과거"를 고른다.
        let victim = pending.keys.min { distanceBack(from: current, to: $0) > distanceBack(from: current, to: $1) }
        if let victim { pending.removeValue(forKey: victim) }
    }

    /// 32비트 wrap 안에서 a가 b보다 과거인가.
    ///
    /// 왜 32비트인가: 조각화가 일어나는 건 UDP 경로뿐이고 거기 실리는 frame_id는 Mini-RTP
    /// 헤더의 32비트 값 그대로다. AOA는 `VideoHeader`의 24비트 seq를 쓰지만 조각이 하나뿐이라
    /// 이 비교를 타지 않는다 — 그리고 24비트 카운터가 도는 순간(0xFFFFFF → 0)도 32비트
    /// 거꾸로-거리로 보면 "아주 먼 과거"가 아니라 "바로 다음"으로 읽혀 판정이 맞는다.
    private func seqIsOlder(_ a: UInt32, than b: UInt32) -> Bool {
        distanceBack(from: b, to: a) < 0x8000_0000 && a != b
    }

    /// now에서 past까지 거꾸로 센 거리(32비트 wrap).
    private func distanceBack(from now: UInt32, to past: UInt32) -> UInt32 {
        now &- past
    }
}
