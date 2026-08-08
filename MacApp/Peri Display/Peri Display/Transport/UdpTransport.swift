//
//  UdpTransport.swift
//  Peri Display
//
//  단일 POSIX UDP 소켓. 하나의 소켓으로 임의 주소에 sendto + 임의 주소에서 recvfrom 한다.
//
//  ★ 왜 NWListener가 아니라 POSIX인가 (구현 전 실측으로 확정, 2026-07-31):
//  NWListener는 **inbound 피어에 대한 NWConnection만** 준다. 홀펀칭은 아직 우리에게 말을 건 적
//  없는 주소로 **먼저** 쏴야 하고(그래야 우리 쪽 NAT 매핑이 열린다), STUN 질의도 미디어와 **같은
//  소켓/포트**에서 나가야 매핑이 유효하다. 둘 다 NWListener 모델과 맞지 않는다.
//
//  "같은 포트에 소켓 2개(NWListener 유지 + 송신 소켓 추가)"도 실측상 bind는 되지만 **수신이 두
//  소켓에 갈려 신뢰할 수 없어** 폐기했다. 그래서 단일 소켓이 요구사항이다.
//
//  스레드: 수신은 전용 큐의 DispatchSourceRead에서, 송신은 아무 스레드에서나(sendto는 원자적).
//
//  **IPv4 전용은 의도된 것이다.** 예전 NWListener는 IPv6 듀얼스택으로 열렸지만, 이 경로는
//  처음부터 끝까지 IPv4다: Android가 받는 접속 주소는 `primaryIPv4()`이고, ICE 후보도 전부
//  IPv4(`LocalCandidates.hostAddresses`가 AF_INET만 수집)다. 소켓만 듀얼스택으로 열면
//  후보에 없는 주소로 들어온 패킷이 생겨 오히려 경로가 어긋난다.
//

import Foundation
import Darwin
import OSLog

private nonisolated let log = Logger(subsystem: "com.peri.display.mac", category: "udp")

