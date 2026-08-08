//
//  ServiceChannel.swift
//  Peri Display
//
//  **서비스가 세션에 대해 아는 전부.**
//
//  지금까지는 화면 미러링이 곧 세션이어서, 인코더 콜백이 `sessionBox.session.sender`와
//  `server`와 `udpPeerBox`를 직접 붙잡고 있었다. 서비스가 늘어나면 그 손이 세 갈래, 네 갈래로
//  늘어나고, 그때부터 "세션 계층"이라는 말은 의미를 잃는다.
//
//  그래서 서비스가 세션에게 요구하는 것만 한 손잡이로 모은다 — **봉인하고, 전달 계약에 맞는
//  경로로 내보내는 것.** 서비스는 TCP도 UDP도 bulk도 모른다. "반드시 도착해야 한다"와
//  "최신이면 된다"만 말한다(DESIGN.md §1).
//
//  B단계(와이어 v2)에서 Mux가 들어오면 **이 파일 안쪽만 바뀐다** — serviceId·AAD·epoch·일반
//  조각화가 여기로 들어오고 호출부는 그대로다. 그게 이 손잡이를 지금 만드는 이유다.
//

import Foundation
import SwiftProtobuf

/// Bearer가 선언하는 능력. 서비스는 이 값만 보고 행동을 조절한다(DESIGN.md §1).
nonisolated struct BearerCaps: Sendable {
    /// 손실 허용 경로가 **실제로** 있는가. AOA는 신뢰 파이프 하나뿐이라 false.
    let hasDatagramPath: Bool
    /// 최신성 경로에 한 번에 실을 수 있는 최대 바이트. LAN은 MTU에 매이고, AOA는 사실상 무한이라
    /// 조각화가 아예 일어나지 않는다 — 지금 AOA 경로가 통짜로 보내는 동작 그대로다.
    let maxMessageSize: Int
    /// 손실도 혼잡도 구조적으로 없는 전송인가(AOA). 서비스는 이 값으로 두 가지를 가른다:
    /// 초기 동작점을 곧장 상한으로 올릴지, 그리고 적응 컨트롤러가 손실 대신 큐잉지연만 보고
    /// 감속할지.
    ///
    /// **서비스가 아니라 채널이 답해야 하는 질문이다.** 서비스는 기기에 매여 있어 LAN↔AOA
    /// 전환을 그대로 살아남는데, 이 값을 서비스가 붙들고 있으면 케이블을 꽂은 뒤에도 옛 전송의
    /// 상한으로 협상한다.
    let reliableStream: Bool
}

/// 최신성 우선 메시지 하나에 딸린 서비스 메타데이터.
///
/// `keyframe`·`isLtr`은 B단계에서 Mux 조각 헤더의 **`serviceFlags` 1바이트**로 접힌다.
/// Mux가 해석하지 않고 모든 조각에 복사만 하는 값 — 재조립이 실패해도 수신측이 "이건
/// 키프레임이었다"를 알아야 대응(키프레임 요청 vs 무시)을 고를 수 있기 때문이다.
nonisolated struct MediaMeta: Sendable {
    let frameId: UInt32
    let timestampMs: UInt32
    let codec: PeriVideoCodec
    let keyframe: Bool
    let isLtr: Bool
}

/// 최신성 경로로 **도착한** 조각 하나. 어느 파이프로 왔든 같은 모양으로 정규화된다
/// (AOA 통짜 프레임 = `fragTotal: 1`인 조각).
///
/// `MediaMeta`의 거울상이다. 다른 점은 `fragIndex`·`fragTotal`뿐인데, 그건 조각화가 송신에선
/// 채널 **안쪽** 일이고 수신에선 채널의 **입구**이기 때문이다 — 조각 경계는 선을 타고 온다.
nonisolated struct MediaFragment: Sendable {
    let frameId: UInt32
    let timestampMs: UInt32
    let codec: PeriVideoCodec
    let keyframe: Bool
    let isLtr: Bool
    let fragIndex: UInt16
    let fragTotal: UInt16
    /// 봉인된 조각. **평문이 아니다** — 봉인은 프레임 전체를 덮으므로 다 모아야 풀린다.
    let body: Data
}

