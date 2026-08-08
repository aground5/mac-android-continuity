//
//  TouchInjector.swift
//  Peri Display
//
//  AOAv2 HID 디지타이저 — Mac이 Android **커널**에 터치를 주입한다.
//
//  왜 여기가 세션 바깥인가
//  ────────────────────
//  AOAv2의 HID 명령(54~57)은 **EP0 제어 전송**이다. 전용 USB 인터페이스도, bulk 파이프도
//  쓰지 않는다. 즉 우리 프레임 계층도 AEAD 세션도 타지 않는다 — `PeriAoaServer`가 USB
//  스택에 직접 쏜다. 그래서 이 파일은 `Transport/`가 아니라 별도로 두고, 프로토콜 버전이
//  올라가도 영향을 받지 않는다.
//
//  ⚠ **Android는 이 입력을 거부할 수 없다.** 커널이 HID 기기로 받아들이고 InputReader가
//  일반 터치스크린과 구분하지 못한다. 페어링·TrustStore는 EP0를 막지 못한다.
//  **방어선은 우리 자신뿐이다** — 등록은 언제나 명시적 호출이어야 하고(자동 등록 금지),
//  인증된 세션 뒤에서만 부르고, 세션이 끝나면 반드시 [unregister]로 짝을 맞춘다.
//
//  좌표계
//  ────
//  디스크립터가 X·Y를 논리값 0…32767로 선언하므로, 커널이 그 범위를 실제 터치스크린
//  크기로 스케일한다. 호출자는 정규화 좌표(0…1, 좌상단 원점)만 넘긴다.
//  **회전·디스플레이 연결 동작은 실기기로만 알 수 있다** — 스파이크의 관찰 대상.
//

import Foundation
import CoreGraphics

