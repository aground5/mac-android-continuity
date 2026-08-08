//
//  MirrorSession.swift
//  Peri Display
//
//  캡처 → 인코딩 → (핸드셰이크/암호화) → LAN 송신 오케스트레이터.
//
//  세션 흐름 (Mac = responder):
//    클라 접속 → ClientHello(+DisplayCaps) 수신
//             → ServerHello 송신 + 키 파생
//             → 협상(코덱/해상도/fps/DPI) → SessionParams(암호화) 송신
//             → 협상값으로 가상 디스플레이/캡처/인코더 구성 (buildPipeline)
//             → UDP HELLO 수신 시 키프레임 → 암호화 영상 송출(STREAMING)
//
//  ※ 가상 디스플레이 크기/DPI/주사율과 인코더 해상도/코덱/fps는 ClientHello가
//    보고한 수신측(Android) 물리 디스플레이 특성에 맞춰 *접속 후* 결정된다.
//

import Foundation
import CryptoKit
import AppKit
import Combine
import CoreMedia
import Network
import OSLog
import SwiftProtobuf

private nonisolated let log = Logger(subsystem: "com.peri.display.mac", category: "session")

/// host clock µs (CMSampleBuffer PTS와 동일 도메인) — 지연 계측 파이프라인의 Mac 타임스탬프.
nonisolated func hostMicros() -> UInt64 {
    UInt64(max(0, CMClockGetTime(CMClockGetHostTimeClock()).seconds) * 1_000_000)
}

/// 핸드셰이크 완료 후 채워지는 암호화 세션 홀더 (스레드 안전).
nonisolated final class SessionBox: @unchecked Sendable {
    private let lock = NSLock()
    private var _s: PeriCryptoSession?
    var session: PeriCryptoSession? {
        get { lock.lock(); defer { lock.unlock() }; return _s }
        set { lock.lock(); _s = newValue; lock.unlock() }
    }
}

/// 현재 제어 채널의 클라 ID 홀더 (스레드 안전).
///
/// **가변인 이유**: 핸드오버(LTE↔Wi-Fi)로 Android IP가 바뀌면 TCP는 4-tuple이 깨져 죽지만
/// 세션 자체는 전송에 독립적이라 살아 있다. 새로 뚫은 연결로 제어 채널만 갈아끼우면 되는데,
/// 그러려면 "내가 어느 연결로 보내는가"가 바뀔 수 있어야 한다.
nonisolated final class ClientIDBox: @unchecked Sendable {
    private let lock = NSLock()
    private var _v: LanServer.ClientID
    init(_ v: LanServer.ClientID) { _v = v }
    var value: LanServer.ClientID {
        get { lock.lock(); defer { lock.unlock() }; return _v }
        set { lock.lock(); _v = newValue; lock.unlock() }
    }
}

/// 핸드셰이크 신원 바인딩 상태: 전사 해시 + 상대 cert를 스태시(ServerHello 시점)했다가
/// ClientAuth(3번째 메시지) 도착 시 서명을 검증한다. 스레드 안전.
/// 이 세션이 이미 정리됐는가 — LAN 큐/메인 액터 어디서든 읽어야 해 박스로 둔다.
private nonisolated final class FlagBox: @unchecked Sendable {
    private let lock = NSLock()
    private var _v = false
    var value: Bool {
        get { lock.lock(); defer { lock.unlock() }; return _v }
        set { lock.lock(); _v = newValue; lock.unlock() }
    }
}

/// STS가 확정한 상대 신원.
///
/// 두 출처가 있다. 보통은 **저장소 조회** — 서명을 저장된 공개키들로 시도검증해 누구인지
/// 알아낸다. 그때는 인증서가 선에 아예 오지 않으므로 `cert`가 없다. 예외는 케이블로 처음
/// 붙는 경우뿐이고, 그때만 상대가 신원을 실어 보내 신뢰를 새로 세울 재료가 된다.
private struct PeerIdentity {
    let pub: Data
    let name: String
    let fingerprint: String
    /// 케이블 부트스트랩 전용. 신뢰를 **새로 세울 때만** 필요하다.
    let cert: PeriCert?

    init(pub: Data, name: String, fingerprint: String, cert: PeriCert? = nil) {
        self.pub = pub; self.name = name; self.fingerprint = fingerprint; self.cert = cert
    }
    init(_ c: PeriCert) {
        self.init(pub: c.pub, name: c.name, fingerprint: c.fingerprint, cert: c)
    }
    init(_ p: TrustedPeer) {
        self.init(pub: p.pub, name: p.name, fingerprint: p.fingerprint, cert: nil)
    }
}

/// STS 라운드가 도는 동안의 재료.
///
/// 상대 인증서를 **여기에 미리 담지 않는다** — STS에서는 인증서가 봉인된 채로 마지막 패스에
/// 오므로, 검증 전에 "이 기기다"라고 말할 수 있는 시점 자체가 없다. 그게 옛 방식과의 차이다:
/// 예전엔 ClientHello의 평문 인증서를 주장으로 받아 UI에 먼저 뿌렸다.
private nonisolated final class AuthBox: @unchecked Sendable {
    private let lock = NSLock()
    private var _th: Data?
    private var _authKey: SymmetricKey?
    private var _authed = false
    func stash(th: Data, authKey: SymmetricKey) {
        lock.lock(); _th = th; _authKey = authKey; _authed = false; lock.unlock()
    }
    var pending: (th: Data?, key: SymmetricKey?) {
        lock.lock(); defer { lock.unlock() }; return (_th, _authKey)
    }
    func markAuthed() { lock.lock(); _authed = true; lock.unlock() }
    var authed: Bool { lock.lock(); defer { lock.unlock() }; return _authed }
}

/// Transport-CC 손실률 기반 AIMD 비트레이트 컨트롤러 (스레드 안전).
/// 세션별 UDP 피어 ID 홀더 (인코더 스레드에서 읽고 매니저가 LAN 큐에서 set → 스레드 안전).
nonisolated final class UDPPeerBox: @unchecked Sendable {
    private let lock = NSLock()
    private var _v: UUID?
    var value: UUID? {
        get { lock.lock(); defer { lock.unlock() }; return _v }
        set { lock.lock(); _v = newValue; lock.unlock() }
    }
}

/// 지금 열려 있는 데이터그램 세대 — Mac이 부여한 id와 거기서 갈라 나온 키.
///
/// 세션 키와 따로 두는 이유가 곧 이 설계의 요점이다: 세션은 TCP 하나로 오래 살고, 이 세대는
/// **최신성 경로를 쓰는 앱이 떠 있는 동안만** 산다. nil = 지금 데이터그램 경로가 없다.
///
/// 인코더 스레드가 읽고 매니저가 메인/LAN 큐에서 쓰므로 박스다.
nonisolated final class DatagramBox: @unchecked Sendable {
    private let lock = NSLock()
    private var _v: (id: Data, keys: PeriDatagramKeys)?
    var value: (id: Data, keys: PeriDatagramKeys)? {
        get { lock.lock(); defer { lock.unlock() }; return _v }
        set { lock.lock(); _v = newValue; lock.unlock() }
    }
}

// (제거됨) `StringBox` — 지문을 LAN 큐에서 서비스로 나르던 홀더.
// 이제 서비스가 기기에 매여 있어 지문을 **생성 시점에** 받는다(`ExtendService.fingerprint`).
// 세션이 그 값을 건네줄 이유가 사라졌다.

/// 스레드 안전 시각 홀더 (LAN 큐가 쓰고 MainActor 통계 루프가 읽는다).
private nonisolated final class DateBox: @unchecked Sendable {
    private let lock = NSLock()
    private var _v: Date?
    var value: Date? {
        get { lock.lock(); defer { lock.unlock() }; return _v }
        set { lock.lock(); _v = newValue; lock.unlock() }
    }
}

/// [DateBox]와 같은 이유로 있는 상자 — 수신 큐(nonisolated)가 쓰고 MainActor가 읽는다.
private nonisolated final class IntervalBox: @unchecked Sendable {
    private let lock = NSLock()
    private var _v: TimeInterval?
    var value: TimeInterval? {
        get { lock.lock(); defer { lock.unlock() }; return _v }
        set { lock.lock(); _v = newValue; lock.unlock() }
    }
}

/// 인증이 끝날 때까지 붙들어 두는 ClientHello (LAN 큐가 쓰고 MainActor가 꺼낸다).
/// 한 번만 꺼내진다 — 재개로 같은 세션이 다시 등록부에 붙어도 재협상이 일어나면 안 된다.
private nonisolated final class HelloBox: @unchecked Sendable {
    private let lock = NSLock()
    private var _v: PeriStsHello?
    func put(_ v: PeriStsHello) { lock.lock(); _v = v; lock.unlock() }
    func take() -> PeriStsHello? {
        lock.lock(); defer { lock.unlock() }
        let v = _v; _v = nil; return v
    }
}

/// 이 세션이 실행 중인 기기의 서비스 등록부 (LAN 큐에서 라우팅에 쓰므로 박스).
/// 신원이 확정되기 전에는 비어 있다 — 익명 연결에는 넘겨줄 기기가 없다.
private nonisolated final class ServicesBox: @unchecked Sendable {
    private let lock = NSLock()
    private var _v: AppHost?
    var value: AppHost? {
        get { lock.lock(); defer { lock.unlock() }; return _v }
        set { lock.lock(); _v = newValue; lock.unlock() }
    }
}

/// 스레드 안전 Data 홀더 (LAN 큐와 MainActor가 함께 읽고 쓴다).
private nonisolated final class DataBox: @unchecked Sendable {
    private let lock = NSLock()
    private var _v: Data?
    var value: Data? {
        get { lock.lock(); defer { lock.unlock() }; return _v }
        set { lock.lock(); _v = newValue; lock.unlock() }
    }
}

/// 한 Android 클라이언트당 하나 — 암호 세션·제어 채널·생존 판정을 캡슐화.
///
/// **서비스가 뭔지 모른다**(DESIGN.md §4). 캡처도 인코더도 가상 디스플레이도 여기 없다 —
/// 세션이 하는 일은 신원을 확정하고, 봉인된 통로([ServiceChannel])를 발급하고, 자기 것이
/// 아닌 메시지를 기기의 서비스 등록부로 넘기는 것뿐이다.
///
/// LanServer는 매니저가 소유하고 주입하며, 이 세션은 자기 clientID/udpPeer로만 송수신한다.
@MainActor
final class Session: ObservableObject, Identifiable {
    nonisolated let id = UUID()
    /// **예외 상황만** 담는 부가 문구(인증 거부 등). 평상시엔 nil이다.
    ///
    /// 평범한 진행 상태("핸드셰이크 중", "송출 중"…)는 기기 상태 기계가 계산하고
    /// ([DeviceState.summary]), 파이프라인 사정(디스플레이 복구·구성 실패)은 서비스가 낸다.
    /// 여기 남는 건 세션 자신이 아는 것뿐이다.
    @Published private(set) var detail: String?
    @Published private(set) var deviceName = "기기"
    /// 기기 신원 지문 (PeriCert.fingerprint). 핸드셰이크에서 확정 — UI가 LAN/AOA 세션을
    /// 기기 하나로 머지하는 1급 키 (DisplayManager·TrustStore와 같은 신원 축). 그전엔 nil.
    @Published private(set) var fingerprint: String?
    /// 이 무선 연결이 실제로 타고 있는 망 — LAN / 외부 Wi-Fi / LTE. 자세한 판정 근거는
    /// [WirelessPath]. AOA(USB) 세션에서는 의미가 없다(전송이 케이블이다).
    @Published private(set) var wireless: WirelessPath = .unknown
    /// 클라이언트가 보고한 망 종류. Mac 혼자서는 LTE와 남의 Wi-Fi를 구분할 수 없다.
    private var reportedNetwork: PeriClientNetwork = .unspecified

    /// 받아 둔 ClientHello — **인증이 끝난 뒤에야** 서비스에 배달한다.
    ///
    /// 검증 전에 넘기면 아무나 남의 인증서를 주장해 그 기기의 상태를 흔들 수 있다(`adopt` 주석).
    /// 그래서 세션은 핸드셰이크 동안 이걸 들고만 있고, `attach` 직후 `receive`로 되돌려 준다 —
    /// 무엇을 어떤 코덱·크기로 보낼지는 세션이 아니라 서비스가 정하기 때문이다.
    private let pendingHello = HelloBox()

