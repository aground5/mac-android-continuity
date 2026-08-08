//
//  NotificationRouter.swift
//  Peri Display
//
//  Mac 알림에서 누른 버튼을 **그 알림을 보낸 기기로** 되돌린다.
//
//  왜 라우터가 따로 필요한가
//  ────────────────────────
//  `UNUserNotificationCenter`는 delegate도 카테고리도 **프로세스에 하나뿐이다.** 기기마다
//  `NotifyService`가 있는데 각자 delegate를 잡으면 마지막에 잡은 것만 살아남고, 두 대를
//  붙여 놓은 상태에서 한쪽 알림에 답장하면 **엉뚱한 폰으로 간다.**
//
//  그래서 전역인 것은 전역에 두고(여기), 기기별인 것은 기기에 둔다(`NotifyService`).
//  누른 알림이 어느 기기 것인지는 **알림 식별자로 되찾는다** — 그 값이 곧 와이어의 key이고,
//  key를 들고 있는 서비스가 그 알림의 주인이다.
//

import Foundation
import OSLog
import UserNotifications

private nonisolated let log = Logger(subsystem: "com.peri.display.mac", category: "notify")

final class NotificationRouter: NSObject, UNUserNotificationCenterDelegate {

    private weak var core: AppCore?

    init(core: AppCore) {
        self.core = core
        super.init()
    }

    /// 앱이 앞에 있어도 배너를 띄운다. 기본값은 "앞에 있으면 안 띄움"인데, 우리 창을 보고
    /// 있는 동안에도 폰 알림은 알려 줘야 한다 — 그게 이 앱의 존재 이유다.
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification) async
        -> UNNotificationPresentationOptions {
        [.banner, .sound, .list]
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse) async {
        let key = response.notification.request.identifier
        guard let apps = await owner(of: key) else {
            log.error("누른 알림의 주인을 못 찾았다 (key=\(key)) — 기기가 이미 떠났나")
            return
        }
        let notify = await apps.notify
        switch response.actionIdentifier {
        case NotifyService.actionDismiss, UNNotificationDismissActionIdentifier:
            // 버튼이든 X든 뜻은 하나다: "치웠다". 폰에서도 치운다 — 안 그러면 폰을 집었을 때
            // 같은 걸 또 본다. X가 여기 오려면 카테고리에 `.customDismissAction`이 필요하다.
            await notify.dismiss(key)

        case NotifyService.actionReply:
            let text = (response as? UNTextInputNotificationResponse)?.userText ?? ""
            await notify.reply(key, text: text)

        case UNNotificationDefaultActionIdentifier:
            await openContinuing(key, apps: apps)

        default:
            break
        }
    }

    /**
     알림 본문을 눌렀다 — **"이걸 이어서 본다"**.

     두 가지를 한다:
      1. 폰에서 그 앱을 연다(`contentIntent`). 이건 언제나 같다 — 예측 가능해야 한다.
      2. 화면 공유가 **이미 돌고 있으면** 그 창을 앞으로 낸다. 그러면 방금 연 화면이
         Mac에 그대로 보이고, 마우스·키보드로 이어서 쓸 수 있다(케이블이 있다면).

     ★ **공유를 켜지는 않는다.** 켜면 폰에 동의 대화상자가 뜨는데, 그걸 누르려면 폰을 집어야
     하고 그 순간 "Mac에서 이어서 본다"는 의미가 사라진다. 클릭 한 번이 상대 기기에 대화상자를
     띄우는 것도 과하다 — 무거운 동작은 사용자가 이름을 보고 고르게 해야 한다.

     그래서 공유가 꺼져 있으면 폰에서 열리기만 한다. 그건 실패가 아니라, 폰을 집었을 때
     원하던 화면이 이미 열려 있는 상태다.
     */
    @MainActor
    private func openContinuing(_ key: String, apps: AppHost) async {
        apps.notify.open(key)
        guard apps.share.isReceiving else { return }
        apps.share.focusWindow()
    }

    /// 이 key를 들고 있는 기기. 알림 앱만이 아니라 **홈 화면 전체**를 돌려준다 —
    /// 클릭이 공유 창까지 건드리므로 한 앱만으로는 부족하다.
    @MainActor
    private func owner(of key: String) -> AppHost? {
        guard let core else { return nil }
        for device in core.devices {
            guard let apps = device.apps else { continue }
            if apps.notify.live.contains(where: { $0.id == key }) { return apps }
        }
        return nil
    }
}
