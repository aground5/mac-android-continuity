package com.peri.display.notify

import android.app.ActivityOptions
import android.app.Notification
import android.app.RemoteInput
import android.os.Build
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import java.io.ByteArrayOutputStream

private const val TAG = "PeriNotify"

/**
 * 알림을 **데이터로** 읽어 Mac으로 넘긴다 (앱 #4).
 *
 * 화면 공유와 무엇이 다른가: 공유는 알림을 *그림*으로 나른다 — 읽을 수만 있고 누를 수도,
 * 답할 수도, 지울 수도 없다. 여기서는 제목·본문·액션이 그대로 오므로 Mac에서 진짜 알림이
 * 되고, 되돌아온 지시로 폰의 앱을 열거나 지우거나 답장할 수 있다.
 *
 * ⚠ **이 서비스는 우리가 켤 수 없다.** `BIND_NOTIFICATION_LISTENER_SERVICE`는 런타임 권한이
 * 아니라 사용자가 설정에서 직접 허용해야 하고(`[NotifyAccess.settingsIntent]`), 허용되면
 * 시스템이 알아서 바인드한다. 그래서 수명이 앱과 다르다 — 앱이 죽어 있어도 이 서비스는
 * 살아 있을 수 있고, 반대도 가능하다.
 *
 * 그 어긋남 때문에 [bridge]는 **프로세스 전역**이다. 서비스가 붙었을 때 컨트롤러가 없으면
 * 조용히 버리고, 컨트롤러가 붙으면 그때부터 흐른다.
 */
class PeriNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        Log.i(TAG, "알림 리스너 연결됨")
        NotifyBridge.listener = this
        NotifyBridge.connected = true
        // 이미 떠 있는 알림들 — 연결 시점에 한 번 밀어 준다.
        if (NotifyBridge.wanted) pushAll()
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "알림 리스너 끊김")
        NotifyBridge.connected = false
        NotifyBridge.listener = null
    }

    /**
     * ★ **거르지 않는다.** 온 것은 다 보낸다.
     *
     * 예전에는 여기서 그룹 요약·빈 알림·우리 자신을 걸렀다. 그러면 Mac은 그런 알림이
     * **있었다는 사실조차** 모른다 — 표시 규칙을 바꾸고 싶어도 원본이 안 왔으니 기기 코드부터
     * 고쳐야 하고, 두 쪽을 같이 배포해야 한다.
     *
     * 지금은 거르는 근거가 될 사실만 싣는다(`groupSummary`, `fromPeri`, 텍스트가 비었는지).
     * 무엇을 안 보여줄지는 Mac이 정한다.
     */
    override fun onNotificationPosted(sbn: StatusBarNotification, ranking: RankingMap?) {
        if (!NotifyBridge.wanted) return
        NotifyBridge.onPosted?.invoke(snapshot(sbn, ranking))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!NotifyBridge.wanted) return
        NotifyBridge.onRemoved?.invoke(sbn.key)
    }

    /** 지금 떠 있는 것 전부 — Mac이 앱을 켤 때/채널이 다시 붙을 때 요청한다. */
    fun pushAll() {
        val all = runCatching { activeNotifications }.getOrNull() ?: return
        val ranking = runCatching { currentRanking }.getOrNull()
        Log.i(TAG, "현재 알림 ${all.size}개 동기화")
        for (sbn in all) NotifyBridge.onPosted?.invoke(snapshot(sbn, ranking))
    }

    // MARK: - 조작

    /**
     * 알림 본문을 눌렀다 → `contentIntent` 실행. 폰에서 그 앱이 열린다.
     *
     * ⚠ **두 가지가 다 있어야 통과한다.** 여기서 두 번 헛짚었으므로 규칙을 적어 둔다
     * (출처: developer.android.com/guide/components/activities/secure-bal):
     *
     *   ① **옵트인** — 발신자가 `setPendingIntentBackgroundActivityStartMode`로 자기 권한을
     *      내주겠다고 밝힌다. Android 14+는 기본이 '안 내줌'이라 이게 없으면 시작도 못 한다.
     *   ② **예외 충족** — 그리고 그 시점에 발신자가 BAL 예외 중 하나를 실제로 만족해야 한다.
     *
     * 처음엔 ①도 없이 맨 `send()`를 불렀고, 그 다음엔 ①만 넣었다. 로그가 `BSP.ALLOW_BAL`
     * (=①은 됐다)인데도 `BAL_BLOCK`이었던 이유가 ②다. 우리는 화면에 창이 없고, IME도
     * 아니고, 시스템이 보낸 PendingIntent도 아니다 — 예외가 하나도 없었다.
     *
     * 우리가 만족시킬 수 있는 예외는 **`SYSTEM_ALERT_WINDOW`(다른 앱 위에 표시)** 하나뿐이다.
     * 사용자가 설정에서 켜야 하고, 안 켜져 있으면 이 호출은 조용히 아무 일도 안 한다
     * (예외도 안 나고 반환값도 없다 — 그래서 Mac 쪽이 4초 뒤 배너를 되살린다).
     */
    fun open(key: String) {
        val sbn = find(key) ?: return
        val intent = sbn.notification.contentIntent
        if (intent == null) {
            Log.i(TAG, "contentIntent 없음 — 열 수 없다 ($key)")
            return
        }
        if (!NotifyAccess.canStartFromBackground(this)) {
            Log.w(TAG, "'다른 앱 위에 표시' 권한 없음 → 열기가 시스템에 막힌다 ($key)")
            NotifyBridge.onActionFailed?.invoke(
                key, FailReason.NOT_PERMITTED,
                "기기에서 '다른 앱 위에 표시'를 허용해야 합니다")
            return
        }

        // ★ **잠금 화면에서는 직접 열지 않는다.**
        //
        // 우리 오버레이 창이 잠금 화면 **아래**에 깔려서 시스템이 "보이는 창"으로 세지 않는다
        // (실기기 로그: 창=true인데 `hasNonAppVisibleWindow: false`). 보내 봐야 막히고,
        // 그런데도 보내면 우리는 그걸 성공으로 착각해 알림을 지운다 — 실제로 그렇게 잃었다.
        //
        // 그렇다고 아무 반응도 없으면 Mac에서는 "눌렀는데 아무 일도 안 일어남"이다.
        // 대신 **폰이 잠금 해제를 청한다** — 사용자가 그 알림을 누르면 시스템이 우리를
        // 실행하고(BAL 예외), 그때는 포그라운드라 앱을 열 수 있다.
        //
        // 잠긴 폰에서 조용히 앱이 열리지 않는다는 성질은 그대로다. 사람 확인이 한 번 들어갈 뿐이다.
        if (isLocked()) {
            Log.i(TAG, "화면이 잠겨 있다 → 잠금 해제를 청한다 ($key)")
            ShareNotifyPrompt.postUnlockRequest(this, key, appLabel(sbn.packageName))
            // Mac 쪽 안내도 폰에서 **실제로 벌어질 일**을 말해야 한다. 화면이 저절로 켜지는지
            // 아니면 알림을 눌러야 하는지는 전체 화면 알림 허용 여부로 갈린다.
            NotifyBridge.onActionFailed?.invoke(
                key, FailReason.LOCKED,
                if (ShareNotifyPrompt.canWakeScreen(this))
                    "폰이 잠겨 있습니다 — 폰 화면을 켰으니 잠금을 해제하세요"
                else
                    "폰이 잠겨 있습니다 — 폰에서 알림을 눌러 잠금을 해제하세요")
            return
        }

        // ① 옵트인. 이것만으로는 부족하지만, 없으면 ②가 있어도 막힌다.
        val opts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else null

        // ② 예외를 실제로 만족시킨다 — **창을 띄우는 동안** 보낸다(아래 주석 참조).
        BalWindow.whileVisible(this) { shown ->
            runCatching {
                if (opts != null) intent.send(this, 0, null, null, null, null, opts)
                else intent.send()
            }
                .onSuccess {
                    Log.i(TAG, "열기 요청 ($key, ${sbn.packageName}, 창=$shown)")
                    // ★ **열렸다고 볼 근거가 있을 때만 지운다.**
                    //
                    // 예전엔 `OnFinished`의 `code=0`을 성공으로 읽었는데, 그건 "액티비티가
                    // 떴다"가 아니라 "PendingIntent가 처리됐다"일 뿐이다. 실행이 막혔는데도
                    // code=0이 와서 알림만 사라졌다 — 앱은 안 열리고 알림도 잃었다.
                    //
                    // 지금 근거는 "BAL 예외를 실제로 만족시켰다"이고, 그건 잠금 확인을
                    // 통과하고 창까지 붙었을 때뿐이다. 아니면 그대로 두고 Mac이 4초 뒤
                    // 배너를 되살린다.
                    if (shown) cancelIfClearable(key)
                    else Log.w(TAG, "BAL 창을 못 띄웠다 → 열기가 막혔을 것이다. 알림 유지 ($key)")
                }
                .onFailure { Log.w(TAG, "열기 실패 ($key): ${it.message}") }
        }
    }

    /**
     * 열림이 성공했다 → 지운다. **지울 수 있는 알림만.**
     *
     * `isClearable`을 보는 이유: 음악 재생·내비처럼 계속 떠 있어야 하는 알림(`ongoing`)은
     * 사용자가 스와이프로도 못 지우는 것들이다. 그걸 우리가 지우면 폰에서 멀쩡히 돌던
     * 표시가 사라진다 — 사용자가 폰에서 탭했을 때와 다른 결과가 된다.
     */
    fun cancelIfClearable(key: String) {
        val sbn = find(key) ?: return
        if (!sbn.isClearable) {
            Log.i(TAG, "지울 수 없는 알림 — 열기만 하고 남긴다 ($key)")
            return
        }
        runCatching { cancelNotification(key) }
            .onSuccess { Log.i(TAG, "열림 확인 → 기기에서 취소 ($key)") }
            .onFailure { Log.w(TAG, "취소 실패: ${it.message}") }
    }

    /** 폰에서 지운다. Mac에서만 지우면 폰에 남아 두 번 보게 된다. */
    fun dismiss(key: String) {
        runCatching { cancelNotification(key) }
            .onFailure { Log.w(TAG, "취소 실패: ${it.message}") }
    }

    /**
     * 답장. `RemoteInput`이 달린 액션을 찾아 그 PendingIntent를 텍스트와 함께 쏜다.
     *
     * 왜 `RemoteInput.addResultsToIntent`인가: 액션의 PendingIntent는 "답장 화면을 열어라"가
     * 아니라 "이 텍스트로 답장해라"를 기대한다. 텍스트를 번들에 실어 보내지 않으면 앱은
     * 빈 답장을 받거나 아무 일도 하지 않는다.
     *
     * [actionIndexPlusOne]은 Mac이 고른 번호 + 1이다(0 = 안 골랐다). **믿되 확인한다** —
     * 보내고 받는 사이에 앱이 알림을 갱신해 액션 순서가 바뀌었을 수 있으므로, 지금 떠 있는
     * 알림에서 그 자리에 정말 입력란이 있는지 보고 아니면 우리가 다시 찾는다.
     */
    fun reply(key: String, actionIndexPlusOne: Int, text: String) {
        val sbn = find(key) ?: return
        val actions = sbn.notification.actions
        if (actions.isNullOrEmpty()) { Log.w(TAG, "액션이 사라졌다 ($key)"); return }

        fun replies(i: Int) = !actions[i].remoteInputs.isNullOrEmpty()
        val chosen = (actionIndexPlusOne - 1).takeIf { it in actions.indices && replies(it) }
            ?: actions.indices.firstOrNull(::replies)
            ?: run { Log.w(TAG, "답장할 수 있는 액션이 없다 ($key)"); return }

        val action = actions[chosen]
        val inputs = action.remoteInputs!!

        val bundle = Bundle()
        for (ri in inputs) bundle.putCharSequence(ri.resultKey, text)
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, bundle)
        // 답장은 백그라운드에서 처리돼야 한다 — 이 플래그가 없으면 앱이 UI를 띄우려 든다.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { action.actionIntent.send(this, 0, intent) }
            .onSuccess { Log.i(TAG, "답장 전송 ($key)") }
            .onFailure { Log.w(TAG, "답장 실패: ${it.message}") }
    }

    /**
     * 잠금 화면이 떠 있는가.
     *
     * `isDeviceLocked`(보안 잠금 여부)가 아니라 `isKeyguardLocked`(잠금 화면이 떠 있는가)를
     * 본다. 우리 오버레이 창을 가리는 건 **화면에 떠 있는 잠금 화면**이지, 그게 PIN을
     * 요구하는지 스와이프인지가 아니다.
     */
    private fun isLocked(): Boolean =
        runCatching {
            getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
        }.getOrDefault(false)

    private fun find(key: String): StatusBarNotification? =
        runCatching { activeNotifications }.getOrNull()?.firstOrNull { it.key == key }

    // MARK: - 스냅샷

    /**
     * 와이어로 나갈 값만 뽑는다 — **받아쓰기다. 해석하지 않는다.**
     *
     * `StatusBarNotification`을 그대로 들고 다니지 않는 이유: 그건 시스템 객체라 수명이
     * 우리 것이 아니고, 전송 큐에 올려 두면 이미 취소된 알림을 붙들고 있게 된다.
     *
     * ★ 여기서 **하지 않는 것**들이 이 함수의 성격을 말한다:
     *   - 빈 칸을 다른 칸으로 메우지 않는다 (`text`가 비어도 `bigText`를 대신 넣지 않는다)
     *   - 그림 셋 중에 고르지 않는다 (앱·작은·큰 아이콘을 다 싣는다)
     *   - 사진 메시지의 빈 본문을 "[사진]"으로 채우지 않는다
     *   - 묶음 이름에 패키지를 엮지 않는다
     *   - 볼 것 없는 알림을 버리지 않는다
     *
     * 전부 **표시 규칙**이고, 표시하는 쪽에서 정해야 고칠 때 한 쪽만 고친다.
     *
     * 우리가 하는 건 셋뿐이다: 읽을 수 없는 것은 못 싣고(다른 앱의 ContentProvider),
     * 크기에 상한을 두고([MAX_LINES]·[MAX_PEOPLE]), Drawable을 PNG로 바꾼다.
     */
    private fun snapshot(sbn: StatusBarNotification, ranking: RankingMap?): NotifySnapshot {
        val n = sbn.notification
        val r = rankingOf(sbn.key, ranking)
        return NotifySnapshot(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel(sbn.packageName),
            postedAtMillis = sbn.postTime,
            whenMillis = n.`when`,
            ongoing = sbn.isOngoing,
            clearable = sbn.isClearable,
            groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            // 어느 쪽이 '나'인지는 기기만 확실히 안다. Mac에 패키지 이름을 상수로 박게 하면
            // 앱 이름을 바꾸는 날 조용히 어긋난다.
            fromPeri = sbn.packageName == packageName,
            isConversation = r != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                             runCatching { r.isConversation }.getOrDefault(false),
            importance = r?.importance ?: 0,
            category = n.category.orEmpty(),
            channelId = n.channelId.orEmpty(),
            group = n.group.orEmpty(),
            sortKey = n.sortKey.orEmpty(),
            color = n.color,
            text = texts(n),
            style = style(n),
            images = images(sbn),
            actions = actions(n),
        )
    }

    /**
     * 이 알림의 순위 정보 — 중요도와 '대화인가'가 여기 있다.
     *
     * `RankingMap`은 알림과 **따로** 온다(`onNotificationPosted`의 두 번째 인자). 동기화
     * 경로에는 그 인자가 없으므로 그때는 지금 것을 물어본다.
     */
    private fun rankingOf(key: String, map: RankingMap?): Ranking? {
        val m = map ?: runCatching { currentRanking }.getOrNull() ?: return null
        val out = Ranking()
        return if (runCatching { m.getRanking(key, out) }.getOrDefault(false)) out else null
    }

    /** `extras`의 텍스트 칸들 — **각각 그대로.** 어느 칸을 쓸지는 받는 쪽이 정한다. */
    private fun texts(n: Notification): NotifyText {
        val x = n.extras
        fun s(k: String) = x.getCharSequence(k)?.toString().orEmpty()
        return NotifyText(
            title = s(Notification.EXTRA_TITLE),
            titleBig = s(Notification.EXTRA_TITLE_BIG),
            text = s(Notification.EXTRA_TEXT),
            bigText = s(Notification.EXTRA_BIG_TEXT),
            subText = s(Notification.EXTRA_SUB_TEXT),
            summaryText = s(Notification.EXTRA_SUMMARY_TEXT),
            infoText = s(Notification.EXTRA_INFO_TEXT),
        )
    }

    /**
     * 어떤 틀로 만들어진 알림인가 — **원본 그대로.**
     *
     * 플랫폼 `Notification.MessagingStyle`에는 추출용 공개 API가 없다(있는 건 hidden이다).
     * AndroidX의 `extractMessagingStyleFromNotification`이 그 자리를 메운다 —
     * `EXTRA_MESSAGES` 번들 배열을 직접 파싱하는 것보다 안전하다. 그 번들의 키는 문서화된
     * 계약이 아니라서 버전마다 흔들릴 수 있다.
     *
     * 그게 실패해도 `EXTRA_TEMPLATE` 문자열은 그대로 실어 보낸다. 우리가 모르는 스타일이
     * 와도 "무엇이었는지"는 남아야 나중에 붙일 수 있다.
     */
    private fun style(n: Notification): NotifyStyle {
        val template = n.extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()

        val messaging = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        }.getOrNull()
        if (messaging != null) {
            return NotifyStyle(NotifyStyleKind.MESSAGING, template, messagingOf(messaging),
                               emptyList())
        }

        // `InboxStyle` — 메일·묶음 알림이 쓰는 여러 줄짜리.
        val inbox = n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.map { it.toString() }.orEmpty()
        if (inbox.isNotEmpty()) {
            return NotifyStyle(NotifyStyleKind.INBOX, template, null, inbox.takeLast(MAX_LINES))
        }

        return NotifyStyle(kindOf(template), template, null, emptyList())
    }

    /**
     * 스타일 클래스 이름 → 우리가 아는 종류.
     *
     * 클래스 이름의 마지막 조각만 본다. 같은 스타일이 플랫폼(`android.app.Notification$…`)과
     * AndroidX(`androidx.core.app.NotificationCompat$…`) 양쪽에서 오기 때문이다.
     */
    private fun kindOf(template: String): NotifyStyleKind =
        when (template.substringAfterLast('$')) {
            "" -> NotifyStyleKind.NONE
            "BigTextStyle" -> NotifyStyleKind.BIG_TEXT
            "BigPictureStyle" -> NotifyStyleKind.BIG_PICTURE
            "InboxStyle" -> NotifyStyleKind.INBOX
            "MessagingStyle" -> NotifyStyleKind.MESSAGING
            "MediaStyle", "DecoratedMediaCustomViewStyle" -> NotifyStyleKind.MEDIA
            "CallStyle" -> NotifyStyleKind.CALL
            "DecoratedCustomViewStyle" -> NotifyStyleKind.DECORATED
            else -> NotifyStyleKind.OTHER
        }

    /**
     * 대화형 알림의 원본.
     *
     * `EXTRA_TEXT`에는 마지막 메시지 하나만 담긴다 — 카카오톡 같은 방에 6개가 와 있어도
     * 그렇다. 폰에서 펼치면 다 보이는 건 `MessagingStyle`이 원본 목록을 따로 들고 있기
     * 때문이고, 여기서 그걸 통째로 꺼낸다.
     */
    private fun messagingOf(s: NotificationCompat.MessagingStyle): NotifyMessaging {
        // 오래된 것부터 자른다 — 최근 대화가 잘리면 볼 이유가 없다.
        val recent = s.messages.takeLast(MAX_LINES)
        val historic = s.historicMessages.takeLast(MAX_LINES)

        // 프로필 사진은 **사람마다 하나씩**. 한 사람이 연달아 말하면 줄마다 붙일 때 같은
        // 사진이 여러 번 나간다 — 알림 하나가 수십 KB가 되고 얻는 건 없다.
        // 줄은 `senderKey`(없으면 `sender`)로 이 목록을 가리킨다.
        val people = LinkedHashMap<String, NotifyPerson>()
        for (m in historic + recent) {
            val p = m.person ?: continue
            val id = p.key.orEmpty().ifEmpty { p.name?.toString().orEmpty() }
            if (id.isEmpty() || people.containsKey(id)) continue
            if (people.size >= MAX_PEOPLE) break
            people[id] = personOf(p)
        }

        return NotifyMessaging(
            conversationTitle = s.conversationTitle?.toString().orEmpty(),
            groupConversation = s.isGroupConversation,
            userName = s.user?.name?.toString().orEmpty(),
            messages = recent.map(::messageOf),
            historic = historic.map(::messageOf),
            people = people.values.toList(),
        )
    }

    /**
     * 메시지 한 줄 — **손대지 않는다.**
     *
     * 사진·동영상 메시지는 `dataUri`에 내용이 있고 `text`는 비어 있는 경우가 많다. 예전에는
     * 여기서 "[사진]"으로 메웠는데, 그건 표시 문구라 표시하는 쪽에 있어야 한다. `mimeType`을
     * 그대로 넘기면 Mac이 자기 말로 쓴다.
     *
     * 사진 자체는 못 나른다. Uri를 읽으려면 그 앱의 ContentProvider 권한이 필요하고, 그건
     * 알림 전달의 범위를 훌쩍 넘는다 — 있다는 사실만 전한다.
     *
     * '나'는 `person`이 null인 것으로 표시된다(문서의 계약이다). 이름을 지어 넣지 않고
     * `fromMe`만 세운다 — 뭐라고 부를지는 받는 쪽의 말이다.
     */
    private fun messageOf(m: NotificationCompat.MessagingStyle.Message) = NotifyMessage(
        text = m.text?.toString().orEmpty(),
        timestampMillis = m.timestamp,
        sender = m.person?.name?.toString().orEmpty(),
        senderKey = m.person?.key.orEmpty(),
        fromMe = m.person == null,
        dataMimeType = m.dataMimeType.orEmpty(),
        dataUri = m.dataUri?.toString().orEmpty(),
    )

    private fun personOf(p: Person) = NotifyPerson(
        name = p.name?.toString().orEmpty(),
        key = p.key.orEmpty(),
        bot = p.isBot,
        important = p.isImportant,
        iconPng = pngOf(PHOTO_PX) { p.icon?.loadDrawable(this) },
    )

    /**
     * 알림에 딸린 그림 **전부** — 고르지 않는다.
     *
     * 예전에는 여기서 한 장을 골랐다("앱 아이콘, 없으면 작은 아이콘"). 그러면 Mac은 그게
     * 무엇이었는지 모른 채 한 장만 받는다 — 색 있는 앱 아이콘과 색 빠진 실루엣을 다르게
     * 다루고 싶어도 방법이 없었다.
     *
     * 큰 아이콘은 메신저가 **방 아이콘 또는 1:1 상대 사진**을 넣는 자리다. 참여자 사진과는
     * 다른 값이라 둘 다 보낸다.
     */
    private fun images(sbn: StatusBarNotification): NotifyImages {
        val n = sbn.notification
        return NotifyImages(
            appIconPng = pngOf { packageManager.getApplicationIcon(sbn.packageName) },
            smallIconPng = pngOf { n.smallIcon?.loadDrawable(this) },
            largeIconPng = pngOf(PHOTO_PX) { n.getLargeIcon()?.loadDrawable(this) },
        )
    }

    /**
     * 알림에 달린 버튼 전부.
     *
     * 예전에는 답장 가능한 첫 액션의 번호 하나만 실었다. 그건 "답장 말고는 안 쓴다"는 판단이
     * 프로토콜에 박힌 것이라, '보관'·'읽음 표시'를 쓰려면 프로토콜부터 고쳐야 했다.
     */
    private fun actions(n: Notification): List<NotifyActionInfo> =
        n.actions?.mapIndexed { i, a ->
            val inputs = a.remoteInputs
            NotifyActionInfo(
                index = i,
                title = a.title?.toString().orEmpty(),
                hasRemoteInput = !inputs.isNullOrEmpty(),
                remoteInputLabel = inputs?.firstOrNull()?.label?.toString().orEmpty(),
                semanticAction = a.semanticAction,
                contextual = a.isContextual,
            )
        }.orEmpty()

    private fun appLabel(pkg: String): String = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrElse {
        // 여기 오면 패키지 가시성이 막힌 것이다(QUERY_ALL_PACKAGES 누락).
        Log.w(TAG, "앱 이름 조회 실패 ($pkg): ${it.message}")
        pkg
    }

    /**
     * 그림 하나를 PNG로. 못 읽으면 **빈 바이트** — 대신할 것을 여기서 고르지 않는다.
     *
     * 실패가 흔하다: 앱 아이콘은 패키지 가시성에 막힐 수 있고, 리소스 아이콘은 그 앱의
     * 리소스를 우리 프로세스에서 여는 것이라 버전이 어긋나면 안 열린다. 그래서 전부
     * `runCatching`으로 감싸고, 없으면 없는 대로 보낸다.
     */
    private fun pngOf(size: Int = ICON_PX, load: () -> Drawable?): ByteArray {
        val d = runCatching(load).getOrNull() ?: return ByteArray(0)
        return runCatching { png(d, size) }.getOrDefault(ByteArray(0))
    }

    private fun png(d: Drawable, size: Int): ByteArray {
        val bmp = if (d is BitmapDrawable && d.bitmap != null) {
            Bitmap.createScaledBitmap(d.bitmap, size, size, true)
        } else {
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
                val c = Canvas(it)
                d.setBounds(0, 0, size, size)
                d.draw(c)
            }
        }
        return ByteArrayOutputStream()
            .also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}