    /// 보고값 또는 피어 주소가 바뀌었을 수 있다 → 다시 판정한다.
    ///
    /// 매 Ping(1Hz)마다 불린다. `getifaddrs` 한 번이라 1Hz에서는 무시할 만하고, 값이 그대로면
    /// `@Published`도 발행하지 않는다 — 재개로 경로가 갈리는 순간을 놓치지 않으려면 주기적
    /// 재판정이 필요하다(RESUME은 새 ClientHello를 만들지 않는다).
    private func refreshWireless() {
        guard !reliableTransport else { return }   // USB는 IP 망 종류와 무관
        let peer = (server as? LanServer)?.peerAddress(clientID)
        let next = WirelessPath.resolve(peerAddress: peer, reported: reportedNetwork)
        if next != wireless {
            log.info("경로 종류: \(self.wireless.label) → \(next.label) (피어 \(peer ?? "—"))")
            wireless = next
        }
        apps?.extend.setLinkDetail("\(next.label) \(peer ?? "—")")
    }

    /// 신뢰·순서보장·광대역 전송(AOA)인가. 이 세션은 판정만 하고 쓰지 않는다 — 그 사실이
    /// 무슨 뜻인지(초기 동작점, 감속 신호)는 채널의 [BearerCaps]를 통해 서비스가 해석한다.
    nonisolated let reliableTransport: Bool

    /// teardown이 돌았는가. 늦게 도착한 콜백이 죽은 세션을 되살리지 못하게 막는다.
    private let tornBox = FlagBox()

    private let clientIDBox: ClientIDBox
    /// 현재 제어 채널. 핸드오버 재협상으로 갈릴 수 있어 매번 읽는다(캡처해 두면 옛 연결로 보낸다).
    nonisolated var clientID: LanServer.ClientID { clientIDBox.value }

    /// 새로 뚫린 연결로 제어 채널을 갈아끼운다 — 세션·크립토·디스플레이는 그대로.
    nonisolated func rebindClient(_ id: LanServer.ClientID) {
        clientIDBox.value = id
        lastPingBox.value = Date()   // 새 채널의 생존 시계는 지금부터
        pingGapBox.value = nil       // 박자도 새로 재기 시작한다(옛 주기로 오판 금지)
        // 채널이 갈렸다 = 피어 주소가 바뀌었을 수 있다(LTE↔Wi-Fi 전환이 곧 이 경로다).
        // 다음 Ping을 기다리지 않고 바로 다시 판정한다 — 최대 1초 동안 낡은 배지가 뜨는 걸 막는다.
        Task { @MainActor in self.refreshWireless() }
    }

    /// 마지막 Ping 수신 시각 — Mac 쪽 생존 판정의 기준.
    ///
    /// **왜 필요한가**: Mac은 여태 Ping에 Pong만 답할 뿐 자기 타임아웃이 없었다. 그래서
    /// 셀룰러가 FIN 없이 사라지는 핸드오버에서는 TCP가 재전송 타임아웃까지 수 분간 매달리고,
    /// 그동안 좀비 세션이 아무도 안 보는 화면을 계속 인코딩한다(HW 인코더·CPU 낭비 +
    /// 가상 디스플레이 점유 유지). 끊김을 우리 쪽에서도 판정해야 한다.
    private let lastPingBox = DateBox()

    /// 상대가 Ping을 보내는 **간격**(관측값). 아직 두 번을 못 받았으면 nil.
    private let pingGapBox = IntervalBox()

    /**
     이만큼 Ping이 없으면 죽은 것으로 본다 — **상대의 박자를 따라간다.**

     기기는 세션 유지 비용을 아끼려고 영상이 흐를 때만 1초로 두드리고, 놀 때는 훨씬 느리게
     보낸다. 그런데 여기가 6초로 고정이면 노는 세션을 **멀쩡한데 죽었다고 판정한다.**

     상수를 크게 잡는 대신 관측한 간격에 배수를 곱한다. 그래야 영상이 흐를 때의 판정 속도는
     지금 그대로(1초 간격 → 6초)이고, 노는 세션에서만 느슨해진다. 프로토콜에 "내 주기는
     N초다"를 싣지 않아도 되는 건 덤이다 — 박자는 이미 관측할 수 있는 사실이다.

     노는 동안 느슨해지는 게 안전한 이유: 그 구간에는 화면도 소리도 안 나가므로, 늦게
     알아채도 잃는 게 "붙어 있다고 표시된 시간"뿐이다. 영상이 흐를 때는 프레임이 끊기는
     것 자체가 훨씬 빠른 신호이기도 하다.
     */
    private var livenessDeadline: TimeInterval {
        let gap = pingGapBox.value ?? Self.assumedPingGap
        return max(Self.livenessTimeout, gap * Self.livenessGapFactor)
    }

    /// 하한. 상대가 아무리 빨리 보내도 이보다 짧게 판정하지 않는다(순간적인 지연 오탐 방지).
    private static let livenessTimeout: TimeInterval = 6
    /// 관측 간격의 몇 배까지 기다릴까. 한두 번 놓치는 건 흔하다.
    private static let livenessGapFactor: Double = 6
    /// 아직 박자를 모를 때 가정하는 간격.
    private static let assumedPingGap: TimeInterval = 1

    // (제거됨) `onLivenessLost` 콜백 박스. 생존 감지 실패는 이제 사실로만 보고되고
    // (`DeviceEv.livenessLost`), 연결을 닫을지는 기계가 정한다 — 예전엔 이 콜백이 곧바로
    // `closeClient`를 불러서, 등록부는 왜 끊겼는지 모른 채 결과만 봤다.

    // (제거됨) `requirePairing` — 미페어링 기기를 거부할지의 토글.
    //
    // 값은 줄곧 true였고 false 가지는 TOFU 자동핀(처음 보는 기기를 사람 확인 없이 신뢰)이었다.
    // "BLE-PAKE 들어오면 없앤다"는 임시 조치였는데, 그 페어링이 이제 양방향 SAS/OTP까지
    // 갖췄으므로 지울 때가 됐다. 토글로 남겨 두면 한 글자로 그 사슬이 통째로 무력화된다 —
    // 사람 확인을 강제하려고 만든 것을 사람이 끌 수 있게 두는 건 앞뒤가 안 맞는다.
    // 이제 페어링은 **구조적으로 필수**다: 신뢰저장소에 없는 신원은 어느 경로로도 못 붙는다.

    private let server: any Bearer
    private let udpPeerBox = UDPPeerBox()
    private let datagramBox = DatagramBox()
    /// 서비스 절반이 세션 절반에게 말하는 유일한 통로. 봉인·경로 선택·조각화·페이싱을 소유한다.
    /// (패킷 순번·페이서가 여기서 사라진 이유 — 전부 "어느 파이프로 나가는가"의 문제였다.)
    ///
    /// 세션이 서비스에게 **주는** 것이지 세션이 쓰는 것이 아니다 — `adopt`이 기기의 등록부에
    /// 이 채널을 붙인다(`attach`).
    let channel: ServiceChannel
    private let sessionBox = SessionBox()
    private let authBox = AuthBox()

    /// 생존 감시 루프 (Ping 끊김 판정). 통계·복구 루프는 서비스가 따로 돌린다 —
    /// 하나였을 때는 "세션이 살아 있는가"와 "디스플레이가 오염됐는가"가 한 타이머에 얽혀 있었다.
    private var livenessTask: Task<Void, Never>?

    // nonisolated: LAN 큐 콜백(매니저)에서 동기로 생성 가능해야 함(곧이은 onFrame 라우팅 레이스 방지).
    nonisolated init(server: any Bearer, clientID: LanServer.ClientID, reliableTransport: Bool = false) {
        self.server = server
        let ids = ClientIDBox(clientID)
        self.clientIDBox = ids
        self.reliableTransport = reliableTransport
        // 서비스가 세션에 대해 알 전부.
        self.channel = ServiceChannel(sessionBox: sessionBox, bearer: server,
                                      clientIDBox: ids, udpPeerBox: udpPeerBox,
                                      datagramBox: datagramBox)
    }

    /// 매니저가 세션 생성 직후 호출(MainActor): 생존 감시 시작.
    func begin() {
        guard livenessTask == nil else { return }
        // 생존 판정 — Ping이 끊기면 우리가 먼저 정리한다. TCP가 알아서 알려주길
        // 기다리면 FIN 없이 사라진 경우(핸드오버) 수 분이 걸린다.
        livenessTask = Task { @MainActor [weak self] in
            while !Task.isCancelled, let self {
                try? await Task.sleep(for: .seconds(1))
                if Task.isCancelled { break }
                let limit = self.livenessDeadline
                if self.livenessEnabled, let last = self.lastPingBox.value,
                   Date().timeIntervalSince(last) > limit {
                    log.error("생존 감지 실패 — \(Int(limit))s간 Ping 없음")
                    self.livenessEnabled = false   // 중복 보고 방지
                    if let fp = self.boundFingerprint { self.manager?.dispatch(fp, .livenessLost) }
                    break
                }
            }
        }
    }

    // MARK: - 기기 등록부와의 접점
    //
    // 이 세션은 **연결 하나를 실행하는 기계**일 뿐 수명을 결정하지 않는다. 관측한 사실을
    // 등록부에 올리고, 등록부가 상태 기계를 돌려 내린 지시를 여기서 수행한다.

    private(set) var boundFingerprint: Fingerprint?
    private(set) weak var manager: SessionManager?
    /// 이 연결의 식별자 — 기계가 "지금 쓰는 경로가 이것인가"를 판정하는 값.
    /// `rebindClient`로 바뀌므로 읽는 시점의 값이다(재개 후에는 새 연결을 가리킨다).
    nonisolated var connID: ConnID { ConnID(raw: clientID, via: reliableTransport ? .aoa : .lan) }
    /// 생존 감지를 돌릴지 — 세션이 서고 경로가 살아 있을 때만 참이다.
    private var livenessEnabled = false

    /// 이 기기의 서비스 등록부. **세션이 소유하지 않는다** — 기기가 소유하고 세션은 빌려 본다.
    /// 익명 구간(신원 확정 전)에는 nil이고, 그동안 도착하는 서비스 메시지는 갈 곳이 없어 버려진다.
    private let servicesBox = ServicesBox()
    nonisolated var apps: AppHost? { servicesBox.value }

    func setManager(_ m: SessionManager) {
        manager = m
        // 앱 등록부가 "최신성 경로가 필요하다/필요 없다"를 채널에 말하면, 그걸 실현하는 건
        // 매니저다(후보 수집·펀칭·소켓이 거기 있다). 세션은 둘을 잇기만 한다.
        channel.onDatagramPathWanted { [weak self] want in
            Task { @MainActor [weak self] in
                guard let self, let mgr = self.manager else { return }
                if want { mgr.openDatagramPath(for: self) }
                else { mgr.closeDatagramPath(for: self, reason: "쓰는 앱이 없다") }
            }
        }
    }

    func bindDevice(fingerprint fp: Fingerprint, manager m: SessionManager,
                    services: AppHost) {
        boundFingerprint = fp
        // ★ **발행도 해야 한다.** `boundFingerprint`만 채우면 등록부는 이 세션이 누구 것인지
        // 알지만 UI는 모른다 — 목록은 `DeviceModel.merge`에서 `session.fingerprint`로 기기 행에
        // 세션을 얹는데, 그게 nil이면 `overlay`가 통째로 건너뛴다.
        //
        // 증상이 엉뚱하게 나온다: 세션은 멀쩡히 붙어 있고 화면도 흐르는데, 기기 행은 계속
        // '페어링됨(오프라인)'이라 **연결 버튼이 그대로 남는다.** 로그 어디에도 이상이 없다.
        fingerprint = fp
        manager = m
        servicesBox.value = services
    }

    func setLivenessEnabled(_ on: Bool) {
        livenessEnabled = on
        // 켜는 순간을 기준점으로 — 옛 시각·옛 박자로 즉시 오탐하지 않게 둘 다 리셋한다.
        if on { lastPingBox.value = Date(); pingGapBox.value = nil }
    }

    /// 등록부에 붙은 직후, 받아 뒀던 ClientHello를 서비스에 배달한다.
    ///
    /// **여기가 협상의 시작점이다.** 세션은 그 안에 뭐가 들었는지 모르고 — 해상도든 코덱이든
    /// 다이내믹 레인지든 — 신원이 확정될 때까지 붙들고 있었을 뿐이다.
    func deliverPendingHandshake() {
        guard let hello = pendingHello.take(), let apps else { return }
        var m = PeriControlMessage(); m.stsHello = hello
        apps.receive(.control(m))
    }

    /// 매니저가 UDP HELLO 시도복호화로 이 세션을 매칭했을 때 → 영상 피어 고정.
    ///
    /// 그 다음에 무엇을 할지(키프레임을 새로 낼지)는 **채널이 알리고 서비스가 정한다** —
    /// 세션은 "최신성 경로가 열렸다"는 사실까지만 안다.
    nonisolated func linkUdp(_ peerID: LanServer.UDPPeerID) {
        udpPeerBox.value = peerID
        channel.noteDatagramPathReady()
        log.info("udp peer linked")
    }

