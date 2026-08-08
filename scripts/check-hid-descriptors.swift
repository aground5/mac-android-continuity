// HID 리포트 디스크립터 검사기.
//
// 왜 필요한가: 디스크립터가 틀리면 **아무 일도 일어나지 않는다.** 커널이 파싱에 실패하면
// 입력 기기를 아예 안 만들고, 에러도 우리 쪽으로 오지 않는다(AOA HID는 단방향이다).
// 리포트 길이가 한 바이트만 어긋나도 모든 리포트가 조용히 버려진다.
//
// 그래서 손으로 센 비트 수를 믿지 않고 여기서 기계로 센다:
//   · 아이템이 문법에 맞게 끝나는가
//   · Collection / End Collection 짝이 맞는가
//   · Usage Page가 Usage보다 먼저 오는가 (전역 항목은 컬렉션을 넘어 살아남는다)
//   · 입력 리포트의 총 비트 수 → **바이트 수**가 우리가 보내는 리포트 길이와 같은가
//
// 실행: swift scripts/check-hid-descriptors.swift

import Foundation

struct Item {
    let tag: UInt8      // bTag
    let type: UInt8     // bType 0=Main 1=Global 2=Local
    let value: UInt32
    let raw: [UInt8]
}

enum HIDParse {
    /// 짧은 아이템만 다룬다 — 우리 디스크립터에 긴 아이템(0xFE)은 없다.
    static func items(_ d: [UInt8]) -> [Item]? {
        var out: [Item] = []
        var i = 0
        while i < d.count {
            let b = d[i]
            if b == 0xFE { return nil }                  // long item — 미지원
            let size = Int(b & 0x03)
            let n = size == 3 ? 4 : size
            let tag = (b >> 4) & 0x0F
            let type = (b >> 2) & 0x03
            guard i + 1 + n <= d.count else { return nil }   // 잘린 아이템
            var v: UInt32 = 0
            for k in 0..<n { v |= UInt32(d[i + 1 + k]) << (8 * k) }
            out.append(Item(tag: tag, type: type, value: v,
                            raw: Array(d[i...(i + n)])))
            i += 1 + n
        }
        return out
    }

    struct Result {
        var inputBits = 0
        var depth = 0
        var maxDepth = 0
        var balanced = true
        var usageBeforePage = false
        var problems: [String] = []
    }

    static func analyze(_ d: [UInt8]) -> Result? {
        guard let items = items(d) else { return nil }
        var r = Result()
        var reportSize = 0
        var reportCount = 0
        var sawUsagePage = false

        for it in items {
            switch (it.type, it.tag) {
            case (0, 0x8):                     // Main / Input
                r.inputBits += reportSize * reportCount
            case (0, 0xA):                     // Main / Collection
                r.depth += 1; r.maxDepth = max(r.maxDepth, r.depth)
            case (0, 0xC):                     // Main / End Collection
                r.depth -= 1
                if r.depth < 0 { r.balanced = false }
            case (1, 0x0):                     // Global / Usage Page
                sawUsagePage = true
            case (1, 0x7):                     // Global / Report Size
                reportSize = Int(it.value)
            case (1, 0x9):                     // Global / Report Count
                reportCount = Int(it.value)
            case (2, 0x0):                     // Local / Usage
                if !sawUsagePage { r.usageBeforePage = true }
            default:
                break
            }
        }
        if r.depth != 0 { r.balanced = false }
        return r
    }
}

// ── 대상 디스크립터 ─────────────────────────────────────────────────────────
//
// 소스와 **같은 바이트**여야 의미가 있다. Swift 파일에서 배열 리터럴을 뽑아 온다.