/**
 * 와이어로 나갈 알림 하나. 시스템 객체를 붙들지 않기 위한 값 타입이고, **와이어의
 * `NotificationPosted`와 1:1이다.**
 *
 * 값 하나하나가 전부 **기기만 아는 사실**이다 — 판단은 하나도 들어 있지 않다.
 */
data class NotifySnapshot(
    val key: String,
    val packageName: String,
    val appLabel: String,
    /** 시스템이 이 알림을 받은 때. */
    val postedAtMillis: Long,
    /** 앱이 "사건이 일어난 때"라고 밝힌 값(`Notification.when`). 0 = 안 밝혔다. */
    val whenMillis: Long,
    val ongoing: Boolean,
    val clearable: Boolean,
    /** 묶음의 머리(`FLAG_GROUP_SUMMARY`). 대개 내용이 없다 — 거를지는 받는 쪽이 정한다. */
    val groupSummary: Boolean,
    /** 우리가 띄운 알림인가. 되돌리면 Mac이 시킨 일이 Mac에게 알림으로 돌아온다. */
    val fromPeri: Boolean,
    /** 시스템이 대화형으로 분류했는가(`Ranking.isConversation()`, API 30+). */
    val isConversation: Boolean,
    /** 채널 중요도(`NotificationManager.IMPORTANCE_*`). */
    val importance: Int,
    /** `Notification.category` — "msg"/"call"/"alarm" 등. */
    val category: String,
    val channelId: String,
    /** 앱이 붙인 묶음 이름 — **가공하지 않은 원본.** 엮는 건 받는 쪽 몫이다. */
    val group: String,
    val sortKey: String,
    /** 앱이 지정한 강조색(ARGB). 0 = 안 지정. */
    val color: Int,
    val text: NotifyText,
    val style: NotifyStyle,
    val images: NotifyImages,
    val actions: List<NotifyActionInfo>,
)

