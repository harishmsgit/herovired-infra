#!/usr/bin/env bash

set -euo pipefail

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required to bootstrap ansible" >&2
  exit 1
fi

# Debian's system Python is externally managed (PEP 668), so installing with
# pip --user is not reliable. Keep Ansible in a persistent virtual environment
# instead; it avoids changing the system Python and is reused by later builds.
ANSIBLE_VENV_DIR="${ANSIBLE_VENV_DIR:-$HOME/.cache/herovired-ansible}"

if [ ! -x "$ANSIBLE_VENV_DIR/bin/ansible" ]; then
  mkdir -p "$(dirname "$ANSIBLE_VENV_DIR")"
  python3 -m venv "$ANSIBLE_VENV_DIR"
  "$ANSIBLE_VENV_DIR/bin/python" -m pip install --upgrade pip ansible-core >/dev/null
fi

export PATH="$ANSIBLE_VENV_DIR/bin:$PATH"

if ! command -v ansible >/dev/null 2>&1; then
  echo "Ansible virtual environment did not produce an ansible executable in PATH" >&2
  exit 1
fi
