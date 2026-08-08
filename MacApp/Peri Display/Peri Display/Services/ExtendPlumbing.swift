//
//  ExtendPlumbing.swift
//  Peri Display
//
//  **확장 앱의 배관 조각들.** 프레임 번호, LTR 매핑, 인코더 홀더, 적응 비트레이트,
//  적응 fps, 바이트 계량, 지연 스택 — 전부 "Mac 화면을 구워서 보낸다"의 사정이다.
//
//  왜 옮겼나: 이 클래스들은 `MirrorSession.swift`에 살았다. 세션이 곧 미러링이던 시절엔
//  자연스러웠지만, 지금은 **원칙 1에 대한 반례**다 — "세션 계층 소스에 display|video|
//  capture|file이 없다"를 grep으로 검사하는데, 이 조각들 때문에 그 검사가 늘 붉게 나왔다.
//  세션은 이것들을 하나도 안 쓴다(전부 `ExtendService`와 그 인코더 콜백이 쓴다).
//
//  ⚠ `U16Counter`·`UDPPacer`는 **여기 없다.** 그 둘은 "어느 파이프로 나가는가"의 문제라
//  `ServiceChannel`이 소유한다 — 확장이 사라져도 남을 것들이다.
//

import Foundation

/// 영상 seq 카운터 (frame 번호용, GCM nonce 카운터와 별개).
nonisolated final class SeqCounter: @unchecked Sendable {
    private var v: UInt32 = 0
    private let lock = NSLock()
    func next() -> UInt32 {
        lock.lock(); defer { lock.unlock() }
        let s = v; v = (v &+ 1) & 0x00FF_FFFF
        return s
    }
}

/// frame_id → LTR ack 토큰 매핑 (Android ACK 수신 시 토큰 조회).
nonisolated final class LtrMap: @unchecked Sendable {
    private var m: [UInt32: Int64] = [:]
    private let lock = NSLock()
    func put(_ frameId: UInt32, _ token: Int64) {
        lock.lock(); if m.count > 256 { m.removeAll() }; m[frameId] = token; lock.unlock()
    }
    func take(_ frameId: UInt32) -> Int64? {
        lock.lock(); defer { lock.unlock() }; return m.removeValue(forKey: frameId)
    }
}


/// 파이프라인 구성 후 채워지는 인코더 홀더 (LAN 큐의 nonisolated 핸들러에서 접근).
nonisolated final class EncoderBox: @unchecked Sendable {
    private let lock = NSLock()
    private var _e: VideoEncoder?
    var encoder: VideoEncoder? {
        get { lock.lock(); defer { lock.unlock() }; return _e }
        set { lock.lock(); _e = newValue; lock.unlock() }
    }
}


