//
//  Harness.swift
//  세 기계가 함께 쓰는 미니 검사 하네스.
//
//  Xcode 테스트 타깃을 만들지 않는 이유: 이 프로젝트는 파일 동기화 그룹을 쓰고 있어
//  `project.pbxproj`를 손대야 하는데, 생성물을 함부로 고치지 않는다는 규칙이 있다.
//  기계 파일들은 Foundation 말고는 아무것에도 의존하지 않으므로 그냥 같이 컴파일하면 된다:
//
//      MacApp/MachineTests/run.sh
//

import Foundation

var failures: [String] = []
var checks = 0

func check(_ cond: Bool, _ what: String) {
    checks += 1
    if !cond { failures.append(what) }
}

func expect<T: Equatable>(_ got: T, _ want: T, _ what: String) {
    checks += 1
    if got != want { failures.append("\(what): \(got) ≠ 기대값 \(want)") }
}

func fail(_ what: String) {
    checks += 1
    failures.append(what)
}

/// 도달 가능한 상태 전부를 BFS로 모은다. 기계마다 사건 목록만 다르다.
func reachableStates<S: Hashable, E>(
    from start: S, events: [E], step: (S, E) -> S
) -> [S] {
    var seen: Set<S> = [start]
    var order = [start]
    var queue = [start]
    while let s = queue.popLast() {
        for ev in events {
            let n = step(s, ev)
            if seen.insert(n).inserted { queue.append(n); order.append(n) }
        }
    }
    return order
}