/// 전역 패킷 시퀀스 카운터 (16-bit wrap). 손실 감지의 축이라 **프레임이 아니라 패킷** 단위다.
nonisolated final class U16Counter: @unchecked Sendable {
    private var v: UInt16 = 0
    private let lock = NSLock()
    func next() -> UInt16 { lock.lock(); defer { lock.unlock() }; let s = v; v = v &+ 1; return s }
}

/// UDP 영상 송출 페이서: 한 프레임의 datagram을 한꺼번에 쏘지 않고 프레임 간격의 일부(≈0.6×)에 걸쳐
/// 소그룹(6개)씩 나눠 보낸다 → AP 다운링크 큐 깊이를 얕게 유지해 버퍼블로트(제어 RTT·영상 지연 스파이크) 완화.
/// 전용 직렬 큐에서 동작(인코더 스레드 비차단). 그룹 간격은 프레임당 패킷 수로 환산(스프레드≈window).
nonisolated final class UDPPacer: @unchecked Sendable {
    private let queue = DispatchQueue(label: "com.peri.display.pace", qos: .userInteractive)
    func send(_ datagrams: [Data], windowMicros: UInt32, _ sink: @escaping @Sendable (Data) -> Void) {
        queue.async {
            let groupSize = 6
            let groups = (datagrams.count + groupSize - 1) / groupSize
            let gap: UInt32 = groups > 1 ? min(4000, max(400, windowMicros / UInt32(groups - 1))) : 0
            var i = 0
            for d in datagrams {
                sink(d); i += 1
                if gap > 0, i % groupSize == 0, i < datagrams.count { usleep(gap) }
            }
        }
    }
}

