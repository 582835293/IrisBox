#!/usr/bin/env bash
# Reuse the existing screencap build flags; compile this one source only.
set -euo pipefail
SOURCE_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
AOSP_DIR=${AOSP_DIR:-/path/to/aosp-rpi4}
python3 "$SOURCE_DIR/build_direct.py" "$AOSP_DIR" "$SOURCE_DIR"
