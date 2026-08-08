//
//  PeriApp.swift
//  Peri Display
//
//  **앱 모델.** 확장·공유·파일 전송은 서로를 모르는 독립된 앱이고, 사용자가 아이콘을 눌러야
//  뜬다. 세션이 붙었다는 건 홈 화면이 켜졌다는 뜻이지 앱이 실행됐다는 뜻이 아니다.
//
//  세션이 앱에게 하는 말은 세 마디뿐 (DESIGN.md §5):
//
//      attach(채널)   — 이 기기와 말할 수 있게 됐다
//      detach(사유)   — 채널이 끊겼다 (세션은 살아 있을 수도)
//      receive(메시지) — 상대가 보냈다
//
//  사용자가 앱에게 하는 말은 두 마디다:
//
//      launch()      — 아이콘을 눌렀다
//      quit(사유)     — 껐다 (또는 앱이 스스로 못 하겠다고 했다)
//
//  이 둘이 **직교한다**는 게 요점이다. 앱은 채널이 없어도 켜져 있을 수 있고(경로 복귀를
//  기다리는 중), 채널이 있어도 꺼져 있을 수 있다(홈 화면). 예전엔 이 둘을 한 축에 뭉쳐서
//  "세션이 서면 화면이 켜진다"가 됐고, 그래서 끌 방법이 없었다.
//
//  누가 무엇을 소유하는가
//  ──────────────────────
//    기기(지문) ─── 오래 산다 ─── AppHost ─── 앱들 (가상 디스플레이·동의·오프셋)
//     └ 세션 ──── 갈린다 (RESUME · 콜드 재수립 · LAN↔AOA 전환) ─── ServiceChannel
//
//  세션이 갈릴 때마다 앱을 새로 만들면 케이블 한 번 꽂을 때마다 가상 디스플레이가
//  파괴·재생성되고, 그건 ScreenCaptureKit 버그(FB17797423)를 직접 건드린다.
//  그래서 갈리는 것은 **채널뿐**이다.
//

import Combine
import Foundation

/// 앱 식별자.
///
/// B단계(와이어 v2)에서 Mux 조각 헤더의 `serviceId` 바이트가 될 값이라 번호를 지금 맞춰 둔다
/// (0 = session-control, 즉 세션 자신).
enum AppID: UInt8, Sendable, Hashable, CaseIterable {
    case extend = 1
    case share = 2
    case files = 3
    case notify = 4

    /// 와이어의 `AppId`. 번호를 맞춰 뒀으므로 변환은 형식일 뿐이지만, 그걸 명시해 두면
    /// 나중에 한쪽 번호가 흔들려도 컴파일러가 잡는다.
    var wire: PeriAppId {
        switch self {
        case .extend: return .extend
        case .share:  return .share
        case .files:  return .files
        case .notify: return .notify
        }
    }

    init?(wire: PeriAppId) {
        switch wire {
        case .extend: self = .extend
        case .share:  self = .share
        case .files:  self = .files
        case .notify: self = .notify
        default:      return nil
        }
    }
}