/// 지연 인식형 적응 비트레이트. 수신측이 *고정* 지터버퍼를 두므로, 이 컨트롤러의 목표는
/// 실제 큐잉지연 q를 그 버퍼 예산(50ms) 안에 유지하는 것 — q≤buffer면 프레임 온타임(hitch/펄싱 없음).
///   · 손실>5% → ×0.85 감속(감속은 손실만 트리거 — 지연 기반 감속은 제거함).
///   · q<LOW 그리고 손실<1% → 증속(품질↑). 상한 70% 미만 빠른 램프, 근접 시 미세.
///     (지연은 증속을 게이트만 함 — 큐 쌓이면 더 안 올림. 감속은 안 함.)
///   ※ LOW는 Android 고정 버퍼(UI 슬라이더)와 커플링(LOW=0.36×). 지연 흡수는 고정 버퍼 몫.
///   ※ 신호(queueDelayMs)는 이미 EWMA 평균이라 innocent 지터가 버퍼로 흡수됨 → 절대 임계로 충분
///     (peak-hold 시절 절대 임계가 박히던 문제는 EWMA 신호 + 버퍼 흡수로 해소).
nonisolated final class BitrateController: @unchecked Sendable {
    private let lock = NSLock()
    private var current = 0
    private var maxK = 0          // 품질 상한(ceiling)
    private var minK = 0
    private var lastLoss = 0.0
    private var lastQDelayMs = 0
    private var highMs = 33   // q 이 이상 = 버퍼 예산 잠식 → 감속 (Android 버퍼 대비 헤드룸)
    private var lowMs = 18    // q 이 미만 = 예산 여유 → 증속
    // AOA 포화 가드: 신뢰 파이프라 손실 신호가 없어 큐잉지연이 유일한 혼잡 신호. 정상 AOA q~0-2ms
    // 훨씬 위(40ms)를 넘을 때만 감속 → 불량 케이블(USB Full-Speed 12Mbps) 등 파이프 포화 대비.
    // LAN(delayGuard=false)은 현행 유지(지연 기반 감속 없음, 고정 버퍼가 지터 흡수).
    private var delayGuard = false
    private let aoaGuardHighMs = 40
    private let aoaGuardLowMs = 12

    /// Android 고정 버퍼(ms)에 맞춰 q 유지 목표대를 갱신 — UI 슬라이더와 커플링(예산 밑 헤드룸).
    func setBudget(bufferMs: Int) {
        lock.lock()
        highMs = max(8, Int(Double(bufferMs) * 0.75))   // 예산의 ~3/4 (지연 감속은 관용적)
        lowMs = max(4, Int(Double(bufferMs) * 0.36))    // 예산의 ~1/3
        lock.unlock()
    }

    /// buildPipeline에서 (재)설정. start=초기 동작점(보수적), ceiling=품질 상한.
    func configure(initialKbps: Int, ceilingKbps: Int, delayGuard: Bool) {
        lock.lock()
        current = initialKbps
        maxK = max(initialKbps, ceilingKbps)
        minK = max(1_000, initialKbps / 4)
        lastLoss = 0; lastQDelayMs = 0
        self.delayGuard = delayGuard
        lock.unlock()
    }

    /// 1초 피드백마다: 손실률(0~1) + 수신측 큐잉지연(ms)으로 비트레이트 조정. 바뀌면 새 kbps.
    func onFeedback(loss: Double, queueDelayMs: Int) -> Int? {
        lock.lock(); defer { lock.unlock() }
        guard maxK > 0 else { return nil }
        lastLoss = loss; lastQDelayMs = queueDelayMs
        let old = current
        if loss > 0.05 {                                        // 손실=하드 신호 → 감속
            current = max(minK, Int(Double(current) * 0.85))
        } else if delayGuard && queueDelayMs > aoaGuardHighMs {  // AOA: 손실 신호 없음 → 큐 급증(포화)만 감속
            current = max(minK, Int(Double(current) * 0.85))
        } else if loss < 0.01 && queueDelayMs < (delayGuard ? aoaGuardLowMs : lowMs) {  // 여유 → 증속(품질↑)
            current = current < (maxK * 7) / 10
                ? min(maxK, Int(Double(current) * 1.15))        // 상한 70% 미만: 빠른 램프
                : min(maxK, current + max(1, maxK / 20))        // 근접: 미세 덧셈
        }
        return current != old ? current : nil
    }

    var snapshot: (targetKbps: Int, maxKbps: Int, lossPct: Int) {
        lock.lock(); defer { lock.unlock() }
        return (current, maxK, Int((lastLoss * 100).rounded()))
    }
}