    /// 이 UDP 피어가 지금 이 세션의 것인가 — 매니저가 수신 영상을 디먹스하는 근거.
    nonisolated func ownsUdpPeer(_ id: LanServer.UDPPeerID) -> Bool { udpPeerBox.value == id }

    /// 손실 허용 경로로 온 영상 조각 하나. **LAN 큐에서 동기로** 불린다.
    ///
    /// 조각이 다 모여야 봉인이 풀리므로 대부분의 호출은 아무 일도 하지 않고 돌아간다 —
    /// 그 판단은 채널이 한다(`openMedia`).
    nonisolated func receiveUdpVideo(_ rest: Data) {
        guard let frag = WireFormat.parseMiniRtp(rest),
              let (plain, meta) = channel.openMedia(frag) else { return }
        servicesBox.value?.receive(.media(plain, meta))
    }

    // MARK: - 데이터그램 세대
    //
    // 세션은 TCP 하나로 오래 살고, 이 세대는 **최신성 경로를 쓰는 앱이 떠 있는 동안만** 산다.
    // 여는 것도 닫는 것도 매니저가 지시한다 — 후보 수집과 펀칭이 거기 있기 때문이다.

    /// 지금 열려 있는 세대 id. nil = 데이터그램 경로 없음.
    nonisolated var datagramChannelID: Data? { datagramBox.value?.id }

    /// 세대를 연다. **왕복이 없다** — 키는 이미 합의한 세션 재료에서 이 id를 소금으로
    /// 갈라 나오므로, 이 한 줄이 도는 순간 양쪽이 같은 키를 갖는다(0-RTT).
    ///
    /// 세션 키가 아직 없으면(핸드셰이크 전) 실패한다 — 그 구간에는 갈라낼 재료가 없다.
    @discardableResult
    nonisolated func openDatagram(channelID: Data) -> Bool {
        guard let session = sessionBox.session else { return false }
        datagramBox.value = (id: channelID, keys: session.datagram(channel: channelID))
        return true
    }

    /// 세대를 닫는다 — 키를 버리고 영상 피어를 놓는다. NAT 매핑은 아무도 안 두드리면 스스로 닫힌다.
    nonisolated func closeDatagram() {
        datagramBox.value = nil
        udpPeerBox.value = nil
        channel.resetMediaReceive()   // 다음 세대의 조각과 섞이면 안 된다
    }

    /// UDP HELLO가 **이 세대의 것인가.** 매니저의 디먹스 판정.
    ///
    /// 두 겹이다: 평문 id가 우리 세대와 같은가(조회), 그리고 봉인이 이 세대 키로 열리는가
    /// (검증). 열린 평문이 다시 그 id여야 한다 — 그래야 앞의 평문 접두를 바꿔치기해서
    /// 남의 세대인 척할 수 없다.
    nonisolated func acceptsHello(channel id: Data, nonce: Data, body: Data) -> Bool {
        guard let d = datagramBox.value, d.id == id else { return false }
        guard let plain = try? d.keys.receiver.open(nonce: nonce, body: body) else { return false }
        return plain == id
    }

    /// RESUME 프레임이 이 세션 키로 열리면 그 평문(펀치 토큰) — 매니저의 재협상 디먹스용.
    /// 핸드셰이크 전(키 없음) 세션은 항상 nil이라 신생 세션이 자기 자신을 매칭할 일은 없다.
    nonisolated func openResume(_ payload: Data) -> Data? {
        guard let session = sessionBox.session, payload.count > 12 else { return nil }
        let nonce = payload.prefix(12)
        let body = payload.suffix(from: payload.startIndex + 12)
        return try? session.receiver.open(nonce: Data(nonce), body: Data(body))
    }

    /// Mac에서 이 기기 연결을 끊는다 (사용자가 '연결 해제'를 누름).
    ///
    /// Android에 Bye(HOST_DISCONNECT)를 먼저 보내는 게 핵심이다 — 안 보내면 Android는 그저
    /// 끊긴 걸로 보고 keepalive 실패 후 곧장 재접속해서 세션이 되살아난다. Bye를 받은 쪽은
    /// 재접속을 멈추고 대기한다. 그 뒤 파이프라인을 내리고 가상 디스플레이까지 정리한다
    /// (사용자 의사가 명확하므로 유예 없이 — Android '끊기'와 대칭).
    func requestDisconnect() async {
        sendBye(reason: .hostDisconnect)
        // ★ **의도를 먼저 알린다.** 예전엔 Bye 플러시를 기다린다며 120ms를 잔 뒤에 알렸는데,
        // 그 사이 Android가 Bye를 받고 자기 쪽을 닫아 `connLost`가 **먼저** 도착했다.
        // 기계는 그걸 사고로 보고 "경로 재탐색 중 · 60s 유예"로 갔다가, 뒤늦게 온 `bye`가
        // 그걸 취소했다 — 사용자가 누른 종료인데 화면에 재협상 문구가 스치고 유예 타이머가
        // 헛돌았다(실기기 로그).
        //
        // 그 대기는 애초에 필요가 없었다. `LanServer.closeClient`는 `NWConnection.cancel()`을
        // 쓰는데 그건 graceful이라 대기 중인 송신을 흘려보낸 뒤 닫는다. 즉 Bye는 잘리지 않는다.
        //
        // 정리 자체는 기계가 한다(캡처 → 디스플레이 순서 포함). 등록부에 없는 익명 연결이면
        // 여기서 직접 내린다 — 어차피 잡고 있는 디스플레이도 없다.
        if let fp = boundFingerprint {
            manager?.dispatch(fp, .bye(reason: "Mac에서 연결 해제"))
        } else {
            try? await Task.sleep(for: .milliseconds(120))   // 익명 연결엔 알릴 기계가 없다
            await teardown()
        }
    }

    /// 신원 거부 — 사유를 실어 보낸 **뒤** 연결을 닫는다.
    ///
    /// 순서가 곧 의미다. Bye가 선에 오르기 전에 닫으면 클라이언트에겐 그냥 끊긴 것으로 보인다.

    /// PQ-STS Pass 3 — 봉인을 열어 **신원과 서명을 동시에** 얻는다.
    ///
    /// 봉인이 열렸다는 것 자체가 상대도 같은 K에 도달했다는 증거이고(키 확인), 서명은 전사
    /// 전체 — 즉 협상 내용까지 — 를 덮는다.
    ///
    /// HANDSHAKE 프레임으로 온다(Pass 1·2와 같다). 세션 키가 아니라 K로 봉인돼 있으므로
    /// 세션이 서기 전에 열 수 있고, 열리는 그 순간이 세션이 서는 순간이다.
    private nonisolated func onStsConfirm(_ confirm: PeriStsConfirm, srv: any Bearer) {
        let (th, authKey) = authBox.pending
        // ★ 서명 하나로 **누구인지** 알아낸다 — 저장된 공개키를 하나씩 넣어 본다.
        // 예전엔 실려 온 인증서를 받아 "신뢰 목록에 있나"만 봤는데, 그건 신원이 아니라
        // 집합 소속이다. 여기서 나온 값은 곧 신원이라 아래 신뢰 검사가 군더더기가 된다.
        let resolved: PeerIdentity? = {
            guard let th, let authKey,
                  let plain = PeriSts.openAuth(authKey, confirm.sealedAuth, role: .client),
                  let a = try? PeriStsAuth(serializedBytes: plain)
            else { return nil }
            let signed = PeriSts.clientSigningBytes(th)
            if let peer = PeriSts.resolveSigner(signature: a.signature, over: signed,
                                                among: TrustStore.shared.all()) {
                return PeerIdentity(peer)
            }
            // 조회 실패 = 우리가 모르는 기기. 케이블로 처음 붙는 경우에만 상대가
            // 신원을 실어 보내고, 그 값으로 아래 USB 신뢰 부트스트랩이 돈다.
            guard srv is AoaBearer, !a.identityCert.isEmpty,
                  let c = PeriCert.parse(a.identityCert), c.verifySelfSignature(),
                  PeriIdentity.verify(pub: c.pub, signature: a.signature, over: signed)
            else { return nil }
            return PeerIdentity(c)
        }()
        guard let cert = resolved else {
            log.error("client identity verification FAILED")
            // ★ 닫기 전에 **거부를 알린다.** 그냥 닫으면 클라이언트는 평범한 끊김과
            // 구분하지 못해 거부하는 Mac에게 영원히 재접속·재협상을 반복한다.
            rejectAndClose("기기 인증 실패 — 연결 거부")
            return
        }
        // ★ 신뢰는 **사람이 확인한 경로**에서만 생긴다. 서명이 맞다는 건 "이 키의
        // 주인이 맞다"일 뿐 "이 기기를 신뢰해도 된다"가 아니다.
        //
        // 그 확인을 받는 경로는 둘이다:
        //  1. BLE 페어링 — 사람이 코드를 비교하거나 패스코드를 옮겨 적는다.
        //  2. **USB 케이블** — 케이블 자체가 인증자다. 꽂으려면 두 기기를 물리적으로
        //     쥐고 있어야 하고, Android 쪽은 OS의 액세서리 권한 대화상자가 사람 확인을
        //     이미 받아 놨다. 무선으로는 흉내 낼 수 없는 증거다.
        //
        // 저장소 조회로 나온 신원은 정의상 이미 신뢰된 기기다 — 그래서 이 갈래로
        // 들어오는 건 케이블 부트스트랩(`cert`가 실려 온 경우)뿐이다.
        if TrustStore.shared.state(of: cert.pub) != .trusted {
            guard srv is AoaBearer, let pending = cert.cert else {
                log.error("미페어링 기기 → 거부 [\(cert.fingerprint)]")
                rejectAndClose("미페어링 기기 — 먼저 페어링하세요")
                return
            }
            // 케이블로 왔다 → 사용자에게 한 번만 묻고 신뢰를 세운다.
            //
            // 왜 그냥 신뢰하지 않는가: 자리를 비운 Mac에 남의 폰을 5초 꽂는 것만으로
            // **지속적인 무선 접근권**이 생기면 곤란하다. 물리 접근이 있었다는 사실이
            // 곧 주인의 의사는 아니다. 코드 비교까지는 필요 없다(케이블이 채널을
            // 인증했다) — 의사 확인 한 번이면 그 격차가 닫힌다.
            let session = self
            Task { @MainActor in
                let willAsk = self.manager?.requestUsbTrust(cert: pending) { accepted in
                    if accepted {
                        _ = TrustStore.shared.trust(pending)
                        log.notice("USB 신뢰 승인: \(pending.name) [\(pending.fingerprint)]")
                        session.resumeAfterUsbTrust(cert: pending)
                    } else {
                        log.notice("USB 신뢰 거부됨: \(pending.fingerprint)")
                        session.rejectAndClose("사용자가 이 기기의 신뢰를 거부했습니다")
                    }
                }
                // 다른 기기 때문에 물어보지도 못했으면 그 사실을 알린다. 같은 기기의
                // 재시도(willAsk == true)는 **조용히 흘려보낸다** — 거절을 보내면
                // 클라가 치명 래치를 걸어 승인 뒤에도 안 붙는다(위 주석 참조).
                if willAsk == false {
                    session.rejectAndClose("다른 기기의 신뢰 확인이 진행 중입니다")
                }
            }
            return   // 승인될 때까지 세션을 세우지 않는다
        }
        completeAuth(cert)
    }

    /// 인증이 확정된 뒤의 공통 마무리 — 등록부 합류 + 제원 + 세션 확립.
    ///
    /// 두 경로가 여기로 모인다: 이미 신뢰된 기기의 ClientAuth, 그리고 USB 케이블로
    /// 신뢰를 새로 세운 뒤의 재개. 갈래마다 따로 쓰면 한쪽이 단계를 빠뜨린다.
    private nonisolated func completeAuth(_ id: PeerIdentity) {
        TrustStore.shared.touch(id.pub)
        authBox.markAuthed()
        // 익명 마감 해제 — AOA(케이블)는 리스너를 안 거치므로 해당 없음.
        (server as? LanServer)?.noteAuthenticated(clientID)
        log.info("client identity authenticated: \(id.name) [\(id.fingerprint)]")
        let conn = self.connID
        let cert = id
        Task { @MainActor in
            self.deviceName = cert.name
            // ★ **등록은 여기가 처음이다.** 예전엔 ClientHello 시점에 `adopt`을 불렀는데,
            // 그때 지문은 상대가 **주장한** 인증서에서 뽑은 값이고 서명 검증은 아직이다.
            //
            // 그래서 이런 게 됐다: 인증서는 공개값이고 ClientHello는 평문이라 한 번만
            // 엿들으면 누구나 남의 인증서를 그대로 실어 보낼 수 있다. 그 ClientHello가
            // `adopt`을 태우면 등록부는 그걸 "그 기기의 새 연결"로 보고 **살아 있던
            // 진짜 세션을 내려버린다**(`adopt`의 이전 세션 교체). 공격자는 개인키가 없어
            // 10초 뒤 마감으로 쫓겨나지만 피해자 화면은 이미 끊긴 뒤고, 10초마다
            // 반복하면 지속적인 서비스 거부가 된다. 디스플레이가 파괴·재생성되므로
            // SCK 재열거를 타고 **붙어 있는 다른 기기까지** 흔들린다.
            //
            // 이제 신원 서명이 검증된 뒤에만 등록부에 들어간다. 그 전 구간은 등록부에
            // 아무 흔적도 남기지 않는 순수한 익명 연결이고, `LanServer`의 연결 단위
            // 마감이 수명을 짊어진다.
            await self.manager?.adopt(session: self, fingerprint: cert.fingerprint, conn: conn)
            // 이제 기기가 정해졌다 → 붙들고 있던 ClientHello를 서비스에 넘긴다.
            // 서비스가 협상해 `specChanged`를 사실로 올리고, 그 다음 줄의 `sessionLive`가
            // 파이프라인을 세우는 방아쇠가 된다(둘 다 같은 이벤트 큐라 순서가 보장된다).
            self.deliverPendingHandshake()
            // 인증된 기기만 화면을 받는다.
            self.manager?.dispatch(cert.fingerprint, .sessionLive(peer: cert.name))
        }
    }

