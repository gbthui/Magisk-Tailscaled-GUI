#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
gcc -Wall -Wextra -Werror -O2 tests/fake-tailscale.c -o tests/tailscale
python3 tests/test_receiver_owner.py
