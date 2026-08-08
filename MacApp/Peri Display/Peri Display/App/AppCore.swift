//
//  AppCore.swift
//  Peri Display
//
//  앱 전역 상태의 단일 소유자. 예전엔 ContentView가 @StateObject로 세 매니저를 소유해
//  창 수명 = 서비스 수명이었고, 메뉴 막대·설정 같은 두 번째 씬이 상태에 닿을 수 없었다.
//  씬들은 이 인스턴스를 environmentObject로 공유하고, 뷰 수명과 무관하게 서비스가 돈다.
//
//  ContentView.onAppear/onChange에 있던 배선(서버 엔드포인트 제공, IP 변경 재광고,
//  프로파일 팬아웃)도 여기로 옮겼다 — 창이 닫혀 있어도 배선이 살아 있어야 하므로.
//

import Foundation
import Combine
import UserNotifications
import OSLog

private let log = Logger(subsystem: "com.peri.display.mac", category: "core")

@MainActor
final class AppCore: ObservableObject {
    let sessions = SessionManager()      // LAN 서버 + LAN 세션들
    let pairing = PairingCoordinator()   // BLE 페어링
    /// 페어링된 기기를 깨우는 호출자. 폰이 상시 광고를 그만둔 대가로 필요해졌다.
    let pager = PeriPageAdvertiser()
    let aoa = AoaLink()                  // USB(AOA) 링크 + USB 세션들
    let profiles = ProfileStore.shared   // 재생 프로파일 (UserDefaults 영속)

    /// 통합 기기 목록 — LAN·AOA·TrustStore·BLE를 지문으로 머지한 스냅샷. UI의 유일한 기기 축.
    @Published private(set) var devices: [DeviceModel] = []

    /// 메뉴 막대에서 기기를 눌러 메인 창을 열 때의 선택 요청 — MainWindow가 소비 후 nil로 되돌림.
    @Published var focusedDeviceID: String?

    private var cancellables = Set<AnyCancellable>()
    private var sessionSubs = Set<AnyCancellable>()
    /// @Published는 willSet에 발행돼 sink 시점엔 아직 옛 값이다 → 트리거만 모아 debounce 후
    /// (새 값이 반영된 다음) 재구성. 세션 통계 1Hz 갱신도 여기서 초당 1회로 뭉쳐진다.
    private let rebuildTrigger = PassthroughSubject<Void, Never>()

    /// Mac 알림의 버튼(지우기·답장)을 받아 해당 기기로 되돌린다.
    ///
    /// **프로세스에 하나뿐이다.** `UNUserNotificationCenter`의 delegate도 카테고리도 전역이라
    /// 기기마다 두면 마지막 것만 살아남는다 — 두 대를 붙여 놓고 한쪽 알림에 답장하면
    /// 엉뚱한 폰으로 가는 종류의 버그가 된다.
    private lazy var notificationRouter = NotificationRouter(core: self)