    /// 사용자가 USB 신뢰를 승인했다 → 멈춰 뒀던 인증을 이어서 마무리한다.
    ///
    /// ★ **죽은 세션이면 아무것도 하지 않는다.** 대화상자가 떠 있는 동안 Android의 핸드셰이크
    /// 워치독(8s)이 먼저 터져 이 연결이 이미 정리됐을 수 있는데, 그때 `completeAuth`를 돌리면
    /// 죽은 세션이 등록부에 합류하며 **살아 있는 다른 세션을 밀어낸다**(`adopt`의 교체 규칙).
    ///
    /// 그래도 신뢰는 이미 저장됐으므로 손해가 없다 — AOA는 액세서리 폴링이 몇 초 뒤 다시
    /// 붙고, 그때는 신뢰된 기기라 아무것도 묻지 않고 통과한다.
    nonisolated func resumeAfterUsbTrust(cert: PeriCert) {
        guard !tornBox.value else {
            log.info("USB 신뢰 승인 — 이 연결은 이미 끊겼다. 다음 재연결에서 통과한다.")
            return
        }
        completeAuth(PeerIdentity(cert))
    }
    /// 상대에게 거부 사유를 알리고 닫는다. 핸드셰이크 거부와 페어링 해제가 같이 쓴다 —
    /// 둘 다 "이 연결은 더 못 쓴다"를 **상대가 알아야** 재시도를 멈추기 때문이다.
    nonisolated func rejectAndClose(_ why: String) {
        sendBye(reason: .authRejected)
        let id = clientID
        Task { @MainActor [weak self] in
            self?.detail = why
            try? await Task.sleep(for: .milliseconds(120))   // Bye가 나갈 틈
            self?.manager?.closeConnection(id)
        }
    }

    /// 세션 종료 선언 전송. 핸드셰이크 전이면 보낼 채널이 없으므로 조용히 건너뛴다.
    private nonisolated func sendBye(reason: PeriBye.Reason) {
        var bye = PeriBye(); bye.reason = reason
        var m = PeriControlMessage(); m.bye = bye
        if channel.sendReliable(m) { log.info("bye 송신 (\(String(describing: reason)))") }
    }
    /// 세션 종료 — **세션 것만 정리한다.**
    ///
    /// 서비스는 손대지 않는다. 무엇을 지키고 무엇을 버릴지는 서비스가 `detach`에서 정하고,
    /// 그걸 언제 부를지는 기기 상태 기계가 정한다. 예전엔 여기서 파이프라인까지 내렸는데,
    /// 그러면 "연결이 죽었다"와 "기기가 떠났다"가 한 함수에 뭉쳐 구분이 사라진다.
    func teardown() async {
        tornBox.value = true
        livenessTask?.cancel(); livenessTask = nil
        servicesBox.value = nil
        // 세대는 세션보다 오래 살 수 없다 — 키를 여기서 놓지 않으면 죽은 세션이 남은 세대
        // id로 HELLO를 받아들인다. 상대에게 알릴 채널은 이미 없으므로 조용히 놓는다.
        closeDatagram()
    }

    // MARK: - 핸드셰이크/제어 (LAN 큐에서 호출, nonisolated)

    /// 제어/핸드셰이크 프레임 처리 (매니저가 LAN 큐에서 호출, nonisolated). 멤버를 지역명에 묶어 본문 유지.
    nonisolated func handle(_ type: WireType, _ payload: Data) {
        let box = sessionBox, srv = server
        switch type {
        case .handshake:
            // ★ **STS 세 패스가 전부 이 프레임 종류로 온다.** Pass 3(`stsConfirm`)을 `.control`
            // 에서 받던 때가 있었는데, 기기는 그걸 `handshakeFrame`으로 보내므로 그 갈래에는
            // 영영 닿지 않았다. 증상이 고약했다: 파싱이 실패하는 게 아니라 **조용히 버려져서**,
            // Mac은 익명 타이머(10초)로 소켓을 닫고 기기는 30초 뒤 "Mac 응답 없음"으로 죽었다.
            // 양쪽 로그 어디에도 "이상한 프레임이 왔다"는 말이 없었다.
            //
            // Pass 3이 CONTROL이 아니어야 하는 이유도 분명하다 — 그 안의 신원은 이미 K로
            // 봉인돼 있고, 그 봉인이 열리는 것 자체가 이 핸드셰이크의 마지막 단계다. 세션 키로
            // 한 겹 더 싸면 "세션이 서기 전에 세션 키가 필요한" 순환이 된다.
            guard let msg = try? PeriControlMessage(serializedBytes: payload) else { return }
            if case .stsConfirm(let confirm)? = msg.payload {
                onStsConfirm(confirm, srv: srv)
                return
            }
            // PQ-STS Pass 1. **여기서 상대가 누군지 알 수 없다** — 인증서는 Pass 3에 봉인돼
            // 오기 때문이다. 옛 방식은 ClientHello의 평문 인증서를 주장으로 받아 UI에 먼저
            // 뿌렸는데, 그건 같은 망의 누구나 기기 신원을 읽을 수 있다는 뜻이기도 했다.
            // 모르는 내용이면 **말하고** 버린다. 조용히 버리는 게 위 버그를 며칠 숨겼다 —
            // 파싱은 멀쩡히 되고 갈래만 없었으므로 어느 쪽 로그에도 아무 흔적이 없었다.
            guard case .stsHello(let hello)? = msg.payload else {
                log.error("핸드셰이크 프레임에 STS 패스가 아닌 내용이 왔다 — 버린다")
                return
            }
            guard let core = try? PeriStsHelloCore(serializedBytes: hello.core) else {
                log.error("STS pass1 파싱 실패")
                return
            }
            // 같은 기기의 이전 세션 정리는 등록부의 `adopt`이 담당 —
            // 이전 세션을 내리고 같은 디스플레이를 무중단 인계한다(정체성 중복 원천 차단).
            do {
                // Pass 2 — 우리 임시키 + 캡슐화.
                let kex = try PeriSts.respond(clientX25519: core.x25519Pub,
                                              clientMLKEM: core.mlkem768Pub)
                let keys = PeriSts.derive(ecdh: kex.ecdh, kem: kex.kem)

                let serverName = Host.current().localizedName ?? "Mac"
                var rc = PeriStsResponseCore()
                rc.protocolVersion = PeriSts.protocolVersion
                rc.sessionID = core.sessionID
                rc.x25519Pub = kex.x25519Pub
                rc.mlkem768Ciphertext = kex.ciphertext
                rc.serverName = serverName
                let coreBytes = try rc.serializedData()

                // ★ 전사는 **선에 나간 바이트 그대로**다. 그래서 협상 내용(코덱·해상도·HDR·
                // 버전)까지 서명이 덮는다 — 옛 전사는 KEX 재료만 묶어 그 전부가 무방비였다.
                let th = PeriSts.transcript(helloCore: hello.core, responseCore: coreBytes)
                authBox.stash(th: th, authKey: keys.auth)

                var auth = PeriStsAuth()
                auth.signature = PeriIdentity.shared.sign(PeriSts.serverSigningBytes(th))
                // ★ 공개키를 싣지 않는다. 상대는 저장된 키로 시도검증해 우리를 찾는다.
                // 예외는 케이블 부트스트랩뿐 — 그때만 상대에게 조회할 게 없다.
                if srv is AoaBearer {
                    auth.identityCert = PeriIdentity.shared.makeCert(name: serverName).serialized()
                }

                var resp = PeriStsResponse()
                resp.core = coreBytes
                resp.sealedAuth = try PeriSts.sealAuth(keys.auth, auth.serializedData(),
                                                       role: .server)
                var respMsg = PeriControlMessage(); respMsg.stsResponse = resp
                let cid = clientID
                srv.sendControl(try WireFormat.handshakeFrame(respMsg), to: cid)

                box.session = keys.session   // 이후 영상/제어 암호화 활성화

                // ★ **여기서 협상하지 않는다.**
                //
                // 이 연결은 아직 **익명**이다 — 클라 신원은 Pass 3에서야 온다. 그래서 Hello를
                // 들고만 있다가 검증 직후 서비스에 넘긴다(`deliverPendingHandshake`). 무엇을
                // 어떤 코덱·크기·비트심도로 보낼지는 세션이 아니라 서비스가 정한다.
                pendingHello.put(hello)
                log.info("STS pass2 전송 — 클라 신원 대기")
                let reportedNet = core.network
                let claimedName = core.clientName
                Task { @MainActor in
                    self.detail = nil
                    // 이름은 표시용 임시값이다. 검증된 신원은 Pass 3에서 덮어쓴다.
                    if self.deviceName.isEmpty, !claimedName.isEmpty {
                        self.deviceName = claimedName
                    }
                    // 첫 Ping(최대 1초 뒤)을 기다리지 않고 배지를 바로 채운다.
                    self.reportedNetwork = reportedNet
                    self.refreshWireless()
                }
            } catch {
                log.error("handshake failed: \(error.localizedDescription)")
                Task { @MainActor in self.detail = "핸드셰이크 실패: \(error.localizedDescription)" }
            }

        case .control:
            guard let session = box.session, payload.count > 12 else { return }
            let nonce = payload.prefix(12)
            let body = payload.suffix(from: payload.startIndex + 12)
            guard let plain = try? session.receiver.open(nonce: Data(nonce), body: Data(body)),
                  let msg = try? PeriControlMessage(serializedBytes: plain) else { return }
            switch msg.payload {
            case .ping(let p):
                // keepalive + NTP: 에코 + Mac 수신/송신 host µs → 수신측 RTT·클럭오프셋 산출
                let now = Date()
                // 상대의 **박자**를 기록해 둔다. 기기는 영상이 흐를 때만 1초로 두드리고
                // 놀 때는 느리게 보내므로, 우리 타임아웃도 그 박자를 따라가야 한다
                // ([livenessDeadline] 참조).
                if let prev = lastPingBox.value { pingGapBox.value = now.timeIntervalSince(prev) }
                lastPingBox.value = now   // 생존 시계 갱신
                // 망 종류는 여기로만 갱신된다 — 재개는 ClientHello를 새로 만들지 않는다.
                let reported = p.network
                Task { @MainActor in
                    self.reportedNetwork = reported
                    self.refreshWireless()
                }
                let macRecv = hostMicros()
                var pong = PeriPong(); pong.timestampUs = p.timestampUs
                pong.macRecvUs = macRecv
                pong.macSendUs = hostMicros()
                var m = PeriControlMessage(); m.pong = pong
                channel.sendReliable(m)

            case .bye(let b):
                // 기기가 *떠난다*는 확답 (사용자 끊기 / 앱 종료). 끊김과 달리 유예를 두지 않는다.
                // 전송 전환에서는 이 메시지가 오지 않으므로(Android가 안 보냄) 전환은 영향 없음.
                let reason: String
                switch b.reason {
                case .userDisconnect: reason = "사용자 끊기"
                case .appExit: reason = "앱 종료"
                default: reason = "미지정"
                }
                log.info("bye 수신 (\(reason)) → 기기 떠남 확정")
                Task { @MainActor in
                    // 정리 순서(캡처 → 디스플레이)는 기계의 `bye` 갈래가 정한다. 여기서 직접
                    // 내리면 등록부는 그 사실을 모른 채 남는다.
                    if let fp = self.boundFingerprint { self.manager?.dispatch(fp, .bye(reason: reason)) }
                }

            case .udpAccept(let a):
                // 상대가 자기 후보를 되돌려줬다 → 이제 양쪽이 서로의 주소를 안다. 펀칭은
                // 매니저가 한다(소켓과 ICE 에이전트가 거기 있다).
                let id = a.channelID, cands = a.candidates, why = a.problem
                Task { @MainActor in
                    self.manager?.onDatagramAccept(channelID: id, candidates: cands,
                                                   problem: why, session: self)
                }

            case .udpClose(let c):
                // 상대가 먼저 닫았다(앱이 내려갔거나 소켓을 잃었거나). 세대가 다르면
                // 이미 지난 세대의 늦은 통보다 — 지금 것을 닫으면 안 된다.
                guard datagramChannelID == c.channelID else {
                    log.info("udp close: 지난 세대의 통보 — 무시")
                    break
                }
                log.notice("데이터그램 세대 닫힘 (상대: \(c.reason))")
                closeDatagram()

            default:
                // ★ **내 것이 아니면 서비스에게 넘긴다.** 세션은 이 안에 뭐가 들었는지 모른다 —
                // 키프레임 요청도 재협상도 비트레이트 피드백도 전부 extend의 어휘다.
                //
                // 와이어 v1에는 `serviceId`가 없어 등록부가 전부에게 뿌린다. B단계에서 Mux가
                // 들어오면 이 한 줄이 그대로 라우팅 한 번이 된다(호출부는 안 바뀐다).
                servicesBox.value?.receive(.control(msg))
            }

        case .video:
            // 신뢰 경로(AOA)로 온 영상. **세션은 봉인만 푼다** — 무슨 코덱인지, 어디에
            // 그릴지, 지금 받아도 되는지는 전부 앱의 어휘다.
            guard let frag = WireFormat.parseVideoFrame(payload),
                  let (plain, meta) = channel.openMedia(frag) else { break }
            servicesBox.value?.receive(.media(plain, meta))

        case .resume:
            break  // 매니저가 라우팅 전에 가로챈다 — 세션까지 내려오면 이미 짝이 맞은 뒤라 무시.
        }
    }