/// 적응형 캡처 fps: Mac 인코드 지연(macEnc=캡처PTS→인코드완료 EWMA)으로 인코더 포화를 감지해
/// 캡처 fps를 AIMD로 조절. 120fps 협상이라도 이 해상도에서 HW 인코더 프레임당 비용(~9ms)이
/// 지속가능 상한을 정하므로, 풀모션 시 프레임레이트가 상한까지 치솟으면 인코드 큐가 밀려 macEnc가
/// 폭증(9→50ms) → 끊김. 포화 시 낮추고 회복 시 협상 fps까지 되올린다 (Mac 로컬 루프, 무피드백;
/// 캡처단 minimumFrameInterval 라이브 교체 + 인코더 ExpectedFrameRate 동기).
/// ⚠️ HW 미디어 엔진은 **모든 세션이 공유**하는 자원이다. 세션마다 독립 컨트롤러를 두면 포화 시
/// 둘 다 동시에 감속(0.8×0.8=0.64)했다가 여유가 생겨 둘 다 증속 → 재포화를 반복하며 진동한다
/// (실측: 2대 연결 시 fps가 48↔88을 계속 왕복). 따라서 컨트롤러는 **전역 1개**만 두고,
/// 공통 배율(scale)을 각 세션이 자기 협상 fps에 곱해 쓴다.
nonisolated final class FpsGovernor: @unchecked Sendable {
    static let shared = FpsGovernor()

    private let lock = NSLock()
    private var encMsEwma = 0.0        // macEnc 평활 평균(ms) — 모든 세션이 함께 기여
    private var scale = 1.0            // 전역 배율: 각 세션 fps = 협상fps × scale
    private let minScale = 0.25
    private let highMs = 20.0          // 이 이상 = 인코더 큐 쌓임(바닥 ~9ms의 2배+) → 감속
    private let lowMs = 12.0           // 이 미만 = 헤드룸 → 증속
    private let stepInterval: TimeInterval = 0.5
    private var lastStepAt = Date.distantPast
    private var enabled = true         // High Quality 프로파일이면 false(배율 1.0 고정)
    private var framesSinceStep = 0
    private var goodStreak = 0
    /// 스텝 최소 표본. observe는 *프레임당* 호출이므로, 정적 화면(프레임 희소)에선 프레임이 올 때마다
    /// 시간 게이트가 이미 만료돼 매 프레임 스텝 → fps가 무의미하게 진동한다. 프레임이 이만큼 모여야
    /// 스텝: 프레임이 드물면 캡이 병목이 아니므로 올리든 내리든 의미가 없다.
    private let minSamples = 6
    /// 증속 전 연속 양호 윈도우 수 — 감속은 즉시, 증속은 신중(진동 억제).
    private let goodStreakToRaise = 2

    /// 현재 배율.
    var currentScale: Double { lock.lock(); defer { lock.unlock() }; return scale }

    /// 적응 활성/비활성 (Low Latency=활성, High Quality=비활성 → 상한 고정).
    func setEnabled(_ on: Bool) {
        lock.lock()
        enabled = on
        if !on { scale = 1.0; encMsEwma = 0; goodStreak = 0 }
        lock.unlock()
    }

    /// onEncoded에서 매 프레임 호출(인코드 스레드). 배율이 바뀌면 새 값 반환(적용은 호출측).
    /// 신호는 전 세션 공유 — 어느 세션이든 인코더가 밀리면 전체가 함께 물러난다.
    func observe(encMs: Double) -> Double? {
        lock.lock(); defer { lock.unlock() }
        guard enabled else { return nil }
        encMsEwma += 0.2 * (encMs - encMsEwma)
        framesSinceStep += 1
        let now = Date()
        guard now.timeIntervalSince(lastStepAt) >= stepInterval, framesSinceStep >= minSamples
        else { return nil }
        lastStepAt = now
        framesSinceStep = 0
        let old = scale
        if encMsEwma > highMs {                       // 포화 → 즉시 곱셈 감속
            scale = max(minScale, scale * 0.8)
            goodStreak = 0
        } else if encMsEwma < lowMs && scale < 1.0 {  // 여유 → 연속 확인 후 완만 증속
            goodStreak += 1
            if goodStreak >= goodStreakToRaise {
                goodStreak = 0
                scale = min(1.0, scale + 0.08)
            }
        } else {
            goodStreak = 0
        }
        return abs(scale - old) > 0.001 ? scale : nil
    }
}

/// 인코딩 바이트 누적 → 측정 비트레이트 산출 (인코드 스레드에서 add, 통계 타이머가 take).
nonisolated final class ByteMeter: @unchecked Sendable {
    private let lock = NSLock()
    private var bytes = 0
    func add(_ n: Int) { lock.lock(); bytes += n; lock.unlock() }
    func take() -> Int { lock.lock(); defer { lock.unlock() }; let b = bytes; bytes = 0; return b }
}

/// 지연 스택 구간값 홀더. enc는 인코드 스레드가(로컬 측정), net/jbuf/dec/rtt는 수신측 피드백이
/// LAN 큐에서 쓴다. 통계 타이머(MainActor)가 1Hz로 읽어 UI에 싣는다 → 락 보호.
/// enc는 프레임마다 튀므로 EWMA로 평활(수신측 값은 이미 1초 평균).
nonisolated final class LatencyBox: @unchecked Sendable {
    private let lock = NSLock()
    private var encEwma = 0.0
    private var net = 0, jbuf = 0, dec = 0, rtt = 0

    func observeEnc(ms: Double) {
        lock.lock()
        encEwma += (encEwma == 0 ? 1.0 : 0.2) * (ms - encEwma)
        lock.unlock()
    }

    func setRemote(net: Int, jbuf: Int, dec: Int, rtt: Int) {
        lock.lock()
        self.net = net; self.jbuf = jbuf; self.dec = dec; self.rtt = rtt
        lock.unlock()
    }

    var snapshot: (enc: Int, net: Int, jbuf: Int, dec: Int, rtt: Int) {
        lock.lock(); defer { lock.unlock() }
        return (Int(encEwma.rounded()), net, jbuf, dec, rtt)
    }
}
