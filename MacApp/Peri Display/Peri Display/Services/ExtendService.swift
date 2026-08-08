//
//  ExtendService.swift
//  Peri Display
//
//  **서비스 #1 — 화면 확장 (Mac → Android).**
//
//  여태 이 코드는 `Session` 안에 있었다. 세션이 곧 미러였으므로 그게 이상하지 않았다.
//  이제 세션은 여러 서비스가 공유하는 암호화된 통로이고, 화면 확장은 그 위의 서비스 하나다
//  (DESIGN.md §5a).
//
//  이 클래스가 세션에 대해 아는 것은 [ServiceChannel] 하나뿐이다 — 봉인도, TCP/UDP/bulk도,
//  clientID도 모른다. "반드시 도착해야 한다"와 "최신이면 된다"만 말한다.
//
//  수명
//  ──
//  서비스는 **기기에 매인다.** 세션은 갈리지만(RESUME·콜드 재수립·LAN↔AOA 전환) 가상
//  디스플레이는 그대로 살아 있어야 한다 — 케이블을 꽂을 때마다 디스플레이가 파괴·재생성되면
//  ScreenCaptureKit 재열거 사이클이 돌아 **붙어 있는 모든 기기**가 1초씩 끊긴다(FB17797423).
//
//  그래서 채널이 끊길 때 무엇을 지키고 무엇을 버릴지는 이 서비스가 정한다:
//    · 지킨다 — 가상 디스플레이(lease), 마지막 협상값
//    · 버린다 — 캡처·인코더 파이프라인
//  Wi-Fi→LTE 전환에서 화면이 마지막 프레임에 얼어붙던 버그가 정확히 이 구분의 부재였다.
//

import Foundation
import Combine
import SwiftProtobuf
import CoreGraphics
import OSLog

private nonisolated let log = Logger(subsystem: "com.peri.display.mac", category: "extend")

/// 이 서비스가 파이프라인을 세우다 막히는 경우. 문구는 그대로 UI의 `detail`에 실린다.
nonisolated enum ExtendError: LocalizedError {
    case virtualDisplayFailed
    case noChannel
    var errorDescription: String? {
        switch self {
        case .virtualDisplayFailed: return "가상 디스플레이 생성 실패 (CGVirtualDisplay)"
        case .noChannel:            return "보낼 채널이 없습니다"
        }
    }
}

@MainActor
final class ExtendService: ObservableObject, PeriApp {

    nonisolated static let descriptor = AppDescriptor(
        id: .extend, name: "화면 확장", icon: "display",
        blurb: "Mac 화면을 기기로 넓힙니다",
        // 공유와 부딪힌다: Mac이 보낸 화면을 폰이 띄우고 있는데 그걸 다시 찍어 보내면
        // 무한 거울이 된다. 목록은 **대칭**이어야 한다(ShareService에도 같은 줄이 있다).
        conflicts: [.share],
        // 화면을 나른다 → 이 앱이 떠 있는 동안만 데이터그램 경로가 열린다.
        needsDatagram: true)

    /// 이 앱의 상태 기계. 파이프라인·디스플레이·SCK 사이클이 전부 여기 있다.
    private var machine = ExtendState()

    var isRunning: Bool { machine.active }
    var statusText: String? { machine.detail }
    var onQuit: ((String) -> Void)?
    /// 진단 화면이 읽는 기계 상태. **기기 기계가 아니라 여기 있다** — 파이프라인이 어디까지
    /// 섰는지는 이 앱만 안다.
    var machineState: ExtendState { machine }

    // MARK: - 세션과의 접점 (이게 전부다)

    /// 봉인·경로 선택·조각화·페이싱을 소유한 손잡이. 서비스가 세션에 대해 아는 유일한 것.
    ///
    /// **갈린다.** 서비스는 기기에 매여 세션보다 오래 살고(RESUME·콜드 재수립·LAN↔AOA 전환),
    /// 그때마다 `attach`로 새 채널이 온다. 채널이 없는 구간(경로 상실 ~ 복귀)이 정상 상태라
    /// 옵셔널이다 — 그 구간에 나가려던 제어 메시지는 조용히 버려지고, 파이프라인이 다시 서면
    /// 어차피 다시 나간다.
    private var channel: ServiceChannel?

    /// 이 전송에서 허용하는 협상 상한(fps·비트레이트). **채널이 답한다** —
    /// 케이블을 꽂으면 같은 서비스가 그대로 AOA 상한으로 갈아탄다.
    private var limits: ExtendLimits { .of(reliableStream: channel?.caps.reliableStream ?? false) }
    /// 신뢰·순서보장·광대역 전송(AOA)인가. 적응 컨트롤러가 손실 대신 큐잉지연만 보고
    /// 감속할지를 가른다.
    private var reliableTransport: Bool { channel?.caps.reliableStream ?? false }
    /// 로그·가상 디스플레이 이름표에 쓰는 기기 표시명. ClientHello의 인증서에서 읽는다
    /// (검증이 끝난 뒤에야 이 서비스로 배달되므로 주장이 아니라 확정된 이름이다).
    private var deviceName = "기기"
    /// 이 기기 신원 → 전용 가상 디스플레이 serial. 오염 복구 때 회전 대상.
    let fingerprint: Fingerprint

    // MARK: - UI 표면

    @Published private(set) var stats = StreamStats()
    /// **예외 상황만** 담는 부가 문구(디스플레이 복구, 구성 실패). 평상시엔 nil이다.
    @Published private(set) var detail: String?
    /// 수신측 고정 지터버퍼 깊이(ms). UI 슬라이더로 라이브 조절.
    ///
    /// 기본값은 **전송이 정한다** — AOA는 net 1~4ms로 평평해 큰 버퍼가 그대로 낭비 지연이고,
    /// Wi-Fi는 지터를 흡수할 여유가 필요하다. 사용자가 한 번 손대면 그 값을 지킨다.
    @Published var playoutBufferMs: Double = ExtendService.lanBufferMs
    /// 사용자가 슬라이더를 만졌는가 — 만졌으면 전송이 바뀌어도 기본값으로 되돌리지 않는다.
    private var playoutBufferPinned = false
    private static let lanBufferMs: Double = 50
    private static let aoaBufferMs: Double = 12

    // MARK: - 파이프라인 소유물

    private let capturer = ScreenCapturer()
    private let encoderBox = EncoderBox()
    private let byteMeter = ByteMeter()
    private let frameCounter = SeqCounter()
    private let ltrMap = LtrMap()
    private let bitrate = BitrateController()
    private let latency = LatencyBox()          // 지연 스택 구간값 (enc 로컬 + net/jbuf/dec 원격)
    // 적응형 캡처 fps — HW 인코더는 공유 자원이므로 컨트롤러도 전역 1개(세션별이면 서로 진동).
    private let fpsGovernor = FpsGovernor.shared

    private var statsTask: Task<Void, Never>?
    private var encoder: VideoEncoder?
    private var virtualDisplay: PeriVirtualDisplay?
    /// 확정된 HDR 마스터링 조건 (헤드룸 실측 기반). SDR이면 nil.
    private var mastering: HDRMasteringInfo?

