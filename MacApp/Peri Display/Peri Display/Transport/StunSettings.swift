//
//  StunSettings.swift
//  Peri Display
//
//  STUN 서버 목록 설정 (UserDefaults 영속). 기본값은 실측으로 고른 공개 서버들.
//
//  공개 STUN을 쓰면 이 기기의 공인 IP가 해당 사업자에게 노출된다 — STUN의 표준 동작이고
//  대안은 직접 서버를 운영하는 것뿐이라, 기본값으로 두되 설정에서 바꿀 수 있게 한다.
//
//  ★ TCP 후보에는 **TCP Binding에 응답하는 서버**가 필요하다. 공개 STUN 다수(Google·Cloudflare)는
//  UDP 전용이라 srflx-tcp를 못 준다. 목록에서 tcp 표시가 있는 항목이 그 역할을 한다.
//

import Foundation

enum StunSettings {
    private static let key = "PeriStunServers"

    /// 현재 설정된 서버 목록. 저장된 값이 없거나 깨졌으면 기본값.
    static var servers: [StunServer] {
        get {
            guard let data = UserDefaults.standard.data(forKey: key),
                  let list = try? JSONDecoder().decode([StunServer].self, from: data),
                  !list.isEmpty else { return StunServer.defaults }
            return list
        }
        set {
            // 빈 목록으로 저장하면 srflx를 아예 못 얻어 조용히 LAN 전용이 된다 → 기본값으로 되돌린다.
            let list = newValue.isEmpty ? StunServer.defaults : newValue
            guard let data = try? JSONEncoder().encode(list) else { return }
            UserDefaults.standard.set(data, forKey: key)
        }
    }

    static func reset() { UserDefaults.standard.removeObject(forKey: key) }

    /// "host:port" 또는 "host:port tcp" 한 줄 → 서버. 설정 UI의 텍스트 입력용.
    static func parse(line: String) -> StunServer? {
        let f = line.split(separator: " ", omittingEmptySubsequences: true).map(String.init)
        guard let first = f.first else { return nil }
        let tcp = f.dropFirst().contains("tcp")
        guard let colon = first.lastIndex(of: ":") else { return nil }
        let host = String(first[first.startIndex..<colon])
        guard let port = UInt16(first[first.index(after: colon)...]), !host.isEmpty else { return nil }
        return StunServer(host, port, tcp: tcp)
    }

    static func line(for s: StunServer) -> String {
        s.tcp ? "\(s.host):\(s.port) tcp" : "\(s.host):\(s.port)"
    }
}
