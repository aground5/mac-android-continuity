//
//  AoaSmoke.swift → AoaLink
//  Peri Display
//
//  AOA(USB) 파이프 위에서 *기존 세션 계층*을 그대로 구동하는 상시 링크 관리자.
//  PeriAoaServer(raw 바이트, 상시 감시·자동 재연결)를 AoaBearer로 감싸 Session에 주입.
//
//  멀티 기기: USB 기기(deviceID = locationID)마다 독립된 FrameReader·AoaBearer·Session을
//  둔다. 한 기기의 바이트 스트림이 다른 기기와 섞이지 않고(리더 분리), 송신도 그 기기의 파이프로만
//  나간다(bearer 분리). LAN의 SessionManager가 clientID로 N개를 디먹스하는 것과 같은 구조.
//
//  세션 수명 = 기기별 ClientHello 단위: 같은 기기에서 새 ClientHello가 오면 "새 연결"로 보고
//  그 기기의 세션만 교체한다 — Android 앱 재시작/재접속이 케이블 재삽입 없이 즉시 새 세션으로 이어진다.
//  fd만 닫히고 조용히 죽은 세션(무응답 8s+)은 워치독이 정리해 인코더/가상디스플레이를 회수한다.
//

import Foundation
import Combine
import OSLog
import SwiftProtobuf

private nonisolated let log = Logger(subsystem: "com.peri.display.mac", category: "aoa")

@MainActor
final class AoaLink: ObservableObject {
    @Published private(set) var status = "대기"
    /// 연결된 USB 기기들의 세션 (UI 표시용, 연결 순서).
    @Published private(set) var sessions: [Session] = []
    /// 링크된 USB 기기 식별자 — 세션이 아직 없어도 존재한다(케이블만 꽂힌 상태).
    /// HID는 세션과 무관하게 EP0로 도니까 이 축이 따로 필요하다.
    @Published private(set) var usbDeviceIDs: [String] = []

    /// 스트리밍 중 Android가 1초마다 Ping/Feedback을 보내므로, 이만큼 무입력이면 죽은 세션.
    private static let sessionTimeout: TimeInterval = 8

    /// 기기 등록부 — AOA 연결도 **같은 기기의 또 다른 연결**이므로 여기에 합류한다.
    ///
    /// 이게 이번 설계의 실질적인 이득이다. 예전엔 USB 세션이 LAN 세션과 완전히 분리돼 있어서
    /// 케이블을 꽂을 때마다 Mac에 새 `Session`이 생기고 → 새 디스플레이가 만들어지고 →
    /// SCK 재열거가 돌아 붙어 있는 다른 기기까지 1초씩 끊겼다. 지금은 지문이 같으면 같은 기기라
    /// 디스플레이가 그대로 인계된다.
    weak var registry: SessionManager?

    private let aoa = PeriAoaServer()
    private let devices = DeviceRegistry()   // deviceID → 기기별 상태 (락 보호)
    private var watchdog: Task<Void, Never>?
    private var started = false

    /// USB 기기별 HID 터치 주입기 (AOAv2, EP0). **여기서 자동 등록하지 않는다** — 등록은
    /// 언제나 명시적 호출이어야 한다. 커널이 받는 입력이라 Android가 거부할 수 없으므로,
    /// "언제 켜지는가"가 유일한 방어선이다(DESIGN.md §5b).
    ///
    /// 주입기 자체는 [DeviceInputHub]가 들고 있다 — 앱은 지문으로 묻고, 링크는 케이블로
    /// 채운다. 그 둘을 잇는 사실("이 지문이 지금 이 케이블에 있다")만 여기서 나온다.