    /// 사용자 HDR 스위치. **협상 능력과 별개다** — 켤 수 있는 세션에서 실제로 켤지의 선택이다.
    ///
    /// **베타 기능이라 기본값은 꺼짐이다.** 끈 상태에서는 가상 디스플레이도 SDR(8bpc·Rec.709)로
    /// 만든다 — HDR을 안 쓰는 사용자가 16bpc 프레임버퍼 비용을 낼 이유가 없고, 그래야 기존과
    /// 완전히 같은 동작이 보장된다.
    private(set) var hdrEnabled = false
    /// 이 세션에서 HDR을 켤 수 **있는가** — 협상이 HDR10으로 났는가. UI 노출 조건.
    var hdrAvailable: Bool { lastParams?.wantsHDR ?? false }
    /// 지금 실제로 HDR로 송출 중인가 (협상 ∧ 사용자 스위치 ∧ 실측 헤드룸).
    private(set) var hdrActive = false
    /// 마지막으로 받은 디스플레이 제원 — HDR 토글이 디스플레이를 다시 잡을 때 필요하다.
    private var lastSpec: DisplaySpec?
    /// 빌려 둔 디스플레이. 기계의 `leaseDisplay` 지시로 채워지고, 파이프라인이 이걸 쓴다.
    private var lease: DisplayManager.Lease?
    private var pipelineBuilt = false
    /// **협상된** 값 — 다음에 갈 곳. 재협상(회전·분할)이 오면 파이프라인보다 먼저 갱신된다.
    private var lastParams: NegotiatedParams?
    /// **실제로 돌고 있는** 값 — 캡처·인코더가 지금 물고 있는 크기.
    ///
    /// 이 둘을 한 변수로 겸용하던 것이 회전 버그의 원인이었다. `adopt(params:)`가 새 값을
    /// 먼저 덮으므로, `resizePipeline`이 "바뀐 게 있나"를 협상값과 비교하면 **언제나 같아서**
    /// 조기 반환한다 — 디스플레이만 새 크기가 되고 캡처는 옛 크기로 남아 레터박스가 생기고,
    /// `activatePreferredMode()`를 건너뛰어 HiDPI가 1x로 떨어진다.
    private var appliedParams: NegotiatedParams?
    private var lastRecoveryAt = Date.distantPast
    private var offlineRecoveries = 0
    private var recoveryGivenUp = false
    /// 재생 프로파일. 빌드 시 ProfileStore에서 읽고, UI 토글은 setProfile로 라이브 전환.
    private var streamProfile: StreamProfile = .lowLatency
    /// 클라가 핸드셰이크 때 보고한 고정 특성. 회전·분할 재협상이 w/h만 바꿔 다시 협상할 때 쓴다.
    /// 수신 경로(nonisolated)에서 쓰고 읽으므로 락 보호 박스다.
    private let clientCaps = ClientCapsBox()
    /// 직전 채널의 전송 종류 — 갈렸는지 알아야 협상값을 버릴지 정한다(attach 참조).
    private var lastTransportReliable: Bool?

    private static let maxOfflineRecoveries = 3

    init(fingerprint: Fingerprint) {
        self.fingerprint = fingerprint
        // ★ SCK 재열거 사이클을 **직접** 구독한다. 세션을 거치지 않는 이유는 이게 연결
        // 수명과 아무 상관이 없기 때문이다 — 가상 디스플레이를 쓰는 앱만의 사정이고,
        // 공유 앱은 이 사건이 있다는 것조차 몰라도 된다.
        DisplayManager.shared.observeSCKCycle(fingerprint) { [weak self] phase in
            guard let self else { return }
            if phase == .suspendAll { await self.suspendForSCK() } else { await self.resumeAfterSCK() }
        }
    }

    /// 프레임 라우터가 LAN/AOA 큐에서 **동기로** 만지는 부분만 묶은 손잡이.
    ///
    /// 여기 담긴 것은 전부 락으로 보호된 박스라 MainActor를 거치지 않는다 — 거치면 프레임마다
    /// 홉이 생긴다. 박스들이 애초에 `@unchecked Sendable`로 만들어진 이유가 이 경로다.
    nonisolated var inbox: ExtendInbox {
        ExtendInbox(encoderBox: encoderBox, ltrMap: ltrMap, bitrate: bitrate, latency: latency)
    }

    // MARK: - 서비스 계약 (Service 프로토콜)

    /// 말할 채널이 생겼다.
    ///
    /// **여러 번 불린다** — 세션은 갈리지만(RESUME은 채널 안쪽에서 clientID만 바뀌므로 여기
    /// 오지 않고, 콜드 재수립과 LAN↔AOA 전환이 여기로 온다) 서비스는 기기에 매여 살아 있다.
    /// 파이프라인을 여기서 세우지 않는 게 중요하다 — 세울지 말지는 기기 상태 기계가 정하고
    /// (`sessionLive` → `buildPipeline`), 그래야 "경로는 왔는데 세션은 아직"인 구간에
    /// 헛도는 인코더가 생기지 않는다.
    func attach(_ ch: ServiceChannel) async {
        channel?.onDatagramPathReady(nil)   // 옛 채널의 알림 끊기
        // ★ 전송이 갈렸으면(LAN↔USB) **옛 협상값을 버린다.**
        //
        // fps 상한도 비트레이트 천장도 초기 동작점도 전부 전송이 정하는 값이다(ExtendLimits).
        // 서비스가 세션보다 오래 살게 되면서 생긴 함정이 여기다: 케이블을 꽂으면 기계가
        // `sessionLive` → `buildPipeline`을 곧바로 지시하는데, 그 시점의 `lastParams`는 아직
        // Wi-Fi 협상값이다. 그대로 세우면 USB인데 60fps·25Mbps로 돌고, 곧이어 도착하는 새
        // 협상은 해상도가 같아서(같은 폰이다) resize 조기반환에 걸려 **영영 반영되지 않는다.**
        //
        // 비워 두면 그 build는 아무것도 하지 않고 지나가고(pipe는 `.building`에 머문다),
        // 새 ClientHello가 협상을 마치며 올리는 `specChanged`가 같은 지시를 다시 내린다.
        // attach가 왔다는 건 새 세션이 섰다는 뜻이고, 그건 곧 새 ClientHello가 온다는 뜻이다.
        if let was = lastTransportReliable, was != ch.caps.reliableStream {
            log.notice("전송 교체 (\(was ? "USB" : "Wi-Fi") → \(ch.caps.reliableStream ? "USB" : "Wi-Fi")) → 협상값 재산출 대기")
            lastParams = nil
        }
        lastTransportReliable = ch.caps.reliableStream
        channel = ch
        // 최신성 경로가 열리는 순간 = 수신측이 아직 아무 참조 프레임도 없는 순간 → 즉시 IDR.
        // (손실 복구의 LTR-P와 다르다. 저쪽엔 되살릴 참조가 아예 없다.)
        ch.onDatagramPathReady { [weak self] in self?.inbox.requestKeyframeNow() }
        // 전송이 바뀌었으면 지터버퍼 기본값도 따라간다(사용자가 손댄 적 없을 때만).
        if !playoutBufferPinned {
            playoutBufferMs = ch.caps.reliableStream ? Self.aoaBufferMs : Self.lanBufferMs
        }
        // 통계 루프는 여기서 안 켠다 — 채널이 붙은 것과 **화면이 흐르는 것**은 다르다.
        // 예전엔 여기서 켜서, 확장을 한 번도 안 띄운 세션에서도 매초 `@Published`가 갱신되고
        // SwiftUI가 다시 그렸다. 잴 것도 보여 줄 것도 없는 시간이었다.
        if machine.active { startStatsLoop() }
        await dispatch(.linked)
    }

