//
//  DeviceModel.swift
//  Peri Display
//
//  UI가 보는 "기기 하나" — 신원 지문(fingerprint)이 유일 키.
//
//  왜 필요한가: 세션은 전송(LAN/AOA)마다 따로 살고 자주 죽고 살아난다. 세션을 그대로
//  나열하면 LAN↔USB 전환 구간에 같은 폰이 두 행으로 보인다(실측). DisplayManager가
//  디스플레이를 지문당 1개로 영속시켜 churn을 없앤 것과 같은 수 — UI도 지문당 1행.
//
//  네 소스를 머지한다:
//   ① TrustStore(페어링된 기기, 오프라인 포함) ② LAN 세션 ③ AOA 세션 ④ BLE 발견(미페어링)
//

import Foundation

/// 사이드바/메뉴 막대의 기기 1행. 값 타입 스냅샷 — AppCore가 소스 변경 때마다 재구성한다.
struct DeviceModel: Identifiable, Equatable {
    /// 기기의 현재 연결 상태 (전송은 기기의 속성이지 기기를 나누는 축이 아니다).
    enum Link: Equatable {
        case usb          // AOA 세션 활성
        case wifi         // LAN 세션 활성
        case offline      // 페어링돼 있으나 지금은 연결 없음
        case discovered   // BLE로 발견된 미페어링 기기
    }

    /// fingerprint(페어링·세션 확보 후) 또는 임시 키(핸드셰이크 전 세션 = "s:<uuid>",
    /// 미페어링 BLE peer = "ble:<uuid>"). DisplayManager·TrustStore와 같은 신원 축.
    let id: String
    var name: String
    var link: Link
    var lastSeenMillis: Int64?     // TrustStore lastSeen — 오프라인 행의 "N일 전 연결" 표시용
    /// 이 기기가 지금 BLE로 보이는가. .discovered면 페어링 시작에, .offline이면 "연결" 시작에 쓴다
    /// (신뢰된 기기는 SAS 없이 자동 재인증 후 Mac이 접속점을 넘겨 Android가 붙는다).
    var blePeer: DiscoveredPeer?
    /// 활성 세션 (송출 중이거나 핸드셰이크 진행). 뷰는 이걸 @ObservedObject로 구독해
    /// 상태 변경을 직접 받는다 — DeviceModel 스냅샷 자체는 목록 구조만 나른다.
    var session: Session?
    /// 이 기기의 홈 화면 — 앱 전부. **세션이 아니라 여기서 온다** — 앱은 기기에 매여
    /// 세션보다 오래 산다(DESIGN.md §5). 뷰가 세션을 거쳐 되비친 값을 읽던 시절엔 세션이
    /// 갈릴 때마다 숫자가 0으로 튀었다.
    var apps: AppHost?
    /// 확장 앱 바로가기 — 통계·HDR·재생 버퍼를 읽는 화면들이 쓴다.
    var extend: ExtendService? { apps?.extend }
    /// 무선 연결이 실제로 타는 망 — LAN / 외부 Wi-Fi / LTE. `link == .wifi`일 때만 의미가 있다.
    /// 배지가 이 값으로 갈린다. 판정 근거는 [WirelessPath].
    var wireless: WirelessPath = .unknown
    /// 기기 상태 기계가 계산한 한 줄 요약([DeviceState.summary]).
    ///
    /// 예전엔 뷰가 `session.statusMessage`를 직접 읽었는데, 그건 코드 여러 곳에서 대입되는
    /// 문자열이라 실제 상태와 어긋나기 일쑤였다(송출 중인데 "인증 대기"로 남는 식).
    /// 이제 유일한 생산자가 기계다.
    var statusText: String?

    static func == (a: DeviceModel, b: DeviceModel) -> Bool {
        a.id == b.id && a.name == b.name && a.link == b.link
            && a.lastSeenMillis == b.lastSeenMillis && a.blePeer == b.blePeer
            && a.session === b.session && a.apps === b.apps
            && a.statusText == b.statusText && a.wireless == b.wireless
    }

    /// 사용자에게 보여줄 한 줄. 세션의 예외 문구(인증 거부)가 가장 급하고, 그 다음이
    /// 서비스의 예외 문구(디스플레이 복구·구성 실패), 평상시엔 기계가 계산한 요약이다.
    @MainActor var detailText: String? {
        session?.detail ?? extend?.detail ?? statusText
    }
}

