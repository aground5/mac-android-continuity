package com.peri.display.transport

/**
 * 전송 계층 추상 (Android 수신측). LanClient(TCP+UDP)과 AoaClient(USB 신뢰 파이프)가 공통으로
 * 제공 — MirrorController가 핸드셰이크/제어를 bearer 무관하게 구동. 영상은 LAN=UDP(onVideoPacket),
 * AOA=WireFrame.Video(onFrame)로 갈라지므로 인터페이스엔 공통부(제어/프레임)만 둔다.
 */
interface Bearer {
    var onFrame: ((WireFrame) -> Unit)?
    var onConnected: (() -> Unit)?
    var onDisconnected: ((Throwable?) -> Unit)?
    fun sendControl(frame: ByteArray)

    /**
     * 바이트가 **실제로 나간 뒤** [onSent]를 부른다.
     *
     * 왜 인터페이스에 있나: Bye처럼 "나갔는지"가 의미를 갖는 프레임이 있는데, 예전엔 이 변형이
     * `LanClient`에만 있어서 호출부가 `if (b is LanClient)`로 갈라졌다. 그 분기가 **AOA 쪽
     * 구멍을 가렸다** — USB로 '끊기'를 누르면 Bye가 큐에 들어간 직후 fd가 닫혀 유실됐고,
     * Mac은 그걸 못 받아 8초 무응답 타임아웃으로 정리했다.
     *
     * 공통 계약으로 올리면 새 bearer가 생겨도 같은 실수를 반복할 수 없다.
     */
    fun sendControl(frame: ByteArray, onSent: (() -> Unit)?)

    /**
     * 최신성 우선 송신 — 화면 공유(Android → Mac) 영상.
     *
     * 무엇을 넘기는지는 bearer마다 다르다: LAN은 **UDP 데이터그램 하나**, AOA는 길이 프리픽스가
     * 붙은 **완성된 프레임**이다. 그 차이는 호출부가 이미 알고 있다(조각화 여부를 정하는 게
     * 곧 그 판단이다) — 여기서는 "이 바이트를 최신성 경로로 내보내라"까지만 말한다.
     *
     * 도착 보장이 없고 실패해도 알리지 않는다. 늦게 도착한 화면 한 장은 쓸모가 없으므로
     * 재전송이 답이 아니고, 다음 프레임이 곧 온다.
     */
    fun sendVideo(payload: ByteArray)

    fun disconnect()
}