    init() {
        // AOA 연결도 같은 기기 등록부에 합류한다 — USB와 Wi-Fi가 한 기기의 두 연결일 뿐이므로,
        // 전환할 때 가상 디스플레이가 흔들리지 않는다(예전엔 케이블마다 새로 만들어졌다).
        aoa.registry = sessions
        NotifyService.registerCategories()
        UNUserNotificationCenter.current().delegate = notificationRouter
        // 등록부가 세션을 놓으면 USB 장부에서도 지운다 — 안 그러면 이미 정리된 세션을
        // AoaLink의 무응답 워치독이 8초 뒤 한 번 더 "정리"한다(실기기 로그).
        // LAN 세션이면 no-op이다.
        sessions.onSessionReleased = { [weak aoa] s in aoa?.forget(s) }

        // 페어링 인증 완료 → 연결 후보(host + STUN srflx)를 모아 BLE로 전달 → Android가 접속·펀칭.
        // 수집은 SessionManager가 한다: STUN은 실제 미디어 소켓에서 나가야 매핑이 유효하고,
        // 그 소켓은 SessionManager가 소유하기 때문이다.
        pairing.candidateProvider = { [sessions] in
            await sessions.gatherCandidates()
        }
        // Android 후보 도착 → 홀펀칭 개시.
        pairing.onRemoteCandidates = { [sessions] bundle in
            sessions.beginPunch(remote: bundle)
        }

        // (제거됨) Mac IP 변경 → USB 세션에 LAN 폴백 주소 재광고.
        // 폴백 주소를 미리 밀어 넣는 방식 자체를 버렸다 — USB 분리든 망 전환이든 Android가
        // BLE로 재협상을 요청하고 그 시점의 후보를 새로 교환한다. 낡을 주소가 없다.

        // 프로파일 토글 → 붙어 있는 기기 전부에 라이브 적용 (새 파이프라인은 빌드 시 ProfileStore
        // 에서 채택). USB 세션도 같은 기기 등록부에 있으므로 전송별로 따로 뿌리지 않는다.
        // @Published는 willSet에 발행되므로 store 재독이 아니라 발행값 p를 써야 새 값이다.
        profiles.$profile
            .dropFirst()
            .sink { [weak self] p in self?.sessions.setProfile(p) }
            .store(in: &cancellables)

        // 기기 목록 재구성 트리거: 세션 목록·BLE 발견·페어링 단계(TrustStore 갱신) 변화.
        rebuildTrigger
            .debounce(for: .milliseconds(80), scheduler: DispatchQueue.main)
            .sink { [weak self] in self?.rebuildDevices() }
            .store(in: &cancellables)
        sessions.$sessions.sink { [weak self] _ in self?.rebuildTrigger.send() }
            .store(in: &cancellables)
        aoa.$sessions.sink { [weak self] _ in self?.rebuildTrigger.send() }
            .store(in: &cancellables)
        pairing.$peers.sink { [weak self] _ in self?.rebuildTrigger.send() }
            .store(in: &cancellables)
        pairing.$phase.sink { [weak self] _ in self?.rebuildTrigger.send() }
            .store(in: &cancellables)

        // 호출 응답 판정은 **부르고 있는 쪽**이 한다. 스캐너는 광고에서 바이트만 꺼내 온다.
        pairing.pageResponseResolver = { [weak pager] d in pager?.resolve(response: d) }
        // 부른 기기가 응답했다 → 곧바로 붙는다. **GATT를 여는 쪽은 언제나 Mac이다** —
        // 응답은 "여기 있다"까지고, 연결은 우리가 건다.
        pairing.$peers
            .compactMap { [weak pager] peers -> (DiscoveredPeer, Fingerprint)? in
                guard let want = pager?.paging else { return nil }
                return peers.first { $0.fingerprint == want }.map { ($0, want) }
            }
            .sink { [weak self] peer, want in
                guard let self else { return }
                log.notice("★ 호출에 응답한 기기 발견 — 접속")
                self.pager.stop()
                // 우리가 **부른 그 기기**다. 응답 자체가 링크 키를 가졌다는 증거이므로
                // 이 경로는 언제나 STS다 — 지문을 넘겨 PIN 라운드로 새지 않게 못박는다.
                self.pairing.connect(peer, expecting: want)
            }
            .store(in: &cancellables)

        pairing.startScan()   // 앱 실행 시 자동으로 주변 기기 탐색
        aoa.start()           // USB 부착 상시 감시 — 케이블 꽂으면 자동 연결
        // LAN 수신은 여기서 켜지 않는다. 접속점은 오직 BLE 후보로만 전달되므로 후보를 모으는
        // 순간(gatherCandidates)이 곧 "곧 누가 붙는다"는 신호이고, 거기서 자동으로 켜진다.
        // 끄기는 마지막 세션이 떠난 뒤 유예로 자동 처리된다(scheduleIdleStop).
        rebuildDevices()
    }

    /// Mac에서 이 기기 연결을 끊는다 — Android에 Bye를 보내 재접속을 막고, 세션을 목록에서
    /// 지우고, 가상 디스플레이까지 정리한다. LAN/USB 어느 쪽이든 호출자는 구분하지 않는다.
    func disconnect(_ device: DeviceModel) async {
        guard let session = device.session else { return }
        await session.requestDisconnect()
        sessions.forget(session)   // 소유가 아니면 no-op
        aoa.forget(session)
        rebuildDevices()
    }

    /// 페어링 해제 — **이 앱에서 신뢰를 거두는 유일한 창구다.**
    ///
    /// 예전엔 UI 두 곳이 `TrustStore.shared.revoke`를 직접 불렀고, 그게 전부였다. 그래서
    /// 두 가지가 빠져 있었다:
    ///
    ///  1. **송출 중이어도 아무 일도 안 일어났다.** TrustStore에는 관찰자가 없어서 세션은
    ///     그대로 흘렀고, 가상 디스플레이도 남았다. 해제는 다음 재연결 때에야 효력이 생겼다.
    ///  2. 기기 목록 갱신이 BLE 스캔의 우연한 변화에 얹혀 있었다 — 근처에 없으면 행이
    ///     한동안 안 사라졌다.
    ///
    /// 신뢰를 상태 축으로 만들지 않은 이유는 [DeviceEv.trustRevoked] 주석에 있다.
    func revoke(fingerprint: Fingerprint) {
        guard let peer = TrustStore.shared.all().first(where: { $0.fingerprint == fingerprint })
        else { return }
        TrustStore.shared.revoke(peer.pub)
        // 붙어 있으면 기계가 통지·정리까지 지시한다. 등록부에 없으면(오프라인) no-op.
        sessions.dispatch(fingerprint, .trustRevoked)
        rebuildDevices()
    }

