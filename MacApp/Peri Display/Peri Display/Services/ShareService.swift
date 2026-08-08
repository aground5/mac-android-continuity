//
//  ShareService.swift
//  Peri Display
//
//  **앱 #2 — 화면 공유 (Android → Mac).**
//
//  확장의 거울상이지만 대칭은 아니다. 여기서 Mac은 **받는 쪽**이고, 시작 버튼은 Mac에 있어도
//  실제로 화면을 내줄지는 사람이 폰에서 정한다:
//
//      Mac이 아이콘을 누른다 → 폰이 사람에게 묻는다(MediaProjection) → 인코더 기동 → 프레임
//
//  ⚠ **동의는 세션보다 수명이 짧다.** Android 14+는 `mediaProjection` 타입 포그라운드
//  서비스가 선행돼야 하고 동의 토큰이 1회용이다. 무손실 재개로 세션이 살아남아도 이 앱은
//  못 살아난다 — 그래서 `detach`에서 확장과 정반대로 행동한다(전부 버린다).
//
//  영상 배관은 확장과 **그대로 공유된다** — 키가 이미 방향별(`sendKey`/`recvKey`)이라
//  Android→Mac 영상은 Android의 송신키를 쓰면 되고 nonce 공간도 겹치지 않는다.
//

import AppKit
import Foundation
import Combine
import CoreMedia
import OSLog

private nonisolated let log = Logger(subsystem: "com.peri.display.mac", category: "share")

/// 수신 문 — 메인 액터(기계)가 여닫고 전송 큐(프레임)가 읽는다.
///
/// 기계 상태(`recv`)를 그대로 읽으면 될 것 같지만 그건 메인 액터에 갇혀 있고, 프레임은
/// 전송 큐에서 온다. 매 프레임 액터를 건너가며 물어볼 수는 없으니 **한 비트만 밖에 내놓는다**.
private nonisolated final class ShareGate: @unchecked Sendable {
    private let lock = NSLock()
    private var _open = false
    /// 도착 사실을 기계에 알려야 하는가. **`.up`으로 갈 때까지 열려 있다.**
    private var _needsSignal = false
    /// 알림 하나가 이미 메인 액터로 가 있는가 — 프레임마다 Task를 만들지 않기 위한 잠금.
    private var _signalInFlight = false
    private var _frames = 0

    var open: Bool { lock.lock(); defer { lock.unlock() }; return _open }

    func open(_ on: Bool) {
        lock.lock(); _open = on; _needsSignal = on; _signalInFlight = false; _frames = 0; lock.unlock()
    }

    /**
     지금 "프레임이 왔다"를 기계에 알려야 하는가.

     ★ **한 번만 알리면 안 된다.** 예전엔 첫 프레임에서 한 번 알리고 잠갔는데, 그게 검은
     화면의 원인이었다: 기계의 `frameArrived`는 `hasParams`를 요구하는데, `ShareParams`는
     **신뢰 채널(TCP)**로 오고 프레임은 **손실 허용 채널(UDP)**로 온다. 둘 사이엔 순서
     보장이 아예 없다. 프레임이 먼저 닿으면 기계는 "제원 없이 도착한 프레임 — 폐기"를
     찍고 `.starting`에 머무는데, 알림은 이미 소진돼 다시 오지 않는다 → 영원히 `.up`이
     못 된다. 화면에는 아무것도 안 나오고, 로그에는 인코더가 잘 도는 것만 보인다.

     그래서 `.up`이 확인될 때까지 계속 알린다. 다만 동시에 하나만 — 안 그러면 프레임마다
     메인 액터로 Task가 날아간다.
     */
    func takeArrivalSignal() -> Bool {
        lock.lock(); defer { lock.unlock() }
        _frames += 1
        guard _needsSignal, !_signalInFlight else { return false }
        _signalInFlight = true
        return true
    }

    /// 알림이 끝났다. 기계가 `.up`에 닿았으면 잠그고, 아니면 다음 프레임에 다시 알린다.
    func settleSignal(reachedUp: Bool) {
        lock.lock()
        _signalInFlight = false
        if reachedUp { _needsSignal = false }
        lock.unlock()
    }

    /// 문이 열린 뒤 들어온 프레임 수 — 진단용.
    var frameCount: Int { lock.lock(); defer { lock.unlock() }; return _frames }
}

@MainActor
final class ShareService: ObservableObject, PeriApp {

    nonisolated static let descriptor = AppDescriptor(
        id: .share, name: "화면 공유", icon: "iphone.and.arrow.forward",
        blurb: "기기 화면을 Mac 창에 띄웁니다",
        // 확장과 부딪힌다 — 무한 거울. 목록은 **대칭**이다(ExtendService에도 같은 줄).
        conflicts: [.extend],
        // 방향만 반대일 뿐 화면을 나르는 건 같다 → 데이터그램 경로가 필요하다.
        needsDatagram: true)

    let fingerprint: Fingerprint
    var onQuit: ((String) -> Void)?

