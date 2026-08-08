//
//  IceAgent.swift
//  Peri Display
//
//  후보 수집 + 홀펀칭 + 경로 선택. Mac은 controlling 역할(BLE central이 개시하므로).
//
//  펀칭 원리 — NAT는 "안에서 나간 적 있는 상대"의 패킷만 들여보낸다. 그래서 양쪽이 **동시에**
//  상대에게 먼저 쏜다. 각자의 첫 패킷은 상대 NAT에서 버려지지만, 그 과정에서 **자기 쪽 NAT에
//  매핑을 뚫는다.** 그 뒤 도착하는 상대 패킷은 통과한다.
//
//  UDP: 양쪽이 PROBE를 반복 송신, 받으면 PROBE_ACK로 응답. ACK를 받은 (소켓, 원격주소) 쌍이
//       살아 있는 경로다.
//  TCP: **양쪽 대칭 — 각자 전용 포트에서 상대 srflx로 connect를 반복한다.** 둘 다 listen하지
//       않는다. 각 시도는 대부분 실패하지만 그 과정에서 자기 NAT에 구멍이 뚫리고, 타이밍이
//       맞으면 **TCP simultaneous open**으로 연결이 그 자리에 선다(RFC 5128 §3.4).
//
//       처음엔 "Mac은 :7437에 listen하면서 같은 포트로 outbound SYN"이라는 비대칭 설계였는데,
//       그 포트는 NWListener가 독점해 어떤 플래그 조합으로도 다른 소켓이 낄 수 없었다(실측).
//       그런데 애초에 7437일 이유가 없었다 — NAT 매핑은 (출발지 IP, 포트) 단위라 임의 포트에서
//       뚫고 그 매핑을 광고하면 그만이고, simultaneous open이면 listener도 불필요하다.
//
//  토큰은 경로 인증용이 아니다. 진짜 인증은 뒤따르는 ML-KEM 핸드셰이크 + 신원서명이 한다.
//  여기서는 엉뚱한 트래픽·포트스캐너를 걸러내고 어느 세션의 프로브인지 구분하는 용도다.
//

import Foundation
import Darwin
import Network
import OSLog

private nonisolated let log = Logger(subsystem: "com.peri.display.mac", category: "ice")

/// UDP 데이터그램 첫 바이트. 기존 HELLO(0)/VIDEO(1)에 펀칭용 2종을 더한다.
/// Android `UdpType`과 값이 일치해야 한다.
nonisolated enum IceUdpType: UInt8 {
    case probe = 2
    case probeAck = 3
}

/// 펀칭으로 확정된 경로.
nonisolated struct SelectedPath: Sendable {
    let candidate: IceCandidate
    /// 프로브 왕복 시간 — 여러 경로가 살아났을 때 고르는 기준.
    let rttMs: Int
}

/// NAT의 매핑 방식. 홀펀칭 가능 여부는 **오직 이것 하나로** 갈린다.
nonisolated enum NatMapping: Sendable {
    /// 목적지가 달라도 외부 매핑이 같다 → 상대가 우리 매핑을 미리 알 수 있다 → 펀칭 성립.
    case endpointIndependent
    /// 목적지마다 매핑이 다르다 → 상대가 미리 알 방법이 없다 → **코드로 해결 불가, 릴레이 필요.**
    case symmetric
    /// STUN 응답이 1곳 이하라 판정 못 함.
    case unknown

    var humanReadable: String {
        switch self {
        case .endpointIndependent: return "endpoint-independent (펀칭 가능)"
        case .symmetric: return "symmetric (펀칭 불가 — 릴레이 필요)"
        case .unknown: return "판정 불가"
        }
    }
}

