//
//  IceCandidate.swift
//  Peri Display
//
//  연결 후보 + BLE로 주고받는 직렬화 포맷. Android `IceCandidate.kt`와 **바이트 호환**이어야 한다.
//
//  포맷(이미 BLE 세션 키로 sealed 되므로 평문 텍스트 — 디버깅이 쉬운 쪽을 택했다):
//
//      v1 <32 hex = 16B 펀치 토큰>
//      f fastudp                          (선택 — 아래 참조)
//      c tcp host  192.168.0.5   7437
//      c udp host  192.168.0.5   7437
//      c tcp srflx 203.0.113.9  41022
//      c udp srflx 203.0.113.9  51234
//
//  줄바꿈은 \n, 필드 구분은 공백 1개 이상. 모르는 줄은 무시한다(앞으로 필드가 늘어도 안 깨지게).
//
//  **기본은 tcp 후보뿐이다.** 세션 수립에 UDP가 필요 없어졌기 때문이다 — 데이터그램 경로는
//  영상을 쓰는 앱이 실제로 뜰 때 제어 채널 위에서 따로 연다(`UdpOpen`). NAT의 UDP 매핑은
//  수명이 짧아 붙들고 있으려면 계속 두드려야 하는데, TCP 매핑은 훨씬 오래 살아서 세션만
//  유지하는 데는 그쪽이 압도적으로 싸다.
//
//  `f fastudp`는 그 기본을 뒤집는 스위치다: Mac이 켜면 접속하는 그 라운드에서 UDP까지 함께
//  뚫어 둔다. **세션이 서는 순간 영상 앱이 곧바로 붙어야 하는 경우**를 위한 것이다 —
//  지난번에 켜 둔 앱이 그런 앱이거나, 망이 바뀌어 채널이 통째로 다시 서는 중일 때. 그때는
//  앱이 뜬 뒤 ICE 한 왕복을 더 기다리는 게 눈에 띈다. 켜도 **수명 규칙은 그대로다** —
//  마지막 영상 앱이 내려가면 UDP는 닫힌다.
//
//  플래그를 명시하는 이유는 "UDP 후보가 없다"와 "UDP 후보를 못 얻었다"가 선에서 똑같이
//  보이기 때문이다 — 상대가 그 둘을 구분해야 자기 쪽 수집을 생략할지 정할 수 있다.
//
//  펀치 토큰: 프로브 패킷에 실어 상대가 우리 프로브임을 확인하는 값. 경로 인증이 목적이 아니라
//  (진짜 인증은 뒤따르는 ML-KEM 핸드셰이크가 한다) 엉뚱한 트래픽·스캐너를 걸러내기 위한 것이다.
//

import Foundation

nonisolated enum CandidateTransport: String, Sendable, CaseIterable {
    case tcp, udp
}

/// 후보의 출처. 우선순위는 host > srflx — 같은 LAN이면 굳이 공인 경로로 돌지 않는다.
nonisolated enum CandidateKind: String, Sendable {
    case host    // 로컬 인터페이스 주소
    case srflx   // STUN이 알려준 공인 매핑 (server-reflexive)

    var priority: Int {
        switch self {
        case .host: return 100
        case .srflx: return 50
        }
    }
}

nonisolated struct IceCandidate: Sendable, Hashable, CustomStringConvertible {
    let transport: CandidateTransport
    let kind: CandidateKind
    let endpoint: IPv4Endpoint

    var description: String { "\(transport.rawValue)/\(kind.rawValue) \(endpoint)" }
}