/// 소스에 `static let name = 12` 형태로 선언된 정수 상수들.
///
/// 디스크립터에는 `UInt8(maxKeys)` 같은 항목이 섞여 있다. 그걸 무시하면 **바이트가 하나
/// 빠진 채로** 파싱돼 그 뒤가 전부 밀린다 — 처음 이 검사기를 돌렸을 때 정확히 그래서
/// 멀쩡한 디스크립터가 깨진 것처럼 나왔다. 같은 파일에서 값을 읽어 채운다.
func intConstants(in src: String) -> [String: Int] {
    var out: [String: Int] = [:]
    let re = try! NSRegularExpression(pattern: #"static\s+let\s+(\w+)\s*(?::\s*\w+\s*)?=\s*(\d+)"#)
    let ns = src as NSString
    for m in re.matches(in: src, range: NSRange(location: 0, length: ns.length)) {
        out[ns.substring(with: m.range(at: 1))] = Int(ns.substring(with: m.range(at: 2))) ?? 0
    }
    return out
}

/// marker 뒤 첫 `[ … ]` 블록의 바이트를 순서대로 뽑는다.
/// `Data([...])`와 `append(contentsOf: [...])`를 둘 다 받는다.
func extractDescriptor(file: String, marker: String) -> [UInt8]? {
    guard let src = try? String(contentsOfFile: file, encoding: .utf8),
          let start = src.range(of: marker) else { return nil }
    let consts = intConstants(in: src)
    let tail = src[start.upperBound...]
    guard let open = tail.firstIndex(of: "[") else { return nil }

    var depth = 1
    var idx = tail.index(after: open)
    var body = ""
    while idx < tail.endIndex, depth > 0 {
        let c = tail[idx]
        if c == "[" { depth += 1 }
        if c == "]" { depth -= 1; if depth == 0 { break } }
        body.append(c)
        idx = tail.index(after: idx)
    }

    // 줄 주석 제거 — 주석 안의 0x.. 를 바이트로 세면 안 된다.
    let cleaned = body.split(separator: "\n", omittingEmptySubsequences: false).map { line -> Substring in
        if let c = line.range(of: "//") { return line[line.startIndex..<c.lowerBound] }
        return line
    }.joined(separator: "\n")

    // 16진 바이트 또는 `UInt8(상수)` — **나오는 순서 그대로**.
    let re = try! NSRegularExpression(pattern: #"0x[0-9A-Fa-f]{1,2}|UInt8\((\w+)\)"#)
    let ns = cleaned as NSString
    var out: [UInt8] = []
    for m in re.matches(in: cleaned, range: NSRange(location: 0, length: ns.length)) {
        let tok = ns.substring(with: m.range)
        if tok.hasPrefix("0x") {
            out.append(UInt8(tok.dropFirst(2), radix: 16) ?? 0)
        } else {
            let name = ns.substring(with: m.range(at: 1))
            guard let v = consts[name] else {
                FileHandle.standardError.write("상수를 못 찾았다: \(name)\n".data(using: .utf8)!)
                return nil
            }
            out.append(UInt8(v))
        }
    }
    return out
}

struct Target {
    let name: String
    let file: String
    let marker: String
    /// 주입기가 실제로 보내는 리포트 바이트 수.
    let reportBytes: Int
}

let root = FileManager.default.currentDirectoryPath
let src = "\(root)/MacApp/Peri Display/Peri Display/Input"

let targets = [
    Target(name: "키보드", file: "\(src)/KeyboardInjector.swift",
           marker: "static let reportDescriptor", reportBytes: 8),
    // 디지타이저는 손가락 컬렉션을 반복해 조립하므로 바이트 배열이 한 덩이가 아니다.
    // 여기서는 **전체 컬렉션 하나**(finger)와 꼬리를 따로 세어 합친다 — 아래에서 처리.
]

var failed = false

for t in targets {
    guard let bytes = extractDescriptor(file: t.file, marker: t.marker) else {
        print("❌ \(t.name): 디스크립터를 못 읽었다 (\(t.file))"); failed = true; continue
    }
    guard let r = HIDParse.analyze(bytes) else {
        print("❌ \(t.name): 디스크립터 파싱 실패 — 아이템이 잘렸다"); failed = true; continue
    }
    var ok = true
    print("\(t.name) — \(bytes.count)바이트, 컬렉션 깊이 \(r.maxDepth)")
    if !r.balanced { print("   ❌ Collection/End Collection 짝이 안 맞는다"); ok = false }
    if r.usageBeforePage { print("   ❌ Usage Page보다 Usage가 먼저 나온다"); ok = false }
    if r.inputBits % 8 != 0 {
        print("   ❌ 입력 리포트가 \(r.inputBits)비트 — 바이트 경계가 안 맞는다"); ok = false
    }
    let computed = r.inputBits / 8
    if computed != t.reportBytes {
        print("   ❌ 리포트 길이 불일치: 디스크립터 \(computed)B vs 송신 \(t.reportBytes)B"); ok = false
    } else {
        print("   ✅ 리포트 \(computed)B — 송신 길이와 일치")
    }
    if ok { print("   ✅ 문법 정상") } else { failed = true }
}

// 디지타이저: 헤더 + finger×N + 꼬리를 실제 소스 상수로 재조립해 센다.
do {
    let file = "\(src)/TouchInjector.swift"
    guard let finger = extractDescriptor(file: file, marker: "private static let fingerCollection"),
          let head = extractDescriptor(file: file, marker: "static let reportDescriptor"),
          let tail = extractDescriptor(file: file, marker: "d.append(contentsOf:")
    else {
        print("❌ 디지타이저: 디스크립터 조각을 못 읽었다"); failed = true; exit(failed ? 1 : 0)
    }
    // 접점 수도 소스에서 읽는다 — 여기 숫자를 박아 두면 소스가 바뀔 때 검사가 거짓말을 한다.
    guard let src = try? String(contentsOfFile: file, encoding: .utf8),
          let maxContacts = intConstants(in: src)["maxContacts"] else {
        print("❌ 디지타이저: maxContacts를 못 읽었다"); exit(1)
    }
    var full = head
    for _ in 0..<maxContacts { full += finger }
    full += tail
    guard let r = HIDParse.analyze(full) else {
        print("❌ 디지타이저: 파싱 실패"); failed = true; exit(1)
    }
    let expect = maxContacts * 6 + 1     // sendReport가 만드는 길이
    print("디지타이저 — \(full.count)바이트, 접점 \(maxContacts)개, 컬렉션 깊이 \(r.maxDepth)")
    var ok = true
    if !r.balanced { print("   ❌ Collection 짝이 안 맞는다"); ok = false }
    if r.usageBeforePage { print("   ❌ Usage Page보다 Usage가 먼저"); ok = false }
    if r.inputBits % 8 != 0 { print("   ❌ \(r.inputBits)비트 — 바이트 경계 불일치"); ok = false }
    let computed = r.inputBits / 8
    if computed != expect {
        print("   ❌ 리포트 길이 불일치: 디스크립터 \(computed)B vs 송신 \(expect)B"); ok = false
    } else {
        print("   ✅ 리포트 \(computed)B — 송신 길이와 일치")
    }
    if ok { print("   ✅ 문법 정상") } else { failed = true }
}

print(failed ? "\n❌ HID 디스크립터 검사 실패" : "\n✅ HID 디스크립터 검사 통과")
exit(failed ? 1 : 0)