    /// 채널이 끊겼다. 무엇을 지키고 무엇을 버릴지는 **기계가** 안다(`unlinked`는 아무것도
    /// 안 버린다 — 유예 안에 돌아오면 아무 일도 없었던 게 된다).
    func detach(_ reason: DetachReason) async {
        channel?.onDatagramPathReady(nil)
        channel = nil
        await dispatch(.unlinked)
        if case .deviceGone(let why) = reason {
            await quit(why)
            // 기기가 떠났다 → 구독도 뺀다. 안 빼면 등록부에 죽은 항목이 쌓이고, 사이클마다
            // 아무 일도 안 하는 클로저를 하나씩 더 부른다.
            DisplayManager.shared.removeSCKObserver(fingerprint)
        }
    }

    // MARK: - 앱 수명

    /// 사용자가 아이콘을 눌렀다.
    func launch() {
        Task { @MainActor in
            await self.dispatch(.activated)
            // 이제부터 잴 것이 생긴다. 채널이 아직이면 [attach]가 켠다.
            if self.channel != nil { self.startStatsLoop() }
        }
    }

    /// 껐다. 캡처를 멈추고 가상 디스플레이까지 놓는다(기계의 `deactivated`가 지시한다).
    func quit(_ reason: String) async {
        guard machine.active else { return }
        log.notice("확장 종료: \(reason)")
        await dispatch(.deactivated)
        statsTask?.cancel(); statsTask = nil
        stats = StreamStats()
    }

    /// 기계를 한 걸음 돌리고 나온 지시를 실행한다.
    ///
    /// 부작용 실행 중에 또 사건이 생길 수 있어(구성 실패 → `pipelineFailed`) 큐로 직렬화한다.
    private var evQueue: [ExtendEv] = []
    private var draining = false

    func dispatch(_ ev: ExtendEv) async {
        evQueue.append(ev)
        guard !draining else { return }
        draining = true
        defer { draining = false }
        while !evQueue.isEmpty {
            let e = evQueue.removeFirst()
            let step = reduceExtend(machine, e)
            machine = step.state
            for fx in step.fx { await run(fx) }
        }
        objectWillChange.send()
    }

    private func run(_ fx: ExtendFx) async {
        switch fx {
        case .leaseDisplay(let spec):   await leaseDisplay(spec)
        case .buildPipeline:            await buildOrResize()
        case .teardownPipeline:         await teardownPipeline()
        case .suspendPipeline:          await teardownPipeline()
        case .resumePipeline:           await buildOrResize()
        case .releaseDisplay(let why):
            lease = nil
            await DisplayManager.shared.retire(fingerprint: fingerprint, reason: why)
        case .reportFailed(let why):    onQuit?(why)
        case .log(let m):               log.info("extend: \(m)")
        }
    }

    /// 상대가 보낸 메시지. **전송 큐에서 동기로** 불린다(`nonisolated`).
    ///
    /// 손실 복구·피드백은 여기서 곧장 끝난다 — MainActor를 거치면 프레임마다 홉이 생기고,
    /// 특히 `TransportFeedback`은 비트레이트를 즉시 되먹여야 해서 지연이 곧 진동이 된다.
    /// 파이프라인을 흔드는 것(협상)만 MainActor로 넘긴다.
    nonisolated func receive(_ msg: AppMessage) {
        guard case .control(let m) = msg else { return }
        switch m.payload {
        case .keyframeRequest:
            inbox.onKeyframeRequest()   // 복구: 작은 LTR-P (ACK된 LTR 없으면 IDR 폴백)

        case .ltrAck(let ack):
            inbox.onLtrAck(frameId: ack.frameID)

        case .transportFeedback(let tf):
            // 손실률 = 1 - received/(highest-base+1), 큐잉지연 = 수신측 적응버퍼 추정.
            // 둘로 지연 인식형 GCC-lite가 비트레이트를 링크 한계까지 자동수렴(품질↑·큐 얕게).
            let span = Int(tf.highestSeq) &- Int(tf.baseSeq) + 1
            guard span > 0 else { return }
            let loss = max(0, 1.0 - Double(tf.receivedCount) / Double(span))
            inbox.onFeedback(loss: loss, queueDelayMs: Int(tf.queueDelayMs))
            // 표시 전용 구간 통계 (제어에 미사용) — 상세 화면 지연 스택.
            inbox.onRemoteLatency(net: Int(tf.pipeNetMs), jbuf: Int(tf.pipeJbufMs),
                                  dec: Int(tf.pipeDecMs), rtt: Int(tf.rttMs))

        case .stsHello(let hello):
            // 세션이 **신원 검증을 마친 뒤** 되돌려 준 핸드셰이크다. 무엇을 어떤 코덱·크기·
            // 비트심도로 보낼지는 여기서 정한다 — 세션은 그 단어들을 몰라야 한다.
            //
            // `core`가 불투명 바이트인 건 서명이 **선에 나간 바이트 그대로**를 덮게 하려는
            // 것이다([PeriSts] 주석). 그 안을 읽어야 하는 건 협상하는 쪽이므로 여기서 푼다.
            guard let ch = try? PeriStsHelloCore(serializedBytes: hello.core) else { return }
            Task { @MainActor in self.negotiateFromHello(ch) }

        case .renegotiate(let rn):
            // 분할/회전 등: 새 창 해상도로 재협상 (dpi/refresh/코덱/maxBound는 핸드셰이크 값 재사용)
            guard rn.hasWidth, rn.hasHeight else { return }
            Task { @MainActor in self.renegotiate(rn) }

        default:
            break   // 이 서비스 것이 아니다 (세션 제어: ping/bye/stsConfirm …)
        }
    }

    /// 통계·복구 루프. 채널이 처음 붙을 때 시작해 기기가 떠날 때까지 돈다.
    private func startStatsLoop() {
        guard statsTask == nil else { return }
        let meter = byteMeter, rate = bitrate, lat = latency
        statsTask = Task { @MainActor [weak self] in
            while !Task.isCancelled, let self {
                try? await Task.sleep(for: .seconds(1))
                if Task.isCancelled { break }
                let bytes = meter.take()
                let snap = rate.snapshot
                let kbps = bytes * 8 / 1000
                self.stats.measuredKbps = kbps
                self.stats.targetKbps = snap.targetKbps
                self.stats.maxKbps = snap.maxKbps
                self.stats.lossPct = snap.lossPct

                let l = lat.snapshot
                self.stats.encMs = l.enc; self.stats.netMs = l.net
                self.stats.jbufMs = l.jbuf; self.stats.decMs = l.dec
                self.stats.rttMs = l.rtt

                // 스파크라인 이력 (최근 60초). 송출 전 0은 담지 않아 그래프가 바닥에서 시작하지 않게.
                if self.stats.active {
                    self.stats.bitrateHistory.append(kbps)
                    if self.stats.bitrateHistory.count > 60 {
                        self.stats.bitrateHistory.removeFirst(self.stats.bitrateHistory.count - 60)
                    }
                }
                await self.recoverDisplayIfNeeded()
            }
        }
    }