/// 홈 화면이 아이콘을 그리는 데 필요한 전부.
///
/// **앱이 스스로 선언한다.** 런처는 이 값만 보고 그리고 막는다 — 어떤 앱이 있는지, 무엇과
/// 부딪히는지를 런처가 알고 있으면 앱을 추가할 때 런처를 뜯어야 한다.
nonisolated struct AppDescriptor: Sendable, Hashable {
    let id: AppID
    /// 홈 화면 아이콘 아래 이름.
    let name: String
    /// SF Symbol.
    let icon: String
    /// 한 줄 설명 (아이콘 툴팁).
    let blurb: String
    /// 이 앱이 도는 동안 **같이 못 도는** 앱들.
    ///
    /// 확장과 공유가 그렇다: Mac이 보낸 화면을 폰이 띄우고 있는데 그 화면을 다시 찍어
    /// 보내면 무한 거울이 된다. 물리적으로 막을 방법이 없으니 런처가 막는다.
    /// **목록은 대칭이어야 한다** — 한쪽만 적으면 켜는 순서에 따라 뚫린다(아래 검사 참조).
    let conflicts: Set<AppID>
    /// 아직 만들다 만 앱인가. 홈 화면에 보이되 눌리지 않는다.
    let comingSoon: Bool
    /// 이 앱이 **최신성 경로**(데이터그램)를 쓰는가.
    ///
    /// 세션은 TCP만으로 선다. UDP는 이 값이 참인 앱이 실제로 떠 있는 동안에만 열려 있고,
    /// 마지막 그런 앱이 내려가면 닫힌다 — NAT의 UDP 구멍은 수명이 짧아 붙들고 있으려면
    /// 계속 두드려야 하는데, 아무도 안 쓰는 구멍에 그 값을 낼 이유가 없다.
    ///
    /// **앱이 스스로 선언한다.** 세션이 "확장과 공유는 영상을 쓴다"를 알고 있으면 앱을
    /// 하나 더할 때마다 세션을 뜯어야 한다(원칙 1·2).
    let needsDatagram: Bool

    init(id: AppID, name: String, icon: String, blurb: String,
         conflicts: Set<AppID> = [], comingSoon: Bool = false,
         needsDatagram: Bool = false) {
        self.id = id
        self.name = name
        self.icon = icon
        self.blurb = blurb
        self.conflicts = conflicts
        self.comingSoon = comingSoon
        self.needsDatagram = needsDatagram
    }
}

/// 채널이 끊긴 이유.
///
/// **무엇을 지키고 무엇을 버릴지는 앱이 스스로 정한다** — 세션은 사실만 전한다.
/// 확장의 답은 "디스플레이·제원은 지키고 캡처·인코더는 버린다"이고, 공유의 답은 "동의를
/// 통째로 버린다"(MediaProjection 토큰이 1회용이라서). 그 차이가 곧 앱이 다르다는 뜻이다.
enum DetachReason: Sendable, Equatable {
    /// 경로만 사라졌다. 세션도 기기도 살아 있고, 유예 안에 새 채널이 올 수 있다.
    case pathLost
    /// 같은 기기의 새 연결이 인계했다 — 곧이어 `attach`가 온다.
    case sessionReplaced
    /// 기기가 떠났다. 돌아오지 않으므로 붙잡고 있던 자원을 전부 놓는다.
    case deviceGone(reason: String)
}

/// 앱에게 배달되는 메시지 하나.
///
/// 레인이 둘이다 — 반드시 도착하는 것과 최신이면 되는 것(DESIGN.md §1). 앱은 어느 파이프로
/// 왔는지 모르고 알 필요도 없다: TCP든 UDP든 AOA든 여기 도착할 때는 같은 모양이다.
///
/// B단계에서 Mux가 들어와도 **이 타입만 늘고 `PeriApp`의 서명은 그대로다**.
nonisolated enum AppMessage: Sendable {
    /// 신뢰 레인 — 제어 메시지.
    case control(PeriControlMessage)
    /// 최신성 레인 — 완성되고 봉인이 풀린 미디어 한 덩이(인코딩된 접근 단위 하나).
    /// 재조립도 복호도 채널이 이미 끝냈다(`ServiceChannel.openMedia`).
    case media(Data, MediaMeta)
}

/// 홈 화면의 앱 하나.
@MainActor
protocol PeriApp: AnyObject, Sendable {
    /// 홈 화면이 읽는 정적 서술. **`nonisolated`다** — 아이콘을 그리는 데 앱 인스턴스의
    /// 상태가 필요하지 않고, 그래야 목록을 어느 문맥에서든 만들 수 있다.
    nonisolated static var descriptor: AppDescriptor { get }

    /// 말할 채널이 생겼다. 세션이 갈려도 앱은 살아 있으므로 **여러 번 불린다**.
    func attach(_ ch: ServiceChannel) async
    /// 채널이 끊겼다. 사유를 보고 지킬 것과 버릴 것을 고른다.
    func detach(_ reason: DetachReason) async
    /// 상대가 보낸 메시지. **전송 큐에서 동기로** 불린다 → `nonisolated`.
    nonisolated func receive(_ msg: AppMessage)

