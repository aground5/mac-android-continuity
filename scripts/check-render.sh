#!/usr/bin/env bash
#
# 화면 공유 표시 경로 검사 — **"프레임이 도착했다"와 "그려질 수 있다"는 다른 사실이다.**
#
# 왜 있나: 검은 화면을 하루 종일 못 찾았다. 수신·복호·재조립이 전부 정상이라 로그가 멀쩡했고,
# 정작 `CMBlockBuffer`가 `.assureMemoryNow` 없이 만들어져 **모든 프레임이 디코더에 닿기도 전에
# 조용히 버려지고 있었다.** 실패를 삼키는 `try?` 하나가 그 침묵을 만들었다.
#
# 이 검사는 진짜 H.264 비트스트림을 만들어 `ShareRenderer`와 같은 경로로 샘플을 조립하고,
# **실제로 디코드까지** 해 본다. 모델 검사(Foundation 전용)로는 닿을 수 없는 구간이다.
#
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
swiftc -O "$ROOT/scripts/check-render-path.swift" -o "$OUT/check-render-path"
"$OUT/check-render-path"