    // MARK: - local IPv4 (en0/en1 우선)

    static func primaryIPv4() -> String? {
        var address: String?
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let flags = Int32(ptr.pointee.ifa_flags)
            guard (flags & IFF_UP) == IFF_UP, (flags & IFF_LOOPBACK) == 0 else { continue }
            let addr = ptr.pointee.ifa_addr.pointee
            guard addr.sa_family == UInt8(AF_INET) else { continue }
            let name = String(cString: ptr.pointee.ifa_name)
            guard name == "en0" || name == "en1" else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(ptr.pointee.ifa_addr, socklen_t(addr.sa_len),
                           &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                address = String(cString: host); break
            }
        }
        return address
    }
}

// MARK: - SessionManager (멀티클라 오케스트레이터)

/// LanServer 1개를 소유하고 클라이언트별 Session을 생성·라우팅한다. 여러 Android를 동시 송출.
@MainActor
final class SessionManager: ObservableObject {
    @Published private(set) var isRunning = false
    @Published private(set) var statusMessage = "준비됨"
    @Published private(set) var localIP = "—"
    @Published private(set) var sessions: [Session] = []
    /// 커널이 배정한 제어(TCP) 포트. **고정값이 아니다** — ICE 후보에 실려 상대에게 전달된다.
    /// 잘 알려진 번호가 필요했던 건 상대가 약속된 포트로 찾아와야 했던 시절 얘기다.
    var controlPort: UInt16 { server.tcpPort }

    private let server = LanServer()
    /// 진행 중인 홀펀칭. 새 페어링이 오면 교체된다(이전 것은 타임아웃으로 스스로 끝난다).
    private var iceAgent: IceAgent?
    /// 펀칭 전용 TCP 포트. 제어 리스너와 분리 — 그 포트는 NWListener가 독점해 다른
    /// 소켓이 낄 수 없다(실측). 자세한 이유는 `IceAgent.reserveTCPPort` 주석 참조.
    private var punchTCPPort: UInt16 = 0
    /// 이 Mac의 NAT 매핑 방식(후보 수집 때 실측). 펀칭 실패 시 사용자에게 "왜"를 말하는 근거.
    private var localNat: NatMapping = .unknown
    /// 이번 재협상 라운드의 공유 펀치 토큰 — RESUME 프레임이 이 값을 담아야 받아들인다.
    /// 라운드마다 새로 만들어 **암호화된 BLE로만** 건네므로, 옛 RESUME을 그대로 복사해 와도
    /// 통하지 않는다(= 제어 채널을 가로채는 재생 공격 차단). LAN 큐에서 읽으므로 박스.
    private let resumeTokenBox = DataBox()
    /// Wi-Fi 전환/DHCP 갱신 감시 — UI 주소 표시가 낡지 않게.
    private let pathMonitor = NWPathMonitor()

    /// LAN 큐 콜백에서 안전하게 세션을 찾기 위한 라우터 (clientID → Session). @Published 맵과 별개.
    private nonisolated final class Router: @unchecked Sendable {
        private let lock = NSLock()
        private var map: [LanServer.ClientID: Session] = [:]
        func set(_ id: LanServer.ClientID, _ s: Session?) {
            lock.lock(); if let s { map[id] = s } else { map.removeValue(forKey: id) }; lock.unlock()
        }
        func get(_ id: LanServer.ClientID) -> Session? {
            lock.lock(); defer { lock.unlock() }; return map[id]
        }
        func all() -> [Session] { lock.lock(); defer { lock.unlock() }; return Array(map.values) }
        func clearAll() { lock.lock(); map.removeAll(); lock.unlock() }
    }
    private let router = Router()

    // MARK: - 기기 등록부 (상태 기계의 실행기)

    /// 기기 하나. 상태는 [DeviceState]가 들고, 여기 있는 건 그 상태를 실현하는 것들뿐이다.
    @MainActor final class Device {
        var state = DeviceState()
        /// 지금 이 기기를 실행 중인 연결. 암호 세션과 제어 채널을 들고 있다.
        var session: Session?
        /// 이 기기의 서비스들. **세션이 아니라 여기 산다** — 세션이 갈려도(RESUME·콜드
        /// 재수립·LAN↔AOA 전환) 가상 디스플레이는 그대로여야 하기 때문이다(DESIGN.md §5).
        let apps: AppHost
        /// 경로가 없는 동안 도는 유예 시계.
        var grace: Task<Void, Never>?
        /// 세션이 서기를 기다리는 동안의 마감.
        var handshake: Task<Void, Never>?
        let fingerprint: Fingerprint
        init(fingerprint: Fingerprint, report: @escaping (DeviceEv) -> Void) {
            self.fingerprint = fingerprint
            self.apps = AppHost(fingerprint: fingerprint)
        }
    }

    private var devices: [Fingerprint: Device] = [:]

    /// 등록부가 세션을 놓았다 → 전송 계층도 자기 장부에서 지워야 한다.
    ///
    /// 수명의 주인은 기기 상태 기계 하나뿐인데, `AoaLink`는 자기 엔트리와 무응답 워치독을
    /// 따로 들고 있다. 알려 주지 않으면 이미 정리된 세션을 8초 뒤 한 번 더 "정리"한다.
    /// AppCore가 `aoa.forget(_:)`으로 배선한다.
    var onSessionReleased: ((Session) -> Void)?

    // MARK: - 로그 형식
    //
    // Android와 **같은 문법**을 쓴다:  `[dev:<지문8>][r:<라운드>] 사건 → 상태 (축…)`
    //
    // 두 로그를 붙이는 키가 지문이다. 예전엔 Mac만 `dev[…]`를 찍고 Android는 아무 키도 안 찍어
    // 타임스탬프로 눈대중 정렬해야 했다. 라운드 번호는 재협상이 겹칠 때(예약 → 재실행)
    // 어느 라운드의 줄인지 가리기 위한 것이다.

    /// 후보 교환 라운드 일련번호. `gatherCandidates`가 곧 한 라운드의 시작이다.
    private(set) var negoRound = 0

    private func prefix(_ fp: Fingerprint) -> String {
        "[dev:\(fp.prefix(8))]" + (negoRound > 0 ? "[r:\(negoRound)]" : "")
    }

    /// 사건을 기기 기계에 넣고 돌려받은 부작용을 실행한다.
    ///
    /// 부작용 실행 중에 또 dispatch가 일어날 수 있으므로(예: `closeConn` → `connLost`)
    /// Android `MirrorController.dispatch`와 같은 큐 방식으로 직렬화한다. 순서가 섞이면
    /// "캡처를 먼저 내리고 디스플레이를 부순다" 같은 순서 의미가 깨진다.
    private var evQueue: [(Fingerprint, DeviceEv)] = []
    private var draining = false

    func dispatch(_ fp: Fingerprint, _ ev: DeviceEv) {
        evQueue.append((fp, ev))
        guard !draining else { return }
        draining = true
        Task { @MainActor in
            defer { self.draining = false }
            while !self.evQueue.isEmpty {
                let (fp, ev) = self.evQueue.removeFirst()
                guard let dev = self.devices[fp] else { continue }
                let before = dev.state
                let step = reduce(before, ev)
                dev.state = step.state
                if before != step.state {
                    log.info("\(self.prefix(fp)) \(String(describing: ev)) → \(step.state.summary)")
                    // 기계 상태는 이제 진단 화면이 그대로 읽는다 → 전이는 발행돼야 한다.
                    // (`sessions`/`statusMessage`만 @Published라, 축만 바뀌는 전이는 예전엔
                    //  아무 데도 발행되지 않아 화면이 낡은 값을 붙들고 있었다.)
                    self.objectWillChange.send()
                }
                for fx in step.fx { await self.run(fx, on: dev) }
            }
            self.publishSessions()
        }
    }

    /// 등록부에 기기를 만들거나 가져온다. 정리 중이던 기기는 **새 상태로 시작한다** —
    /// 은퇴는 비가역이므로 되살리지 않고 갈아치운다.
    private func device(_ fp: Fingerprint) -> Device {
        if let d = devices[fp], !d.state.retiring { return d }
        let d = Device(fingerprint: fp) { [weak self] ev in self?.dispatch(fp, ev) }
        devices[fp] = d
        return d
    }

    /// 기계의 지시를 실행한다.
    ///
    /// ★ **앱 이름도 자원 이름도 여기 없다.** 예전엔 이 함수가 `DisplayManager`를 직접 불렀고,
    /// 그 다음엔 모드별로 갈라졌다. 지금 아는 건 "말할 수 있게 됐다/없어졌다"뿐이고, 그걸로
    /// 무엇을 할지는 앱들이 각자 정한다 — 앱이 늘어도 이 함수는 안 바뀐다.
    private func run(_ fx: DeviceFx, on dev: Device) async {
        switch fx {
        case .appsLinked:
            if let ch = dev.session?.channel { await dev.apps.attach(ch) }

        case .appsUnlinked:
            await dev.apps.detach(.pathLost)

        case .retireDevice(let reason):
            // 앱이 각자 자기 자원을 놓는다 — 캡처를 먼저 내리고 그 다음 디스플레이를 부순다.
            // 순서가 뒤바뀌면 SCStream이 사라진 디스플레이를 붙들고 에러를 낸다(detach 안쪽).
            await dev.apps.detach(.deviceGone(reason: reason))
            await dev.session?.teardown()
            // ★ 전송 계층의 장부에서도 지운다.
            //
            // 이게 없으면 `AoaLink`가 그 세션을 계속 들고 있다가 8초 뒤 자기 워치독으로
            // "세션 무응답 → 정리"를 또 찍는다. 사용자가 Android에서 '끊기'를 눌러 Bye로
            // 깔끔히 정리된 뒤에도 그랬다 — 등록부와 전송 계층이 각자 수명을 관리하던
            // 흔적이고, LAN에서 이미 걷어낸 문제가 AOA에 남아 있었다.
            if let s = dev.session { onSessionReleased?(s) }
            dev.session = nil
            dev.grace?.cancel(); dev.grace = nil
            dev.handshake?.cancel(); dev.handshake = nil
            devices.removeValue(forKey: dev.fingerprint)
            log.notice("\(self.prefix(dev.fingerprint)) 등록부에서 제거 (\(reason))")

        case .armGrace(let seconds):
            dev.grace?.cancel()
            let fp = dev.fingerprint
            dev.grace = Task { @MainActor [weak self] in
                try? await Task.sleep(for: .seconds(seconds))
                guard !Task.isCancelled else { return }
                self?.dispatch(fp, .graceExpired)
            }

        case .cancelGrace:
            dev.grace?.cancel(); dev.grace = nil

        case .armHandshakeWatchdog(let seconds):
            dev.handshake?.cancel()
            let fp = dev.fingerprint
            dev.handshake = Task { @MainActor [weak self] in
                try? await Task.sleep(for: .seconds(seconds))
                guard !Task.isCancelled else { return }
                self?.dispatch(fp, .handshakeTimeout)
            }

        case .cancelHandshakeWatchdog:
            dev.handshake?.cancel(); dev.handshake = nil

        case .startLiveness:
            dev.session?.setLivenessEnabled(true)

        case .stopLiveness:
            dev.session?.setLivenessEnabled(false)

        case .closeConn(let conn):
            server.closeClient(conn.raw)

        case .rejectAndClose(let conn, let reason):
            // 세션이 있어야 Bye를 봉인할 수 있다(송신 키가 거기 있다). 없으면 그냥 닫는다.
            if let s = dev.session { s.rejectAndClose(reason) } else { server.closeClient(conn.raw) }

        case .log(let msg):
            log.info("\(self.prefix(dev.fingerprint)) \(msg)")
        }
    }