    /// 사용자가 아이콘을 눌렀다.
    func launch()
    /// 껐다 — 사용자가 눌렀거나, 앱이 스스로 못 하겠다고 했거나, 기기가 떠났거나.
    func quit(_ reason: String) async

    /// 지금 켜져 있는가. 홈 화면 아이콘의 실행 표시.
    var isRunning: Bool { get }
    /// 아이콘 아래 보조 문구 ("동의를 기다리는 중…"). 평상시엔 nil.
    var statusText: String? { get }

    /// 앱이 **스스로** 못 하겠다고 할 때 부른다(동의 거부, 디스플레이 실패…).
    /// 호스트가 여기에 자기 종료 절차를 꽂아 둔다 — 앱은 호스트를 모른다.
    var onQuit: ((String) -> Void)? { get set }
}

extension PeriApp {
    nonisolated var id: AppID { Self.descriptor.id }
}

/// 한 기기의 홈 화면.
///
/// **새 앱 추가 = 파일 추가 + 여기 등록 한 줄**(DESIGN.md 원칙 2). 세션·기기 기계 파일은
/// 0줄 바뀐다 — 세션은 자기 것이 아닌 메시지를 통째로 여기로 넘길 뿐이다.
@MainActor
final class AppHost: ObservableObject {

    let extend: ExtendService
    let share: ShareService
    let notify: NotifyService

    /// 등록된 앱 전부. 홈 화면이 이 순서대로 그린다.
    ///
    /// **`nonisolated`다** — 수신 경로가 전송 큐에서 동기로 여기를 훑기 때문이다. 목록 자체는
    /// 생성 후 절대 안 바뀌므로(앱은 설치·제거되지 않는다) 락 없이 안전하다.
    nonisolated let apps: [any PeriApp]
    /// 아직 구현 안 된 자리 — 아이콘만 있고 눌리지 않는다.
    let placeholders: [AppDescriptor] = [
        AppDescriptor(id: .files, name: "파일", icon: "folder.fill",
                      blurb: "기기와 파일을 주고받습니다", comingSoon: true),
    ]

    let fingerprint: Fingerprint
    /// 앱이 "못 하겠다"고 할 때 기기 등록부로 올리는 통로 (로그·UI 갱신용).
    var onChange: (() -> Void)?

    /// 홈 화면이 다시 그려져야 한다 — 앱이 뜨거나 지거나, 채널이 붙거나 끊겼다.
    /// 등록부(`onChange`)와 뷰(`objectWillChange`)가 같은 사실을 봐야 하므로 한 곳에서 낸다.
    private func changed() {
        syncDatagramPath()
        objectWillChange.send()
        onChange?()
    }

    /// 최신성 경로를 요구하는 앱들. **의도의 집합이지 실행 상태가 아니다.**
    ///
    /// `isRunning`으로 계산하면 안 된다: `launch()`는 앱의 상태 기계에 사건을 올릴 뿐이라
    /// 돌아온 직후의 `isRunning`은 아직 거짓이고(같은 이유로 `attach`가 알릴 목록을
    /// 미리 만든다), 그러면 방금 띄운 앱의 경로가 영영 안 열린다.
    private var datagramWanters: Set<AppID> = []

    /// 지금 데이터그램 경로가 필요한가를 채널에 알린다.
    ///
    /// **여기 한 곳에서만** 계산한다 — 앱이 뜨고 지는 지점마다 따로 부르면 언젠가 한 곳이
    /// 빠지고, 그 증상은 "가끔 화면이 안 온다"로 나타난다. `changed()`가 그 지점 전부를
    /// 이미 지나가므로 여기에 얹으면 빠질 자리가 없다.
    private func syncDatagramPath() {
        channel?.setDatagramPathWanted(!datagramWanters.isEmpty)
    }

    private func noteDatagramNeed(_ id: AppID, wanted: Bool) {
        guard allDescriptors.first(where: { $0.id == id })?.needsDatagram == true else { return }
        if wanted { datagramWanters.insert(id) } else { datagramWanters.remove(id) }
    }

    private var channel: ServiceChannel?
    /// 말할 수 있는가. 앱은 이 값과 무관하게 켜져 있을 수 있다(복귀 대기).
    private(set) var linked = false

