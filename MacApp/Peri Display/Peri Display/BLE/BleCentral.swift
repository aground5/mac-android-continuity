//
//  BleCentral.swift
//  Peri Display
//
//  BLE 페어링 전송(2b) — Mac = central(스캔/연결/write/subscribe). Android=peripheral.
//  프로토콜 무관 불투명 프레임 파이프: 논리 메시지 = [2B BE 길이][payload], MTU 청크로
//  쪼개 RX(write)로 보내고 TX(notify)로 받아 길이프리픽스로 재조립. 위에 2c SAS 프로토콜이 얹힌다.
//
//  요구: App Sandbox Bluetooth 권한(ENABLE_RESOURCE_ACCESS_BLUETOOTH) + NSBluetoothAlwaysUsageDescription.
//  CBCentralManager를 .main 큐로 돌려 모든 델리게이트 콜백·@Published 변경을 메인에서 처리.
//

import Foundation
import Combine
import CoreBluetooth
import OSLog

private let log = Logger(subsystem: "com.peri.display.mac", category: "ble")

enum PeriBLE {
    static let service = CBUUID(string: "D7F3A8E0-1B2C-4D5E-9F60-A1B2C3D4E5F6")
    static let rx = CBUUID(string: "D7F3A8E1-1B2C-4D5E-9F60-A1B2C3D4E5F6")  // central→peripheral (write)
    static let tx = CBUUID(string: "D7F3A8E2-1B2C-4D5E-9F60-A1B2C3D4E5F6")  // peripheral→central (notify)
}

struct DiscoveredPeer: Identifiable, Equatable {
    let id: UUID          // peripheral.identifier
    var name: String
    var rssi: Int
    /// 광고의 회전 토큰이 신뢰저장소의 기기로 해석됐을 때 그 지문. nil = 미페어링(낯선) 기기.
    /// UI는 이 값으로 "기존 목록의 그 기기"와 광고를 잇는다 — 이름 대조 휴리스틱을 대체.
    var fingerprint: String?
}

final class BleCentral: NSObject, ObservableObject {
    @Published private(set) var peers: [DiscoveredPeer] = []
    @Published private(set) var status = "대기"
    @Published private(set) var isReady = false      // RX/TX 준비 완료(프레임 송수신 가능)
    @Published private(set) var isScanning = false
    @Published private(set) var lastInbound: Data?   // 스모크/디버그: 마지막 수신 프레임

    /// 호출 응답 광고 → 지문. 지금 부르고 있는 기기의 응답일 때만 값이 나온다(AppCore가 주입).
    var pageResponseResolver: ((Data) -> String?)?

    /// 재조립된 논리 프레임 수신 콜백 (메인 스레드).
    var onFrame: ((Data) -> Void)?
    /// 채널 준비 완료 콜백 (연결+특성탐색+notify 구독 후 1회).
    var onReady: (() -> Void)?
    /// 연결이 끊겼을 때 콜백 (페어링 핸드오프 완료·실패 후 스캔 재개용).
    var onDisconnect: (() -> Void)?

