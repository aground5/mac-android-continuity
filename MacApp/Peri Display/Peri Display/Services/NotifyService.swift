//
//  NotifyService.swift
//  Peri Display
//
//  **앱 #4 — 알림 (Android → Mac).**
//
//  화면 공유가 알림을 *그림*으로 나른다면, 여기서는 **데이터**로 온다. 그 차이가 전부다:
//  Mac의 진짜 알림이 되고, 눌러서 폰의 그 앱을 열고, 폰에서 지우고, 답장까지 한다.
//
//  그래서 화면 공유와 **독립적이다** — 케이블도 공유도 필요 없다(HID·MediaProjection과 다르다).
//  세 앱이 서로를 모른 채 같은 채널을 나눠 쓰는 게 이 구조의 요점이고, 이 앱은 그걸
//  처음으로 **세 번째** 증명한다: 새 파일 + 등록 한 줄, 세션·기기 기계는 0줄.
//
//  상태 기계가 없는 이유
//  ────────────────────
//  확장·공유에는 기계가 있다(파이프라인이 서고 눕고, 동의가 나고 만료된다). 알림에는
//  **세울 것이 없다.** 켜져 있으면 메시지가 오고 꺼지면 안 온다 — `active`와 `linked`
//  두 불리언이 상태의 전부라 기계를 두면 그 둘을 옮겨 적기만 하는 껍데기가 된다.
//  기계는 필요할 때 만드는 것이지 모든 앱이 갖춰야 할 장식이 아니다.
//

import AppKit
import Combine
import Foundation
import OSLog
import UserNotifications

private nonisolated let log = Logger(subsystem: "com.peri.display.mac", category: "notify")

/// 지금 폰에 떠 있는 알림 하나 — **Mac 알림이 요구하는 모양으로 이미 줄인 것.**
///
/// 와이어의 `NotificationPosted`는 이보다 훨씬 크다(스타일별 원본, 그림 셋, 액션 전부).
/// 그걸 여기까지 줄이는 게 [NotifyMapper]이고, 그 아래로는 안드로이드 어휘가 안 내려온다.
struct PeriNotification: Identifiable, Equatable {
    let id: String            // 와이어의 key
    var appLabel: String
    var title: String
    /// 부제. 조작 실패 사유가 오면 이 자리를 덮어쓴다.
    var subtitle: String
    var body: String
    var postedAt: Date
    var ongoing: Bool
    var clearable: Bool
    /// 답장 액션 번호 + 1 (0 = 답장 불가). 폰에 되돌릴 값이라 그대로 들고 있는다 —
    /// 배너를 다시 띄울 때 이 값이 또 필요하다.
    var replyAction: UInt32
    var canReply: Bool { replyAction > 0 }
    /// 알림에 붙일 그림 PNG. `NSImage`가 아니라 원본 바이트로 들고 있는 건 알림 첨부가
    /// **파일**을 요구하기 때문이다 — 다시 띄울 때 또 써야 한다.
    var iconPNG: Data
    /// [iconPNG] 오른쪽 아래에 얹을 앱 아이콘. 비어 있으면 안 얹는다 — 바탕이 이미
    /// 앱 아이콘인 경우다.
    var badgePNG: Data
    /// 같은 값이면 Mac 알림 센터에서도 한 묶음 (`threadIdentifier`).
    var groupKey: String
    /// 폰에서도 조용히 뜨는 알림인가(채널 중요도가 낮다). Mac에서만 울리면 폰을 조용히 해 둔
    /// 뜻이 무너진다.
    var silent: Bool

    static func == (a: PeriNotification, b: PeriNotification) -> Bool {
        a.id == b.id && a.title == b.title && a.subtitle == b.subtitle
            && a.body == b.body && a.postedAt == b.postedAt
    }
}

@MainActor
final class NotifyService: ObservableObject, PeriApp {

    nonisolated static let descriptor = AppDescriptor(
        id: .notify, name: "알림", icon: "bell.badge.fill",
        blurb: "기기 알림을 Mac 알림으로 받습니다")

    let fingerprint: Fingerprint
    var onQuit: ((String) -> Void)?

    private var active = false
    private var linked = false
    private var channel: ServiceChannel?