nonisolated final class IceAgent: @unchecked Sendable {

    // MARK: 후보 수집

    /// 로컬 후보를 모은다. **STUN은 반드시 udp 인자로 받은 그 소켓에서** 나간다 —
    /// 다른 포트에서 얻은 매핑은 그 포트의 것이라 펀칭에 쓸 수 없다.
    ///
    /// 서버를 여러 개 도는 이유는 중복 확인이 아니라 가용성 때문이다(일부는 응답이 없다).
    /// 첫 성공에서 멈춘다.
    /// - Parameters:
    ///   - listenPort: 제어 리스너 포트(커널 배정). **host-tcp 후보**로만 광고한다 — 같은 LAN에서
    ///     상대가 평범하게 접속하는 주소다. 유동이라 이 값 없이는 상대가 찾아올 방법이 없다.
    ///   - punchTCPPort: 펀칭 전용 포트. **srflx-tcp 후보**가 이 포트의 공인 매핑이다.
    ///     리스너 포트와 분리한 이유는 `reserveTCPPort` 주석 참조.
    ///   - transports: 이번에 모을 전송. 세션 수립은 `[.tcp]`, 데이터그램 채널을 열 때는
    ///     `[.udp]`, fast UDP 모드에서 접속할 때만 둘 다다. **STUN 왕복이 전송마다 따로**
    ///     일어나므로 이 구분이 곧 지연이다 — TCP만 필요한데 UDP까지 물으면 그만큼 늦는다.
    static func gather(udp: UdpTransport, listenPort: UInt16, punchTCPPort: UInt16,
                       servers: [StunServer],
                       transports: Set<CandidateTransport>) async
        -> (candidates: [IceCandidate], nat: NatMapping) {
        var out: [IceCandidate] = []
        let wantUDP = transports.contains(.udp)
        let wantTCP = transports.contains(.tcp)

        // host — 멀티홈이면 여러 개(Wi-Fi + 유선 등). 상대가 어느 쪽에 닿을지 모르니 전부 싣는다.
        for ip in LocalCandidates.hostAddresses() {
            if wantUDP {
                out.append(IceCandidate(transport: .udp, kind: .host,
                                        endpoint: IPv4Endpoint(address: ip, port: udp.port)))
            }
            if wantTCP {
                out.append(IceCandidate(transport: .tcp, kind: .host,
                                        endpoint: IPv4Endpoint(address: ip, port: listenPort)))
            }
        }

        // srflx-udp — 미디어 소켓 그대로 사용(다른 포트의 매핑은 펀칭에 못 쓴다).
        //
        // 서버 **2곳**에 물어 매핑을 비교한다. 홀펀칭 가능 여부는 오직 이걸로 갈린다:
        // 목적지가 달라도 외부 매핑이 같아야(endpoint-independent) 상대가 우리 매핑을 미리 알 수
        // 있다. 목적지마다 다르면 symmetric이라 코드로는 해결 불가(릴레이 필요)다.
        // 실패했을 때 "왜"를 사용자에게 정직하게 말하려면 추측이 아니라 이 실측이 있어야 한다.
        var mappings: [(server: String, ep: IPv4Endpoint)] = []
        if wantUDP {
            for s in servers {
                guard let ep = try? StunClient.query(udpSocket: udp.fd, server: s) else { continue }
                mappings.append((s.display, ep))
                log.notice("srflx-udp \(ep.description) via \(s.display)")
                if mappings.count >= 2 { break }
            }
        }

        // NAT 판정은 UDP 매핑 비교로만 나온다 → UDP를 안 모았으면 판정 자체가 없다(`.unknown`).
        let nat: NatMapping
        switch mappings.count {
        case 0, 1:
            nat = .unknown
            if wantUDP { log.warning("NAT 판정 불가 — 응답한 STUN 서버 \(mappings.count)곳") }
        default:
            let ports = Set(mappings.map(\.ep.port))
            if ports.count == 1 {
                nat = .endpointIndependent
                log.notice("★ NAT: endpoint-independent (매핑 \(mappings[0].ep.port) 일치) → 홀펀칭 가능")
            } else {
                nat = .symmetric
                let detail = mappings.map { "\($0.server)→\($0.ep.port)" }.joined(separator: ", ")
                log.error("★ NAT: SYMMETRIC — 목적지마다 매핑이 다름 (\(detail)) → 홀펀칭 원리적으로 불가")
            }
        }
        if let first = mappings.first {
            out.append(IceCandidate(transport: .udp, kind: .srflx, endpoint: first.ep))
        }

        // srflx-tcp — 펀칭에 쓸 바로 그 포트에서 질의한다.
        //
        // **POSIX를 쓴다(NWConnection 아님).** 펀치 포트는 리스너와 분리돼 있어 POSIX bind가
        // 가능하고, 무엇보다 `close()`로 **포트를 확정적으로 놓는다**. NWConnection으로 했더니
        // 질의가 끝난 뒤에도 그 포트가 물려 있어 뒤이은 펀칭이 매번 `[48: Address already in
        // use]`로 죽었다(실기기 로그, 취소·핸들러 해제로도 안 풀림).
        //
        // 서버를 여러 개 도는 건 중복 확인이 아니라 가용성 때문 — 응답 없는 서버가 흔하다.
        if wantTCP {
            for s in servers where s.tcp {
                if let ep = try? StunClient.queryTCP(server: s, fromLocalPort: punchTCPPort) {
                    out.append(IceCandidate(transport: .tcp, kind: .srflx, endpoint: ep))
                    log.notice("srflx-tcp \(ep.description) via \(s.display)")
                    break
                }
            }
            if !out.contains(where: { $0.kind == .srflx && $0.transport == .tcp }) {
                log.warning("srflx-tcp 없음 — 다른 망에서는 제어 채널이 NAT를 못 넘는다")
            }
        }
        if !out.contains(where: { $0.kind == .srflx }) {
            log.warning("srflx 후보 전무 — 같은 LAN(host)에서만 연결 가능")
        }
        return (out, nat)
    }

    // MARK: UDP 펀칭

    /// UDP 프로브 패킷: [1B type][16B 토큰][8B BE 타임스탬프(us)]
    /// 타임스탬프를 그대로 되돌려받아 RTT를 잰다(별도 상태 불필요).
    static func probePacket(_ type: IceUdpType, token: Data, stampUs: UInt64) -> Data {
        var d = Data([type.rawValue])
        d.append(token)
        var be = stampUs.bigEndian
        withUnsafeBytes(of: &be) { d.append(contentsOf: $0) }
        return d
    }

    /// 프로브/ACK 파싱. 토큰이 다르면 우리 것이 아니다 → nil.
    static func parseProbe(_ d: Data, expecting token: Data) -> (type: IceUdpType, stampUs: UInt64)? {
        let want = 1 + CandidateBundle.tokenLength + 8
        guard d.count >= want, let t = IceUdpType(rawValue: d[d.startIndex]) else { return nil }
        let tokenStart = d.startIndex + 1
        guard d.subdata(in: tokenStart..<(tokenStart + CandidateBundle.tokenLength)) == token else {
            return nil
        }
        var stamp: UInt64 = 0
        let s = tokenStart + CandidateBundle.tokenLength
        for i in 0..<8 { stamp = (stamp << 8) | UInt64(d[s + i]) }
        return (t, stamp)
    }

    /// UDP 홀펀칭. 상대의 udp 후보 전부에 프로브를 반복 발사하고, ACK가 오는 경로를 고른다.
    ///
    /// - Parameter token: **세션 공유 토큰.** 양쪽이 같은 값을 쓴다(Mac이 만들어 BLE로 전달).
    ///
    /// 방향별로 토큰을 따로 두려다 실패했다: B가 A의 프로브에 *자기* 토큰으로 ACK하면, A는 그걸
    /// "상대 토큰이 실린 패킷"으로 분류해 승자 기록에 도달하지 못한다(로컬 2-에이전트 테스트로
    /// 잡음). 방향 구분은 타입 바이트가 이미 하므로 토큰을 나눌 이유가 없다.
    ///
    /// `udp.onDatagram`은 호출자가 걸어 두고 `handleInbound`로 넘긴다 — 이 소켓엔 영상도
    /// 흐르므로 여기서 콜백을 가로채지 않는다.
    func punchUDP(udp: UdpTransport, remote: [IceCandidate],
                  token: Data, timeout: TimeInterval = 10.0) async -> SelectedPath? {
        guard !remote.isEmpty else { return nil }
        arm(token: token)

        let deadline = Date().addingTimeInterval(timeout)
        var round = 0
        while Date() < deadline {
            if let w = currentWinner() { return w }

            // 상대의 모든 후보에 동시에 쏜다 — 어느 쪽이 뚫릴지 미리 알 수 없다.
            let stamp = UInt64(Date().timeIntervalSince1970 * 1_000_000)
            for c in remote {
                udp.send(Self.probePacket(.probe, token: token, stampUs: stamp), to: c.endpoint)
            }
            round += 1
            if round == 1 || round % 20 == 0 {
                log.info("udp punch round \(round) → \(remote.count)개 후보")
            }
            try? await Task.sleep(for: .milliseconds(50))
        }
        log.warning("udp 펀칭 실패 (\(Int(timeout))s 안에 응답 없음)")
        return nil
    }

    /// 수신된 데이터그램이 프로브/ACK면 처리하고 true를 돌려준다(호출자는 그럼 영상 파서로 넘기지 않음).
    ///
    /// - PROBE 수신 → 같은 타임스탬프를 실어 ACK 회신. **회신 자체가 상대에게 경로를 알려준다.**
    /// - PROBE_ACK 수신 → 그 주소가 살아 있는 경로. 되돌아온 타임스탬프로 RTT 계산.
    func handleInbound(_ data: Data, from: IPv4Endpoint, udp: UdpTransport) -> Bool {
        guard let token = currentToken(),
              let (type, stamp) = Self.parseProbe(data, expecting: token) else { return false }

        switch type {
        case .probe:
            // 받은 타임스탬프를 그대로 되돌려줘 상대가 RTT를 재게 한다(우리 쪽 상태 불필요).
            udp.send(Self.probePacket(.probeAck, token: token, stampUs: stamp), to: from)
        case .probeAck:
            let now = UInt64(Date().timeIntervalSince1970 * 1_000_000)
            recordWinner(endpoint: from, rttMs: max(0, Int((now &- stamp) / 1000)))
        }
        return true
    }

    // MARK: TCP 펀칭 (TCP simultaneous open, 전용 포트에서)

    /// 사용 가능한 TCP 포트 하나를 확보한다. 펀칭용 전용 포트 — 제어 리스너와 무관하다.
    ///
    /// **왜 리스너 포트를 안 쓰는가:** 그 포트는 `NWListener`가 쥐고 있고, 실측 결과 어떤 플래그
    /// 조합으로도(IPv4/IPv6 × REUSEADDR/REUSEPORT) 다른 소켓이 낄 수 없다. 그런데 애초에
    /// 같을 필요가 없다 — NAT 매핑은 (출발지 IP, 출발지 포트) 단위라, 임의 포트 E에서 뚫고
    /// **E의 공인 매핑을 srflx-tcp로 광고하면** 상대가 그리로 온다. listener도 필요 없다:
    /// 우리 소켓이 SYN_SENT로 대기하는 동안 상대 SYN이 도착하면 **TCP simultaneous open**으로
    /// 그 자리에서 연결이 성립한다(RFC 5128 §3.4).
    static func reserveTCPPort() -> UInt16 {
        let fd = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { return 0 }
        defer { Darwin.close(fd) }
        var sa = Darwin.sockaddr_in()
        sa.sin_len = UInt8(MemoryLayout<Darwin.sockaddr_in>.size)
        sa.sin_family = sa_family_t(AF_INET)
        sa.sin_port = 0            // 커널이 빈 포트를 고르게 한다
        sa.sin_addr.s_addr = INADDR_ANY
        let ok = withUnsafePointer(to: &sa) { p in
            p.withMemoryRebound(to: Darwin.sockaddr.self, capacity: 1) {
                Darwin.bind(fd, $0, socklen_t(MemoryLayout<Darwin.sockaddr_in>.size))
            }
        }
        guard ok == 0 else { return 0 }
        var out = Darwin.sockaddr_in()
        var len = socklen_t(MemoryLayout<Darwin.sockaddr_in>.size)
        _ = withUnsafeMutablePointer(to: &out) { p in
            p.withMemoryRebound(to: Darwin.sockaddr.self, capacity: 1) {
                Darwin.getsockname(fd, $0, &len)
            }
        }
        return UInt16(bigEndian: out.sin_port)
    }

    /// TCP 홀펀칭. 상대의 tcp 후보로 지정 포트에서 연결을 반복 시도하고, 성립한 연결을 돌려준다.
    ///
    /// 개별 시도는 **대부분 실패한다** — 상대 NAT가 아직 안 열렸으면 SYN이 버려지거나 RST가
    /// 온다. 실패는 오류가 아니라 정상이고, 그 과정에서 **우리 쪽 NAT에 구멍이 뚫린다.**
    /// 양쪽이 동시에 이걸 하다가 타이밍이 맞으면 연결이 선다.
    ///
    /// 반환된 `NWConnection`은 이미 `.ready`라 기존 TCP 읽기 경로에 그대로 태울 수 있다.
    /// 후보마다 **연결 하나를 길게 유지**한다. 짧은 시도를 빠르게 반복하지 않는 이유:
    ///  ① TCP 스택이 이미 SYN을 자동 재전송한다 — 우리가 손으로 반복할 필요가 없다.
    ///  ② 같은 로컬 포트로 연결을 빠르게 만들었다 지우면 이전 것이 포트를 놓기 전에 다음이
    ///     bind를 시도해 `[48: Address already in use]`가 난다(실기기 로그에서 관측).
    /// 그래서 한 번에 하나씩, 충분히 기다린다.
    func punchTCP(localPort: UInt16, remote: [IceCandidate],
                  timeout: TimeInterval = 10.0) async -> NWConnection? {
        // ★ **srflx만 노린다.** host 후보(상대의 사설 IP)는 우리가 같은 LAN에 있을 때만
        // 닿는데, 그 경우는 어차피 평범한 접속 경로가 처리한다. 그런데 순차로 시도하면
        // 그 도달 불가 주소에 몇 초를 통째로 버리게 되고, 그 사이 상대도 우리 host 후보를
        // 붙잡고 있어 **양쪽이 서로 다른 쌍을 동시에 두드리는** 상황이 된다.
        // simultaneous open은 같은 쌍을 동시에 두드려야 성립하므로 그러면 절대 안 뚫린다
        // (실기기 로그: Mac이 192.168.0.8에 5초, 진짜 목표엔 3초만 써서 실패).
        // UDP가 성공한 건 모든 후보에 *동시에* 쐈기 때문이다.
        let all = remote.filter { $0.transport == .tcp }
        let srflx = all.filter { $0.kind == .srflx }
        let targets = srflx.isEmpty ? all : srflx
        guard !targets.isEmpty, localPort != 0 else { return nil }
        let deadline = Date().addingTimeInterval(timeout)

        for c in targets {
            let remaining = deadline.timeIntervalSinceNow
            guard remaining > 0.5 else { break }
            let slice = remaining / Double(max(1, targets.count))
            log.info("tcp punch → \(c.endpoint.description) (\(String(format: "%.1f", slice))s)")
            if let conn = await Self.tryConnect(localPort: localPort, to: c.endpoint,
                                                timeout: slice) {
                log.notice("tcp 펀칭 성공 \(c.endpoint.description)")
                return conn
            }
            try? await Task.sleep(for: .milliseconds(300))
        }
        log.warning("tcp 펀칭 실패 (대상 \(targets.count)개, srflx=\(!srflx.isEmpty))")
        return nil
    }

    /// 한 번의 연결 시도. 성공하면 ready 상태의 연결, 아니면 nil(연결은 정리됨).
    private static func tryConnect(localPort: UInt16, to dst: IPv4Endpoint,
                                   timeout: TimeInterval) async -> NWConnection? {
        let params = NWParameters(tls: nil, tcp: {
            let o = NWProtocolTCP.Options()
            o.noDelay = true
            o.connectionTimeout = max(2, Int(timeout))
            return o
        }())
        params.allowLocalEndpointReuse = true
        params.requiredLocalEndpoint = .hostPort(host: .ipv4(.any),
                                                 port: NWEndpoint.Port(rawValue: localPort)!)
        guard let port = NWEndpoint.Port(rawValue: dst.port) else { return nil }
        let conn = NWConnection(host: .init(dst.address), port: port, using: params)

        let ok = await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
            let done = TcpOneShot(cont)
            conn.stateUpdateHandler = { state in
                switch state {
                case .ready: done.finish(true)
                case .failed, .cancelled: done.finish(false)
                default: break
                }
            }
            conn.start(queue: .global(qos: .userInitiated))
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) { done.finish(false) }
        }
        if ok { return conn }
        // 실패한 연결은 핸들러까지 끊어 확실히 해제한다 — 안 그러면 로컬 포트를 물고 있어
        // 다음 시도가 EADDRINUSE로 죽는다(같은 포트를 반복해 쓰는 구조라 치명적).
        conn.stateUpdateHandler = nil
        conn.cancel()
        return nil
    }

    // MARK: 상태

    // 상태 접근은 전부 **동기** 메서드로 감싼다 — async 함수 본문에서 NSLock을 직접 잡으면
    // Swift 6에서 오류다(await 사이에 스레드가 바뀔 수 있어 안전하지 않음).
    private let lock = NSLock()
    private var token: Data?
    private var winner: SelectedPath?

    private func arm(token: Data) {
        lock.lock(); defer { lock.unlock() }
        self.token = token
        winner = nil
    }

    private func currentToken() -> Data? {
        lock.lock(); defer { lock.unlock() }
        return token
    }

    private func currentWinner() -> SelectedPath? {
        lock.lock(); defer { lock.unlock() }
        return winner
    }

    /// 여러 경로가 살아나면 더 빠른 쪽을 남긴다.
    private func recordWinner(endpoint: IPv4Endpoint, rttMs: Int) {
        lock.lock(); defer { lock.unlock() }
        if let w = winner, w.rttMs <= rttMs { return }
        winner = SelectedPath(
            candidate: IceCandidate(transport: .udp, kind: .srflx, endpoint: endpoint),
            rttMs: rttMs)
        log.notice("udp 경로 확보 \(endpoint.description) rtt=\(rttMs)ms")
    }

    func reset() {
        lock.lock(); token = nil; winner = nil; lock.unlock()
    }
}

/// 상태 콜백과 타임아웃이 경쟁하는 곳에서 continuation을 정확히 한 번만 재개시킨다.
private final class TcpOneShot: @unchecked Sendable {
    private let lock = NSLock()
    private var cont: CheckedContinuation<Bool, Never>?

    init(_ c: CheckedContinuation<Bool, Never>) { cont = c }

    func finish(_ value: Bool) {
        lock.lock()
        let c = cont
        cont = nil
        lock.unlock()
        c?.resume(returning: value)
    }
}