nonisolated final class TouchInjector: @unchecked Sendable {

    /// 이 기기에 등록할 HID id. 기기당 여러 HID를 둘 수 있으므로(키보드 등) 값을 나눠 쓴다.
    static let digitizerHidID: UInt16 = 1
    /// 디스크립터가 선언한 논리 좌표 상한.
    static let logicalMax = 32767
    /// 디스크립터에 접점 컬렉션을 몇 개 넣었는가. 리포트 길이가 여기 매인다.
    static let maxContacts = 2

    private let aoa: PeriAoaServer
    private let deviceID: String
    private let lock = NSLock()
    /// 접점별 (눌림, x, y). 리포트는 언제나 전체 상태를 실어 보낸다(HID는 상태 전송이다).
    private var contacts: [(down: Bool, x: Int, y: Int)]
    private var registered = false

    init(aoa: PeriAoaServer, deviceID: String) {
        self.aoa = aoa
        self.deviceID = deviceID
        self.contacts = Array(repeating: (false, 0, 0), count: Self.maxContacts)
    }

    var isRegistered: Bool { lock.lock(); defer { lock.unlock() }; return registered }

    /// 이 기기가 AOAv2를 지원하는가. HID는 프로토콜 2 이상에서만 동작한다.
    var isSupported: Bool { aoa.protocolVersion(of: deviceID) >= 2 }

    // MARK: - 수명

    /// 디지타이저를 등록한다. **호출자가 인증 상태를 책임진다** — 이 클래스는 검사하지 않는다.
    ///
    /// `async`인 이유: 등록은 결과를 알아야 해서 EP0 제어 전송을 동기로 기다린다(요청당 최대 1초).
    /// 그걸 MainActor에서 하면 상태 기계의 시계와 UI가 그동안 멈춘다 — 이 앱에서 이미 한 번
    /// 대가를 치른 실수라(`NSAlert.runModal`) 대기를 여기서 격리한다.
    @discardableResult
    func register() async -> Bool {
        lock.lock()
        if registered { lock.unlock(); return true }
        lock.unlock()

        let aoa = self.aoa, deviceID = self.deviceID
        let ok = await Task.detached(priority: .userInitiated) {
            aoa.registerHid(Self.digitizerHidID,
                            descriptor: Self.reportDescriptor,
                            onDevice: deviceID)
        }.value
        if ok {
            lock.lock(); registered = true; lock.unlock()
            // 등록 직후 상태를 한 번 확정한다 — 커널이 남은 접점을 눌린 채로 오해하지 않게.
            sendReport()
        }
        return ok
    }

    func unregister() {
        lock.lock()
        guard registered else { lock.unlock(); return }
        // 떠나기 전에 모든 접점을 뗀다. 안 그러면 안드로이드가 손가락이 눌린 채 사라진 걸로 본다.
        contacts = Array(repeating: (false, 0, 0), count: Self.maxContacts)
        lock.unlock()
        sendReport()
        aoa.unregisterHid(Self.digitizerHidID, onDevice: deviceID)
        lock.lock(); registered = false; lock.unlock()
    }

    // MARK: - 입력

    /// 접점 하나의 상태를 갱신하고 즉시 리포트를 보낸다. `p`는 정규화 좌표(0…1).
    func set(contact index: Int, down: Bool, at p: CGPoint) {
        guard index >= 0, index < Self.maxContacts else { return }
        let x = Self.quantize(p.x), y = Self.quantize(p.y)
        lock.lock()
        // 떼는 순간에도 좌표는 유지한다 — 마지막 위치가 유효해야 커널이 탭으로 해석한다.
        contacts[index] = (down, x, y)
        lock.unlock()
        sendReport()
    }

    /// 눌린 접점을 전부 뗀다. **좌표는 건드리지 않는다** — `set(...at: .zero)`로 떼면
    /// 손가락이 좌상단으로 순간이동한 뒤 떨어진 꼴이라 커널이 그걸 스와이프로 읽는다.
    /// 창이 포커스를 잃을 때 부른다.
    func releaseAll() {
        lock.lock()
        let had = contacts.contains { $0.down }
        for i in contacts.indices { contacts[i].down = false }
        lock.unlock()
        if had { sendReport() }
    }

    /// 짧은 탭. 눌림→뗌 사이에 최소 간격을 둬야 커널이 두 상태를 별개 이벤트로 본다.
    func tap(at p: CGPoint, holdMs: UInt64 = 40) async {
        set(contact: 0, down: true, at: p)
        try? await Task.sleep(for: .milliseconds(holdMs))
        set(contact: 0, down: false, at: p)
    }

    /// 드래그. `steps` 만큼 중간 지점을 보낸다 — 커널·InputReader가 속도를 재려면 이동이 필요하다.
    func swipe(from a: CGPoint, to b: CGPoint,
               durationMs: UInt64 = 300, steps: Int = 24) async {
        set(contact: 0, down: true, at: a)
        let per = max(1, durationMs / UInt64(max(1, steps)))
        for i in 1...max(1, steps) {
            let t = CGFloat(i) / CGFloat(max(1, steps))
            let p = CGPoint(x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t)
            try? await Task.sleep(for: .milliseconds(per))
            set(contact: 0, down: true, at: p)
        }
        set(contact: 0, down: false, at: b)
    }

    // MARK: - 리포트

    /// 리포트 레이아웃 (Report ID 없음 — 입력 리포트가 하나뿐이라 붙일 이유가 없고,
    /// 붙였는데 상대가 기대하지 않으면 **모든 리포트가 조용히 어긋난다**):
    ///
    ///   접점마다 [1B tip(bit0)][1B contactID][2B X LE][2B Y LE]  ×2
    ///   마지막에 [1B contactCount]
    private func sendReport() {
        lock.lock()
        let snapshot = contacts
        let live = registered
        lock.unlock()
        guard live else { return }

        var r = Data(capacity: Self.maxContacts * 6 + 1)
        var count = 0
        for (i, c) in snapshot.enumerated() {
            r.append(c.down ? 1 : 0)
            r.append(UInt8(i))                    // contact identifier
            r.append(UInt8(c.x & 0xFF)); r.append(UInt8((c.x >> 8) & 0xFF))
            r.append(UInt8(c.y & 0xFF)); r.append(UInt8((c.y >> 8) & 0xFF))
            if c.down { count += 1 }
        }
        r.append(UInt8(count))
        aoa.sendHidEvent(r, hid: Self.digitizerHidID, toDevice: deviceID)
    }

    private static func quantize(_ v: CGFloat) -> Int {
        Int((max(0, min(1, v)) * CGFloat(logicalMax)).rounded())
    }

    // MARK: - 리포트 디스크립터

    /// Win8식 멀티터치 디지타이저. `hid-multitouch`가 이 모양을 보고 `INPUT_PROP_DIRECT`
    /// 터치스크린으로 잡는다 — Android InputReader가 마우스·태블릿이 아니라 터치로 분류하는 근거.
    ///
    /// Contact Count Maximum **Feature 리포트는 넣지 않았다.** AOA HID는 단방향이라
    /// (액세서리→기기) 커널이 GET_REPORT로 되물을 방법이 없고, 없으면 드라이버가 디스크립터에
    /// 선언된 접점 수를 쓴다.
    static let reportDescriptor: Data = {
        var d = Data([
            0x05, 0x0D,        // Usage Page (Digitizers)
            0x09, 0x04,        // Usage (Touch Screen)
            0xA1, 0x01,        // Collection (Application)
        ])
        for _ in 0..<maxContacts { d.append(fingerCollection) }
        d.append(contentsOf: [
            0x05, 0x0D,        //   Usage Page (Digitizers)
            0x09, 0x54,        //   Usage (Contact Count)
            0x15, 0x00,        //   Logical Minimum (0)
            0x25, UInt8(maxContacts),
            0x75, 0x08,        //   Report Size (8)
            0x95, 0x01,        //   Report Count (1)
            0x81, 0x02,        //   Input (Data,Var,Abs)
            0xC0,              // End Collection
        ])
        return d
    }()

    /// 접점 하나. **전역 항목(Usage Page·Logical Max·Report Size)을 매번 다시 선언한다** —
    /// HID 전역 항목은 컬렉션을 넘어 그대로 살아남으므로, 앞 접점이 남긴 값을 물려받으면
    /// 두 번째 접점의 Tip Switch가 Generic Desktop 페이지로 해석된다.
    private static let fingerCollection = Data([
        0x09, 0x22,        //   Usage (Finger)
        0xA1, 0x02,        //   Collection (Logical)
        0x05, 0x0D,        //     Usage Page (Digitizers)
        0x09, 0x42,        //     Usage (Tip Switch)
        0x15, 0x00,        //     Logical Minimum (0)
        0x25, 0x01,        //     Logical Maximum (1)
        0x75, 0x01,        //     Report Size (1)
        0x95, 0x01,        //     Report Count (1)
        0x81, 0x02,        //     Input (Data,Var,Abs)
        0x75, 0x07,        //     Report Size (7)
        0x95, 0x01,        //     Report Count (1)
        0x81, 0x03,        //     Input (Cnst,Var,Abs)      ← tip switch 뒤 패딩
        0x09, 0x51,        //     Usage (Contact Identifier)
        0x25, 0x7F,        //     Logical Maximum (127)
        0x75, 0x08,        //     Report Size (8)
        0x95, 0x01,        //     Report Count (1)
        0x81, 0x02,        //     Input (Data,Var,Abs)
        0x05, 0x01,        //     Usage Page (Generic Desktop)
        0x15, 0x00,        //     Logical Minimum (0)
        0x26, 0xFF, 0x7F,  //     Logical Maximum (32767)
        0x75, 0x10,        //     Report Size (16)
        0x95, 0x01,        //     Report Count (1)
        0x09, 0x30,        //     Usage (X)
        0x81, 0x02,        //     Input (Data,Var,Abs)
        0x09, 0x31,        //     Usage (Y)
        0x81, 0x02,        //     Input (Data,Var,Abs)
        0xC0,              //   End Collection
    ])
}