    /// **신뢰된** 기기가 광고에 재협상 요청 비트를 세웠다 — 상승 에지에서 1회만.
    ///
    /// Android의 IP가 바뀌면(LTE↔Wi-Fi) 뚫어 둔 NAT 구멍이 전부 무효가 되는데, IP가 바뀌는
    /// 마당에 IP 기반 채널로는 그 사실조차 못 알린다. 광고는 IP와 무관하고 이미 상시 돌고
    /// 있으므로, 연결 없이 스캔만으로 요청을 받는다.
    var onIceRestartRequested: ((DiscoveredPeer) -> Void)?

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var rxChar: CBCharacteristic?
    private var txChar: CBCharacteristic?
    private var discovered: [UUID: CBPeripheral] = [:]
    private var rxBuffer = Data()
    private var wantScan = false
    private let resolver = AdvertResolver()   // 광고 토큰 → 신뢰된 기기 지문
    /// 재협상 비트를 이미 알린 지문 — 광고는 초당 여러 번 오므로 상승 에지에서만 통지한다.
    private var restartAsserted: Set<String> = []

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
    }

    func startScan() {
        peers = []; discovered = [:]; wantScan = true
        if central.state == .poweredOn { beginScan() } else { status = "Bluetooth 준비 중…" }
    }

    func stopScan() { wantScan = false; isScanning = false; central.stopScan() }

    func connect(_ peer: DiscoveredPeer) {
        guard let p = discovered[peer.id] else { return }
        // 붙는 순간 에지 기록을 지운다 — 이 라운드가 실패해 다시 스캔으로 돌아왔을 때
        // 같은 요청 비트를 새 요청으로 다시 볼 수 있어야 재시도가 산다.
        restartAsserted.removeAll()
        central.stopScan(); isScanning = false
        status = "\(peer.name) 연결 중…"
        peripheral = p
        p.delegate = self
        central.connect(p, options: nil)
    }

    func disconnect() {
        if let p = peripheral { central.cancelPeripheralConnection(p) }
    }

    /// 논리 프레임 송신 (2B 길이프리픽스 + MTU 청크).
    func send(_ frame: Data) {
        guard let p = peripheral, let rx = rxChar else { return }
        var msg = Data([UInt8((frame.count >> 8) & 0xFF), UInt8(frame.count & 0xFF)])
        msg.append(frame)
        // GATT 속성값 상한 512B(스펙)로 캡 — MTU가 더 커도 단일 write는 512 이하.
        let chunk = min(512, max(20, p.maximumWriteValueLength(for: .withResponse)))
        var i = 0
        while i < msg.count {
            let end = min(i + chunk, msg.count)
            p.writeValue(msg.subdata(in: i..<end), for: rx, type: .withResponse)
            i = end
        }
    }

    private func beginScan() {
        isScanning = true
        status = "스캔 중…"
        // ★ 중복 허용이 **필수**다. false면 CoreBluetooth가 스캔 세션당 peripheral 1회로
        // 합쳐 버려서, 광고 내용이 바뀌어도(= 재협상 요청 비트가 켜져도) 콜백이 다시 오지
        // 않는다. 그러면 핸드오버를 영영 못 본다. 대신 콜백이 광고 주기(≈100ms)로 쏟아지므로
        // didDiscover에서 @Published 갱신을 의미 있는 변화로만 제한한다.
        central.scanForPeripherals(withServices: [PeriBLE.service],
                                   options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
    }

    private func ingest(_ data: Data) {
        rxBuffer.append(data)
        while rxBuffer.count >= 2 {
            let s = rxBuffer.startIndex
            let len = Int(rxBuffer[s]) << 8 | Int(rxBuffer[s + 1])
            guard rxBuffer.count >= 2 + len else { break }
            let frame = rxBuffer.subdata(in: (s + 2)..<(s + 2 + len))
            rxBuffer.removeSubrange(s..<(s + 2 + len))
            lastInbound = frame
            onFrame?(frame)
        }
    }
}

