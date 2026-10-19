#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export TC_TEST_SH="${TC_TEST_SH:-/bin/sh}"
"$TC_TEST_SH" -c 'printf "Receiver test shell: %s\n" "${KSH_VERSION:-POSIX /bin/sh}"'
gcc -Wall -Wextra -Werror -O2 tests/fake-tailscale.c -o tests/tailscale
python3 tests/test_lock_fds.py
python3 tests/test_receiver_owner.py