    /// USB 감시 시작 (앱 실행 시 자동 호출, 멱등).
    func start() {
        guard !started else { return }
        started = true
        status = "USB 감시 중 · 기기 연결 대기"
        let aoa = self.aoa
        let devices = self.devices
        // 등록소가 "이 지문이 지금 어느 케이블에 있나"를 물어 볼 통로. 앱은 이 함수의
        // 존재를 모르고, 우리는 앱의 존재를 모른다.
        DeviceInputHub.shared.deviceIDForFingerprint = { [weak self] fp in
            self?.usbDeviceID(forFingerprint: fp)
        }

        aoa.onConnected = { [weak self] deviceID in
            // 이 기기 전용 bearer/리더 등록 (기존 다른 기기 링크는 유지).
            devices.put(DeviceEntry(deviceID: deviceID,
                                    bearer: AoaBearer(aoa, deviceID: deviceID)))
            Task { @MainActor in
                guard let self else { return }
                // 주입기를 **만들어만** 둔다. Android에 HID 기기가 생기는 건 앱이
                // `register()`를 부를 때다 — 케이블을 꽂았다는 이유만으로 남의 폰에
                // 키보드를 심지 않는다.
                DeviceInputHub.shared.register(deviceID: deviceID, aoa: aoa)
                self.refreshUsbDevices()
                self.status = "USB \(devices.count)대 연결 · 세션 대기"
            }
        }
        aoa.onDisconnected = { [weak self] deviceID, reason in
            let entry = devices.remove(deviceID)
            let old = entry?.takeSession()
            Task { @MainActor in
                guard let self else { return }
                // 케이블이 빠진 뒤엔 unregister를 보낼 길이 없다(EP0도 함께 사라진다).
                // 주입기만 버린다 — 커널 쪽 HID 기기는 기기가 detach되며 같이 정리된다.
                DeviceInputHub.shared.forget(deviceID: deviceID)
                self.report(lost: old)
                self.refreshSessions()
                self.refreshUsbDevices()
                self.status = "USB 끊김(\(reason)) · 남은 \(devices.count)대 · 재연결 감시 중"
            }
        }
        aoa.onData = { [weak self] deviceID, chunk in
            guard let entry = devices.entry(deviceID) else { return }
            entry.touch()
            for (type, payload) in entry.parse(chunk) {
                // 새 StsHello = 이 기기의 새 연결 → 이 기기 세션만 교체. 동기 생성(nonisolated init)으로
                // 이어지는 핸드셰이크 프레임이 라우팅될 세션이 항상 존재하게 한다.
                if type == .handshake,
                   let msg = try? PeriControlMessage(serializedBytes: payload),
                   case .stsHello? = msg.payload {
                    let fresh = Session(server: entry.bearer, clientID: entry.bearer.clientID,
                                               reliableTransport: true)
                    let old = entry.swapSession(fresh)
                    log.info("aoa[\(deviceID)]: StsHello → 새 세션 (기존 \(old == nil ? "없음" : "교체"))")
                    Task { @MainActor in
                        guard let self else { return }
                        self.report(lost: old)
                        if let registry = self.registry { fresh.setManager(registry) }
                        // (제거됨) 여기서 재생 버퍼를 12ms로 낮추던 줄. 그건 **전송의 성질**이
                        // 낳는 값이라(AOA는 net 1~4ms로 평평해 큰 지터버퍼가 낭비) 서비스가
                        // 채널의 `reliableStream`을 보고 스스로 고른다 — 전송을 아는 쪽이
                        // 정해야 LAN↔USB 전환에서도 자동으로 따라간다.
                        // USB 분리 시의 Wi-Fi 폴백은 더 이상 여기서 주소를 밀어 넣지 않는다 —
                        // Android가 BLE로 재협상을 요청하면 후보를 새로 교환한다(망 전환과 동일 경로).
                        fresh.begin()
                        self.refreshSessions()
                        self.status = "USB \(devices.count)대 · 세션 \(self.sessions.count)개"
                    }
                }
                entry.session?.handle(type, payload)
            }
        }
        aoa.start()
        armWatchdog()
    }

    func stop() {
        watchdog?.cancel(); watchdog = nil
        aoa.stop()
        aoa.onConnected = nil; aoa.onDisconnected = nil; aoa.onData = nil
        for entry in devices.removeAll() {
            if let s = entry.takeSession() { Task { await s.teardown() } }
        }
        sessions = []
        usbDeviceIDs = []
        started = false
        status = "대기"
    }

    // (제거됨) `setProfile` — USB 세션에만 프로파일을 뿌리던 갈래.
    //
    // 재생 프로파일은 **기기**의 속성이지 전송의 속성이 아니다. USB 세션도 LAN 세션과 같은
    // 기기 등록부에 합류하므로, 팬아웃은 `SessionManager.setProfile` 하나면 된다.
    // 전송별로 따로 두면 케이블 탈착 순간에 한쪽만 적용되는 창이 생긴다.

    // MARK: - HID 터치 주입 (AOAv2)

    /// 이 기기가 보고한 AOA 프로토콜 버전. HID는 2 이상에서만 가능하다.
    func aoaProtocolVersion(_ deviceID: String) -> Int { Int(aoa.protocolVersion(of: deviceID)) }

    /// 지문 → USB deviceID. **여기만 아는 사실이다** — 입력 등록소가 이 함수를 물어 본다.
    ///
    /// 케이블에 꽂힌 기기 중 그 지문의 세션을 들고 있는 엔트리를 찾는다. Wi-Fi로만 붙어
    /// 있으면 없고, 그건 "입력을 넣을 수 없다"는 정확한 답이다(HID는 EP0가 필요하다).
    private func usbDeviceID(forFingerprint fp: Fingerprint) -> String? {
        devices.all().first { $0.session?.boundFingerprint == fp }?.deviceID
    }

    private func refreshUsbDevices() {
        usbDeviceIDs = devices.all().map(\.deviceID).sorted()
    }