    /// UI가 보는 세션 목록 — **등록부에서 파생된다.** 예전엔 별도 배열이라 라우터·디스플레이
    /// 관리자와 따로 갱신됐고, 그 불일치가 '핸드셰이크 대기' 유령 행이었다.
    private func publishSessions() {
        let live = devices.values.compactMap(\.session)
        if sessions.map(ObjectIdentifier.init) != live.map(ObjectIdentifier.init) {
            sessions = live
        }
        statusMessage = live.isEmpty ? "대기 중 (연결 없음)" : "\(live.count)개 연결됨"
        if live.isEmpty && devices.isEmpty { scheduleIdleStop() } else { cancelIdleStop() }
    }

    /// 이 기기의 한 줄 요약 — UI가 읽는 **유일한** 상태 문구 출처.
    func summary(for fingerprint: Fingerprint) -> String? { devices[fingerprint]?.state.summary }

    /// 이 세션이 자기 기기의 **현역 연결**인가 — 전환 과도기(LAN·AOA 겹침)에 UI가 어느 행을
    /// 남길지 가르는 기준. 통계로 추론할 게 아니라 등록부가 아는 사실이다.
    func isCurrent(_ s: Session) -> Bool {
        guard let fp = s.boundFingerprint else { return false }
        return devices[fp]?.session === s
    }

    /// 이 기기의 홈 화면 — UI가 아이콘을 그리고 앱을 띄우는 출처.
    /// 세션이 아니라 여기서 꺼내는 이유: 앱이 기기에 매여 있고, 세션은 갈리기 때문이다.
    func appHost(for fingerprint: Fingerprint) -> AppHost? {
        devices[fingerprint]?.apps
    }

    /// 재생 프로파일 전환을 붙어 있는 모든 기기에 라이브 적용.
    /// 전송(LAN/USB)을 구분하지 않는다 — USB 세션도 같은 등록부의 기기다.
    func setProfile(_ p: StreamProfile) {
        for dev in devices.values { dev.apps.extend.setProfile(p) }
    }

    // MARK: - USB 신뢰 부트스트랩

    /// 케이블로 온 미페어링 기기의 신뢰 승인 요청. UI가 대화상자로 띄운다.
    struct UsbTrustRequest: Identifiable {
        let id = UUID()
        let name: String
        let fingerprint: Fingerprint
        let decide: (Bool) -> Void
    }

    @Published var usbTrustRequest: UsbTrustRequest?

    /// 케이블로 온 기기가 신뢰 목록에 없다 → 사용자에게 한 번 묻는다.
    ///
    /// 코드 비교(SAS)를 요구하지 않는 이유: **케이블이 이미 채널을 인증했다.** 꽂으려면 두
    /// 기기를 물리적으로 쥐어야 하고 무선으로는 흉내 낼 수 없다. Android 쪽은 OS의 액세서리
    /// 권한 대화상자가 사람 확인을 이미 받아 놨다.
    ///
    /// 그런데도 묻는 이유: **Mac이 잠겨 있어도 AOA는 자동으로 붙는다.** 즉 물리 접근이
    /// 있었다는 사실이 곧 주인의 의사는 아니다. 자리를 비운 Mac에 남의 폰을 잠깐 꽂는
    /// 것만으로 지속적인 무선 접근권이 생기면 안 된다.
    ///
    /// - Returns: 호출자가 이 연결을 어떻게 할지. `true`면 **조용히 기다린다**(거부를 보내지
    ///   않는다), `false`면 거절해도 된다.
    func requestUsbTrust(cert: PeriCert, decide: @escaping (Bool) -> Void) -> Bool {
        if let pending = usbTrustRequest {
            // ★ 같은 기기가 다시 왔다 → **거절하지 않는다.**
            //
            // 잠긴 Mac에서는 사람이 프롬프트를 못 본다. 그동안 Android는 8초 워치독으로 끊고
            // 몇 초 뒤 다시 붙기를 반복하는데, 그때마다 `AUTH_REJECTED`를 보내면 클라가
            // **치명 래치**를 걸고 폴링을 멈춘다 — 잠금을 풀고 '신뢰'를 눌러도 영영 안 붙는다.
            // 조용히 흘려보내면 승인되는 순간 다음 재시도가 그대로 통과한다.
            if pending.fingerprint == cert.fingerprint { return true }
            // 다른 기기가 끼어들었다 → 대화상자를 쌓지 않는다(사용자가 뭘 누르는지 모르게 된다).
            decide(false)
            return false
        }
        usbTrustRequest = UsbTrustRequest(name: cert.name, fingerprint: cert.fingerprint) { [weak self] ok in
            self?.usbTrustRequest = nil
            decide(ok)
        }
        // 메인 창·메뉴 막대 패널 둘 다 닫혀 있는 게 이 앱의 정상 상태라 SwiftUI alert으로는
        // 못 띄운다. 씬에 의존하지 않는 자체 창을 쓴다 — 자세한 이유는 UsbTrustPrompt 주석.
        let req = usbTrustRequest!
        UsbTrustPrompt.show(name: req.name, fingerprint: req.fingerprint, decide: req.decide)
        return true
    }

    // MARK: - 진단 스냅샷
    //
    // 뷰가 `devices`나 아래 사설 필드를 직접 보게 하지 않는다 — 진단을 위해 캡슐화를 뚫으면
    // 그 구멍이 영구히 남는다. 여기서 한 번 찍어 건넨다. 자세한 이유는 `Diagnostics.swift`.

    /// 선택된 기기의 상태 + 실행기 상태.
    func snapshot(for fingerprint: Fingerprint) -> DeviceSnapshot? {
        guard let d = devices[fingerprint] else { return nil }
        return DeviceSnapshot(fingerprint: fingerprint,
                              state: d.state,
                              hasSession: d.session != nil,
                              graceArmed: d.grace != nil,
                              handshakeArmed: d.handshake != nil)
    }

    /// 이 Mac 자신의 상태. 기계가 없는 축이라 여러 소유자에서 긁어모은다.
    func hostSnapshot(aoa: AoaLink?, pairing: PairingCoordinator?) -> HostSnapshot {
        var h = HostSnapshot()
        h.serving = isRunning
        h.controlPort = controlPort
        h.localIP = localIP
        h.negoRound = negoRound
        h.nat = localNat.humanReadable
        h.punchPort = punchTCPPort
        h.resumeArmed = resumeTokenBox.value != nil
        h.deviceCount = devices.count
        h.streamingCount = devices.values.filter { !$0.apps.running.isEmpty }.count
        h.usbStatus = aoa?.status ?? "—"
        h.blePhase = pairing.map { String(describing: $0.phase) } ?? "—"
        return h
    }

    /// 연결을 닫는다 — 세션은 전송을 직접 모르므로(Bearer에 닫기가 없다) 여기를 거친다.
    func closeConnection(_ id: LanServer.ClientID) { server.closeClient(id) }

    /// 인증을 마친 연결이 자기 기기를 밝혔다 → 등록부에 합류시킨다.
    ///
    /// 여기가 **익명 연결이 기기가 되는 유일한 지점**이다. 예전엔 TCP accept 순간에 세션
    /// 객체를 만들어 목록에 넣었는데, 그때는 상대가 누군지 모르는 상태라 재개로 버려질 객체가
    /// 목록에 남았다(유령 행). 이제 신원이 확정된 뒤에만 등록부에 들어간다.
    func adopt(session: Session, fingerprint fp: Fingerprint, conn: ConnID) async {
        let dev = device(fp)
        if let old = dev.session, old !== session {
            // 같은 기기의 이전 연결 — 디스플레이는 그대로 두고 실행기만 갈아끼운다.
            let tag = prefix(fp)
            log.warning("\(tag) 이전 세션 교체 — 디스플레이 무중단 인계")
            // **기다린다.** 떼어낸 Task로 돌리면 옛 캡처가 멈추는 시점이 새 파이프라인이
            // 같은 가상 디스플레이에서 캡처를 시작하는 시점과 경쟁한다 — 한 디스플레이에
            // SCStream이 둘 걸치는 창이 생기고, 그게 FB17797423이 사는 동네다.
            // 순서를 코드로 못박아 두면 로그의 `capture stopped`가 항상 재구성 앞에 온다.
            await old.teardown()
            await dev.apps.detach(.sessionReplaced)
        }
        dev.session = session
        session.bindDevice(fingerprint: fp, manager: self, services: dev.apps)
        // 말할 채널이 생겼다. 서비스는 이 순간 무엇을 다시 세울지 스스로 정한다.
        // attach는 기계의 `appsLinked`가 지시한다 — 세션이 실제로 서야 앱이 말할 수 있다.
        dispatch(fp, .connAdopted(conn))
    }

    init() {
        localIP = Session.primaryIPv4() ?? "—"

        // 네트워크 경로 변경 → 로컬 IP 재계산 (1.5s 뒤 재확인: DHCP 배정이 경로 이벤트보다 늦는 경우).
        // localIP는 @Published — ContentView가 onChange로 활성 AOA 세션에 재광고를 전파한다.
        pathMonitor.pathUpdateHandler = { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.refreshLocalIP()
                try? await Task.sleep(for: .seconds(1.5))
                self?.refreshLocalIP()
            }
        }
        pathMonitor.start(queue: DispatchQueue.global(qos: .utility))

        // SCK 재열거 사이클 — 디스플레이 관리자는 누가 붙어 있는지 모르므로 등록부가 답한다.
        //
        // **큐를 거치지 않고 곧바로 reduce+실행한다.** 이 훅은 이미 어떤 부작용(retireDisplay →
        // retire → recycleForSCK) 실행 중에 불릴 수 있어서, 큐에 넣으면 바깥 드레인이 이 await를
        // 기다리는 동안 아무도 처리하지 않는다. 사이클이 디스플레이를 부수기 **전에** 파이프라인이
        // 내려가 있어야 하므로 순서가 곧 정확성이다.
        // (제거됨) SCK 재열거 사이클을 여기서 잡아 모든 기기의 확장 앱으로 뿌리던 훅.
        //
        // 그건 **가상 디스플레이의 사정**이지 세션 등록부의 일이 아니었다. 이제 확장 앱이
        // `DisplayManager`를 직접 구독한다 — 디스플레이를 안 쓰는 앱(공유)은 그 사건이
        // 있다는 것조차 모르고, 이 파일에서 display라는 단어가 한 번 더 사라졌다(원칙 1).