    private var machine = ShareState()
    private var channel: ServiceChannel?

    var isRunning: Bool { machine.active }
    var statusText: String? { machine.detail }

    /// 지금 그릴 수 있는 프레임이 들어오고 있는가 — 홈 화면이 폰 틀 안을 이 값으로 가른다.
    @Published private(set) var isReceiving = false
    /// 상대가 보고한 영상 크기 — 폰 틀의 종횡비를 여기에 맞춘다.
    @Published private(set) var frameSize = CGSize(width: 1080, height: 2400)
    /// 기기 화면의 실제 물리 크기(µm). 0 = 기기가 모른다.
    private var physicalUm = (w: 0, h: 0)

    /// 창 제목에 쓸 기기 이름.
    ///
    /// 신뢰저장소에서 직접 꺼낸다 — 사이드바가 오프라인 기기 이름을 얻는 곳과 같다.
    /// 세션을 거쳐 받아 오면 세션이 갈릴 때마다 제목이 흔들리고, 이 앱은 세션보다 오래 산다.
    private var deviceName: String {
        TrustStore.shared.all().first { $0.fingerprint == fingerprint }?.name ?? "기기"
    }

    /// 실물 크기 전용 창. 앱이 뜨면 열리고 지면 닫힌다 — **창이 곧 앱이다.**
    private var window: ShareWindowController?

    /// 디코드·표시. 인코딩된 AU를 그대로 먹여 주면 화면에 나온다(별도 디코더 세션 불필요).
    /// **`nonisolated`다** — 프레임이 전송 큐에서 곧장 들어간다(`ShareRenderer` 주석 참조).
    nonisolated let renderer = ShareRenderer()

    /// 지금 프레임을 받아도 되는가 — **전송 큐에서** 읽힌다.
    ///
    /// 리시버가 서기 전(동의 대기)이나 내려간 뒤에 도착한 프레임을 그냥 그리면 껐는데도
    /// 화면이 남는다. 상대의 인코더가 멈추기까지는 늘 몇 프레임이 더 온다.
    private let gate = ShareGate()

    private var consentDeadlineTask: Task<Void, Never>?

    init(fingerprint: Fingerprint) {
        self.fingerprint = fingerprint
    }

    // MARK: - 앱 계약

    func attach(_ ch: ServiceChannel) async {
        channel = ch
        await dispatch(.linked)
        // 전송이 갈렸을 수 있다(무선 → 케이블). 입력 통로를 다시 묻는다.
        refreshInputs()
    }

    func detach(_ reason: DetachReason) async {
        channel = nil
        await dispatch(.unlinked)
        if case .deviceGone(let why) = reason { await quit(why) }
    }

    func launch() { Task { await dispatch(.activated) } }

    func quit(_ reason: String) async {
        guard machine.active else { return }
        log.notice("공유 종료: \(reason)")
        await dispatch(.deactivated)
    }

    nonisolated func receive(_ msg: AppMessage) {
        switch msg {
        case .media(let au, let meta): receiveMedia(au, meta)
        case .control(let m):          receiveControl(m)
        }
    }

    private nonisolated func receiveControl(_ m: PeriControlMessage) {
        switch m.payload {
        case .shareState(let st):
            // 상대가 동의 결과·중단을 알려 온다. 사람이 누른 결과라 이 한 갈래가 전부다.
            Task { @MainActor in
                switch st.phase {
                case .granted:  await self.dispatch(.consentGranted)
                case .denied:   await self.dispatch(.consentDenied(st.reason.isEmpty ? "기기에서 거부했습니다" : st.reason))
                case .stopped:  await self.dispatch(.remoteStopped(st.reason.isEmpty ? "기기에서 중지했습니다" : st.reason))
                default: break
                }
            }
        case .shareParams(let p):
            Task { @MainActor in
                self.frameSize = CGSize(width: Int(p.width), height: Int(p.height))
                self.physicalUm = (Int(p.physicalWidthUm), Int(p.physicalHeightUm))
                self.renderer.configure(codec: p.codec)
                self.window?.apply(video: self.frameSize,
                                   physicalWidthUm: self.physicalUm.w,
                                   physicalHeightUm: self.physicalUm.h)
                await self.dispatch(.paramsArrived)
            }
        default:
            break
        }
    }

    // MARK: - 영상 수신