/** `extras`의 텍스트 칸들 — 각각 그대로. 합치지 않는다. */
data class NotifyText(
    val title: String,
    val titleBig: String,
    val text: String,
    val bigText: String,
    val subText: String,
    val summaryText: String,
    val infoText: String,
)

/** 어떤 틀로 만들어진 알림인가. */
enum class NotifyStyleKind {
    NONE, BIG_TEXT, BIG_PICTURE, INBOX, MESSAGING, MEDIA, CALL, DECORATED, OTHER
}

data class NotifyStyle(
    val kind: NotifyStyleKind,
    /** `EXTRA_TEMPLATE`의 원본 문자열. 모르는 스타일도 사실은 그대로 넘어간다. */
    val templateClass: String,
    /** `kind == MESSAGING`일 때만 채워진다. */
    val messaging: NotifyMessaging?,
    /** `kind == INBOX`일 때의 줄들. */
    val inboxLines: List<String>,
)

/** 대화형 알림(`MessagingStyle`)의 원본. */
data class NotifyMessaging(
    /** 방 이름. 1:1이면 대개 비어 있다 — 앱들이 그걸 "그룹인가"의 힌트로 쓰기 때문이다. */
    val conversationTitle: String,
    val groupConversation: Boolean,
    /** '나'의 표시 이름. 비어 있을 수 있다 — 뭐라 부를지는 받는 쪽이 정한다. */
    val userName: String,
    val messages: List<NotifyMessage>,
    /** 이 알림 이전의 대화(`getHistoricMessages()`). 대개 이미 읽은 것들이다. */
    val historic: List<NotifyMessage>,
    /** 등장한 사람들. 줄은 `senderKey`(없으면 `sender`)로 여기를 가리킨다. */
    val people: List<NotifyPerson>,
)