    init(fingerprint: Fingerprint) {
        self.fingerprint = fingerprint
        self.extend = ExtendService(fingerprint: fingerprint)
        self.share = ShareService(fingerprint: fingerprint)
        self.notify = NotifyService(fingerprint: fingerprint)
        // ★ 등록 한 줄. 새 앱은 여기 한 줄이 전부다.
        //
        // 알림 앱이 그 약속의 세 번째 증명이다 — 이 줄과 파일 하나를 더했을 뿐,
        // 세션·기기 기계·먹스는 0줄 바뀌었다(DESIGN.md 원칙 2).
        self.apps = [extend, share, notify]
        for app in apps {
            let id = app.id
            app.onQuit = { [weak self] why in
                Task { @MainActor in await self?.quit(id, reason: why) }
            }
        }
    }

    // MARK: - 홈 화면이 보는 것

    /// 아이콘 목록 — 구현된 앱 + 자리만 잡아 둔 앱.
    var allDescriptors: [AppDescriptor] {
        apps.map { type(of: $0).descriptor } + placeholders
    }

    func app(_ id: AppID) -> (any PeriApp)? { apps.first { $0.id == id } }

    var running: Set<AppID> { Set(apps.filter(\.isRunning).map(\.id)) }

    /// 지금 이 앱을 띄울 수 있는가. **nil이면 가능**, 아니면 막힌 이유(사용자에게 보여줄 문구).
    func blockedReason(_ id: AppID) -> String? {
        guard let desc = allDescriptors.first(where: { $0.id == id }) else { return "없는 앱입니다" }
        if desc.comingSoon { return "아직 준비 중입니다" }
        guard linked else { return "기기와 연결되면 사용할 수 있습니다" }
        // 부딪히는 앱이 돌고 있으면 막는다. 이름을 실어 줘야 사용자가 뭘 꺼야 할지 안다.
        for other in desc.conflicts where running.contains(other) {
            let name = allDescriptors.first { $0.id == other }?.name ?? "다른 앱"
            return "\(name)을(를) 먼저 종료하세요"
        }
        return nil
    }

    // MARK: - 실행 · 종료

    @discardableResult
    func launch(_ id: AppID) -> String? {
        if let why = blockedReason(id) { return why }
        guard let app = app(id), !app.isRunning else { return nil }
        app.launch()
        noteDatagramNeed(id, wanted: true)
        announce(id, launch: true, reason: "")
        AppLaunchStore.shared.setRunning(true, app: id, for: fingerprint)
        changed()
        return nil
    }

    func quit(_ id: AppID, reason: String) async {
        guard let app = app(id), app.isRunning else { return }
        await app.quit(reason)
        noteDatagramNeed(id, wanted: false)
        announce(id, launch: false, reason: reason)
        AppLaunchStore.shared.setRunning(false, app: id, for: fingerprint)
        changed()
    }

    /// 기기가 떠났다 — 전부 내린다. **기억은 지우지 않는다**(다음에 붙으면 다시 띄운다).
    ///
    /// 알리지 않는다: 기기가 떠났다는 건 채널이 없다는 뜻이고, 있다 해도 상대는 자기 쪽
    /// 끊김에서 같은 결론에 이미 도달해 있다.
    func quitAll(reason: String) async {
        for app in apps where app.isRunning { await app.quit(reason) }
        datagramWanters.removeAll()
        changed()
    }

    /// **런처가 내린 결정을 기기에 알린다.** 앱마다 따로 알리는 게 아니라 여기 한 곳이다 —
    /// 뜨고 지는 걸 정하는 게 런처이므로, 그 사실을 전할 책임도 런처에 있다.
    ///
    /// 앱이 이 메시지를 직접 보내게 하면 앱마다 "나는 떴다"를 잊지 않고 보내야 하고,
    /// 한 앱이 빠뜨리면 그 기기는 영영 자기 절반을 못 세운다.
    private func announce(_ id: AppID, launch: Bool, reason: String) {
        guard let channel else { return }
        var c = PeriAppControl()
        c.app = id.wire
        c.launch = launch
        c.reason = reason
        var m = PeriControlMessage(); m.appControl = c
        channel.sendReliable(m)
    }