extension BleCentral: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn: if wantScan { beginScan() }
        case .poweredOff: status = "Bluetooth 꺼짐"
        case .unauthorized: status = "Bluetooth 권한 거부됨"
        case .unsupported: status = "Bluetooth 미지원"
        default: status = "Bluetooth 준비 중…"
        }
    }

    func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String)
            ?? p.name ?? "알 수 없는 기기"
        // 회전 토큰 해석 → 페어링된 기기면 지문 확보(기존 목록 행에 병합됨), 아니면 nil.
        let mfr = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data
        let advert = mfr.flatMap(PeriAdvertToken.parse(manufacturerData:))
        // 호출 응답 광고는 신원 토큰이 아니라 **우리가 부른 것에 대한 대답**이라, 상시 역인덱스가
        // 아니라 지금 부르는 쪽(pager)이 판정한다. 부르고 있지 않으면 아무것도 안 나온다.
        let answered = mfr.flatMap(PeriAdvertToken.parseResponse(manufacturerData:))
            .flatMap { pageResponseResolver?($0) }
        let fingerprint = answered ?? advert.map(\.token).flatMap(resolver.fingerprint(forToken:))
        discovered[p.identifier] = p

        let peer: DiscoveredPeer
        if let idx = peers.firstIndex(where: { $0.id == p.identifier }) {
            // ★ 지문은 **파생값**이다 — 광고 토큰을 신뢰저장소에 대조한 결과이지 광고에 실려
            // 오는 값이 아니다. 그래서 대조가 가능한 상황에서는 매번 다시 쓴다.
            //
            // 예전엔 "해석 실패는 일시적일 수 있다"며 한번 잡은 값을 영구 보존했는데, 그게
            // 페어링 해제 뒤에도 남아 UI가 계속 '페어링됨'으로 표시하는 버그가 됐다. 근처에
            // 있는 한 광고가 계속 오므로 유령 행이 되살아나기까지 했다.
            //
            // 일시적 실패 걱정은 근거가 약했다: 해석 테이블은 epoch ±1을 모두 담으므로
            // (`PeriAdvertToken.rebuildIfNeeded`) 토큰 회전 중에도 신뢰된 기기는 해석된다.
            // 즉 **광고가 파싱됐는데 해석이 안 된다 = 지금 신뢰 목록에 없다**가 맞다.
            // 광고 자체가 안 읽힌 경우(다른 제조사 데이터, 잘린 패킷)만 옛 값을 남긴다.
            let nextFingerprint = (advert != nil || answered != nil)
                ? fingerprint : peers[idx].fingerprint
            let changed = peers[idx].name != name
                || peers[idx].fingerprint != nextFingerprint
                || abs(peers[idx].rssi - RSSI.intValue) >= 5
            if changed {
                peers[idx].rssi = RSSI.intValue
                peers[idx].name = name
                peers[idx].fingerprint = nextFingerprint
            }
            peer = peers[idx]
        } else {
            let p2 = DiscoveredPeer(id: p.identifier, name: name,
                                    rssi: RSSI.intValue, fingerprint: fingerprint)
            peers.append(p2)
            peer = p2
        }

        // 재협상 요청 비트 — 신뢰된 기기(지문 해석 성공)만. 낯선 광고는 비트를 세워도 무시한다.
        guard let fingerprint, let advert else { return }
        if advert.wantsIceRestart {
            guard !restartAsserted.contains(fingerprint) else { return }
            restartAsserted.insert(fingerprint)
            log.notice("★ 광고에 ICE 재협상 요청 — \(peer.name) [\(fingerprint)]")
            onIceRestartRequested?(peer)
        } else {
            restartAsserted.remove(fingerprint)   // 하강 에지 → 다음 요청을 다시 받을 수 있게
        }
    }

    func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
        status = "연결됨 · 서비스 탐색…"
        p.discoverServices([PeriBLE.service])
    }

    func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        status = "연결 실패: \(error?.localizedDescription ?? "?")"
    }

    func centralManager(_ c: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        isReady = false; rxChar = nil; txChar = nil; rxBuffer = Data(); peripheral = nil
        status = "연결 끊김"
        onDisconnect?()
    }
}

extension BleCentral: CBPeripheralDelegate {
    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard let svc = p.services?.first(where: { $0.uuid == PeriBLE.service }) else {
            status = "Peri 서비스 없음"; return
        }
        p.discoverCharacteristics([PeriBLE.rx, PeriBLE.tx], for: svc)
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        for ch in service.characteristics ?? [] {
            if ch.uuid == PeriBLE.rx { rxChar = ch }
            if ch.uuid == PeriBLE.tx { txChar = ch; p.setNotifyValue(true, for: ch) }
        }
        if rxChar != nil, txChar != nil {
            isReady = true
            status = "페어링 채널 준비됨"
            onReady?()
        }
    }

    func peripheral(_ p: CBPeripheral, didUpdateValueFor ch: CBCharacteristic, error: Error?) {
        guard ch.uuid == PeriBLE.tx, let d = ch.value else { return }
        ingest(d)
    }

    func peripheral(_ p: CBPeripheral, didWriteValueFor ch: CBCharacteristic, error: Error?) {
        if let error { log.error("ble write error: \(error.localizedDescription)") }
    }
}