/** 대화의 메시지 한 줄. 사진만 보낸 메시지는 `text`가 **비어 있다** — 채우지 않는다. */
data class NotifyMessage(
    val text: String,
    val timestampMillis: Long,
    /** 내가 보냈으면 비어 있다(`person`이 null인 것이 '나'의 표시다). */
    val sender: String,
    val senderKey: String,
    val fromMe: Boolean,
    /** "image/jpeg" 등. 비었으면 글 메시지다. */
    val dataMimeType: String,
    /** 내용의 위치. **우리는 못 읽는다** — 있다는 사실만 전한다. */
    val dataUri: String,
)

/** 대화 참여자 한 명. */
data class NotifyPerson(
    val name: String,
    val key: String,
    val bot: Boolean,
    val important: Boolean,
    val iconPng: ByteArray,
)

/** 알림에 딸린 그림들 — 고르지 않고 있는 대로 전부. 없으면 빈 바이트. */
data class NotifyImages(
    /** 앱 아이콘(패키지 매니저). 색이 있는 진짜 아이콘. */
    val appIconPng: ByteArray,
    /** 알림의 작은 아이콘. 실루엣이지만 패키지 가시성과 무관하게 읽힌다. */
    val smallIconPng: ByteArray,
    /** 알림의 큰 아이콘. 메신저는 여기에 방/상대 사진을 넣는다. */
    val largeIconPng: ByteArray,
)

