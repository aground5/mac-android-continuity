//
//  Stun.swift
//  Peri Display
//
//  STUN(RFC 5389) Binding 요청/응답 코덱 + 클라이언트. 공인 매핑 주소(srflx 후보)를 알아낸다.
//
//  ★ 불변식: **STUN은 실제 미디어를 나를 소켓/포트에서 보내야 한다.** 다른 포트에서 얻은
//  매핑은 NAT가 그 포트에 대해 만든 것이라 펀칭에 쓸 수 없다. 그래서 이 파일의 API는
//  전부 "쓸 소켓/로컬 포트"를 받는다.
//
//  이 Mac의 망에서 실측한 것(2026-07-31, 구현 전 검증):
//   - UDP·TCP 모두 endpoint-independent mapping — 서로 다른 STUN 서버 5곳이 같은 외부 포트를 보고.
//     이것이 홀펀칭이 성립하는 조건이다(대칭 NAT였다면 목적지마다 매핑이 달라 불가).
//   - **포트 보존은 가정하지 말 것.** UDP는 보존됐지만(로컬 62956 → 외부 62956) TCP는 실측에서
//     로컬 47438 → 외부 65093으로 바뀌었다. 그래서 후보는 항상 STUN이 알려준 포트를 쓰고,
//     로컬 포트와 같으리라 넘겨짚지 않는다.
//   - TCP Binding에 응답하는 공개 서버: nextcloud/blackberry/antisip/metered.
//     (Google·Cloudflare STUN은 UDP 전용 — srflx-tcp 후보엔 못 쓴다.)
//

import Foundation
import Darwin
import Network
import OSLog

private nonisolated let log = Logger(subsystem: "com.peri.display.mac", category: "stun")

/// 하나의 STUN 서버. `tcp`는 이 서버가 TCP Binding에도 응답하는지 — 공개 서버 다수가 UDP 전용이다.
nonisolated struct StunServer: Sendable, Hashable, Codable {
    let host: String
    let port: UInt16
    /// TCP 전송 지원 여부. srflx-tcp 후보를 얻으려면 true인 서버가 필요하다.
    let tcp: Bool

    init(_ host: String, _ port: UInt16, tcp: Bool = false) {
        self.host = host; self.port = port; self.tcp = tcp
    }

    var display: String { "\(host):\(port)" }

    /// 기본 서버 목록 — 위 주석의 실측으로 고른 것. 설정에서 변경 가능(StunSettings).
    /// UDP 전용을 앞에 두고, TCP 가능 서버를 함께 둬 두 후보를 모두 얻는다.
    static let defaults: [StunServer] = [
        StunServer("stun.l.google.com", 19302),
        StunServer("stun.cloudflare.com", 3478),
        StunServer("stun.nextcloud.com", 3478, tcp: true),
        StunServer("stun.antisip.com", 3478, tcp: true),
        StunServer("stun.voip.blackberry.com", 3478, tcp: true),
    ]
}

/// IPv4 엔드포인트. 후보·프로브·펀칭이 공통으로 쓴다.
nonisolated struct IPv4Endpoint: Sendable, Hashable, CustomStringConvertible {
    let address: String
    let port: UInt16

    var description: String { "\(address):\(port)" }

    /// sockaddr_in으로 변환 (sendto/connect용).
    func sockaddr() -> Darwin.sockaddr_in? {
        var sa = Darwin.sockaddr_in()
        sa.sin_family = sa_family_t(AF_INET)
        sa.sin_port = port.bigEndian
        guard inet_pton(AF_INET, address, &sa.sin_addr) == 1 else { return nil }
        sa.sin_len = UInt8(MemoryLayout<Darwin.sockaddr_in>.size)
        return sa
    }

    init(address: String, port: UInt16) { self.address = address; self.port = port }

    init?(_ sa: Darwin.sockaddr_in) {
        var addr = sa.sin_addr
        var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        guard inet_ntop(AF_INET, &addr, &buf, socklen_t(INET_ADDRSTRLEN)) != nil else { return nil }
        self.address = String(cString: buf)
        self.port = UInt16(bigEndian: sa.sin_port)
    }
}

// MARK: - 메시지 코덱

enum Stun {
    static let magicCookie: UInt32 = 0x2112_A442
    private static let typeBindingRequest: UInt16 = 0x0001
    private static let typeBindingSuccess: UInt16 = 0x0101
    private static let attrMappedAddress: UInt16 = 0x0001
    private static let attrXorMappedAddress: UInt16 = 0x0020
    static let headerSize = 20

