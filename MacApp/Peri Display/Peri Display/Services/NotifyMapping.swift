//
//  NotifyMapping.swift
//  Peri Display
//
//  **안드로이드 알림 → Mac 알림.** 이 파일이 그 번역의 전부다.
//
//  왜 따로 있나
//  ───────────
//  예전에는 기기가 정제해서 보냈다. 스타일을 골라 한 줄 목록으로 펴고, 빈 텍스트를 "[사진]"
//  으로 메우고, 그림 셋 중 하나를 고르고, 볼 것 없는 알림을 걸렀다. 그러면 표시를 바꿀 때마다
//  **안드로이드 코드를 고치고 프로토콜을 늘리고 두 쪽을 같이 배포해야** 했다. 대화 제목,
//  발신자 사진, 방 아이콘이 실제로 그 순서로 하나씩 뒤늦게 붙었다.
//
//  지금 기기는 사실만 받아쓴다(`NotificationPosted`). 그 사실 뭉치를 **macOS 알림 하나가
//  요구하는 모양**(제목·부제·본문·그림 한 장·묶음 열쇠)으로 줄이는 게 여기다. 그래서 표시
//  규칙을 바꾸려면 이 파일만 고치면 되고, 기기는 다시 만질 일이 없다.
//
//  이 파일의 규칙: **여기 있는 건 전부 취향이다.** 사실은 하나도 만들지 않는다 — 없는 값을
//  지어내지 않고, 있는 값만 고르고 줄이고 이어 붙인다.
//

import Foundation
import OSLog

private nonisolated let log = Logger(subsystem: "com.peri.display.mac", category: "notify")

// MARK: - 거르기

/// 이 알림을 안 보여주는 이유. **기기가 아니라 여기서 정한다** — 기기가 걸러 버리면
/// 그런 알림이 있었다는 사실조차 Mac에 오지 않아서, 규칙을 바꾸려면 기기부터 고쳐야 한다.
enum NotifySkip: String {
    /// 우리가 폰에 띄운 알림(잠금 해제 요청·공유 중 표시). 되돌리면 Mac이 시킨 일이
    /// Mac에게 알림으로 돌아온다 — 실기기에서 "Mac에서 알림을 열려고 합니다"가 Mac에 떴다.
    case ourOwn = "우리 알림"
    /// 묶음의 머리(`FLAG_GROUP_SUMMARY`). 폰의 그늘에서는 접힌 묶음의 제목 줄이지만, 우리는
    /// 알림을 하나씩 띄우므로 **제목도 본문도 없는 빈 항목**이 앱마다 하나씩 따라붙는다.
    case groupSummary = "묶음 요약"
    /// 제목도 본문도 없다. 띄워 봐야 빈 배너다.
    case empty = "내용 없음"
}

// MARK: - 옮기기

/// 와이어의 안드로이드 알림을 **Mac 알림 하나**로 옮긴다.
///
/// 전부 `static` 인 이유: 이 변환에는 상태가 없다. 같은 입력이면 언제나 같은 결과라야
/// 목록과 배너가 어긋나지 않는다.
enum NotifyMapper {

    /// 안 보여줄 이유가 있으면 그것을, 없으면 nil.
    static func skip(_ n: PeriNotificationPosted) -> NotifySkip? {
        if n.fromPeri { return .ourOwn }
        if n.groupSummary { return .groupSummary }
        if heading(n).isEmpty && body(n).isEmpty { return .empty }
        return nil
    }

    /// 보여줄 모양으로 줄인다. [skip]이 nil을 준 알림에만 부른다.
    static func map(_ n: PeriNotificationPosted, fingerprint: String) -> PeriNotification {
        let label = n.appLabel.isEmpty ? n.packageName : n.appLabel
        let head = heading(n)
        let art = artwork(n)
        return PeriNotification(
            id: n.key,
            appLabel: label,
            // 제목이 없으면 앱 이름으로 대신한다 — 그것마저 없으면 빈 알림이 뜬다.
            title: head.isEmpty ? label : head,
            subtitle: subtitle(n, heading: head),
            body: body(n),
            postedAt: postedAt(n),
            ongoing: n.ongoing,
            clearable: n.clearable,
            replyAction: replyAction(n),
            iconPNG: art.main,
            badgePNG: art.badge,
            groupKey: groupKey(n, fingerprint: fingerprint),
            silent: n.importance > 0 && n.importance < Self.importanceDefault)
    }

    // MARK: 시각

