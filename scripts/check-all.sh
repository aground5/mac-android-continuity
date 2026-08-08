#!/usr/bin/env bash
#
# 손으로 확인할 수 없는 것들을 전부 기계로 검사한다.
#
# 각 항목은 **실제로 물린 버그**에서 나왔다 — 하나씩 하루를 잡아먹은 것들이고,
# 전부 "로그는 멀쩡한데 아무 일도 안 일어난다" 종류였다.
#
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
fail=0
run() {
  echo "── $1 ─────────────────────────────"
  shift
  if "$@"; then echo; else echo "   ↑ 실패"; fail=1; echo; fi
}

run "원칙 1 (세션 계층이 앱을 모르는가)"  ./scripts/check-layering.sh
run "표시 경로 (프레임이 그려질 수 있는가)" ./scripts/check-render.sh
run "HID 디스크립터 (커널이 읽을 수 있는가)" swift scripts/check-hid-descriptors.swift
run "상태 기계 모델 검사"                  bash -c 'cd MacApp/MachineTests && ./run.sh'

if [ "$fail" -eq 0 ]; then echo "✅ 전부 통과"; else echo "❌ 실패한 검사가 있다"; fi
exit "$fail"
