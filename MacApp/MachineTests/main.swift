//
//  main.swift
//  모델 검사 실행기.
//
//  기계마다 **따로** 돈다. 그게 DESIGN.md 원칙 3이다 — "각 서비스 상태 기계는 독립적으로
//  모델 검사된다." 한 파일에 몰아넣으면 서비스가 늘 때마다 상태공간이 곱해지고, 어느 기계의
//  어느 불변식이 깨졌는지도 흐려진다.
//
//      MacApp/MachineTests/run.sh
//

import Foundation

runDeviceTests()
runExtendTests()
runShareTests()
runPairingTests()

if failures.isEmpty {
    print("✅ \(checks)개 검사 전부 통과")
    exit(0)
} else {
    print("❌ \(failures.count)/\(checks) 실패\n")
    for f in failures { print("  · \(f)") }
    exit(1)
}