    /// 언제 온 알림인가.
    ///
    /// `when`(앱이 밝힌 사건 시각)을 먼저 쓴다 — 메신저는 여기에 **메시지가 온 시각**을 넣고,
    /// 그게 사용자가 기대하는 값이다. 앱이 안 밝혔으면(0) 시스템이 받은 시각으로 물러선다.
    ///
    /// 미래 시각은 버린다. 폰의 시계가 앞서 있으면 목록이 영영 맨 위에 붙박이는데, 그건
    /// 시각을 표시하는 것보다 나쁘다.
    private static func postedAt(_ n: PeriNotificationPosted) -> Date {
        let now = Date()
        for millis in [n.whenMillis, n.postedAtMillis] where millis > 0 {
            let t = Date(timeIntervalSince1970: Double(millis) / 1000)
            if t <= now.addingTimeInterval(60) { return t }
        }
        return now
    }

    // MARK: 제목

    /**
     알림의 제목 — **어느 방인지**가 먼저다.

     단톡방에서 사용자가 찾는 축은 방 이름이다. `title`에는 앱이 원하는 아무 문구나 들어가고
     카카오톡은 거기에 **마지막 발신자**를 넣는다 — 그것만 보고 있으면 같은 방의 알림이
     말한 사람이 바뀔 때마다 다른 제목으로 뜬다. 그 이름은 [subtitle]로 내려간다.

     방 이름을 찾는 순서:
      ① `conversationTitle` — 정석이다. 이걸 채운 앱은 여기서 끝난다.
      ② `subText` — **단톡방일 때만.** 카카오톡이 방 이름을 여기 넣는다.
      ③ `title` — 둘 다 없으면 앱이 고른 제목을 그대로 쓴다.

     ⚠ **②를 대화 밖으로 내보내면 안 된다.** `subText`는 앱마다 뜻이 다르다:
     카카오톡은 *방 이름*을 넣지만 토스는 *브랜드명*("토스증권"·"토스뱅크")을, Gmail은
     *계정 주소*를 넣는다. 이걸 제목 우선순위로 전역에 올리면 토스 알림의 진짜 제목
     ("주식 주문 체결 완료")이 "토스증권"에 덮인다 — 실제로 그렇게 한 번 망가뜨렸다.
     그래서 ②는 `hasMessaging`이고 [isGroup]인 가지 안에만 있다.

     1:1 대화에서는 `conversationTitle`이 대개 비어 있다. 안드로이드가 이 필드의 유무를
     "그룹인가"의 힌트로 쓰기 때문에 앱들이 일부러 안 채운다 — 그때는 `title`이 곧 상대다.
     */
    private static func heading(_ n: PeriNotificationPosted) -> String {
        let t = n.text
        if n.hasStyle, n.style.hasMessaging {
            let m = n.style.messaging
            let room = m.conversationTitle.trimmed
            if !room.isEmpty { return room }
            // 방 이름을 안 밝힌 앱 — **단톡방일 때만** `subText`로 물러선다. 위 ⚠ 참조.
            if isGroup(m) {
                let sub = t.subText.trimmed
                if !sub.isEmpty { return sub }
            }
        }
        return t.title.trimmed.orIfEmpty(t.titleBig.trimmed)
    }

    /**
     부제 — **제목이 가져가지 않은 쪽.**

     대화형이면 앱이 고른 `title`이 온다. 단톡방에서 그건 대개 **마지막으로 말한 사람**이고,
     방 이름이 제목을 차지했으니 그 이름은 이 자리로 내려온다. 방 이름을 못 찾아서 `title`이
     이미 제목이 됐다면 같은 문구라 안 쓴다.

     대화가 아니면 `subText`가 온다 — Gmail은 계정 주소, 토스는 "토스증권", 뉴스 앱은
     섹션을 넣는다. 전부 "제목만으로는 모자란 문맥"이라 부제로 딱 맞는다.

     제목과 같으면 안 쓴다 — 같은 문구가 두 줄 겹치면 본문 볼 자리만 줄어든다.

     (조작 실패 사유는 이 자리를 덮어쓴다. `NotifyService.banner(_:note:)`를 보라.)
     */
    private static func subtitle(_ n: PeriNotificationPosted, heading: String) -> String {
        let t = n.text
        let candidate = (n.hasStyle && n.style.hasMessaging)
            ? t.title.trimmed
            : t.subText.trimmed
        return candidate == heading ? "" : candidate
    }

