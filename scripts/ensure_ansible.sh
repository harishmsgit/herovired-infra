#!/usr/bin/env bash

set -euo pipefail

if command -v ansible >/dev/null 2>&1; then
  exit 0
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required to bootstrap ansible" >&2
  exit 1
fi

python3 -m pip install --user --upgrade pip >/dev/null 2>&1 || true
python3 -m pip install --user ansible-core >/dev/null

export PATH="$HOME/.local/bin:$PATH"

if ! command -v ansible >/dev/null 2>&1; then
  echo "ansible installation did not produce an ansible executable in PATH" >&2
  exit 1
fi