    var isRunning: Bool { active }
    var statusText: String? {
        guard active else { return nil }
        if !linked { return "경로를 기다리는 중…" }
        // 상대가 못 하겠다고 한 이유가 가장 급하다 — 권한이 없으면 한 건도 안 오는데,
        // 그 사실은 기기만 안다. 이걸 안 보여 주면 "켰는데 조용하다"로 끝난다.
        if let remoteBlocked { return remoteBlocked }
        if !authorized { return "Mac 알림 권한이 필요합니다" }
        return live.isEmpty ? nil : "\(live.count)개"
    }

    /// 기기가 보고한 막힘 사유(`AppState.detail`). nil = 저쪽은 정상.
    @Published private(set) var remoteBlocked: String?

    /// 지금 폰에 떠 있는 알림들 — 최근 순.
    @Published private(set) var live: [PeriNotification] = []
    /// macOS 알림 권한을 받았는가. 없으면 목록에는 쌓이되 배너가 안 뜬다.
    @Published private(set) var authorized = false

    private let center = UNUserNotificationCenter.current()
    /// 우리가 띄운 Mac 알림: key → 띄운 시각.
    ///
    /// 시각까지 들고 있는 이유는 [reconcileCleared] 때문이다 — 방금 띄운 알림은 아직 알림
    /// 센터 목록에 안 잡힐 수 있는데, 그걸 "사용자가 지웠다"로 오해하면 폰에서 멀쩡한 알림이
    /// 사라진다.
    private var posted: [String: Date] = [:]
    /// 알림 센터를 주기적으로 대조하는 루프.
    ///
    /// **볼 게 있을 때만 돈다** — 장부가 비면 스스로 끝나고, 다음 알림이 뜨면 다시 시작한다.
    private var reconcileTask: Task<Void, Never>?
    /// 사람이 마지막으로 알림을 만진 때. 대조 주기를 정하는 근거다.
    private var lastTouch = Date()
    /// 화면·시스템이 깨어나는 것을 지켜보는 관찰자들. 앱이 도는 동안만 붙어 있는다.
    private var observers: [any NSObjectProtocol] = []

    init(fingerprint: Fingerprint) {
        self.fingerprint = fingerprint
    }

    // MARK: - 앱 계약

    func attach(_ ch: ServiceChannel) async {
        channel = ch
        linked = true
        // 채널이 (다시) 붙었으면 목록을 처음부터 맞춘다. 끊겨 있는 동안 온 알림과
        // 사라진 알림을 우리는 못 봤으므로, 지금 떠 있는 것을 통째로 다시 받는 게 유일하게
        // 어긋나지 않는 방법이다.
        if active { requestSync() }
        objectWillChange.send()
    }

    func detach(_ reason: DetachReason) async {
        linked = false
        channel = nil
        if case .deviceGone = reason { clearAll() }
        objectWillChange.send()
    }

    func launch() {
        guard !active else { return }
        active = true
        lastTouch = Date()
        Task { await requestAuthorization() }
        requestSync()
        observeReturns()
        // 대조 루프는 여기서 안 켠다 — 아직 띄운 알림이 없다. 첫 알림이 게시되는 순간
        // [submit]이 켠다.
        objectWillChange.send()
    }

    func quit(_ reason: String) async {
        guard active else { return }
        active = false
        remoteBlocked = nil
        reconcileTask?.cancel(); reconcileTask = nil
        forgetReturns()
        log.notice("알림 종료: \(reason)")
        clearAll()
        objectWillChange.send()
    }

    nonisolated func receive(_ msg: AppMessage) {
        guard case .control(let m) = msg else { return }
        switch m.payload {
        case .notificationPosted(let n):
            Task { @MainActor in self.onPosted(n) }
        case .notificationRemoved(let r):
            Task { @MainActor in self.onRemoved(r.key) }
        case .notificationActionFailed(let f):
            Task { @MainActor in self.onActionFailed(f) }
        case .appState(let s) where s.app == .notify:
            // 기기가 자기 쪽 사정을 알려 왔다 — 대개 "알림 접근을 허용해야 한다"이다.
            Task { @MainActor in
                self.remoteBlocked = s.running ? nil : (s.detail.isEmpty ? nil : s.detail)
                self.objectWillChange.send()
            }
        default:
            break
        }
    }

    // MARK: - 수신

