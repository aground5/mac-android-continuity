#!/usr/bin/env bash
#
# Generates Swift code from the shared proto into the Mac app.
# Single source of truth: /proto/continuity.proto
#
# Requires: protoc, protoc-gen-swift  (brew install protobuf swift-protobuf)
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROTO_DIR="$ROOT/proto"
OUT_DIR="$ROOT/MacApp/Peri Display/Peri Display/Generated"

command -v protoc >/dev/null        || { echo "error: protoc not found (brew install protobuf)"; exit 1; }
command -v protoc-gen-swift >/dev/null || { echo "error: protoc-gen-swift not found (brew install swift-protobuf)"; exit 1; }

mkdir -p "$OUT_DIR"

protoc \
  --proto_path="$PROTO_DIR" \
  --swift_out="$OUT_DIR" \
  --swift_opt=Visibility=Public \
  "$PROTO_DIR"/*.proto

echo "✓ Swift proto generated → ${OUT_DIR#"$ROOT"/}"
ls -1 "$OUT_DIR"/*.swift | sed "s|$OUT_DIR/|  |"
