//
//  PairingCoordinator.swift
//  Peri Display
//
//  BLE 페어링 — Mac = 개시자/central. **상태는 여기 없다.**
//
//  이 파일은 [PairingMachine]의 **실행기**다. 단계 판단·순서 제약·마감은 전부 순수 리듀서에
//  있고, 여기 남은 건 세 가지뿐이다:
//
//   1. 프레임을 파싱해 **사실**로 바꿔 기계에 넣는다(`dispatch`).
//   2. 기계가 지시한 부작용을 실행한다(`run`).
//   3. 부작용에 필요한 재료(KEM·PAKE·세션·인증서·후보)를 들고 있는다.
//
//  프로토콜은 [PeriBleHandshake] 주석 참조. 요약하면:
//
//    Mac → HELLO(논스, ML-KEM 공개키) → 기기가 PIN을 띄우고 OFFER(논스, 암호문, 파라미터)
//    → 사람이 PIN을 옮겨 적음 → PAKE1/2/3 → 채널 봉인 → 신원 교환 → 후보 교환
//
//  v1과의 차이: COMMIT·SAS·OTP·재인증 서명이 전부 없다. 그 넷은 "사람을 부를까, 어떻게
//  부를까"를 양쪽이 합의하기 위한 장치였는데, v2는 **모든 라운드가 PIN을 거치므로**
//  합의할 것이 없다. 그리고 옛 SAS는 키 교환을 묶지 않아 능동 중간자에 뚫렸다.
//

import Foundation
import Combine
import CryptoKit
import OSLog

private let log = Logger(subsystem: "com.peri.display.mac", category: "pairing")

@MainActor
final class PairingCoordinator: ObservableObject {

    // MARK: 기계가 계산하는 것 (UI가 읽는 유일한 출처)

    @Published private(set) var phase: PairPhase = .idle
    @Published private(set) var status = "대기"
    @Published private(set) var peers: [DiscoveredPeer] = []
    @Published private(set) var pairedName: String?

    /// 사람이 PIN을 입력해야 하는가. UI가 입력칸을 띄울지의 유일한 기준.
    @Published private(set) var needsPin = false

    /// 연결 후보 제공자(AppCore가 주입). 인증 완료 시 호출해 후보 묶음을 BLE로 전달한다.
    ///
    /// 왜 클로저인가: STUN은 **실제 미디어를 나를 소켓에서** 나가야 매핑이 유효한데 그 소켓은
    /// SessionManager가 소유한다. 여기(시그널링)는 소켓을 몰라야 하므로 수집을 위임한다.
    var candidateProvider: (() async -> CandidateBundle)?

    /// 상대(Android) 후보를 받았을 때 — 펀칭 개시. SessionManager가 받아 IceAgent를 돌린다.
    var onRemoteCandidates: ((CandidateBundle) -> Void)?

    /// 호출 응답 광고 → 지문. 판정은 부르는 쪽(pager)이 하고, 우리는 배선만 한다.
    var pageResponseResolver: ((Data) -> String?)? {
        get { ble.pageResponseResolver }
        set { ble.pageResponseResolver = newValue }
    }

    // MARK: 실행기가 들고 있는 재료

    private let ble = BleCentral()
    private var cancellables = Set<AnyCancellable>()

    private var machine = PairState()
    private var deadlineTask: Task<Void, Never>?
    /// `connect` 부작용이 붙을 대상 — UI가 고른 기기.
    private var pendingPeer: DiscoveredPeer?

    /// 이번 라운드의 ML-KEM 키쌍. Mac이 먼저 말하므로 **우리가 키쌍을 만든다**(복호하는 쪽).
    private var kem: PeriBleHandshake.Kem?
    private var macNonce = Data()
    private var deviceNonce = Data()
    private var kemSecret = Data()
    private var transcript = Data()

    private var spake: SpakeClient?
    /// step3이 낸 마지막 조각. `sendProofAndIdentity`가 꺼내 쓴다.
    private var pendingProof: Data?

    // ── 재연결(STS) 경로 ──────────────────────────────────────────────────
    //
    // 링크 키를 가진 상대에 걸 때만 채워진다. 이 경로에는 PIN도 인증서도 없다.