/** 알림에 달린 버튼 하나. */
data class NotifyActionInfo(
    val index: Int,
    val title: String,
    /** `RemoteInput`이 달려 있는가 — 있으면 답장할 수 있다. */
    val hasRemoteInput: Boolean,
    val remoteInputLabel: String,
    /** `SEMANTIC_ACTION_*`. 0 = 앱이 안 밝혔다. */
    val semanticAction: Int,
    val contextual: Boolean,
)

/** 조작이 안 된 이유. 와이어의 `NotificationActionFailed.Reason`과 짝이다. */
enum class FailReason { LOCKED, NOT_PERMITTED, GONE }

/**
 * 한 알림에서 실어 보낼 메시지 수 상한.
 *
 * 안드로이드가 알림 안에 붙들고 있는 상한이 25개다(`MAXIMUM_RETAINED_MESSAGES`).
 * 그보다 크게 잡아 봐야 올 것이 없어서 거기에 맞춘다 — 폰에서 펼쳤을 때 보이는 만큼이
 * Mac에서도 보이는 게 이 기능의 목적이다.
 */
private const val MAX_LINES = 25

/** 프로필 사진을 실어 보낼 사람 수 상한. 단톡방이라도 알림에 등장하는 얼굴은 몇 안 된다. */
private const val MAX_PEOPLE = 8

