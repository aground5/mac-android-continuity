//
//  Bearer.swift
//  Peri Display
//
//  전송 계층 추상. Session(세션/크립토/영상 로직)이 LanServer(TCP+UDP)든
//  AoaBearer(USB 신뢰 파이프)든 동일하게 제어/영상 프레임을 내보낸다.
//  프레이밍·암호화는 bearer 무관 — bearer는 프레임화된 바이트만 나른다.
//

import Foundation

nonisolated protocol Bearer: Sendable {
    /// 제어/핸드셰이크 프레임(이미 길이-프리픽스) 송신.
    func sendControl(_ data: Data, to client: LanServer.ClientID)
    /// 영상 송신. LAN=UDP mini-RTP 데이터그램, AOA=신뢰 파이프(Phase 3).
    func sendVideo(_ datagram: Data, to peer: LanServer.UDPPeerID)
    /// 영상 송출 게이트(수신 피어 준비 여부). LAN=UDP HELLO 등록됨, AOA=파이프 연결됨.
    func hasUdpPeer(_ peer: LanServer.UDPPeerID?) -> Bool
}

// LanServer는 이미 동일 시그니처를 가짐 → 선언만으로 적합.
extension LanServer: Bearer {}

/// AOA(USB) 한 기기의 파이프를 Bearer로 감싼다. 제어/영상 모두 하나의 신뢰 스트림으로 나간다.
/// 멀티 기기: 기기(deviceID)마다 하나씩 만들어 그 기기의 bulk OUT으로만 라우팅한다.
nonisolated final class AoaBearer: Bearer, @unchecked Sendable {
    let clientID = UUID()      // 이 기기의 가상 ClientID
    let deviceID: String       // USB locationID 기반 — 송신 라우팅 키
    private let aoa: PeriAoaServer

    init(_ aoa: PeriAoaServer, deviceID: String) {
        self.aoa = aoa
        self.deviceID = deviceID
    }

    func sendControl(_ data: Data, to client: LanServer.ClientID) { aoa.send(data, toDevice: deviceID) }

    /// 영상 프레임(WireFormat.videoFrame, 이미 길이-프리픽스) 통째 송신 — 신뢰 파이프.
    func sendVideoFrame(_ frame: Data) { aoa.send(frame, toDevice: deviceID) }

    /// Bearer 프로토콜 요구(LAN UDP용). AOA는 sendVideoFrame을 쓰므로 미사용.
    func sendVideo(_ datagram: Data, to peer: LanServer.UDPPeerID) { aoa.send(datagram, toDevice: deviceID) }

    /// AOA는 UDP 피어 개념이 없음 → Phase 2에선 영상 게이트 off(핸드셰이크만 검증).
    func hasUdpPeer(_ peer: LanServer.UDPPeerID?) -> Bool { false }
}