    /// 페어링된 기기에 연결을 요청한다.
    ///
    /// ★ **근처에 있는지 몰라도 누를 수 있어야 한다.** 폰이 상시 광고를 그만두면서 "근처에
    /// 있음"이라는 신호 자체가 사라졌다 — 예전처럼 `blePeer`가 있을 때만 버튼을 띄우면
    /// 페어링해 둔 기기에 영영 연결할 수 없다.
    ///
    /// 그래서 두 갈래다:
    ///  - 마침 광고 중이면(첫 페어링 직후 등) 곧바로 붙는다.
    ///  - 아니면 **호출한다** — BLE 광고로 그 기기를 깨우고, 깨어난 폰이 광고를 시작하면
    ///    그때 스캔이 발견해 평소 경로로 붙는다.
    func connect(_ device: DeviceModel) {
        if let peer = device.blePeer {
            pager.stop()
            // ★ **누구를 눌렀는지를 같이 넘긴다.** 페어링된 기기 행의 `id`가 곧 그 기기의
            // 지문이다. 광고에서 해석한 지문은 파생값이라 순간적으로 빗나갈 수 있고, 그때
            // 조용히 PIN 라운드로 떨어지면 이미 페어링된 기기에 페어링을 다시 시킨다.
            // 낯선 기기 행(`ble:<uuid>`)은 지문이 아니므로 넘기지 않는다.
            pairing.connect(peer, expecting: device.link == .discovered ? nil : device.id)
            return
        }
        guard let peer = TrustStore.shared.all().first(where: { $0.fingerprint == device.id })
        else { return }
        // 링크 키가 호출의 자격이다. 없으면 pager가 이유를 띄운다 — 조용히 지나가면 버튼이
        // 눌리는데 아무 일도 안 일어나는 상태가 된다.
        pager.page(devicePub: peer.pub,
                   linkKey: TrustStore.shared.linkKey(for: peer.pub) ?? Data(),
                   fingerprint: device.id)
    }

    /// 사용자가 호출을 그만뒀다.
    func cancelPage() { pager.stop() }

    /// 통합 기기 목록 재구성. 결과가 실제로 달라졌을 때만 @Published에 반영(뷰 무효화 최소화).
    ///
    /// 세션과 **서비스** 둘 다 재구독한다 — 지문·기기명은 핸드셰이크 후 늦게 도착하고(세션),
    /// 통계는 1Hz로 갱신된다(서비스). 서비스 구독이 빠지면 목록의 해상도·fps가 얼어붙는다.
    private func rebuildDevices() {
        let lan = sessions.sessions
        let usb = aoa.sessions

        sessionSubs.removeAll()
        for s in lan + usb {
            s.objectWillChange
                .sink { [weak self] _ in self?.rebuildTrigger.send() }
                .store(in: &sessionSubs)
        }
        for fp in Set((lan + usb).compactMap(\.fingerprint)) {
            guard let host = sessions.appHost(for: fp) else { continue }
            // 홈 화면(앱이 뜨고 지는 것)과 확장의 1Hz 통계는 서로 다른 알림이다 — 둘 다 구독한다.
            // 확장 구독이 빠지면 목록의 해상도·fps가 얼어붙고, 홈 구독이 빠지면 아이콘이 안 바뀐다.
            for pub in [host.objectWillChange, host.extend.objectWillChange] {
                pub.sink { [weak self] _ in self?.rebuildTrigger.send() }
                   .store(in: &sessionSubs)
            }
        }

        let merged = DeviceModel.merge(trusted: TrustStore.shared.all(),
                                       lanSessions: lan, aoaSessions: usb,
                                       blePeers: pairing.peers,
                                       summary: { [sessions] fp in sessions.summary(for: fp) },
                                       apps: { [sessions] fp in sessions.appHost(for: fp) },
                                       isCurrent: { [sessions] s in sessions.isCurrent(s) })
        if merged != devices { devices = merged }
    }
}