nonisolated final class ServiceChannel: @unchecked Sendable {

    private let sessionBox: SessionBox
    private let bearer: any Bearer
    private let clientIDBox: ClientIDBox
    private let udpPeerBox: UDPPeerBox
    /// 지금 열려 있는 데이터그램 세대(id + 키). LAN에서 최신성 레인이 이 키로 봉인된다.
    private let datagramBox: DatagramBox

    /// 데이터그램 경로의 전송 측 상태. **서비스가 아니라 채널이 소유한다** — 패킷 순번도
    /// 버스트 평탄화도 "어느 파이프로 나가는가"의 문제이지 서비스의 관심사가 아니다.
    private let pktCounter = U16Counter()
    private let pacer = UDPPacer()

    /// 데이터그램 경로의 **수신** 측 상태. 송신의 `pacer`와 같은 이유로 채널이 소유한다 —
    /// 조각이 빠졌다는 건 전송의 사고이지 앱이 알아야 할 사실이 아니다. 앱은 완성된 프레임만 본다.
    private let reassembler = VideoReassembler()

    let caps: BearerCaps

    init(sessionBox: SessionBox, bearer: any Bearer,
         clientIDBox: ClientIDBox, udpPeerBox: UDPPeerBox, datagramBox: DatagramBox) {
        self.sessionBox = sessionBox
        self.bearer = bearer
        self.clientIDBox = clientIDBox
        self.udpPeerBox = udpPeerBox
        self.datagramBox = datagramBox
        let isAoa = bearer is AoaBearer
        self.caps = BearerCaps(hasDatagramPath: !isAoa,
                               maxMessageSize: isAoa ? Int.max : UdpVideo.maxChunk,
                               reliableStream: isAoa)
    }

    // MARK: - 최신성 레인의 키
    //
    // **AOA는 세션 키를 그대로 쓴다.** 세대 키가 있는 이유(짧은 NAT 수명 때문에 채널을
    // 열었다 닫았다 하고, 그때 frame_id가 되감기면 (키, nonce)가 겹친다)가 케이블에서는
    // 통째로 성립하지 않는다 — 파이프는 링크가 살아 있는 동안 계속 열려 있고 세대가 없다.

    private var lossySender: Sealer? {
        if bearer is AoaBearer { return sessionBox.session?.sender }
        return datagramBox.value?.keys.sender
    }

    private var lossyReceiver: Opener? {
        if bearer is AoaBearer { return sessionBox.session?.receiver }
        return datagramBox.value?.keys.receiver
    }

    /// 최신성 경로가 **방금 열렸다**는 알림. 서비스가 `attach`에서 건다.
    ///
    /// 세션이 서비스의 이름을 부르지 않게 하려고 여기 둔다 — UDP 피어가 붙는 건 채널의
    /// 사정이고, 그때 무엇을 할지(키프레임을 새로 낼지)는 서비스가 정할 일이다.
    /// 세터는 MainActor(attach), 호출은 LAN 큐라 락으로 감싼다.
    private let readyHookLock = NSLock()
    private var readyHook: (@Sendable () -> Void)?

    func onDatagramPathReady(_ hook: (@Sendable () -> Void)?) {
        readyHookLock.lock(); readyHook = hook; readyHookLock.unlock()
    }

    func noteDatagramPathReady() {
        readyHookLock.lock(); let h = readyHook; readyHookLock.unlock()
        h?()
    }

    /// 최신성 경로가 지금 열려 있는가. LAN은 세대 키와 UDP 피어가 **둘 다** 있어야 하고,
    /// AOA는 신뢰 파이프로 강등되므로 링크가 있으면 언제나 참이다.
    var isDatagramPathReady: Bool {
        if bearer is AoaBearer { return true }
        guard datagramBox.value != nil, let peer = udpPeerBox.value else { return false }
        return bearer.hasUdpPeer(peer)
    }

    // MARK: - 최신성 경로의 수명
    //
    // **누가 정하는가가 요점이다.** 앱 등록부가 "지금 이 경로를 쓰는 앱이 있다/없다"를 말하고,
    // 그걸 어떻게 실현할지(세대 id 부여 · 후보 수집 · 펀칭 · 정리)는 세션이 정한다.
    // 앱은 UDP도 NAT도 모르고, 세션은 어느 앱이 왜 필요한지 모른다.

    private let wantHookLock = NSLock()
    private var wantHook: (@Sendable (Bool) -> Void)?
    private var wanted = false

    func onDatagramPathWanted(_ hook: (@Sendable (Bool) -> Void)?) {
        wantHookLock.lock(); wantHook = hook; wantHookLock.unlock()
    }

    /// 앱 등록부가 부른다. **값이 바뀔 때만** 아래로 흘린다 — 이 훅은 앱이 뜨고 질 때마다
    /// 불리는데, 그때마다 세대를 다시 열면 화면이 흐르는 도중에 경로가 끊긴다.
    func setDatagramPathWanted(_ on: Bool) {
        guard !(bearer is AoaBearer) else { return }   // 케이블엔 열고 닫을 경로가 없다
        wantHookLock.lock()
        let changed = (wanted != on)
        wanted = on
        let h = changed ? wantHook : nil
        wantHookLock.unlock()
        h?(on)
    }

    /// 반드시, 순서대로. 세션 키가 아직 없으면(핸드셰이크 전) 조용히 버린다 — 호출부가
    /// 매번 `guard let session`을 반복하지 않게 하려는 것이고, 그 구간의 제어 메시지는
    /// 파이프라인이 서면 어차피 다시 나간다.
    @discardableResult
    func sendReliable(_ message: PeriControlMessage) -> Bool {
        guard let session = sessionBox.session,
              let sealed = try? session.sender.seal(try message.serializedData())
        else { return false }
        bearer.sendControl(WireFormat.controlFrame(nonce: sealed.nonce, body: sealed.body),
                           to: clientIDBox.value)
        return true
    }

    /// 최신성 우선. 봉인·경로 선택·조각화·페이싱이 전부 여기서 끝난다.
    /// 반환값은 실제로 나간 조각 수(0 = 경로 없음/봉인 실패) — 호출부의 로그용.
    @discardableResult
    func sendDatagram(_ plaintext: Data, _ meta: MediaMeta, pacingWindowMicros: UInt32) -> Int {
        guard let sealer = lossySender else { return 0 }
        let aoa = bearer as? AoaBearer
        var peer: LanServer.UDPPeerID?
        if aoa == nil {
            guard let p = udpPeerBox.value, bearer.hasUdpPeer(p) else { return 0 }
            peer = p
        }
        // nonce는 frameId에서 유도된다(수신측도 동일) → 와이어에 nonce를 싣지 않는다.
        guard let body = try? sealer.sealVideo(plaintext, frameId: UInt64(meta.frameId))
        else { return 0 }

        if let aoa {
            // 신뢰 파이프 → 조각화도 페이싱도 필요 없다. nonce 필드는 placeholder.
            let header = VideoHeader(keyframe: meta.keyframe, codec: meta.codec,
                                     seq: meta.frameId & 0xFF_FFFF, timestampMs: meta.timestampMs,
                                     fragIndex: 0, fragTotal: 1)
            aoa.sendVideoFrame(WireFormat.videoFrame(header: header,
                                                     nonce: Data(count: 12), body: body))
            return 1
        }

        let datagrams = WireFormat.packetizeVideo(
            frameId: meta.frameId, keyframe: meta.keyframe, isLtr: meta.isLtr,
            payloadType: (meta.codec == .h265) ? 1 : 0,
            timestampMs: meta.timestampMs, body: body,
            nextPacketSeq: { [pktCounter] in pktCounter.next() })
        let b = bearer, target = peer!
        pacer.send(datagrams, windowMicros: pacingWindowMicros) { b.sendVideo($0, to: target) }
        return datagrams.count
    }

    // MARK: - 최신성 경로 수신

    /**
     도착한 조각 하나 → **완성되고 봉인이 풀린** 미디어. 아직 덜 모였으면 nil.

     재조립과 복호가 **둘 다 여기 있는 이유**는 순서가 하나뿐이기 때문이다. 봉인은 프레임
     전체를 한 덩이로 덮으므로 조각이 다 모이기 전에는 풀 수 없고, 따라서 재조립을 앱에
     맡기면 앱이 세션 키를 만져야 한다. 그러면 "암호는 세션 계층"이라는 선이 앱 수만큼 뚫린다.

     nonce는 선에 실리지 않는다 — 양쪽이 frame_id에서 같은 값을 유도한다(`PeriCrypto`).
     그래서 조각이 통째로 유실돼도 그 다음 프레임은 멀쩡히 풀린다.
     */
    func openMedia(_ f: MediaFragment) -> (Data, MediaMeta)? {
        guard let opener = lossyReceiver else { return nil }
        guard let whole = reassembler.accept(f) else { return nil }
        guard let plain = try? opener.openVideo(whole, frameId: UInt64(f.frameId)) else {
            // 조각이 섞이거나 늦게 온 프레임 — 태그 검증이 잡아낸다. 로그만 남기고 흘려보낸다.
            return nil
        }
        return (plain, MediaMeta(frameId: f.frameId, timestampMs: f.timestampMs,
                                 codec: f.codec, keyframe: f.keyframe, isLtr: f.isLtr))
    }

    /// 수신 상태를 비운다(앱이 켜지고 꺼질 때). 붙들고 있던 미완성 조각은 다음 세션의
    /// 프레임과 섞이면 안 되고, seq 기준점도 새로 잡아야 한다.
    func resetMediaReceive() { reassembler.clear() }
}
