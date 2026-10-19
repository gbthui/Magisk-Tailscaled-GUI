"""Descriptor-boundary tests using the production lock_fd, real tools and flock syscalls.
Run once per shell via TC_TEST_SH. Host mksh is not a substitute for Android CI.
"""
import fcntl
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import tempfile
import unittest

BASE = Path(__file__).resolve().parents[1]
SHELL = os.environ.get('TC_TEST_SH', '/bin/sh')
SOURCE = (BASE / 'app/src/main/assets/taildrop-owner.sh').read_text()
FUNCTION = re.search(r'^lock_fd\(\) \{\n.*?^\}', SOURCE, re.M | re.S).group()


class LockFdTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='tc-lock-fds-')
        self.lock = Path(self.tmp.name) / 'test.lock'
        self.tool = shutil.which('flock')
        self.assertIsNotNone(self.tool)

    def tearDown(self):
        self.tmp.cleanup()

    def held(self):
        with self.lock.open('a') as file:
            try:
                fcntl.flock(file, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                return True
            return False

    def holder(self, fd, tool, applet):
        # FD is a fixed integer supplied by the test, never untrusted shell syntax.
        command = FUNCTION + f'''\nFLOCK_TOOL=$1; FLOCK_APPLET=$2
exec {fd}>>"$3"
lock_fd {fd} || exit 40
printf 'READY\\n'
IFS= read -r release
'''
        return subprocess.Popen([SHELL, '-c', command, 'fd-test', tool, str(applet).lower(), str(self.lock)],
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

    def assert_tool(self, tool, applet):
        for fd in (6, 7, 8, 9):
            with self.subTest(fd=fd, tool=tool):
                process = self.holder(fd, tool, applet)
                try:
                    ready = process.stdout.readline().strip()
                    self.assertEqual(ready, 'READY')
                    # The external flock command already exited. Its shell still owns the OFD.
                    self.assertTrue(self.held())
                    process.stdin.write('release\n'); process.stdin.flush()
                    process.wait(timeout=3)
                    self.assertEqual(process.returncode, 0)
                    self.assertFalse(self.held())
                finally:
                    if process.poll() is None: process.kill()
                    process.communicate(timeout=3)

    def test_fds_6_7_8_9_with_standalone_flock(self):
        self.assert_tool(self.tool, False)

    def test_fds_6_7_8_9_with_busybox_applet(self):
        tool = shutil.which('busybox')
        if not tool or 'flock' not in subprocess.check_output([tool, '--list'], text=True).split():
            self.skipTest('This host BusyBox was built without the flock applet')
        self.assert_tool(tool, True)

    def test_fds_6_7_8_9_with_toybox_applet(self):
        tool = shutil.which('toybox')
        if not tool: self.skipTest('Toybox not installed on this host')
        self.assert_tool(tool, True)

    def test_busybox_argument_contract_with_inheriting_script_adapter(self):
        # A script executed by the selected shell preserves explicit descriptors as an applet
        # wrapper would. This checks argv and the common redirection branch, not BusyBox itself.
        adapter = Path(self.tmp.name) / 'busybox-adapter'
        adapter.write_text('#!' + SHELL + '\n[ "$1" = flock ] || exit 64\nshift\nexec "' + self.tool + '" "$@"\n')
        adapter.chmod(0o700)
        self.assert_tool(str(adapter), True)

    def test_mksh_old_unguarded_call_and_function_redirection_both_fail(self):
        version = subprocess.check_output([SHELL, '-c', 'printf "%s" "${KSH_VERSION:-}"'], text=True)
        if 'MIRBSD KSH' not in version: self.skipTest('negative control requires actual mksh')
        for command in ('exec 7>>"$1"; "$2" -n 7',
                        'plain_lock() { "$2" -n "$1"; }; exec 7>>"$1"; plain_lock 7 "$2" 7>&7'):
            with self.subTest(command=command):
                result = subprocess.run([SHELL, '-c', command, 'negative', str(self.lock), self.tool],
                                        text=True, capture_output=True, timeout=3)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('Bad file descriptor', result.stderr)

    def test_shell_to_shell_to_native_child_holds_lock_after_owner_exit(self):
        command = FUNCTION + '''
FLOCK_TOOL=$1; FLOCK_APPLET=false
exec 8>>"$2"
lock_fd 8 || exit 40
"$3" -c 'exec "$1" 60 8>&8' native-child "$4" 8>&8 9>&- &
child=$!
printf '%s\\n' "$child"
wait "$child"
'''
        process = subprocess.Popen([SHELL, '-c', command, 'inherit', self.tool, str(self.lock),
                                    SHELL, shutil.which('sleep')], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        child = None
        try:
            child = int(process.stdout.readline().strip())
            self.assertTrue(self.held())
            process.kill(); process.wait(timeout=3)
            self.assertTrue(Path(f'/proc/{child}/fd/8').exists())
            self.assertEqual(Path(f'/proc/{child}/fd/8').stat().st_ino, self.lock.stat().st_ino)
            self.assertTrue(self.held(), 'lock was released while native child was alive')
            os.kill(child, signal.SIGKILL)
            # The kernel closes descriptors during exit; wait for that, not an arbitrary sleep.
            import time
            deadline = time.monotonic() + 3
            while self.held() and time.monotonic() < deadline: time.sleep(.01)
            self.assertFalse(self.held())
        finally:
            if process.poll() is None: process.kill()
            if child:
                try: os.kill(child, signal.SIGKILL)
                except ProcessLookupError: pass
            process.communicate(timeout=3)

    def test_invalid_fd_is_rejected(self):
        command = FUNCTION + '\nFLOCK_TOOL=$1; FLOCK_APPLET=false; lock_fd 5'
        result = subprocess.run([SHELL, '-c', command, 'invalid', self.tool], capture_output=True, text=True, timeout=3)
        self.assertEqual(result.returncode, 64)
        self.assertIn('Unsupported lock FD', result.stderr)


if __name__ == '__main__':
    print('FD test shell:', SHELL, flush=True)
    unittest.main(verbosity=2)
