//
//  LanServer.swift
//  Peri Display
//
//  멀티클라이언트 LAN/NAT Bearer (Mac 측). 한 리스너 쌍이 N개 클라이언트를 디먹스한다:
//    - 제어/핸드셰이크 : TCP. 연결마다 ClientID + 전용 FrameReader. 콜백에 ClientID를 실어
//                       상위(SessionManager)가 어느 클라인지 구분.
//    - 영상 + 펀칭     : UDP. **단일 POSIX 소켓**(UdpTransport).
//
//  ★ 포트는 둘 다 **유동(커널 배정)**이다. ICE로 후보를 주고받는 순간 잘 알려진 포트가
//  필요 없어졌다 — 상대는 BLE로 받은 후보에 적힌 포트로 오지, 약속된 번호로 오지 않는다.
//  TCP와 UDP가 같은 번호일 이유도 사라졌다(후보가 전송별로 따로 나간다). 고정 포트를 버리면
//  다른 앱과의 충돌도, 두 번째 인스턴스의 bind 실패도 없다.
//
//  ★ UDP가 NWListener가 아닌 이유 (실측으로 확정, 2026-07-31):
//  NWListener는 inbound 피어의 NWConnection만 준다. 홀펀칭은 ① 아직 우리에게 말을 건 적 없는
//  주소로 **먼저** 쏴야 하고(그래야 우리 쪽 NAT 매핑이 열린다) ② STUN 질의가 미디어와 **같은
//  소켓/포트**에서 나가야 매핑이 유효하다 — 둘 다 NWListener 모델로는 불가능하다.
//  "같은 포트에 소켓 2개"도 bind는 되지만 수신이 갈려 신뢰할 수 없어 폐기했다(실측).
//
//  TCP는 NWListener를 그대로 쓴다. 펀칭은 리스너 포트가 아니라 **전용 유동 포트**에서 하므로
//  (IceAgent.reserveTCPPort) 리스너와 포트를 공유할 필요가 없다.
//
//  App Sandbox: Incoming/Outgoing Network Connections entitlement 필요(설정됨).
//  스레드: TCP 콜백은 LAN 큐에서, UDP 콜백은 UdpTransport의 수신 큐에서. 맵은 락으로 보호.
//

import Foundation
import Network
import OSLog

private nonisolated let log = Logger(subsystem: "com.peri.display.mac", category: "lan")