    // MARK: - 기계의 지시

    /// `leaseDisplay` — 디스플레이를 확보한다(없으면 생성, 있으면 재사용/resize).
    ///
    /// **파이프라인과 분리한 게 핵심이다.** 디스플레이는 기기의 것이라 경로가 없어도 살아 있어야
    /// 하고(SCK 사이클 중 핸드오버가 겹친 경우), 파이프라인은 보낼 곳이 있을 때만 돈다.
    private func leaseDisplay(_ spec: DisplaySpec) async {
        lastSpec = spec
        // spec.hdr는 **협상 능력**이다. 실제로 HDR 디스플레이를 만들지는 사용자 스위치가 정한다 —
        // 꺼져 있으면 예전과 똑같은 SDR 디스플레이를 만든다(회귀 0).
        guard let l = await DisplayManager.shared.lease(
            fingerprint: fingerprint, deviceName: deviceName,
            width: spec.width, height: spec.height, ppi: spec.ppi,
            refreshRate: spec.refreshRate, hiDPI: spec.hiDPI, maxBound: spec.maxBound,
            hdr: spec.hdr && hdrEnabled
        ) else {
            await dispatch(.pipelineFailed("가상 디스플레이 확보 실패"))
            return
        }
        lease = l
        virtualDisplay = l.display
    }

    /// `buildPipeline` — 처음이면 세우고, 이미 서 있으면 크기만 바꾼다(resize-in-place).
    private func buildOrResize() async {
        guard let np = lastParams else {
            // 아직 협상 전이다(전송 교체 직후). **아무것도 보고하지 않는다** — 그러면 기계는
            // `.building`에 머물고, 곧 도착할 협상이 `specChanged`로 같은 지시를 다시 내린다.
            // 실패로 보고하면 `.down`으로 떨어져 사용자에게 없던 오류가 보인다.
            log.info("협상값 대기 중 — 파이프라인은 협상이 끝나면 선다")
            return
        }
        if pipelineBuilt { await resizePipeline(np) } else { await buildPipeline(np) }
        await dispatch(pipelineBuilt ? .pipelineReady : .pipelineFailed("파이프라인 구성 실패"))
    }

    /// HDR 켜기/끄기 — **전환 UX를 실제로 느껴 보려고 만든 수동 스위치**다.
    ///
    /// 가상 디스플레이는 건드리지 않는다. 재생성하면 배치가 흔들려서 전환 자체의 체감을
    /// 평가할 수 없고, HDR 프레임버퍼를 SDR로 캡처하는 건 ScreenCaptureKit이 해준다.
    /// 그래서 비용은 **캡처+인코더 재시작 한 번**이고, 수신측은 새 SessionParams와
    /// 새 키프레임을 받는다(코덱 파라미터 세트가 바뀌므로 IDR이 반드시 필요).
    func setHDR(_ on: Bool) async {
        guard hdrEnabled != on else { return }
        hdrEnabled = on
        guard let np = lastParams, np.wantsHDR else { return }   // 켤 수 없는 세션이면 상태만 기억
        guard pipelineBuilt, let spec = lastSpec else { return }
        log.notice("HDR 스위치 \(on ? "켬" : "끔") → 디스플레이·파이프라인 재구성")
        // 디스플레이도 다시 잡는다. HDR은 프레임버퍼 속성(16bpc·PQ)이자 생성 시점에만 정해지는
        // 색역(P3) 선언이라, 스트림만 바꿔서는 EDR 헤드룸이 생기지도 없어지지도 않는다.
        await teardownPipeline()
        await leaseDisplay(spec)
        await buildPipeline(np)
    }

    /// SCK 재열거 사이클 — 조정자가 모든 기기에 뿌린다. 기계가 나머지를 정한다.
    func suspendForSCK() async { await dispatch(.suspendForSCK) }
    func resumeAfterSCK() async { await dispatch(.resumeAfterSCK) }

    // MARK: - 협상 (수신측 특성 → 송출 파라미터)
    //
    // **파이프라인을 세우라고 여기서 지시하지 않는다.** 협상은 사실을 하나 만들 뿐이고
    // (`specChanged`), 그걸로 무엇을 할지는 기기 상태 기계가 정한다. 예전엔 세션이 협상 직후
    // 곧바로 빌드했는데, 그러면 인증에 실패했을 때 이미 만든 디스플레이를 도로 부수는 경로가
    // 따로 필요했다.

    /// STS Pass 1의 내용 — 세션이 신원 검증을 마친 뒤 되돌려 준다.
    private func negotiateFromHello(_ ch: PeriStsHelloCore) {
        // 인증서는 더 이상 여기 없다(봉인된 채 Pass 3으로 온다). 대신 `client_name`이
        // 전사에 덮여 **서명된** 값이라, 예전의 평문 주장보다 오히려 믿을 만하다.
        if !ch.clientName.isEmpty { deviceName = ch.clientName }
        var np = ExtendNegotiator.negotiate(from: ch, limits: limits)
        // AOA는 혼잡·피드백이 없어 적응 컨트롤러가 램프를 못 돈다 → 처음부터 상한.
        if reliableTransport { np = np.startingAtCeiling() }
        // 재협상이 재사용할 고정 특성 (분할/회전은 w/h만 바뀐다).
        clientCaps.set(dpi: ch.hasDisplay && ch.display.densityDpi > 0 ? Int(ch.display.densityDpi) : 160,
                       refresh: ch.hasDisplay && ch.display.refreshRate > 0 ? Double(ch.display.refreshRate) : 60.0,
                       h265: ch.supportedCodecs.contains(.h265),
                       maxBound: np.maxBound,
                       ranges: ch.hasDisplay ? ch.display.supportedDynamicRanges : [],
                       decoders: ch.decoders)
        log.info("client caps — \(ExtendNegotiator.capsDescription(ch))")
        adopt(np, why: "handshake")
    }

    /// Renegotiate — 분할·회전 등으로 창 크기가 바뀌었다. 능력은 그대로이므로 w/h만 새로 넣는다.
    private func renegotiate(_ rn: PeriRenegotiate) {
        let snap = clientCaps.snapshot
        guard !clientCaps.isEmpty else {
            log.error("재협상이 ClientHello보다 먼저 도착 — 무시")
            return
        }
        var np = ExtendNegotiator.negotiate(
            widthPx: Int(rn.width), heightPx: Int(rn.height),
            densityDpi: snap.dpi, refreshRate: snap.refresh,
            orientation: rn.hasOrientation ? rn.orientation : .unspecified,
            supportsH265: snap.h265, clientRanges: snap.ranges, decoders: snap.decoders,
            maxBound: snap.maxBound, limits: limits)
        if reliableTransport { np = np.startingAtCeiling() }
        adopt(np, why: "renegotiate")
    }

    /// 협상값을 채택한다 — 클라에 통지하고, 기기 기계에는 **디스플레이 제원만** 사실로 올린다.
    private func adopt(_ np: NegotiatedParams, why: String) {
        lastParams = np
        sendSessionParams(np)
        log.info("\(why) → \(np.width)x\(np.height) codec=\(np.codec == .h265 ? "H265" : "H264") fps=\(np.fps) \(np.wantsHDR ? "HDR10" : "SDR")")
        Task { await self.dispatch(.specChanged(np.displaySpec)) }
    }