    /// 우리가 부르고 있는 상대. **이 한 사람의 공개키로만 서명을 검증한다** — 누구를 불렀는지
    /// 알면서 "아무 신뢰된 기기나 응답하면 통과"로 두면 A에게 걸었는데 B가 받아도 통과한다.
    private var stsPeer: TrustedPeer?
    private var stsLinkKey: Data?
    /// 전사에 들어갈 **선에 나간 그대로의** 바이트들.
    private var helloBytes = Data()
    private var stsHelloBytes = Data()
    private var stsTranscript = Data()
    private var stsAuth: SymmetricKey?
    private var pendingStsResponse: Data?

    private var session: PeriCryptoSession?
    /// 이 라운드가 남길 링크 키. 상대 신원이 도착하면 그 옆에 저장된다(`pinPeer`).
    private var linkKey: Data?
    private var peerCert: PeriCert?
    /// 이번 세션의 공유 펀치 토큰. Android가 그대로 되돌려줘야 그 후보를 받아들인다.
    private var punchToken: Data?
    /// 개시 신호와 함께 넘길 상대 후보.
    private var pendingRemote: CandidateBundle?

    private let deviceName = Host.current().localizedName ?? "Mac"

    init() {
        ble.$peers.assign(to: &$peers)
        // 연결 전 단계에서만 BLE 저수준 상태를 노출한다. 그 뒤로는 기계의 요약이 이긴다 —
        // 예전엔 두 출처가 같은 `status`를 놓고 다퉜다.
        ble.$status.sink { [weak self] s in
            guard let self else { return }
            switch self.machine.phase {
            case .idle, .scanning, .linking: self.status = s
            default: break
            }
        }.store(in: &cancellables)
        ble.onReady = { [weak self] in self?.dispatch(.linkReady) }
        ble.onFrame = { [weak self] f in self?.handle(f) }
        ble.onDisconnect = { [weak self] in self?.onBleDisconnect() }
        ble.onIceRestartRequested = { [weak self] peer in self?.onIceRestartRequested(peer) }
    }

    // MARK: - 기계 구동

    /// 사건 → 리듀서 → 상태 반영 → 부작용 실행.
    ///
    /// 기기 등록부(`SessionManager.dispatch`)와 같은 큐 방식으로 직렬화한다 — 부작용 실행
    /// 중에 또 dispatch가 일어난다(예: `disconnect` → `linkLost`).
    private var evQueue: [PairEv] = []
    private var draining = false

    private func dispatch(_ ev: PairEv) {
        evQueue.append(ev)
        guard !draining else { return }
        draining = true
        Task { @MainActor in
            defer { self.draining = false }
            while !self.evQueue.isEmpty {
                let e = self.evQueue.removeFirst()
                let before = self.machine
                let step = reduce(before, e)
                self.machine = step.state
                if before != step.state {
                    log.info("[pair] \(String(describing: e)) → \(String(describing: step.state.phase))")
                }
                self.publish()
                for fx in step.fx { await self.run(fx) }
            }
            self.publish()
        }
    }

    /// 기계 상태를 UI로 흘린다 — **여기 말고는 대입하는 곳이 없다.**
    private func publish() {
        if phase != machine.phase { phase = machine.phase }
        if needsPin != machine.needsPin { needsPin = machine.needsPin }
        // BLE 저수준 문구가 이기는 구간(연결 전)만 예외.
        switch machine.phase {
        case .idle, .scanning, .linking: break
        default: if status != machine.summary { status = machine.summary }
        }
    }

