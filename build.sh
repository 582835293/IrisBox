#!/usr/bin/env sh
# Cross-platform POSIX shell entrypoint for Linux and macOS (and Git Bash/WSL).
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)

is_python39() {
    "$1" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 9) else 1)' >/dev/null 2>&1
}

PYTHON_BIN=${PYTHON:-}
if [ -n "$PYTHON_BIN" ]; then
    if ! is_python39 "$PYTHON_BIN"; then
        echo "Error: PYTHON=$PYTHON_BIN is not runnable Python 3.9+." >&2
        exit 1
    fi
else
    for cand in python3 python; do
        if command -v "$cand" >/dev/null 2>&1 && is_python39 "$cand"; then
            PYTHON_BIN=$cand
            break
        fi
    done
fi

if [ -z "$PYTHON_BIN" ]; then
    echo "Error: Python 3.9+ is required to build IrisBox." >&2
    exit 1
fi

exec "$PYTHON_BIN" "$SCRIPT_DIR/tools/build_all.py" "$@"