/// 한쪽이 상대에게 보내는 후보 묶음.
nonisolated struct CandidateBundle: Sendable {
    /// 16바이트. 프로브 패킷에 실린다. 양쪽이 상대의 토큰을 알고 있어야 프로브를 알아본다.
    let token: Data
    let candidates: [IceCandidate]
    /// 이 라운드에서 UDP까지 함께 뚫는가 (파일 상단 주석 참조). 기본은 false = TCP만.
    let fastUdp: Bool

    static let tokenLength = 16
    private static let version = "v1"
    /// 플래그 줄의 낱말. 모르는 낱말은 무시하므로 앞으로 더 붙어도 구버전이 안 깨진다.
    private static let flagFastUdp = "fastudp"

    init(token: Data, candidates: [IceCandidate], fastUdp: Bool = false) {
        self.token = token
        self.candidates = candidates
        self.fastUdp = fastUdp
    }

    static func randomToken() -> Data {
        var t = Data(count: tokenLength)
        t.withUnsafeMutableBytes { raw in
            guard let base = raw.baseAddress else { return }
            _ = SecRandomCopyBytes(kSecRandomDefault, tokenLength, base)
        }
        return t
    }

    func serialized() -> Data {
        var lines = ["\(Self.version) \(token.map { String(format: "%02x", $0) }.joined())"]
        if fastUdp { lines.append("f \(Self.flagFastUdp)") }
        for c in candidates {
            lines.append("c \(c.transport.rawValue) \(c.kind.rawValue) \(c.endpoint.address) \(c.endpoint.port)")
        }
        return Data(lines.joined(separator: "\n").utf8)
    }

    static func parse(_ data: Data) -> CandidateBundle? {
        guard let text = String(data: data, encoding: .utf8) else { return nil }
        var token: Data?
        var fastUdp = false
        var out: [IceCandidate] = []

        for raw in text.split(separator: "\n", omittingEmptySubsequences: true) {
            let f = raw.split(separator: " ", omittingEmptySubsequences: true).map(String.init)
            guard !f.isEmpty else { continue }

            if f[0] == version, f.count >= 2 {
                token = hexToData(f[1])
            } else if f[0] == "f" {
                // 한 줄에 여러 낱말이 올 수 있고, 모르는 낱말은 그냥 지나간다.
                if f.dropFirst().contains(flagFastUdp) { fastUdp = true }
            } else if f[0] == "c", f.count >= 5 {
                guard let t = CandidateTransport(rawValue: f[1]),
                      let k = CandidateKind(rawValue: f[2]),
                      let port = UInt16(f[4]) else { continue }   // 모르는 값은 조용히 건너뜀
                out.append(IceCandidate(transport: t, kind: k,
                                        endpoint: IPv4Endpoint(address: f[3], port: port)))
            }
            // 그 밖의 줄은 미래 확장 — 무시.
        }

        guard let token, token.count == tokenLength else { return nil }
        return CandidateBundle(token: token, candidates: out, fastUdp: fastUdp)
    }

    /// 시도 순서 — host 먼저, 같은 종류면 tcp·udp 순.
    func ordered(_ transport: CandidateTransport) -> [IceCandidate] {
        candidates
            .filter { $0.transport == transport }
            .sorted { $0.kind.priority > $1.kind.priority }
    }

    private static func hexToData(_ s: String) -> Data? {
        let chars = Array(s)
        guard chars.count % 2 == 0 else { return nil }
        var out = Data(capacity: chars.count / 2)
        var i = 0
        while i < chars.count {
            guard let b = UInt8(String(chars[i...i + 1]), radix: 16) else { return nil }
            out.append(b)
            i += 2
        }
        return out
    }
}

// MARK: - 로컬 후보 수집

nonisolated enum LocalCandidates {
    /// 상대가 도달할 수 없어 후보로 실어봐야 펀칭 시도만 낭비하는 대역.
    /// 169.254/16 링크로컬, 192.0.0/24(464XLAT CLAT — RFC 7335), 그리고 문서화용 예약 대역.
    private static let unreachablePrefixes = [
        "169.254.", "192.0.0.", "192.0.2.", "198.51.100.", "203.0.113.",
    ]

    /// 이 기기의 사설/로컬 IPv4 전부 (루프백·도달불가 대역 제외). 멀티홈이면 여러 개가 나온다.
    static func hostAddresses() -> [String] {
        var out: [String] = []
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let start = head else { return out }
        defer { freeifaddrs(head) }

        var cur: UnsafeMutablePointer<ifaddrs>? = start
        while let ptr = cur {
            defer { cur = ptr.pointee.ifa_next }
            let flags = Int32(ptr.pointee.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0,
                  let sa = ptr.pointee.ifa_addr,
                  sa.pointee.sa_family == sa_family_t(AF_INET) else { continue }

            let sin = sa.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee }
            guard let ep = IPv4Endpoint(sin) else { continue }
            guard !unreachablePrefixes.contains(where: { ep.address.hasPrefix($0) }) else { continue }
            if !out.contains(ep.address) { out.append(ep.address) }
        }
        return out
    }
}
