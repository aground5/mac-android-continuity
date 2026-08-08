#!/bin/sh
# 상태 기계 모델 검사. Xcode 테스트 타깃 없이 순수 파일만 컴파일해 돌린다.
#
# 기계마다 파일이 따로다 — 그게 DESIGN.md 원칙 3이다. 새 서비스를 넣으면 여기에
# 기계 파일 하나와 테스트 파일 하나가 붙고, 기존 줄은 바뀌지 않는다.
set -e
cd "$(dirname "$0")"
SRC="../Peri Display/Peri Display"
OUT=$(mktemp -d)
swiftc -O -o "$OUT/machines" \
    "$SRC/App/DeviceMachine.swift" \
    "$SRC/Services/ExtendMachine.swift" \
    "$SRC/Services/ShareMachine.swift" \
    "$SRC/BLE/PairingMachine.swift" \
    Harness.swift \
    DeviceTests.swift \
    ExtendTests.swift \
    ShareTests.swift \
    PairingTests.swift \
    main.swift
"$OUT/machines"
