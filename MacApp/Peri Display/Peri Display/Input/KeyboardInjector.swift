//
//  KeyboardInjector.swift
//  Peri Display
//
//  AOAv2 HID 키보드 — Mac이 Android **커널**에 키 입력을 주입한다. [TouchInjector]의 짝.
//
//  왜 문자가 아니라 키 위치를 보내는가
//  ──────────────────────────────────
//  우리는 **물리 키보드를 흉내 내는 것**이지 문자열을 밀어 넣는 게 아니다. HID 키보드는
//  "어느 자리의 키가 눌렸다"를 보내고, 배열(한글·Dvorak·QWERTY)을 해석하는 건 받는 쪽이다.
//  그래서 macOS 가상 키코드(kVK_*, 이미 **물리 위치**다)를 HID Usage로 옮긴다.
//
//  문자를 보내려 했다면(`charactersIgnoringModifiers`) Mac 배열로 해석된 결과를 Android
//  배열로 다시 밀어 넣는 셈이라, 두 배열이 다른 순간 어긋난다. 그리고 한글 입력기는 애초에
//  키 위치를 받아야 조합을 시작한다 — 문자를 보내면 조합 자체가 일어나지 않는다.
//
//  ⚠ 주입 위험은 [TouchInjector]와 같다. 등록은 언제나 명시적이고, 세션이 끝나면 반드시
//  [unregister]로 짝을 맞춘다.
//
//  수식키 매핑 (정책)
//  ─────────────────
//    ⇧ → Shift    ⌥ → Alt    ⌘ → **Ctrl**    ⌃ → **Ctrl**
//
//  ⌘를 Meta(GUI)가 아니라 Ctrl로 보내는 건 의도다. Android의 복사·붙여넣기·전체선택은
//  전부 Ctrl 조합이라, ⌘를 그대로 Meta로 보내면 손에 익은 단축키가 **전부 아무 일도 하지
//  않는다.** 대신 Meta는 닿지 않게 되는데, Android에서 Meta는 주로 단축키 목록을 여는
//  용도라 잃는 게 적다.
//

import AppKit
import Foundation

