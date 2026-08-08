//
//  DisplaySerialStore.swift
//  Peri Display
//
//  기기(신원 fingerprint)별 가상 디스플레이 serial 영구 매핑.
//
//  macOS는 디스플레이 배치/스케일을 vendor/product/serial 정체성별로 저장한다. 예전엔 전역으로
//  serial 하나만 관리해 모든 가상 디스플레이가 같은 정체성을 가졌고 → 두 기기를 동시에 붙이면
//  두 번째 CGVirtualDisplay가 첫 번째와 충돌(같은 디스플레이로 인식/미러)해 영상이 안 나왔다.
//
//  이제 기기마다 고유 serial을 부여한다:
//   (a) 동시 연결 시 서로 다른 디스플레이로 등록 → N대 동시 송출 가능.
//   (b) 기기별 serial이 고정 → 사용자가 시스템 설정에서 잡은 배치/해상도가 재연결에도 유지.
//

import Foundation

enum DisplaySerialStore {
    private static let key = "PeriVirtualDisplaySerials"   // [fingerprint: serial(NSNumber)]
    private static let lock = NSLock()

    /// 해당 기기의 serial 반환 (없으면 기존과 겹치지 않는 랜덤을 생성·영구 저장).
    static func serial(for fingerprint: String) -> UInt32 {
        lock.lock(); defer { lock.unlock() }
        var map = currentMap()
        if let existing = map[fingerprint]?.uint32Value, existing != 0 { return existing }
        let s = freshSerial(avoiding: usedSet(map))
        map[fingerprint] = NSNumber(value: s)
        UserDefaults.standard.set(map, forKey: key)
        // 즉시 플러시: 디버깅 중 앱이 강제 종료(Xcode Stop)되면 지연 기록이 유실돼 매 실행마다
        // 새 serial → macOS가 매번 새 디스플레이로 인식 → 사용자가 잡은 배치/스케일이 초기화된다.
        UserDefaults.standard.synchronize()
        return s
    }

    /// 해당 기기의 serial을 새 랜덤으로 교체 (presentation 모드로 배치가 오염됐을 때 탈출용).
    @discardableResult
    static func rotate(_ fingerprint: String) -> UInt32 {
        lock.lock(); defer { lock.unlock() }
        var map = currentMap()
        let s = freshSerial(avoiding: usedSet(map))
        map[fingerprint] = NSNumber(value: s)
        UserDefaults.standard.set(map, forKey: key)
        // 즉시 플러시: 디버깅 중 앱이 강제 종료(Xcode Stop)되면 지연 기록이 유실돼 매 실행마다
        // 새 serial → macOS가 매번 새 디스플레이로 인식 → 사용자가 잡은 배치/스케일이 초기화된다.
        UserDefaults.standard.synchronize()
        return s
    }

    // MARK: -

    private static func currentMap() -> [String: NSNumber] {
        (UserDefaults.standard.dictionary(forKey: key) as? [String: NSNumber]) ?? [:]
    }

    private static func usedSet(_ map: [String: NSNumber]) -> Set<UInt32> {
        Set(map.values.map { $0.uint32Value })
    }

    /// 0과 기존 serial을 피한 랜덤 32비트값.
    private static func freshSerial(avoiding used: Set<UInt32>) -> UInt32 {
        var s = UInt32.random(in: 1...UInt32.max)
        while s == 0 || used.contains(s) { s = UInt32.random(in: 1...UInt32.max) }
        return s
    }
}