/**
 * 앱 아이콘·작은 아이콘의 한 변(px).
 *
 * 이것들은 Mac에서 **모서리의 작은 배지**로 쓰인다 — 크게 그릴 일이 없다.
 */
private const val ICON_PX = 64

/**
 * 방 아이콘·프로필 사진의 한 변(px).
 *
 * Mac 알림에 **그림 자리를 통째로 차지하는** 쪽이라 48px로는 Retina에서 뭉갠다. 얼굴을
 * 알아보는 게 이 그림의 목적이므로 여기만 크게 잡는다 — 알림 하나에 방 사진 한 장과 얼굴
 * 몇 장이니 늘어나는 양은 수십 KB다.
 */
private const val PHOTO_PX = 96

/**
 * 리스너 서비스와 컨트롤러 사이의 다리.
 *
 * **프로세스 전역인 이유**: 리스너 서비스의 수명은 시스템이 쥔다(설정에서 허용하면 바인드,
 * 앱이 죽어도 살아 있을 수 있다). 컨트롤러의 수명은 Activity가 쥔다. 둘이 서로를 직접
 * 붙들면 어느 한쪽이 없는 구간에서 참조가 새거나 끊긴다.
 */
object NotifyBridge {
    /** 시스템이 리스너를 바인드했는가. */
    @Volatile var connected = false
    /** Mac이 알림 앱을 켰는가. 꺼져 있으면 리스너는 돌되 아무것도 안 보낸다. */
    @Volatile var wanted = false

