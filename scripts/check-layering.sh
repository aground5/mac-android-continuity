#!/usr/bin/env bash
#
# DESIGN.md 원칙 1을 **실제로 검사한다.**
#
#   "세션 계층 소스에 display|video|capture|file 같은 단어가 하나도 없다."
#
# 원칙을 문서에만 적어 두면 다음 커밋에서 조용히 깨진다. 이 스크립트가 있어야
# "지켰다"가 의견이 아니라 사실이 된다.
#
# 주석은 세지 않는다 — 층이 왜 이렇게 갈렸는지 설명하려면 그 단어를 써야 하고,
# 설명을 금지하면 주석이 사라지지 코드가 깨끗해지지 않는다.
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/MacApp/Peri Display/Peri Display"
DROID="$ROOT/AndroidApp/app/src/main/java/com/peri/display"

# 세션 계층 = 신원·키·경로·생존. 여기 있는 파일은 어느 앱이 떠 있든 의미가 같아야 한다.
#
# ★ **양쪽을 다 검사한다.** 원칙은 프로토콜의 성질이지 한쪽 구현의 취향이 아니다.
# Android 쪽은 오래도록 세 앱의 절반이 `MirrorController`에 뭉쳐 있어서 이 검사를
# 통과할 수 없었고, 그래서 목록에 없었다 — 없으니 더 뭉쳤다.
#
# 두 쪽 모두 앱 어휘가 도달하는 마지막 지점은 채널이다(`ServiceChannel.swift` /
# `apps/SessionChannel.kt`). 거기서 봉인·조각화가 끝나므로 keyframe·codec을 알아야
# 하고, 그래서 목록에 넣지 않는다. 그 위(세션)와 아래(전송)가 모르면 된다.
SESSION_FILES=(
  "$SRC/App/MirrorSession.swift"
  "$SRC/App/DeviceMachine.swift"
  "$DROID/vm/MirrorController.kt"
  "$DROID/vm/MirrorMachine.kt"
  "$DROID/vm/SessionCrypto.kt"
  "$DROID/transport/PathNegotiator.kt"
)

# 서비스(앱) 어휘. 하나라도 나오면 그 앱의 사정이 세션으로 새어 나온 것이다.
#
# `video`가 목록에 없는 건 의도적이다 — 와이어 타입 이름이 `WireType.video`라
# 세션이 그 단어를 쓰는 게 맞다. 프레임을 **어떻게 다루는지**가 아니라 프레임이
# 존재한다는 사실까지는 전송의 어휘다.
PATTERN='DisplayManager|VirtualDisplay|CGVirtualDisplay|ScreenCaptur|VideoEncoder|VideoDecoder|MediaProjection|bitrate|keyframe|codec|hdr10|FileTransfer'

fail=0
for f in "${SESSION_FILES[@]}"; do
  name="$(basename "$f")"
  if [ ! -f "$f" ]; then
    echo "⚠️  없음: $name (파일이 옮겨졌나?)"
    fail=1
    continue
  fi
  # 주석을 지운다. 줄 주석(`//`)만 지우면 `/** … */` **본문 줄**이 남아서, 층이 왜
  # 이렇게 갈렸는지 설명하는 문단이 위반으로 잡힌다. 블록 상태를 들고 돌아야 한다.
  hits="$(awk '
    { line = $0 }
    inblock {
      if (line ~ /\*\//) { inblock = 0 }
      next
    }
    line ~ /^[[:space:]]*\/\*/ {
      if (line !~ /\*\//) inblock = 1
      next
    }
    line ~ /^[[:space:]]*\/\// { next }
    { print NR ":" line }
  ' "$f" | grep -Ei "$PATTERN" || true)"
  if [ -z "$hits" ]; then
    echo "✅ $name"
  else
    echo "❌ $name — 서비스 어휘가 코드에 있다:"
    echo "$hits" | sed 's/^/     /'
    fail=1
  fi
done

echo
if [ "$fail" -eq 0 ]; then
  echo "원칙 1 통과 — 양쪽 세션 계층 모두 앱을 모른다."
else
  echo "원칙 1 위반. 그 코드는 해당 앱의 파일(Mac: Services/, Android: apps/)로 내려가야 한다."
fi
exit "$fail"