    // MARK: - 세션과의 접점

    func attach(_ ch: ServiceChannel) async {
        channel = ch
        linked = true
        for app in apps { await app.attach(ch) }
        // ★ **채널이 새로 붙을 때마다 실행 중인 앱을 기기에 알린다.**
        //
        // 경로가 갈리면(RESUME · LAN↔AOA 전환) 앱은 이쪽에서 살아남지만 저쪽은 세션이
        // 끊길 때 자기 절반을 내렸다. 알리지 않으면 Mac은 송출하는데 기기는 디코더를
        // 안 세운 상태로 남는다 — 화면이 검은 채로 통계만 도는 그 증상이다.
        //
        // ⚠ 알릴 목록을 **`launch()` 전에** 만든다. `launch()`는 `Task`로 기계에 사건을
        // 올리므로 돌아온 직후의 `isRunning`은 아직 false다 — 방금 띄운 앱을 `isRunning`으로
        // 걸러 내면 그 앱은 영영 안 알려진다(확장이면 기기가 디코더를 안 세운다).
        var toAnnounce = Set(apps.filter(\.isRunning).map(\.id))
        // 지난번에 켜 뒀던 앱을 다시 띄운다. **요청은 여전히 요청이다** — 사용자가 매번
        // 누르지 않아도 되게 우리가 대신 눌러 주는 것이고, 켠 적 없는 앱은 뜨지 않는다.
        for id in AppLaunchStore.shared.running(for: fingerprint) where blockedReason(id) == nil {
            app(id)?.launch()
            toAnnounce.insert(id)
        }
        for id in toAnnounce.sorted(by: { $0.rawValue < $1.rawValue }) {
            announce(id, launch: true, reason: "")
        }
        // 경로가 갈렸으면 데이터그램 세대도 갈렸다 — 지금 떠 있는(또는 방금 되띄운) 앱을
        // 기준으로 다시 계산해, 새 채널 위에서 세대를 새로 연다.
        datagramWanters = toAnnounce.filter { id in
            allDescriptors.first { $0.id == id }?.needsDatagram == true
        }
        changed()
    }

    func detach(_ reason: DetachReason) async {
        linked = false
        channel = nil
        for app in apps { await app.detach(reason) }
        if case .deviceGone(let why) = reason { await quitAll(reason: why) }
        changed()
    }

    /// 세션이 자기 것이 아닌 메시지를 통째로 넘긴다.
    ///
    /// 와이어 v1에는 `serviceId`가 없어 등록부가 전부에게 뿌린다. 지금은 앱이 둘뿐이라
    /// 비용이 없고, B단계에서 Mux가 라우팅을 맡으면 이 팬아웃이 곧 조회 한 번이 된다.
    nonisolated func receive(_ msg: AppMessage) {
        for app in apps { app.receive(msg) }
    }
}

/// 기기별로 "마지막에 켜 두었던 앱"을 기억한다.
///
/// 진짜 폰과 같은 감각을 위해서다 — 케이블을 뽑았다 꽂았다고 홈 화면으로 돌아가면 성가시다.
/// 다만 **켠 적 없는 앱은 절대 뜨지 않는다**. 기억은 사용자의 지난 선택이지 기본값이 아니다.
@MainActor
final class AppLaunchStore {
    static let shared = AppLaunchStore()
    private let key = "runningApps"      // [지문: [앱 rawValue]]

    private var table: [String: [UInt8]] {
        get { (UserDefaults.standard.dictionary(forKey: key) as? [String: [UInt8]]) ?? [:] }
        set { UserDefaults.standard.set(newValue, forKey: key) }
    }

    func running(for fp: Fingerprint) -> [AppID] {
        (table[fp] ?? []).compactMap(AppID.init(rawValue:))
    }

    func setRunning(_ on: Bool, app: AppID, for fp: Fingerprint) {
        var t = table
        var ids = Set(t[fp] ?? [])
        if on { ids.insert(app.rawValue) } else { ids.remove(app.rawValue) }
        t[fp] = Array(ids).sorted()
        table = t
    }
}