nonisolated final class KeyboardInjector: @unchecked Sendable {

    /// HID id. 디지타이저(1)와 겹치면 안 된다 — 기기당 여러 HID를 번호로 가른다.
    static let keyboardHidID: UInt16 = 2
    /// 부트 키보드 리포트가 한 번에 실을 수 있는 키 개수.
    static let maxKeys = 6

    private let aoa: PeriAoaServer
    private let deviceID: String
    private let lock = NSLock()
    /// 지금 눌려 있는 키들(HID usage). 순서가 곧 리포트 순서다.
    private var pressed: [UInt8] = []
    private var modifiers: UInt8 = 0
    private var registered = false

    init(aoa: PeriAoaServer, deviceID: String) {
        self.aoa = aoa
        self.deviceID = deviceID
    }

    var isRegistered: Bool { lock.lock(); defer { lock.unlock() }; return registered }
    var isSupported: Bool { aoa.protocolVersion(of: deviceID) >= 2 }

    // MARK: - 수명

    @discardableResult
    func register() async -> Bool {
        lock.lock()
        if registered { lock.unlock(); return true }
        lock.unlock()

        let aoa = self.aoa, deviceID = self.deviceID
        let ok = await Task.detached(priority: .userInitiated) {
            aoa.registerHid(Self.keyboardHidID,
                            descriptor: Self.reportDescriptor,
                            onDevice: deviceID)
        }.value
        if ok {
            lock.lock(); registered = true; lock.unlock()
            sendReport()   // 빈 리포트로 상태를 확정 — 남은 키가 눌린 채로 오해되지 않게
        }
        return ok
    }

    func unregister() {
        lock.lock()
        guard registered else { lock.unlock(); return }
        pressed.removeAll()
        modifiers = 0
        lock.unlock()
        sendReport()       // 떠나기 전에 전부 뗀다 — 안 그러면 키가 눌린 채 사라진다
        aoa.unregisterHid(Self.keyboardHidID, onDevice: deviceID)
        lock.lock(); registered = false; lock.unlock()
    }

    // MARK: - 입력

    /// 키 하나의 눌림 상태. `virtualKey`는 `NSEvent.keyCode`(kVK_*).
    func set(virtualKey: UInt16, down: Bool) {
        guard let usage = Self.hidUsage(forVirtualKey: virtualKey) else { return }
        lock.lock()
        if down {
            if !pressed.contains(usage) {
                // 상한을 넘으면 가장 오래된 키를 민다. 실제 키보드의 롤오버와 같은 동작이고,
                // 넘친 키를 버리면 "안 눌리는 키"가 생긴다.
                if pressed.count >= Self.maxKeys { pressed.removeFirst() }
                pressed.append(usage)
            }
        } else {
            pressed.removeAll { $0 == usage }
        }
        lock.unlock()
        sendReport()
    }

    /// 수식키 상태를 통째로 갱신한다(`NSEvent.modifierFlags`).
    ///
    /// 개별 키가 아니라 **상태 전체**를 받는 이유: macOS는 수식키를 `flagsChanged` 하나로
    /// 알리고 어느 쪽 ⇧가 눌렸는지도 플래그로만 준다. 매번 전체를 다시 계산하면 눌림·뗌을
    /// 따로 추적하다 어긋날 일이 없다.
    func setModifiers(_ flags: NSEvent.ModifierFlags) {
        var m: UInt8 = 0
        if flags.contains(.shift)   { m |= 0x02 }   // Left Shift
        if flags.contains(.option)  { m |= 0x04 }   // Left Alt
        // ⌘와 ⌃ 둘 다 Ctrl로 — 위 주석의 정책.
        if flags.contains(.command) || flags.contains(.control) { m |= 0x01 }   // Left Ctrl
        lock.lock(); modifiers = m; lock.unlock()
        sendReport()
    }

    /// 눌린 것을 전부 뗀다. 창이 포커스를 잃을 때 부른다 — 안 그러면 Android 쪽에 키가
    /// 눌린 채로 남아 자동 반복이 돈다.
    func releaseAll() {
        lock.lock()
        let had = !pressed.isEmpty || modifiers != 0
        pressed.removeAll(); modifiers = 0
        lock.unlock()
        if had { sendReport() }
    }

    // MARK: - 리포트

    /// 부트 키보드 리포트 8바이트: [수식키][예약(0)][키 usage ×6].
    private func sendReport() {
        lock.lock()
        let mods = modifiers
        let keys = pressed
        let live = registered
        lock.unlock()
        guard live else { return }

        var r = Data(capacity: 2 + Self.maxKeys)
        r.append(mods)
        r.append(0)                       // 예약 바이트 — 디스크립터의 상수 필드
        for i in 0..<Self.maxKeys { r.append(i < keys.count ? keys[i] : 0) }
        aoa.sendHidEvent(r, hid: Self.keyboardHidID, toDevice: deviceID)
    }

    // MARK: - 리포트 디스크립터

    /// 표준 부트 키보드. **출력 리포트(LED)는 넣지 않았다** — AOA HID는 액세서리→기기
    /// 단방향이라 CapsLock LED 같은 걸 되받을 통로가 없고, 선언만 해 두면 커널이 오지 않는
    /// 리포트를 기다린다.
    static let reportDescriptor = Data([
        0x05, 0x01,        // Usage Page (Generic Desktop)
        0x09, 0x06,        // Usage (Keyboard)
        0xA1, 0x01,        // Collection (Application)
        0x05, 0x07,        //   Usage Page (Keyboard/Keypad)
        0x19, 0xE0,        //   Usage Minimum (Left Control)
        0x29, 0xE7,        //   Usage Maximum (Right GUI)
        0x15, 0x00,        //   Logical Minimum (0)
        0x25, 0x01,        //   Logical Maximum (1)
        0x75, 0x01,        //   Report Size (1)
        0x95, 0x08,        //   Report Count (8)
        0x81, 0x02,        //   Input (Data,Var,Abs)   ← 수식키 8비트
        0x95, 0x01,        //   Report Count (1)
        0x75, 0x08,        //   Report Size (8)
        0x81, 0x03,        //   Input (Cnst,Var,Abs)   ← 예약 바이트
        0x95, UInt8(maxKeys),
        0x75, 0x08,        //   Report Size (8)
        0x15, 0x00,        //   Logical Minimum (0)
        0x26, 0xFF, 0x00,  //   Logical Maximum (255)
        0x05, 0x07,        //   Usage Page (Keyboard/Keypad)
        0x19, 0x00,        //   Usage Minimum (0)
        0x29, 0xFF,        //   Usage Maximum (255)
        0x81, 0x00,        //   Input (Data,Ary,Abs)   ← 동시 키 6개
        0xC0,              // End Collection
    ])

    // MARK: - 키 매핑

    /**
     macOS 가상 키코드 → HID Keyboard/Keypad(0x07) usage.

     왼쪽 값은 `Carbon/HIToolbox/Events.h`의 `kVK_*`를 그대로 옮긴 것이고(SDK에서 추출),
     오른쪽은 HID Usage Table의 Keyboard/Keypad 페이지다. 둘 다 **위치** 기준이라 이 표는
     배열과 무관하다 — 한글 자판을 쓰든 Dvorak을 쓰든 같은 자리의 키는 같은 usage다.

     빠진 키는 `nil`이라 조용히 무시된다. Fn·Eisu·Kana처럼 HID 부트 키보드에 대응이 없거나
     Mac 안에서만 의미가 있는 키들이다.
     */
    static func hidUsage(forVirtualKey vk: UInt16) -> UInt8? { map[vk] }

    private static let map: [UInt16: UInt8] = [
        // 글자 (kVK 순서가 알파벳이 아니다 — 자판 물리 배치 순서다)
        0x00: 0x04, 0x0B: 0x05, 0x08: 0x06, 0x02: 0x07, 0x0E: 0x08,   // A B C D E
        0x03: 0x09, 0x05: 0x0A, 0x04: 0x0B, 0x22: 0x0C, 0x26: 0x0D,   // F G H I J
        0x28: 0x0E, 0x25: 0x0F, 0x2E: 0x10, 0x2D: 0x11, 0x1F: 0x12,   // K L M N O
        0x23: 0x13, 0x0C: 0x14, 0x0F: 0x15, 0x01: 0x16, 0x11: 0x17,   // P Q R S T
        0x20: 0x18, 0x09: 0x19, 0x0D: 0x1A, 0x07: 0x1B, 0x10: 0x1C,   // U V W X Y
        0x06: 0x1D,                                                    // Z

        // 숫자열
        0x12: 0x1E, 0x13: 0x1F, 0x14: 0x20, 0x15: 0x21, 0x17: 0x22,   // 1 2 3 4 5
        0x16: 0x23, 0x1A: 0x24, 0x1C: 0x25, 0x19: 0x26, 0x1D: 0x27,   // 6 7 8 9 0

        // 편집·공백
        0x24: 0x28,   // Return
        0x35: 0x29,   // Escape
        0x33: 0x2A,   // Delete(백스페이스)
        0x30: 0x2B,   // Tab
        0x31: 0x2C,   // Space

        // 기호
        0x1B: 0x2D,   // -
        0x18: 0x2E,   // =
        0x21: 0x2F,   // [
        0x1E: 0x30,   // ]
        0x2A: 0x31,   // \
        0x29: 0x33,   // ;
        0x27: 0x34,   // '
        0x32: 0x35,   // `
        0x2B: 0x36,   // ,
        0x2F: 0x37,   // .
        0x2C: 0x38,   // /
        0x39: 0x39,   // CapsLock

        // 기능키
        0x7A: 0x3A, 0x78: 0x3B, 0x63: 0x3C, 0x76: 0x3D, 0x60: 0x3E, 0x61: 0x3F,   // F1–F6
        0x62: 0x40, 0x64: 0x41, 0x65: 0x42, 0x6D: 0x43, 0x67: 0x44, 0x6F: 0x45,   // F7–F12

        // 이동
        0x75: 0x4C,   // ForwardDelete
        0x77: 0x4D,   // End
        0x79: 0x4E,   // PageDown
        0x73: 0x4A,   // Home
        0x74: 0x4B,   // PageUp
        0x7C: 0x4F,   // →
        0x7B: 0x50,   // ←
        0x7D: 0x51,   // ↓
        0x7E: 0x52,   // ↑

        // 키패드
        0x4B: 0x54, 0x43: 0x55, 0x4E: 0x56, 0x45: 0x57, 0x4C: 0x58,   // / * - + Enter
        0x53: 0x59, 0x54: 0x5A, 0x55: 0x5B, 0x56: 0x5C, 0x57: 0x5D,   // 1–5
        0x58: 0x5E, 0x59: 0x5F, 0x5B: 0x60, 0x5C: 0x61, 0x52: 0x62,   // 6–9 0
        0x41: 0x63,                                                    // .
    ]
}