    /**
     단톡방인가.

     `isGroupConversation()`을 먼저 믿는다. 하지만 안 밝히는 앱이 많고, 그 값만 보고 있으면
     방 이름을 찾을 기회를 놓친다.

     그래서 못 믿을 때는 **말한 사람이 둘 이상인가**로 판정한다. 이건 기기가 메시지 목록을
     통째로 보내 주기 때문에 셀 수 있는 것이다 — 예전처럼 기기가 한 줄로 정제해 보냈다면
     여기서 알 수 없는 사실이다.

     한 사람만 말한 단톡방은 1:1로 보인다. 그건 방 이름을 못 찾는 것으로 끝나고(제목이
     발신자 이름이 된다) 잘못된 제목을 만들지는 않으므로, 반대 방향으로 틀리는 것보다 낫다.
     */
    private static func isGroup(_ m: PeriMessagingStyle) -> Bool {
        if m.groupConversation { return true }
        var seen = Set<String>()
        for msg in m.messages where !msg.fromMe {
            seen.insert(msg.senderKey.isEmpty ? msg.sender : msg.senderKey)
            if seen.count > 1 { return true }
        }
        return false
    }

    // MARK: 본문

    /**
     알림 본문 — **스타일마다 다르게 읽는다.**

     기기는 어느 틀로 만들어진 알림인지만 알려 준다. 그 틀에서 무엇을 꺼내 어떻게 이을지가
     여기다.

     대화형이 가장 중요하다. `text`에는 **마지막 메시지 하나만** 담기므로, 카카오톡 같은 방에
     6개가 와 있어도 그것만 보면 하나밖에 안 보인다 — 폰에서 펼치면 다 보이는데 Mac에는
     하나만 보이던 이유다. macOS 배너는 두어 줄만 보여주지만 **알림 센터에서 펼치면 전부
     읽힌다** — 폰과 같은 감각이다.
     */
    private static func body(_ n: PeriNotificationPosted) -> String {
        let t = n.text
        if n.hasStyle {
            if n.style.hasMessaging {
                let lines = messageLines(n.style.messaging)
                if !lines.isEmpty { return lines.joined(separator: "\n") }
            }
            if n.style.hasInbox {
                let lines = n.style.inbox.lines.map(\.trimmed).filter { !$0.isEmpty }
                if !lines.isEmpty { return lines.joined(separator: "\n") }
            }
            // 펼친 본문이 있으면 그쪽이 길고 정확하다. 접힌 한 줄은 그걸 줄인 것이다.
            if n.style.kind == .bigText, !t.bigText.trimmed.isEmpty { return t.bigText.trimmed }
        }
        return t.text.trimmed.orIfEmpty(t.bigText.trimmed)
    }

    /**
     대화의 각 줄.

     **단톡방은 두 겹이다:**

         방금 온 내용                  ← 배너에 잘려도 이건 보인다
         김철수: 어제 그거 됐어?        ┐
         이영희: 응 방금 확인함         ├ 펼치면 최근 대화가 이어 붙는다
         김철수: 방금 온 내용           ┘

     macOS 배너는 두어 줄만 보여주고 나머지는 알림 센터에서 펼쳐야 보인다. 그래서 **가장
     급한 한 줄**(방금 온 내용)을 맨 위에 따로 두고, 그 아래에 문맥을 깐다. 첫 줄에 이름을
     안 붙이는 건 그게 이미 부제에 있기 때문이고, 덕분에 기록과 눈으로도 구분된다.

     메시지가 하나뿐이면 기록을 안 깐다 — 같은 줄이 두 번 나올 뿐이다.

     **1:1은 한 겹이다.** 제목이 곧 상대라 매 줄에 같은 이름이 반복되면 읽기만 나빠지고,
     맨 위에 한 줄을 더 뽑아도 바로 아래와 같은 말이 된다.

     `historic`은 안 쓴다. 앱이 문맥으로 딸려 보내는 **이미 읽은** 대화라, 새 메시지 하나가
     왔는데 지난 스무 줄이 같이 뜨면 무엇이 새 것인지 알 수 없다. 값은 받아 두므로 목록
     화면을 만들 때 쓸 수 있다.
     */
    private static func messageLines(_ m: PeriMessagingStyle) -> [String] {
        // 제목을 정할 때와 **같은 판정**을 쓴다. 여기서만 `groupConversation`을 보면 방
        // 이름이 제목으로 올라간 대화인데도 각 줄에 누가 말했는지가 안 붙는다.
        let group = isGroup(m)
        let msgs = m.messages.compactMap { msg -> (who: String, text: String)? in
            let text = display(msg)
            guard !text.isEmpty else { return nil }
            return (msg.fromMe ? me(m) : msg.sender.trimmed, text)
        }

        guard group else { return msgs.map { $0.text } }
        guard let latest = msgs.last else { return [] }
        guard msgs.count > 1 else { return [latest.text] }

        let history = msgs.suffix(historyLines).map {
            $0.who.isEmpty ? $0.text : "\($0.who): \($0.text)"
        }
        return [latest.text] + history
    }

