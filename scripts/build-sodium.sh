#!/usr/bin/env bash
#
# libsodium 정적 라이브러리 빌드 — spake2-ee(BLE 페어링 PAKE)가 이걸 쓴다.
#
#   ./scripts/build-sodium.sh mac       macOS arm64
#   ./scripts/build-sodium.sh android   arm64-v8a + x86_64 (NDK)
#   ./scripts/build-sodium.sh all
#
# 결과물:
#   ThirdParty/sodium/include/sodium.h …        (헤더는 플랫폼 공통)
#   ThirdParty/sodium/macos/libsodium.a         (arm64 + x86_64 유니버설)
#   ThirdParty/sodium/android-<abi>/libsodium.a
#
# 왜 소스 빌드인가: 저장소에 바이너리를 넣지 않으려고. brew의 .a를 쓰면 이 맥에서만
# 빌드되고, 미리 빌드한 .a를 커밋하면 갱신이 수동이 된다. 버전은 여기 한 곳에 박혀 있고
# 체크섬으로 확인하므로 "언젠가 다른 libsodium이 섞였다"가 생기지 않는다.
#
set -euo pipefail

VERSION="1.0.22"
SHA256="adbdd8f16149e81ac6078a03aca6fc03b592b89ef7b5ed83841c086191be3349"
URL="https://download.libsodium.org/libsodium/releases/libsodium-${VERSION}.tar.gz"

# NDK r27 = 최소 지원. 프로젝트의 minSdk와 맞춘다.
ANDROID_API=29
ANDROID_ABIS=(arm64-v8a x86_64)

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/ThirdParty/sodium"
WORK="$OUT/.build"

log() { printf '\033[36m▸\033[0m %s\n' "$*"; }
die() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

# ── 소스 준비 ────────────────────────────────────────────────────────────────
fetch() {
  local tarball="$WORK/libsodium-${VERSION}.tar.gz"
  mkdir -p "$WORK"
  if [[ ! -f "$tarball" ]]; then
    log "내려받는 중 libsodium ${VERSION}"
    curl -fsSL "$URL" -o "$tarball"
  fi
  local got
  got="$(shasum -a 256 "$tarball" | cut -d' ' -f1)"
  [[ "$got" == "$SHA256" ]] || die "체크섬 불일치 (기대 $SHA256, 실제 $got)"
  if [[ ! -d "$WORK/libsodium-${VERSION}" ]]; then
    log "푸는 중"
    tar -xzf "$tarball" -C "$WORK"
  fi
  SRC="$WORK/libsodium-${VERSION}"
}

# 헤더는 한 번만 설치한다 — 플랫폼별로 같다.
install_headers() {
  local from="$1"
  mkdir -p "$OUT/include"
  rsync -a --delete "$from/" "$OUT/include/"
}

# ── macOS ───────────────────────────────────────────────────────────────────
# **유니버설로 만든다.** arm64만 내면 Release(ONLY_ACTIVE_ARCH=NO)에서 x86_64 슬라이스를
# 못 찾아 링크가 깨지고, 그걸 피하려면 Xcode에 ARCHS=arm64를 따로 박아야 한다. 빌드 1분을
# 더 쓰고 설정 하나를 지우는 쪽이 낫다.
build_mac() {
  fetch
  local slices=()
  for arch in arm64 x86_64; do
    local host prefix
    case "$arch" in
      arm64)  host="aarch64-apple-darwin" ;;
      x86_64) host="x86_64-apple-darwin" ;;
    esac
    prefix="$WORK/out-macos-$arch"
    if [[ ! -f "$prefix/lib/libsodium.a" ]]; then
      log "빌드 macOS $arch"
      rm -rf "$WORK/b-mac-$arch"; mkdir -p "$WORK/b-mac-$arch"
      (
        export CFLAGS="-arch $arch -mmacosx-version-min=13.0 -O2"
        export LDFLAGS="-arch $arch"
        cd "$WORK/b-mac-$arch"
        "$SRC/configure" --host="$host" \
                         --prefix="$prefix" \
                         --enable-static --disable-shared \
                         >/dev/null
        make -j"$(sysctl -n hw.ncpu)" >/dev/null
        make install >/dev/null
      )
    fi
    slices+=("$prefix/lib/libsodium.a")
    install_headers "$prefix/include"
  done
  mkdir -p "$OUT/macos"
  lipo -create "${slices[@]}" -output "$OUT/macos/libsodium.a"
  log "✓ ThirdParty/sodium/macos/libsodium.a ($(lipo -archs "$OUT/macos/libsodium.a"))"
}

# ── Android ─────────────────────────────────────────────────────────────────
ndk_root() {
  local base="${ANDROID_NDK_HOME:-}"
  [[ -n "$base" && -d "$base" ]] && { echo "$base"; return; }
  base="${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk"
  [[ -d "$base" ]] || die "NDK를 못 찾았다 (ANDROID_NDK_HOME 설정)"
  # 설치된 것 중 가장 높은 버전.
  echo "$base/$(ls "$base" | sort -V | tail -1)"
}

build_android() {
  fetch
  local ndk toolchain host_tag
  ndk="$(ndk_root)"
  host_tag="darwin-x86_64"   # NDK는 Apple Silicon에서도 이 이름으로 배포된다(Rosetta 아님, universal)
  toolchain="$ndk/toolchains/llvm/prebuilt/$host_tag"
  [[ -d "$toolchain" ]] || die "NDK 툴체인 없음: $toolchain"
  log "NDK: $ndk"

  for abi in "${ANDROID_ABIS[@]}"; do
    local triple
    case "$abi" in
      arm64-v8a) triple="aarch64-linux-android" ;;
      x86_64)    triple="x86_64-linux-android" ;;
      *) die "모르는 ABI: $abi" ;;
    esac

    local prefix="$WORK/out-android-$abi"
    if [[ ! -f "$prefix/lib/libsodium.a" ]]; then
      log "빌드 Android $abi (API $ANDROID_API)"
      rm -rf "$WORK/b-$abi"; mkdir -p "$WORK/b-$abi"
      (
        # ★ export여야 한다. `VAR=... configure` 꼴로 주면 configure에만 붙고 make가
        # 부르는 libtool은 그 PATH를 못 봐서 "clang: command not found"로 죽는다.
        export PATH="$toolchain/bin:$PATH"
        export CC="$toolchain/bin/${triple}${ANDROID_API}-clang"
        export AR="$toolchain/bin/llvm-ar"
        export RANLIB="$toolchain/bin/llvm-ranlib"
        export STRIP="$toolchain/bin/llvm-strip"
        export CFLAGS="-O2 -fPIC -fvisibility=hidden"
        cd "$WORK/b-$abi"
        "$SRC/configure" --host="$triple" \
                         --prefix="$prefix" \
                         --enable-static --disable-shared \
                         --with-pic \
                         >/dev/null
        make -j"$(sysctl -n hw.ncpu)" >/dev/null
        make install >/dev/null
      )
    fi
    mkdir -p "$OUT/android-$abi"
    cp "$prefix/lib/libsodium.a" "$OUT/android-$abi/libsodium.a"
    install_headers "$prefix/include"
    log "✓ ThirdParty/sodium/android-$abi/libsodium.a"
  done
}

case "${1:-all}" in
  mac)     build_mac ;;
  android) build_android ;;
  all)     build_mac; build_android ;;
  *) die "쓰임: $0 [mac|android|all]" ;;
esac