    private func run(_ fx: PairFx) async {
        switch fx {
        case .startScan: ble.startScan()
        case .stopScan: ble.stopScan()
        case .connect:
            guard let peer = pendingPeer else {
                dispatch(.failed("연결할 기기가 지정되지 않았습니다")); return
            }
            ble.connect(peer)
        case .disconnect: ble.disconnect()

        case .sendHello(let sts):
            macNonce = PeriCryptoSession.randomNonce32()
            var body = Data()
            if sts {
                guard let link = stsLinkKey else {
                    dispatch(.failed("링크 키가 없습니다")); return
                }
                // 호출 광고와 **같은 구성**의 TOTP다(유도 라벨과 소금만 다르다).
                body = PeriBleSts.hint(linkKey: link, nonce: macNonce)
            } else {
                do {
                    let k = try PeriBleHandshake.Kem()
                    kem = k
                    body = k.publicKey
                } catch {
                    dispatch(.failed("키 생성 실패: \(error.localizedDescription)")); return
                }
            }
            var out = Data([sts ? Self.modeSts : Self.modePake])
            appendLP(&out, macNonce); appendLP(&out, body)
            helloBytes = out            // 전사는 선에 나간 바이트 그대로를 덮는다
            ble.send(frame(.hello, out))

        case .sendStsResponse:
            guard let out = pendingStsResponse else { return }
            pendingStsResponse = nil
            ble.send(frame(.stsResponse, out))

        case .beginPake(let pin):
            // ★ Argon2id가 64MiB를 돌린다. 메인 액터에서 돌리면 그 시간만큼 UI가 멎는다.
            let publicData = self.publicData
            let client = await Task.detached(priority: .userInitiated) {
                SpakeClient(publicData: publicData, pin: pin)
            }.value
            guard let client else {
                dispatch(.failed("PIN 처리 실패")); return
            }
            spake = client
            ble.send(frame(.pake1, client.response1))

        case .sendProofAndIdentity:
            guard let proof = pendingProof, let session else { return }
            ble.send(frame(.pake3, proof))
            pendingProof = nil
            // 신원은 **봉인된 뒤에** 나간다. 평문 flight에 인증서를 실으면 지나가는
            // 스캐너에게 두 기기의 신원이 그대로 새어 나간다.
            let cert = PeriIdentity.shared.makeCert(name: deviceName)
            if let sealed = try? session.sender.seal(cert.serialized()) {
                ble.send(frame(.identity, sealed.nonce + sealed.body))
            }

        case .pinPeer:
            guard let peerCert else { return }
            // 신원과 링크 키를 **한 번에** 넣는다. 둘을 따로 저장하면 신원만 남고 키가 없는
            // (또는 그 반대의) 반쪽 상태가 생기고, 다음 재연결이 그 위에서 갈린다.
            _ = TrustStore.shared.trust(peerCert, linkKey: linkKey)
            linkKey = nil
            pairedName = peerCert.name
            log.notice("paired with \(peerCert.name) [\(peerCert.fingerprint)]")

        case .sendCandidates:
            guard let session, let provider = candidateProvider else { return }
            let bundle = await provider()
            punchToken = bundle.token
            if let sealed = try? session.sender.seal(bundle.serialized()) {
                ble.send(frame(.params, sealed.nonce + sealed.body))
                log.notice("후보 \(bundle.candidates.count)개 전송: \(bundle.candidates.map(\.description).joined(separator: ", "))")
            }

        case .sendPunchStart:
            guard let session else { return }
            if let sealed = try? session.sender.seal(Data("PUNCH".utf8)) {
                ble.send(frame(.punch, sealed.nonce + sealed.body))
                log.notice("펀칭 개시 신호 전송")
            }

        case .beginPunch:
            if let bundle = pendingRemote { onRemoteCandidates?(bundle); pendingRemote = nil }

        case .armDeadline(let seconds):
            deadlineTask?.cancel()
            deadlineTask = Task { @MainActor [weak self] in
                try? await Task.sleep(for: .seconds(seconds))
                guard !Task.isCancelled else { return }
                self?.dispatch(.deadlineElapsed)
            }

        case .cancelDeadline:
            deadlineTask?.cancel(); deadlineTask = nil

        case .log(let msg):
            log.info("[pair] \(msg)")
        }
    }

    /// OFFER가 실어 온 암호 해싱 파라미터. `beginPake`가 꺼내 쓴다.
    private var publicData = Data()

    // MARK: - UI 진입점

    func startScan() { reset(); dispatch(.startScan) }
    func stopScan() { dispatch(.stopScan) }

