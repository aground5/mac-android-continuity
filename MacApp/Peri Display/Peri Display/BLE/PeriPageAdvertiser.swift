//
//  PeriPageAdvertiser.swift
//  Peri Display
//
//  **호출자** — 페어링된 기기 하나를 깨우려고 광고를 잠깐 띄운다.
//
//  ## 왜 Mac이 광고하나
//
//  예전엔 폰이 상시 광고하고 Mac이 스캔했다. 그 상시 광고가 폰 배터리를 계속 갉았다. 방향을
//  뒤집으면 **콘센트에 꽂힌 쪽이 부르고** 폰은 칩셋 오프로드 스캔만 걸어 둔다 — 앱이 죽어
//  있어도 칩셋이 감시하다가 매치될 때만 깨운다.
//
//  대가는 이것이다: 폰이 광고하지 않으므로 **Mac의 목록에 "근처에 있음"이 안 뜬다.** 그래서
//  UI의 연결 버튼이 항상 있어야 한다 — 눌러야 이 호출이 나간다.
//
//  ## 광고에 담을 수 있는 게 UUID뿐이다
//
//  macOS는 `LocalName`과 `ServiceUUIDs`만 지원한다(SDK 헤더가 명시). 제조사·서비스 데이터가
//  없으므로 [PeriPageToken]이 128비트 UUID 안에 전부 인코딩한다. 예산이 28바이트라 UUID
//  하나(16B + 헤더 2B)만 들어가고, 그래서 **한 번에 대상 하나**다.
//
//  이름은 싣지 않는다. 이 구간은 "누구를 부른다"만 필요하고, 이름 노출은 첫 페어링에만 둔다.
//
//  ## 오래 켜 두지 않는다
//
//  호출은 사건이지 상태가 아니다. 상대가 붙으면 즉시, 아니면 [maxDuration] 뒤에 끈다. 계속
//  띄우면 Mac이 상시 비콘이 되고, 그건 방금 폰에서 없앤 바로 그 비용이다.
//

import Foundation
import CoreBluetooth
import Combine
import OSLog

private let log = Logger(subsystem: "com.peri.display.mac", category: "page")

@MainActor
final class PeriPageAdvertiser: NSObject, ObservableObject {

    /// 지금 부르고 있는 기기의 지문. UI가 "부르는 중…"을 띄우는 유일한 기준.
    @Published private(set) var paging: String?

    /// 부를 수 없었던 이유. 다음 호출이나 [stop]에서 지워진다.
    ///
    /// 조용히 실패하면 안 되는 경우가 하나 있다: **링크 키가 없는 상대.** 케이블로만 신뢰를
    /// 세웠거나 링크 키가 생기기 전에 페어링한 기기가 그렇다. 버튼은 눌리는데 아무 일도
    /// 안 일어나는 상태가 되므로, 왜인지는 말해 줘야 한다.
    @Published private(set) var problem: String?

    /// 한 번의 호출이 유지되는 최대 시간.
    ///
    /// 폰이 오프로드 스캔에서 깨어나 광고를 시작하고 우리가 그걸 발견하기까지의 여유다.
    /// 배치 보고(1s) + 저전력 스캔 간격을 감안하면 이 정도는 있어야 한다.
    static let maxDuration: TimeInterval = 30

    /// 이번 호출의 상대와 소금.
    ///
    /// **nonce는 호출 한 번에 하나다**(창마다 새로 뽑지 않는다). 응답이 이 값을 소금으로 쓰므로
    /// 우리가 무엇을 기다리는지 알고 있어야 하고, 호출마다 새로 뽑으니 불연결성도 그대로다.
    private typealias Target = (pub: Data, linkKey: Data, fingerprint: String, nonce: Data)

    private var manager: CBPeripheralManager?
    /// 매니저가 켜지기 전에 누른 경우 — 켜지면 이걸 집어 시작한다.
    private var pending: Target?
    private var current: Target?
    private var rotateTask: Task<Void, Never>?

    override init() {
        super.init()
        // 지연 생성하지 않는다 — 전원이 켜지기까지 한 박자 걸려서, 버튼을 누른 뒤에 만들면
        // 그 첫 호출이 늘 한 번 씹힌다.
        manager = CBPeripheralManager(delegate: self, queue: .main)
    }

