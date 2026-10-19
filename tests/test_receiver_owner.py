"""Real Linux processes, flock FDs and /proc. No phone or private inbox is touched."""
import concurrent.futures
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import time
import unittest

BASE=Path(__file__).resolve().parents[1]
SCRIPT=BASE/'app/src/main/assets/taildrop-owner.sh'
CLI=BASE/'tests/tailscale'

class OwnerTests(unittest.TestCase):
    def setUp(self):
        self.tmp=Path(tempfile.mkdtemp(prefix='tc-owner-'))
        self.root=self.tmp/'root'
        self.dest=self.tmp/'dest'
        self.dest.mkdir()
        self.env=dict(os.environ, TC_SH='/bin/sh', TC_FIXTURE=str(self.tmp))
        (self.tmp/'mode').write_text('normal')
        self.extra=[]
    def call(self,action,*args):
        p=subprocess.run(['/bin/sh',str(SCRIPT),action,str(self.root),str(CLI),*map(str,args)],
             env=self.env,text=True,capture_output=True,timeout=12)
        return p.stdout.strip(),p.stderr,p.returncode
    def start(self,token='one',directory=None):
        return self.call('start',token,directory or self.dest,'rename')
    def status(self):
        return self.call('status')[0].split('\t')
    def until(self,predicate,timeout=10):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            result=predicate()
            if result:return result
            time.sleep(.1)
        self.fail('Timed out: '+str(self.call('status'))+'\n'+self.logs())
    def logs(self):
        return '\n'.join(p.read_text(errors='replace') for p in self.root.glob('*.log'))
    def session(self):return self.root/('session-'+(self.root/'current').read_text())
    def wait_running(self):
        self.until(lambda:self.status()[1]=='RUNNING')
    def stop(self):
        status=self.status()
        if len(status)>=3:self.call('stop',status[2])
        return self.until(lambda:self.status()[1] in ('STOPPED','PAUSED'))
    def tearDown(self):
        # Only test-fixture processes: immutable temporary-directory identities and executable.
        try:
            if self.root.exists():
                current=self.status()
                if len(current)>=3:self.call('stop',current[2])
                deadline=time.monotonic()+8
                while time.monotonic()<deadline:
                    s=self.status()
                    if s[1] in ('STOPPED','PAUSED'):break
                    time.sleep(.1)
                for record in self.root.glob('session-*/*'):
                    if record.name not in ('supervisor','child'):continue
                    fields=record.read_text().split()
                    if len(fields)==5:
                        pid=int(fields[1])
                        try:
                            cmd=Path(f'/proc/{pid}/cmdline').read_bytes()
                            if str(SCRIPT).encode() in cmd or str(CLI).encode() in cmd:os.kill(pid,signal.SIGKILL)
                        except (OSError,ValueError):pass
        finally:
            for proc in self.extra:
                if proc.poll() is None:proc.kill()
                proc.wait()
            shutil.rmtree(self.tmp,ignore_errors=True)
    def test_missing_current_record_with_held_lock_is_unknown(self):
        (self.tmp/'mode').write_text('slow')
        self.start(); self.wait_running()
        (self.root/'current').rename(self.root/'current.saved')
        self.assertEqual(self.status()[1],'UNKNOWN')
        self.assertEqual(self.start('other')[0].split('\t')[1],'UNKNOWN')
        (self.root/'current.saved').rename(self.root/'current')
    def test_running_receiver_cannot_be_acknowledged(self):
        self.start(); self.wait_running()
        self.call('acknowledge','one')
        self.assertEqual(self.status()[1],'RUNNING')
    def test_locked_inbox_inode_is_not_replaced(self):
        self.start(); self.wait_running()
        before=(self.root/'inbox.lock').stat().st_ino
        self.stop(); self.start('two'); self.wait_running()
        self.assertEqual((self.root/'inbox.lock').stat().st_ino,before)
    def test_failed_start_record_is_not_kept_alive_by_repeated_requests(self):
        self.call('status')
        session=self.root/'session-never-launched'; session.mkdir()
        (self.root/'current').write_text('never-launched')
        (session/'directory').write_text(str(self.dest))
        (session/'policy').write_text('rename')
        (session/'state').write_text('STARTING')
        uptime=int(float(Path('/proc/uptime').read_text().split()[0]))
        (session/'lease').write_text(str(uptime+3))
        before=(session/'lease').read_text()
        self.start('retry')
        self.assertEqual((session/'lease').read_text(),before)
        (session/'lease').write_text('0')
        self.assertEqual(self.status()[1],'STOPPED')
    def test_closed_start_response_preserves_root_owner(self):
        proc=subprocess.Popen(['/bin/sh',str(SCRIPT),'start',str(self.root),str(CLI),'one',str(self.dest),'rename'],
            env=self.env,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
        proc.stdout.close()
        proc.wait(timeout=10)
        self.wait_running()
        self.assertEqual(self.start('again')[0].split('\t')[2],'one')
    def test_unmanaged_one_shot_blocks_but_is_not_selected_for_cleanup(self):
        (self.tmp/'mode').write_text('slow')
        proc=subprocess.Popen([str(CLI),'file','get','--verbose',str(self.dest)],env=self.env,
            stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        self.extra.append(proc);time.sleep(.1)
        self.assertEqual(self.start()[0].split('\t')[1],'BLOCKED')
        self.assertFalse(self.call('legacy-list')[0])
        self.assertIsNone(proc.poll())
    def test_four_unlocked_consumers_can_copy_one_item_without_delete_error(self):
        # A mechanism reproduction with the fixture, not a claim to replay the private device capture.
        (self.tmp/'mode').write_text('barrier')
        (self.tmp/'inbox').write_bytes(b'one inbox item')
        for _ in range(4):
            proc=subprocess.Popen([str(CLI),'file','get','--verbose',str(self.dest)],env=self.env,
                stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            self.extra.append(proc)
        deadline=time.monotonic()+5
        while len(list(self.tmp.glob('opened-*')))<4 and time.monotonic()<deadline:time.sleep(.01)
        self.assertEqual(len(list(self.tmp.glob('opened-*'))),4)
        (self.tmp/'go').touch()
        for proc in self.extra:self.assertEqual(proc.wait(timeout=5),0)
        copies=list(self.dest.glob('receipt-*'))
        self.assertEqual(len(copies),4)
        self.assertTrue(all(p.read_bytes()==b'one inbox item' for p in copies))
    def test_parallel_starts_share_one_session_and_one_inbox(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(lambda i:self.start(f'req-{i}'),range(8)))
        self.wait_running()
        token=self.status()[2]
        for i in range(4):
            s=self.start(f'follow-{i}')[0].split('\t')
            self.assertEqual(s[2],token)
        self.assertEqual(len(list(self.root.glob('session-*'))),1)
    def test_lost_response_does_not_lose_owner(self):
        self.start()
        self.wait_running()
        owner=(self.session()/'supervisor').read_text()
        self.start('second')
        self.assertEqual((self.session()/'supervisor').read_text(),owner)
    def test_one_send_one_file_with_repeated_start(self):
        self.start();self.wait_running()
        (self.tmp/'inbox').write_bytes(b'one transfer')
        for _ in range(5):self.start('ignored')
        self.until(lambda:len(list(self.dest.glob('receipt-*')))==1)
        time.sleep(2)
        self.assertEqual(len(list(self.dest.glob('receipt-*'))),1)
    def test_two_real_sends_are_not_hash_deduplicated(self):
        self.start();self.wait_running()
        (self.tmp/'inbox').write_bytes(b'same data')
        self.until(lambda:len(list(self.dest.glob('receipt-*')))==1)
        self.until(lambda:not (self.tmp/'inbox').exists())
        (self.tmp/'inbox').write_bytes(b'same data')
        self.until(lambda:len(list(self.dest.glob('receipt-*')))==2)
    def test_delete_failure_pauses_instead_of_replaying(self):
        (self.tmp/'mode').write_text('delete-fail')
        (self.tmp/'inbox').write_bytes(b'read succeeds delete fails')
        self.start()
        self.until(lambda:self.status()[1]=='PAUSED')
        for _ in range(3):
            self.assertEqual(self.start('retry')[0].split('\t')[1],'PAUSED')
        time.sleep(1)
        self.assertEqual(len(list(self.dest.glob('receipt-*'))),1)
        self.assertTrue((self.tmp/'inbox').exists())
        self.assertIn('deleting fixture',self.logs())
    def test_stop_is_complete_before_replacement(self):
        self.start();self.wait_running();self.stop()
        self.start('replacement');self.wait_running()
        self.assertEqual(self.status()[2],'replacement')
        self.call('stop','one')
        self.assertEqual(self.status()[2],'replacement')
        self.assertEqual(self.status()[1],'RUNNING')
    def test_different_directories_share_lock(self):
        self.start();self.wait_running()
        other=self.tmp/'other';other.mkdir()
        s=self.start('other',other)[0].split('\t')
        self.assertIn(s[1],('STOPPING','STOPPED'))
        self.assertEqual(len(list(self.root.glob('session-*'))),1)
        self.until(lambda:self.status()[1]=='STOPPED')
        self.start('other',other);self.wait_running()
        self.assertEqual(self.status()[2],'other')
    def test_legacy_receivers_block_start_and_targeted_cleanup(self):
        proc=subprocess.Popen([str(CLI),'file','get','--conflict=rename','--loop',str(self.dest)],env=self.env,
             stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        self.extra.append(proc);time.sleep(.1)
        s=self.start()[0].split('\n')[0].split('\t')
        self.assertEqual(s[1],'BLOCKED')
        rows=self.call('legacy-list')[0].splitlines()
        row=next(x for x in rows if x.split('\t')[2]==str(proc.pid)).split('\t')
        self.assertEqual(self.call('legacy-stop',*row[1:])[2],0)
        proc.wait(timeout=2)
        self.start();self.wait_running()
    def test_cleanup_rejects_changed_identity_and_ignores_ping(self):
        proc=subprocess.Popen([str(CLI),'file','get','--conflict=rename','--loop',str(self.dest)],env=self.env,
             stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        ping=subprocess.Popen([str(CLI),'ping','host'],env=self.env)
        self.extra.extend((proc,ping));time.sleep(.1)
        rows=self.call('legacy-list')[0].splitlines()
        self.assertFalse(any(x.split('\t')[2]==str(ping.pid) for x in rows))
        row=next(x for x in rows if x.split('\t')[2]==str(proc.pid)).split('\t')
        row[3]='0'
        self.assertNotEqual(self.call('legacy-stop',*row[1:])[2],0)
        self.assertIsNone(proc.poll());self.assertIsNone(ping.poll())
    def test_special_directory_is_literal(self):
        special=self.tmp/"space ' \" $dollar 中文\ntrailing\n"
        self.start(directory=special);self.wait_running()
        (self.tmp/'inbox').write_bytes(b'literal path')
        self.until(lambda:(special/'receipt-0').exists())
        self.assertEqual((special/'receipt-0').read_bytes(),b'literal path')
    def test_app_lease_expiry_stops_without_relaunch(self):
        self.start();self.wait_running()
        (self.session()/'lease').write_text('0')
        self.until(lambda:self.status()[1] in ('STOPPED','PAUSED'))
        count=len((self.tmp/'calls').read_text().splitlines()) if (self.tmp/'calls').exists() else 0
        time.sleep(1)
        new=len((self.tmp/'calls').read_text().splitlines()) if (self.tmp/'calls').exists() else 0
        self.assertEqual(count,new)
    def test_supervisor_crash_child_keeps_lock_and_pending(self):
        (self.tmp/'mode').write_text('slow')
        self.start();self.wait_running()
        self.until(lambda:(self.session()/'child').exists())
        child=int((self.session()/'child').read_text().split()[1])
        self.until(lambda:Path(f'/proc/{child}/exe').resolve()==CLI.resolve())
        pid=int((self.session()/'supervisor').read_text().split()[1]);os.kill(pid,signal.SIGKILL)
        self.until(lambda:self.status()[1]=='UNKNOWN')
        self.assertEqual(self.start('new')[0].split('\t')[1],'UNKNOWN')
        self.assertTrue(Path(f'/proc/{child}/fd/8').exists())
        self.call('stop','one')
        self.until(lambda:self.status()[1]=='PAUSED')
        self.assertEqual(self.start('new')[0].split('\t')[1],'PAUSED')
    def test_missing_identity_is_unknown_not_dead(self):
        (self.tmp/'mode').write_text('slow')
        self.start();self.wait_running()
        self.until(lambda:(self.session()/'child').exists())
        (self.session()/'supervisor').write_text('broken')
        self.assertEqual(self.status()[1],'UNKNOWN')
        self.assertEqual(self.start('duplicate')[0].split('\t')[1],'UNKNOWN')
    def test_child_without_inherited_lock_fails_closed(self):
        (self.tmp/'mode').write_text('close-lock')
        self.start()
        self.until(lambda:self.status()[1]=='PAUSED')
        self.assertEqual(self.status()[4],'lock-fd-not-inherited')
    def test_acknowledge_requires_matching_paused_generation(self):
        (self.tmp/'mode').write_text('fail')
        self.start();self.until(lambda:self.status()[1]=='PAUSED')
        self.call('acknowledge','wrong')
        self.assertEqual(self.status()[1],'PAUSED')
        self.call('acknowledge','one')
        self.assertEqual(self.status()[1],'STOPPED')
        (self.tmp/'mode').write_text('normal')
        self.start('two');self.wait_running()

if __name__=='__main__':unittest.main(verbosity=2)