nonisolated final class LanServer: @unchecked Sendable {
    typealias ClientID = UUID
    typealias UDPPeerID = UUID

    private final class TcpClient {
        let conn: NWConnection
        let reader = FrameReader()
        init(_ c: NWConnection) { conn = c }
    }

    private var tcpListener: NWListener?
    private var udp: UdpTransport?
    private var tcpClients: [ClientID: TcpClient] = [:]
    /// 아직 신원이 확정되지 않은(익명) 연결의 마감 타이머. 해제는 `noteAuthenticated`뿐이다.
    private var anonTimers: [ClientID: DispatchWorkItem] = [:]
    /// 영상 송신 대상. NWConnection이 아니라 주소를 들고 있다 — 단일 소켓이므로 주소만 있으면 된다.
    private var udpPeers: [UDPPeerID: IPv4Endpoint] = [:]

    /// 이 클라이언트가 어느 주소에서 왔는가 — "같은 LAN인가"를 Mac이 스스로 판정하는 근거.
    /// 없으면 아직 준비 전이거나 이미 끊긴 연결이다.
    func peerAddress(_ id: ClientID) -> String? {
        lock.lock(); let conn = tcpClients[id]?.conn; lock.unlock()
        guard let conn else { return nil }
        // `currentPath`가 실제로 협상된 경로다. `endpoint`는 우리가 붙일 때 쓴 값이라
        // 펀칭으로 채택한 연결에서는 다를 수 있다.
        let ep = conn.currentPath?.remoteEndpoint ?? conn.endpoint
        guard case .hostPort(let host, _) = ep else { return nil }
        switch host {
        case .ipv4(let a): return "\(a)".split(separator: "%").first.map(String.init)
        case .ipv6(let a): return "\(a)".split(separator: "%").first.map(String.init)
        default: return "\(host)"
        }
    }

    private let lock = NSLock()
    private let queue = DispatchQueue(label: "com.peri.display.lan")

    /// 새 TCP 클라이언트 연결됨. LAN 큐.
    var onClientConnected: (@Sendable (ClientID) -> Void)?
    /// TCP 클라이언트 끊김. LAN 큐.
    var onClientDisconnected: (@Sendable (ClientID) -> Void)?
    /// 수신된 raw 제어 프레임(복호화/파싱은 세션 계층). LAN 큐.
    var onFrame: (@Sendable (ClientID, WireType, Data) -> Void)?
    /// 영상 UDP HELLO 수신. rest = [12B nonce][sealed(session_id)]. 상위가 시도복호화로 세션 매칭.
    var onUdpHello: (@Sendable (UDPPeerID, Data) -> Void)?
    /// 영상 UDP 데이터그램 수신 (화면 공유 — Android가 보내는 쪽). rest = [16B Mini-RTP][chunk].
    ///
    /// HELLO와 달리 **시도복호화로 디먹스하지 않는다.** 조각 하나는 그 자체로 열리지 않고
    /// (봉인이 프레임 전체를 덮는다), 초당 수백 개가 오는 경로에서 세션마다 복호를 시도하는
    /// 건 값이 너무 비싸다. 대신 주소로 가른다 — HELLO를 이미 받아 등록된 피어만 인정한다.
    var onUdpVideo: (@Sendable (UDPPeerID, Data) -> Void)?

    /// 경로 생사 확인 PING(Android → Mac). HELLO와 같은 처리(피어 재등록)를 거친 뒤 불린다.
    var onUdpPing: (@Sendable (UDPPeerID, Data) -> Void)?
    /// ICE 프로브/ACK 후킹. true를 돌려주면 이 데이터그램은 소비된 것으로 보고 더 처리하지 않는다.
    /// SessionManager가 IceAgent에 연결한다.
    var onUdpProbe: (@Sendable (Data, IPv4Endpoint) -> Bool)?

    var clientCount: Int { lock.lock(); defer { lock.unlock() }; return tcpClients.count }

    /// STUN이 **이 소켓 그대로** 써야 한다(다른 포트의 매핑은 펀칭에 못 쓴다) → 후보 수집용으로 노출.
    var udpTransport: UdpTransport? { lock.lock(); defer { lock.unlock() }; return udp }

    /// 커널이 배정한 제어(TCP) 리스너 포트. host-tcp 후보로 광고된다. 0 = 아직 바인드 전.
    var tcpPort: UInt16 { lock.lock(); defer { lock.unlock() }; return boundTCPPort }
    /// 커널이 배정한 영상/펀칭(UDP) 포트. host-udp 후보로 광고된다.
    var udpPort: UInt16 { lock.lock(); defer { lock.unlock() }; return udp?.port ?? 0 }

    private var boundTCPPort: UInt16 = 0
    private var tcpPortContinuations: [CheckedContinuation<UInt16, Error>] = []

    func awaitTCPPort() async throws -> UInt16 {
        lock.lock()
        if boundTCPPort != 0 {
            let p = boundTCPPort
            lock.unlock()
            return p
        }
        lock.unlock()
        
        return try await withCheckedThrowingContinuation { continuation in
            lock.lock()
            if boundTCPPort != 0 {
                let p = boundTCPPort
                lock.unlock()
                continuation.resume(returning: p)
            } else {
                tcpPortContinuations.append(continuation)
                lock.unlock()
            }
        }
    }

    /// 두 소켓 모두 포트 0으로 바인드하고 커널이 준 번호를 후보로 광고한다.
    /// TCP는 `NWListener`가 `.ready`가 돼야 포트를 알려주므로 상태 핸들러에서 받아 둔다.
    func start() throws {
        let tcpOpts = NWProtocolTCP.Options()
        tcpOpts.noDelay = true   // Nagle off — 작은 제어/Pong을 즉시 송신해 RTT 지터↓
        let tcpParams = NWParameters(tls: nil, tcp: tcpOpts)
        let tcp = try NWListener(using: tcpParams)   // 포트 미지정 = 커널 배정
        tcp.newConnectionHandler = { [weak self] conn in self?.acceptTCP(conn) }
        
        tcp.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                if let p = tcp.port?.rawValue {
                    self.lock.lock()
                    let isFirst = (self.boundTCPPort == 0)
                    self.boundTCPPort = p
                    let continuations = self.tcpPortContinuations
                    self.tcpPortContinuations.removeAll()
                    self.lock.unlock()
                    
                    if isFirst { log.info("TCP 제어 리스너 :\(p)") }
                    continuations.forEach { $0.resume(returning: p) }
                }
            case .failed(let error):
                self.lock.lock()
                let continuations = self.tcpPortContinuations
                self.tcpPortContinuations.removeAll()
                self.lock.unlock()
                continuations.forEach { $0.resume(throwing: error) }
            case .cancelled:
                self.lock.lock()
                let continuations = self.tcpPortContinuations
                self.tcpPortContinuations.removeAll()
                self.lock.unlock()
                continuations.forEach { $0.resume(throwing: CancellationError()) }
            default: break
            }
        }
        tcp.start(queue: queue)
        tcpListener = tcp

        let u = try UdpTransport(port: 0)
        u.onDatagram = { [weak self] data, from in self?.handleUDP(data, from: from) }
        u.start()
        lock.lock(); udp = u; lock.unlock()

        log.info("LAN bearer 시작 — UDP :\(u.port) (영상+펀칭), TCP는 바인드 후 통보")
    }

    func stop() {
        lock.lock()
        let tcs = tcpClients.values.map { $0.conn }
        let u = udp
        tcpClients = [:]; udpPeers = [:]; udp = nil
        lock.unlock()
        tcs.forEach { $0.cancel() }
        u?.close()
        tcpListener?.cancel()
        tcpListener = nil
        lock.lock(); boundTCPPort = 0; lock.unlock()
    }

    /// 제어/핸드셰이크 프레임 송신 (특정 TCP 클라이언트로).
    func sendControl(_ data: Data, to client: ClientID) {
        lock.lock(); let conn = tcpClients[client]?.conn; lock.unlock()
        conn?.send(content: data, completion: .contentProcessed { error in
            if let error { log.error("tcp send error: \(error.localizedDescription)") }
        })
    }

    /// 영상 데이터그램 송신 (특정 UDP 피어로).
    func sendVideo(_ datagram: Data, to peer: UDPPeerID) {
        lock.lock(); let dst = udpPeers[peer]; let u = udp; lock.unlock()
        guard let dst, let u else { return }
        u.send(datagram, to: dst)
    }

    /// 해당 UDP 피어가 아직 등록돼 있는지(영상 송출 가드용).
    func hasUdpPeer(_ peer: UDPPeerID?) -> Bool {
        guard let peer else { return false }
        lock.lock(); defer { lock.unlock() }; return udpPeers[peer] != nil
    }

    /// 펀칭으로 확정된 주소를 영상 피어로 미리 등록한다(HELLO를 기다리지 않는 NAT 경로용).
    @discardableResult
    func registerUdpPeer(_ endpoint: IPv4Endpoint) -> UDPPeerID {
        let id = UDPPeerID()
        lock.lock(); udpPeers[id] = endpoint; lock.unlock()
        return id
    }

    /// 홀펀칭으로 이미 성립한 연결을 평범한 클라이언트처럼 채택한다.
    ///
    /// 펀칭된 연결은 리스너를 거치지 않으므로 여기서 직접 등록해야 한다. 등록 뒤로는 리스너가
    /// 받은 연결과 **완전히 동일한 경로**를 탄다 — 같은 FrameReader, 같은 콜백. 그래서 세션·
    /// 크립토·핸드셰이크 코드는 이 연결이 펀칭된 것인지 알 필요가 없다.
    func adoptPunchedConnection(_ conn: NWConnection) {
        let id = ClientID()
        lock.lock(); tcpClients[id] = TcpClient(conn); lock.unlock()
        log.notice("펀칭된 TCP 연결 채택 → client \(id)")
        // 이미 .ready 상태로 넘어오므로 상태 핸들러는 이후 끊김만 처리한다.
        conn.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled: self?.dropTCP(id)
            default: break
            }
        }
        onClientConnected?(id)
        armHandshakeTimer(id)
        receiveTCP(id, conn)
    }

    /// **익명 구간 전체**의 마감 — 연결 수락부터 신원 검증까지.
    ///
    /// 예전엔 "첫 프레임이 오면 해제"였다. 그때는 상위(기기 상태 기계)의 핸드셰이크 마감이
    /// ClientHello 시점부터 돌았으니 그걸로 이어졌기 때문이다. 그런데 그 등록이 **검증 전에**
    /// 일어나는 게 문제였고(`adopt` 주석 참조) 등록을 ClientAuth 뒤로 옮기면서, ClientHello만
    /// 보내고 입을 다무는 연결은 다시 아무 타이머에도 안 걸리게 됐다.
    ///
    /// 그래서 이 타이머는 이제 **인증될 때까지** 돈다. 익명 연결의 수명을 짊어지는 유일한
    /// 장치이고, 해제 지점은 [noteAuthenticated] 하나뿐이다.
    private func armHandshakeTimer(_ id: ClientID) {
        let t = DispatchWorkItem { [weak self] in
            guard let self else { return }
            log.notice("lan[\(id)]: \(Int(Self.anonymousDeadline))s 안에 인증 없음 → 익명 연결 정리")
            self.closeClient(id)
        }
        lock.lock(); anonTimers[id] = t; lock.unlock()
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.anonymousDeadline, execute: t)
    }

    /// 신원이 확정됐다(ClientAuth 검증 성공 또는 RESUME 수용) — 익명 마감 해제.
    func noteAuthenticated(_ id: ClientID) {
        lock.lock(); let t = anonTimers.removeValue(forKey: id); lock.unlock()
        t?.cancel()
    }

    /// 연결 수락부터 신원 검증까지의 상한. 이 안에 인증을 못 마치면 버린다.
    ///
    /// 왜 필요한가: 이 구간의 연결은 `tcpClients`와 `Session`을 붙잡고 있는데 등록부엔
    /// 없다. 마감이 없으면 포트 스캐너 한 번, 혹은 ClientHello만 보내고 멈추는 연결 하나로
    /// 그대로 쌓인다.
    private static let anonymousDeadline: TimeInterval = 10

    // MARK: - TCP

    private func acceptTCP(_ conn: NWConnection) {
        let id = ClientID()
        lock.lock(); tcpClients[id] = TcpClient(conn); lock.unlock()
        conn.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.onClientConnected?(id)
                self.armHandshakeTimer(id)
                self.receiveTCP(id, conn)
            case .failed, .cancelled:
                self.dropTCP(id)
            default: break
            }
        }
        conn.start(queue: queue)
    }

    private func receiveTCP(_ id: ClientID, _ conn: NWConnection) {
        lock.lock(); let reader = tcpClients[id]?.reader; lock.unlock()
        guard let reader else { return }
        conn.receive(minimumIncompleteLength: 1, maximumLength: 65536) {
            [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                reader.append(data)
                while let frame = reader.next() {
                    self.onFrame?(id, frame.type, frame.payload)
                }
            }
            if isComplete || error != nil { self.dropTCP(id) }
            else { self.receiveTCP(id, conn) }
        }
    }

    /// 특정 클라 연결을 끊는다 — 핸드오버 재협상으로 제어 채널이 새 연결로 갈린 뒤,
    /// 남은 옛 연결(대개 이미 죽어 있지만 FIN 없이 사라진 경우 수 분간 매달린다)을 정리한다.
    func closeClient(_ id: ClientID) {
        noteAuthenticated(id)   // 남아 있는 마감 타이머 해제
        lock.lock(); let c = tcpClients.removeValue(forKey: id); lock.unlock()
        guard let c else { return }
        c.conn.stateUpdateHandler = nil   // cancel이 dropTCP를 또 부르지 않게
        c.conn.cancel()
        onClientDisconnected?(id)
    }

    private func dropTCP(_ id: ClientID) {
        noteAuthenticated(id)   // 남아 있는 마감 타이머 해제
        lock.lock(); let existed = tcpClients.removeValue(forKey: id) != nil; lock.unlock()
        if existed { onClientDisconnected?(id) }
    }

    // MARK: - UDP

    /// 수신 데이터그램 분기. **ICE 프로브를 먼저** 본다 — 펀칭 중에는 HELLO보다 먼저 오고,
    /// 프로브 타입(2·3)은 UdpType(0·1·4·5)에 없어 parseUdp가 어차피 nil을 준다.
    private func handleUDP(_ data: Data, from: IPv4Endpoint) {
        if onUdpProbe?(data, from) == true { return }

        guard let parsed = WireFormat.parseUdp(data) else { return }
        switch parsed.type {
        case .hello, .ping:
            // 이 주소를 영상 피어로 등록. 같은 주소가 다시 HELLO/PING을 보내면 새 ID를
            // 만들지 않고 재사용해 재접속 때 좀비 피어가 쌓이지 않게 한다.
            //
            // PING이 HELLO와 같은 취급을 받는 이유: 와이어 모양이 같고(피어 재등록 겸용),
            // 망 전환으로 주소가 바뀐 뒤 첫 HELLO가 유실됐을 때 PING이 사실상 그 재전송
            // 역할을 한다 — 여기서 걸러 버리면 그 복구 경로가 통째로 막힌다.
            lock.lock()
            let existing = udpPeers.first(where: { $0.value == from })?.key
            let id = existing ?? UDPPeerID()
            udpPeers[id] = from
            lock.unlock()
            if parsed.type == .hello { onUdpHello?(id, parsed.rest) }
            else { onUdpPing?(id, parsed.rest) }

        case .video:
            // 알려진 피어에서 온 것만 받는다. **여기서 새 피어를 만들지 않는다** — 그러면
            // 아무 주소나 영상을 쏘아 등록부에 자리를 만들 수 있고, 그건 HELLO를 통과해야
            // 피어가 된다는 규칙을 우회하는 길이 된다.
            lock.lock()
            let id = udpPeers.first(where: { $0.value == from })?.key
            lock.unlock()
            guard let id else { return }
            onUdpVideo?(id, parsed.rest)

        // Mac → Android 전용. Mac이 받을 일이 없다(Android가 자기 앞으로 보낼 이유가 없다) —
        // 와도 그냥 버린다.
        case .pong:
            break
        }
    }
}