    /// 이 기기를 부른다. 이미 다른 기기를 부르고 있었으면 그쪽을 그만두고 갈아탄다.
    ///
    /// `linkKey`가 호출의 자격이다 — 페어링이 남긴 공유 비밀을 가진 쪽만 남의 폰을 깨울 수 있다.
    func page(devicePub: Data, linkKey: Data, fingerprint: String) {
        guard !devicePub.isEmpty, !linkKey.isEmpty else {
            problem = "이 기기는 호출할 수 없습니다. USB 케이블로 연결하거나 다시 페어링하세요."
            log.error("링크 키 없음 — 호출 불가 [\(fingerprint.prefix(8))]")
            return
        }
        problem = nil
        let target: Target = (devicePub, linkKey, fingerprint, PeriPageToken.randomNonce())
        guard let manager, manager.state == .poweredOn else {
            pending = target
            log.info("BLE 준비 전 — 호출 보류 [\(fingerprint.prefix(8))]")
            return
        }
        start(target)
    }

    /// 광고로 돌아온 응답이 **지금 부르는 그 기기**의 것인가 → 맞으면 그 지문.
    ///
    /// 부르고 있지 않으면 항상 nil이다. 응답은 호출에 대한 대답이지 상시 비콘이 아니므로,
    /// 우리가 부르지 않는 동안 오는 응답은 정의상 우리 것이 아니다.
    func resolve(response: Data) -> String? {
        guard let cur = current,
              PeriPageToken.matches(response: response, linkKey: cur.linkKey, nonce: cur.nonce)
        else { return nil }
        return cur.fingerprint
    }

    /// 상대가 붙었거나 사용자가 취소했다.
    func stop() {
        rotateTask?.cancel(); rotateTask = nil
        pending = nil; current = nil
        problem = nil
        manager?.stopAdvertising()
        if paging != nil { paging = nil; log.info("호출 종료") }
    }

    private func start(_ target: Target) {
        rotateTask?.cancel()
        current = target
        paging = target.fingerprint
        let fingerprint = target.fingerprint
        log.notice("★ 호출 시작 [\(fingerprint.prefix(8))]")

        rotateTask = Task { @MainActor [weak self] in
            let deadline = Date().addingTimeInterval(Self.maxDuration)
            while !Task.isCancelled, Date() < deadline {
                self?.emit()
                // 토큰이 창마다 바뀌므로 광고도 그 주기로 갈아 준다. 창 경계에 맞춰 자면
                // 경계를 넘긴 직후 값이 바로 새것이 된다.
                let now = Date().timeIntervalSince1970
                let next = (now / PeriPageToken.epochSeconds).rounded(.down) + 1
                let sleep = max(0.5, next * PeriPageToken.epochSeconds - now)
                try? await Task.sleep(for: .seconds(sleep))
            }
            guard !Task.isCancelled else { return }
            log.info("호출 시간 초과 — 응답 없음")
            self?.stop()
        }
    }

    /// 이번 창의 UUID로 광고를 갈아 끼운다.
    private func emit() {
        guard let manager, manager.state == .poweredOn, let cur = current else { return }
        let uuid = PeriPageToken.uuid(linkKey: cur.linkKey, nonce: cur.nonce)
        // 값이 바뀌었으므로 멈췄다 다시 건다 — CoreBluetooth는 광고 내용 갱신 API가 없다.
        manager.stopAdvertising()
        manager.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [CBUUID(nsuuid: uuid)]])
    }
}

extension PeriPageAdvertiser: CBPeripheralManagerDelegate {
    nonisolated func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        Task { @MainActor in
            guard peripheral.state == .poweredOn else {
                if self.paging != nil { self.stop() }
                return
            }
            // 켜지기 전에 누른 호출이 있으면 이제 내보낸다.
            if let p = self.pending {
                self.pending = nil
                self.start(p)
            }
        }
    }

    nonisolated func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager,
                                                          error: (any Error)?) {
        if let error {
            log.error("호출 광고 실패: \(error.localizedDescription)")
        }
    }
}