    /// 끊긴 세션을 그 기기 엔트리에서 제거 (Mac에서 연결 해제한 경우). USB 링크 자체는 유지 —
    /// 케이블은 그대로이고, Android가 다시 연결하면 그 링크 위로 새 ClientHello가 온다.
    /// 이 세션이 USB 소유가 아니면 아무 일도 하지 않는다.
    func forget(_ session: Session) {
        for entry in devices.all() where entry.session === session {
            _ = entry.takeSession()
            refreshSessions()
            status = "연결 해제됨 · USB \(devices.count)대 감시 중"
            return
        }
    }

    /// 레지스트리의 현재 세션들을 @Published 배열로 동기 (UI 갱신).
    /// 죽은 AOA 세션을 등록부에 알린다 — 세션·디스플레이를 버릴지는 기계가 정한다.
    /// 기기에 합류하지 못한(핸드셰이크 전) 세션은 여기서 바로 내린다.
    private func report(lost old: Session?) {
        guard let old else { return }
        if let fp = old.boundFingerprint, let registry {
            registry.dispatch(fp, .connLost(old.connID))
        } else {
            Task { @MainActor in await old.teardown() }
        }
    }

    private func refreshSessions() {
        sessions = devices.all().compactMap { $0.session }
    }

    /// Android fd가 조용히 닫힌 경우(앱 종료/전환) USB 이벤트가 없다 → 무응답 세션을 정리해
    /// 인코더·가상디스플레이를 회수. 링크는 유지되어 다음 ClientHello로 즉시 재개된다.
    private func armWatchdog() {
        watchdog?.cancel()
        let devices = self.devices
        watchdog = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(2))
                guard let self, !Task.isCancelled else { break }
                // 세션 없는 링크는 건드리지 않는다 — Android가 준비되면 그 링크 위로 ClientHello가
                // 오고 바로 세션이 열린다(유휴 링크는 무해). 부트스트랩은 Android가 주도.
                for entry in devices.all() where entry.session != nil {
                    guard entry.secondsSinceTouch() > Self.sessionTimeout else { continue }
                    let old = entry.takeSession()
                    if let old { await old.teardown() }
                    log.info("aoa[\(entry.deviceID)]: 세션 무응답 \(Int(Self.sessionTimeout))s → 정리")
                    self.refreshSessions()
                    self.status = "세션 무응답 → 정리됨 · USB 감시 중"
                }
            }
        }
    }
}

/// 한 USB 기기의 상태: 전용 FrameReader(스트림 분리) + bearer(송신 라우팅) + 세션 + 최종수신 시각.
/// read 큐(기기별)와 MainActor에서 함께 접근하므로 락 보호.
private final class DeviceEntry: @unchecked Sendable {
    let deviceID    : String
    let bearer: AoaBearer
    private let lock = NSLock()
    private var reader = FrameReader()
    private var _session: Session?
    private var lastData = Date()

    init(deviceID: String, bearer: AoaBearer) {
        self.deviceID = deviceID
        self.bearer = bearer
    }

    func parse(_ chunk: Data) -> [(WireType, Data)] {
        lock.lock(); defer { lock.unlock() }
        reader.append(chunk)
        var out: [(WireType, Data)] = []
        while let f = reader.next() { out.append(f) }
        return out
    }

    var session: Session? { lock.lock(); defer { lock.unlock() }; return _session }

    /// 세션 교체 + 리더 리셋(새 연결은 프레임 경계가 새로 시작). 기존 세션 반환.
    @discardableResult
    func swapSession(_ new: Session?) -> Session? {
        lock.lock(); defer { lock.unlock() }
        let old = _session
        _session = new
        if new == nil { reader = FrameReader() }
        return old
    }

    func takeSession() -> Session? { swapSession(nil) }

    func touch() { lock.lock(); lastData = Date(); lock.unlock() }
    func secondsSinceTouch() -> TimeInterval {
        lock.lock(); defer { lock.unlock() }
        return Date().timeIntervalSince(lastData)
    }
}

/// deviceID → DeviceEntry. 콜백(기기별 read 큐/connect 큐)과 MainActor가 공유하므로 락 보호.
private final class DeviceRegistry: @unchecked Sendable {
    private let lock = NSLock()
    private var map: [String: DeviceEntry] = [:]

    func put(_ e: DeviceEntry) { lock.lock(); map[e.deviceID] = e; lock.unlock() }
    func entry(_ id: String) -> DeviceEntry? { lock.lock(); defer { lock.unlock() }; return map[id] }
    func remove(_ id: String) -> DeviceEntry? {
        lock.lock(); defer { lock.unlock() }
        return map.removeValue(forKey: id)
    }
    func all() -> [DeviceEntry] { lock.lock(); defer { lock.unlock() }; return Array(map.values) }
    func removeAll() -> [DeviceEntry] {
        lock.lock(); defer { lock.unlock() }
        let v = Array(map.values); map.removeAll(); return v
    }
    var count: Int { lock.lock(); defer { lock.unlock() }; return map.count }
}