    // MARK: - 재생 정책

    /// 사용자가 슬라이더로 재생 버퍼를 바꿨다 — 이후로는 전송 기본값이 이 값을 덮지 않는다.
    func setPlayoutBuffer(_ ms: Double) {
        playoutBufferPinned = true
        playoutBufferMs = ms
        applyPlayoutBuffer()
    }

    /// 재생 버퍼(Android 고정 지터버퍼) 깊이 적용: 컨트롤러 예산 갱신 + Android로 PlayoutConfig 전송.
    /// 채널이 없으면 조용히 버린다(파이프라인이 다시 서면 재전송된다).
    func applyPlayoutBuffer() {
        let ms = Int(playoutBufferMs.rounded())
        bitrate.setBudget(bufferMs: ms)
        var pc = PeriPlayoutConfig()
        pc.bufferMs = UInt32(ms)
        pc.renderOnArrival = (streamProfile == .lowLatency)   // 저지연=버퍼 생략(즉시 렌더)
        var m = PeriControlMessage(); m.playoutConfig = pc
        channel?.sendReliable(m)
    }

    /// UI 프로파일 전환 (저지연 ↔ 고화질). governor·지터버퍼를 함께 설정하고 Android에 동기.
    func setProfile(_ p: StreamProfile) {
        streamProfile = p
        applyProfileLocal()
        applyPlayoutBuffer()
    }

    /// 프로파일에 따른 Mac 로컬 설정: 적응형 fps governor 활성/고정.
    private func applyProfileLocal() {
        switch streamProfile {
        case .lowLatency:
            fpsGovernor.setEnabled(true)                      // 적응형 fps 켬
        case .highQuality:
            fpsGovernor.setEnabled(false)                     // 배율 1.0 고정
            if let fps = lastParams?.fps {                    // 낮춰뒀으면 협상 상한으로 복귀
                capturer.updateFrameRate(fps)
                encoder?.setExpectedFrameRate(fps)
            }
        }
    }

    // MARK: - 디스플레이 오염 복구

    /// 가상 디스플레이가 정상 확장 상태에서 벗어나면(미러 고착 / 사용자가 "윈도우 또는 앱"
    /// 모드를 골라 오프라인됨) 자동 복구한다. 1초 통계 루프에서 폴링.
    ///  - 오프라인(presentation/창모드): 데스크톱 배치에서 빠진 것. 같은 신원으로 재생성하면
    ///    macOS가 저장된 오염 상태(offline)를 복원해 무한반복되므로, **신원(serial)을 회전**시켜
    ///    신선한 신원으로 재생성 → 오염 탈출(임시방편). 한도 초과 시 중단.
    ///  - 미러: 온라인이지만 미러 → in-place 확장 강제(재생성 불필요).
    private func recoverDisplayIfNeeded() async {
        guard pipelineBuilt, !recoveryGivenUp, let vd = virtualDisplay, vd.displayID != 0 else { return }

        let online = vd.isOnline()
        if online && !vd.isMirrored() {
            offlineRecoveries = 0   // 안정 상태 → 카운터 리셋
            if detail != nil { detail = nil }
            return
        }
        guard Date().timeIntervalSince(lastRecoveryAt) > 3 else { return }  // 쿨다운
        lastRecoveryAt = Date()

        if !online {
            offlineRecoveries += 1
            guard offlineRecoveries <= Self.maxOfflineRecoveries else {
                recoveryGivenUp = true
                log.error("디스플레이 오프라인 복구 \(Self.maxOfflineRecoveries)회 실패 → 중단")
                detail = "디스플레이 복구 실패 — 재연결하거나 시스템 설정에서 디스플레이를 확장으로 바꿔주세요"
                return
            }
            log.warning("가상 디스플레이 오프라인(presentation/창모드?) → 신원 회전 후 재생성 (시도 \(self.offlineRecoveries)/\(Self.maxOfflineRecoveries))")
            detail = "디스플레이 복구 중 (확장 모드로 되돌림 \(offlineRecoveries)/\(Self.maxOfflineRecoveries))"
            guard let p = lastParams else { return }
            let fp = fingerprint
            // 순서 중요: 캡처를 먼저 멈추고 나서 파괴한다. 반대로 하면 살아 있는 SCStream이
            // 붙든 displayID가 파괴되고, 그 번호가 다른 기기 디스플레이에 재배정된다.
            await teardownPipeline()
            DisplaySerialStore.rotate(fp)                    // 이 기기 신원만 회전 → 오염 배치 탈출
            DisplayManager.shared.discard(fingerprint: fp)   // 오염 디스플레이 파괴
            await buildPipeline(p)                           // lease가 회전된 신원으로 새로 생성
        } else {   // 온라인인데 미러
            let ok = vd.forceExtended()
            log.warning("가상 디스플레이 미러 감지 → 확장 강제: \(ok ? "성공" : "실패")")
        }
    }

    /// 가상 디스플레이 결과(논리해상도/백킹스케일)와 협상값으로 정적 통계 갱신.
    private func refreshDisplayStats(_ np: NegotiatedParams) {
        let vid = virtualDisplay?.displayID ?? 0
        let b = CGDisplayBounds(vid)
        stats.active = true
        stats.codec = (np.codec == .h265) ? "H.265" : "H.264"
        stats.width = np.width; stats.height = np.height
        stats.logicalW = Int(b.width); stats.logicalH = Int(b.height)
        stats.backingScale = b.width > 0 ? Double(np.width) / Double(b.width) : 1
        stats.fps = np.fps
        stats.targetKbps = np.bitrateKbps
        stats.maxKbps = np.maxBitrateKbps
        stats.displayID = vid
    }

    /// 링크 설명 문구는 **세션이 안다**(어느 경로를 타는지는 세션의 사정이다) → 받아 적기만 한다.
    func setLinkDetail(_ s: String) { stats.linkDetail = s }

    // MARK: - 파이프라인 구성