extension DeviceModel {
    /// 네 소스를 지문으로 머지. 우선순위: 활성 세션(USB > LAN) > 페어링됨(오프라인) > BLE 발견.
    ///  - 같은 지문에 LAN·AOA 세션이 겹치면(전환 과도기) **등록부가 현역으로 삼은 쪽**을 남긴다
    ///    — 등록부가 곧 한쪽으로 수렴하므로 과도기 표시일 뿐이다.
    ///  - BLE 발견 기기는 신원(pub)을 광고하지 않아 지문 대조가 불가 — 현재 연결/페어링된
    ///    기기와 이름이 겹치면 숨겨 중복 행을 줄인다(휴리스틱).
    @MainActor
    static func merge(trusted: [TrustedPeer],
                      lanSessions: [Session],
                      aoaSessions: [Session],
                      blePeers: [DiscoveredPeer],
                      summary: (Fingerprint) -> String? = { _ in nil },
                      apps: (Fingerprint) -> AppHost? = { _ in nil },
                      isCurrent: (Session) -> Bool = { _ in true }) -> [DeviceModel] {
        var byID: [String: DeviceModel] = [:]
        var order: [String] = []   // 섹션 정렬 전 안정 순서 유지용

        func upsert(_ m: DeviceModel) {
            if byID[m.id] == nil { order.append(m.id) }
            byID[m.id] = m
        }

        // ① 페어링된 기기 — 전부 오프라인으로 깔아둔다 (아래에서 세션이 덮어씀).
        for p in trusted {
            upsert(DeviceModel(id: p.fingerprint, name: p.name, link: .offline,
                               lastSeenMillis: p.lastSeenMillis, blePeer: nil, session: nil,
                               apps: apps(p.fingerprint),
                               statusText: summary(p.fingerprint)))
        }

        // ② 활성 세션 오버레이. LAN 먼저, AOA 나중 — 같은 지문이면 아래 규칙으로 승자 결정.
        func overlay(_ s: Session, _ link: Link) {
            // ★ 지문이 없는 연결은 **기기가 아니다.** 예전엔 `s:<uuid>` 임시 행을 만들었는데,
            // 그게 곧 "누군지 모르는 채로 목록에 올린다"는 뜻이라 재개·재연결 때마다 같은 문구의
            // 유령 행이 하나씩 더 붙었다(스크린샷). 신원이 확정된 뒤에만 행이 생긴다.
            guard let key = s.fingerprint else { return }
            // 전환 과도기(LAN·AOA 세션이 잠깐 겹칠 때)에는 **등록부가 지금 쓰는 연결**을 남긴다.
            //
            // 예전엔 세션별 통계(`stats.active`)로 갈랐는데, 이제 한 기기의 두 연결이 같은
            // 서비스를 공유하므로 그 값으로는 구분되지 않는다. 애초에 "어느 연결이 현역인가"는
            // 통계에서 추론할 게 아니라 등록부가 아는 사실이었다.
            if byID[key]?.session != nil, !isCurrent(s) { return }
            var m = byID[key] ?? DeviceModel(id: key, name: s.deviceName, link: link,
                                             lastSeenMillis: nil, blePeer: nil, session: nil,
                                             apps: nil, statusText: nil)
            m.name = s.deviceName
            m.link = link
            m.session = s
            m.apps = apps(key)
            m.statusText = summary(key)
            // USB는 케이블이 곧 전송이라 IP 망 종류가 의미 없다 — 배지를 갈지 않는다.
            m.wireless = (link == .usb) ? .unknown : s.wireless
            upsert(m)
        }
        for s in lanSessions { overlay(s, .wifi) }
        for s in aoaSessions { overlay(s, .usb) }

        // ③ BLE 발견. 광고의 회전 토큰이 해석되면 = 이미 페어링된 기기 → 새 행을 만들지 않고
        //    기존 행에 근접 정보만 붙인다(오프라인 행이 "지금 연결 가능"이 됨). 해석 안 되면
        //    낯선 기기 → 페어링 후보로 별도 행. 예전 이름 대조 휴리스틱을 대체한다.
        for peer in blePeers {
            // ★ **행을 새로 만들지 않는다.** 지문이 있어도 위 ①②에서 안 잡혔다면 그건
            // 신뢰저장소에도 없고 세션도 없다는 뜻 = 우리 기기가 아니다.
            //
            // 예전엔 여기에 "신뢰저장소엔 있으나 ①에서 안 잡힌 경우"라는 방어 갈래가 있었다.
            // 그 전제가 페어링 해제로 깨진다 — 해제된 기기의 광고가 (낡은 캐시 탓에) 지문을
            // 들고 오면 이 갈래가 `.offline` 행, 즉 '페어링됨' 항목을 **되살렸다**. 방어라기보다
            // 신뢰저장소의 판정을 뒤엎는 두 번째 권위였다.
            if let fp = peer.fingerprint, var known = byID[fp] {
                known.blePeer = peer          // link/session은 그대로 — 근접만 추가
                upsert(known)
            } else {
                upsert(DeviceModel(id: "ble:\(peer.id.uuidString)", name: peer.name,
                                   link: .discovered, lastSeenMillis: nil,
                                   blePeer: peer, session: nil))
            }
        }

        // 정렬: 연결됨(USB→Wi-Fi) → 페어링됨(최근 연결순) → 발견(신호순).
        func rank(_ m: DeviceModel) -> Int {
            switch m.link {
            case .usb: return 0
            case .wifi: return 1
            case .offline: return 2
            case .discovered: return 3
            }
        }
        return order.compactMap { byID[$0] }.sorted {
            let (ra, rb) = (rank($0), rank($1))
            if ra != rb { return ra < rb }
            if $0.link == .offline {
                return ($0.lastSeenMillis ?? 0) > ($1.lastSeenMillis ?? 0)
            }
            if $0.link == .discovered {
                return ($0.blePeer?.rssi ?? -100) > ($1.blePeer?.rssi ?? -100)
            }
            return $0.name < $1.name
        }
    }
}