    /// 단톡방 본문에 깔 기록 줄 수.
    ///
    /// 기기는 25개까지 보내 준다(안드로이드가 알림 안에 붙들고 있는 상한). 그걸 다 깔면
    /// 알림 센터에서 스크롤을 해야 하고, 정작 새로 온 것을 찾기가 나빠진다 — 문맥을 짚을
    /// 만큼만 깐다. 전부 보는 건 목록 화면의 몫이다.
    private static let historyLines = 5

    /**
     메시지 한 줄의 표시 문구.

     사진·동영상 메시지는 내용이 `dataUri`에 있고 **본문은 비어 있는 경우가 많다.** 기기는
     그걸 그대로 보낸다(읽을 수 없으니 나를 수도 없다) — 비었으면 여기서 우리 말로 메운다.
     안 그러면 사진을 보냈을 때 Mac에는 아무 줄도 안 뜨고, "메시지가 왔다는데 아무것도 없다"가
     된다.
     */
    private static func display(_ msg: PeriMessagingMessage) -> String {
        let t = msg.text.trimmed
        if !t.isEmpty { return t }
        let mime = msg.dataMimeType
        if mime.hasPrefix("image/") { return "[사진]" }
        if mime.hasPrefix("video/") { return "[동영상]" }
        if mime.hasPrefix("audio/") { return "[음성]" }
        return mime.isEmpty ? "" : "[첨부]"
    }

    /// '나'를 뭐라고 부를까. 앱이 이름을 밝혔으면 그걸 쓰고, 아니면 우리 말로 부른다 —
    /// 기기는 `fromMe`만 세워 보내고 이름은 짓지 않는다.
    private static func me(_ m: PeriMessagingStyle) -> String {
        m.userName.trimmed.orIfEmpty("나")
    }

    // MARK: 그림

    /**
     알림에 붙일 그림 — **바탕 한 장과 모서리에 얹을 앱 아이콘.**

     macOS 알림에 그림은 **한 장만** 붙는다(헤더 아이콘은 언제나 우리 앱 것이라 바꿀 수 없다).
     기기는 있는 그림을 다 보내 주므로, 고르는 건 순전히 여기의 선택이다.

     바탕을 고르는 순서:

     ① **큰 아이콘** — 메신저가 방 아이콘이나 1:1 상대 사진을 넣는 자리다. 이게 가장 유용하다.
     ② **마지막으로 말한 사람의 얼굴** — 방 사진이 없는 앱이 많다. 내가 보낸 줄은 건너뛴다:
        내 얼굴을 보려고 알림을 보는 게 아니다.
     ③ **앱 아이콘** — 대화가 아니거나 사진이 하나도 없을 때.
     ④ **작은 아이콘** — 앱 아이콘조차 못 읽었을 때(패키지 가시성). 색 빠진 실루엣이지만
        아무것도 없는 것보다는 어느 앱인지 알아보기 쉽다.

     ★ ①·②가 이기면 **어느 앱에서 왔는지가 사라진다** — 얼굴만 보고 카톡인지 인스타인지
     텔레그램인지 알 수 없다. 그래서 그때만 앱 아이콘을 배지로 딸려 보낸다. 폰의 알림 그늘이
     하는 것과 같다. ③·④는 바탕이 이미 앱 아이콘이라 배지가 없다.

     ─────────────────────────────────────────────────────────────────────────
     ★ 왼쪽 큰 자리(헤더)를 사진으로 바꾸는 길이 **하나 있었고, 안 쓰기로 했다.**

     `INSendMessageIntent`를 얹는 **통신 알림**이다. 실기기에서 동작까지 확인했지만 되돌렸다 —
     대가가 글자 자리였기 때문이다:

       - 제목이 `INPerson` 이름에 묶인다. `content.title`을 되돌려도 표시 시점에 무시된다.
       - **부제가 사라진다.** 마지막 발신자를 놓을 자리가 없어진다.
       - 사진 자리도 하나뿐이다 — `setImage(_:forParameterNamed:)`가 Swift/macOS에서 막혀서
         방 사진과 발신자 얼굴을 함께 못 준다.

     유일한 실질 이득인 집중 모드 '사람 허용'은 시스템 연락처와 **매칭될 때** 성립한다.
     안드로이드에서 온 이름을 Mac 주소록에 붙일 방법이 없으므로(설령 `contactIdentifier`를
     들고 와도 두 주소록이 같은 사람을 가리킨다는 보장이 없다) 그 이득은 우리에게 0이다.

     그래서 그림 한 장을 **자유롭게 그리는** 쪽이 낫다. 여기서 그리는 한 장은 원하면
     발신자 얼굴까지 더 얹을 수 있다 — 통신 알림이 구조적으로 못 하는 일이다.
     */
    private static func artwork(_ n: PeriNotificationPosted) -> (main: Data, badge: Data) {
        let img = n.images
        // 배지로 쓸 앱 표식. 앱 아이콘이 먼저고, 못 읽었으면 실루엣이라도 낫다.
        let mark = img.appIconPng.isEmpty ? img.smallIconPng : img.appIconPng

        if !img.largeIconPng.isEmpty { return (img.largeIconPng, mark) }

        if n.hasStyle, n.style.hasMessaging {
            let m = n.style.messaging
            if let last = m.messages.last(where: { !$0.fromMe && !$0.sender.isEmpty }),
               let who = person(last, in: m), !who.iconPng.isEmpty {
                return (who.iconPng, mark)
            }
        }
        return (mark, Data())
    }