    /// 기기가 알림 하나를 보내 왔다 — **거를지도 어떻게 보일지도 여기서 정한다.**
    ///
    /// 기기는 아무것도 거르지 않고 전부 보낸다. 그래야 규칙을 바꿀 때 이 파일만 고치면 되고,
    /// 무엇이 왜 안 떴는지도 로그로 남는다 — 기기가 걸러 버리면 그 알림이 있었다는 사실조차
    /// 여기 오지 않는다.
    private func onPosted(_ n: PeriNotificationPosted) {
        guard active else { return }
        if let why = NotifyMapper.skip(n) {
            log.debug("거름(\(why.rawValue)): \(n.packageName)")
            return
        }
        let item = NotifyMapper.map(n, fingerprint: fingerprint)

        // 같은 key면 갱신이다 — Android가 같은 key로 다시 post하는 게 곧 갱신이다.
        if let i = live.firstIndex(where: { $0.id == item.id }) {
            let unchanged = live[i] == item
            live[i] = item
            // 내용이 그대로면 배너를 다시 띄우지 않는다. 진행 중 알림(음악 진행률 등)이
            // 초마다 갱신되는데 그때마다 배너가 뜨면 못 쓴다.
            if unchanged { return }
        } else {
            live.insert(item, at: 0)
        }
        live.sort { $0.postedAt > $1.postedAt }

        // 진행 중 알림은 목록에만 남긴다 — 곡이 바뀔 때마다 배너가 뜨면 안 된다.
        guard !item.ongoing else { return }
        banner(item)
    }

    /**
     기기가 "그 조작은 안 됐다"고 알려 왔다 — 배너를 **이유와 함께** 되살린다.

     4초 확인(`confirmOpened`)이 있는데도 이 경로가 필요한 이유는 **왜**를 말할 수 있어서다.
     그냥 되살아나면 사용자는 자기가 잘못 눌렀는지, 폰이 죽었는지, 뭐가 문제인지 모른다.
     "기기가 잠겨 있습니다"가 뜨면 그 다음에 할 일이 분명해진다 — 폰이 이미 잠금 해제를
     청하는 알림을 띄워 뒀으므로 그걸 누르면 열린다.
     */
    private func onActionFailed(_ f: PeriNotificationActionFailed) {
        guard active, let item = live.first(where: { $0.id == f.key }) else { return }
        let why = f.detail.isEmpty ? Self.describe(f.reason) : f.detail
        log.notice("기기가 조작을 못 했다: \(why)")
        banner(item, note: why)
    }

    private static func describe(_ r: PeriNotificationActionFailed.Reason) -> String {
        switch r {
        case .locked:        return "기기가 잠겨 있습니다"
        case .notPermitted:  return "기기에서 권한이 필요합니다"
        case .gone:          return "그 알림이 기기에서 사라졌습니다"
        default:             return "기기에서 처리하지 못했습니다"
        }
    }

    private func onRemoved(_ key: String) {
        live.removeAll { $0.id == key }
        withdraw(key)
        objectWillChange.send()
    }

    private func clearAll() {
        center.removeDeliveredNotifications(withIdentifiers: Array(posted.keys))
        posted.removeAll()
        live.removeAll()
        // 첨부에 실패해 남은 아이콘 파일들. 성공한 것들은 시스템이 이미 가져갔다.
        try? FileManager.default.removeItem(at: Self.iconDir)
    }

    // MARK: - 알림 센터 대조