    /// 이 기기에 붙는다. **이미 페어링된 상대면 PIN 없이 STS로 간다.**
    ///
    /// - Parameter expecting: 사용자가 **누구를 눌렀는가**(기기 행의 지문). 광고에서 해석한
    ///   지문보다 이쪽을 먼저 믿는다.
    ///
    ///   왜 그런가: 광고의 지문은 회전 토큰을 신뢰저장소에 대조해 **매번 다시 계산하는
    ///   파생값**이고, 그 대조는 토큰 회전·표 재생성 같은 이유로 순간적으로 빗나갈 수 있다.
    ///   그때 조용히 PIN 라운드로 떨어지면 **이미 페어링된 기기에 PIN이 뜨고**, 그 라운드가
    ///   성공하면 신뢰와 링크 키를 통째로 갈아치운다 — 사용자 눈에는 "왜 다시 페어링을
    ///   하지?"로 보인다. 사용자가 누른 행은 그 자체로 "이 기기"라는 확정된 답이므로,
    ///   추론한 값으로 그걸 덮지 않는다.
    ///
    /// 링크 키가 없으면 STS가 **원리적으로** 불가능하다(공유 비밀이 그것뿐이다). 케이블로만
    /// 만난 사이가 그렇고, 그때는 PIN 라운드가 링크 키를 만드는 유일한 길이라 그대로 간다 —
    /// 다만 **조용히 넘어가지 않는다.** 이 갈래를 로그로 남기지 않았던 탓에 "왜 SPAKE2로
    /// 갔는지" 세 후보(지문 해석 실패 · 신뢰 목록에 없음 · 링크 키 없음)를 구분할 수 없었다.
    func connect(_ peer: DiscoveredPeer, expecting: Fingerprint? = nil) {
        reset()
        pendingPeer = peer
        let fp = expecting ?? peer.fingerprint
        if let fp {
            if let t = TrustStore.shared.all().first(where: { $0.fingerprint == fp }) {
                if let link = TrustStore.shared.linkKey(for: t.pub) {
                    stsPeer = t
                    stsLinkKey = link
                } else {
                    log.warning("[pair] \(fp.prefix(8))는 신뢰 목록에 있으나 링크 키가 없다 → PIN 라운드 (케이블로만 만난 사이거나 키가 유실됨)")
                }
            } else {
                log.warning("[pair] \(fp.prefix(8))가 신뢰 목록에 없다 → PIN 라운드")
            }
        } else {
            log.info("[pair] 지문 미상(낯선 광고) → PIN 라운드")
        }
        log.notice("[pair] 연결 개시 — \(self.stsPeer != nil ? "STS(재연결)" : "SPAKE2(첫 페어링)")")
        dispatch(.connectRequested(sts: stsPeer != nil))
    }

    func cancel() { dispatch(.failed("취소됨")) }

    /// TCP/UDP 세션이 실제로 섰다 — 펀칭 창을 붙들 이유가 사라졌으면 지금 끝낸다.
    ///
    /// `AppCore`가 `SessionManager`의 `sessionLive`/`pathSwapped` 처리 시점(제어 채널이
    /// 살아 있다고 확인되는 바로 그 순간)에 부른다. 라운드가 `.punching` 중이 아니면
    /// [PairingMachine.reduce]가 조용히 흘려보낸다 — 이 세션이 지금 도는 라운드와 무관할
    /// 수 있으므로(다른 기기의 재개 등) 여기서 그 구분을 다시 하지 않는다.
    func finishRound() { dispatch(.roundFinished) }

    /// 사용자가 기기 화면의 PIN을 입력했다. **기회는 한 번뿐이다.**
    func submitPin(_ code: String) {
        dispatch(.pinSubmitted(code.trimmingCharacters(in: .whitespaces)))
    }

    private func reset() {
        kem = nil; macNonce = Data(); deviceNonce = Data(); kemSecret = Data()
        transcript = Data(); publicData = Data()
        spake = nil; pendingProof = nil
        session = nil; linkKey = nil; peerCert = nil; punchToken = nil; pendingRemote = nil
        stsPeer = nil; stsLinkKey = nil; stsAuth = nil; pendingStsResponse = nil
        helloBytes = Data(); stsHelloBytes = Data(); stsTranscript = Data()
        pairedName = nil
    }