    private func buildPipeline(_ npIn: NegotiatedParams) async {
        guard !pipelineBuilt else { return }
        pipelineBuilt = true
        // lastParams는 **협상 능력**이다 — HDR이 꺼지거나 강등돼도 여기는 그대로 둔다.
        // 여기까지 SDR로 덮으면 hdrAvailable이 false가 되어 토글로 되켤 수 없다.
        lastParams = npIn
        var np = npIn
        do {
            // 디스플레이는 **이미 빌려져 있다** — 기계가 `leaseDisplay`를 먼저 지시하기 때문이다.
            // 예전엔 여기서 직접 빌렸는데, 그러면 "파이프라인을 세운다 = 디스플레이를 잡는다"가 돼
            // 파이프라인이 내려갈 때마다 디스플레이 수명이 흔들렸다.
            guard let lease else { throw ExtendError.virtualDisplayFailed }
            let vd = lease.display
            let vdid = lease.displayID
            let serial = lease.serial
            virtualDisplay = vd

            // 캡처를 세우기 **전에** HDR 성립 여부를 확정한다 — 캡처 포맷·인코더 프로파일이
            // 모두 이 판정에 달려 있어서, 나중에 뒤집으면 파이프라인을 통째로 다시 세워야 한다.
            np = confirmDynamicRange(np, on: vd)

            try await capturer.start(displayID: vdid,
                                     pixelWidth: np.width, pixelHeight: np.height, maxFPS: np.fps,
                                     hdr: np.wantsHDR)

            // HiDPI면 네이티브 backing(np.width)의 2x 모드를 활성화 → 현미경 UI(1x)를 Retina로.
            if np.hiDPI {
                let activated = vd.activatePreferredMode()
                log.info("activatePreferredMode: \(activated ? "2x 모드 활성화됨" : "적합한 2x 모드 없음(1x 유지)")")
            }

            // 검증: macOS가 실제로 만든 가상 디스플레이의 논리 해상도(=백킹스케일) 확인.
            // 생성 직후엔 디스플레이가 아직 등록 전(0x0/garbage)이라, 캡처 시작까지 기다린 뒤 읽는다.
            let vid = vd.displayID
            // 신규 등록 시 미러로 들어오는 경우가 있음 → 확장으로 강제 (등록 완료된 지금이 올바른 타이밍).
            if vd.isMirrored() {
                let ok = vd.forceExtended()
                log.warning("virtual display가 미러로 등록됨 → 확장 강제: \(ok ? "성공" : "실패")")
            }
            let b = CGDisplayBounds(vid)
            let mirrors = CGDisplayMirrorsDisplay(vid)
            let scale = b.width > 0 ? Double(np.width) / Double(b.width) : 0
            log.info("virtual display result: 논리 \(Int(b.width))x\(Int(b.height))pt, mode \(np.width)x\(np.height)px → backingScale≈\(String(format: "%.2f", scale)) | mirror=\(mirrors == 0 ? "OFF(확장)" : "ON(미러 of \(mirrors))") (dpi=\(Int(np.ppi)) hiDPI=\(np.hiDPI))")

            // 2x를 못 얻었을 때만 진단용 모드 목록 덤프 (정상 동작 시엔 로그 깔끔).
            if np.hiDPI && scale < 1.5 {
                let opts = [kCGDisplayShowDuplicateLowResolutionModes: kCFBooleanTrue!] as CFDictionary
                if let modes = CGDisplayCopyAllDisplayModes(vid, opts) as? [CGDisplayMode] {
                    let listed = modes.map {
                        let lw = $0.width, pw = $0.pixelWidth
                        return "\(lw)x\($0.height)pt/\(pw)x\($0.pixelHeight)px(\(lw > 0 ? pw / lw : 0)x)"
                    }.joined(separator: ", ")
                    log.warning("HiDPI 2x 실패, available modes(\(modes.count)): \(listed)")
                }
            }

            try installEncoder(np)   // 인코더 생성/배선/시작 (build·resize 공용)
            refreshDisplayStats(np)
            appliedParams = np       // 여기까지 와야 "실제로 이 크기로 돈다"
            detail = nil             // 구성에 성공했다 → 이전 실패 문구를 지운다
            let codecName = (np.codec == .h265) ? "H.265" : "H.264"
            let range = np.wantsHDR ? "HDR10" : "SDR"
            log.notice("pipeline built \(np.width)x\(np.height) codec=\(codecName) \(range) fps=\(np.fps) dpi=\(Int(np.ppi)) hiDPI=\(np.hiDPI) | display=\(vdid) serial=\(serial) 기기=\(self.deviceName)")
        } catch ScreenCapturer.CaptureError.superseded {
            // 시작 도중 세션이 내려갔거나 더 새로운 시작이 인계 — 오류가 아니라 정상 취소.
            pipelineBuilt = false
            log.info("buildPipeline 취소됨 (캡처 세대 교체)")
        } catch {
            pipelineBuilt = false
            detail = "구성 실패: \(error.localizedDescription)"
            log.error("buildPipeline failed: \(error.localizedDescription)")
        }
    }

    /// 인코더 생성 → 데이터플레인 배선 → 시작 (build와 resize-in-place 공용).
    /// capturer가 이미 시작된 상태여야 함 (capturer.width/height 사용). 기존 인코더는 교체.
    /// 확정된 영상 파라미터를 SessionParams(암호화)로 통지한다 (핸드셰이크·재협상·HDR 강등 공용).
    ///
    /// 세션이 아니라 여기 있는 이유: 무엇을 어떤 코덱·비트심도로 보내는지는 **이 서비스가 정한다.**
    /// HDR처럼 파이프라인을 세워 봐야 아는 것도 여기서만 확정된다.
    private func sendSessionParams(_ np: NegotiatedParams, mastering: HDRMasteringInfo? = nil) {
        var sp = PeriSessionParams()
        if np.wantsHDR, let m = mastering { sp.hdrStaticInfo = m.cta8613StaticMetadata }
        sp.codec = np.codec
        sp.width = UInt32(np.width); sp.height = UInt32(np.height); sp.fps = UInt32(np.fps)
        sp.initialBitrateKbps = UInt32(np.bitrateKbps)
        sp.orientation = np.orientation
        sp.keyframeIntervalMs = 0   // 주기 IDR 없음 — 손실 시 요청 기반 LTR 복구(접속 시 IDR 1회)
        sp.dynamicRange = np.dynamicRange
        var spMsg = PeriControlMessage(); spMsg.sessionParams = sp
        channel?.sendReliable(spMsg)
    }

    /// 협상된 HDR이 **실제로 성립하는지** 판정하고, 안 되면 SDR로 강등한 파라미터를 돌려준다.
    ///
    /// 판정 근거는 EDR 헤드룸 하나뿐이다. `transferFunction=1` 선언이 먹었는지는 macOS가 따로
    /// 알려주지 않고, 여기서 잘못 믿으면 SDR 소스를 10비트 PQ로 인코딩해 폰에서 잿빛으로 뜬다.
    /// 강등하면 클라에도 다시 알린다 — 클라는 SessionParams만 믿고 디코더를 세우기 때문이다.
    private func confirmDynamicRange(_ np: NegotiatedParams, on vd: PeriVirtualDisplay) -> NegotiatedParams {
        mastering = nil
        hdrActive = false
        guard np.wantsHDR else { return np }

        // 사용자가 껐으면 SDR로 간다. 디스플레이도 이미 SDR로 만들어져 있다
        // (leaseDisplay가 `spec.hdr && hdrEnabled`로 빌린다) — 여기서 스트림만 맞춰 준다.
        guard hdrEnabled else {
            log.notice("HDR 사용자 설정으로 꺼짐 → SDR 송출")
            let sdr = np.downgradedToSDR()
            sendSessionParams(sdr)
            return sdr
        }

        let headroom = Double(vd.potentialHeadroom())
        guard headroom > 1.0 else {
            log.warning("HDR 협상됐으나 가상 디스플레이가 헤드룸을 못 받음 → SDR 강등. \(vd.hdrDiagnostics())")
            let sdr = np.downgradedToSDR()
            sendSessionParams(sdr)
            return sdr
        }

        // 마스터링 조건은 **디스플레이를 만들어 봐야 안다**(헤드룸이 근거다) → 핸드셰이크 때
        // 보낸 SessionParams엔 못 실렸다. 여기서 메타데이터를 채워 다시 보낸다.
        // 첫 키프레임보다 먼저 나가므로 클라는 디코더 설정 전에 받는다.
        let m = HDRMasteringInfo(edrHeadroom: headroom)
        mastering = m
        hdrActive = true
        log.notice("HDR 성립 — \(vd.hdrDiagnostics()) → 피크 \(Int(m.peakNits))nit (헤드룸 \(String(format: "%.1f", headroom))× \(Int(HDRMasteringInfo.sdrReferenceWhiteNits))nit)")
        sendSessionParams(np, mastering: m)
        return np
    }