        // 새 TCP 클라 → **익명 연결**. 아직 기기가 아니다.
        //
        // 예전엔 여기서 곧바로 `sessions`에 넣었는데, 그때는 상대가 누군지 모르는 상태라
        // 재개로 버려질 객체가 목록에 남았다(재개 3회 = '핸드셰이크 대기' 유령 행 3개).
        // 지금은 핸드셰이크가 신원을 확정한 뒤 `adopt`이 등록부에 넣는다.
        server.onClientConnected = { [weak self] id in
            guard let self else { return }
            let session = Session(server: self.server, clientID: id)
            self.router.set(id, session)
            Task { @MainActor in
                self.cancelIdleStop()   // 연결이 생겼다 → 유휴 정지 취소
                session.setManager(self)
                session.begin()
            }
        }
        server.onClientDisconnected = { [weak self] id in
            guard let self else { return }
            let session = self.router.get(id)
            self.router.set(id, nil)
            Task { @MainActor in
                guard let session else { self.publishSessions(); return }
                // 기기에 합류한 연결이면 기계가 판정한다 — 그게 지금 쓰는 경로일 때만 의미가 있고
                // (교체된 옛 연결의 늦은 종료는 무시), 세션·디스플레이를 버릴지도 기계가 정한다.
                if let fp = session.boundFingerprint {
                    self.dispatch(fp, .connLost(session.connID))
                } else {
                    // 익명인 채로 죽은 연결 — 등록부에 없으므로 정리할 것도 없다.
                    await session.teardown()
                    self.publishSessions()
                }
            }
        }
        // 제어 프레임 → 해당 세션. RESUME만 매니저가 직접 처리한다(아직 세션 소속이 안 정해진
        // 연결이 "나는 그 세션이다"라고 주장하는 프레임이라, 라우팅 대상 자체를 정하는 일이다).
        server.onFrame = { [weak self] id, type, payload in
            guard let self else { return }
            if type == .resume { self.handleResume(newID: id, payload: payload); return }
            self.router.get(id)?.handle(type, payload)
        }
        // UDP HELLO → 그 세대를 연 세션에 피어 연결.
        //
        // 예전엔 살아 있는 세션들의 키로 하나씩 **시도복호화**해서 찾았다. 세션이 몇 개
        // 안 되니 값은 쌌지만, 답을 아는 방법이 "다 해 보기"였다. 이제 데이터그램에
        // Mac이 부여한 세대 id가 평문으로 붙어 오므로 조회 한 번이고, 봉인이 그 다음 줄에서
        // 검증을 한다(`acceptsHello`) — id만 베껴 보내 봐야 열리지 않는다.
        server.onUdpHello = { [weak self] peerID, rest in
            guard let self else { return }
            guard let h = WireFormat.parseUdpHello(rest) else {
                log.error("udp HELLO: 형식 오류 (\(rest.count)B)")
                return
            }
            for s in self.router.all()
            where s.acceptsHello(channel: h.channel, nonce: h.nonce, body: h.body) {
                s.linkUdp(peerID)
                log.info("udp HELLO → 세대 매칭, 영상 피어 연결")
                return
            }
            log.error("udp HELLO: 열려 있는 세대 중 맞는 것이 없음")
        }
        // UDP 영상(화면 공유) → 그 피어를 가진 세션. HELLO가 이미 주소↔세션을 묶어 놨으므로
        // 여기선 조회 한 번이면 된다 — 프레임마다 시도복호화를 돌릴 이유가 없다.
        server.onUdpVideo = { [weak self] peerID, rest in
            self?.router.all().first { $0.ownsUdpPeer(peerID) }?.receiveUdpVideo(rest)
        }
    }

    /**
     핸드오버로 새로 뚫린 연결이 "나는 살아 있는 그 세션이다"라고 주장 → 검증 후 제어 채널 교체.

     LAN 큐에서 불린다(프레임 도착 스레드). 검증은 두 겹이다:

      1. **살아 있는 세션들의 수신키로 시도복호화.** 열리는 세션이 곧 그 세션이다 — UDP HELLO
         디먹스와 같은 방식이고, 키를 모르면 어느 세션도 열리지 않는다. 핸드셰이크 전인 신생
         세션은 키가 없어 `openResume`이 항상 nil이라 자기 자신을 매칭할 일도 없다.
      2. **평문이 이번 라운드의 펀치 토큰과 일치.** AEAD 계층에 재생 창이 없으므로(nonce를
         프레임에 실어 보내는 무상태 구조) 이 바인딩이 재생 방어를 맡는다.

     통과하면 핸드셰이크도, 새 가상 디스플레이도, 키프레임도 없다 — 세션은 그대로 두고
     "어느 연결로 보내는가"만 바꾼다.
     */
    nonisolated private func handleResume(newID: LanServer.ClientID, payload: Data) {
        guard let expected = resumeTokenBox.value else {
            log.error("RESUME 수신 — 진행 중인 재협상이 없다, 연결 폐기")
            server.closeClient(newID)
            return
        }
        // 이 연결을 받으며 `onClientConnected`가 만들어 둔 빈 세션. 재개가 성립하면 버려진다.
        let newborn = router.get(newID)
        for s in router.all() {
            guard s !== newborn else { continue }
            guard let plain = s.openResume(payload), plain == expected else { continue }
            let oldID = s.clientID
            guard oldID != newID else { return }   // 이미 이 연결 — 중복 RESUME
            log.notice("★ RESUME 검증 통과 → 제어 채널 교체 (세션·디스플레이 유지)")
            // 라우팅을 먼저 바꾼다 — 옛 연결을 닫으면 onClientDisconnected가 도는데,
            // 그때 oldID가 아직 이 세션을 가리키고 있으면 방금 살린 세션을 teardown 해버린다.
            router.set(newID, s)
            router.set(oldID, nil)
            s.rebindClient(newID)
            // 재개 연결은 ClientAuth를 보내지 않는다 — 신원은 라운드 토큰으로 이미 증명됐다.
            // 익명 마감을 여기서 풀지 않으면 방금 살려 낸 채널이 10초 뒤 스스로 닫힌다.
            server.noteAuthenticated(newID)
            resumeTokenBox.value = nil
            server.closeClient(oldID)
            Task { @MainActor in
                // 신생 세션은 **애초에 등록부에 없다** — 익명이라 기기가 되지 못했다.
                // 예전엔 accept 순간에 목록에 넣었기 때문에 여기서 따로 빼줘야 했고, 그걸
                // 빠뜨려서 재개할 때마다 유령 행이 쌓였다. 이제 구조적으로 생기지 않는다.
                if let newborn { await newborn.teardown() }
                // 기기에 "제어 채널이 바뀌었다"고 알린다 — 세션·디스플레이·파이프라인은 그대로다.
                if let fp = s.boundFingerprint {
                    self.dispatch(fp, .pathSwapped(s.connID))
                }
            }
            return
        }
        log.error("RESUME 검증 실패 — 일치하는 세션이 없다, 연결 폐기")
        server.closeClient(newID)
    }

    /// 네트워크 경로 변경 시 로컬 IPv4 재계산 — 바뀌었을 때만 @Published 갱신.
    private func refreshLocalIP() {
        let ip = Session.primaryIPv4() ?? "—"
        guard ip != localIP else { return }
        log.info("local IP changed: \(self.localIP) → \(ip)")
        localIP = ip
    }

    func start() {
        guard !isRunning else { return }
        do {
            try server.start()
            isRunning = true
            // 포트는 더 이상 사용자에게 알릴 값이 아니다 — 상대는 BLE 후보로 찾아온다.
            statusMessage = "송출 준비 · 기기에서 연결하세요"
            log.info("session manager started")
        } catch {
            statusMessage = "시작 실패: \(error.localizedDescription)"
            log.error("server start failed: \(error.localizedDescription)")
        }
    }

    func stop() {
        cancelIdleStop()
        server.stop()
        let current = sessions
        sessions = []
        router.clearAll()
        for s in current { Task { await s.teardown() } }
        isRunning = false
        statusMessage = "대기 중"
    }

    // MARK: - 수신 수명 (자동)

    /// 유휴 정지 예약. 사용자가 켜고 끄는 스위치가 아니라 앱이 판단한다 —
    /// 켜기는 후보 수집(`gatherCandidates`)이, 끄기는 여기가 맡는다.
    private var idleStopTask: Task<Void, Never>?

    /**
     세션이 0개가 되면 유예 뒤에 리스너를 내린다.

     유예 값은 `DisplayManager.departureGrace`를 그대로 쓴다. 같은 질문("이 기기가 곧
     돌아올까?")에 답하는 타이머를 둘로 나눌 이유가 없다 — 가상 디스플레이와 수신 소켓이
     같은 창 동안 함께 유지되고 함께 정리된다.

     **이 유예는 Android의 복귀 예산보다 길어야 한다.** Android는 1s/2s/4s 백오프로 3회
     재접속하고 각 connect가 5s 타임아웃이라 최악 ~22초, 핸드오버 재협상 워치독이 30초다.
     그 안에 리스너를 내리면 재접속이 connection refused로 죽고, 게다가 포트가 유동이라
     다시 켤 때 번호까지 달라져 상대가 들고 있던 host 후보가 통째로 무효가 된다.
     */
    private func scheduleIdleStop() {
        cancelIdleStop()
        idleStopTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(departureGrace))
            guard !Task.isCancelled, let self, self.sessions.isEmpty, self.isRunning else { return }
            log.info("\(Int(departureGrace))s간 연결 없음 → LAN 수신 중지 (다음 후보 수집 때 자동 재시작)")
            self.stop()
        }
    }

    private func cancelIdleStop() {
        idleStopTask?.cancel()
        idleStopTask = nil
    }

    /// 끊긴 세션을 목록·라우터에서 제거 (Mac에서 연결 해제한 경우). 이 세션이 LAN 소유가
    /// 아니면 아무 일도 하지 않는다 — 호출자가 LAN/USB를 구분하지 않아도 되게.
    func forget(_ session: Session) {
        router.set(session.clientID, nil)
        // 기기에 속한 세션이면 등록부가 정리한다 — 목록만 지우면 디스플레이가 주인 없이 남는다.
        if let fp = session.boundFingerprint {
            dispatch(fp, .bye(reason: "목록에서 제거"))
        } else {
            Task { @MainActor in await session.teardown(); self.publishSessions() }
        }
    }

    // MARK: - ICE (P2P NAT 트래버설)

    /// 이 Mac의 연결 후보를 모은다. **미디어를 나를 바로 그 소켓에서** STUN을 쏜다 —
    /// 다른 포트에서 얻은 매핑은 그 포트의 것이라 펀칭에 쓸 수 없다.
    ///
    /// 세션 공유 펀치 토큰도 여기서 만든다(Mac이 controlling). Android는 이 값을 그대로 되돌려준다.
    ///
    /// ★ **기본은 TCP 후보뿐이다.** 세션 수립에 UDP가 필요 없어졌다 — 데이터그램 경로는
    /// 영상을 쓰는 앱이 뜰 때 제어 채널 위에서 따로 연다(`openDatagramPath`). NAT의 UDP
    /// 매핑은 금방 닫혀 붙들려면 계속 두드려야 하는데, TCP 매핑은 훨씬 오래 살아서 세션만
    /// 유지하는 데는 그쪽이 압도적으로 싸다. `fastUdp` 설정이 그 기본을 뒤집는다.
    func gatherCandidates() async -> CandidateBundle {
        negoRound += 1   // 후보 수집 = 한 라운드의 시작. 이후 로그가 이 번호를 단다.
        // 후보를 모은다 = 곧 누가 붙는다. 수신을 켜고, 예약된 유휴 정지가 있으면 취소한다.
        cancelIdleStop()
        if !isRunning { start() }
        let token = CandidateBundle.randomToken()
        // ★ UDP를 같이 뚫는 이유가 둘이다.
        //
        //  1. 설정으로 켜 뒀다 — 붙자마자 영상 앱이 뜰 것을 알고 미리 뚫는다.
        //  2. **지금 열려 있는 세대가 있다** — 이건 재협상 라운드라는 뜻이고(망 전환),
        //     그 순간 화면은 이미 흐르고 있었다. 여기서 UDP를 빼면 새 망에서 세대를 처음부터
        //     다시 열어야 해서 그 공백이 그대로 사용자에게 보인다. 설정과 무관하게,
        //     "지금 쓰고 있는가"가 이 라운드에 UDP가 필요한지의 정확한 답이다.
        let fast = DatagramSettings.fastUdp
            || router.all().contains { $0.datagramChannelID != nil }
        warmDatagramPath = false   // 새 라운드 — 지난 라운드가 뚫어 둔 경로는 이제 없는 셈이다
        guard let udp = server.udpTransport else {
            log.error("UDP 소켓이 없어 host 후보만 생성")
            return CandidateBundle(token: token, candidates: [], fastUdp: fast)
        }
        // ★ **연결 시도마다 새 포트를 잡는다.** 이전엔 한 번 잡아 계속 재사용했는데, 펀칭으로
        // 성립한 제어 연결이 그 포트를 쓰다가 세션이 끝나도 TIME_WAIT로 남아 있어 재연결 때
        // TCP STUN·펀칭이 둘 다 `[48: Address already in use]`로 죽었다(실기기 로그).
        // 포트는 이 시도 안에서 STUN 질의와 펀칭이 같기만 하면 되지, 세션 간에 같을 이유가 없다.
        punchTCPPort = IceAgent.reserveTCPPort()
        let servers = StunSettings.servers
        let listenPort: UInt16
        do {
            listenPort = try await server.awaitTCPPort()
        } catch {
            log.error("TCP 제어 포트 대기 실패: \(error.localizedDescription)")
            return CandidateBundle(token: token, candidates: [], fastUdp: fast)
        }
        let punchPort = punchTCPPort
        let transports: Set<CandidateTransport> = fast ? [.tcp, .udp] : [.tcp]
        // STUN은 블로킹 소켓 호출이라 메인 액터를 잡고 있으면 UI가 멎는다 → 백그라운드로.
        let result = await Task.detached(priority: .userInitiated) {
            await IceAgent.gather(udp: udp, listenPort: listenPort,
                                  punchTCPPort: punchPort, servers: servers,
                                  transports: transports)
        }.value
        localNat = result.nat
        log.notice("로컬 후보 \(result.candidates.count)개 수집 (펀치 TCP 포트 \(punchPort), NAT \(result.nat.humanReadable), fast-udp \(fast))")
        return CandidateBundle(token: token, candidates: result.candidates, fastUdp: fast)
    }

    /// 상대 후보를 받아 홀펀칭 시작. 성공하면 그 주소를 영상 피어로 등록해 둔다 —
    /// 뚫린 경로로 Android가 곧 TCP 제어 연결과 UDP HELLO를 보내온다.
    func beginPunch(remote: CandidateBundle) {
        guard let udp = server.udpTransport else { return }
        // 이 라운드의 토큰을 RESUME 검증 기준으로 세워 둔다. 첫 연결이면 아무도 RESUME을
        // 보내지 않으니 그냥 안 쓰이고, 핸드오버 재협상이면 Android가 이 값을 담아 보낸다.
        resumeTokenBox.value = remote.token
        let agent = IceAgent()
        iceAgent = agent
        // 이 소켓엔 영상·HELLO도 흐른다 → 프로브만 골라 소비하고 나머지는 원래 경로로 흘린다.
        server.onUdpProbe = { [weak agent] data, from in
            agent?.handleInbound(data, from: from, udp: udp) ?? false
        }

        let udpTargets = remote.ordered(.udp)
        let tcpTargets = remote.ordered(.tcp)
        if punchTCPPort == 0 { punchTCPPort = IceAgent.reserveTCPPort() }
        let punchPort = punchTCPPort

        // ★ **UDP 후보가 없으면 UDP는 아예 시도하지 않는다.** 기본 모드에서는 그게 정상이다 —
        // 데이터그램 경로는 앱이 뜰 때 따로 연다. 예전처럼 빈 목록으로 펀칭에 들어가면
        // 10초를 헛돌고 "P2P 경로 실패"를 사용자에게 띄운다(실패가 아닌데).
        if !udpTargets.isEmpty {
        // 영상(UDP)과 제어(TCP)는 서로를 기다리지 않는다 — 한쪽만 뚫려도 그만큼은 살린다.
        Task { [weak self] in
            let path = await agent.punchUDP(udp: udp, remote: udpTargets, token: remote.token)
            await MainActor.run {
                guard let self else { return }
                if let path {
                    self.server.registerUdpPeer(path.candidate.endpoint)
                    // fast 모드의 접속 라운드다 — 이 경로를 첫 세대가 그대로 물려받는다.
                    self.warmDatagramPath = true
                    log.notice("udp 펀칭 성공 \(path.candidate.endpoint.description) rtt=\(path.rttMs)ms")
                } else {
                    // 추측하지 않는다 — 후보 수집 때 실측한 NAT 방식으로 원인을 특정한다.
                    switch self.localNat {
                    case .symmetric:
                        self.statusMessage = "이 Mac의 네트워크가 대칭 NAT라 P2P 연결이 불가능합니다 — 같은 Wi-Fi나 USB를 쓰세요"
                        log.error("udp 펀칭 실패: 우리 쪽이 symmetric NAT — 릴레이 없이는 불가")
                    case .endpointIndependent:
                        self.statusMessage = "P2P 경로 실패 — 상대 기기 네트워크(셀룰러 CGNAT 등)가 막고 있을 수 있습니다"
                        log.warning("udp 펀칭 실패: 우리 NAT는 정상 → 상대 쪽 NAT/방화벽 의심")
                    case .unknown:
                        self.statusMessage = "P2P 경로 실패 — STUN 서버 응답이 없어 원인을 특정할 수 없습니다"
                        log.warning("udp 펀칭 실패: NAT 판정 불가(STUN 무응답)")
                    }
                }
            }
        }
        }

        Task { [weak self] in
            // 성립한 연결은 리스너가 받은 것과 동일한 경로로 들어간다(핸드셰이크부터 그대로 진행).
            let conn = await agent.punchTCP(localPort: punchPort, remote: tcpTargets)
            await MainActor.run {
                guard let self else { return }
                if let conn {
                    self.server.adoptPunchedConnection(conn)
                    self.statusMessage = "P2P 제어 채널 연결됨"
                } else {
                    log.warning("tcp 펀칭 실패 — 같은 LAN이면 Android가 host 후보로 접속한다")
                }
            }
        }
    }

    // MARK: - 데이터그램(UDP) 채널 — 앱의 수명을 따른다
    //
    // 세션은 TCP 하나로 오래 산다. 이 경로는 **최신성 레인을 쓰는 앱이 떠 있는 동안만** 산다.
    // 이유는 순전히 NAT다: UDP 매핑은 수명이 짧아 붙들려면 계속 두드려야 하는데, 그건 아무도
    // 안 보는 동안에도 라디오를 깨우는 일이다. TCP 매핑은 훨씬 오래 살아 세션 유지는 값싸다.
    //
    // 열리는 순서 (Mac이 개시한다 — 세대 이름을 부여하는 쪽이 하나여야 한다):
    //
    //   1. Mac: channel_id 생성 → 그 자리에서 세션 키에서 세대 키를 갈라낸다(왕복 없음)
    //   2. Mac: UDP 후보 수집 → `UdpOpen{channel_id, 후보}`를 **암호화된 TCP 제어 채널**로
    //   3. And: 세대 키 유도(0-RTT 준비 완료) → 자기 UDP 후보 수집 → `UdpAccept`
    //   4. 양쪽: 홀펀칭 → And가 HELLO 한 발(이미 봉인돼 있다) → Mac이 영상 피어를 링크
    //
    // 시그널링이 BLE가 아니라 TCP인 것이 요점이다. 세션이 이미 상호 인증돼 있으므로 여기서
    // 신원을 다시 세울 필요가 없고, BLE 왕복(연결 간격·MTU)도 타지 않는다.

    /// 진행 중인 세대 라운드. 키는 세대 id — `UdpAccept`가 그 id를 에코해 돌아온다.
    ///
    /// **세션을 들고 있지 않다.** 응답에 세대 id가 실려 오므로 그걸로 세션을 찾을 수 있고,
    /// 여기서 세션을 잡고 있으면 답이 영영 안 오는 라운드가 죽은 세션을 붙들어 둔다.
    private struct DatagramRound {
        let token: Data
        let agent: IceAgent
    }
    private var datagramRounds: [Data: DatagramRound] = [:]

    /// 접속 라운드가 UDP까지 뚫어 뒀는가(fast UDP 모드).
    ///
    /// **첫 세대만** 이 경로를 그대로 물려받는다. 그게 fast 모드가 사는 자리다 — 붙자마자
    /// 영상 앱이 떠야 할 때(지난번에 켜 둔 앱이거나, 망 전환으로 채널이 통째로 다시 서는 중)
    /// ICE 한 왕복을 아낀다. 한 번 쓰면 내린다: 그 뒤의 세대는 앱이 다 내려갔다 다시 뜬
    /// 경우라 시간이 지났고, UDP 매핑이 살아 있다는 보장이 없어 새로 뚫어야 한다.
    private var warmDatagramPath = false

    /// 최신성 경로를 쓰는 앱이 떴다 → 세대를 연다. 이미 열려 있으면 아무 일도 하지 않는다.
    func openDatagramPath(for session: Session) {
        guard !session.reliableTransport else { return }   // 케이블엔 열고 닫을 경로가 없다
        guard session.datagramChannelID == nil else { return }

        let channelID = PeriCryptoSession.randomChannelID()
        guard session.openDatagram(channelID: channelID) else {
            log.error("데이터그램 세대를 못 연다 — 세션 키가 아직 없다")
            return
        }
        let hex = channelID.prefix(4).map { String(format: "%02x", $0) }.joined()

        // 접속 때 이미 뚫어 둔 경로가 있으면 ICE 라운드를 건너뛴다. 후보를 비워 보내는 것이
        // 곧 "새로 뚫지 말고 그 경로를 쓰라"는 뜻이다.
        if warmDatagramPath {
            warmDatagramPath = false
            log.notice("데이터그램 세대 \(hex) 열기 — 접속 때 뚫어 둔 경로 재사용")
            sendUdpOpen(session: session, channelID: channelID, candidates: Data())
            return
        }

        // 답이 오지 않은 지난 라운드를 걷어낸다 — 상대가 `UdpAccept`를 영영 안 보내면
        // 그 항목은 아무도 지우지 않는다(성공·닫기 두 경로에서만 지워진다).
        let live = Set(router.all().compactMap(\.datagramChannelID))
        datagramRounds = datagramRounds.filter { live.contains($0.key) }

        let token = CandidateBundle.randomToken()
        let agent = IceAgent()
        datagramRounds[channelID] = DatagramRound(token: token, agent: agent)
        log.notice("데이터그램 세대 \(hex) 열기 — UDP 후보 수집")

        Task { @MainActor [weak self] in
            guard let self, let udp = self.server.udpTransport else { return }
            let servers = StunSettings.servers
            let result = await Task.detached(priority: .userInitiated) {
                // TCP 몫은 이미 세션이 쓰고 있다 — 여기서는 UDP만 묻는다(STUN 왕복 절약).
                await IceAgent.gather(udp: udp, listenPort: 0, punchTCPPort: 0,
                                      servers: servers, transports: [.udp])
            }.value
            self.localNat = result.nat
            // 세대가 그새 닫혔으면(앱이 곧바로 내려감) 후보를 보내 봐야 상대만 혼란스럽다.
            guard session.datagramChannelID == channelID else {
                self.datagramRounds.removeValue(forKey: channelID)
                return
            }
            let bundle = CandidateBundle(token: token, candidates: result.candidates)
            self.sendUdpOpen(session: session, channelID: channelID,
                             candidates: bundle.serialized())
        }
    }

    private func sendUdpOpen(session: Session, channelID: Data, candidates: Data) {
        var open = PeriUdpOpen()
        open.channelID = channelID
        open.candidates = candidates
        var m = PeriControlMessage(); m.udpOpen = open
        if !session.channel.sendReliable(m) {
            log.error("UdpOpen 송신 실패 — 제어 채널이 없다")
        }
    }

    /// 상대가 후보를 되돌려줬다 → 펀칭. 후보가 비어 있으면 재사용 경로이므로 할 일이 없다.
    func onDatagramAccept(channelID: Data, candidates: Data, problem: String, session: Session) {
        guard session.datagramChannelID == channelID else {
            log.info("UdpAccept: 지난 세대의 응답 — 무시")
            return
        }
        if !problem.isEmpty {
            log.error("상대가 데이터그램 경로를 못 열었다: \(problem)")
            closeDatagramPath(for: session, reason: "상대 준비 실패")
            return
        }
        guard let round = datagramRounds.removeValue(forKey: channelID) else {
            // 재사용 경로(후보 없이 연 세대) — 상대는 곧 HELLO만 보낸다.
            log.info("UdpAccept: ICE 라운드 없는 세대 — HELLO를 기다린다")
            return
        }
        guard let udp = server.udpTransport, let remote = CandidateBundle.parse(candidates) else {
            log.error("UdpAccept: 후보 파싱 실패 — 영상 경로 없음")
            return
        }
        let agent = round.agent
        // 이 소켓엔 영상·HELLO도 흐른다 → 프로브만 골라 소비하고 나머지는 원래 경로로 흘린다.
        server.onUdpProbe = { [weak agent] data, from in
            agent?.handleInbound(data, from: from, udp: udp) ?? false
        }
        let targets = remote.ordered(.udp)
        Task { [weak self] in
            let path = await agent.punchUDP(udp: udp, remote: targets, token: round.token)
            await MainActor.run {
                guard let self else { return }
                if let path {
                    self.server.registerUdpPeer(path.candidate.endpoint)
                    log.notice("데이터그램 펀칭 성공 \(path.candidate.endpoint.description) rtt=\(path.rttMs)ms")
                } else {
                    // 실패해도 세대는 닫지 않는다 — 같은 LAN이면 상대가 host 후보로 곧장
                    // HELLO를 보내 오고, 그것만으로 경로가 선다(펀칭은 그 경우 원리상 무의미).
                    log.warning("데이터그램 펀칭 실패 (\(targets.count)개 후보) — host 경로의 HELLO를 기다린다")
                }
            }
        }
    }

    /// 마지막 앱이 내려갔거나 상대가 준비를 못 했다 → 세대를 닫는다.
    ///
    /// 상대에게 **먼저 알리고** 우리 것을 놓는다. 안 알리면 상대는 그저 조용해진 것으로 보고
    /// 소켓과 매핑을 계속 붙들고 있는데, 그게 정확히 이 설계가 피하려는 상태다.
    func closeDatagramPath(for session: Session, reason: String) {
        guard let channelID = session.datagramChannelID else { return }
        datagramRounds.removeValue(forKey: channelID)
        var close = PeriUdpClose()
        close.channelID = channelID
        close.reason = reason
        var m = PeriControlMessage(); m.udpClose = close
        session.channel.sendReliable(m)
        session.closeDatagram()
        log.notice("데이터그램 세대 닫음 (\(reason))")
    }
}
