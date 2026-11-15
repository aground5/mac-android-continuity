package com.peri.display.notify

import com.google.protobuf.ByteString
import com.peri.display.proto.InboxStyle
import com.peri.display.proto.MessagingMessage
import com.peri.display.proto.MessagingStyle
import com.peri.display.proto.NotificationActionInfo
import com.peri.display.proto.NotificationImages
import com.peri.display.proto.NotificationPerson
import com.peri.display.proto.NotificationPosted
import com.peri.display.proto.NotificationStyle
import com.peri.display.proto.NotificationText

/**
 * **받아쓴 알림 → 와이어.** 이 파일이 그 번역의 전부다 — Mac `NotifyMapping.swift`의 대칭.
 *
 * 왜 따로 있나
 * ───────────
 * [PeriNotificationListener]는 안드로이드 알림에서 **사실만** 뽑아 [NotifySnapshot]으로
 * 만든다. 여기서 하는 일은 그 사실 뭉치를 protobuf 모양으로 **옮겨 적는 것뿐**이다.
 * 이 둘을 갈라 두는 이유는 성격이 다르기 때문이다: 받아쓰기는 안드로이드 API가 바뀌면
 * 바뀌고, 옮겨 적기는 `.proto`가 바뀌면 바뀐다. 한 함수에 있으면 어느 쪽 변경이든 같은
 * 자리를 건드린다.
 *
 * ## 이 파일의 규칙: **판단이 없다**
 *
 * 값을 고르거나 비었다고 건너뛰기 시작하면 필터가 이 자리로 옮겨 온 것일 뿐이다.
 * 무엇을 보여줄지는 **Mac이** 정한다(`NotifyMapper.skip`) — 기기가 걸러 버리면 그런
 * 알림이 있었다는 사실조차 Mac에 오지 않아서, 표시 규칙을 바꾸려면 기기부터 고치고
 * 두 쪽을 같이 배포해야 한다. 그 경험 때문에 판단은 전부 Mac으로 옮겼다.
 */
internal object NotifyProto {

    fun posted(s: NotifySnapshot): NotificationPosted = NotificationPosted.newBuilder()
        .setKey(s.key)
        .setPackageName(s.packageName)
        .setAppLabel(s.appLabel)
        .setPostedAtMillis(s.postedAtMillis)
        .setWhenMillis(s.whenMillis)
        .setOngoing(s.ongoing)
        .setClearable(s.clearable)
        .setGroupSummary(s.groupSummary)
        .setFromPeri(s.fromPeri)
        .setIsConversation(s.isConversation)
        .setImportance(s.importance)
        .setCategory(s.category)
        .setChannelId(s.channelId)
        .setGroup(s.group)
        .setSortKey(s.sortKey)
        .setColor(s.color)
        .setText(text(s))
        .setStyle(style(s))
        .setImages(images(s))
        .addAllActions(s.actions.map(::action))
        .build()

    private fun text(s: NotifySnapshot): NotificationText.Builder =
        NotificationText.newBuilder()
            .setTitle(s.text.title)
            .setTitleBig(s.text.titleBig)
            .setText(s.text.text)
            .setBigText(s.text.bigText)
            .setSubText(s.text.subText)
            .setSummaryText(s.text.summaryText)
            .setInfoText(s.text.infoText)

    private fun images(s: NotifySnapshot): NotificationImages.Builder =
        NotificationImages.newBuilder()
            .setAppIconPng(ByteString.copyFrom(s.images.appIconPng))
            .setSmallIconPng(ByteString.copyFrom(s.images.smallIconPng))
            .setLargeIconPng(ByteString.copyFrom(s.images.largeIconPng))

    private fun action(a: NotifyActionInfo): NotificationActionInfo =
        NotificationActionInfo.newBuilder()
            .setIndex(a.index)
            .setTitle(a.title)
            .setHasRemoteInput(a.hasRemoteInput)
            .setRemoteInputLabel(a.remoteInputLabel)
            .setSemanticAction(a.semanticAction)
            .setContextual(a.contextual)
            .build()

    private fun style(s: NotifySnapshot): NotificationStyle.Builder {
        val b = NotificationStyle.newBuilder()
            .setKind(when (s.style.kind) {
                NotifyStyleKind.NONE -> NotificationStyle.Kind.KIND_UNSPECIFIED
                NotifyStyleKind.BIG_TEXT -> NotificationStyle.Kind.KIND_BIG_TEXT
                NotifyStyleKind.BIG_PICTURE -> NotificationStyle.Kind.KIND_BIG_PICTURE
                NotifyStyleKind.INBOX -> NotificationStyle.Kind.KIND_INBOX
                NotifyStyleKind.MESSAGING -> NotificationStyle.Kind.KIND_MESSAGING
                NotifyStyleKind.MEDIA -> NotificationStyle.Kind.KIND_MEDIA
                NotifyStyleKind.CALL -> NotificationStyle.Kind.KIND_CALL
                NotifyStyleKind.DECORATED -> NotificationStyle.Kind.KIND_DECORATED
                NotifyStyleKind.OTHER -> NotificationStyle.Kind.KIND_OTHER
            })
            .setTemplateClass(s.style.templateClass)

        s.style.messaging?.let { m ->
            b.setMessaging(
                MessagingStyle.newBuilder()
                    .setConversationTitle(m.conversationTitle)
                    .setGroupConversation(m.groupConversation)
                    .setUserName(m.userName)
                    .addAllMessages(m.messages.map(::message))
                    .addAllHistoric(m.historic.map(::message))
                    .addAllPeople(m.people.map(::person))
            )
        }
        if (s.style.inboxLines.isNotEmpty()) {
            b.setInbox(InboxStyle.newBuilder().addAllLines(s.style.inboxLines))
        }
        return b
    }

    private fun message(m: NotifyMessage): MessagingMessage =
        MessagingMessage.newBuilder()
            .setText(m.text)
            .setTimestampMillis(m.timestampMillis)
            .setSender(m.sender)
            .setSenderKey(m.senderKey)
            .setFromMe(m.fromMe)
            .setDataMimeType(m.dataMimeType)
            .setDataUri(m.dataUri)
            .build()

    private fun person(p: NotifyPerson): NotificationPerson =
        NotificationPerson.newBuilder()
            .setName(p.name)
            .setKey(p.key)
            .setBot(p.bot)
            .setImportant(p.important)
            .setIconPng(ByteString.copyFrom(p.iconPng))
            .build()
}