    /// 완성된 접근 단위 하나. **전송 큐에서 동기로** 불린다 — 메인 액터로 넘기지 않는다.
    ///
    /// 프레임마다 액터 홉을 하나 걸면 60fps에서 초당 60번이고, 더 나쁜 건 `Task`들 사이의
    /// **순서가 보장되지 않는다**는 점이다. 디코더에 프레임이 뒤바뀌어 들어가면 참조가 깨진다.
    /// `AVSampleBufferDisplayLayer.enqueue`는 그 자체로 스레드 안전하므로 여기서 바로 넣는다.
    private nonisolated func receiveMedia(_ au: Data, _ meta: MediaMeta) {
        guard gate.open else { return }
        renderer.enqueue(au, keyframe: meta.keyframe, timestampMs: meta.timestampMs)
        // 프레임이 **선을 타고 오긴 하는가**를 눈으로 확인할 수 있어야 한다. 이게 없으면
        // "안 나온다"의 원인이 수신인지 표시인지 로그만 보고는 못 가른다.
        let n = gate.frameCount
        if n < 3 || n % 120 == 0 {
            log.info("공유 프레임 #\(n + 1) key=\(meta.keyframe) \(au.count)B")
        }
        guard gate.takeArrivalSignal() else { return }
        Task { @MainActor in
            await self.dispatch(.frameArrived)
            // 기계가 `.up`에 못 갔으면(제원이 아직 안 왔다) 다음 프레임에 다시 알린다.
            self.gate.settleSignal(reachedUp: self.machine.recv == .up)
        }
    }

    // MARK: - 창

    /// 실물 크기 창을 연다. **입력은 여기서 붙는다** — 케이블이 없으면 nil이 가고, 창이
    /// 그 사실을 사용자에게 설명한다.
    private func openWindow() {
        if window == nil {
            window = ShareWindowController(title: deviceName, renderer: renderer) { [weak self] in
                // 사용자가 창을 닫았다 = 앱을 껐다. 창이 곧 앱이라 그 반대는 없다.
                Task { @MainActor in self?.onQuit?("창을 닫았습니다") }
            }
        }
        window?.show()
        window?.apply(video: frameSize,
                      physicalWidthUm: physicalUm.w, physicalHeightUm: physicalUm.h)
        refreshInputs()
    }

    /// 공유 창을 앞으로 낸다. 알림을 눌렀을 때 방금 연 화면을 바로 보게 하려는 것 —
    /// 창이 없으면(공유가 꺼져 있으면) 아무 일도 하지 않는다. **켜지는 않는다.**
    func focusWindow() {
        window?.show()
        NSApp.activate(ignoringOtherApps: true)
    }

    private func closeWindow() {
        window?.setInputs(nil)
        window?.close()
        window = nil
    }

    /// 이 기기에 입력을 넣을 수 있는지 다시 확인해 창에 반영한다.
    ///
    /// 케이블은 세션과 **따로** 오간다(무선으로 붙어 있다가 나중에 꽂을 수 있다). 그래서
    /// 앱이 뜰 때 한 번 보고 마는 게 아니라, 채널이 갈릴 때마다 다시 묻는다.
    private func refreshInputs() {
        guard let window else { return }
        let inputs = DeviceInputHub.shared.inputs(for: fingerprint)
        window.setInputs(inputs?.isSupported == true ? inputs : nil)
        if inputs == nil {
            log.info("입력 통로 없음 — 무선 연결이거나 AOAv1. 화면만 보인다.")
        }
    }

    // MARK: - 기계 구동

    private var evQueue: [ShareEv] = []
    private var draining = false

    func dispatch(_ ev: ShareEv) async {
        evQueue.append(ev)
        guard !draining else { return }
        draining = true
        defer { draining = false }
        while !evQueue.isEmpty {
            let e = evQueue.removeFirst()
            let step = reduceShare(machine, e)
            machine = step.state
            for fx in step.fx { await run(fx) }
        }
        isReceiving = machine.isReceiving
        objectWillChange.send()
    }

    private func run(_ fx: ShareFx) async {
        switch fx {
        case .requestConsent:
            // 폰에게 "화면을 내줘" — 사람에게 묻는 건 저쪽 몫이다.
            var req = PeriShareRequest()
            req.action = .start
            var m = PeriControlMessage(); m.shareRequest = req
            channel?.sendReliable(m)
            log.notice("화면 공유 요청 송신")

        case .armConsentDeadline(let secs):
            consentDeadlineTask?.cancel()
            consentDeadlineTask = Task { @MainActor [weak self] in
                try? await Task.sleep(for: .seconds(secs))
                guard !Task.isCancelled else { return }
                await self?.dispatch(.consentTimeout)
            }

        case .cancelConsentDeadline:
            consentDeadlineTask?.cancel(); consentDeadlineTask = nil

        case .startReceiver:
            channel?.resetMediaReceive()
            renderer.start()
            gate.open(true)
            openWindow()

        case .stopReceiver:
            // ★ **문을 먼저 닫는다.** 상대의 인코더는 우리가 멈추라고 한 뒤에도 몇 프레임을
            // 더 내보내는데, 그게 방금 비운 레이어에 다시 쌓이면 껐는데도 그림이 남는다.
            gate.open(false)
            renderer.stop()
            channel?.resetMediaReceive()
            closeWindow()

        case .tellRemoteStop:
            var req = PeriShareRequest()
            req.action = .stop
            var m = PeriControlMessage(); m.shareRequest = req
            channel?.sendReliable(m)

        case .reportFailed(let why):
            onQuit?(why)

        case .log(let m):
            log.info("share: \(m)")
        }
    }
}