    /// 줄이 가리키는 사람. `senderKey`가 정확한 축이다 — 단톡방에는 **이름이 같은 두 사람**이
    /// 있을 수 있고, 그때 이름으로만 찾으면 엉뚱한 얼굴이 뜬다. 키가 없으면 이름으로 물러선다.
    private static func person(_ msg: PeriMessagingMessage,
                               in m: PeriMessagingStyle) -> PeriNotificationPerson? {
        if !msg.senderKey.isEmpty,
           let hit = m.people.first(where: { $0.key == msg.senderKey }) { return hit }
        return m.people.first { $0.name == msg.sender }
    }

    // MARK: 묶음

    /**
     Mac 알림 센터에서 한 묶음으로 접힐 열쇠 (`threadIdentifier`).

     기기는 앱이 붙인 묶음 이름(`group`)을 **가공하지 않고** 보낸다. 엮는 건 여기다:

      - **패키지 이름을 붙인다.** 그룹 이름은 앱이 자기 안에서만 유일하게 짓는다. 두 앱이
        나란히 "chat"을 쓰면 그대로 옮겼을 때 서로 다른 앱의 알림이 한 묶음이 된다.
      - **지문을 붙인다.** 폰이 두 대면 둘 다 `com.instagram.android`를 보내는데, 그대로 쓰면
        서로 다른 기기의 알림이 한 묶음으로 접힌다. 알림 본문에 어느 폰인지 적히지도 않으므로
        그러면 구분할 방법이 아예 없어진다.
      - 그룹이 없으면 **패키지까지만.** 그러면 앱 단위로 묶이는데, 그게 없는 것보다 낫고 대개
        사용자가 기대하는 모양이다.
     */
    private static func groupKey(_ n: PeriNotificationPosted, fingerprint: String) -> String {
        let base = fingerprint + "|" + n.packageName
        return n.group.isEmpty ? base : base + "|" + n.group
    }

    // MARK: 액션

    /**
     답장할 수 있는 액션의 **번호 + 1** (0 = 답장 불가).

     기기가 액션을 전부 보내 주므로 고르는 건 여기다. 첫 번째 입력란을 쓴다 — 답장 액션이
     둘 이상인 알림은 사실상 없고, 있더라도 첫째가 기본이다.

     +1인 이유: 0을 "없음"으로 쓰려면 유효한 액션 번호 0과 겹치면 안 된다.

     ★ 나머지 액션('보관'·'읽음 표시')도 와이어에는 다 와 있다. 지금 안 쓰는 건 macOS 알림의
     버튼이 **카테고리 단위로 미리 등록**되는 물건이라, 알림마다 다른 버튼을 붙이려면 액션
     조합마다 카테고리를 만들어야 하기 때문이다. 알림 목록 화면을 만들 때 거기서 쓴다.
     */
    private static func replyAction(_ n: PeriNotificationPosted) -> UInt32 {
        guard let a = n.actions.first(where: { $0.hasRemoteInput_p }) else { return 0 }
        return a.index + 1
    }

    /// `NotificationManager.IMPORTANCE_DEFAULT`. 이보다 낮으면 폰에서도 소리를 안 낸다 —
    /// Mac에서만 울리면 폰을 조용히 해 둔 뜻이 무너진다.
    private static let importanceDefault: Int32 = 3
}

// MARK: - 잔심부름

private extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
    /// 비어 있으면 대신할 값. `??`가 옵셔널에만 되는 자리를 메운다.
    func orIfEmpty(_ other: @autoclosure () -> String) -> String {
        isEmpty ? other() : self
    }
}