    /**
     Android가 광고로 재협상을 요청했다(IP가 바뀜) → 붙어서 후보를 다시 교환한다.

     ⚠ v2에서는 이 경로도 **PIN을 요구한다.** v1의 재인증(서명 기반 자동 인증)이 사라졌기
     때문이다. 망이 바뀔 때마다 사람을 부르는 건 실용적이지 않으므로, 핀된 신원으로 PAKE를
     건너뛰는 재연결 경로를 뒤에 따로 얹어야 한다 — flight 1·2는 그대로 두고 PAKE 자리에
     전사 서명을 넣으면 된다.
     */
    private func onIceRestartRequested(_ peer: DiscoveredPeer) {
        log.notice("재협상 요청 → \(peer.name)")
        connect(peer)
    }

    /// BLE 링크가 떨어지면 주변 탐색을 재개 → 다른 기기도 계속 발견.
    private func onBleDisconnect() {
        // 판단을 **먼저** 하고 두 사건을 순서대로 넣는다. `dispatch` 뒤에 상태를 다시 읽으면
        // 드레인이 비동기라 아직 반영 전일 수 있다 — 큐에 순서대로 넣는 쪽이 결정적이다.
        let wasActive: Bool
        switch machine.phase {
        case .idle, .scanning: wasActive = false
        default: wasActive = true
        }
        dispatch(.linkLost)
        if wasActive { dispatch(.startScan) }   // 라운드가 끝났으면 주변 탐색 재개
    }

    // MARK: - 프레임 → 사실

    private func handle(_ data: Data) {
        guard let (type, payload) = parseFrame(data) else { return }
        switch type {
        case .offer: onOffer(payload)
        case .pake2: onPake2(payload)
        case .identity: onIdentity(payload)
        case .candidates: onCandidates(payload)
        case .stsHello: onStsHello(payload)
        case .stsConfirm: onStsConfirm(payload)
        default: break
        }
    }

    /// STS1 도착 → 캡슐화 + 전사 확정 + 우리 서명 봉인. **판단은 기계가 한다.**
    private func onStsHello(_ payload: Data) {
        guard let f = readLPFields(payload, count: 2) else { return }
        do {
            let r = try PeriSts.respond(clientX25519: f[0], clientMLKEM: f[1])
            // core = 봉인 블롭 **앞**까지. 양쪽이 같은 바이트를 해시하도록 이 값을 그대로 보낸다.
            var core = Data()
            appendLP(&core, r.x25519Pub); appendLP(&core, r.ciphertext)
            stsHelloBytes = payload
            let th = PeriBleSts.transcript(hello: helloBytes, stsHello: payload, responseCore: core)
            let keys = PeriBleSts.derive(ecdh: r.ecdh, kem: r.kem, transcript: th)
            stsTranscript = th
            stsAuth = keys.auth
            session = keys.session
            let sig = PeriIdentity.shared.sign(PeriBleSts.macSigningBytes(th))
            var out = core
            appendLP(&out, try PeriSts.sealAuth(keys.auth, sig, role: .server))
            pendingStsResponse = out
            dispatch(.stsHelloReceived)
        } catch {
            dispatch(.failed("키 교환 실패: \(error.localizedDescription)"))
        }
    }

    /// STS3 도착 → **우리가 부른 그 기기**의 서명인지 확인한다.
    ///
    /// 서명의 주인을 따로 찾아내 우리가 부른 상대와 대조한다. `want.pub` 하나로만 검증해도
    /// 결과는 같지만, 그러면 "부른 상대 = 서명한 상대"가 코드 구조에 숨는다. 둘이 갈릴 수 있는
    /// 경로가 실제로 있다 — 호출 응답은 몇 바이트뿐이라 충돌이 가능하고, 링크 키가 두 항목에 걸릴
    /// 수도 있다. 대조를 밖으로 꺼내 두면 그때 무엇이 어긋났는지가 로그에 남는다.
    private func onStsConfirm(_ payload: Data) {
        guard let auth = stsAuth, let want = stsPeer,
              let sig = PeriSts.openAuth(auth, payload, role: .client) else {
            dispatch(.failed("기기 신원을 확인하지 못했습니다")); return
        }
        let signer = PeriSts.resolveSigner(signature: sig,
                                           over: PeriBleSts.androidSigningBytes(stsTranscript),
                                           among: TrustStore.shared.all())
        guard let signer, signer.pub == want.pub else {
            log.error("부른 기기는 \(want.name)인데 서명은 \(signer?.name ?? "모르는 키")다")
            dispatch(.failed("기기 신원을 확인하지 못했습니다")); return
        }
        TrustStore.shared.touch(want.pub)
        pairedName = want.name
        log.notice("재연결 인증 완료: \(want.name) [\(want.fingerprint)]")
        dispatch(.stsConfirmed)
    }