nonisolated final class UdpTransport: @unchecked Sendable {
    /// 수신 콜백 — (데이터그램, 보낸 쪽 주소). 수신 큐에서 호출된다.
    var onDatagram: (@Sendable (Data, IPv4Endpoint) -> Void)?

    /// STUN이 **이 소켓 그대로** 써야 하므로 노출한다 (다른 포트의 매핑은 펀칭에 못 쓴다).
    private(set) var fd: Int32 = -1
    let port: UInt16

    private let queue = DispatchQueue(label: "com.peri.display.udp")
    private var source: DispatchSourceRead?
    private let lock = NSLock()
    private var closed = false

    /// 지정 포트에 bind. port=0이면 커널이 배정(그 경우 boundPort로 실제 값을 읽는다).
    init(port: UInt16) throws {
        let s = Darwin.socket(AF_INET, SOCK_DGRAM, 0)
        guard s >= 0 else { throw UdpError.socketFailed(errno) }

        // SO_REUSEADDR만 켠다(닫은 직후 재시작 허용). **SO_REUSEPORT는 일부러 안 켠다** —
        // 켜면 앱이 두 번 떠 있을 때 두 프로세스가 같은 포트에 붙어 수신 데이터그램이 갈린다.
        // 그러면 영상이 조용히 절반씩 사라져 진단이 매우 어렵다. 여기선 두 번째 인스턴스가
        // bind에서 시끄럽게 실패하는 편이 낫다. (TCP 펀치 소켓은 listen 소켓과 포트를 공유해야
        // 하므로 그쪽에서만 REUSEPORT를 쓴다 — IceAgent.fireSYN 참조.)
        var one: Int32 = 1
        setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &one, socklen_t(MemoryLayout<Int32>.size))
        // 키프레임은 수백 프래그먼트가 버스트로 오간다 → 버퍼를 크게(Android도 8MB로 맞춰 둠).
        var buf: Int32 = 8 * 1024 * 1024
        setsockopt(s, SOL_SOCKET, SO_RCVBUF, &buf, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(s, SOL_SOCKET, SO_SNDBUF, &buf, socklen_t(MemoryLayout<Int32>.size))

        var sa = Darwin.sockaddr_in()
        sa.sin_len = UInt8(MemoryLayout<Darwin.sockaddr_in>.size)
        sa.sin_family = sa_family_t(AF_INET)
        sa.sin_port = port.bigEndian
        sa.sin_addr.s_addr = INADDR_ANY
        let bound = withUnsafePointer(to: &sa) { p in
            p.withMemoryRebound(to: Darwin.sockaddr.self, capacity: 1) {
                Darwin.bind(s, $0, socklen_t(MemoryLayout<Darwin.sockaddr_in>.size))
            }
        }
        guard bound == 0 else {
            let e = errno
            Darwin.close(s)
            throw UdpError.bindFailed(port: port, errno: e)
        }

        self.fd = s
        self.port = port == 0 ? Self.readBoundPort(s) : port
        log.info("udp socket bound :\(self.port)")
    }

    deinit { close() }

    /// 수신 루프 시작. onDatagram을 먼저 설정하고 부를 것.
    func start() {
        lock.lock(); defer { lock.unlock() }
        guard source == nil, !closed, fd >= 0 else { return }

        let src = DispatchSource.makeReadSource(fileDescriptor: fd, queue: queue)
        src.setEventHandler { [weak self] in self?.drain() }
        src.resume()
        source = src
    }

    /// 한 데이터그램 송신. 실패는 로그만 — UDP라 호출자가 할 수 있는 일이 없다.
    func send(_ data: Data, to dst: IPv4Endpoint) {
        guard fd >= 0, var sa = dst.sockaddr() else { return }
        let n = data.withUnsafeBytes { raw -> Int in
            withUnsafePointer(to: &sa) { sap in
                sap.withMemoryRebound(to: Darwin.sockaddr.self, capacity: 1) { generic in
                    Darwin.sendto(fd, raw.baseAddress, raw.count, 0, generic,
                                  socklen_t(MemoryLayout<Darwin.sockaddr_in>.size))
                }
            }
        }
        if n < 0 {
            let e = errno
            // ENOBUFS/EAGAIN은 큐 포화 — 흔하고 정상적인 배압이라 소음을 내지 않는다.
            if e != ENOBUFS && e != EAGAIN {
                log.error("sendto \(dst.description) 실패 errno=\(e)")
            }
        }
    }

    func close() {
        lock.lock()
        guard !closed else { lock.unlock(); return }
        closed = true
        let s = source; source = nil
        let f = fd; fd = -1
        lock.unlock()

        s?.cancel()
        if f >= 0 { Darwin.close(f) }
    }

    // MARK: -

    /// 읽을 게 있는 동안 모두 꺼낸다 — 이벤트 하나에 데이터그램이 여러 개 밀려 있을 수 있다.
    private func drain() {
        var buf = [UInt8](repeating: 0, count: 65536)
        while true {
            var sa = Darwin.sockaddr_in()
            var len = socklen_t(MemoryLayout<Darwin.sockaddr_in>.size)
            let n = withUnsafeMutablePointer(to: &sa) { sap in
                sap.withMemoryRebound(to: Darwin.sockaddr.self, capacity: 1) { generic in
                    Darwin.recvfrom(fd, &buf, buf.count, MSG_DONTWAIT, generic, &len)
                }
            }
            guard n > 0, let from = IPv4Endpoint(sa) else { return }   // n<=0 → 더 없음/에러
            onDatagram?(Data(buf[0..<n]), from)
        }
    }

    private static func readBoundPort(_ fd: Int32) -> UInt16 {
        var sa = Darwin.sockaddr_in()
        var len = socklen_t(MemoryLayout<Darwin.sockaddr_in>.size)
        _ = withUnsafeMutablePointer(to: &sa) { p in
            p.withMemoryRebound(to: Darwin.sockaddr.self, capacity: 1) {
                Darwin.getsockname(fd, $0, &len)
            }
        }
        return UInt16(bigEndian: sa.sin_port)
    }

    enum UdpError: Error {
        case socketFailed(Int32)
        case bindFailed(port: UInt16, errno: Int32)
    }
}