    private func installEncoder(_ np: NegotiatedParams) throws {
        // 채널 없이 인코더를 세우면 아무 데도 못 가는 프레임을 만든다. 기계가 파이프라인을
        // 지시하는 시점엔 언제나 채널이 있지만(`sessionLive` → `.live(channel)`), 그 불변식이
        // 깨지면 조용히 헛도는 대신 여기서 실패해야 한다.
        guard let ch = channel else { throw ExtendError.noChannel }
        let frameCounter = self.frameCounter
        let ltrMap = self.ltrMap
        let meter = byteMeter
        let capturer = self.capturer        // 적응형 fps governor가 캡처 rate 라이브 조절
        let governor = fpsGovernor
        let lat = latency                   // 지연 스택의 인코딩 구간 기록 (표시용)
        let ceilFps = np.fps                // 이 세션의 상한(협상값) — 전역 배율을 여기에 곱한다
        // 페이싱 윈도우 ≈ 프레임간격 × 0.6 (마이크로초). 이 시간에 걸쳐 한 프레임 datagram을 분산.
        let paceWindowMicros = UInt32(max(1, 1000 / max(1, np.fps))) * 600
        let codec = np.codec

        let enc = VideoEncoder(width: capturer.width, height: capturer.height,
                               codec: np.codec, hdr: np.wantsHDR, mastering: mastering)
        enc.onEncoded = { [weak enc] frame in
            let sendUs = hostMicros()
            // 적응형 fps: macEnc(캡처 PTS→인코드 완료) EWMA 폭증 = 인코더 포화 → 배율을 낮춰
            // *모든 세션*이 함께 물러난다(공유 HW 인코더). 배율 × 이 세션의 협상 fps = 목표 fps.
            if frame.ptsHostUs > 0, sendUs > frame.ptsHostUs {
                let encMs = Double(sendUs - frame.ptsHostUs) / 1000.0
                lat.observeEnc(ms: encMs)   // 지연 스택의 인코딩 구간 (표시용)
                if let newScale = governor.observe(encMs: encMs) {
                    let newFps = max(30, min(ceilFps, Int((Double(ceilFps) * newScale).rounded())))
                    capturer.updateFrameRate(newFps)
                    enc?.setExpectedFrameRate(newFps)
                    log.info("fps govern → \(newFps)fps (scale \(String(format: "%.2f", newScale)))")
                }
            }
            // 영상 게이트: 최신성 경로가 열려 있어야 인코딩분을 흘려보낸다.
            guard ch.isDatagramPathReady else { return }
            meter.add(frame.data.count)   // 측정 비트레이트용 바이트 누적
            let frameId = frameCounter.next()
            // 지연 계측 임베드: [ptsHostUs 8B BE][macSendUs 8B BE] + 비트스트림 (씰 안, 수신측이 스트립)
            var stamped = Data(capacity: 16 + frame.data.count)
            var ptsBE = frame.ptsHostUs.bigEndian
            var sendBE = sendUs.bigEndian
            withUnsafeBytes(of: &ptsBE) { stamped.append(contentsOf: $0) }
            withUnsafeBytes(of: &sendBE) { stamped.append(contentsOf: $0) }
            stamped.append(frame.data)
            let isLtr = frame.ltrToken != nil
            if let token = frame.ltrToken { ltrMap.put(frameId, token) }
            let meta = MediaMeta(frameId: frameId, timestampMs: frame.ptsMs, codec: codec,
                                 keyframe: frame.keyframe, isLtr: isLtr)
            let frags = ch.sendDatagram(stamped, meta, pacingWindowMicros: paceWindowMicros)
            if frags > 0, frame.keyframe || isLtr {
                log.info("video sent frame=\(frameId) key=\(frame.keyframe) ltr=\(isLtr) frags=\(frags) bytes=\(frame.data.count) \(np.width)x\(np.height)")
            }
        }
        // 주기 IDR 2초. 한때 0(off)으로 두고 LTR 복구에만 맡겼는데, 복구를 유발하는 신호가
        // Android의 packet_seq 갭 감지 하나뿐이라 구멍이 있었다: 패킷은 다 왔는데 참조 프레임이
        // 오염된 경우(디코더 오류, LTR 상태 꼬임)는 갭이 없어 감지되지 않고, 그러면 다음 IDR까지
        // 최대 10분(안전망 간격) 동안 깨진 화면이 고착된다. 주기 IDR은 그 최악 지연을 2초로 묶는다.
        try enc.start(bitrateKbps: np.bitrateKbps, fps: np.fps, keyframeIntervalSec: 2)
        // 새 파이프라인은 현재 전역 배율을 즉시 반영 (이미 포화 상태면 상한부터 시작하지 않도록).
        let startFps = max(30, min(np.fps, Int((Double(np.fps) * fpsGovernor.currentScale).rounded())))
        if startFps != np.fps { capturer.updateFrameRate(startFps); enc.setExpectedFrameRate(startFps) }
        // 지연 인식형 적응 기준. delayGuard: AOA는 손실 신호가 없어 큐잉지연으로만 포화 감속.
        bitrate.configure(initialKbps: np.bitrateKbps, ceilingKbps: np.maxBitrateKbps,
                          delayGuard: reliableTransport)
        encoder?.stop()                                  // resize 시 기존 인코더 정리
        encoder = enc
        encoderBox.encoder = enc
        capturer.onFrame = { [weak enc] captured in enc?.encode(captured.sampleBuffer) }
        // 영상 피어가 이미 있으면(재협상 등) 즉시 IDR (새 해상도 키프레임).
        if ch.isDatagramPathReady { enc.requestKeyframe() }
        streamProfile = ProfileStore.shared.profile   // 새 세션은 현재 전역 프로파일 채택
        applyProfileLocal()    // governor 활성/고정 (프로파일별)
        applyPlayoutBuffer()   // 컨트롤러 예산 + PlayoutConfig(buffer + render_on_arrival) 동기
    }

    /// 캡처/인코더만 내린다. 디스플레이 destroy는 DisplayManager.discard(오염 복구)만 한다 —
    /// 여기서 부수면 ID 재사용·창 이주·정체성 중복이 재발한다.
    private func teardownPipeline() async {
        capturer.onFrame = nil
        await capturer.stop()
        encoder?.stop()
        encoder = nil
        encoderBox.encoder = nil
        virtualDisplay = nil   // 빌린 참조만 해제
        pipelineBuilt = false
        appliedParams = nil    // 도는 게 없으면 적용된 크기도 없다
        stats.active = false
    }

