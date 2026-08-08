//
//  DeviceInput.swift
//  Peri Display
//
//  **기기에 입력을 주입하는 통로를 한 곳에 모은다.**
//
//  왜 등록소가 따로 필요한가
//  ────────────────────────
//  HID 주입기는 **USB 기기(locationID)** 에 매여 있고, 앱은 **신원 지문**에 매여 있다.
//  둘을 잇는 사실 — "이 지문의 기기가 지금 이 케이블에 꽂혀 있다" — 은 AOA 링크만 안다.
//  그 사실을 앱이 알려고 링크를 직접 붙들면 앱이 전송을 알게 되고(원칙 4 위반), 세션을
//  거치게 하면 세션이 입력을 알게 된다(원칙 1 위반).
//
//  그래서 가운데에 등록소를 둔다. 링크는 채우고, 앱은 묻는다. 서로를 모른다.
//
//  ⚠ **USB에서만 존재한다.** AOAv2 HID는 EP0 제어 전송이라 케이블이 있어야 한다.
//  Wi-Fi로 붙은 기기는 `inputs(for:)`가 nil을 준다 — 그건 오류가 아니라 사실이고,
//  호출부는 그걸 사용자에게 설명해야 한다("입력하려면 케이블을 연결하세요").
//

import Foundation

/// 한 기기에 붙는 주입기 한 벌.
@MainActor
struct DeviceInputs {
    let deviceID: String
    let touch: TouchInjector
    let keyboard: KeyboardInjector

    /// 이 기기가 AOAv2를 지원하는가. 프로토콜 1이면 HID 자체가 없다.
    var isSupported: Bool { touch.isSupported }

    /// 둘 다 등록한다. 하나라도 실패하면 false — 반쪽만 켜 두면 원인을 못 찾는다.
    @discardableResult
    func register() async -> Bool {
        let t = await touch.register()
        let k = await keyboard.register()
        return t && k
    }

    func unregister() {
        touch.unregister()
        keyboard.unregister()
    }
}

@MainActor
final class DeviceInputHub {
    static let shared = DeviceInputHub()
    private init() {}

    private var byDevice: [String: DeviceInputs] = [:]

    /// 지문 → USB deviceID. **AOA 링크가 채운다** — 그쪽만 아는 사실이다.
    var deviceIDForFingerprint: ((Fingerprint) -> String?)?

    /// 케이블이 꽂혔다. 주입기를 **만들기만** 한다 — `register()`를 불러야 비로소
    /// Android에 HID 기기가 생긴다. 그 구분이 이 계층의 안전장치다.
    func register(deviceID: String, aoa: PeriAoaServer) {
        guard byDevice[deviceID] == nil else { return }
        byDevice[deviceID] = DeviceInputs(
            deviceID: deviceID,
            touch: TouchInjector(aoa: aoa, deviceID: deviceID),
            keyboard: KeyboardInjector(aoa: aoa, deviceID: deviceID))
    }

    /// 케이블이 빠졌다. **unregister를 보내지 않는다** — EP0가 이미 사라졌고, 커널 쪽
    /// HID 기기는 기기가 detach되며 같이 정리된다.
    func forget(deviceID: String) {
        byDevice.removeValue(forKey: deviceID)
    }

    /// 이 기기에 입력을 넣을 수 있는가. nil = 케이블이 없다(또는 AOAv1).
    func inputs(for fingerprint: Fingerprint) -> DeviceInputs? {
        guard let id = deviceIDForFingerprint?(fingerprint) else { return nil }
        return byDevice[id]
    }

    /// USB 기기 id로 직접. **진단 화면 전용** — 세션이 서기 전에도 HID를 시험해 볼 수 있어야
    /// 하는데, 그때는 아직 지문이 없다.
    func inputs(deviceID: String) -> DeviceInputs? { byDevice[deviceID] }
}