    /// OFFER 수신 → ML-KEM 복호 + 파라미터 대조 + 전사 확정.
    ///
    /// **판단은 여기서 하지 않는다.** 재료를 갖추고 사실만 넣는다.
    private func onOffer(_ payload: Data) {
        guard let f = readLPFields(payload, count: 3), let kem else { return }
        let (nonce, ciphertext, params) = (f[0], f[1], f[2])
        // 상대가 정한 Argon2id 파라미터를 그대로 믿으면 memlimit에 4GiB를 적어 보내는 것만으로
        // 우리를 죽일 수 있다. 우리가 못 박은 값과 정확히 같을 때만 진행한다.
        guard PeriSpake.accepts(params) else {
            dispatch(.failed("기기가 보낸 암호 파라미터를 신뢰할 수 없습니다")); return
        }
        do {
            kemSecret = try kem.open(ciphertext)
        } catch {
            dispatch(.failed("키 교환 실패: \(error.localizedDescription)")); return
        }
        deviceNonce = nonce
        publicData = params
        transcript = PeriBleHandshake.transcript(
            macNonce: macNonce, kemPub: kem.publicKey,
            deviceNonce: nonce, ciphertext: ciphertext, publicData: params)
        dispatch(.offerReceived)
    }

    /// PAKE2 수신 → step3. **여기서 PIN이 맞았는지 갈린다.**
    ///
    /// 실패 사유를 상대에게 알리지 않는다 — 알릴 키도 없고(세션이 안 섰다), 평문 사유를
    /// 허용하면 아무나 남의 라운드를 닫을 수 있다. 링크를 끊는 것으로 충분하다.
    private func onPake2(_ payload: Data) {
        guard let spake else { return }
        guard let out = spake.finish(response2: payload,
                                     clientID: PeriBleHandshake.clientID(transcript),
                                     serverID: PeriBleHandshake.serverID(transcript)) else {
            log.error("PAKE 검증 실패 — PIN 불일치 또는 중간자")
            dispatch(.failed("PIN이 맞지 않습니다"))
            return
        }
        pendingProof = out.response3
        session = PeriBleHandshake.session(kemSecret: kemSecret, spake: out.keys,
                                           macNonce: macNonce, deviceNonce: deviceNonce)
        // 링크 키도 **여기서** 뽑는다 — PAKE 재료가 살아 있는 유일한 순간이다. 상대가 누구인지는
        // 아직 모르므로 들고만 있다가, 신원이 도착하면 그 옆에 붙인다.
        linkKey = PeriBleHandshake.linkKey(kemSecret: kemSecret, spake: out.keys,
                                           macNonce: macNonce, deviceNonce: deviceNonce)
        dispatch(.pakeAccepted)
    }

    /// 봉인된 채널로 온 상대 인증서. 열렸다는 것 자체가 상대가 PIN을 알았다는 증거다.
    private func onIdentity(_ payload: Data) {
        guard let plain = open(payload), let cert = PeriCert.parse(plain),
              cert.verifySelfSignature() else {
            dispatch(.failed("기기 신원을 확인하지 못했습니다")); return
        }
        peerCert = cert
        dispatch(.peerIdentity)
    }

