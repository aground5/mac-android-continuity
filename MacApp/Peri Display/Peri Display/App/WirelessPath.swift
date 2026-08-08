//
//  WirelessPath.swift
//  Peri Display
//
//  무선 연결이 **실제로 어떤 망을 타고 있는가**.
//
//  왜 필요한가: UI가 여태 "Wi-Fi" 하나로 뭉뚱그렸는데, 그 안에 성격이 완전히 다른 셋이 있다.
//
//   · 같은 LAN      — NAT을 안 거친다. 지연 최저, 대역 최대. 여기가 정상 동작점이다.
//   · 외부 Wi-Fi    — 카페·회사 등 다른 망. NAT을 통과했고 왕복이 인터넷을 탄다.
//   · 셀룰러(LTE/5G) — 캐리어 CGNAT. 지연·손실 모두 최악이고 펀칭조차 안 되는 경우가 흔하다.
//
//  화질이나 끊김을 볼 때 이 셋을 구분하지 못하면 원인을 못 짚는다 — "Wi-Fi인데 왜 느리지"의
//  답이 대개 "그 Wi-Fi가 남의 망이라 인터넷을 돌아온다"이기 때문이다.
//
//  **판정 근거가 둘로 나뉜다는 게 이 파일의 요점이다.**
//
//   1. LAN인가 — Mac이 **직접 관측**한다. 피어 주소가 우리 인터페이스의 서브넷 안이면 LAN이다.
//      상대가 NAT 뒤에 있으면 우리에게 보이는 건 공인 주소라 서브넷이 안 맞는다(오탐 없음).
//   2. 셀룰러인가 — **클라이언트만 안다.** 공인 주소로 들어온 연결이 LTE인지 남의 Wi-Fi인지
//      Mac은 구분할 방법이 전혀 없다. 그래서 Android가 `ClientNetwork`로 실어 보낸다.
//

import Foundation
import Darwin
import SwiftUI

enum WirelessPath: Equatable, Sendable {
    /// 같은 LAN — 피어 주소가 우리 서브넷 안이다.
    case lan
    /// Wi-Fi지만 다른 망 — NAT을 통과해서 왔다.
    case remoteWifi
    /// LTE/5G.
    case cellular
    /// 클라가 안 알려줬거나(구버전) 아직 첫 Ping 전.
    case unknown

    var label: String {
        switch self {
        case .lan: return "LAN"
        case .remoteWifi: return "외부 Wi-Fi"
        case .cellular: return "LTE"
        case .unknown: return "Wi-Fi"
        }
    }

    /// SF Symbol. 링크 품질이 한눈에 읽히도록 셋을 다른 모양으로 둔다.
    var icon: String {
        switch self {
        case .lan: return "wifi"
        case .remoteWifi: return "globe"
        case .cellular: return "antenna.radiowaves.left.and.right"
        case .unknown: return "wifi"
        }
    }

    /// 배지 색. 링크 품질 순서대로 파랑(최선) → 보라(우회) → 주황(최악)이다.
    var tint: Color {
        switch self {
        case .lan: return .blue
        case .remoteWifi: return .purple
        case .cellular: return .orange
        case .unknown: return .secondary
        }
    }

    /// 한 줄 설명 — 툴팁·진단용.
    var detail: String {
        switch self {
        case .lan: return "같은 네트워크 — NAT을 거치지 않는 직결 경로"
        case .remoteWifi: return "다른 네트워크의 Wi-Fi — NAT을 통과해 인터넷을 경유한다"
        case .cellular: return "셀룰러(LTE/5G) — 캐리어 NAT 경유, 지연이 가장 크다"
        case .unknown: return "망 종류 미확인 — 클라이언트 보고를 기다리는 중"
        }
    }

    /// 관측(피어 주소)과 보고(클라이언트)를 합쳐 판정한다.
    ///
    /// LAN 판정이 먼저인 이유: 그건 우리가 직접 본 사실이고, 보고는 상대의 주장이다.
    /// 둘이 어긋날 일은 거의 없지만(셀룰러가 우리 서브넷에 있을 수 없다), 어긋난다면
    /// 관측을 믿는 게 맞다.
    static func resolve(peerAddress: String?, reported: PeriClientNetwork) -> WirelessPath {
        if let peerAddress, LocalNetwork.isOnSameSubnet(peerAddress) { return .lan }
        switch reported {
        case .cellular: return .cellular
        case .wifi, .ethernet: return .remoteWifi
        case .other: return .remoteWifi
        case .unspecified, .UNRECOGNIZED: return .unknown
        }
    }
}

enum LocalNetwork {
    /// 이 주소가 우리 인터페이스 중 하나와 같은 서브넷에 있는가.
    ///
    /// `getifaddrs`로 각 IPv4 인터페이스의 주소와 넷마스크를 읽어 `(a & m) == (p & m)`을 본다.
    /// 사설 대역인지만 보는 방식(10./192.168. 등)으로는 안 된다 — 상대가 자기 집 LAN에서
    /// 192.168.0.x를 쓰고 있어도 우리에게 도달하는 소스 주소는 그쪽 NAT의 공인 주소다.
    /// 반대로 우리 서브넷 안 주소가 보인다는 건 중간에 NAT이 없었다는 직접 증거다.
    static func isOnSameSubnet(_ ip: String) -> Bool {
        guard let peer = ipv4(ip) else { return false }
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return false }
        defer { freeifaddrs(ifaddr) }
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let flags = Int32(ptr.pointee.ifa_flags)
            guard (flags & IFF_UP) == IFF_UP, (flags & IFF_LOOPBACK) == 0 else { continue }
            guard let sa = ptr.pointee.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET),
                  let nm = ptr.pointee.ifa_netmask else { continue }
            let addr = sa.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr.s_addr }
            let mask = nm.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr.s_addr }
            guard mask != 0 else { continue }   // /0 인터페이스는 아무 주소나 매칭돼 무의미하다
            if (addr & mask) == (peer & mask) { return true }
        }
        return false
    }

    private static func ipv4(_ s: String) -> in_addr_t? {
        var a = in_addr()
        guard inet_pton(AF_INET, s, &a) == 1 else { return nil }
        return a.s_addr
    }
}