    /// Binding Request 한 통 + 그 트랜잭션 ID(응답 대조용).
    static func bindingRequest() -> (message: Data, txid: Data) {
        var txid = Data(count: 12)
        txid.withUnsafeMutableBytes { raw in
            guard let base = raw.baseAddress else { return }
            _ = SecRandomCopyBytes(kSecRandomDefault, 12, base)
        }
        var m = Data()
        m.append(be16(typeBindingRequest))
        m.append(be16(0))                    // attribute 없음
        m.append(be32(magicCookie))
        m.append(txid)
        return (m, txid)
    }

    /// 성공 응답에서 매핑 주소를 꺼낸다. txid가 다르거나 형식이 어긋나면 nil.
    static func parseBindingResponse(_ data: Data, txid: Data) -> IPv4Endpoint? {
        let d = Data(data)   // 0-기반 인덱스 보장
        guard d.count >= headerSize else { return nil }
        let type = u16(d, 0)
        let length = Int(u16(d, 2))
        guard u32(d, 4) == magicCookie, type == typeBindingSuccess,
              d.subdata(in: 8..<20) == txid,
              d.count >= headerSize + length else { return nil }

        var i = headerSize
        let end = headerSize + length
        while i + 4 <= end {
            let atype = u16(d, i)
            let alen = Int(u16(d, i + 2))
            let vStart = i + 4
            guard vStart + alen <= d.count else { return nil }
            let v = d.subdata(in: vStart..<(vStart + alen))

            // family(1B) == 0x01 → IPv4. 앞 1바이트는 예약.
            if alen >= 8, v[v.startIndex + 1] == 0x01 {
                if atype == attrXorMappedAddress {
                    // X-Port = port XOR 매직쿠키 상위 16비트, X-Address = addr XOR 매직쿠키
                    let xport = u16(v, 2) ^ UInt16(magicCookie >> 16)
                    let xaddr = u32(v, 4) ^ magicCookie
                    return IPv4Endpoint(address: ipv4String(xaddr), port: xport)
                }
                if atype == attrMappedAddress {
                    return IPv4Endpoint(address: ipv4String(u32(v, 4)), port: u16(v, 2))
                }
            }
            // 속성은 4바이트 경계로 패딩된다.
            i = vStart + alen + ((4 - alen % 4) % 4)
        }
        return nil
    }

    // MARK: 바이트 유틸 (모두 big-endian)

    private static func be16(_ v: UInt16) -> Data { Data([UInt8(v >> 8), UInt8(v & 0xFF)]) }
    private static func be32(_ v: UInt32) -> Data {
        Data([UInt8(v >> 24), UInt8((v >> 16) & 0xFF), UInt8((v >> 8) & 0xFF), UInt8(v & 0xFF)])
    }
    private static func u16(_ d: Data, _ i: Int) -> UInt16 {
        let b = d.startIndex
        return UInt16(d[b + i]) << 8 | UInt16(d[b + i + 1])
    }
    private static func u32(_ d: Data, _ i: Int) -> UInt32 {
        let b = d.startIndex
        return UInt32(d[b + i]) << 24 | UInt32(d[b + i + 1]) << 16
             | UInt32(d[b + i + 2]) << 8 | UInt32(d[b + i + 3])
    }
    private static func ipv4String(_ v: UInt32) -> String {
        "\(v >> 24).\((v >> 16) & 0xFF).\((v >> 8) & 0xFF).\(v & 0xFF)"
    }
}

// MARK: - 조회 클라이언트

enum StunError: Error {
    case resolveFailed
    case socketFailed(errno: Int32)
    case timeout
    case badResponse
}