    @Volatile var listener: PeriNotificationListener? = null

    @Volatile var onPosted: ((NotifySnapshot) -> Unit)? = null
    @Volatile var onRemoved: ((String) -> Unit)? = null
    /** 조작이 안 됐다 — **실패만** 알린다. 성공은 알림이 사라지는 것으로 드러난다. */
    @Volatile var onActionFailed: ((key: String, reason: FailReason, detail: String) -> Unit)? = null

    /** 지금 떠 있는 것 전부 다시 — Mac의 `NotificationSync`. */
    fun syncNow() {
        listener?.pushAll() ?: Log.i(TAG, "리스너 미연결 — 동기화 요청 보류")
    }
}

/** 알림 접근 권한을 사람이 켜도록 안내하는 데 필요한 것들. */
object NotifyAccess {
    /**
     * 지금 허용돼 있는가.
     *
     * `Settings.Secure.ENABLED_NOTIFICATION_LISTENERS`에 우리 컴포넌트가 들어 있는지 본다 —
     * 이게 **유일하게 믿을 수 있는 판정**이다. `NotifyBridge.connected`는 시스템이 아직
     * 바인드하기 전이면 false라, 그것만 보면 이미 켠 사용자에게 또 켜라고 한다.
     */
    fun isGranted(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(
            ctx.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(ctx, PeriNotificationListener::class.java)
        return flat.split(":").any {
            ComponentName.unflattenFromString(it) == me
        }
    }

    /** 설정 화면으로 보낸다. **런타임 권한이 아니라 사용자가 직접 켜야 한다.** */
    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 백그라운드에서 액티비티를 시작할 수 있는가 — **알림을 눌러 앱을 여는 데만 필요하다.**
     *
     * 알림을 받아 Mac에 띄우는 건 이것 없이도 된다. 막히는 건 "Mac에서 알림을 눌렀을 때
     * 폰에서 그 앱이 열리는" 부분 하나다.
     *
     * 우리가 만족시킬 수 있는 BAL 예외가 이것뿐이라 이 권한을 본다. 나머지 예외는
     * 화면에 창이 있거나(백그라운드가 아니다), IME이거나, 시스템 서명 권한이라 해당이 없다.
     */
    fun canStartFromBackground(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /** '다른 앱 위에 표시' 설정 화면. 우리 앱으로 바로 간다. */
    fun overlaySettingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
               android.net.Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