    /// 회전·분할 등 재협상: 디스플레이 인스턴스를 유지하고 모드만 교체(resize-in-place) +
    /// 인코더만 재생성. 실제 끊김이 아니므로 displayID/UDP 채널/암호화 세션을 그대로 둔다.
    private func resizePipeline(_ npIn: NegotiatedParams) async {
        // 비교 대상은 **지금 돌고 있는 값**이다. 협상값(lastParams)은 이미 np로 갱신돼 있어
        // 기준으로 쓸 수 없다 — 그게 회전이 먹히지 않던 이유다.
        //
        // 왜 w/h/codec 셋이면 충분한가: 같은 전송에서 fps는 주사율·디코더 상한이 정하고(둘 다
        // 고정), 비트레이트는 w×h×fps에서 나온다 — 앞의 셋이 같으면 나머지도 같다. 전송이
        // 갈려 상한 자체가 바뀌는 경우는 여기 오지 않는다(`attach`가 협상값을 비운다).
        if let p = appliedParams, p.width == npIn.width, p.height == npIn.height, p.codec == npIn.codec {
            return  // 실제로 변화 없음
        }
        guard pipelineBuilt, let vd = virtualDisplay else {
            await buildPipeline(npIn); return   // 아직 구성 전이면 그냥 빌드 (협상값 그대로 넘긴다)
        }
        // 재협상은 협상 능력을 다시 실어 오므로(회전 등으로 w/h만 바뀐 값), HDR 판정을
        // 여기서도 통과시켜 사용자 스위치·실측 헤드룸을 반영한다. 위 조기반환 뒤에 두는 게
        // 중요하다 — 변화가 없을 때까지 SessionParams를 다시 쏘면 안 된다.
        let np = confirmDynamicRange(npIn, on: vd)
        log.info("renegotiate → resize-in-place \(np.width)x\(np.height)")

        // 1) 디스플레이 유지, 모드만 교체 (displayID 동일)
        //
        // ⚠️ 배치 틈은 여기서 발생한다 — 모드를 교체하면 macOS가 그 모드에 저장해 둔 origin을
        // 새 논리 크기에 복원해 본 디스플레이와 어긋난다(`displays have a gap` →
        // `Display state is invalid` → 창 위치 어긋남). **아직 미해결이다.** 시도해서 막힌 것들:
        //
        //  · `CGVirtualDisplaySettings.rotation` — API는 실재하고 `applySettings`도 성공을
        //    반환하는데 macOS가 값을 무시한다(rotation=90 후에도 CGDisplayBounds가 세로 그대로).
        //  · 생성 시 양방향 모드를 모두 선언 — macOS는 현재 프레임버퍼 방향의 모드만 노출한다
        //    (가로일 때 21개 전부 가로 비율, 세로 모드 0개).
        //  · `CGConfigureDisplayOrigin`으로 직접 밀착 — 성공을 반환하고 값이 안 먹는다.
        //    무제한 재시도하면 재구성 폭주가 되어 원래 문제보다 나빠진다.
        guard vd.resize(toWidth: UInt32(np.width), height: UInt32(np.height),
                        ppi: np.ppi, refreshRate: np.refreshRate, hiDPI: np.hiDPI) else {
            log.error("resize applySettings 실패 → 전체 재구성 폴백")
            await teardownPipeline(); await buildPipeline(np); return
        }
        // 2) 같은 displayID에서 캡처를 새 크기로 재시작
        capturer.onFrame = nil
        await capturer.stop()
        do {
            // 이미 등록된 디스플레이지만 정체성 확인을 거쳐 잘못된 대상 캡처를 원천 차단.
            let vdid = vd.confirmedDisplayID()
            guard vdid != 0 else { throw ExtendError.virtualDisplayFailed }
            try await capturer.start(displayID: vdid,
                                     pixelWidth: np.width, pixelHeight: np.height, maxFPS: np.fps,
                                     hdr: np.wantsHDR)
            if np.hiDPI {
                let activated = vd.activatePreferredMode()
                log.info("activatePreferredMode(resize): \(activated ? "2x 모드 활성화됨" : "적합한 2x 모드 없음(1x 유지)")")
            }
            try installEncoder(np)            // 3) 인코더만 재생성 (VTCompressionSession 크기 고정)
            refreshDisplayStats(np)
            // build 때와 같은 검증을 resize에서도 찍는다 — 없으면 회전 후 배율이 1x로 떨어져도
            // 로그만 봐서는 알 수 없다(실제로 그 상태를 눈으로만 보고 있었다).
            let rb = CGDisplayBounds(vd.displayID)
            let rscale = rb.width > 0 ? Double(np.width) / Double(rb.width) : 0
            log.info("resize result: 논리 \(Int(rb.width))x\(Int(rb.height))pt, mode \(np.width)x\(np.height)px → backingScale≈\(String(format: "%.2f", rscale)) | origin=(\(Int(rb.origin.x)),\(Int(rb.origin.y)))")
            appliedParams = np
            detail = nil
            log.info("resize-in-place done \(np.width)x\(np.height)")
        } catch ScreenCapturer.CaptureError.superseded {
            // 정상 취소 — 전체 재구성 폴백을 돌리면 오히려 방금 인계한 쪽과 충돌한다.
            log.info("resize 취소됨 (캡처 세대 교체)")
        } catch {
            log.error("resize capture/encode 실패 → 전체 재구성 폴백: \(error.localizedDescription)")
            // 폴백엔 **협상값**을 넘긴다 — 강등된 값을 넘기면 lastParams가 SDR로 굳어
            // 토글로 되켤 수 없게 된다.
            await teardownPipeline(); await buildPipeline(npIn)
        }
    }
}

/// extend 서비스의 **수신 경로**. 프레임 라우터(LAN/AOA 큐)가 동기로 부른다.
///
/// 왜 서비스 본체와 갈라 뒀나: 서비스는 `@MainActor`(디스플레이·캡처·UI가 거기 있다)인데
/// 제어 메시지는 전송 큐에서 프레임 단위로 도착한다. 매번 MainActor를 거치면 홉이 쌓이고,
/// 특히 `TransportFeedback`은 비트레이트를 즉시 되먹여야 해서 지연이 곧 진동이 된다.
/// 여기 담긴 것은 전부 락 보호 박스라 어느 스레드에서 만져도 안전하다.
nonisolated struct ExtendInbox: Sendable {
    let encoderBox: EncoderBox
    let ltrMap: LtrMap
    let bitrate: BitrateController
    let latency: LatencyBox

    /// 손실 복구: 작은 LTR-P (ACK된 LTR이 없으면 IDR 폴백).
    func onKeyframeRequest() { encoderBox.encoder?.forceLTRRefresh() }

    func onLtrAck(frameId: UInt32) {
        if let token = ltrMap.take(frameId) { encoderBox.encoder?.acknowledgeLTR([token]) }
    }

    func onFeedback(loss: Double, queueDelayMs: Int) {
        if let kbps = bitrate.onFeedback(loss: loss, queueDelayMs: queueDelayMs) {
            encoderBox.encoder?.setBitrate(kbps)
        }
    }

    func onRemoteLatency(net: Int, jbuf: Int, dec: Int, rtt: Int) {
        latency.setRemote(net: net, jbuf: jbuf, dec: dec, rtt: rtt)
    }

    /// UDP 피어가 붙었다(또는 재협상) → 즉시 키프레임.
    func requestKeyframeNow() { encoderBox.encoder?.requestKeyframe() }
}