    /// Android 후보 수신(sealed) → 펀칭 개시.
    ///
    /// Android는 자기 토큰을 새로 만들지 않고 우리가 준 값을 그대로 되돌려준다(세션 공유 토큰).
    /// 값이 다르면 응답이 우리 것이 아니거나 형식이 어긋난 것이므로 버린다.
    private func onCandidates(_ payload: Data) {
        guard let plain = open(payload), let bundle = CandidateBundle.parse(plain) else {
            log.error("Android 후보 복호화/파싱 실패"); return
        }
        guard let mine = punchToken, bundle.token == mine else {
            log.error("Android 후보의 펀치 토큰이 우리가 보낸 값과 다름 — 폐기"); return
        }
        log.notice("Android 후보 \(bundle.candidates.count)개 수신: \(bundle.candidates.map(\.description).joined(separator: ", "))")
        pendingRemote = bundle
        dispatch(.remoteCandidates)
    }

    /// [12B nonce][ciphertext‖tag] → 평문. 세션이 없거나 열리지 않으면 nil.
    private func open(_ payload: Data) -> Data? {
        guard let session, payload.count >= 12 else { return nil }
        let nonce = payload.prefix(12)
        let body = payload.suffix(from: payload.startIndex + 12)
        return try? session.receiver.open(nonce: Data(nonce), body: Data(body))
    }

    // MARK: - 프레임 코덱 — [1B type][payload]
    //
    // 값이 하나뿐인 프레임은 payload가 곧 그 값이다(길이가 프로토콜 상수라 경계가 없어도 된다).
    // 값이 여럿이면 2B BE 길이프리픽스로 나눈다. 봉인된 프레임은 [12B nonce][본문]이다.

    /// Android `PairingCoordinator.kt`의 T_* 상수와 값이 일치해야 한다.
    private enum FrameType: UInt8 {
        case hello = 1       // Mac → And : [모드][논스][ML-KEM 공개키 또는 TOTP 힌트]
        case offer = 2       // And → Mac : 논스 ‖ 암호문 ‖ 암호해싱 파라미터
        case pake1 = 3       // Mac → And
        case pake2 = 4       // And → Mac
        case pake3 = 5       // Mac → And
        case identity = 6    // 양방향 (sealed) : 장기 신원 인증서
        case params = 7      // Mac → And (sealed) : 후보 묶음 + 펀치 토큰
        case candidates = 8  // And → Mac (sealed) : 후보 묶음 (같은 토큰 에코)
        case punch = 9       // Mac → And (sealed) : 지금 펀칭 시작
        // 재연결(STS) 경로. PAKE 프레임과 번호를 나눠 써서 섞일 수 없다.
        case stsHello = 10   // And → Mac : X_A ‖ PK_A
        case stsResponse = 11 // Mac → And : X_B ‖ ct ‖ 봉인된 Mac 서명
        case stsConfirm = 12 // And → Mac : 봉인된 기기 서명
    }

    /// HELLO 첫 바이트 — 이 라운드가 PIN인지 재연결인지. Android의 `MODE_*`와 값이 같아야 한다.
    ///
    /// **이건 요청이 아니라 우리가 이미 내린 결정의 통보다.** 받는 쪽은 이 바이트를 믿고
    /// 갈라지지 않는다 — 힌트가 실제로 맞아떨어질 때만 STS로 간다.
    private static let modePake: UInt8 = 1
    private static let modeSts: UInt8 = 2

    private func frame(_ t: FrameType, _ payload: Data) -> Data { Data([t.rawValue]) + payload }

    private func parseFrame(_ d: Data) -> (FrameType, Data)? {
        guard let first = d.first, let t = FrameType(rawValue: first) else { return nil }
        return (t, d.subdata(in: (d.startIndex + 1)..<d.endIndex))
    }

    private func appendLP(_ out: inout Data, _ field: Data) {
        out.append(UInt8((field.count >> 8) & 0xFF))
        out.append(UInt8(field.count & 0xFF))
        out.append(field)
    }

    private func readLPFields(_ input: Data, count: Int) -> [Data]? {
        let d = Data(input)   // 0-기반 보장
        var out: [Data] = []; var i = 0
        for _ in 0..<count {
            guard i + 2 <= d.count else { return nil }
            let len = Int(d[i]) << 8 | Int(d[i + 1]); i += 2
            guard i + len <= d.count else { return nil }
            out.append(d.subdata(in: i..<(i + len))); i += len
        }
        return out
    }
}