    /**
     사용자가 **알림 센터에서 치운 것**을 찾아 폰에도 반영한다.

     ★ **macOS는 일괄 지우기를 안 알려 준다.** `.customDismissAction`은 알림 하나를 개별로
     닫았을 때만 콜백을 준다. '모두 지우기'나 묶음 단위 지우기에는 아무 통지도 없고, 그걸
     관찰하는 API도 없다 — 그래서 지운 뒤에도 폰에는 그대로 남았다.

     방법은 하나뿐이다: **지금 알림 센터에 뭐가 있는지 물어보고 우리 장부와 대조한다.**
     우리가 띄웠는데 목록에 없고 우리가 치운 적도 없다면, 치운 건 사용자다.

     ─────────────────────────────────────────────────────────────────────────
     **언제 도는가** — 예전엔 앱이 켜져 있는 내내 2초마다 무조건 깨어났다. 두 가지를 고쳤다.

     ① **볼 게 없으면 아예 안 돈다.** 장부가 비어 있으면 루프를 끝낸다. 알림이 하나도 없는
        대부분의 시간에 2초마다 깨어나 "비었네" 하고 다시 자던 게 순수한 낭비였다.
        다음 알림이 뜨면([submit]) 다시 시작한다.

     ② **사람이 만지는 동안만 빠르다.** 이 루프가 잡으려는 건 '모두 지우기'인데, 그건 사람이
        알림 센터를 **정리하는 중**에 일어난다. 그래서 사람의 손길이 있었으면 2초, 조용하면
        10초로 물러선다.

     "사람의 손길"을 시각으로 짐작하지 않고 **실제 사건**으로 센다([noteActivity]): 알림을
     누름·지움, 대조에서 뭔가 발견됨, 화면·시스템이 깨어남, 우리 창이 앞으로 나옴. 사람이
     알림을 하나 지웠다면 더 지울 참일 가능성이 높다 — 그때 빠른 게 값을 한다.

     ★ **주기를 무작정 늘리지 않는 이유.** 이 지연은 그대로 "Mac에서 지웠는데 폰에 아직
     남아 있는 시간"이다. 사람이 앞에 있을 때 15초씩 미루면 아끼는 건 깨어남 몇 번뿐이고
     잃는 건 기능 그 자체다. 그래서 물러서는 건 **아무 일도 없을 때만**이다.
     */
    /// 대조 루프를 (다시) 시작한다.
    ///
    /// **언제 불려도 안전하다** — 돌고 있던 게 있으면 취소하고 새로 시작한다. 그래서 "지금
    /// 돌고 있나?"를 따로 추적할 필요가 없다. 장부가 비면 루프가 스스로 끝나고, 다음 알림이
    /// 게시될 때([submit]) 여기가 다시 불린다.
    private func startReconciling() {
        reconcileTask?.cancel()
        reconcileTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                // 돌 이유가 남아 있는지 매 바퀴 다시 본다 — 장부가 비면 여기서 끝난다.
                guard let owner = self, owner.active, !owner.posted.isEmpty else { return }
                try? await Task.sleep(for: owner.interval)
                guard !Task.isCancelled else { return }
                await self?.reconcileCleared()
            }
        }
    }

    /// 지금 얼마 만에 한 번 볼까.
    private var interval: Duration {
        Date().timeIntervalSince(lastTouch) < Self.attentiveSeconds
            ? Self.fastInterval : Self.idleInterval
    }

    /// 사람이 알림을 만졌다 — 이제부터 한동안 빠른 주기로 본다.
    private func noteActivity() { lastTouch = Date() }

    /**
     사람이 자리로 돌아왔다 — **곧장 한 번 대조한다.**

     화면이 꺼져 있거나 잠들어 있던 동안에는 알림 센터를 만질 수 없었으니 대조할 것도 없었다.
     돌아온 그 순간이 가장 중요하다: 자리를 비운 사이 쌓인 것을 사용자가 한 번에 치우기
     직전이다.
     */
    private func observeReturns() {
        let ws = NSWorkspace.shared.notificationCenter
        let pairs: [(NotificationCenter, Notification.Name)] = [
            (ws, NSWorkspace.screensDidWakeNotification),
            (ws, NSWorkspace.didWakeNotification),
            (ws, NSWorkspace.sessionDidBecomeActiveNotification),
            (.default, NSApplication.didBecomeActiveNotification),
        ]
        for (center, name) in pairs {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { _ in
                Task { @MainActor [weak self] in
                    guard let self, self.active else { return }
                    self.noteActivity()
                    await self.reconcileCleared()
                }
            })
        }
    }

    private func forgetReturns() {
        let ws = NSWorkspace.shared.notificationCenter
        for o in observers {
            ws.removeObserver(o)
            NotificationCenter.default.removeObserver(o)
        }
        observers.removeAll()
    }

    private func reconcileCleared() async {
        guard !posted.isEmpty else { return }
        let delivered = Set(await center.deliveredNotifications().map(\.request.identifier))
        let now = Date()
        // 갓 띄운 것은 건너뛴다 — 시스템 목록에 반영되기 전이라 "사라졌다"로 보인다.
        let vanished = posted.filter { key, at in
            !delivered.contains(key) && now.timeIntervalSince(at) > Self.settleSeconds
        }
        guard !vanished.isEmpty else { return }

        for (key, _) in vanished {
            posted.removeValue(forKey: key)
            live.removeAll { $0.id == key }
            send(.dismiss, key: key)
        }
        log.info("알림 센터에서 \(vanished.count)개 치워짐 → 기기에도 반영")
        // 하나를 치웠으면 더 치울 참이다 — 정리하는 동안은 빠르게 따라간다.
        noteActivity()
        objectWillChange.send()
    }

    /// 띄운 직후 이만큼은 대조하지 않는다.
    private static let settleSeconds: TimeInterval = 3

    /// 사람이 만지는 중일 때의 주기. 사람이 지우는 속도보다 빠를 필요는 없다.
    private static let fastInterval: Duration = .seconds(2)
    /// 아무 일도 없을 때의 주기. 이 값이 곧 "Mac에서 지웠는데 폰에 남아 있는 최대 시간"이라
    /// 무작정 늘리지 않는다.
    private static let idleInterval: Duration = .seconds(10)
    /// 마지막 손길로부터 이만큼은 빠른 주기를 유지한다.
    private static let attentiveSeconds: TimeInterval = 60

    // MARK: - Mac 알림

    private func requestAuthorization() async {
        let ok = (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
        authorized = ok
        if !ok { log.error("Mac 알림 권한 없음 — 목록에는 쌓이지만 배너는 안 뜬다") }
        objectWillChange.send()
    }

    /**
     Mac 알림을 띄운다. `note`가 있으면 부제를 **덮어쓴다** — "왜 안 열렸는지"가 앱이 붙인
     문맥보다 급하다.

     **경로가 하나다.** 예전에 대화 알림만 통신 알림(`INSendMessageIntent`)으로 올려 왼쪽 큰
     자리에 얼굴을 넣어 봤지만 되돌렸다 — 제목이 인텐트에 묶이고 **부제가 통째로 사라져서**,
     그림 하나를 왼쪽으로 옮기는 대가로 글자 자리를 내주는 거래였다. 자세한 건
     `NotifyMapper.artwork`의 주석에 적어 뒀다.

     그래서 제목·부제·본문은 우리가 정한 그대로 나가고, 그림은 첨부 한 장에 사진과 앱
     아이콘을 겹쳐 담는다.
     */
    private func banner(_ item: PeriNotification, note: String? = nil,
                        silent: Bool = false) {
        guard authorized else { return }
        submit(compose(item, note: note, silent: silent), key: item.id)
    }

    /// 알림 내용을 짓는다.
    private func compose(_ item: PeriNotification, note: String?,
                         silent: Bool) -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = item.title
        content.body = item.body
        let sub = note ?? item.subtitle
        if !sub.isEmpty { content.subtitle = sub }
        // 소리를 안 내는 두 경우:
        //  - 폰에서도 조용히 뜨는 알림(`item.silent`). 채널 중요도를 낮춰 둔 건 "알려는 주되
        //    방해하지는 말라"는 뜻이고, 그 뜻은 화면이 바뀐다고 달라지지 않는다.
        //  - **되살리는 배너**(`silent`). 이미 한 번 울린 알림이고, 사용자는 방금 그걸 눌러
        //    화면을 보고 있는 중이다. 같은 알림으로 두 번 울리는 건 알림이 아니라 소음이다.
        content.sound = (item.silent || silent) ? nil : .default
        content.categoryIdentifier = item.canReply ? Self.categoryReply : Self.categoryPlain
        // 기기에서 한 묶음이던 알림은 여기서도 한 묶음이 된다. Android의 알림 그룹과
        // macOS의 `threadIdentifier`가 같은 개념이라 값만 옮기면 그대로 통한다.
        //
        // ⚠ **시스템 설정에 매인다.** 시스템 설정 → 알림 → Peri Display의 '알림 그룹화'가
        // '자동'일 때만 이 값이 쓰인다. '앱별'이면 우리 알림 전부가 한 묶음이 되고,
        // '끔'이면 묶이지 않는다. 기본값이 '자동'이라 대개는 의도대로 동작한다.
        if !item.groupKey.isEmpty { content.threadIdentifier = item.groupKey }
        // 어떤 알림인지 되돌아오는 길 — 사용자가 누르면 이 값으로 폰에 지시를 보낸다.
        content.userInfo = ["key": item.id, "reply": Int(item.replyAction)]

        // 보낸 앱의 아이콘을 붙인다. **헤더 아이콘은 못 바꾼다** — macOS 알림의 그 자리는
        // 언제나 띄운 앱(우리)의 것이라, 폰 앱을 알아보게 하려면 첨부로 얹는 수밖에 없다.
        if let url = Self.cacheIcon(item.iconPNG, badge: item.badgePNG, key: item.id),
           let att = try? UNNotificationAttachment(identifier: "icon", url: url) {
            content.attachments = [att]
        }
        return content
    }

    /// 알림 센터에 넘긴다.
    ///
    /// ★ 장부에는 **실제로 게시된 뒤에만** 올린다.
    ///
    /// 미리 올려 두면 게시가 실패했을 때 그 key가 장부에는 있고 알림 센터에는 없는
    /// 상태가 되고, 3초 뒤 [reconcileCleared]가 그걸 "사용자가 치웠다"로 읽어
    /// **폰의 멀쩡한 알림을 지운다.** 실패를 지우기로 바꾸는 셈이라 반드시 순서를 지킨다.
    private func submit(_ content: UNNotificationContent, key: String) {
        let req = UNNotificationRequest(identifier: key, content: content, trigger: nil)
        center.add(req) { err in
            if let err {
                log.error("Mac 알림 실패: \(err.localizedDescription)")
                return
            }
            Task { @MainActor [weak self] in
                guard let self else { return }
                // 장부가 **비어 있다가 채워지는** 순간이 대조 루프가 (다시) 돌아야 할 순간이다.
                // 비어 있는 동안에는 루프가 스스로 끝나 있다.
                let wasEmpty = self.posted.isEmpty
                self.posted[key] = Date()
                if wasEmpty { self.startReconciling() }
            }
        }
    }

    /// 첨부는 **파일 URL**만 받는다 — 메모리의 이미지를 바로 못 준다. 임시 폴더에 쓴다.
    ///
    /// `UNNotificationAttachment`는 넘긴 파일을 자기 저장소로 **옮긴다**(복사가 아니다).
    /// 그래서 같은 파일을 두 번 첨부할 수 없고, 알림마다 새 파일을 써야 한다.
    private static func cacheIcon(_ png: Data, badge: Data, key: String) -> URL? {
        guard !png.isEmpty else { return nil }
        // 겹치기가 실패해도 알림은 떠야 한다 — 바탕만이라도 붙인다.
        let art = badge.isEmpty ? png : (composite(png, badge: badge) ?? png)
        try? FileManager.default.createDirectory(at: iconDir, withIntermediateDirectories: true)
        // key에 `/`·`:` 같은 게 섞여 있다(패키지|id|사용자 형태) → 파일명으로 못 쓴다.
        // `abs(hashValue)`를 쓰면 안 된다 — 값이 `Int.min`이면 abs가 **트랩한다**.
        // 비트패턴을 그대로 부호 없는 수로 읽으면 그 경계가 아예 없다.
        let stem = String(UInt(bitPattern: key.hashValue), radix: 36)
        let url = iconDir.appendingPathComponent("\(stem)-\(UInt32.random(in: 0...UInt32.max)).png")
        guard (try? art.write(to: url)) != nil else { return nil }
        return url
    }

    /**
     바탕 그림 **오른쪽 아래에 앱 아이콘을 얹는다.**

     왜 필요한가: macOS 알림의 **헤더 아이콘은 언제나 우리 앱(Peri Display) 것**이라 바꿀 수
     없고, 붙일 수 있는 그림은 첨부 한 장뿐이다. 대화 알림에서 그 한 장은 방 아이콘이나 상대
     얼굴이 가장 유용한데, 그걸 쓰면 **어느 앱에서 온 알림인지가 사라진다** — 얼굴만 보고
     카톡인지 인스타인지 텔레그램인지 알 수 없다.

     그래서 겹친다. 폰의 알림 그늘이 프로필 사진 모서리에 작은 앱 아이콘을 다는 것과 같다.

     흰 받침을 까는 이유: 어두운 프로필 사진 위에 어두운 앱 아이콘이 얹히면 형체가 안 보인다.
     모서리에서 조금 띄우는 이유: 알림 썸네일은 둥근 모서리로 잘리는데, 배지가 딱 붙어 있으면
     그 곡선에 깎인다.
     */
    private static func composite(_ base: Data, badge: Data) -> Data? {
        guard let art = NSImage(data: base), let mark = NSImage(data: badge),
              let rep = NSBitmapImageRep(
                bitmapDataPlanes: nil, pixelsWide: Int(side), pixelsHigh: Int(side),
                bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0),
              let ctx = NSGraphicsContext(bitmapImageRep: rep)
        else { return nil }
        rep.size = NSSize(width: side, height: side)

        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = ctx
        ctx.imageInterpolation = .high

        // 바탕을 **왼쪽 위로 물린다.** 캔버스를 꽉 채우면 오른쪽 아래 배지가 사진 한가운데를
        // 덮는다(예전엔 배지가 통째로 사진 위에 앉았다). 조금 물러서면 배지가 대부분 사진
        // **밖**으로 나가고, 겹치는 건 모서리 한 귀퉁이뿐이다.
        //
        // AppKit의 원점은 **좌하단**이라 위로 붙이려면 y를 남는 만큼 올린다.
        let baseSide = side * 0.95
        art.draw(in: NSRect(x: 0, y: side - baseSide, width: baseSide, height: baseSide))

        let markSide = side * 0.36
        let inset = side * 0.04
        let frame = NSRect(x: side - markSide - inset, y: inset,
                           width: markSide, height: markSide)
        let plate = frame.insetBy(dx: -side * 0.02, dy: -side * 0.02)
        NSColor.white.setFill()
        NSBezierPath(roundedRect: plate,
                     xRadius: plate.width * 0.26, yRadius: plate.width * 0.26).fill()
        mark.draw(in: frame)

        NSGraphicsContext.restoreGraphicsState()
        return rep.representation(using: .png, properties: [:])
    }

    /// 겹쳐 만든 그림의 한 변(px). 폰이 보내는 사진(96px)보다 크게 잡아 배지가 뭉개지지
    /// 않을 자리를 준다 — Retina에서 이 썸네일은 실제로 그만큼 그려진다.
    private static let side: CGFloat = 128

    /// 첨부용 아이콘 임시 폴더. `UNNotificationAttachment`가 성공하면 파일을 **가져가지만**,
    /// 실패한 경우엔 여기 남는다 — 앱을 끌 때 통째로 비운다.
    private static let iconDir = FileManager.default.temporaryDirectory
        .appendingPathComponent("peri-notify-icons", isDirectory: true)

    /// 우리가 치운다 — **장부에서 먼저 뺀다.** 그래야 [reconcileCleared]가 이걸 "사용자가
    /// 알림 센터에서 치웠다"로 보고 폰에 같은 지시를 한 번 더 보내지 않는다.
    private func withdraw(_ key: String) {
        guard posted.removeValue(forKey: key) != nil else { return }
        center.removeDeliveredNotifications(withIdentifiers: [key])
    }

    // MARK: - 조작 (Mac → 폰)

    /**
     눌렀다 → 폰에서 그 앱을 연다. **여기서는 아무것도 지우지 않는다.**

     지우는 순서가 이 기능의 핵심이다:

         Mac 클릭 → 열기 요청 → 폰에서 실제로 열림 → 폰이 알림을 취소
                 → `NotificationRemoved` → Mac에서 사라짐

     즉 **지우는 근거가 "눌렀다"가 아니라 "실제로 열렸다"** 이고, 지우는 주체는 폰이다.
     반대로 Mac이 먼저 지우고 폰이 따라오게 하면, 열기가 막혔을 때(백그라운드 시작 차단)
     알림만 사라지고 앱은 안 열린 채로 끝난다 — 아무 흔적도 안 남는 최악의 실패다.

     ★ `posted`에서 빼는 건 지우는 게 아니다. macOS는 사용자가 알림을 누른 순간 그걸 알림
     센터에서 걷어내는데, 그 사실을 장부에 반영해 두지 않으면 [reconcileCleared]가 2초 뒤
     "사용자가 치웠다"로 읽고 **폰에 지우기를 보낸다** — 위에서 막으려던 바로 그 순서가
     뒷문으로 되살아난다. `live`에는 그대로 남는다(폰에는 아직 있으니까).
     */
    func open(_ key: String) {
        posted.removeValue(forKey: key)
        noteActivity()
        send(.open, key: key)
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(Self.openConfirmSeconds))
            self?.confirmOpened(key)
        }
    }

    /**
     열기가 정말 됐는지 확인한다.

     성공했으면 폰이 알림을 취소했을 것이고, 그 취소가 `NotificationRemoved`로 돌아와
     [live]에서 빠졌을 것이다. **아직 남아 있다면 열기가 막힌 것이다** — Android의
     백그라운드 시작 제한이 실기기에서 실제로 막았던 그 경로다.

     그때 배너를 되살린다. 안 그러면 최악의 상태가 된다: macOS는 누른 순간 알림을 걷어냈고,
     폰은 그대로 들고 있고, 사용자는 **아무 일도 안 일어난 채 알림만 잃는다.**

     `clearable`만 되살리는 이유: 음악 재생처럼 지울 수 없는 알림은 열기가 성공해도 폰에
     그대로 남으므로, 여기서 "실패했다"로 읽으면 배너가 영영 되살아난다.
     */
    private func confirmOpened(_ key: String) {
        guard active,
              let item = live.first(where: { $0.id == key }),
              item.clearable, !item.ongoing
        else { return }
        log.notice("열기 확인 실패 — 기기가 알림을 아직 들고 있다. 배너 복구")
        // **소리는 안 낸다.** 이건 새 소식이 아니라 아까 그 알림을 되돌려 놓는 것이고,
        // 사용자는 4초 전에 그걸 눌러 화면을 보고 있다.
        banner(item, silent: true)
    }

    /// 열기를 청한 뒤 이만큼 기다려 본다. 폰이 열고 → 취소하고 → 그 사실이 되돌아오는
    /// 왕복 시간이라 넉넉해야 하지만, 길면 "눌렀는데 반응 없음"이 그만큼 길어진다.
    private static let openConfirmSeconds: TimeInterval = 4

    /// 지운다 → **폰에서도** 지운다. Mac에서만 지우면 폰에 그대로 남아 두 번 본다.
    ///
    /// 여기가 대조 루프의 가장 좋은 힌트다: 하나를 지웠다는 건 **정리하는 중**이라는 뜻이고,
    /// '모두 지우기'가 곧 이어질 수 있다. 그래서 이 순간부터 빠른 주기로 돌아간다.
    func dismiss(_ key: String) {
        noteActivity()
        send(.dismiss, key: key)
        live.removeAll { $0.id == key }
        withdraw(key)
        objectWillChange.send()
    }

    /// 답장. **어느 액션에 실을지도 같이 보낸다** — 액션을 고른 건 우리다([NotifyMapper]).
    ///
    /// 기기가 그 번호를 그대로 믿지는 않는다. 보내고 받는 사이에 앱이 알림을 갱신해 순서가
    /// 바뀌었을 수 있어서, 지금 떠 있는 알림에 그 자리가 입력란이 맞는지 보고 아니면 다시
    /// 찾는다. 그래서 0(= 골라 달라)을 보내도 동작은 한다.
    func reply(_ key: String, text: String) {
        guard !text.isEmpty else { return }
        let index = live.first { $0.id == key }?.replyAction ?? 0
        send(.reply, key: key, text: text, actionIndex: index)
    }

    private func send(_ kind: PeriNotificationAction.Kind, key: String,
                      text: String = "", actionIndex: UInt32 = 0) {
        guard let channel else { return }
        var a = PeriNotificationAction()
        a.key = key
        a.kind = kind
        a.text = text
        a.actionIndex = actionIndex
        var m = PeriControlMessage(); m.notificationAction = a
        channel.sendReliable(m)
    }

    /// 지금 떠 있는 것을 전부 다시 보내 달라.
    private func requestSync() {
        guard let channel else { return }
        var m = PeriControlMessage(); m.notificationSync = PeriNotificationSync()
        channel.sendReliable(m)
    }

    // MARK: - 알림 카테고리

    static let categoryPlain = "peri.notify.plain"
    static let categoryReply = "peri.notify.reply"
    static let actionDismiss = "peri.notify.dismiss"
    static let actionReply = "peri.notify.reply.text"

    /// 알림에 붙는 버튼들. 앱 시작 시 한 번 등록한다 — 카테고리는 프로세스 전역이라
    /// 서비스마다 등록하면 마지막 것만 남는다.
    ///
    /// ★ `.customDismissAction`이 **닫기(X) 버튼의 전부다.**
    ///
    /// 이 옵션이 없으면 macOS는 사용자가 X를 눌러도 앱에 아무것도 알리지 않는다 — 알림만
    /// 조용히 사라진다. 그래서 '지우기' 버튼은 폰까지 지웠는데 X는 Mac에서만 사라지고
    /// 폰에는 그대로 남았다. 두 동작이 같은 뜻인데 결과가 달랐던 이유가 이 한 줄이다.
    static func registerCategories() {
        let dismiss = UNNotificationAction(identifier: actionDismiss, title: "지우기",
                                           options: [])
        let reply = UNTextInputNotificationAction(identifier: actionReply, title: "답장",
                                                  options: [],
                                                  textInputButtonTitle: "보내기",
                                                  textInputPlaceholder: "메시지")
        UNUserNotificationCenter.current().setNotificationCategories([
            UNNotificationCategory(identifier: categoryPlain, actions: [dismiss],
                                   intentIdentifiers: [], options: [.customDismissAction]),
            UNNotificationCategory(identifier: categoryReply, actions: [reply, dismiss],
                                   intentIdentifiers: [], options: [.customDismissAction]),
        ])
    }
}