nonisolated enum StunClient {
    /// 호스트명 → IPv4 (STUN 서버는 A 레코드만 쓴다).
    static func resolve(_ host: String, port: UInt16) throws -> IPv4Endpoint {
        var hints = addrinfo(ai_flags: 0, ai_family: AF_INET, ai_socktype: SOCK_DGRAM,
                             ai_protocol: 0, ai_addrlen: 0, ai_canonname: nil,
                             ai_addr: nil, ai_next: nil)
        var res: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, String(port), &hints, &res) == 0, let first = res else {
            throw StunError.resolveFailed
        }
        defer { freeaddrinfo(res) }
        guard let sa = first.pointee.ai_addr else { throw StunError.resolveFailed }
        let sin = sa.withMemoryRebound(to: Darwin.sockaddr_in.self, capacity: 1) { $0.pointee }
        guard let ep = IPv4Endpoint(sin) else { throw StunError.resolveFailed }
        return ep
    }

    /// **이미 열려 있는 UDP 소켓**으로 Binding 요청 → 그 소켓의 공인 매핑을 얻는다.
    /// 미디어에 쓸 소켓을 그대로 넘겨야 매핑이 유효하다.
    static func query(udpSocket fd: Int32, server: StunServer,
                      timeout: TimeInterval = 2.0) throws -> IPv4Endpoint {
        let dst = try resolve(server.host, port: server.port)
        guard var sa = dst.sockaddr() else { throw StunError.resolveFailed }
        let (msg, txid) = Stun.bindingRequest()

        let sent = msg.withUnsafeBytes { raw -> Int in
            withUnsafePointer(to: &sa) { sap in
                sap.withMemoryRebound(to: Darwin.sockaddr.self, capacity: 1) { generic in
                    Darwin.sendto(fd, raw.baseAddress, raw.count, 0, generic,
                                  socklen_t(MemoryLayout<Darwin.sockaddr_in>.size))
                }
            }
        }
        guard sent > 0 else { throw StunError.socketFailed(errno: errno) }

        // 응답을 기다린다. 이 소켓엔 미디어도 흐를 수 있으므로 STUN 응답만 골라낸다.
        let deadline = Date().addingTimeInterval(timeout)
        var buf = [UInt8](repeating: 0, count: 1500)
        while Date() < deadline {
            var tv = timeval(tv_sec: 0, tv_usec: 200_000)
            var rfds = fd_set()
            fdZero(&rfds); fdSet(fd, &rfds)
            guard Darwin.select(fd + 1, &rfds, nil, nil, &tv) > 0 else { continue }

            let n = Darwin.recv(fd, &buf, buf.count, 0)
            guard n > 0 else { continue }
            let data = Data(buf[0..<n])
            if let ep = Stun.parseBindingResponse(data, txid: txid) { return ep }
            // STUN 응답이 아니면(미디어 등) 무시하고 계속 기다린다.
        }
        throw StunError.timeout
    }

    /// **지정한 로컬 포트에서** TCP Binding 요청 — `NWConnection` 판.
    ///
    /// 왜 POSIX가 아니라 Network.framework인가: 펀칭도 같은 포트에서 `NWConnection`으로 하기
    /// 때문이다. 그래야 성립한 연결을 기존 TCP 읽기 경로(FrameReader)에 그대로 태울 수 있다
    /// (Network.framework는 외부 fd 채택을 지원하지 않아, POSIX로 뚫으면 읽기 루프를 새로
    /// 짜야 한다). STUN과 펀칭은 **순차**로 같은 포트를 쓰므로 충돌하지 않는다 — 실측 확인.
    static func queryTCPviaNW(server: StunServer, fromLocalPort localPort: UInt16,
                              timeout: TimeInterval = 4.0) async -> IPv4Endpoint? {
        let params = NWParameters(tls: nil, tcp: {
            let o = NWProtocolTCP.Options(); o.noDelay = true; return o
        }())
        params.allowLocalEndpointReuse = true
        params.requiredLocalEndpoint = .hostPort(host: .ipv4(.any),
                                                 port: NWEndpoint.Port(rawValue: localPort)!)
        guard let port = NWEndpoint.Port(rawValue: server.port) else { return nil }
        let conn = NWConnection(host: .init(server.host), port: port, using: params)
        let (msg, txid) = Stun.bindingRequest()

        // ★ 핸들러를 반드시 끊고 취소한다. 예전엔 stateUpdateHandler 클로저가 conn을 강하게
        // 캡처하고 conn이 그 클로저를 소유해 **retain cycle**이 생겼다 → 연결이 해제되지 않아
        // 이 로컬 포트를 계속 쥐고 있었고, 뒤이은 TCP 펀칭이 매 시도마다 EADDRINUSE로 죽었다
        // (실기기 로그: "local address: 0.0.0.0:51821 ... [48: Address already in use]").
        defer {
            conn.stateUpdateHandler = nil
            conn.cancel()
        }
        return await withCheckedContinuation { (cont: CheckedContinuation<IPv4Endpoint?, Never>) in
            // 여러 경로(실패/타임아웃/성공)에서 재개될 수 있으므로 1회만 재개되게 잠근다.
            let done = OneShot(cont)
            conn.stateUpdateHandler = { [weak conn] state in
                guard let conn else { done.finish(nil); return }
                switch state {
                case .ready:
                    conn.send(content: msg, completion: .contentProcessed { err in
                        guard err == nil else { done.finish(nil); return }
                        conn.receive(minimumIncompleteLength: 1, maximumLength: 1500) { data, _, _, _ in
                            done.finish(data.flatMap { Stun.parseBindingResponse($0, txid: txid) })
                        }
                    })
                case .failed, .cancelled:
                    done.finish(nil)
                default:
                    break
                }
            }
            conn.start(queue: .global(qos: .userInitiated))
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) { done.finish(nil) }
        }
    }

    /// **지정한 로컬 포트에서** TCP로 Binding 요청 (POSIX 판). 리스너가 그 포트를 쥐고 있지
    /// 않을 때만 쓸 수 있다 — 진단·테스트용.
    static func queryTCP(server: StunServer, fromLocalPort localPort: UInt16,
                         timeout: TimeInterval = 3.0) throws -> IPv4Endpoint {
        let dst = try resolve(server.host, port: server.port)
        let fd = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { throw StunError.socketFailed(errno: errno) }
        defer { Darwin.close(fd) }

        var one: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &one, socklen_t(MemoryLayout<Int32>.size))

        var local = Darwin.sockaddr_in()
        local.sin_len = UInt8(MemoryLayout<Darwin.sockaddr_in>.size)
        local.sin_family = sa_family_t(AF_INET)
        local.sin_port = localPort.bigEndian
        local.sin_addr.s_addr = INADDR_ANY
        let bound = withUnsafePointer(to: &local) { lp in
            lp.withMemoryRebound(to: Darwin.sockaddr.self, capacity: 1) {
                Darwin.bind(fd, $0, socklen_t(MemoryLayout<Darwin.sockaddr_in>.size))
            }
        }
        guard bound == 0 else { throw StunError.socketFailed(errno: errno) }

        guard var sa = dst.sockaddr() else { throw StunError.resolveFailed }
        let connected = withUnsafePointer(to: &sa) { sap in
            sap.withMemoryRebound(to: Darwin.sockaddr.self, capacity: 1) {
                Darwin.connect(fd, $0, socklen_t(MemoryLayout<Darwin.sockaddr_in>.size))
            }
        }
        guard connected == 0 else { throw StunError.socketFailed(errno: errno) }

        var tv = timeval(tv_sec: Int(timeout), tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))

        let (msg, txid) = Stun.bindingRequest()
        let sent = msg.withUnsafeBytes { Darwin.send(fd, $0.baseAddress, $0.count, 0) }
        guard sent > 0 else { throw StunError.socketFailed(errno: errno) }

        var buf = [UInt8](repeating: 0, count: 1500)
        let n = Darwin.recv(fd, &buf, buf.count, 0)
        guard n > 0 else { throw StunError.timeout }
        guard let ep = Stun.parseBindingResponse(Data(buf[0..<n]), txid: txid) else {
            throw StunError.badResponse
        }
        return ep
    }
}

/// 콜백이 여러 갈래(성공·실패·타임아웃)로 들어오는 곳에서 continuation을 **정확히 한 번만**
/// 재개시킨다. 두 번 재개하면 런타임 크래시라 반드시 필요하다.
private final class OneShot<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var cont: CheckedContinuation<T, Never>?

    init(_ c: CheckedContinuation<T, Never>) { cont = c }

    func finish(_ value: T) {
        lock.lock()
        let c = cont
        cont = nil
        lock.unlock()
        c?.resume(returning: value)
    }
}

// MARK: - fd_set 헬퍼 (Swift에 FD_ZERO/FD_SET 매크로가 없다)

private func fdZero(_ set: inout fd_set) {
    withUnsafeMutableBytes(of: &set) { raw in
        _ = raw.initializeMemory(as: UInt8.self, repeating: 0)
    }
}

private func fdSet(_ fd: Int32, _ set: inout fd_set) {
    let intOffset = Int(fd) / 32
    let bitOffset = Int(fd) % 32
    withUnsafeMutableBytes(of: &set) { raw in
        guard let base = raw.baseAddress?.assumingMemoryBound(to: Int32.self) else { return }
        base[intOffset] |= Int32(1 << bitOffset)
    }
}
